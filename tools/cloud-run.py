#!/usr/bin/env python3
"""Run a sweep (every pack, the whole port corpus, every convert) or a port's game proof on GitHub's runners, wait, and download it.

    python tools/cloud-run.py packs [--ref BRANCH] [-f packs=atm10-neoforge-1.21.1] [-f focus=MODID] [-f fix=true]
    python tools/cloud-run.py ports [-f cases=jade-neoforge-1.21.1-1.21.6]
    python tools/cloud-run.py convert [-f mods=theoneprobe-fabric] [-f label=round-4]
    python tools/cloud-run.py exposure [-f only=fabric:1.21.1,neoforge:1.21.1]
    python tools/cloud-run.py load -f updates=neoforge:1.21.1:1.21.2,fabric:1.21.1 [-f mods=chunky,clumps]
    python tools/cloud-run.py source [-f cases=jei,create] [-f cases=prove:neoforge:jei:1.21.4]
    python tools/cloud-run.py --run RUN_ID        wait for and download a run already started
    tools/cloud-run ... (POSIX) and tools\\cloud-run.cmd ... (Windows) are the same

packs is .github/workflows/cloud-packs.yml (pack-check, one job per pack), ports is cloud-ports.yml (the port board,
one job per case), convert is
cloud-convert.yml (the convert sweep, one job per mod, in the game and back), exposure is cloud-exposure.yml (the jar
scoreboard, one job per update and loader, joined into one table; main's is kept on the generated branch), source is
cloud-ports.yml with corpus=source (the same mods ported from their source to every release, bench/source_ports.py;
cases=prove:LOADER:SLUG:TO runs one source port's game proof instead; a whole run on main, --ref main, is kept on the
generated branch); any other
name is taken as a workflow file. The workflow runs the commit the branch (--ref, default the current one) has on
GitHub, so push first: a branch whose local commit is not on origin is refused (--allow-unpushed runs origin's).

It dispatches with a request id the workflow puts in its run name, finds that run, waits up to --timeout minutes
(default 120; the run is never cancelled, and --run RUN_ID picks it up again), prints each job's result and time, and
downloads every artifact to .runs/cloud/WORKFLOW-RUNID/ (--out). A summary artifact's summary.md is printed.
Exit 0 when the run succeeded, 1 when it failed, 124 when the wait ended first. Needs the GitHub CLI (gh), logged in.

exposure is the whole jar scoreboard, for a person or the coordinator to start: a pull request gets port-check.yml, the
ports its change can move, on its own events. Run on main it is main's table (generated.yml records it, as it does for
each release's); run on a branch it is compared with main's recorded table (python bench/exposure.py compare). One
failed job is re-run alone (gh run rerun RUN --failed), not with a new sweep.

load is cloud-load.yml, the game proof of a port (python bench/exposure.py load), one job per update: the update's mods
that link (or only mods=), ported and started on a real server and in a real client of the new release. Each update's
summary is copied to .runs/cloud/load-RUNID/ in the shape load writes, and compared with the receipt main keeps for that
update (bench/exposure/load-*.json, the one main changed last) into compare.md.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WORKFLOWS = {"packs": "cloud-packs.yml", "ports": "cloud-ports.yml", "convert": "cloud-convert.yml", "exposure": "cloud-exposure.yml",
             "load": "cloud-load.yml", "source": "cloud-ports.yml"}
# Names that are a workflow run with fixed inputs: the source scoreboard is cloud-ports.yml's third corpus.
PRESET = {"source": ["corpus=source"]}


def gh_path():
    found = os.environ.get("GH") or shutil.which("gh")
    if found:
        return found
    for candidate in (r"C:\Program Files\GitHub CLI\gh.exe", "/usr/local/bin/gh", "/opt/homebrew/bin/gh"):
        if Path(candidate).is_file():
            return candidate
    raise SystemExit("cloud-run needs the GitHub CLI (gh): https://cli.github.com, then gh auth login")


TRANSIENT = ("HTTP 500", "HTTP 502", "HTTP 503", "HTTP 504", "timeout", "connection reset", "TLS handshake", "EOF")


def transient(output):
    """A GitHub hiccup worth retrying (a 5xx, a dropped connection), not an answer: a sweep hours long must not end on one."""
    return any(sign.lower() in (output or "").lower() for sign in TRANSIENT)


def gh(*args, check=True, tries=6, run=subprocess.run, sleep=time.sleep):
    for attempt in range(tries):
        done = run([gh_path(), *args], cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
        if not done.returncode or not transient(done.stderr or done.stdout) or attempt == tries - 1:
            break
        sleep(min(60, 5 * 2 ** attempt))
    if check and done.returncode:
        raise SystemExit(f"gh {' '.join(args[:3])} failed: {(done.stderr or done.stdout).strip()[-800:]}")
    return done.stdout


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True).stdout.strip()


def ref_on_origin(ref, allow_unpushed):
    """The commit the workflow will run; refuses a branch whose local commit GitHub does not have."""
    remote = git("ls-remote", "origin", f"refs/heads/{ref}").split()
    if not remote:
        raise SystemExit(f"origin has no branch {ref}: push it first (git push -u origin {ref})")
    local = git("rev-parse", "HEAD") if git("rev-parse", "--abbrev-ref", "HEAD") == ref else remote[0]
    if local != remote[0] and not allow_unpushed:
        raise SystemExit(f"{ref} is {local[:10]} here but {remote[0][:10]} on origin: push first, or pass --allow-unpushed to run origin's")
    return remote[0]


def find_run(workflow, request, started, timeout=180):
    """The run whose name carries this request id; GitHub lists a dispatched run a few seconds after the dispatch."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        runs = json.loads(gh("run", "list", "--workflow", workflow, "--event", "workflow_dispatch", "--limit", "30",
                             "--json", "databaseId,displayTitle,createdAt,url"))
        match = pick(runs, request)
        if match:
            return match
        time.sleep(5)
    raise SystemExit(f"dispatched {workflow} at {started}, but no run named with request {request} appeared in {timeout} s")


def pick(runs, request):
    """The listed run whose title ends with the request id, or None."""
    return next((r for r in runs if r.get("displayTitle", "").strip().endswith(request)), None)


def seconds(start, end):
    if not start or not end or start.startswith("0001"):
        return None
    from datetime import datetime
    parse = lambda t: datetime.fromisoformat(t.replace("Z", "+00:00"))  # noqa: E731
    return round((parse(end) - parse(start)).total_seconds())


def duration(s):
    return "—" if s is None else f"{s // 60}m{s % 60:02d}s"


def wait(run_id, timeout_minutes):
    """Polls until the run completes or the wait ends; prints each job once it finishes. A run still going then is left
    going: one cancelled after waiting for runners is one started again at the back of the line."""
    deadline = time.monotonic() + timeout_minutes * 60
    seen = set()
    while True:
        view = json.loads(gh("run", "view", str(run_id), "--json", "status,conclusion,jobs,url,createdAt,updatedAt,name,workflowName,displayTitle,headSha,headBranch"))
        for job in view.get("jobs", []):
            if job.get("status") == "completed" and job["name"] not in seen:
                seen.add(job["name"])
                print(f"  {job.get('conclusion'):<9} {duration(seconds(job.get('startedAt'), job.get('completedAt'))):>8}  {job['name']}", flush=True)
        if view.get("status") == "completed":
            return view
        if time.monotonic() > deadline:
            print(f"stopped waiting after {timeout_minutes} min; the run goes on: tools/cloud-run --run {run_id} picks it up", flush=True)
            view["conclusion"] = "timed out"
            return view
        time.sleep(30)


# ---- main's table ---------------------------------------------------------------------------------------------------
def compare_with_main(branch_table):
    """What a branch's whole sweep changes against main's table, the one its last whole sweep (by hand or a release)
    recorded on the generated branch."""
    main = ROOT / ".runs" / "cloud" / "main-all.json"
    done = subprocess.run([sys.executable, str(ROOT / "bench" / "generated.py"), "show", "scoreboard/all.json"],
                          cwd=ROOT, capture_output=True, text=True, encoding="utf-8")
    if done.returncode:
        print(f"\nno main table to compare with: {done.stderr.strip()}", flush=True)
        return
    main.write_text(done.stdout, encoding="utf-8")
    at = (json.loads(done.stdout).get("commit") or "?")[:10]
    done = subprocess.run([sys.executable, str(ROOT / "bench" / "exposure.py"), "compare", str(main), str(branch_table)],
                          cwd=ROOT, capture_output=True, text=True, encoding="utf-8")
    print(f"\nAgainst main's table at {at}:\n{done.stdout}{done.stderr}", flush=True)


# ---- a game proof against main's receipt ------------------------------------------------------------------------------
def receipt_of(name, update):
    """Whether a file in bench/exposure/ is a load receipt of the update (LOADER-FROM-TO): load-, or the older
    load-linked-, then the update, then the commit it ran or nothing."""
    return re.fullmatch(rf"load-(linked-)?{re.escape(update)}(-[0-9a-f]{{7,40}})?\.json", name) is not None


def main_receipt(update, git=git):
    """The receipt main keeps for an update: the one of its bench/exposure/load-*.json files main changed last, or None."""
    files = [f for f in git("ls-tree", "--name-only", "origin/main", "bench/exposure/").splitlines() if receipt_of(f.rsplit("/", 1)[-1], update)]
    if not files:
        return None
    return max(files, key=lambda f: int(git("log", "-1", "--format=%ct", "origin/main", "--", f) or 0))


def compare_loads_with_main(out):
    """Each load summary the run brought back, compared with main's receipt for its update; written to out/compare.md."""
    git("fetch", "--quiet", "origin", "main")
    lines = []
    for summary in sorted(out.glob("load-*/summary/load-*.json")):
        shutil.copy2(summary, out / summary.name)
        after = json.loads(summary.read_text(encoding="utf-8"))
        update = f"{after['loader']}-{after['from']}-{after['to']}"
        saved = main_receipt(update)
        if not saved:
            lines.append(f"{update}: main keeps no receipt for it yet")
            continue
        before = out / ("main-" + saved.rsplit("/", 1)[-1])
        before.write_text(git("show", f"origin/main:{saved}") + "\n", encoding="utf-8")
        done = subprocess.run([sys.executable, str(ROOT / "bench" / "exposure.py"), "compare", str(before), str(out / summary.name)],
                              cwd=ROOT, capture_output=True, text=True, encoding="utf-8")
        lines.append(f"Against main's {saved}:\n{done.stdout}{done.stderr}")
    if lines:
        (out / "compare.md").write_text("\n".join(lines), encoding="utf-8")
        print("\n" + "\n".join(lines), flush=True)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("workflow", nargs="?", help="packs, ports, convert, exposure, load, or a workflow file name")
    ap.add_argument("-f", "--field", action="append", default=[], metavar="KEY=VALUE", help="a workflow input; repeat for more")
    ap.add_argument("--ref", help="the branch to run (default: the current one)")
    ap.add_argument("--run", type=int, help="wait for and download this run instead of starting one")
    ap.add_argument("--timeout", type=float, default=120, help="minutes to wait (default 120); the run is never cancelled")
    ap.add_argument("--no-wait", action="store_true", help="dispatch, print the run and return")
    ap.add_argument("--allow-unpushed", action="store_true", help="run origin's commit of the branch even when this one differs")
    ap.add_argument("--out", type=Path, help="where artifacts go (default .runs/cloud/WORKFLOW-RUNID)")
    args = ap.parse_args(argv)
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    if not args.run and not args.workflow:
        ap.error("name a workflow (packs, ports, perf) or --run RUN_ID")
    started = time.monotonic()
    if args.run:
        run_id = args.run
        workflow = json.loads(gh("run", "view", str(run_id), "--json", "workflowName"))["workflowName"]
    else:
        workflow = WORKFLOWS.get(args.workflow, args.workflow)
        ref = args.ref or git("rev-parse", "--abbrev-ref", "HEAD")
        commit = ref_on_origin(ref, args.allow_unpushed)
        request = "req-" + uuid.uuid4().hex[:12]
        fields = [x for f in PRESET.get(args.workflow, []) + args.field for x in ("-f", f)]
        gh("workflow", "run", workflow, "--ref", ref, *fields, "-f", f"request={request}")
        run = find_run(workflow, request, time.strftime("%H:%M:%S"))
        run_id = run["databaseId"]
        print(f"{workflow} on {ref} ({commit[:10]}): {run['url']}", flush=True)
        if args.no_wait:
            return 0
    view = wait(run_id, args.timeout)
    name = Path(str(args.workflow or view.get("workflowName") or workflow)).stem.replace(" ", "-").lower()
    out = args.out or ROOT / ".runs" / "cloud" / f"{name}-{run_id}"
    out.mkdir(parents=True, exist_ok=True)
    gh("run", "download", str(run_id), "--dir", str(out), check=False)
    (out / "run.json").write_text(json.dumps(view, indent=1), encoding="utf-8")
    summary = next(iter(sorted(out.glob("*summary/summary.md"))), None)
    if summary:
        print(summary.read_text(encoding="utf-8"))
    table = out / "summary" / "all.json"
    if table.is_file() and view.get("headBranch") != "main" and "every update" in view.get("displayTitle", ""):
        compare_with_main(table)
    compare_loads_with_main(out)
    wall = seconds(view.get("createdAt"), view.get("updatedAt"))
    print(f"{view.get('conclusion')}: {view.get('url')} in {duration(wall)} (waited {duration(round(time.monotonic() - started))}); artifacts in {out}")
    return 0 if view.get("conclusion") == "success" else 124 if view.get("conclusion") == "timed out" else 1


if __name__ == "__main__":
    sys.exit(main())

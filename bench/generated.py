"""What the bot writes after a merge lives on the `generated` branch, never on main: the website (docs/, which GitHub
Pages serves from that branch), ROADMAP.md, and main's jar scoreboard (scoreboard/all.json, and scoreboard/points.json
with the points of every measure marked recordedBy in coverage/measures.json).

main requires a pull request to be up to date before it merges. When the bot committed these files to main, each of its
up to three commits after a merge put every open pull request behind again, and each paid another CI round to catch up.
On their own branch they cost a pull request nothing, and main is still never red from a stale merge.

    python bench/generated.py build [--dir DIR]        the site and ROADMAP.md as main would publish them (default .runs/generated)
    python bench/generated.py show PATH                a file from the generated branch, fetched first (scoreboard/all.json)
    python bench/generated.py publish --dir CHECKOUT [--table T --commit SHA --run URL]
                                                       build into a checkout of the branch, record a scoreboard sweep with
                                                       --table, commit and push; the workflows run this, never by hand
"""
import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BRANCH = "generated"
REF = f"origin/{BRANCH}"
TABLE = "scoreboard/all.json"
POINTS = "scoreboard/points.json"


def git(*args, cwd=ROOT, check=True):
    done = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True, encoding="utf-8")
    if check and done.returncode:
        raise SystemExit(f"git {' '.join(args)} failed: {done.stderr.strip()}")
    return done


def fetch():
    """Brings origin/generated up to date; False when there is no remote branch to fetch (offline, or a fork)."""
    return git("fetch", "--quiet", "origin", f"+refs/heads/{BRANCH}:refs/remotes/{REF}", check=False).returncode == 0


def read(path, ref=REF):
    """A file's text on the generated branch as last fetched, or None. Never fetches, so offline checks stay offline."""
    done = git("show", f"{ref}:{path}", check=False)
    return done.stdout if done.returncode == 0 else None


def points(folder=None):
    """The recordedBy measures' points: from FOLDER/scoreboard/points.json, else from the branch as last fetched."""
    if folder is None:
        text = read(POINTS)
    else:
        kept = Path(folder) / POINTS
        text = kept.read_text(encoding="utf-8") if kept.is_file() else None
    return json.loads(text) if text else {}


def build(folder):
    """Writes FOLDER/docs (the site) and FOLDER/ROADMAP.md from main's sources and FOLDER/scoreboard."""
    import bench_page
    import porting_map
    folder = Path(folder)
    docs = folder / "docs"
    docs.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / "docs" / "logo.svg", docs / "logo.svg")
    if bench_page.main(["--dir", str(folder)]):
        raise SystemExit("the site was not built: coverage/ has problems (above)")
    porting_map.main(docs)


def record_and_build(folder, table=None, commit=None, run=None):
    if table:
        import exposure
        exposure.record_main(table, commit, run, Path(folder) / "scoreboard")
    build(folder)


def publish(folder, table=None, commit=None, run=None, attempts=5):
    """Builds into FOLDER, a checkout of the generated branch, and pushes one commit with whatever changed. When another
    run pushed first, takes its commit and builds again on top, so neither run's scoreboard point is lost."""
    folder = Path(folder)
    subject = f"Main's jar scoreboard at {commit[:10]}" if table else f"Site and ROADMAP.md at main {git('rev-parse', '--short=10', 'HEAD').stdout.strip()}"
    for _ in range(attempts):
        record_and_build(folder, table, commit, run)
        git("add", "-A", ".", cwd=folder)
        if git("diff", "--cached", "--quiet", cwd=folder, check=False).returncode == 0:
            print(f"{BRANCH} already matches main")
            return True
        git("-c", "user.name=github-actions[bot]", "-c", "user.email=41898282+github-actions[bot]@users.noreply.github.com",
            "commit", "--quiet", "-m", subject, cwd=folder)
        if git("push", "--quiet", "origin", f"HEAD:refs/heads/{BRANCH}", cwd=folder, check=False).returncode == 0:
            print(f"pushed {subject} to {BRANCH}")
            return True
        git("fetch", "--quiet", "origin", BRANCH, cwd=folder)
        git("reset", "--quiet", "--hard", "FETCH_HEAD", cwd=folder)
    raise SystemExit(f"could not push to {BRANCH} after {attempts} tries; check that the push key can write it")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build", help="the site and ROADMAP.md as main would publish them")
    b.add_argument("--dir", type=Path, default=ROOT / ".runs" / "generated")
    s = sub.add_parser("show", help="print a file from the generated branch, fetched first")
    s.add_argument("path")
    p = sub.add_parser("publish", help="build into a checkout of the branch and push (the workflows run this)")
    p.add_argument("--dir", type=Path, required=True)
    p.add_argument("--table", type=Path, help="a sweep of main to record as main's scoreboard")
    p.add_argument("--commit", help="the main commit the sweep ran")
    p.add_argument("--run", help="the sweep's run URL")
    args = ap.parse_args(argv)
    if args.cmd == "show":
        fetch()
        text = read(args.path)
        if text is None:
            print(f"{args.path} is not on {REF}", file=sys.stderr)
            return 1
        sys.stdout.write(text)
        return 0
    if args.cmd == "build":
        if not (args.dir / "scoreboard").is_dir() and fetch():
            for name in (TABLE, POINTS):
                text = read(name)
                if text is not None:
                    (args.dir / name).parent.mkdir(parents=True, exist_ok=True)
                    (args.dir / name).write_text(text, encoding="utf-8", newline="\n")
        build(args.dir)
        return 0
    if args.table and not (args.commit and args.run):
        ap.error("--table needs --commit and --run")
    publish(args.dir, args.table, args.commit, args.run)
    return 0


sys.path.insert(0, str(Path(__file__).resolve().parent))

if __name__ == "__main__":
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    sys.exit(main())

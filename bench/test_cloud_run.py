"""tools/cloud-run.py's parts that need no GitHub: which listed run is the one it started, and run times."""
import contextlib
import importlib.util
import io
import json
import sys
from pathlib import Path

spec = importlib.util.spec_from_file_location("cloud_run", Path(__file__).resolve().parent.parent / "tools" / "cloud-run.py")
C = importlib.util.module_from_spec(spec)
spec.loader.exec_module(C)
checks = 0


def check(ok, what):
    global checks
    if not ok:
        print("FAIL:", what)
        sys.exit(1)
    checks += 1


runs = [{"databaseId": 1, "displayTitle": "Cloud pack sweep: all req-aaaaaaaaaaaa"},
        {"databaseId": 2, "displayTitle": "Cloud pack sweep: all --fix req-bbbbbbbbbbbb "},
        {"databaseId": 3, "displayTitle": "Cloud pack sweep: all "}]
check(C.pick(runs, "req-bbbbbbbbbbbb")["databaseId"] == 2, "the run named with the request id is the one started")
check(C.pick(runs, "req-cccccccccccc") is None, "no run carries another request's id")
check(C.seconds("2026-11-15T18:00:00Z", "2026-11-15T18:17:05Z") == 1025, "run time from GitHub's timestamps")
check(C.seconds("0001-01-01T00:00:00Z", "2026-11-15T18:17:05Z") is None, "a job that never started has no time")
check(C.duration(1025) == "17m05s" and C.duration(None) == "—", "times print as minutes and seconds")
check(C.WORKFLOWS["packs"] == "cloud-packs.yml" and all((C.ROOT / ".github" / "workflows" / f).is_file() for f in C.WORKFLOWS.values()),
      "every short name is a workflow in .github/workflows")

# A GitHub 502 while waiting is retried, not the end of an hours-long sweep; a real refusal still stops at once.
class Done:
    def __init__(self, code, err=""):
        self.returncode, self.stderr, self.stdout = code, err, "ok" if code == 0 else ""
answers = [Done(1, "failed to get run: HTTP 502: Server Error"), Done(1, "HTTP 503"), Done(0)]
calls = []
C.gh_path = lambda: "gh"
out = C.gh("run", "view", "1", run=lambda *a, **k: (calls.append(1), answers[len(calls) - 1])[1], sleep=lambda s: None)
check(out == "ok" and len(calls) == 3, f"transient GitHub errors are retried: {len(calls)} calls")
calls.clear()
try:
    C.gh("run", "view", "1", run=lambda *a, **k: (calls.append(1), Done(1, "HTTP 404: Not Found"))[1], sleep=lambda s: None)
    check(False, "a 404 stops")
except SystemExit:
    check(len(calls) == 1, "a real refusal is not retried")

# Main's table is made once per commit and found again. A commit that changed only files the table cannot read (the
# workflow's paths-ignore) has its parent's table; a sweep dispatched for a commit names it in its title.
globs = C.ignored_paths()
check("docs/**" in globs and "coverage/measures.json" in globs and "coverage/roadmap/**" in globs, f"the ignored files are read from the workflow: {globs}")
check(C.glob_matches("**.md", ".claude/skills/port-a-mod/SKILL.md") and C.glob_matches("coverage/roadmap/**", "coverage/roadmap/goal-2/055-78.json")
      and not C.glob_matches("docs/**", "tools/docs.java") and not C.glob_matches("bench/exposure/load-*.json", "bench/exposure/mods/a.json"),
      "path filters match as GitHub's: ** crosses folders, * stays in one")
history = {"c3": ("c2", ["ROADMAP.md", "docs/index.html"]), "c2": ("c1", ["tools/JarPort.java"]), "c1": ("c0", [])}
fake_git = lambda *a: (history.get(a[-1].rstrip("^"), (None,))[0] or "") if a[0] == "rev-parse" else "\n".join(history[a[-1]][1])  # noqa: E731
check(C.same_table("c3", globs, git=fake_git) == ["c3", "c2"], "a commit that changed only generated files has its parent's table, and no further back")
A, B, D = "a" * 40, "b" * 40, "d" * 40
listed = [{"databaseId": 1, "headSha": A, "displayTitle": "Cloud jar scoreboard: fabric:1.20.1 ", "status": "completed", "conclusion": "success"},
          {"databaseId": 2, "headSha": B, "displayTitle": f"Cloud jar scoreboard: every update at {A} req-x", "status": "in_progress", "conclusion": ""},
          {"databaseId": 3, "headSha": D, "displayTitle": "Cloud jar scoreboard: every update ", "status": "completed", "conclusion": "failure"},
          {"databaseId": 4, "headSha": D, "displayTitle": "Cloud jar scoreboard: every update ", "status": "completed", "conclusion": "success"}]
check([r["databaseId"] for r in C.full_runs_of(listed, [A])] == [2], "a partial sweep is no table; one dispatched for the commit is, even while it runs")
check([r["databaseId"] for r in C.full_runs_of(listed, [B, D])] == [4], "a sweep that ran the commit as its head counts, a failed one does not")
T, U = "1" * 40, "2" * 40
named = [{"databaseId": 5, "headSha": B, "displayTitle": f"Cloud jar scoreboard: every update tree {T} req-y", "status": "completed", "conclusion": "success"},
         {"databaseId": 6, "headSha": B, "displayTitle": f"Cloud jar scoreboard: fabric:1.20.1 tree {U} req-z", "status": "completed", "conclusion": "success"}]
check([r["databaseId"] for r in C.full_runs_of(named, [D], [T])] == [5], "a pull request's whole sweep of the tree is the table of a commit with that tree")
check(C.full_runs_of(named, [D], [U]) == [], "a partial sweep of the tree is not")
asked = []


def fake_gh(*a):
    asked.append(a[:2])
    if a[:2] == ("run", "list"):
        return json.dumps(listed)
    raise AssertionError(f"nothing else is asked of GitHub: {a}")


C.same_table = lambda commit, globs=None, git=None: [commit]
C.tree_of = lambda commit, git=None: ""
check(C.main_table(D, gh=fake_gh)[0] == 4 and asked == [("run", "list")], f"a saved table is found without dispatching a sweep of main: {asked}")
check(C.main_table("e" * 40, gh=fake_gh, dispatch=False) == (None, "none"), "a commit with no sweep has no table yet")

# Waiting for main's table spends one timeout in all: a sweep still queued at it is not waited on again; a cancelled one
# is replaced and waited on for what is left.
waits = []
ends = {}


def fake_wait(run_id, minutes, keep):
    waits.append((run_id, minutes))
    return {"conclusion": ends[run_id]}


saved_wait, saved_table = C.wait, C.main_table
C.wait = fake_wait
looks = iter([(7, "running"), (8, "dispatched")])
C.main_table = lambda commit: next(looks)
ends.update({7: "timed out"})
try:
    C.fetch_main_table("f" * 40, 2)
    check(False, "a sweep still queued at the timeout ends the wait")
except SystemExit:
    pass
check(waits == [(7, 2)], f"a sweep still queued at the timeout is not looked up and waited on again: {waits}")
waits.clear()
looks = iter([(7, "running"), (8, "dispatched")])
ends.update({7: "cancelled", 8: "timed out"})
try:
    C.fetch_main_table("f" * 40, 2)
except SystemExit:
    pass
check([w[0] for w in waits] == [7, 8] and waits[1][1] <= 2, f"a cancelled sweep is replaced within the same timeout: {waits}")
C.wait, C.main_table = saved_wait, saved_table

# A game proof is compared with the receipt main changed last for the same update, whatever commit or older name it has.
tree = ["bench/exposure/load-neoforge-1.21.1-1.21.2.json", "bench/exposure/load-linked-neoforge-1.21.1-1.21.2.json",
        "bench/exposure/load-neoforge-1.21.1-1.21.21.json", "bench/exposure/load-neoforge-1.21.1-1.21.2-e15ee907f4.json",
        "bench/exposure/jars-neoforge-1.21.1-1.21.2.json", "bench/exposure/load-fabric-1.21.1-1.21.2.json"]
when = {tree[0]: 100, tree[1]: 300, tree[3]: 200}
tree_git = lambda *a: "\n".join(tree) if a[0] == "ls-tree" else str(when.get(a[-1], 0))  # noqa: E731
check(C.main_receipt("neoforge-1.21.1-1.21.2", git=tree_git) == tree[1], "the receipt main changed last is the one compared")
when[tree[3]] = 400
check(C.main_receipt("neoforge-1.21.1-1.21.2", git=tree_git) == tree[3], "a newer receipt named for its commit replaces it")
check(C.main_receipt("neoforge-1.21.2-1.21.3", git=tree_git) is None, "an update main keeps no receipt for has none")
# A scoreboard run reports each update as its job finishes, and stops at the first failed job with its log's end, leaving
# the run going: a sweep whose first job failed at minute 4 used to be waited on until minute 38, and an update that
# finished at minute 4 said nothing until the whole run ended.
import tempfile  # noqa: E402
job = lambda name, status, conclusion=None, i=0: {"name": name, "status": status, "conclusion": conclusion, "databaseId": 900 + i,  # noqa: E731
                                                  "url": f"https://x/job/{900 + i}", "startedAt": "2026-11-23T10:00:00Z",
                                                  "completedAt": "2026-11-23T10:04:00Z" if status == "completed" else None,
                                                  "steps": [{"name": "Port and link this job's mods", "conclusion": conclusion}]}
polls = [
    [job("plan", "completed", "success"), job("fabric:1.21.1:1.21.2", "completed", "success", 1), job("neoforge:1.21.1:1.21.2", "in_progress", i=2)],
    [job("plan", "completed", "success"), job("fabric:1.21.1:1.21.2", "completed", "success", 1), job("neoforge:1.21.1:1.21.2", "completed", "failure", 2)],
]
calls, compared = [], []
with tempfile.TemporaryDirectory() as tmp:
    out = Path(tmp)

    def run_gh(*a, check=True):
        calls.append(a[:2])
        if a[:2] == ("run", "view"):
            if not polls:
                raise AssertionError("waited on after the failed job")
            return json.dumps({"status": "in_progress", "conclusion": "", "url": "https://x/run/5", "headSha": "a" * 40, "jobs": polls.pop(0)})
        if a[0] == "api" and a[1].endswith("/logs"):
            return "2026-11-23T10:03:59.1Z Exception in thread main\n2026-11-23T10:04:00.0Z ##[error]Process completed with exit code 1.\n2026-11-23T10:04:01.0Z Post job cleanup."
        if a[0] == "api":
            return json.dumps([{"name": "exposure-0", "id": 1}])
        if a[:2] == ("run", "download"):
            folder = Path(a[a.index("--dir") + 1])
            folder.mkdir(parents=True)
            (folder / "fabric-1.21.1-1.21.2.json").write_text("{}", encoding="utf-8")
            return ""
        raise AssertionError(f"unexpected gh {a}")

    saved_sleep, saved_gh = C.time.sleep, C.gh
    C.time.sleep, C.gh = (lambda s: None), run_gh
    printed = io.StringIO()
    try:
        with contextlib.redirect_stdout(printed):
            view = C.wait(5, 60, False, on_job=C.Updates(5, out, gh=run_gh, compare=lambda files, head: compared.append(([f.name for f in files], head))))
    finally:
        C.time.sleep, C.gh = saved_sleep, saved_gh
    text = printed.getvalue()
    check(compared == [(["fabric-1.21.1-1.21.2.json"], "a" * 40)], f"the finished update's summary is compared as its job finishes: {compared}")
    check(view["conclusion"] == "failure" and not polls, f"the wait ends at the first failed job: {view['conclusion']}")
    check(("run", "cancel") not in calls, "the run is left going")
    check("Process completed with exit code 1" in text and "Post job cleanup" not in text and "https://x/run/5" in text and "--run 5" in text,
          f"the failure prints its log's end and the run: {text}")
    check(C.download_new(5, out, {"exposure-0"}, gh=run_gh) == [], "an artifact already downloaded is not downloaded again")
# Every agent shares one API limit (5,000 calls an hour): a poll is one call, polls slow down while no job finishes, and
# a spent limit ends the wait with when it renews and the run's link, where it used to crash the wait.
views = [[], [], [job("a", "completed", "success")], [job("a", "completed", "success")]]
asked, pauses = [], []


def limited_gh(*a, check=True, tries=6):
    asked.append(a[:2])
    if a[:2] == ("api", "rate_limit"):
        return "1795000000"
    if not views:
        raise SystemExit("gh run view 5 failed: HTTP 403: API rate limit exceeded for user ID 1.")
    return json.dumps({"status": "in_progress", "url": "https://x/run/5", "jobs": views.pop(0)})


printed = io.StringIO()
with contextlib.redirect_stdout(printed):
    view = C.wait(5, 60, False, sleep=pauses.append, gh=limited_gh)
check(pauses == [60, 120, 30, 60], f"polls slow while nothing finishes and speed up when a job does: {pauses}")
check(asked.count(("run", "view")) == 5 and len(asked) == 6, f"one API call a poll: {asked}")
check(view["conclusion"] == "rate limited" and "until 11:06 UTC" in printed.getvalue() and "https://x/run/5" in printed.getvalue(),
      f"a spent limit ends the wait with when it renews and the run's link: {printed.getvalue()}")
print(f"{checks} cloud-run checks passed")

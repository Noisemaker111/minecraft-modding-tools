"""tools/cloud-run.py's parts that need no GitHub: which listed run is the one it started, and run times."""
import importlib.util
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
print(f"{checks} cloud-run checks passed")

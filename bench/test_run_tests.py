"""The checks a pull request runs are the ones its changes can affect, and every one when that is unsure.

CI runs bench/run_tests.py --base HEAD^1: a check skipped wrongly lets a break merge, so each case below is one where a
wrong pick would skip a check that reads the change, or run all of them for nothing.
Run: python bench/test_run_tests.py"""
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_tests  # noqa: E402

failed = []
passed = 0


def check(ok, label):
    global passed
    if ok:
        passed += 1
    else:
        failed.append(label)


names = list(run_tests.CHECKS)
jar_checks = [n for n, (needs_jar, _, _) in run_tests.CHECKS.items() if needs_jar and n not in run_tests.READS]

picked, suites = run_tests.affected(["tools/port/jar/StandinClasses.java"], names)
check({"jar-port", "package", "self-test"} <= set(picked) and suites == ["port"], f"a porter change runs the porter's tests and self-test's port suite: {picked} {suites}")
check("fast" not in picked and "hidden-window" not in picked, f"and not the checks that never run the porter: {picked}")

picked, suites = run_tests.affected(["tools/cli/StartCheck.java"], names)
check(set(jar_checks) <= set(picked) and suites is None, f"a change to anything else the jar carries runs every jar check and every suite: {sorted(set(jar_checks) - set(picked))} {suites}")

picked, suites = run_tests.affected(["tools/port/jar/JarPort.java", "tools/cli/Mmt.java"], names)
check(set(jar_checks) <= set(picked) and suites is None, f"a porter change next to another jar change runs every check: {picked} {suites}")

picked, _ = run_tests.affected(["docs/index.md", "AGENTS.md"], names)
check(picked == ["python"], f"docs alone run the python checks only: {picked}")

picked, _ = run_tests.affected(["tools/agent/Console.java"], names)
check("console-accessor" in picked and "package" in picked, f"a source a check compiles itself runs that check, and the jar's: {picked}")

for runner in run_tests.RUNNER:
    picked, suites = run_tests.affected([runner], names)
    check(picked == names and suites is None, f"a change to how checks are picked or run ({runner}) runs every check: {picked}")

picked, _ = run_tests.affected(["bench/pipez/experiment.json"], names)
check({"checkout-data", "package"} <= set(picked) and ("launcher" in picked) == ("launcher" in names), f"an experiment's change runs the checks that read the experiments: {picked}")
picked, _ = run_tests.affected(["coverage/perf-runs.json"], names)
check("checkout-data" in picked, f"so does a change to the kept runs: {picked}")

# A base the checkout does not have (a shallow clone, a typo) is no list of changes: every check runs.
check(run_tests.changed_files("0" * 40) is None, "a base that is not here gives no list, so every check runs")

# A base that is here: the files changed since it, committed or not.
with tempfile.TemporaryDirectory() as folder:
    repo = Path(folder)
    git = lambda *a: subprocess.run(["git", *a], cwd=repo, capture_output=True, text=True, check=True).stdout.strip()
    git("init", "-q")
    git("config", "user.email", "t@t")
    git("config", "user.name", "t")
    (repo / "a.txt").write_text("a")
    git("add", ".")
    git("commit", "-qm", "base")
    base = git("rev-parse", "HEAD")
    (repo / "tools").mkdir()
    (repo / "tools" / "B.java").write_text("class B {}")
    git("add", ".")
    git("commit", "-qm", "change")
    (repo / "c.md").write_text("c")
    root = run_tests.ROOT
    run_tests.ROOT = repo
    try:
        check(run_tests.changed_files(base) == ["c.md", "tools/B.java"], f"the files changed since the base, committed and new: {run_tests.changed_files(base)}")
        # A move names both paths: a check that still reads the old one runs, and finds it gone.
        moved = git("rev-parse", "HEAD")
        (repo / "bench").mkdir()
        git("mv", "tools/B.java", "bench/B.java")
        git("commit", "-qm", "move")
        check(run_tests.changed_files(moved) == ["bench/B.java", "c.md", "tools/B.java"], f"a moved file is changed at both paths: {run_tests.changed_files(moved)}")
    finally:
        run_tests.ROOT = root

if failed:
    print("\n".join("FAILED: " + f for f in failed))
    sys.exit(1)
print(f"test runner: {passed} checks passed")

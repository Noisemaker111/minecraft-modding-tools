"""Every offline check, in one list: what CI runs on each pull request and what to run before pushing.

    python bench/run_tests.py                  the checks this branch's changes can affect (all of them in CI)
    python bench/run_tests.py --all            all of them (builds the jar first), as CI runs them
    python bench/run_tests.py --only NAME,...  some of them (package runs first when one needs the jar)
    python bench/run_tests.py --list           the names

Outside CI, a run picks the checks from the files changed against origin/main (committed or not): each check that
compiles its sources runs when one of them changed; the checks that use the packaged jar run when anything the jar
carries changed; the python checks always run (about 10 s). A change to the porter's sources alone (what jar-port
compiles) needs only jar-port and the self-test suite that runs it (port: mmt port --jar), as self-test has no game and
no network to run the rest of them with. Agents reran the whole suite (about 3 minutes) 352 times in 36 hours while
iterating; CI still runs all of it on every push.

The self-test runs from the packaged jar, as users get it, not from the checkout. On Linux it runs with LC_ALL=C
so a child's non-ASCII output is read through a non-UTF-8 console; Windows gives cp1252. No game, no network
beyond Gradle's own downloads. Each check prints its time; the run exits 1 when any check failed.
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAR = "build/distributions/mmt.jar"
ZIP = "build/distributions/mmt-portable.zip"
FIXTURE = "fixtures/prism-lab"  # Prism Lab, the demo mod the mod-project checks run on
WINDOWS = os.name == "nt"
SEP = ";" if WINDOWS else ":"

# Every bench/**/test_*.py runs in the python check, except one another check runs with arguments.
PYTHON = sorted(p.relative_to(ROOT).as_posix() for p in (ROOT / "bench").rglob("test_*.py") if p.name != "test_fingerprint.py")


def java(*args):
    return ["java", *args]


def checkout_data():
    out = ROOT / ".runs" / "checkout-tests"
    out.mkdir(parents=True, exist_ok=True)
    yield ["javac", "-encoding", "UTF-8", "-cp", JAR, "-d", str(out), "tools/tests/CheckoutDataTest.java"]
    yield java("-cp", JAR + SEP + str(out), "CheckoutDataTest", ".")


def port_preflight():
    out = ROOT / ".runs" / "preflight-tests"
    yield ["javac", "--release", "21", "-encoding", "UTF-8", "-d", str(out), "tools/VersionMap.java",
           "tools/cli/Route.java", "tools/cli/Json.java", "tools/cli/Versions.java",
           "tools/tests/PortPreflightTest.java", "tools/tests/ChainMapTest.java", "tools/tests/LegacyMapTest.java"]
    yield java("-cp", str(out), "PortPreflightTest")
    yield java("-cp", str(out), "ChainMapTest")
    yield java("-cp", str(out), "LegacyMapTest")


# The porter's tests: every tools/tests file named for what it tests (JarPort*Test, Game*Test, ModNames*Test), found
# here rather than listed, so two pull requests that each add one never touch the same line. Each is a class with a main.
JAR_PORT_TESTS = ("JarPort*Test.java", "Game*Test.java", "ModNames*Test.java")


def jar_port_tests(folder=ROOT / "tools" / "tests"):
    return sorted({p.relative_to(ROOT).as_posix() for pattern in JAR_PORT_TESTS for p in folder.glob(pattern)})


def jar_port():
    out = ROOT / ".runs" / "jarport-tests"
    asm = SEP.join(["tools/perf/lib/asm-9.9.jar", "tools/perf/lib/asm-commons-9.9.jar"])
    tests = jar_port_tests()
    yield ["javac", "--release", "25", "-encoding", "UTF-8", "-cp", asm, "-d", str(out), "tools/JarPort.java", "tools/FixExpr.java", "tools/PortChains.java", "tools/Exposure.java",
           "tools/GameDiff.java", "tools/VersionMap.java", "tools/ModNames.java", "tools/cli/Json.java", *tests]
    for test in tests:
        yield java("-cp", str(out) + SEP + asm, Path(test).stem, str(out / "scratch"))


def console_accessor():
    out = ROOT / ".runs" / "console-tests"
    # Console is compiled by the game's own Java 8 JVM, so its source must stay at that level.
    yield ["javac", "--release", "8", "-encoding", "UTF-8", "-d", str(out), "tools/agent/Console.java",
           "tools/tests/ConsoleAccessorTest.java"]
    yield java("-cp", str(out), "ConsoleAccessorTest", ".")


def agent_world_open():
    out = ROOT / ".runs" / "agent-world-open-tests"
    asm = SEP.join(["tools/perf/lib/asm-9.9.jar", "tools/perf/lib/asm-commons-9.9.jar"])
    yield ["javac", "-nowarn", "-encoding", "UTF-8", "-cp", asm, "-sourcepath", SEP.join(["tools/agent", "tools/cli", "tools"]), "-d", str(out),
           "tools/agent/PortableAgent.java", "tools/tests/AgentWorldOpenTest.java"]
    yield java("-cp", str(out) + SEP + asm, "AgentWorldOpenTest")


def legacy_agent():
    """Windows: the legacy launch agent in JAVA_TOOL_OPTIONS keeps system properties strings, and Gradle still runs."""
    prepared = subprocess.run(java("-jar", JAR, "legacy-agent", "--root", FIXTURE, "--json"), cwd=ROOT,
                              capture_output=True, text=True)
    agent = json.loads(prepared.stdout.strip().splitlines()[-1])["result"]["agent"]
    env = {"JAVA_TOOL_OPTIONS": f'"-javaagent:{agent}=legacy" -Dmmt.background=true -Dmmt.quiet=true'}
    yield java("tools/tests/LegacyAgentPropertiesTest.java"), env
    yield java("-jar", JAR, "fast", "--root", FIXTURE, "--refresh", "--json"), env


# name -> (needs the jar, systems or None for all, commands or a generator of them)
CHECKS = {
    "python": (False, None, [[sys.executable, p] for p in PYTHON]),
    "package": (False, None, [java("tools/cli/Bootstrap.java", ".", "package", "--json")]),
    "self-test": (True, None, [(java("-jar", JAR, "self-test", "--root", "."), {} if WINDOWS else {"LC_ALL": "C"})]),
    "checkout-data": (True, None, checkout_data),
    "fingerprint": (True, None, [[sys.executable, "bench/test_fingerprint.py", JAR]]),
    "port-preflight": (False, None, port_preflight),
    "jar-port": (False, None, jar_port),
    "console-accessor": (False, None, console_accessor),
    "agent-world-open": (False, None, agent_world_open),
    "zip": (True, None, [java("tools/tests/PackageAcceptanceTest.java", ZIP, ZIP + ".sha256", tempfile.gettempdir())]),
    "fast": (True, None, [java("-jar", JAR, "fast", "--root", FIXTURE, "--json")]),
    "port-plan": (True, None, [java("tools/tests/PortPlanCacheTest.java", FIXTURE, JAR)]),
    "hidden-window": (True, None, [java("tools/tests/BackgroundWindowsTest.java", FIXTURE, JAR)]),
    "fastlane-isolation": (True, None, [java("tools/tests/FastLaneIsolationTest.java", ".", JAR)]),
    "runtime-natives": (True, ("posix",), [java("tools/tests/RuntimeNativesTest.java", ".", JAR)]),
    "launcher": (False, ("nt",), [[sys.executable, "tools/ci/launcher_experiments.py"]]),
    "legacy-agent": (True, ("nt",), legacy_agent),
}


def jar_port_sources():
    return ["tools/JarPort.java", "tools/FixExpr.java", "tools/PortChains.java", "tools/Exposure.java", "tools/GameDiff.java",
            "tools/VersionMap.java", "tools/ModNames.java", "tools/cli/Json.java"]


ASM_JARS = ["tools/perf/lib/asm-9.9.jar", "tools/perf/lib/asm-commons-9.9.jar"]
# The files each check that compiles its own sources reads; a check not named here uses the packaged jar.
READS = {
    "port-preflight": ["tools/VersionMap.java", "tools/cli/Route.java", "tools/cli/Json.java", "tools/cli/Versions.java",
                       "tools/tests/PortPreflightTest.java", "tools/tests/ChainMapTest.java", "tools/tests/LegacyMapTest.java"],
    "jar-port": lambda: jar_port_sources() + jar_port_tests() + ASM_JARS,
    "console-accessor": ["tools/agent/Console.java", "tools/tests/ConsoleAccessorTest.java"],
    # Compiled with tools/agent, tools/cli and tools on its source path: any of them may be compiled in.
    "agent-world-open": lambda: [f for f in git_files() if f.startswith(("tools/agent/", "tools/cli/")) or f.count("/") == 1 and f.startswith("tools/") and f.endswith(".java")]
                                + ["tools/tests/AgentWorldOpenTest.java"] + ASM_JARS,
}
# Files no packaged jar carries and no jar check reads: the docs, the bench scripts and cloud-run (the python check runs those), CI,
# the desktop app and the generated site.
NOT_IN_JAR = ("docs/", "bench/", ".github/", ".claude/", "app/", "tools/cloud-run", "coverage/roadmap", "coverage/ledger.json", "coverage/measures.json",
              "coverage/perf-runs.json", "ROADMAP.md")


def git_files():
    return subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True).stdout.split()


def changed_files():
    """Files this branch changed against where it left origin/main, committed, staged, unstaged or new; None without git."""
    base = subprocess.run(["git", "merge-base", "HEAD", "origin/main"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    if not base:
        return None
    listed = subprocess.run(["git", "diff", "--name-only", base], cwd=ROOT, capture_output=True, text=True).stdout.split()
    new = subprocess.run(["git", "ls-files", "--others", "--exclude-standard"], cwd=ROOT, capture_output=True, text=True).stdout.split()
    return sorted(set(listed) | set(new))


def in_jar(path):
    return not path.startswith(NOT_IN_JAR) and not (path.endswith(".md") and path != "PERFORMANCE.md")


def affected(changed, names):
    """The checks of names that changed files can affect, and what self-test runs: None for every suite, else a list."""
    reads = {n: set(r() if callable(r) else r) for n, r in READS.items()}
    picked = [n for n in names if n == "python" or n in reads and reads[n] & set(changed)]
    porter = set(jar_port_sources()) | set(jar_port_tests())
    jar = [f for f in changed if in_jar(f)]
    if any(f not in porter for f in jar):
        return picked + [n for n in names if n not in READS and n not in picked and n != "python"], None
    if jar:
        return picked + [n for n in ("package", "self-test") if n in names], ["port"]
    return picked, None


def run(name, suites=None):
    """Runs every command of one check, reporting each; False when any failed."""
    _, _, commands = CHECKS[name]
    if name == "self-test" and suites:
        commands = [(java("-jar", JAR, "self-test", "--root", ".", "--only", ",".join(suites)), {} if WINDOWS else {"LC_ALL": "C"})]
    passed = True
    for item in (commands() if callable(commands) else commands):
        command, env = item if isinstance(item, tuple) else (item, {})
        started = time.monotonic()
        done = subprocess.run(command, cwd=ROOT, env={**os.environ, **env}, capture_output=True, text=True,
                              encoding="utf-8", errors="replace")
        took = time.monotonic() - started
        shown = " ".join(str(c) for c in command)
        if done.returncode:
            print(f"FAIL {name} ({took:.0f} s): {shown}\n{done.stdout[-4000:]}{done.stderr[-4000:]}", flush=True)
            passed = False
            continue
        last = (done.stdout.strip().splitlines() or [""])[-1][:160]
        print(f"ok   {name} ({took:.0f} s): {shown[-90:]}  {last if not last.startswith('{') else ''}", flush=True)
    return passed


def main(argv=None):
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", help="comma-separated check names")
    ap.add_argument("--all", action="store_true", help="every check, as CI runs them (the default in CI)")
    ap.add_argument("--list", action="store_true")
    args = ap.parse_args(argv)
    here = "nt" if WINDOWS else "posix"
    names = [n for n, (_, systems, _) in CHECKS.items() if systems is None or here in systems]
    if args.list:
        print("\n".join(names))
        return 0
    if args.only:
        wanted = [x.strip() for x in args.only.split(",")]
        unknown = [x for x in wanted if x not in CHECKS]
        if unknown:
            print(f"No check {', '.join(unknown)}. Checks: {', '.join(CHECKS)}", file=sys.stderr)
            return 2
        if any(CHECKS[x][0] for x in wanted) and "package" not in wanted:
            wanted.insert(0, "package")
        names = [n for n in names if n in wanted]
    suites = None
    changed = None if args.only or args.all or os.environ.get("CI") else changed_files()
    if changed is not None:
        names, suites = affected(changed, names)
        names = [n for n in CHECKS if n in names]
        print(f"{len(changed)} files changed against origin/main: running {', '.join(names)}"
              + (f" (self-test: {', '.join(suites)})" if suites else "") + "; --all runs every check", flush=True)
    started = time.monotonic()
    failed = [n for n in names if not run(n, suites)]
    print(f"{len(names) - len(failed)} of {len(names)} checks passed in {time.monotonic() - started:.0f} s"
          + (f"; failed: {', '.join(failed)}" if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

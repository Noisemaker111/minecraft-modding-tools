"""Every offline check, in one list: what CI runs on each pull request and what to run before pushing.

    python bench/run_tests.py                  the checks this branch's changes can affect
    python bench/run_tests.py --base REF       the checks the changes since REF can affect, as CI runs them on a pull request
    python bench/run_tests.py --all            all of them (builds the jar first)
    python bench/run_tests.py --only NAME,...  some of them (package runs first when one needs the jar)
    python bench/run_tests.py --list           the names

Outside CI, a run picks the checks from the files changed against origin/main (committed or not): each check that
compiles its sources runs when one of them changed; the checks that use the packaged jar run when anything the jar
carries changed; the python checks always run (about 10 s). A change to the porter's sources alone (what jar-port
compiles) needs only jar-port and the self-test suite that runs it (port: mmt port --jar), as self-test has no game and
no network to run the rest of them with. Agents reran the whole suite (about 3 minutes) 352 times in 36 hours while
iterating. CI picks the same way, from what the pull request changes against its base (--base HEAD^1): running every
check on Windows took 7.5 minutes of a 4-core runner for a porter-only change (#237, 2026-11-26).

The self-test runs from the packaged jar, as users get it, not from the checkout. On Linux it runs with LC_ALL=C
so a child's non-ASCII output is read through a non-UTF-8 console; Windows gives cp1252. No game, no network
beyond Gradle's own downloads. Each check prints its time; the run exits 1 when any check failed.

package runs first, alone; then the other checks run side by side, with no more commands at once than the machine has
cores, and each prints its block whole when it finishes. The checks that build with Gradle share one lane and one warm
daemon (GRADLE). The demo mod is copied for the checks that run mmt on it, so two lanes never share one, and every check
keeps its own scratch folder.
"""
import argparse
import heapq
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import port_tools

ROOT = Path(__file__).resolve().parent.parent
JAR = "build/distributions/mmt.jar"
ZIP = "build/distributions/mmt-portable.zip"
FIXTURE = "fixtures/prism-lab"  # Prism Lab, the demo mod the mod-project checks run on
WINDOWS = os.name == "nt"
SEP = ";" if WINDOWS else ":"
CORES = len(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity") else os.cpu_count() or 1

# Every bench/**/test_*.py runs in the python check, except one another check runs with arguments.
PYTHON = sorted(p.relative_to(ROOT).as_posix() for p in (ROOT / "bench").rglob("test_*.py") if p.name != "test_fingerprint.py")


def java(*args, home=None):
    """A short-lived java (port_tools.QUICK): the JDK's at home, else the one on PATH."""
    return [str(home / "bin" / "java") if home else "java", port_tools.QUICK, *args]


def javac(*args, home=None):
    return [str(home / "bin" / "javac") if home else "javac", "-J" + port_tools.QUICK, *args]


class Together(list):
    """Commands that share nothing and run side by side; each still reports its own line."""


# The demo mod's build output, kept in each copy so a rerun builds warm; everything else is refreshed from the fixture.
BUILT = {".gradle", "build", ".mmt", ".runs"}


# These checks build with Gradle in one lane, one after another in this order, so one warm Gradle daemon serves them;
# beside each other each starts its own and sets up cold, and on CI's 4 cores fast (20 s alone) took 127 s that way.
# The first three run mmt on one shared copy of the demo mod: they would refuse each other's project lock.
# fastlane-isolation, which builds two small projects of its own, is a lane of its own: on 4 cores the whole run takes
# 68 s that way and 76 s with it in this lane.
GRADLE = ("fast", "port-plan", "legacy-agent", "runtime-natives")
SHARED = ("fast", "port-plan", "legacy-agent")


class Slots:
    """No more commands at once than the machine has cores (self-test's suites and Gradle use more on their own). A
    free slot goes to the waiting command of the earliest-listed lane: the long lanes are listed first, and a short
    test queued ahead of them would otherwise make the whole run wait (fast waited 29 s behind jar-port's tests)."""

    def __init__(self, count):
        self.free = count
        self.waiting = []
        self.changed = threading.Condition()

    def take(self, rank):
        with self.changed:
            ticket = (rank, time.monotonic_ns(), threading.get_ident())
            heapq.heappush(self.waiting, ticket)
            self.changed.wait_for(lambda: self.free and self.waiting[0] == ticket)
            heapq.heappop(self.waiting)
            self.free -= 1
            self.changed.notify_all()

    def give(self):
        with self.changed:
            self.free += 1
            self.changed.notify_all()


SLOTS = Slots(CORES)


def fixture(check):
    """A copy of the demo mod for a check (the SHARED checks share one), its sources the fixture's own; the path
    relative to the checkout."""
    copy = ROOT / ".runs" / "checks" / ("shared" if check in SHARED else check) / "prism-lab"
    copy.mkdir(parents=True, exist_ok=True)
    for entry in copy.iterdir():
        if entry.name not in BUILT:
            shutil.rmtree(entry) if entry.is_dir() else entry.unlink()
    source = ROOT / FIXTURE
    for entry in source.iterdir():
        if entry.name in BUILT:
            continue
        if entry.is_dir():
            shutil.copytree(entry, copy / entry.name)
        else:
            shutil.copy2(entry, copy / entry.name)
    return copy.relative_to(ROOT).as_posix()


def stop_fixture_daemons():
    """Stops the Gradle daemons the checks' copies of the demo mod started: left running, each held up to a gigabyte of the
    machine for ten minutes after the checks ended, kept warm for a rerun."""
    copies = [c for c in (ROOT / ".runs" / "checks").glob("*/prism-lab") if (c / ".mmt" / "portable" / "gradle-daemons").is_dir()]
    if not copies or not (ROOT / JAR).is_file():
        return
    with ThreadPoolExecutor(len(copies)) as pool:
        list(pool.map(lambda c: subprocess.run(java("-jar", JAR, "game-stop", "--root", str(c), "--json"), cwd=ROOT,
                                               capture_output=True, timeout=120), copies))


def checkout_data():
    out = ROOT / ".runs" / "checkout-tests"
    out.mkdir(parents=True, exist_ok=True)
    yield javac("-encoding", "UTF-8", "-cp", JAR, "-d", str(out), "tools/tests/CheckoutDataTest.java")
    yield java("-cp", JAR + SEP + str(out), "CheckoutDataTest", ".")


PREFLIGHT_TESTS = ["tools/tests/PortPreflightTest.java", "tools/tests/ChainMapTest.java", "tools/tests/LegacyMapTest.java"]


def port_preflight():
    out = ROOT / ".runs" / "preflight-tests"
    asm = SEP.join(port_tools.ASM)
    home = jdk(25)
    yield javac("--release", "25", "-encoding", "UTF-8", "-cp", asm, "-d", str(out), *port_tools.sources(), *PREFLIGHT_TESTS, home=home)
    for test in PREFLIGHT_TESTS:
        yield java("-cp", str(out) + SEP + asm, Path(test).stem, home=home)


# The porter's tests: every tools/tests file named for what it tests (JarPort*Test, Game*Test, ModNames*Test, SourceAnswers*Test), found
# here rather than listed, so two pull requests that each add one never touch the same line. Each is a class with a main.
JAR_PORT_TESTS = ("JarPort*Test.java", "Game*Test.java", "ModNames*Test.java", "SourceAnswers*Test.java")


def jar_port_tests(folder=ROOT / "tools" / "tests"):
    return sorted({p.relative_to(ROOT).as_posix() for pattern in JAR_PORT_TESTS for p in folder.glob(pattern)})


class Unavailable(Exception):
    """Something a check needs is not on this machine; the message says what and where it was looked for."""


def jdk_feature(home):
    """The feature release of the JDK at home (25 for 25.0.1), from its release file; None when it is no JDK."""
    try:
        line = next(l for l in (home / "release").read_text(errors="replace").splitlines() if l.startswith("JAVA_VERSION="))
        version = line.split("=", 1)[1].strip('"')
        return int(version.split(".")[1] if version.startswith("1.") else version.split(".")[0].split("-")[0].split("+")[0])
    except (OSError, StopIteration, ValueError, IndexError):
        return None


def jdk(feature):
    """The home of a JDK of feature or newer, looked for where mmt looks (Runtimes.installed): JAVA_HOME and the
    JAVA*_HOME variables, the javac on PATH, then mmt's own downloads and the usual install folders."""
    candidates = [Path(os.environ[k]) for k in ("JAVA_HOME", "JDK_HOME", f"JAVA{feature}_HOME") if os.environ.get(k)]
    on_path = shutil.which("javac")
    if on_path:
        candidates.append(Path(on_path).resolve().parent.parent)
    # actions/setup-java names each JDK it installs JAVA_HOME_<feature>_<arch>.
    candidates += [Path(v) for k, v in sorted(os.environ.items()) if k.startswith("JAVA_HOME_") and k[10:].split("_")[0].isdigit() and v]
    user = Path.home()
    folders = [user / ".mmt" / "jdks", ROOT / ".tools", user / ".gradle" / "jdks", user / ".jdks", user / "tools", Path("/usr/lib/jvm"),
               Path("/Library/Java/JavaVirtualMachines")]
    if WINDOWS:
        folders += [Path(os.environ.get("ProgramFiles", "C:/Program Files")) / v for v in ("Eclipse Adoptium", "Java", "Microsoft", "JetBrains")]
    for folder in folders:
        if folder.is_dir():
            for child in sorted(folder.iterdir()):
                candidates += [child, child / "Contents" / "Home"]
    javac = "javac.exe" if WINDOWS else "javac"
    for home in candidates:
        if (home / "bin" / javac).is_file() and (jdk_feature(home) or 0) >= feature:
            return home
    raise Unavailable(f"needs JDK {feature} or newer, and none was found in JAVA_HOME, JAVA{feature}_HOME, the javac on PATH, "
                      f"~/.mmt/jdks or the usual install folders. Install one (any Temurin {feature}+), or set JAVA{feature}_HOME to it.")


def jar_port():
    out = ROOT / ".runs" / "jarport-tests"
    asm = SEP.join(port_tools.ASM)
    tests = jar_port_tests()
    home = jdk(25)
    yield javac("--release", "25", "-encoding", "UTF-8", "-cp", asm, "-d", str(out), *port_tools.sources(), *tests, home=home)
    yield Together(java("-cp", str(out) + SEP + asm, Path(test).stem, str(out / "scratch" / Path(test).stem), home=home) for test in tests)


def console_accessor():
    out = ROOT / ".runs" / "console-tests"
    # Console is compiled by the game's own Java 8 JVM, so its source must stay at that level.
    yield javac("--release", "8", "-encoding", "UTF-8", "-d", str(out), "tools/agent/Console.java",
                "tools/tests/ConsoleAccessorTest.java")
    yield java("-cp", str(out), "ConsoleAccessorTest", ".")


def agent_world_open():
    out = ROOT / ".runs" / "agent-world-open-tests"
    asm = SEP.join(["tools/perf/lib/asm-9.9.jar", "tools/perf/lib/asm-commons-9.9.jar"])
    yield javac("-nowarn", "-encoding", "UTF-8", "-cp", asm, "-sourcepath", SEP.join(["tools/agent", "tools/cli", "tools"]), "-d", str(out),
                "tools/agent/PortableAgent.java", "tools/tests/AgentWorldOpenTest.java")
    yield java("-cp", str(out) + SEP + asm, "AgentWorldOpenTest")


def legacy_agent():
    """Windows: the legacy launch agent in JAVA_TOOL_OPTIONS keeps system properties strings, and Gradle still runs."""
    root = fixture("legacy-agent")
    prepared = subprocess.run(java("-jar", JAR, "legacy-agent", "--root", root, "--json"), cwd=ROOT,
                              capture_output=True, text=True)
    agent = json.loads(prepared.stdout.strip().splitlines()[-1])["result"]["agent"]
    env = {"JAVA_TOOL_OPTIONS": f'"-javaagent:{agent}=legacy" -Dmmt.background=true -Dmmt.quiet=true'}
    yield java("tools/tests/LegacyAgentPropertiesTest.java"), env
    yield java("-jar", JAR, "fast", "--root", root, "--refresh", "--json"), env


def on_fixture(check, *command):
    """A check that runs one command on its own copy of the demo mod; {root} in command is the copy."""
    def commands():
        root = fixture(check)
        yield [root if part == "{root}" else part for part in command]
    return commands


# name -> (needs the jar, systems or None for all, commands or a generator of them). The slowest checks come first, so
# they start first when there are more checks than cores.
CHECKS = {
    "package": (False, None, [java("tools/cli/Bootstrap.java", ".", "package", "--json")]),
    "self-test": (True, None, [(java("-jar", JAR, "self-test", "--root", "."), {} if WINDOWS else {"LC_ALL": "C"})]),
    "fast": (True, None, on_fixture("fast", *java("-jar", JAR, "fast", "--root", "{root}", "--json"))),
    "port-plan": (True, None, on_fixture("port-plan", *java("tools/tests/PortPlanCacheTest.java", "{root}", JAR))),
    "legacy-agent": (True, ("nt",), legacy_agent),
    "fastlane-isolation": (True, None, [java("tools/tests/FastLaneIsolationTest.java", ".", JAR)]),
    "build-setup": (True, None, [java("tools/tests/BuildSetupTest.java", ".", JAR)]),
    "jar-port": (False, None, jar_port),
    "fingerprint": (True, None, [[sys.executable, "bench/test_fingerprint.py", JAR]]),
    "runtime-natives": (True, ("posix",), [java("tools/tests/RuntimeNativesTest.java", ".", JAR)]),
    "hidden-window": (True, None, on_fixture("hidden-window", *java("tools/tests/BackgroundWindowsTest.java", "{root}", JAR))),
    "python": (False, None, [Together([sys.executable, p] for p in PYTHON)]),
    "copies": (False, None, [[sys.executable, "bench/copies.py"]]),
    "port-preflight": (False, None, port_preflight),
    "console-accessor": (False, None, console_accessor),
    "agent-world-open": (False, None, agent_world_open),
    "launcher": (False, ("nt",), [[sys.executable, "tools/ci/launcher_experiments.py"]]),
    "checkout-data": (True, None, checkout_data),
    "zip": (True, None, [java("tools/tests/PackageAcceptanceTest.java", ZIP, ZIP + ".sha256", tempfile.gettempdir())]),
}


ASM_JARS = list(port_tools.ASM)
# The files each check that compiles its own sources reads; a check not named here uses the packaged jar.
READS = {
    # What jscpd reads (.jscpd.json): the tools' and the bench's own code.
    "copies": lambda: [f for f in git_files() if f.endswith((".java", ".py")) and f.startswith(("tools/", "bench/"))] + [".jscpd.json", "bench/copies.json"],
    "port-preflight": lambda: port_tools.sources() + PREFLIGHT_TESTS + ASM_JARS,
    # The stand-ins the jar-port tests compile as they read: the JDK's, and 1.19.4's navigation on 1.19.3 (JarPortNavStandinTest).
    "jar-port": lambda: port_tools.sources() + jar_port_tests() + ASM_JARS
                        + [f for f in git_files() if f.startswith(("knowledge/standins/jdk/", "knowledge/standins/1.19.4-1.19.3/net/minecraft/client/gui/"))],
    "console-accessor": ["tools/agent/Console.java", "tools/tests/ConsoleAccessorTest.java"],
    # Compiled with tools/agent, tools/cli and tools on its source path: any of them may be compiled in.
    "agent-world-open": lambda: [f for f in git_files() if f.startswith(("tools/agent/", "tools/cli/")) or f.count("/") == 1 and f.startswith("tools/") and f.endswith(".java")]
                                + ["tools/tests/AgentWorldOpenTest.java"] + ASM_JARS,
}
# Files outside the jar that a check using the jar reads too: the performance experiments (each folder of bench/ holding an
# experiment*.json, with the gates and worlds it names) and the kept runs.
def experiment_files():
    folders = {f.rsplit("/", 1)[0] + "/" for f in git_files() if f.startswith("bench/") and f.count("/") == 2 and f.rsplit("/", 1)[1].startswith("experiment")}
    return [f for f in git_files() if f.startswith(tuple(folders))]


ALSO_READS = {
    "checkout-data": lambda: experiment_files() + ["coverage/perf-runs.json"],
    "launcher": experiment_files,
}
# What decides which checks run and how: a change to these runs every check.
RUNNER = ("bench/run_tests.py", "bench/port_tools.py", ".github/workflows/portable.yml")
# Files no packaged jar carries and no jar check reads but those in ALSO_READS: the docs, the bench scripts and cloud-run (the python check runs those), CI,
# the desktop app and the generated site.
NOT_IN_JAR = ("docs/", "bench/", ".github/", ".claude/", "app/", "tools/cloud-run", "coverage/roadmap", "coverage/ledger.json", "coverage/measures.json",
              "coverage/perf-runs.json", "ROADMAP.md")


def git_files():
    return subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True).stdout.split()


def changed_files(base=None):
    """Files this branch changed against base (else where it left origin/main), committed, staged, unstaged or new; None
    without git or when base is not there."""
    base = base or subprocess.run(["git", "merge-base", "HEAD", "origin/main"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    if not base or subprocess.run(["git", "rev-parse", "--verify", "--quiet", base + "^{commit}"], cwd=ROOT, capture_output=True).returncode:
        return None
    listed = subprocess.run(["git", "diff", "--no-renames", "--name-only", base], cwd=ROOT, capture_output=True, text=True).stdout.split()
    new = subprocess.run(["git", "ls-files", "--others", "--exclude-standard"], cwd=ROOT, capture_output=True, text=True).stdout.split()
    return sorted(set(listed) | set(new))


def in_jar(path):
    return not path.startswith(NOT_IN_JAR) and not (path.endswith(".md") and path != "PERFORMANCE.md")


def affected(changed, names):
    """The checks of names that changed files can affect, and what self-test runs: None for every suite, else a list."""
    if set(changed) & set(RUNNER):
        return list(names), None
    reads = {n: set(r() if callable(r) else r) for n, r in READS.items()}
    also = {n: set(r()) for n, r in ALSO_READS.items()}
    picked = [n for n in names if n == "python" or n in reads and reads[n] & set(changed) or n in also and also[n] & set(changed)]
    if any(CHECKS[n][0] for n in picked) and "package" in names and "package" not in picked:
        picked.append("package")
    porter = {f for f in port_tools.sources() if f.startswith(port_tools.PORT)} | set(jar_port_tests())
    jar = [f for f in changed if in_jar(f)]
    if any(f not in porter for f in jar):
        return picked + [n for n in names if n not in READS and n not in picked and n != "python"], None
    if jar:
        return picked + [n for n in ("package", "self-test") if n in names and n not in picked], ["port"]
    return picked, None


def execute(name, item, rank):
    """Runs one command when a slot is free; its report line (with the end of its output when it failed) and whether it passed."""
    command, env = item if isinstance(item, tuple) else (item, {})
    SLOTS.take(rank)
    try:
        started = time.monotonic()
        done = subprocess.run(command, cwd=ROOT, env={**os.environ, **env}, capture_output=True, text=True,
                              encoding="utf-8", errors="replace")
        took = time.monotonic() - started
    finally:
        SLOTS.give()
    shown = " ".join(str(c) for c in command)
    if done.returncode:
        return f"FAIL {name} ({took:.0f} s): {shown}\n{done.stdout[-4000:]}{done.stderr[-4000:]}", False
    last = (done.stdout.strip().splitlines() or [""])[-1][:160]
    return f"ok   {name} ({took:.0f} s): {shown[-90:]}  {last if not last.startswith('{') else ''}", True


def run(name, suites=None, rank=0):
    """Runs every command of one check, side by side where they share nothing; its report and whether all passed.
    A failed compile stops the check, since its tests cannot run; a failed test does not stop the next."""
    _, _, commands = CHECKS[name]
    if name == "self-test" and suites:
        commands = [(java("-jar", JAR, "self-test", "--root", ".", "--only", ",".join(suites)), {} if WINDOWS else {"LC_ALL": "C"})]
    started = time.monotonic()
    lines, passed = [], True
    try:
        for item in (commands() if callable(commands) else commands):
            if isinstance(item, Together):
                with ThreadPoolExecutor(CORES) as pool:
                    results = list(pool.map(lambda one: execute(name, one, rank), item))
            else:
                results = [execute(name, item, rank)]
            lines += [line for line, _ in results]
            if not all(ok for _, ok in results):
                passed = False
                if not isinstance(item, Together) and Path((item[0] if isinstance(item, tuple) else item)[0]).stem == "javac":
                    break
    except Unavailable as missing:
        lines.append(f"FAIL {name}: {missing}")
        passed = False
    except Exception as error:  # a generator that prepares its commands (a fixture copy, legacy-agent's agent) failed
        lines.append(f"FAIL {name}: {type(error).__name__}: {error}")
        passed = False
    lines.append(f"{'ok  ' if passed else 'FAIL'} {name} done in {time.monotonic() - started:.0f} s")
    return "\n".join(lines), passed


def run_all(names, suites):
    """Runs package, then the other checks side by side, printing each check's block whole; the failed names."""
    printing = threading.Lock()
    failed = []

    def one(name, rank=0):
        report, passed = run(name, suites, rank)
        with printing:
            print(report, flush=True)
            if not passed:
                failed.append(name)
        return passed

    # package runs alone first: every jar check waits on it, and it builds slower (12 s, not 4 s) beside the others.
    rest = [n for n in names if n != "package"]
    if "package" in names and not one("package"):
        jar = [n for n in rest if CHECKS[n][0]]
        if jar:
            print(f"FAIL not run, as package failed: {', '.join(jar)}", flush=True)
            failed.extend(jar)
        rest = [n for n in rest if n not in jar]
    # Each lane runs its checks in order: the GRADLE checks are one lane, started where the first of them is listed.
    gradle = [n for n in GRADLE if n in rest]
    lanes = [gradle if gradle and n == gradle[0] else [n] for n in rest if n not in gradle[1:]]
    with ThreadPoolExecutor(len(lanes) or 1) as pool:
        list(pool.map(lambda rank: [one(n, rank) for n in lanes[rank]], range(len(lanes))))
    return [n for n in names if n in failed]


def main(argv=None):
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", help="comma-separated check names")
    ap.add_argument("--all", action="store_true", help="every check (the default in CI without --base)")
    ap.add_argument("--base", help="the checks the changes since this commit can affect (CI: a pull request's merge against its base, HEAD^1)")
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
    changed = None if args.only or args.all or os.environ.get("CI") and not args.base else changed_files(args.base)
    if args.base and changed is None and not (args.only or args.all):
        print(f"{args.base} is not a commit here: running every check", flush=True)
    if changed is not None:
        names, suites = affected(changed, names)
        names = [n for n in CHECKS if n in names]
        print(f"{len(changed)} files changed against {args.base or 'origin/main'}: running {', '.join(names)}"
              + (f" (self-test: {', '.join(suites)})" if suites else "") + "; --all runs every check", flush=True)
    started = time.monotonic()
    try:
        failed = run_all(names, suites)
    finally:
        stop_fixture_daemons()
    print(f"{len(names) - len(failed)} of {len(names)} checks passed in {time.monotonic() - started:.0f} s"
          + (f"; failed: {', '.join(failed)}" if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

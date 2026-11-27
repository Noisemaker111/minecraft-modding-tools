"""Every javac mmt starts, and every short java the checks start, goes without HotSpot's optimizing compiler.

A JVM that lives seconds spends most of its CPU on optimized code it exits before using: compiling mmt's own jar took 63
CPU-seconds with it and 13 without, and one round of the offline checks used every core of a 24-thread PC for minutes.
Runtimes.Jdk.javac() / quickJava() (Java) and run_tests.java() / javac() (Python) add the setting; this fails on a
command that starts javac another way.

    python bench/test_quick_jvm.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "bench"))
import port_tools  # noqa: E402

# A javac path made into a command word, outside Runtimes, which makes the quick one.
JAVA = re.compile(r'"javac\.exe" : "javac"\)\)\.toString\(\)')
# A Python command list that starts with javac or java by hand.
PYTHON = re.compile(r'\[\s*(?:"javac"|"java"|str\(\w+ / "bin" / "javac?"\))\s*,')

bad = []
for f in sorted((ROOT / "tools").rglob("*.java")):
    if f.name == "Runtimes.java":
        continue
    for n, line in enumerate(f.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
        if JAVA.search(line):
            bad.append(f"{f.relative_to(ROOT).as_posix()}:{n}: use Runtimes.Jdk.javac(...) (or quickJava for a short java run)")
for f in [ROOT / "bench" / "run_tests.py", ROOT / "bench" / "portboard" / "porter.py", ROOT / "bench" / "learn_diff.py"]:
    for n, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
        if PYTHON.search(line) or "javac" in line and "subprocess.run([javac," in line and "QUICK" not in line:
            bad.append(f"{f.relative_to(ROOT).as_posix()}:{n}: use run_tests.java()/javac() or add port_tools.QUICK")

sys.path.insert(0, str(ROOT / "bench"))
import run_tests  # noqa: E402
for check in ("package", "self-test", "port-preflight", "jar-port", "checkout-data"):
    commands = run_tests.CHECKS[check][2]
    if callable(commands):
        commands = list(commands())
    for command in commands:
        for one in (command if isinstance(command, run_tests.Together) else [command]):
            argv = one[0] if isinstance(one, tuple) else one
            if Path(argv[0]).stem in ("java", "javac") and not any(port_tools.QUICK in a for a in argv):
                bad.append(f"run_tests {check} starts {Path(argv[0]).stem} without {port_tools.QUICK}: {' '.join(map(str, argv[:4]))}")

if bad:
    print(f"{len(bad)} JVM start(s) with the optimizing compiler on:\n  " + "\n  ".join(bad))
    sys.exit(1)
print("quick JVM: every javac and short java the tools and checks start goes without the optimizing compiler")

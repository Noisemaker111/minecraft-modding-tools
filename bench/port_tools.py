"""The porter's sources: every .java under tools/port, and the three tools/cli files compiled with them.

This is the one list of them on the python side (tools/cli/JavaTools.java holds the same rule for mmt itself). The
checks, the jar scoreboard and learn_diff.py all read it, so a file added under tools/port is compiled, shipped and
hashed by being there. Nine hand-kept copies of the list came before it; a porter file missing from one of them
compiled in the checks and failed in the shipped jar.

    sources()                  all of them, as paths from the repository root
    sources("learn", "names")  tools/port's own files and those two folders: what a cache entry built by them is keyed on
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PORT = "tools/port"
WITH = ("tools/cli/Json.java", "tools/cli/Route.java", "tools/cli/Versions.java")
ASM = ("tools/perf/lib/asm-9.9.jar", "tools/perf/lib/asm-commons-9.9.jar")
# A JVM that lives seconds (javac, a test) without HotSpot's optimizing compiler: its background compiling is thrown away
# when the process ends, and was most of the CPU (compiling mmt's jar: 63 CPU-s with it, 13 without). Runtimes.QUICK.
QUICK = "-XX:TieredStopAtLevel=1"


def sources(*folders, root=ROOT):
    port = Path(root) / PORT
    if not port.is_dir():
        raise SystemExit(f"{port} is missing: the porter's sources live there (ARCHITECTURE.md)")
    if folders:
        found = [p for p in port.glob("*.java")]
        for folder in folders:
            found += (port / folder).rglob("*.java")
    else:
        found = port.rglob("*.java")
    return sorted(p.relative_to(root).as_posix() for p in found) + list(WITH)

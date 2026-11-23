"""No new copied code in the tools: fails when a file holds more copied lines than bench/copies.json records for it.

    python bench/copies.py            the check (bench/run_tests.py runs it as "copies")
    python bench/copies.py --update   after merging copies: records the lower counts (it never records more)

jscpd finds the copies (.jscpd.json: runs of 70 tokens or more that appear twice in tools/ and bench/). A copy is two
places that must change together with nothing saying so; the fix is one home for the code, called from both. Which
home: ARCHITECTURE.md says where each kind of thing lives. What was copied when this check was added is recorded per
file, so only new copies fail, and merging an old one lowers the record (--update, in the same change).

Not read, and why: tests; tools/packcheck/game and tools/packcheck/fingerprint (one whole file per generation of the
game's API, each compiled alone inside that game); bench/**/*.java (single-file scripts handed to a running game,
which cannot share a file).

Needs Node (npx): GitHub's runners have it. Without it this prints that it did not run and passes, except in CI.
"""
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RECORD = ROOT / "bench" / "copies.json"
JSCPD = "jscpd@4.3.0"


def measure():
    """Each file's copied lines (a line counted once however many copies it is in), and every pair of copies found."""
    out = ROOT / ".runs" / "copies"
    shutil.rmtree(out, ignore_errors=True)
    done = subprocess.run([shutil.which("npx"), "--yes", JSCPD, "--config", ".jscpd.json", "--output", str(out), "tools", "bench"], cwd=ROOT, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    report = out / "jscpd-report.json"
    if not report.is_file():
        raise SystemExit(f"jscpd wrote no report:\n{done.stdout[-2000:]}{done.stderr[-2000:]}")
    pairs = json.loads(report.read_text(encoding="utf-8"))["duplicates"]
    copied = {}
    for pair in pairs:
        for side in (pair["firstFile"], pair["secondFile"]):
            copied.setdefault(side["name"].replace("\\", "/"), set()).update(range(side["start"], side["end"] + 1))
    return {name: len(lines) for name, lines in sorted(copied.items())}, pairs


def main():
    if not shutil.which("npx"):
        print("copies: not run, Node (npx) is not installed here; CI runs it")
        return 1 if os.environ.get("CI") else 0
    recorded = json.loads(RECORD.read_text(encoding="utf-8"))["copiedLines"] if RECORD.is_file() else {}
    now, pairs = measure()
    more = {name: n - recorded.get(name, 0) for name, n in now.items() if n > recorded.get(name, 0)}
    total, before = sum(now.values()), sum(recorded.values())
    if "--update" in sys.argv:
        if total > before and RECORD.is_file():
            print(f"{total} copied lines, {total - before} more than recorded: --update only records fewer. Merge the new copies first.")
            return 1
        RECORD.write_text(json.dumps({"about": "Copied lines per file that python bench/copies.py found when they were last merged; it fails on more.",
                                      "copiedLines": now}, indent=1) + "\n", encoding="utf-8")
        print(f"recorded {total} copied lines in {len(now)} files (was {before})")
        return 0
    if more and total > before:
        print(f"{total - before} more copied lines than recorded. Keep the code in one place and call it from the other (ARCHITECTURE.md says where each kind of thing lives):")
        shown = 0
        for pair in pairs:
            a, b = pair["firstFile"], pair["secondFile"]
            names = [a["name"].replace("\\", "/"), b["name"].replace("\\", "/")]
            if any(n in more for n in names):
                shown += 1
                if shown <= 30:
                    print(f"  {names[0]}:{a['start']}-{a['end']} is {names[1]}:{b['start']}-{b['end']}")
        if shown > 30:
            print(f"  and {shown - 30} more (.runs/copies/jscpd-report.json has them all)")
        return 1
    if total < before or more:
        print(f"{before - total} copied lines are gone or moved: run python bench/copies.py --update and commit bench/copies.json, so they stay gone")
        return 1
    print(f"ok: no file has more copied lines than recorded ({total} lines in {len(now)} files)")
    return 0


if __name__ == "__main__":
    sys.exit(main())

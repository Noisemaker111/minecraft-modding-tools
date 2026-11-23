"""Main's saved table: what a branch's table changes against it, and the measures recorded from it."""
import json
import re
import subprocess
import sys
from datetime import date
from pathlib import Path

from portboard import anyversion, settings

# The measures main's table is recorded in, each with the totals it reads: (link, mods).
MAIN_MEASURES = {
    "port.jar-links.every-update": lambda t: (t["total"]["link"], t["total"]["mods"]),
    "port.jar-links.big-updates": lambda t: (t["total"]["big"]["link"], t["total"]["big"]["mods"]),
    "port.jar-links.newest": lambda t: (t["total"]["newest"]["link"], t["total"]["newest"]["mods"]),
    "port.jar-links.any-version": lambda t: (t.get("anyVersion", {}).get("every", 0), t.get("anyVersion", {}).get("mods", 0)),
}
# The headline counts compare names first, one line each.
HEADS = len(MAIN_MEASURES)


def compare(before, after):
    """What one table changes against another, as lines: the headline counts, each update and jump whose count
    moved, and each type of break whose finishes moved, the lower ones named first."""
    def num(n):
        return f"{n:,}"

    def heads(t):
        return {"every update": (t["total"]["link"], t["total"]["mods"]), "big updates": (t["total"]["big"]["link"], t["total"]["big"]["mods"]),
                "newest game": (t["total"]["newest"]["link"], t["total"]["newest"]["mods"]),
                "any version": MAIN_MEASURES["port.jar-links.any-version"](t)}

    lines = []
    for name, (b, a) in ((k, (heads(before)[k], v)) for k, v in heads(after).items()):
        delta = a[0] - b[0]
        lines.append(f"{name}: {num(b[0])} -> {num(a[0])} of {num(a[1])}" + (f" ({delta:+,})" if delta else "") + ("" if b[1] == a[1] else f" (was of {num(b[1])})"))
    for kind in ("updates", "jumps"):
        old = {(r["loader"], r["from"], r["to"]): r for r in before.get(kind, [])}
        moved = [(r["modsThatLink"] - old[k]["modsThatLink"], f"{r['loader']} {r['from']} -> {r['to']} {old[k]['modsThatLink']} -> {r['modsThatLink']}")
                 for r in after.get(kind, []) for k in [(r["loader"], r["from"], r["to"])] if k in old and r["modsThatLink"] != old[k]["modsThatLink"]]
        lower = [text for d, text in moved if d < 0]
        higher = [text for d, text in sorted(moved, key=lambda m: -m[0]) if d > 0]
        if lower:
            lines.append(f"{kind} lower: " + "; ".join(lower))
        if higher:
            lines.append(f"{kind} higher: " + "; ".join(higher))
        # Which mods moved, where both tables name the mods that link (tables made before 2026-11-22 do not).
        for r in after.get(kind, []):
            k = (r["loader"], r["from"], r["to"])
            if k in old and r.get("links") is not None and old[k].get("links") is not None:
                gained, lost = sorted(set(r["links"]) - set(old[k]["links"])), sorted(set(old[k]["links"]) - set(r["links"]))
                if gained or lost:
                    lines.append(f"  {r['loader']} {r['from']} -> {r['to']}: " + "; ".join(
                        x for x in (f"newly link {', '.join(gained)}" if gained else "", f"no longer link {', '.join(lost)}" if lost else "") if x))
    old = {t["type"]: t for t in before.get("types", [])}
    new = {t["type"]: t for t in after.get("types", [])}
    types = [(new.get(k, {}).get("finishes", 0) - old.get(k, {}).get("finishes", 0), k) for k in sorted(set(old) | set(new))]
    moved = [f"'{k}' finishes {old.get(k, {}).get('finishes', 0)} -> {new.get(k, {}).get('finishes', 0)}, mods {old.get(k, {}).get('mods', 0)} -> {new.get(k, {}).get('mods', 0)}"
             for d, k in sorted(types, key=lambda t: t[0]) if d]
    if moved:
        lines.append("types: " + "; ".join(moved))
    if not lines[HEADS:]:
        lines.append("no update, jump or type of break moved")
    return lines


def record_main(table_path, commit, run, folder, day=None):
    """Saves a sweep of a main commit as main's table (FOLDER/all.json) and adds its totals to the port.jar-links points
    (FOLDER/points.json), each point's note saying what moved against the table it replaces. FOLDER is scoreboard/ in a
    checkout of the generated branch; bench/generated.py publish runs this for the cloud sweep a push to main started."""
    sys.path.insert(0, str(settings.ROOT / "bench"))
    import measures as M
    folder = Path(folder)
    folder.mkdir(parents=True, exist_ok=True)
    main_table, points_path = folder / "all.json", folder / "points.json"
    table = json.loads(Path(table_path).read_text(encoding="utf-8"))
    table = {"commit": commit, "run": run, **{k: v for k, v in table.items() if k not in ("commit", "run")}}
    previous = json.loads(main_table.read_text(encoding="utf-8")) if main_table.is_file() else None
    if previous and previous.get("commit") and previous["commit"] != commit and subprocess.run(
            ["git", "merge-base", "--is-ancestor", previous["commit"], commit], cwd=settings.ROOT, capture_output=True).returncode != 0:
        # Sweeps of main can finish out of order: a newer commit's table is already there.
        print(f"main's table already holds {previous['commit'][:10]}, which {commit[:10]} does not follow; not recorded")
        return None
    kept = json.loads(points_path.read_text(encoding="utf-8")) if points_path.is_file() else {}
    subject = subprocess.run(["git", "log", "-1", "--format=%s", commit], cwd=settings.ROOT, capture_output=True, text=True).stdout.strip()
    pr = re.search(r"\(#(\d+)\)\s*$", subject)
    at = f"main at {commit[:10]}" + (f" (#{pr.group(1)})" if pr else "")
    moved = [line for line in compare(previous, table)[HEADS:] if not line.startswith(("no update", "  "))] if previous else []
    for mid, read in MAIN_MEASURES.items():
        link, mods = read(table)
        points = kept.setdefault(mid, [])
        last = points[-1] if points else None
        note = (f"{link - last['value']:+,} on {last['value']:,}" if last and last["value"] != link else "the same") + (": " + "; ".join(moved) if moved else "")
        points.append({"date": day or date.today().isoformat(), "value": link, "n": mods, "note": note[:600], "source": f"tools/cloud-run exposure {run} on {at}"})
    points_path.write_text(M.dump(kept), encoding="utf-8", newline="\n")
    main_table.write_text(json.dumps(table, indent=1) + "\n", encoding="utf-8")
    (folder / anyversion.ANY_VERSION_PAGE).write_text(anyversion.any_version_page(table), encoding="utf-8", newline="\n")
    print(f"main's table is {at}: " + "; ".join(compare(previous, table)[:HEADS] if previous else [f"{read(table)[0]:,}" for read in MAIN_MEASURES.values()]))
    return table

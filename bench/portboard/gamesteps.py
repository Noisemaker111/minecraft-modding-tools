"""How much of the game each update links (classes, methods, fields), update by update."""
import json
import re
import subprocess

from portboard import cachekeys, gamereleases, porter, settings


HOP = re.compile(r"linked (\d+)/(\d+) classes, (\d+)/(\d+) methods, (\d+)/(\d+) fields")


def steps(frm, to):
    classes = porter.compile_tools()
    versions = gamereleases.chain(frm, to)
    target = settings.ROOT / ".runs" / "exposure" / f"steps-{frm}-{to}-{cachekeys.tools_key(*settings.MAP_SOURCES)}.json"
    if not target.is_file():
        target.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run([porter.java(), f"-Xmx{settings.HEAP}g", "-cp", str(classes), "GameDiff", "--chain", ",".join(versions), "--out", str(target)], check=True)
    route = json.loads(target.read_text(encoding="utf-8"))
    # Rows per kind of replacement, and each member with no counterpart counted once whatever gave it one ("learned"
    # also lists members whose signature changed: those have a counterpart, so they are not in "gone").
    learned, members = {}, {}
    for section in ("rewrites", "chains", "classRewrites", "learned"):
        rows_key = "learnerRows" if section == "learned" else section  # "learned" is the per-update member total
        for r in route.get(section, []):
            learned.setdefault(r["step"], {}).setdefault(rows_key, 0)
            learned[r["step"]][rows_key] += 1
            if not r.get("linked"):
                members.setdefault(r["step"], set()).add((r.get("owner", ""), r["from"], r.get("desc", "")))
    rows, gone, taught = [], 0, 0
    for step, line in route.get("hops", {}).items():
        m = HOP.search(line)
        if not m:
            continue
        n = list(map(int, m.groups()))
        unlinked = (n[1] - n[0]) + (n[3] - n[2]) + (n[5] - n[4])
        learnt = len(members.get(step, ()))
        if unlinked:
            rows.append({"step": step, "gone": unlinked, "learned": learnt, **learned.get(step, {})})
        gone, taught = gone + unlinked, taught + learnt
    summary = {"from": frm, "to": to, "updates": len(route.get("hops", {})), "gone": gone, "learned": taught, "steps": rows}
    for r in rows:
        print(f"  {r['step']:<22} {r['gone']:>6} with no counterpart  {r['learned']:>4} replacements learned")
    print(f"{summary['updates']} updates: {gone} classes, methods and fields have no counterpart in the next release; "
          f"the game's own code gave {taught} of them a replacement the porter applies")
    out = settings.ROOT / "bench" / "exposure" / "steps.json"
    out.write_text(json.dumps(summary, indent=1) + "\n", encoding="utf-8")
    print(f"saved {out}")
    return summary

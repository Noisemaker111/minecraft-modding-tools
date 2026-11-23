"""Main's saved table: what a branch's table changes against it, and the measures recorded from it."""
import json
import re
import subprocess
import sys
from datetime import date
from pathlib import Path

from portboard import anyversion, board, settings


def slug(jar):
    """A pinned jar's Modrinth slug: a release pins one file per mod, named SLUG--FILE."""
    return jar.split("--", 1)[0]


def held_types(row):
    """A row of main's table's mods held back, by slug, each with the types of break holding it; None for a table made
    before rows named them (2026-11-23)."""
    if row.get("heldBy") is None:
        return None
    held = {}
    for kind, slugs in row["heldBy"].items():
        for s in slugs:
            held.setdefault(s, []).append(kind)
    return {s: sorted(kinds) for s, kinds in held.items()}


def row_moved(row, after):
    """verdicts_moved against a row of main's table (table_row), which names the mods that link and the types of break
    holding back each other mod it read: over the mods both read, those that newly link, no longer link, or are held by
    other types. A row that names no held mods is taken to have read the mods this run read."""
    new = after.get("verdicts", {})
    links, held = {slug(m) for m in row.get("links") or ()}, held_types(row)
    both = sorted(m for m in new if held is None or slug(m) in links or slug(m) in held)
    moved = {"newly link": [m for m in both if slug(m) not in links and not new[m]["left"]],
             "no longer link": [m for m in both if slug(m) in links and new[m]["left"]],
             "other types left": [m for m in both if held is not None and slug(m) in held and new[m]["left"] and held[slug(m)] != sorted(new[m].get("types", []))]}
    moved["same"] = len(both) - sum(len(v) for v in moved.values())
    return moved


def compare_update(summary, main=None):
    """One update's summary against main's row for it, on one line: the count, and the mods that moved."""
    table, what = main if main is not None else main_table()
    head = f"{summary['loader']} {summary['from']} -> {summary['to']}: {summary['modsThatLink']}/{summary['modsRead']} link"
    row = table_row(table, summary["loader"], summary["from"], summary["to"]) if table else None
    if row is None:
        return f"{head}; main's table has no row for it ({what})"
    moved = row_moved(row, summary)
    return (f"{head}, main {row['modsThatLink']}/{row['modsRead']} ({summary['modsThatLink'] - row['modsThatLink']:+d}; {what})"
            + "".join(f"; {k} {', '.join(v)}" for k, v in moved.items() if k != "same" and v))


def table_row(table, loader, frm, to):
    """A table's row for one port: an update, a jump or an any-version pair (rows since 2026-11-23); None when it has none."""
    for r in table.get("updates", []) + table.get("jumps", []) + table.get("anyVersionPairs", []):
        if (r.get("loader"), r.get("from"), r.get("to")) == (loader, frm, to):
            return r
    return None


GENERATED = "origin/generated"
MAIN_TABLES = {}


def git_out(*args):
    """A git command's output in this checkout, or None when it fails (offline, no such commit, or a --at copy with no .git)."""
    done = subprocess.run(["git", *args], cwd=settings.ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return done.stdout.strip() if done.returncode == 0 else None


def main_table(head="HEAD", git=git_out, fetch=True):
    """Main's jar scoreboard for a branch, read instead of porting main's mods again: the newest table on the generated
    branch (bench/generated.py) whose commit the branch contains, its base on main or one before it. Returns (table, what
    it is), or (None, why there is none)."""
    if head in MAIN_TABLES:
        return MAIN_TABLES[head]
    if git("rev-parse", "--is-shallow-repository") == "true":
        # The cloud sweep's checkout: it has no history to find main's table by, and tools/cloud-run compares each job's summary itself.
        MAIN_TABLES[head] = (None, "a shallow clone cannot tell which of main's tables it has")
        return MAIN_TABLES[head]
    if fetch:
        git("fetch", "--quiet", "origin", f"+refs/heads/generated:refs/remotes/{GENERATED}")
    found = (None, "main's table is not on this machine (no origin/generated)")
    for rev in (git("log", "--format=%H", "-n", "60", GENERATED, "--", "scoreboard/all.json") or "").split():
        text = git("show", f"{rev}:scoreboard/all.json")
        table = json.loads(text) if text else {}
        commit = table.get("commit")
        if not commit or git("merge-base", "--is-ancestor", commit, head) is None:
            found = (None, "no table on the generated branch is of a commit this branch has")
            continue
        base = git("merge-base", head, "origin/main")
        between = git("rev-list", "--count", f"{commit}..{base}") if base else None
        found = (table, f"main's table at {commit[:10]}" + (f", {between} main commits before this branch's base {base[:10]}" if between and between != "0" else ""))
        break
    MAIN_TABLES[head] = found
    return found


def against(work, kind, summary, main=None):
    """What a jars summary is compared with, and how its mods moved: the run before (a whole run whose porter inputs
    differed), for a partial run the last whole run, else main's row for the update (main_table). Returns (what, moved),
    or (None, None) when there is nothing to compare with. main: (table, what), main_table() when not given."""
    before = work / ("before.json" if not kind else "last.json")
    if before.is_file():
        return f"the {'run before' if not kind else 'last whole run'} ({before})", board.verdicts_moved(json.loads(before.read_text(encoding="utf-8")), summary)
    table, what = main if main is not None else main_table()
    row = table_row(table, summary["loader"], summary["from"], summary["to"]) if table else None
    if row is None:
        return None, None
    return what, row_moved(row, summary)


def narrowed(pinned, work, kind=None, mods=None, main=None):
    """The jars a narrow run ports: those the last whole run here (else main's row for the update) held back with a type of
    break whose text has kind in it, and those of the slugs in mods. Every jar when neither is asked."""
    loader, frm, to = work.name.split("-", 3)[1:]
    picked = None
    if kind:
        last = work / "last.json"
        if last.is_file():
            held = {slug(mod): v.get("types", []) for mod, v in json.loads(last.read_text(encoding="utf-8")).get("verdicts", {}).items()}
        else:
            table, what = main if main is not None else main_table()
            row = table_row(table, loader, frm, to) if table else None
            held = held_types(row) if row else None
            if held is None:
                raise SystemExit(f"--type needs a whole run of the update, here or in main's table ({what}{'' if row else ', which has no row for it'}): "
                                 f"python bench/exposure.py jars --loader {loader} --from {frm} --to {to}")
        picked = {s for s, types in held.items() if any(kind in t for t in types)}
        if not picked:
            raise SystemExit(f"no mod is held back by a type of break with '{kind}' in it")
    if mods:
        unknown = sorted(set(mods) - {slug(p.name) for p in pinned})
        if unknown:
            raise SystemExit(f"--mods: {loader} {frm} pins no {', '.join(unknown)}")
        picked = (picked or set()) | set(mods)
    return pinned if picked is None else [p for p in pinned if slug(p.name) in picked]


def narrow_hint(loader, frm, to, count, main=None):
    """One line on how to port only the mods a change can move, with the type of break holding back the most mods in
    main's row as the example."""
    table, _ = main if main is not None else main_table()
    row = table_row(table, loader, frm, to) if table else None
    types = {t: len(slugs) for t, slugs in (row or {}).get("heldBy", {}).items()}
    top = max(types.items(), key=lambda kv: (kv[1], kv[0]), default=None)
    example = f"--type '{top[0]}' ports the {top[1]} mods main holds back with it" if top else "--type TEXT ports the mods a type of break holds back"
    return f"porting all {count} mods; to port only those your change can move: {example}, --mods SLUG,... only those"

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

"""One update's scoreboard: every mod ported and linked, what holds each back by type of break, and the whole table over every update."""
import json
import os
import re
import shutil
import subprocess
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import date
from pathlib import Path

from portboard import anyversion, cachekeys, gamereleases, javalevels, modrinth, porter, settings


def nearest(rows, data, said):
    """The to-do list: the mods in the order they finish when every turn completes the one with the fewest references
    left, each with the references that turn adds and what the game's own code did about them. The references the
    most mods share are the wrong order: a mod held back has 15 at the median, so on NeoForge 1.21.1 -> 1.21.2 the
    100 most shared freed 2 mods, and the first 33 in this order freed 21."""
    todo = {m["mod"]: porter.left(m, data) for m in rows}
    todo = {mod: refs for mod, refs in todo.items() if refs}
    fixed, done, order = set(), 0, []
    while todo:
        # Fewest left first; among those, the ones the game's own code answers.
        mod = min(todo, key=lambda k: (len(todo[k] - fixed), sum(1 for ref in todo[k] - fixed if said.get(ref, {}).get("game") not in ANSWERED), k))
        new = sorted(todo[mod] - fixed)
        fixed.update(new)
        freed = sorted(k for k, refs in todo.items() if refs <= fixed)
        for k in freed:
            del todo[k]
        done += len(freed)
        order.append({"mod": mod, "alsoFrees": [k for k in freed if k != mod], "fixes": len(fixed), "modsDone": done,
                      "members": [{"member": ref, **said.get(ref, {})} for ref in new]})
    return order


ANSWERED = {"replaced", "split", "linked", "template"}


def kind_of(member, said):
    """The type of break a reference is: what it is (a call, a mixin target whose signature changed, an abstract method
    not written ...) and what the game's own code did about it. A way to handle a type frees every mod held by it, in
    every update; a rule for one member frees the mods that use that member. A reference into one of the libraries the
    release ships (DataFixerUpper, Brigadier...) is a type of its own: the answer to it lives in that library, not the game."""
    row = said.get(member, {})
    return f"{member.split(': ', 1)[0]}{' (library)' if row.get('part') == 'library' else ''}, game: {row.get('game') or 'not read'}"


def held_by(row, data, said):
    by = {}
    for ref in sorted(porter.left(row, data)):
        by.setdefault(kind_of(ref, said), []).append(ref)
    return by


def by_type(rows, data, said):
    """Each type of break with the mods that have one, the mods it alone holds back (handled, they are done), and
    its most-used references as examples; the types that finish the most mods first."""
    types = {}
    for m in rows:
        refs = porter.left(m, data)
        kinds = {kind_of(ref, said) for ref in refs}
        for ref in refs:
            t = types.setdefault(kind_of(ref, said), {"mods": set(), "finishes": 0, "uses": {}})
            t["mods"].add(m["mod"])
            t["uses"][ref] = t["uses"].get(ref, 0) + 1
        if len(kinds) == 1:
            types[next(iter(kinds))]["finishes"] += 1
    return sorted(({"type": k, "finishes": t["finishes"], "mods": len(t["mods"]), "references": len(t["uses"]),
                    "examples": [ref for ref, _ in sorted(t["uses"].items(), key=lambda kv: (-kv[1], kv[0]))[:3]]} for k, t in types.items()),
                  key=lambda t: (-t["finishes"], -t["mods"], t["type"]))


def shown(member):
    return f"{member['member']}  ({member.get('part', 'game')}: {member.get('game', '?')})"


def receipt(kind, loader, frm, to):
    """Where --save keeps a run's summary: named for the update and the commit, so no two pull requests write one file."""
    commit = subprocess.run(["git", "rev-parse", "--short=10", "HEAD"], cwd=settings.ROOT, capture_output=True, text=True).stdout.strip() or "unknown"
    return settings.ROOT / "bench" / "exposure" / f"{kind}-{loader}-{frm}-{to}-{commit}.json"


def jars(loader, frm, to, save=False, out=None, quiet=False, kind=None):
    """The jar scoreboard: what every top mod's classes and mixins reference, linked against the new release as the JVM and Mixin do.

    A mod whose port was made before with the same inputs is read from the cache (jar_port), so a run after an edit ports
    only what the edit can change. Each run keeps its summary in .runs/exposure/jars-LOADER-FROM-TO/last.json; when the
    porter's inputs differ from the last run's, that one becomes before.json, and the run prints which mods newly link,
    stopped linking or changed their port against it. kind: port only the mods the last run held back with a type of break
    (its text, or part of it), the answer for those mods in seconds; the summary is then partial and is not kept."""
    seconds = {}

    def timed(stage, work, *args):
        started = time.monotonic()
        try:
            return work(*args)
        finally:
            seconds[stage] = round(seconds.get(stage, 0) + time.monotonic() - started, 1)

    pinned = timed("download", modrinth.fetch, loader, frm)
    work = settings.ROOT / ".runs" / "exposure" / f"jars-{loader}-{frm}-{to}"
    last = work / "last.json"
    if kind:
        if not last.is_file():
            raise SystemExit(f"--type needs a run of the whole update first: python bench/exposure.py jars --loader {loader} --from {frm} --to {to}")
        held = {mod for mod, v in json.loads(last.read_text(encoding="utf-8")).get("verdicts", {}).items() if any(kind in t for t in v.get("types", []))}
        pinned = [p for p in pinned if p.name in held]
        if not pinned:
            raise SystemExit(f"no mod of the last run is held back by a type of break with '{kind}' in it")
    porter.compile_tools()  # once, before the threads below that run it
    # Reading the jars' names, the game's change maps and the releases' registry reports need nothing from each other.
    with ThreadPoolExecutor(3) as pool:
        named = pool.submit(timed, "names", porter.mojang_names, loader, frm, pinned)
        mapped = pool.submit(timed, "map", porter.routes, loader, frm, to)
        reported = pool.submit(timed, "reports", porter.registry_reports, porter.stops(loader, frm, to))
        mods, unread = named.result()
        mapped.result()
        reported.result()
    # A run of some mods only (kind) keeps its report apart: load reads the whole update's.
    written = work / ("report.json" if not kind else "report-type.json")
    report = timed("port", porter.jar_port, loader, frm, to, mods, work / "mods", written, False, True) if mods else {"linked": 0, "mods": [], "unlinkedByMods": []}
    shutil.rmtree(work / "mods", ignore_errors=True)  # only the report is read; load ports the mods it starts itself
    if mods:
        report = javalevels.without_platform_copies(report, javalevels.platform_ids(loader, to), {m.name: m for m in mods})
        written.write_text(json.dumps(report, indent=1), encoding="utf-8")
    said = timed("game", porter.game_says, loader, frm, to, written) if report["unlinkedByMods"] else {}
    rows = [m for m in report["mods"] if "error" not in m]
    # What the port left of each mod's data, judged when both releases' registry reports could be made.
    data = {m["mod"]: [u.split(": ", 1)[1] for u in m["data"]] for m in rows} if all("data" in m for m in rows) else None
    data_by = {}
    for mod, refs in (data or {}).items():
        for ref in refs:
            data_by.setdefault(re.sub(r" \(in [^)]*\)$", "", ref), set()).add(mod)
    summary = {
        "loader": loader, "from": frm, "to": to, "modsRead": len(rows), "modsThatLink": report["linked"],
        # Linking and naming nothing in their data that the new release dropped: the stricter count, when the reports ran.
        "modsThatLinkAndData": None if data is None else sum(1 for m in rows if not m["unlinked"] and not data.get(m["mod"])),
        "dataTop": sorted(({"member": k, "mods": len(v)} for k, v in data_by.items()), key=lambda r: -r["mods"])[:20],
        "silentOnly": sum(1 for m in rows if not m["unlinked"] and m["silent"]),
        "brokenUses": sum(r["mods"] for r in report["unlinkedByMods"]),  # distinct references, a mod and its nested jars once
        # The loader's own API (NeoForge with FML and its event bus; Fabric Loader and Fabric API) is linked like the game:
        # the uses of it that break, and the mods that break on nothing else.
        "loaderUses": sum(r["mods"] for r in report["unlinkedByMods"] if javalevels.LOADER_REF.search(r["member"])),
        "modsHeldOnlyByLoader": sum(1 for m in rows if m["unlinked"] and all(javalevels.LOADER_REF.search(u) for u in m["unlinked"])),
        "unreadable": {**unread, **{m["mod"]: m["error"] for m in report["mods"] if "error" in m}},
        "top": [{**r, **said.get(r["member"], {})} for r in report["unlinkedByMods"][:40]],
        # Where this run's time went: download, names, map and reports ran side by side; port and game after them.
        "seconds": seconds,
    }
    order = nearest(rows, data, said)
    summary["types"] = by_type(rows, data, said)
    # Every blocked mod's references by type, so a way to handle several types can be counted by the mods it frees.
    summary["held"] = {m["mod"]: held_by(m, data, said) for m in rows if porter.left(m, data)}
    # Why each blocked mod's mixin handlers did not follow their targets (JarPort.why), for designing the mixin types.
    summary["refused"] = {m["mod"]: m["refused"] for m in rows if porter.left(m, data) and m.get("refused")}
    summary["nearest"] = [{**turn, "members": turn["members"][:25]} for turn in order[:40]]
    summary["finishing"] = [[turn["fixes"], turn["modsDone"]] for turn in order]
    # Each mod's verdict: the references it still needs, the types of break they are, and what its port wrote. Two runs
    # agree on a mod when these are equal; a mod whose port wrote the same jar needs no new proof in game.
    summary["links"] = sorted(m["mod"] for m in rows if not porter.left(m, data))
    summary["verdicts"] = {m["mod"]: {"left": sorted(porter.left(m, data)), "types": sorted({kind_of(r, said) for r in porter.left(m, data)}), "ported": m.get("ported")}
                           for m in rows}
    summary["porter"] = porter.port_key(loader, frm, to) + " " + cachekeys.content_key(*(p.read_bytes() for p in mods))
    if kind:
        summary["partial"] = f"only the {len(mods)} mods the last run held back with '{kind}'"
    before = work / "before.json"
    if not kind and last.is_file() and json.loads(last.read_text(encoding="utf-8")).get("porter") != summary["porter"]:
        os.replace(last, before)
    against = before if not kind else last
    moved = verdicts_moved(json.loads(against.read_text(encoding="utf-8")), summary) if against.is_file() else None
    summary["moved"] = moved
    if not kind:
        work.mkdir(parents=True, exist_ok=True)
        last.write_text(json.dumps(summary, indent=1) + "\n", encoding="utf-8")
    if not quiet:
        if settings.VERIFY:
            print(f"--verify: {report.get('verified', 0)} kept ports were the same as porting them again")
        if moved is not None and (moved["newly link"] or moved["link, port changed"]):
            slugs = sorted({m.split("--", 1)[0] for m in moved["newly link"] + moved["link, port changed"]})
            print(f"prove in game only those: python bench/exposure.py load --loader {loader} --from {frm} --to {to} --mods {','.join(slugs)}"
                  f" (or tools/cloud-run load -f updates={loader}:{frm}:{to} -f mods={','.join(slugs)})")
        if moved is not None:
            print(f"against {'the run before' if not kind else 'the last whole run'} ({against}): "
                  + "; ".join(f"{k} {', '.join(v) if v else 'none'}" for k, v in moved.items() if k != "same") + f"; {moved['same']} mods the same")
        if kind:
            print(summary["partial"])
        print(f"{summary['modsThatLink']}/{summary['modsRead']} {loader} mods link against {to} after the port "
              f"({summary['brokenUses']} broken uses; {summary['silentOnly']} keep an override the game no longer calls)"
              + ("" if data is None else f"; {summary['modsThatLinkAndData']} of them also name nothing in their data that {to} dropped")
              + f"; {summary['loaderUses']} broken uses are of the loader's API, and {summary['modsHeldOnlyByLoader']} mods break on nothing else")
        print("seconds: " + ", ".join(f"{stage} {took:g}" for stage, took in seconds.items()))
        print("\nTypes of break, by the mods each one alone holds back (a way to handle the type finishes them, here and in every update):")
        for t in summary["types"][:10]:
            print(f"  finishes {t['finishes']:>3}, in {t['mods']:>3} mods, {t['references']:>4} references  {t['type']}   e.g. {t['examples'][0]}")
        print("\nNearest to done (each mod with the references it still needs):")
        for turn in summary["nearest"][:12]:
            also = f", and frees {len(turn['alsoFrees'])} more" if turn["alsoFrees"] else ""
            print(f"  {turn['mod']}: {len(turn['members'])} left{also}; {turn['modsDone']} mods done after {turn['fixes']} fixes")
            for member in turn["members"][:6]:
                print(f"      {shown(member)}")
        print("\nShared by the most mods (a fix to how the porter learns reaches these in every update; alone they free few mods):")
        for row in summary["top"][:10]:
            print(f"  {row['mods']:>4} mods  {shown(row)}")
    for path in [p for p in (out, receipt("jars", loader, frm, to) if save else None) if p]:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(summary, indent=1) + "\n", encoding="utf-8")
        if not quiet:
            print(f"saved {path}")
    return summary


def verdicts_moved(before, after):
    """Mods whose verdict differs between two runs of one update, over the mods both read: those that newly link, no
    longer link, still break on other references, or link with another port written."""
    old, new = before.get("verdicts", {}), after.get("verdicts", {})
    both = sorted(set(old) & set(new))
    moved = {"newly link": [m for m in both if old[m]["left"] and not new[m]["left"]],
             "no longer link": [m for m in both if not old[m]["left"] and new[m]["left"]],
             "other references left": [m for m in both if old[m]["left"] and new[m]["left"] and old[m]["left"] != new[m]["left"]],
             "link, port changed": [m for m in both if not old[m]["left"] and not new[m]["left"] and old[m].get("ported") != new[m].get("ported")]}
    moved["same"] = len(both) - sum(len(v) for v in moved.values())
    return moved


def every_update(loaders, only, jobs, out_file=None):
    """jars for every update, every jump and every any-version target of each loader, side by side; joined into one table."""
    todo = [(lo, a, b) for lo in loaders for a, b in gamereleases.runs(lo) if not only or f"{lo}:{a}" in only]
    porter.jar_tools()
    porter.compile_tools()
    settings.CORES = max(1, (os.cpu_count() or 1) // max(1, jobs))
    out = settings.ROOT / ".runs" / "exposure" / "all"
    out.mkdir(parents=True, exist_ok=True)

    def one(job):
        lo, a, b = job
        started = time.monotonic()
        try:
            s = jars(lo, a, b, out=out / f"{lo}-{a}-{b}.json", quiet=True)
            print(f"  {lo} {a} -> {b}: {s['modsThatLink']}/{s['modsRead']} link ({time.monotonic() - started:.0f} s)", flush=True)
        except (SystemExit, subprocess.CalledProcessError, OSError) as failed:
            print(f"  {lo} {a} -> {b}: failed: {failed}", flush=True)

    # Two updates next to each other share a release, and their first run downloads its files to the same cache: each
    # wave holds updates that share none, so no two runs write one file.
    for loader in loaders:
        single = gamereleases.updates(loader)
        for parity in (0, 1):
            wave = [t for t in todo if t[0] == loader and t[1:] in single and single.index(t[1:]) % 2 == parity]
            with ThreadPoolExecutor(max(1, jobs)) as pool:
                list(pool.map(one, wave))
        # A jump reads every release on its way. The updates above fetched them, so jumps side by side write no file
        # twice; one that crosses an update that did not run (--only) goes alone.
        fetched = {v for t in todo if t[0] == loader and t[1:] in single for v in t[1:]}
        far = [t for t in todo if t[0] == loader and t[1:] not in single]
        warm = [t for t in far if set(gamereleases.chain(*t[1:])) <= fetched]
        with ThreadPoolExecutor(max(1, jobs)) as pool:
            list(pool.map(one, warm))
        for t in far:
            if t not in warm:
                one(t)
    return join(out, out_file)


def join(folder, out=None):
    """One table from per-update summaries: the mods that link across each update and each jump, the totals, and the mods nearest to done."""
    found = []
    for f in sorted(Path(folder).rglob("*.json")):
        s = json.loads(f.read_text(encoding="utf-8"))
        if {"loader", "from", "to", "modsRead"} <= s.keys():
            found.append(s)
    single = {(lo, *u): i for lo in settings.FIRST for i, u in enumerate(gamereleases.updates(lo))}
    wanted = {(lo, *u): i for lo in settings.FIRST for i, u in enumerate(gamereleases.jumps(lo))}
    pair = lambda s: (s["loader"], s["from"], s["to"])
    rows = sorted((s for s in found if pair(s) in single), key=lambda s: (s["loader"], single[pair(s)]))
    far = sorted((s for s in found if pair(s) in wanted), key=lambda s: (s["loader"], wanted[pair(s)]))

    def count(some):
        return {"updates": len(some), "mods": sum(s["modsRead"] for s in some), "link": sum(s["modsThatLink"] for s in some)}

    total = {"mods": sum(s["modsRead"] for s in rows), "link": sum(s["modsThatLink"] for s in rows)}
    # One update at a time is two different jobs: the updates that start a new family of releases, and the rest,
    # across which nearly every mod links untouched.
    total["big"] = count([s for s in rows if gamereleases.big(s["from"], s["to"])])
    total["small"] = count([s for s in rows if not gamereleases.big(s["from"], s["to"])])
    # What people ask for: a port over several updates.
    total["jumps"] = {"jumps": len(far), "mods": sum(s["modsRead"] for s in far), "link": sum(s["modsThatLink"] for s in far)}
    # The jump asked for most: the most-used release's mods on the newest game.
    newest = [s for s in far if s["to"] == gamereleases.updates(s["loader"])[-1][1]]
    total["newest"] = {"jumps": len(newest), "mods": sum(s["modsRead"] for s in newest), "link": sum(s["modsThatLink"] for s in newest)}
    checked = [s for s in rows if s.get("modsThatLinkAndData") is not None]
    # Over the updates whose registry reports ran: mods that link and also name nothing in their data that the new release dropped.
    total["data"] = {"updates": len(checked), "link": sum(s["modsThatLink"] for s in checked), "linkAndData": sum(s["modsThatLinkAndData"] for s in checked)}
    # Breaks in the loader's own API, which the link check reads like the game's.
    total["loader"] = {"uses": sum(s.get("loaderUses", 0) for s in rows), "modsHeldOnlyByLoader": sum(s.get("modsHeldOnlyByLoader", 0) for s in rows)}
    loaders = {}
    for s in rows:
        t = loaders.setdefault(s["loader"], {"updates": 0, "mods": 0, "link": 0})
        t["updates"] += 1
        t["mods"] += s["modsRead"]
        t["link"] += s["modsThatLink"]
    top = sorted(({"loader": s["loader"], "update": f"{s['from']} -> {s['to']}", **r} for s in rows for r in s["top"]),
                 key=lambda r: -r["mods"])[:60]
    # The to-do list over every update: the mods with the fewest references left, those the game's own code answers first.
    # Among equals, the update into the release with the most mods: that is where a finished port is wanted most.
    family = gamereleases.families()
    used = {v: f.get("mods", {}).get(v, 0) for v, f in family.items()}
    turns = [{"loader": s["loader"], "update": f"{s['from']} -> {s['to']}", "mod": t["mod"], "members": t["members"], "wanted": used.get(s["to"], 0)}
             for s in rows for t in s.get("nearest", [])]
    turns.sort(key=lambda t: (len(t["members"]), sum(1 for m in t["members"] if m.get("game") not in ANSWERED), -t["wanted"]))
    # The to-do list by type of break, over every update: what a way to handle the type would finish.
    kinds = {}
    for s in rows:
        for t in s.get("types", []):
            k = kinds.setdefault(t["type"], {"type": t["type"], "finishes": 0, "mods": 0, "references": 0, "updates": 0, "examples": []})
            k["finishes"] += t["finishes"]
            k["mods"] += t["mods"]
            k["references"] += t["references"]
            k["updates"] += 1
            k["examples"].append([t["finishes"], f"{s['loader']} {s['from']} -> {s['to']}: {t['examples'][0]}"])
    for k in kinds.values():
        k["examples"] = [text for _, text in sorted(k["examples"], key=lambda e: -e[0])[:4]]
    kinds = sorted(kinds.values(), key=lambda k: (-k["finishes"], -k["mods"], k["type"]))
    # A summary an older run made has no types: the ranking then covers fewer updates than the counts, and says so.
    untyped = [f"{s['loader']} {s['from']} -> {s['to']}" for s in rows if "types" not in s]
    targets = {(lo, *p) for lo in settings.FIRST for p in gamereleases.any_version(lo)}
    missing = [f"{lo} {a} -> {b}" for lo, a, b in dict.fromkeys(list(single) + list(wanted) + sorted(targets))
               if not any(pair(s) == (lo, a, b) for s in found)]
    anyv = anyversion.any_version_table({pair(s): s for s in found if pair(s) in targets})
    keep = ("loader", "from", "to", "modsRead", "modsThatLink", "modsThatLinkAndData", "brokenUses", "loaderUses", "modsHeldOnlyByLoader", "links")
    table = {
        "date": date.today().isoformat(), "total": total, "loaders": loaders, "missing": missing, "anyVersion": anyv,
        "updates": [{**{k: s.get(k) for k in keep}, "big": gamereleases.big(s["from"], s["to"])} for s in rows],
        "jumps": [{**{k: s.get(k) for k in keep}, "updatesCrossed": len(gamereleases.chain(s["from"], s["to"])) - 1} for s in far],
        "types": kinds,
        "typesNotRead": untyped,
        "nearest": turns[:60],
        "top": top,
    }

    def share(part, whole):
        return f"{part} of {whole} ({part / whole:.1%})" if whole else "0 of 0"

    print(anyversion.any_version_lines(anyv)[0] + "\n")
    print(f"| Loader | Update | Mods that link | And their data |\n|---|---|---|---|")
    for s in rows:
        data = "-" if s.get("modsThatLinkAndData") is None else s["modsThatLinkAndData"]
        print(f"| {s['loader']} | {s['from']} -> {s['to']}{' (big)' if gamereleases.big(s['from'], s['to']) else ''} | {s['modsThatLink']}/{s['modsRead']} | {data} |")
    if far:
        print(f"\n| Loader | Jump | Updates crossed | Mods that link |\n|---|---|---|---|")
        for s in table["jumps"]:
            print(f"| {s['loader']} | {s['from']} -> {s['to']} | {s['updatesCrossed']} | {s['modsThatLink']}/{s['modsRead']} |")
    j, n = total["jumps"], total["newest"]
    print(f"\n{share(n['link'], n['mods'])} of the most-used release's mods link on the newest game; {share(j['link'], j['mods'])} mod ports link across all {j['jumps']} jumps of several updates")
    print(f"one update at a time: {share(total['big']['link'], total['big']['mods'])} across the {total['big']['updates']} big updates, "
          f"{share(total['small']['link'], total['small']['mods'])} across the {total['small']['updates']} small ones; {share(total['link'], total['mods'])} together"
          + "".join(f"; {lo} {t['link']}/{t['mods']}" for lo, t in loaders.items()) + (f"; not run: {', '.join(missing)}" if missing else ""))
    d = total["data"]
    print(f"over the {d['updates']} updates whose registry reports ran, {d['link'] - d['linkAndData']} of {d['link']} mods that link name a game id in their data that the new release dropped")
    print(f"{total['loader']['uses']} broken uses are of the loader's own API; {total['loader']['modsHeldOnlyByLoader']} mod ports break on nothing else")
    print("\nTypes of break, " + (f"{len(rows) - len(untyped)} of {len(rows)} updates (the others' summaries are older and list none)" if untyped else "every update")
          + ", by the mod ports each one alone holds back:")
    for k in kinds[:15]:
        print(f"  finishes {k['finishes']:>4}, in {k['mods']:>5} mod ports of {k['updates']:>2} updates, {k['references']:>5} references  {k['type']}")
        print(f"      e.g. {k['examples'][0]}")
    print("\nNearest to done, every update:")
    for t in table["nearest"][:15]:
        print(f"  {t['loader']} {t['update']}  {t['mod']}: {len(t['members'])} left")
        for member in t["members"][:4]:
            print(f"      {shown(member)}")
    print("\nShared by the most mods, every update:")
    for r in top[:10]:
        print(f"  {r['mods']:>4} mods  {r['loader']} {r['update']}  {shown(r)}")
    print("\nAny version:\n" + "\n".join(anyversion.any_version_lines(anyv)))
    if out:
        Path(out).write_text(json.dumps(table, indent=1) + "\n", encoding="utf-8")
        print(f"saved {out}")
    return table

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

from portboard import anyversion, cachekeys, gamereleases, javalevels, mainrecord, modrinth, porter, settings


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


def jars(loader, frm, tos, save=False, out=None, quiet=False, kind=None, only=None):
    """The jar scoreboard: what every top mod's classes and mixins reference, ported from frm to each release of tos and
    linked against it as the JVM and Mixin do; a summary for each.

    The targets share what reads the release ported from: its mods are downloaded and read into Mojang's names once, every
    map is built in one process, and one JarPort ports the steps the ports share once (jar_ports). A mod whose port was made
    before with the same inputs is read from the cache, so a run after an edit ports only what the edit can change. Each
    run keeps its summary in .runs/exposure/jars-LOADER-FROM-TO/last.json; when the porter's inputs differ from the last
    run's, that one becomes before.json, and the run prints which mods newly link, stopped linking or changed their port
    against it, and against main's row of the same port (main's table, on the generated branch). kind: port only the mods
    the last run held back with a type of break (its text, or part of it), the answer for those mods in seconds; only:
    port only these mods (Modrinth slugs); either way the summary is partial and is not kept. out: the summary's file, or
    a folder (one that is there, or any with several targets) that gets LOADER-FROM-TO.json for each."""
    seconds = {}
    timed = timer(seconds)
    if (kind or only) and len(tos) > 1:
        raise SystemExit("--type and --mods answer one update at a time: give one --to")
    pinned = timed("download", modrinth.fetch, loader, frm)
    works = {to: settings.ROOT / ".runs" / "exposure" / f"jars-{loader}-{frm}-{to}" for to in tos}
    if kind:
        last = works[tos[0]] / "last.json"
        if not last.is_file():
            raise SystemExit(f"--type needs a run of the whole update first: python bench/exposure.py jars --loader {loader} --from {frm} --to {tos[0]}")
        held = {mod for mod, v in json.loads(last.read_text(encoding="utf-8")).get("verdicts", {}).items() if any(kind in t for t in v.get("types", []))}
        pinned = [p for p in pinned if p.name in held]
        if not pinned:
            raise SystemExit(f"no mod of the last run is held back by a type of break with '{kind}' in it")
    if only:
        unknown = sorted(set(only) - {p.name.split("--", 1)[0] for p in pinned})
        if unknown:
            raise SystemExit(f"{loader} {frm} pins no {', '.join(unknown)} (bench/exposure/mods/{loader}-{frm}.json)")
        pinned = [p for p in pinned if p.name.split("--", 1)[0] in only]
    partial = (f"only the {len(pinned)} mods the last run held back with '{kind}'" if kind
               else f"only {', '.join(sorted(only))}" if only else None)
    porter.compile_tools()  # once, before the threads below that run it
    # Every step of every target, so one GameDiff process builds the maps that are missing.
    segments = list(dict.fromkeys((x, y) for to in tos for x, y in zip(porter.stops(loader, frm, to), porter.stops(loader, frm, to)[1:])))
    # Reading the jars' names, the game's change maps and the releases' registry reports need nothing from each other.
    with ThreadPoolExecutor(3) as pool:
        named = pool.submit(timed, "names", porter.mojang_names, loader, frm, pinned)
        mapped = pool.submit(timed, "map", porter.route_maps, segments, loader, porter.compile_tools())
        reported = pool.submit(timed, "reports", porter.registry_reports, list(dict.fromkeys(v for to in tos for v in porter.stops(loader, frm, to))))
        mods, unread = named.result()
        mapped.result()
        reported.result()
    # A run of some mods only keeps its report apart, so a whole run's report is not replaced by part of it.
    written = {to: works[to] / ("report.json" if not partial else "report-part.json") for to in tos}
    for work in works.values():
        work.mkdir(parents=True, exist_ok=True)
    empty = {"linked": 0, "mods": [], "unlinkedByMods": []}
    for to, work in works.items():
        # A run of named mods before this one kept its jars; they are this run's too only when the same port wrote them.
        keep_same_port(work / "mods", porter.port_key(loader, frm, to) if only else None)
    reports = timed("port", porter.jar_ports, loader, frm, [(to, works[to] / "mods", written[to]) for to in tos], mods, False, True) if mods else {to: dict(empty) for to in tos}
    if only and mods:
        # A run of named mods is someone looking at those mods: their ported jars are kept and every file the port read
        # is printed (files_lines). A mod whose port was read from the cache has no jar yet, so it is ported once more.
        unwritten = [m for m in mods if not (works[tos[0]] / "mods" / m.name).is_file()]
        if unwritten:
            timed("port", porter.jar_ports, loader, frm, [(tos[0], works[tos[0]] / "mods", works[tos[0]] / "report-kept.json")], unwritten)
    else:
        for work in works.values():
            shutil.rmtree(work / "mods", ignore_errors=True)  # only the report is read; load ports the mods it starts itself
    summaries = {}
    for to in tos:
        # What the targets shared, and each one's own questions to the game after it.
        own = dict(seconds)
        target = out if out is None or (len(tos) == 1 and not Path(out).is_dir()) else Path(out) / f"{loader}-{frm}-{to}.json"
        summaries[to] = summarize(loader, frm, to, mods, unread, reports[to], written[to], works[to], partial, save, target, quiet, timer(own), own)
    if only and mods and not quiet:
        read, standins = port_reads(loader, gamereleases.chain(frm, tos[0]), porter.stops(loader, frm, tos[0]))
        shipped = {p.name: p for p in pinned}
        print("\n".join(files_lines([(m.name.split("--", 1)[0], shipped.get(m.name), m, works[tos[0]] / "mods" / m.name) for m in mods],
                                    {v: game_files(v) for v in read}, porter.fabric_apis(read) if loader == "fabric" else {},
                                    porter.routes(loader, frm, tos[0]), standins)))
    return summaries


def port_reads(loader, chain, steps):
    """The releases a port reads and the stand-in folders it may read. Every release of the chain, not only the ones the
    port stops at: a step across several updates runs on a map composed through the releases between (route_maps), built
    from their game jars and Fabric API. Stand-ins are per step, plus the Java ones a port to an older Java uses."""
    folder = settings.ROOT / "knowledge" / "standins"
    return list(chain), [folder / f"{a}-{b}{x}" for a, b in zip(steps, steps[1:]) for x in ("", f".{loader}")] + [folder / "jdk"]


def keep_same_port(folder, key):
    """Empties a folder of kept ported jars unless the port that wrote them is this run's (key: porter.port_key; None
    for a run that keeps none), and marks it as this run's. A mod's jar ported by an equal key is the jar this run would
    write, so a repeat run of named mods ports nothing (it ported each again, 9 s where a repeat had taken 1.3 s)."""
    marker = folder / "port-key.txt"
    if key is None or not marker.is_file() or marker.read_text(encoding="utf-8") != key:
        shutil.rmtree(folder, ignore_errors=True)
    if key is not None:
        folder.mkdir(parents=True, exist_ok=True)
        marker.write_text(key, encoding="utf-8")


def game_files(version):
    """A release's files on this machine: its jar in Mojang's names where mmt has made one, and the jar and name table
    the porter reads."""
    return [settings.MAPPINGS.parent / "game" / version / f"minecraft-{version}-named.jar", settings.MAPPINGS / f"client-{version}.jar",
            settings.MAPPINGS / f"client-{version}.txt"]


def files_lines(mods, games, apis, maps, standins):
    """What a run of named mods prints last: where each file of the port is, so it is opened (javap, a decompiler, a
    diff) instead of searched for; agents searched ~/.mmt for these 17 times in one session, up to two minutes a search.
    mods: (slug, the jar as shipped, the jar in Mojang's names, the ported jar); only files that are there are named."""
    def there(paths):
        return [str(p) for p in paths if p and Path(p).exists()]
    lines = ["", "Files of this port:"]
    for slug, shipped, named, ported in mods:
        lines.append(f"  {slug}")
        for label, path in (("as shipped", shipped), ("in Mojang's names", named if named != shipped else None), ("ported (Mojang's names)", ported)):
            lines += [f"    {label}: {p}" for p in there([path])]
    for version, paths in games.items():
        lines += [f"  game {version}: {p}" for p in there(paths)]
    lines += [f"  Fabric API for {version}: {p}" for version, path in apis.items() for p in there([path])]
    lines += [f"  route map: {p}" for p in there(maps)]
    lines += [f"  stand-ins: {p}" for p in there(standins)]
    return lines


def timer(seconds):
    """A function that runs work and adds the seconds it took to seconds[stage]."""
    def timed(stage, work, *args):
        started = time.monotonic()
        try:
            return work(*args)
        finally:
            seconds[stage] = round(seconds.get(stage, 0) + time.monotonic() - started, 1)
    return timed


def summarize(loader, frm, to, mods, unread, report, written, work, partial, save, out, quiet, timed, seconds):
    """One target's summary from its port's report: what still breaks, by type and by the mods nearest to done, printed
    unless quiet and written to out."""
    last = work / "last.json"
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
    # What holds back each mod that does not link, by type: kept in main's table, so a check of one type of break ports
    # only the mods main says it holds (check_plan).
    summary["heldBy"] = {m["mod"]: sorted({kind_of(r, said) for r in porter.left(m, data)}) for m in rows if porter.left(m, data)}
    summary["verdicts"] = {m["mod"]: {"left": sorted(porter.left(m, data)), "types": sorted({kind_of(r, said) for r in porter.left(m, data)}), "ported": m.get("ported")}
                           for m in rows}
    summary["porter"] = porter.port_key(loader, frm, to) + " " + cachekeys.content_key(*(p.read_bytes() for p in mods))
    if partial:
        summary["partial"] = partial
    before = work / "before.json"
    if not partial and last.is_file() and json.loads(last.read_text(encoding="utf-8")).get("porter") != summary["porter"]:
        os.replace(last, before)
    against = before if not partial else last
    moved = verdicts_moved(json.loads(against.read_text(encoding="utf-8")), summary) if against.is_file() else None
    summary["moved"] = moved
    row = mainrecord.main_row(loader, frm, to)
    summary["againstMain"] = mainrecord.against_main(row, summary) if row else None
    if not partial:
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
            print(f"against {'the run before' if not partial else 'the last whole run'} ({against}): "
                  + "; ".join(f"{k} {', '.join(v) if v else 'none'}" for k, v in moved.items() if k != "same") + f"; {moved['same']} mods the same")
        if summary["againstMain"] is not None:
            m = summary["againstMain"]
            print(f"against main's table at {(row.get('commit') or '?')[:10]}: newly link {', '.join(m['newly link']) or 'none'}; "
                  f"no longer link {', '.join(m['no longer link']) or 'none'}; {m['same']} mods the same")
        if partial:
            print(partial)
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
    """jars for every update, every jump and every any-version target of each loader, side by side, in the jobs the cloud
    sweep runs (plan); joined into one table."""
    todo = [job for lo in loaders for job in plan(lo, only)]
    porter.jar_tools()
    porter.compile_tools()
    settings.CORES = max(1, (os.cpu_count() or 1) // max(1, jobs))
    out = settings.ROOT / ".runs" / "exposure" / "all"
    out.mkdir(parents=True, exist_ok=True)

    def one(job):
        lo, a, tos = job
        started = time.monotonic()
        try:
            for b, s in jars(lo, a, tos, out=out, quiet=True).items():
                print(f"  {lo} {a} -> {b}: {s['modsThatLink']}/{s['modsRead']} link ({time.monotonic() - started:.0f} s)", flush=True)
        except (SystemExit, subprocess.CalledProcessError, OSError) as failed:
            print(f"  {lo} {a} -> {', '.join(tos)}: failed: {failed}", flush=True)

    # Runs side by side share the caches safely: every file is written under a name of its own and moved into place.
    with ThreadPoolExecutor(max(1, jobs)) as pool:
        list(pool.map(one, todo))
    return join(out, out_file)


# What a job costs, in steps of a port (about 40 s on GitHub's 4-processor runners, measured on the sweep of 2026-11-23):
# a job's own start (the runner, the download, reading the mods into Mojang's names and the first release) about 2.5,
# and each target after the first about 1.8 (its last step, its map, its questions to Exposure).
JOB, TARGET = 2.5, 1.8


def planned_steps(loader, frm, to):
    """About the steps JarPort takes from frm to to: one for each update with stand-ins (JarPort.hops), and one for the
    updates between. Only a job's cost is planned from it; what is ported is JarPort's plan."""
    folder = settings.ROOT / "knowledge" / "standins"
    c = gamereleases.chain(frm, to)
    return 1 + sum(1 for x, y in zip(c, c[1:]) if (folder / f"{x}-{y}").is_dir() or (folder / f"{x}-{y}.{loader}").is_dir())


def plan(loader, only=None):
    """The scoreboard's jobs for a loader: every pair runs() lists, as (loader, from, [to, ...]). The pairs from one release
    in one direction are one fan: a port to a later release passes every stop of a port to an earlier one, so one JarPort
    ports the shared steps once and each target adds its last step (jar_ports), and the mods are read once. A fan is cut,
    nearest targets first, into jobs that take at most as long as the longest single port and four more targets: the far
    end of the most-used release's fan cannot be shorter than its own port, and longer jobs saved little more (one job a
    fan: 291 runner minutes a sweep against 322, with a 36-minute job).
    only: LOADER:FROM or LOADER:FROM:TO names; the pairs they name."""
    pairs = [(frm, to) for frm, to in gamereleases.runs(loader) if not only or f"{loader}:{frm}" in only or f"{loader}:{frm}:{to}" in only]
    cost = lambda frm, tos: JOB + planned_steps(loader, frm, tos[-1]) + TARGET * (len(tos) - 1)
    budget = max((cost(frm, [to]) for frm, to in gamereleases.runs(loader)), default=0) + 4 * TARGET
    fans = {}
    for frm, to in pairs:
        fans.setdefault((frm, gamereleases.vkey(to) > gamereleases.vkey(frm)), []).append(to)
    jobs = []
    for (frm, _), tos in fans.items():
        tos.sort(key=lambda to: len(gamereleases.chain(frm, to)))
        job = []
        for to in tos:
            if job and cost(frm, job + [to]) > budget:
                jobs.append((loader, frm, job))
                job = []
            job.append(to)
        jobs.append((loader, frm, job))
    return jobs


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
    keep = ("loader", "from", "to", "modsRead", "modsThatLink", "modsThatLinkAndData", "brokenUses", "loaderUses", "modsHeldOnlyByLoader", "links", "heldBy")
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

    print("\n".join(anyversion.any_version_lines(anyv)[:2]) + "\n")
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

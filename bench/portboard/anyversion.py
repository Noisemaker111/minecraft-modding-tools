"""The mods that port to every release, forward and back: the count, and what each of the others is missing."""
import json
import re

from portboard import gamereleases, settings


def any_version_table(by_pair):
    """Any mod, any version: for each loader, the most-used release's mods (the pinned 150) ported to every other release
    it is read for, and the mods whose port links (classes, mixins, access transformers and data) on every one. A target
    whose job did not run, or where a mod could not be read, counts as not reached. For each mod that misses: the releases
    it misses and the types of break holding it there. Then what would free the most mods for every release: each type
    of break and each release, by the mods it holds and the mods it alone holds back (handled, they reach every release)."""
    loaders, held, everywhere = {}, [], {}
    for lo in settings.FIRST:
        frm, targets = gamereleases.most_used(lo), [b for _, b in gamereleases.any_version(lo)]
        here = {t: by_pair.get((lo, frm, t)) for t in targets}
        # Every pinned mod, so a target or a whole loader whose jobs did not run lowers the count instead of leaving it out.
        pinned = settings.PINS / f"{lo}-{frm}.json"
        names = {f"{m['slug']}--{m['file']}" for m in json.loads(pinned.read_text(encoding="utf-8"))["mods"]} if pinned.is_file() else set()
        names = sorted(names | {m for s in here.values() if s for m in list(s.get("verdicts", {})) + list(s.get("unreadable", {}))})
        reached = {}
        for jar in names:
            missing = {}
            for t, s in here.items():
                if s is None:
                    missing[t] = ["not run"]
                elif jar in s.get("verdicts", {}):
                    if s["verdicts"][jar]["left"]:
                        missing[t] = s["verdicts"][jar]["types"]
                else:
                    missing[t] = [not_read(s.get("unreadable", {}).get(jar))]
            mod = jar.split("--", 1)[0]
            reached[mod] = len(targets) - len(missing)
            if missing:
                types = {}
                for kinds in missing.values():
                    for k in kinds:
                        types[k] = types.get(k, 0) + 1
                held.append({"loader": lo, "mod": mod, "reached": reached[mod], "of": len(targets), "missing": list(missing),
                             "types": dict(sorted(types.items(), key=lambda kv: (-kv[1], kv[0])))})
        older = [t for t in targets if gamereleases.vkey(t) < gamereleases.vkey(frm)]
        everywhere[lo] = sorted(m for m, n in reached.items() if n == len(targets))
        loaders[lo] = {"from": frm, "targets": len(targets), "older": len(older), "newer": len(targets) - len(older),
                       "mods": len(names), "every": len(everywhere[lo]),
                       "everyOlder": sum(1 for m in held if m["loader"] == lo and not set(m["missing"]) & set(older)) + len(everywhere[lo]),
                       "everyNewer": sum(1 for m in held if m["loader"] == lo and set(m["missing"]) <= set(older)) + len(everywhere[lo]),
                       "notRun": [t for t, s in here.items() if s is None], "reached": reached}
    kinds, releases = {}, {}
    for m in held:
        for k, n in m["types"].items():
            t = kinds.setdefault(k, {"type": k, "finishes": 0, "mods": 0, "releases": 0})
            t["mods"] += 1
            t["releases"] += n
        if len(m["types"]) == 1:
            kinds[next(iter(m["types"]))]["finishes"] += 1
        for r in m["missing"]:
            t = releases.setdefault((m["loader"], r), {"loader": m["loader"], "release": r, "finishes": 0, "mods": 0})
            t["mods"] += 1
        if len(m["missing"]) == 1:
            releases[(m["loader"], m["missing"][0])]["finishes"] += 1
    held.sort(key=lambda m: (len(m["missing"]), len(m["types"]), m["loader"], m["mod"]))
    return {"mods": sum(t["mods"] for t in loaders.values()), "every": sum(t["every"] for t in loaders.values()),
            "loaders": loaders, "everywhere": everywhere,
            "types": by_breaks(kinds.values()),
            "releases": sorted(releases.values(), key=lambda t: (-t["finishes"], -t["mods"], t["loader"], gamereleases.vkey(t["release"]))),
            "held": held}


def by_breaks(types):
    """Types of break, the most breaks left first: the mods each holds on some release, then the mods it alone holds back."""
    return sorted(types, key=lambda t: (-t["mods"], -t["finishes"], t["type"]))


def breaks_left(a):
    """The breaks left of an any-version table: one per mod and type of break holding it on some release. It falls only
    when a type is handled for a mod on every release it held it on, so a fix for one update or one mod barely moves it
    and a way to handle a whole type moves it by every mod the type held; it is 0 exactly when every mod goes to any
    version. The count of mods that go to any version moves only when a mod's last type is handled, which most types
    never are alone."""
    return sum(t["mods"] for t in a.get("types", []))


def not_read(error):
    """A mod the port could not read, as a type of break: why, as the porter said it (its exception's message), so the list
    ranks each reason by the mods it holds back instead of one "unreadable"."""
    if error is None:
        return "not read"
    why = error if isinstance(error, str) else "; ".join(map(str, error))
    why = re.sub(r"^[\w.$]+(?:Exception|Error): ", "", why.strip()).splitlines()[0] if why.strip() else "no reason given"
    return "not read: " + (why if len(why) <= 100 else why[:97] + "...")


def any_version_lines(a, top=10):
    """The any-version count as lines: the headline, then what would free the most mods, then the mods nearest to it."""
    lo = a.get("loaders", {})
    split = "; ".join(f"{k.capitalize()} {t['every']} of {t['mods']} from {t['from']} to all {t['targets']} other releases "
                      f"({t['everyNewer']} reach every newer one, {t['everyOlder']} every older one)" for k, t in lo.items())
    lines = [f"{a.get('every', 0)} of {a.get('mods', 0)} mods go to any version (their ported jar links on every release): {split}",
             f"{breaks_left(a):,} breaks left: each type of break holding a mod on some release, one per mod; a type handled on every release removes one per mod it held"]
    not_run = [f"{k} {r}" for k, t in lo.items() for r in t["notRun"]]
    if not_run:
        lines.append(f"not run, so counted as not reached: {', '.join(not_run)}")
    lines.append("types of break, by the breaks left each one is (the mods it holds on some release), and the mods it alone keeps from every release:")
    lines += [f"  in {t['mods']:>3} mods, {t['releases']:>5} mod-releases, frees {t['finishes']:>3}  {t['type']}" for t in by_breaks(a.get("types", []))[:top]]
    lines.append("releases, by the mods each one alone keeps from going to any version (and the mods missing it):")
    lines += [f"  frees {t['finishes']:>3}, {t['mods']:>3} mods miss it  {t['loader']} {t['release']}" for t in a.get("releases", [])[:top]]
    lines.append("nearest to any version:")
    lines += [f"  {m['loader']} {m['mod']}: {m['reached']} of {m['of']}, misses {', '.join(m['missing'])} ({'; '.join(m['types'])})"
              for m in a.get("held", [])[:top]]
    return lines


ANY_VERSION_PAGE = "any-version.md"


def any_version_page(table):
    """scoreboard/any-version.md on the generated branch: what is not there yet for any mod, any version, written with
    main's table."""
    a = table.get("anyVersion") or {}
    out = ["# Any mod, any version: what is not there yet", "",
           f"Written with main's jar scoreboard (scoreboard/all.json on the generated branch) at "
           f"{(table.get('commit') or '?')[:10]}, run {table.get('run', '?')}. Do not edit by hand.", "",
           "The 150 most-downloaded mods of each loader's most-used release, each ported as a jar to every other release "
           "the loader has, forward and back, and linked against it (classes, mixins, access transformers, data). It says "
           "the port links, not that it runs: the game proof is `tools/cloud-run load`.", "",
           "**" + any_version_lines(a)[0] + "**", "", "**" + any_version_lines(a)[1] + "**", ""]
    out += ["## What removes the most breaks", "", "Types of break, by the mods each holds on some release: a way to handle the "
            "type on every release removes that many breaks left. *Frees* counts the mods it alone keeps from every release.", "",
            "| Mods | Mod-releases | Frees | Type of break |", "|---|---|---|---|"]
    out += [f"| {t['mods']} | {t['releases']} | {t['finishes']} | {t['type']} |" for t in by_breaks(a.get("types", []))[:30]]
    out += ["", "Releases. *Frees* counts the mods that miss only this release.", "", "| Frees | Mods missing it | Loader | Release |", "|---|---|---|---|"]
    out += [f"| {t['finishes']} | {t['mods']} | {t['loader']} | {t['release']} |" for t in a.get("releases", [])[:30]]
    out += ["", "## Every mod that misses a release, nearest first", "", "| Loader | Mod | Reaches | Misses | Held by |", "|---|---|---|---|---|"]
    out += [f"| {m['loader']} | {m['mod']} | {m['reached']} of {m['of']} | {', '.join(m['missing'])} | "
            f"{'; '.join(f'{k} ({n})' for k, n in m['types'].items())} |" for m in a.get("held", [])]
    out += ["", "## Mods that reach every release", ""]
    out += [f"- {lo.capitalize()}: {', '.join(mods) or 'none yet'}" for lo, mods in a.get("everywhere", {}).items()]
    return "\n".join(out) + "\n"

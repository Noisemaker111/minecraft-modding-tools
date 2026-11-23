"""The game's releases in order, and which updates and jumps between them the scoreboard runs."""
import json
import re

from portboard import settings


def releases(data=None):
    """The indexed releases of the change index (coverage/changes/index.json), or of data, another copy of it."""
    data = json.loads(settings.INDEX.read_text(encoding="utf-8")) if data is None else data
    return [v["id"] for v in data["allVersions"] if v.get("status") == "indexed"]


def vkey(v):
    return tuple(int(x) for x in re.findall(r"\d+", v))


def chain(frm, to):
    """Every indexed release from one to the other, in the direction of the port."""
    all_versions = sorted(releases(), key=vkey)
    lo, hi = sorted([frm, to], key=vkey)
    span = [v for v in all_versions if vkey(lo) <= vkey(v) <= vkey(hi)]
    return span if vkey(frm) <= vkey(to) else list(reversed(span))


def updates(loader, index=None):
    """Every update from one indexed release to the next whose mod jars are read for this loader (index: another copy of
    the change index)."""
    every = [v for v in sorted(releases(index), key=vkey) if vkey(v) >= vkey(settings.FIRST[loader])]
    return list(zip(every, every[1:]))


def families():
    """Each indexed release's family: releases between which the game changes almost nothing (coverage/changes/index.json)."""
    data = json.loads(settings.INDEX.read_text(encoding="utf-8"))
    return {v: f for f in data["families"]["list"] for v in f["versions"]}


def big(frm, to):
    """An update into another family: the game broke at least 1 in 100 of its names. Half the updates are not, and nearly every mod links across those untouched."""
    family = families()
    return family[frm]["id"] != family[to]["id"]


def jumps(loader):
    """The ports people ask for, which cross several updates: from each family's hub (its release with the most mods)
    to the next family's hub, and from the release with the most mods of all to the newest one. A mod has to get
    through every update on the way, so these are far lower than any one update's count. One update apart, the pair
    is already in updates. Every hub to the newest release was measured once (2026-11-20, run 44821929843): 33 more
    jobs that each read every release on the way, 60% of the run's time, and all but the nearest few under 15 of 147."""
    single = updates(loader)
    hubs = hubs_of(loader)
    pairs = list(zip(hubs, hubs[1:])) + [(most_used(loader), single[-1][1])]
    return [p for p in dict.fromkeys(pairs) if p not in single]


def hubs_of(loader):
    read = [frm for frm, _ in updates(loader)]
    return sorted({f["hub"] for f in families().values() if f["hub"] in read}, key=vkey)


def most_used(loader):
    """The release with the most mods of all among a loader's hubs (Fabric 1.20.1, NeoForge 1.21.1): whose mods every
    port people ask for starts from."""
    family = families()
    return max(hubs_of(loader), key=lambda hub: family[hub].get("mods", {}).get(hub, 0))


def any_version(loader):
    """The ports "any mod, any version" asks for: the most-used release's mods to every other release the loader is read
    for, forward to the newest and back to the first. A mod goes to any version when its port links on every one."""
    frm = most_used(loader)
    every = [a for a, _ in updates(loader)] + [updates(loader)[-1][1]]
    return [(frm, v) for v in every if v != frm]


def runs(loader):
    """Every pair the scoreboard runs for a loader: each update, each jump and each any-version target, once."""
    return list(dict.fromkeys(updates(loader) + jumps(loader) + any_version(loader)))

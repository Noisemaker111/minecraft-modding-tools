"""What real mods reference of the game, ported as jars and linked against the next release: the porter's scoreboard and its to-do list.

    python bench/exposure.py jars --loader neoforge|fabric --from 1.21.1 --to 1.21.2 [--type TYPE] [--save] [--out FILE]
    python bench/exposure.py all [--loader neoforge|fabric] [--only fabric:1.20.1] [--jobs 4] [--out FILE] [--verify]
    python bench/exposure.py all --at origin/main --out BASE.json    (any command, on another commit's porter)
    python bench/exposure.py join DIR [--out FILE]
    python bench/exposure.py compare BEFORE.json AFTER.json
    python bench/exposure.py fetch --loader neoforge|fabric --version 1.21.1 [--repin]
    python bench/exposure.py pin [--loader neoforge|fabric] [--version 1.21.1] [--repin]
    python bench/exposure.py load --loader neoforge --from 1.21.1 --to 1.21.2 [--mods SLUG,...] [--save] [--out FILE]
    python bench/exposure.py load-plan neoforge:1.21.1:1.21.2,fabric:1.21.1
    python bench/exposure.py steps [--from 1.14.4] [--to 26.3]

The mods are the 150 most downloaded of one loader and release on Modrinth, each in its newest file for that release,
listed once in bench/exposure/mods/LOADER-VERSION.json so every run reads the same jars, here or in the cloud (pin
lists them for every release a loader is read for, without downloading; --repin lists them again). They are downloaded into ~/.mmt/exposure/jars/ and only read, never built or shipped: rules still come only from the game and
the loaders.

jars ports one release's mods to the next with tools/JarPort.java (what mmt port --jar runs) and links every reference
their classes, mixins and access transformers make to the game against the new release, as the JVM and Mixin will. It
prints the mods that link and the references that hold the most back, each with what the game's own code did about it
(tools/Exposure.java: replaced, template, split, linked, redesigned, unused). Fabric mods ship in intermediary names
before 26.1; tools/ModNames.java reads them in Mojang's names first, through the game's own mappings.

all runs jars for every update a loader's jars can be read across (Fabric from 1.14.4, NeoForge from its first release,
1.20.2; its mods before 1.20.5 are read through NeoForm's SRG names) and joins them: per update the share of mods that link, in total, and the references that
hold the most mods back across every update. join does the joining for summaries made elsewhere (the cloud run,
tools/cloud-run exposure). --out writes the table to a file.

Main's table is kept on the generated branch as scoreboard/all.json, never on main (bench/generated.py): every push to
main runs the cloud sweep (.github/workflows/cloud-exposure.yml), and its record job saves the table there with the
commit it ran and adds its totals to the port.jar-links points in scoreboard/points.json. A commit to main for each would
put every open pull request behind. `python bench/generated.py show scoreboard/all.json` prints the newest; compare
prints what a branch's table changes against main's (tools/cloud-run exposure finds main's for the branch's base).

Every stage is kept in ~/.mmt/exposure/ under the content of everything it reads (the porter's classes, the route map,
the rules, the loaders' release lists, the JVM, each mod's jar), shared by every worktree and run on the machine, so a
run computes only what an edit can change: an edit to JarPort ports every mod again (each one runs the code it touched),
but reuses the downloads, the names, the map, the data check and most of Exposure's answers; an edit to anything else
reuses the ports. jars prints which mods newly link, stop linking or changed their port against the run before, and the
load --mods that proves only those in game. --verify computes everything again and fails where a kept answer differs (main's sweep).

--save keeps a jars or load summary in bench/exposure/, named for the update and the commit it ran on, so two pull
requests proving the same update keep two receipts instead of conflicting on one.

load is the proof that the list predicts the game: the mods jars says link, with the library mods each one's Modrinth
version lists as required (their release for the new version, else their old one ported along), are ported and
started together on a real server of the new release (mmt pack-check), then in a real client of it (pack-check --side
client: the title screen, a world, the inventory and every creative tab), since a server never runs client code; a
failed start drops the mod its log names and starts again. It reports each mod's server and client verdicts side by
side, and any mod that loaded on the server and failed in the client.

jars also runs the JVM's verifier (the JDK's copy of HotSpot's) over every ported class against the new release, as the
game would when it loads the class: a value of a type the instruction using it does not accept, or a method declared
twice, is a VerifyError or ClassFormatError in game though every reference links, and the mod does not link. A class
route (a removed class moved to the class the game put in its place) the verifier refuses for a mod is left out for that
mod, and the old class is then a stand-in's (knowledge/standins) or reported gone.

jars also checks each mod's data files against the new release: a built-in game id (an enchantment effect type, a loot
function, a data component) that the old release registers and the new one does not, read from each release's own
data generator report. A server refuses such a file, which the link check cannot see; modsThatLinkAndData counts the
mods that pass both.

steps measures the porter on every update at once, with no mods: GameDiff links each release to the next from the
game's own code, and for every update it counts the classes, methods and fields with no counterpart in the next release (renames,
moves and new signatures are linked and carried), and how many of them have a replacement learned from how the game's
own code changed (call rewrites, call chains, class replacements). A new kind of change the learner understands shows up in every
update here, not only in the one being worked on. Kept in bench/exposure/steps.json.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# Gigabytes of heap for the Java tools; lower it when other work shares the machine.
HEAP = int(os.environ.get("MMT_HEAP_GB") or 10)
HOME = Path(os.environ.get("MMT_EXPOSURE_HOME") or Path(os.path.expanduser("~")) / ".mmt" / "exposure")
# --verify: port every mod and ask Exposure every reference as if nothing were cached, and fail where a cached answer with
# the same key differs. Main's sweep runs so, with the answers of main's sweep before it, as the check that the cache is exact.
VERIFY = False
AGENT = {"User-Agent": "modding-accelerator-exposure/1.0"}
INDEX = ROOT / "coverage" / "changes" / "index.json"
PINS = ROOT / "bench" / "exposure" / "mods"
COUNT = 150
# The first release each loader's mods are read for, in Mojang's names: Fabric's through intermediary before 26.1,
# NeoForge's (its first release is 1.20.2) through NeoForm's SRG ids before 1.20.5, as shipped after.
FIRST = {"fabric": "1.14.4", "neoforge": "1.20.2"}
UNOBFUSCATED = "26.1"
MOJANG_NEOFORGE = "1.20.5"


def api(path, params=None):
    url = "https://api.modrinth.com/v2/" + path + ("?" + urllib.parse.urlencode(params) if params else "")
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=AGENT), timeout=60) as r:
                return json.load(r)
        except Exception as error:  # a 429 or a dropped connection: back off and try again
            if attempt == 3:
                raise
            time.sleep(5 * (attempt + 1))


def project_versions(project):
    """A Modrinth project's versions, newest first, with what choosing a file needs; kept a day, so pinning every release asks once a project."""
    cache = HOME / "modrinth" / f"{project}.json"
    if cache.is_file() and time.time() - cache.stat().st_mtime < 86400:
        try:
            return json.loads(cache.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            pass  # another run is writing it: ask again
    versions = [{"id": v["id"], "published": v["date_published"], "game": v["game_versions"], "loaders": v["loaders"],
                 "files": [{"url": f["url"], "file": f["filename"], "primary": f.get("primary"), "sha1": f["hashes"].get("sha1")}
                           for f in v.get("files", [])]}
                for v in api(f"project/{project}/version", {"include_changelog": "false"})]
    versions.sort(key=lambda v: v["published"], reverse=True)
    cache.parent.mkdir(parents=True, exist_ok=True)
    part = cache.with_name(f"{project}.{os.getpid()}.{id(versions)}.part")
    part.write_text(json.dumps(versions), encoding="utf-8")
    try:
        part.replace(cache)
    except OSError:
        part.unlink(missing_ok=True)  # another run just wrote the same list
    return versions


def pin(loader, version, count=COUNT):
    """The most-downloaded mods of one loader and release, each with its newest primary jar for that release, kept in PINS."""
    mods, offset = [], 0
    while len(mods) < count:
        facets = json.dumps([["project_type:mod"], [f"categories:{loader}"], [f'versions:"{version}"']])  # unquoted, 1.20 is read as a number and matches nothing
        hits = api("search", {"facets": facets, "index": "downloads", "limit": 100, "offset": offset}).get("hits", [])
        if not hits:
            break
        offset += len(hits)
        for hit in hits:
            if len(mods) >= count:
                break
            for v in project_versions(hit["project_id"]):
                if loader not in v["loaders"] or version not in v["game"]:
                    continue
                f = next((f for f in v["files"] if f["primary"] and f["file"].endswith(".jar")), None)
                if f:
                    mods.append({"slug": hit["slug"], "project": hit["project_id"], "version": v["id"], **{k: f[k] for k in ("file", "url", "sha1")}})
                    break
    path = PINS / f"{loader}-{version}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    write_pin(path, {"loader": loader, "version": version, "date": date.today().isoformat(), "mods": mods})
    print(f"pinned {len(mods)} {loader} mods for {version} in {path.relative_to(ROOT)}")
    return path


def write_pin(path, pin):
    """One mod a line, so a repinned release reads as the mods that changed."""
    head = json.dumps({k: v for k, v in pin.items() if k != "mods"})[:-1]
    path.write_text(head + ', "mods": [\n' + ",\n".join(json.dumps(m) for m in pin["mods"]) + "\n]}\n", encoding="utf-8")


def fetch(loader, version, repin=False):
    """The pinned mods of one loader and release, downloaded once into ~/.mmt/exposure/jars/LOADER-VERSION/ (pinned first if they are not)."""
    path = PINS / f"{loader}-{version}.json"
    if repin or not path.is_file():
        pin(loader, version)
    mods = json.loads(path.read_text(encoding="utf-8"))["mods"]
    folder = HOME / "jars" / f"{loader}-{version}"
    folder.mkdir(parents=True, exist_ok=True)

    def one(m):
        target = folder / f"{m['slug']}--{m['file']}"
        if target.is_file():
            return target
        for attempt in range(3):
            try:
                with urllib.request.urlopen(urllib.request.Request(m["url"], headers=AGENT), timeout=300) as r:
                    data = r.read()
                break
            except Exception as error:
                if attempt == 2:
                    print(f"  {m['slug']}: {error}", file=sys.stderr)
                    return None
                time.sleep(5 * (attempt + 1))
        if m.get("sha1") and hashlib.sha1(data).hexdigest() != m["sha1"]:
            print(f"  {m['slug']}: the download is not the pinned file", file=sys.stderr)
            return None
        part = part_of(target)
        part.write_bytes(data)
        return publish(part, target)

    with ThreadPoolExecutor(8) as pool:
        jars = [j for j in pool.map(one, mods) if j]
    return jars


def releases():
    data = json.loads(INDEX.read_text(encoding="utf-8"))
    return [v["id"] for v in data["allVersions"] if v.get("status") == "indexed"]


def vkey(v):
    return tuple(int(x) for x in re.findall(r"\d+", v))


def chain(frm, to):
    """Every indexed release from one to the other, in the direction of the port."""
    all_versions = sorted(releases(), key=vkey)
    lo, hi = sorted([frm, to], key=vkey)
    span = [v for v in all_versions if vkey(lo) <= vkey(v) <= vkey(hi)]
    return span if vkey(frm) <= vkey(to) else list(reversed(span))


def updates(loader):
    """Every update from one indexed release to the next whose mod jars are read for this loader."""
    every = [v for v in sorted(releases(), key=vkey) if vkey(v) >= vkey(FIRST[loader])]
    return list(zip(every, every[1:]))


def families():
    """Each indexed release's family: releases between which the game changes almost nothing (coverage/changes/index.json)."""
    data = json.loads(INDEX.read_text(encoding="utf-8"))
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


def java():
    jdk = Path(os.path.expanduser("~")) / ".mmt" / "jdks"
    for candidate in sorted(jdk.glob("temurin-25*"), reverse=True) + sorted(jdk.glob("temurin-2[5-9]*"), reverse=True):
        exe = candidate / "bin" / ("java.exe" if os.name == "nt" else "java")
        if exe.is_file():
            return str(exe)
    return "java"


def compile_tools():
    """The class path GameDiff and Exposure run from: the snapshot reads loader jars in Mojang names through ModNames (ASM)."""
    return jar_tools()


# What a route map is built from: it changes with GameDiff or VersionMap, not with Exposure itself.
MAP_SOURCES = ("GameDiff.java", "VersionMap.java", "Exposure.java", "ModNames.java", "FixExpr.java", "cli/Json.java", "cli/Versions.java")


def tools_key(*names):
    """The content of the tools that build a cache entry, hashed. Several worktrees share ~/.mmt/exposure and run at once;
    keyed by file times, one worktree's newer tools wiped what another was reading (#126). Keyed by content, worktrees
    with the same tools share entries and worktrees with other tools never touch each other's."""
    h = hashlib.sha256()
    for name in names:
        h.update(name.encode() + b"\0" + (ROOT / "tools" / name).read_bytes() + b"\0")
    return h.hexdigest()[:16]


KEY = re.compile(r"^[0-9a-f]{16}$")


def keyed(kind, *names):
    """~/.mmt/exposure/KIND/KEY/ for the tools named."""
    return keyed_at(kind, tools_key(*names))


def keyed_at(kind, key):
    """~/.mmt/exposure/KIND/KEY/; a key no run has used for a day is removed when a new one is made."""
    base = HOME / kind
    folder = base / key
    fresh = not folder.is_dir()
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "used").write_text(str(os.getpid()))
    if fresh:
        for old in base.iterdir():
            used = old / "used"
            if old != folder and KEY.match(old.name) and used.is_file() and time.time() - used.stat().st_mtime > 86400:
                shutil.rmtree(old, ignore_errors=True)
    return folder


def publish(part, target):
    """Moves a finished file into a shared cache entry. If a run beside this one finished it first, its copy is kept: the same
    tools on the same inputs wrote the same thing, and replacing a file another run is reading can break that run."""
    # A hard link never replaces, on any system (a move replaces on POSIX); a file system without hard links gets the move.
    try:
        os.link(part, target)
    except FileExistsError:
        pass
    except OSError:
        try:
            os.rename(part, target)
        except FileExistsError:
            pass
    part.unlink(missing_ok=True)
    return target


def part_of(target):
    """A name for a file being written into a shared cache, unique to this run."""
    return target.with_name(f"{target.name}.{os.getpid()}.{uuid.uuid4().hex[:12]}.part")


MAPPINGS = Path(os.path.expanduser("~")) / ".mmt" / "mappings"  # VersionMap.cache: where the tools keep what they download
# The loaders' release lists. The tools take the newest loader, API and loader build of a release from these, so they are
# inputs of every map and port; each is kept once downloaded (VersionMap.fetch), here as in the tools.
PLATFORM = {
    "neoforge-maven-metadata.xml": "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml",
    "fabric-loader-maven-metadata.xml": "https://maven.fabricmc.net/net/fabricmc/fabric-loader/maven-metadata.xml",
}


def platform_bytes():
    """The loaders' release lists the tools will read, downloaded first if this machine has none, as VersionMap.fetch would."""
    out = b""
    for name, url in PLATFORM.items():
        path = MAPPINGS / name
        if not path.is_file() or not path.stat().st_size:
            MAPPINGS.mkdir(parents=True, exist_ok=True)
            with urllib.request.urlopen(urllib.request.Request(url, headers=AGENT), timeout=60) as r:
                data = r.read()
            part = part_of(path)
            part.write_bytes(data)
            publish(part, path)
        out += name.encode() + b"\0" + path.read_bytes() + b"\0"
    return out


def platform_key(loader, route):
    """Which loader releases a port and Exposure read: the builds the route map names for each release (NeoForge's, and
    the Fabric API file of each); Fabric Loader's build is chosen from its release list, which the map does not name."""
    platform_bytes()
    loader_list = (MAPPINGS / "fabric-loader-maven-metadata.xml").read_bytes() if loader == "fabric" else b""
    return json.dumps(json.loads(route.read_text(encoding="utf-8")).get("loader"), sort_keys=True).encode() + loader_list


def content_key(*parts):
    """A cache key from every input's content: equal keys mean equal inputs, so a cached answer is the answer."""
    h = hashlib.sha256()
    for p in parts:
        h.update(p if isinstance(p, bytes) else str(p).encode())
        h.update(b"\0")
    return h.hexdigest()[:16]


def java_version():
    return subprocess.run([java(), "-version"], capture_output=True, text=True).stderr.strip()


def route_map(frm, to, loader, classes):
    return route_maps([(frm, to)], loader, classes)[0]


def route_maps(updates, loader, classes):
    """The route map of each (from, to), kept in ~/.mmt/exposure/maps/. The missing ones are built by one GameDiff process,
    each as its own chain: a long port has a map per step (stops), and a process per map started cold and read both
    releases again every time."""
    folder = keyed_at("maps", content_key(tools_key(*MAP_SOURCES), platform_bytes(), java_version()))
    # Fabric API is chosen per release (Exposure.fabricApi), not from a list platform_bytes holds: a map is kept per choice.
    jars = fabric_apis([v for frm, to in updates for v in chain(frm, to)]) if loader == "fabric" else {}
    apis = lambda frm, to: "-" + content_key(*(jars[v].name if jars[v] else "" for v in chain(frm, to))) if jars else ""
    targets = [folder / f"{loader}-{frm}-{to}{apis(frm, to)}.json" for frm, to in updates]
    missing = {t: (frm, to) for t, (frm, to) in zip(targets, updates) if not t.is_file()}
    if missing:
        parts = {t: part_of(t) for t in missing}
        args = [java(), f"-Xmx{HEAP}g", "-cp", str(classes), "GameDiff"] + (["--loader", loader] if loader else [])
        for t, (frm, to) in missing.items():
            args += ["--chain", ",".join(chain(frm, to)), "--out", str(parts[t])]
        subprocess.run(args, check=True, stdout=subprocess.DEVNULL)
        for t, part in parts.items():
            publish(part, t)
    return targets


ASM = [ROOT / "tools" / "perf" / "lib" / "asm-9.9.jar", ROOT / "tools" / "perf" / "lib" / "asm-commons-9.9.jar"]
JAR_SOURCES = ("JarPort.java", "FixExpr.java", "PortChains.java", "Exposure.java", "GameDiff.java", "VersionMap.java", "ModNames.java", "cli/Json.java",
               "cli/Versions.java")


def tools_classes_key():
    """The porter's sources and the ASM it is built with, hashed: what its classes, and so every port, are made of."""
    if "key" not in CLASSES:
        CLASSES["key"] = content_key(tools_key(*JAR_SOURCES), *(a.read_bytes() for a in ASM))
    return CLASSES["key"]


CLASSES = {}  # this run's porter: read once, so an edit made while a sweep runs does not mix two porters in it


def jar_tools():
    """The porter's classes, compiled once per content of its sources in ~/.mmt/exposure/classes/KEY/, so every worktree
    and every run with the same sources shares one compile (a compile is about 20 s; agents spent hours on them)."""
    if "path" in CLASSES:
        return CLASSES["path"]
    out = keyed_at("classes", tools_classes_key()) / "classes"
    if not (out / "JarPort.class").is_file():
        sources = [ROOT / "tools" / n for n in JAR_SOURCES]
        javac = java().replace("java.exe", "javac.exe") if java().endswith("java.exe") else java() + "c"
        part = part_of(out)
        subprocess.run([javac, "-nowarn", "-encoding", "UTF-8", "-cp", os.pathsep.join(map(str, ASM)), "-d", str(part)] + [str(s) for s in sources],
                       check=True)
        try:
            os.rename(part, out)
        except OSError:
            shutil.rmtree(part, ignore_errors=True)  # a run beside this one compiled the same sources first
    CLASSES["path"] = os.pathsep.join([str(out)] + [str(a) for a in ASM])
    return CLASSES["path"]


# Each process that reads jars (ModNames; JarPort ports on every processor in one process) starts cold and reads a
# release first, so more piles stop paying: when JarPort ran in piles, 150 NeoForge mods took 144 s in one pile on 24
# processors, 66 s in four and 75 s in nine.
PILE = 36
# A pile only pays with processors to spare for its start: on GitHub's 4-processor runners four piles took 104 s
# where one had taken about 90.
SPARE = 4
# Processors this run may use; all runs every update side by side and gives each its share.
CORES = os.cpu_count() or 1


def piles(jars):
    """The jars dealt into piles that take about as long, one per processor: every next-biggest jar goes on the lightest pile."""
    count = max(1, min(CORES // SPARE, len(jars) // PILE))
    dealt = [[0, []] for _ in range(count)]
    for jar in sorted(jars, key=lambda j: (-j.stat().st_size, j.name)):
        pile = min(dealt, key=lambda d: d[0])
        pile[0] += jar.stat().st_size
        pile[1].append(jar)
    return [pile for _, pile in dealt]


def side_by_side(command, jars, report, one=False):
    """Runs a tool that reads jars one at a time (ModNames) on every processor at once: a jar's result does not depend on
    the jars beside it, and one process used one of this machine's processors (150 mods took 140 s on one of 24). one: a
    tool that uses every processor itself (JarPort) gets every jar in one process. Returns the reports' rows in the order
    the jars were given."""
    dealt = [list(jars)] if one else piles(jars)
    if len(dealt) > 1:
        # The piles share the memory one process had.
        command = [f"-Xmx{max(3, HEAP // len(dealt))}g" if c.startswith("-Xmx") else c for c in command]
    parts = [report.with_name(f"{report.stem}.{i}{report.suffix}") for i in range(len(dealt))]
    running = [subprocess.Popen(command + ["--report", str(part)] + [str(j) for j in pile], stdout=subprocess.DEVNULL) for part, pile in zip(parts, dealt)]
    codes = [process.wait() for process in running]
    if any(codes):
        raise SystemExit(f"{' '.join(command[:1] + [c for c in command if c in ('JarPort', 'ModNames')])} failed on a pile of jars (exit {max(codes, key=abs)}); its output is above")
    rows = {}
    for part in parts:
        for row in json.loads(part.read_text(encoding="utf-8"))["mods"]:
            rows[row["mod"]] = row
        part.unlink()
    return [rows[j.name] for j in jars if j.name in rows]


def mojang_names(loader, version, jars):
    """The jars in Mojang's names: Fabric's before 26.1 read through intermediary by tools/ModNames.java (kept in
    ~/.mmt/exposure/mojang/ until ModNames or VersionMap changes), every other one as it is. A jar it cannot read is
    left out, with why."""
    if not shipped_obfuscated(loader, version):
        return jars, {}
    out = keyed("mojang", *NAME_SOURCES) / f"{loader}-{version}"
    out.mkdir(parents=True, exist_ok=True)
    missing = [j for j in jars if not (out / j.name).is_file()]
    failed = {}
    if missing:
        # ModNames writes each jar under a name of its own and moves it into place, so a run beside this one never reads half a jar.
        report = ROOT / ".runs" / "exposure" / f"names-{loader}-{version}-{os.getpid()}.json"
        report.parent.mkdir(parents=True, exist_ok=True)
        rows = side_by_side([java(), f"-Xmx{min(4, HEAP)}g", "-cp", jar_tools(), "ModNames", "--version", version, "--loader", loader, "--out", str(out)], missing, report)
        failed = {m["mod"]: m["error"] for m in rows if "error" in m}
    return [out / j.name for j in jars if (out / j.name).is_file()], failed


# What reading a jar in Mojang's names is built from.
NAME_SOURCES = ("ModNames.java", "Exposure.java", "VersionMap.java", "cli/Json.java", "cli/Versions.java")


def shipped_obfuscated(loader, version):
    """Whether a loader's mods for a release name the game in other names than Mojang's: Fabric's in intermediary before 26.1,
    NeoForge's in SRG ids (m_N_, f_N_) before 1.20.5."""
    return vkey(version) < vkey(UNOBFUSCATED) if loader == "fabric" else vkey(version) < vkey(MOJANG_NEOFORGE)


def stops(loader, frm, to):
    """The releases a port from frm to to stops at (tools/JarPort.java, hops): each update with stand-ins is a step of its
    own, so its stand-ins run and the steps after it port them; the updates between are one step on their composed map."""
    key = (loader, frm, to)
    if key not in STOPS:
        if len(chain(frm, to)) <= 2:
            STOPS[key] = [frm, to]
        else:
            planned = subprocess.run([java(), "-cp", jar_tools(), "JarPort", "--hops", "--chain", ",".join(chain(frm, to)),
                                      "--fixes", str(ROOT / "knowledge" / "fixes.json"), "--loader", loader], capture_output=True, text=True, check=True)
            STOPS[key] = planned.stdout.strip().splitlines()[-1].split(",")
    return STOPS[key]


STOPS = {}


def routes(loader, frm, to):
    """The route map of each step of a port from frm to to."""
    steps = stops(loader, frm, to)
    return route_maps(list(zip(steps, steps[1:])), loader, compile_tools())


def port_key(loader, frm, to, runtime=False):
    """Everything one port reads beside the mod's own jar: the porter's classes, each step's route map and stand-ins, the
    port rules, the loaders' release lists, the JVM and the update. A mod's port with an equal key and an equal jar is the same port."""
    # The hand-back of an obfuscated release reads the jars as they came too: the release's pinned files.
    pinned = (PINS / f"{loader}-{frm}.json").read_bytes() if shipped_obfuscated(loader, frm) and (PINS / f"{loader}-{frm}.json").is_file() else b""
    steps = stops(loader, frm, to)
    maps = routes(loader, frm, to)
    return content_key(tools_classes_key(), *(r.read_bytes() for r in maps), (ROOT / "knowledge" / "fixes.json").read_bytes(),
                       *(standins_key(loader, a, b) for a, b in zip(steps, steps[1:])), *(platform_key(loader, r) for r in maps),
                       java_version(), loader, frm, to, runtime, pinned)


def standins_key(loader, frm, to):
    """The stand-ins JarPort reads for one update and loader (knowledge/standins/FROM-TO/ and FROM-TO.LOADER/), each file's
    path and content."""
    parts = []
    for folder in (ROOT / "knowledge" / "standins" / f"{frm}-{to}", ROOT / "knowledge" / "standins" / f"{frm}-{to}.{loader}"):
        if folder.is_dir():
            for f in sorted(folder.rglob("*.java")):
                parts += [f.relative_to(folder.parent).as_posix().encode(), f.read_bytes()]
    return content_key(*parts)


def jar_port(loader, frm, to, jars, out_dir, report, runtime=False, cache=False):
    """Ports jars with tools/JarPort.java into out_dir and returns its report. runtime: in the names the loader runs
    (a Fabric jar handed back in the target release's intermediary), for a game to start them. cache: only the report is
    wanted, so a mod whose port (port_key and its jar's content) was made before, by any run on this machine, is read from
    ~/.mmt/exposure/ports/ instead of ported again; only the rest go to JarPort."""
    classpath = jar_tools()
    steps = stops(loader, frm, to)
    maps = routes(loader, frm, to)
    out_dir.mkdir(parents=True, exist_ok=True)
    rows, todo, keys, checked = {}, [], {}, 0
    if cache:
        folder = keyed_at("ports", port_key(loader, frm, to, runtime))
        for jar in jars:
            keys[jar.name] = folder / (hashlib.sha256(jar.read_bytes()).hexdigest()[:24] + ".json")
            if keys[jar.name].is_file() and not VERIFY:
                rows[jar.name] = json.loads(keys[jar.name].read_text(encoding="utf-8"))
            else:
                todo.append(jar)
    else:
        todo = list(jars)
    if todo:
        args = [java(), f"-Xmx{HEAP}g", "-cp", classpath, "JarPort", "--from", frm, "--to", to, "--fixes", str(ROOT / "knowledge" / "fixes.json"),
                "--out", str(out_dir), "--loader", loader] + (["--runtime-names"] if runtime else [])
        if len(steps) > 2:
            args += ["--via", ",".join(steps[1:-1])]
        for route in maps:
            args += ["--map", str(route)]
        if shipped_obfuscated(loader, frm):
            # The jars as they came, before mojang_names: the hand-back gives a class's own intermediary names back from them.
            args += ["--originals", str(HOME / "jars" / f"{loader}-{frm}")]
        # One process: JarPort ports the jars of each step on every processor, and each process read every step's releases.
        for m in side_by_side(args, todo, report, one=True):
            rows[m["mod"]] = m
            if cache:
                kept = {k: v for k, v in m.items() if k != "out"}  # where this run wrote it, not part of the answer
                if VERIFY and keys[m["mod"]].is_file():
                    checked += 1
                if VERIFY and keys[m["mod"]].is_file() and json.loads(keys[m["mod"]].read_text(encoding="utf-8")) != kept:
                    raise SystemExit(f"the cached port of {m['mod']} ({loader} {frm} -> {to}, {keys[m['mod']]}) differs from porting it again: "
                                     "the cache key misses an input of the port, or the port is not the same run to run")
                part = part_of(keys[m["mod"]])
                part.write_text(json.dumps(kept), encoding="utf-8")
                publish(part, keys[m["mod"]])
    rows = [rows[j.name] for j in jars if j.name in rows]
    counts = {}
    for m in rows:
        for member in left(m):
            counts[member] = counts.get(member, 0) + 1
    merged = {"from": frm, "to": to, "loader": loader, "linked": sum(1 for m in rows if "error" not in m and not m["unlinked"]), "mods": rows,
              "unlinkedByMods": [{"member": k, "mods": v} for k, v in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))],
              "ported": len(todo), "verified": checked}
    report.write_text(json.dumps(merged, indent=1), encoding="utf-8")
    return merged


def classes_key(classpath, names):
    """The bytes of the named classes of the porter's classpath (its first entry), hashed with the ASM it runs with."""
    folder = Path(classpath.split(os.pathsep)[0])
    return content_key(*sorted(names), *((folder / (n.replace(".", "/") + ".class")).read_bytes() if (folder / (n.replace(".", "/") + ".class")).is_file() else b"gone" for n in sorted(names)),
                       *(a.read_bytes() for a in ASM))


LOADED = re.compile(r"\[class,load\] (\S+) source: (?:file:)?(.*)$")


def game_says(loader, frm, to, report):
    """What the game's own code did about each reference JarPort's report ranks (tools/Exposure.java). Exposure answers each
    reference on its own, from the two releases alone, and runs only some of the porter's classes (not JarPort's port), so
    every answer is kept in ~/.mmt/exposure/game/ under the bytes of the classes it loaded the last time and the loaders'
    release lists: an edit to the port leaves it, and it runs again only for a reference it never answered. A class it
    starts to load is loaded from code that changed, so its key changes with it."""
    classes = compile_tools()
    asked = [r["member"] for r in json.loads(report.read_text(encoding="utf-8"))["unlinkedByMods"]]
    # The loader releases of each step: a port that stops on the way reads no map of the whole jump, and none is built for it.
    folder = keyed_at("game", content_key(*(platform_key(loader, r) for r in routes(loader, frm, to)), java_version())) / f"{loader}-{frm}-{to}"
    folder.mkdir(parents=True, exist_ok=True)
    loaded = folder / "loaded.txt"
    known = folder / (classes_key(classes, loaded.read_text(encoding="utf-8").split()) + ".json") if loaded.is_file() else None
    said = json.loads(known.read_text(encoding="utf-8")) if known and known.is_file() else {"answered": [], "said": {}}
    if VERIFY or not set(asked) <= set(said["answered"]):
        out = report.with_name("game.json")
        log = report.with_name("game-classes.log")
        subprocess.run([java(), f"-Xmx{HEAP}g", f"-Xlog:class+load=info:file={log}", "-cp", str(classes), "Exposure", "--from", frm, "--to", to,
                        "--report", str(report), "--out", str(out), "--loader", loader], check=True, stdout=subprocess.DEVNULL)
        own = Path(classes.split(os.pathsep)[0]).resolve()
        names = sorted({m.group(1) for m in map(LOADED.search, log.read_text(encoding="utf-8", errors="replace").splitlines())
                        if m and Path(urllib.parse.unquote(m.group(2))).resolve() == own})
        fresh = json.loads(out.read_text(encoding="utf-8"))
        target = folder / (classes_key(classes, names) + ".json")
        if target != known:
            said = json.loads(target.read_text(encoding="utf-8")) if target.is_file() else {"answered": [], "said": {}}
        wrong = [m for m in asked if m in said["answered"] and said["said"].get(m) != fresh.get(m)]
        if wrong:
            raise SystemExit(f"Exposure's cached answer for {wrong[0]} ({loader} {frm} -> {to}) differs from asking again ({len(wrong)} references)")
        # A reference Exposure cannot place in the old release gets no row; it is still answered.
        said = {"answered": sorted(set(said["answered"]) | set(asked)), "said": {**said["said"], **fresh}}
        for path, text in ((target, json.dumps(said)), (loaded, "\n".join(names))):
            part = part_of(path)
            part.write_text(text, encoding="utf-8")
            os.replace(part, path)  # a later answer only adds rows; each row is the same in every run with this key
    return {m: said["said"][m] for m in asked if m in said["said"]}


def registry_ids(version):
    """Every id the release registers in its built-in registries, by registry, from its own data generator's report
    (the vanilla server run with --reports), kept in ~/.mmt/exposure/registries/; None when that release's generator does
    not run here."""
    cache = HOME / "registries" / f"{version}.json"
    if cache.is_file():
        return json.loads(cache.read_text(encoding="utf-8"))
    manifest = json.load(urllib.request.urlopen(urllib.request.Request("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", headers=AGENT), timeout=60))
    url = next(v["url"] for v in manifest["versions"] if v["id"] == version)
    meta = json.load(urllib.request.urlopen(urllib.request.Request(url, headers=AGENT), timeout=60))
    work = ROOT / ".runs" / "exposure" / "registries" / version
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)
    server = work / "server.jar"
    with urllib.request.urlopen(urllib.request.Request(meta["downloads"]["server"]["url"], headers=AGENT), timeout=300) as r:
        server.write_bytes(r.read())
    import zipfile
    with zipfile.ZipFile(server) as z:
        bundled = "META-INF/versions.list" in z.namelist()
    # From 1.18 the server jar is a bundler that names the class to run; before, the generator is on its class path.
    command = ([java(), "-DbundlerMainClass=net.minecraft.data.Main", "-jar", str(server)] if bundled
               else [java(), "-cp", str(server), "net.minecraft.data.Main"]) + ["--reports", "--output", str(work / "generated")]
    done = subprocess.run(command, cwd=work, capture_output=True, text=True, encoding="utf-8", errors="replace")
    report = work / "generated" / "reports" / "registries.json"
    if done.returncode or not report.is_file():
        return None
    ids = {name: sorted(entry["entries"]) for name, entry in json.loads(report.read_text(encoding="utf-8")).items()}
    cache.parent.mkdir(parents=True, exist_ok=True)
    part = part_of(cache)
    part.write_text(json.dumps(ids), encoding="utf-8")
    publish(part, cache)
    return ids


GAME_ID = re.compile(r"^minecraft:[a-z0-9_./-]+$")
# Keys whose value a codec looks up in a built-in registry, by the game's own data format: the dispatch keys of every
# typed object (an enchantment effect, a feature, a placement), a loot function and a loot or predicate condition. Any
# other string may name something data-driven or of another kind, so it is not judged.
DECODED_BY = {"type", "function", "condition"}


def data_check(frm, to, mods):
    """What each mod's data files (and its nested mods') name of the game that the new release no longer registers: a
    built-in id of the old release (an enchantment effect type, a data component, a loot function ...) that is in no
    registry of the new one. The server refuses such a file when it loads the mod's data, which the link check cannot see.
    None when either release's registry report is not available."""
    old, new = registry_ids(frm), registry_ids(to)
    if old is None or new is None:
        return None
    gone = {}
    present = {i for ids in new.values() for i in ids}
    for registry, ids in old.items():
        for i in ids:
            if i not in present:
                gone.setdefault(i, registry)
    import io
    import zipfile

    def scan(data, name, found):
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            for entry in z.namelist():
                if entry.endswith(".jar") and entry.startswith(("META-INF/jarjar/", "META-INF/jars/")):
                    scan(z.read(entry), name, found)
                elif entry.startswith("data/") and entry.endswith(".json") and "/tags/" not in entry:
                    # A tag naming a missing entry is skipped with a warning; the files below are refused.
                    try:
                        value = json.loads(z.read(entry).decode("utf-8-sig"))
                    except (ValueError, UnicodeDecodeError):
                        continue
                    stack = [(None, value)]
                    while stack:
                        key, v = stack.pop()
                        if isinstance(v, dict):
                            for k, item in v.items():
                                # A data component is named by its key ("minecraft:food" in an item's components).
                                if key == "components" and k.lstrip("!") in gone:
                                    found.setdefault(f"data id gone: {gone[k.lstrip('!')]} {k.lstrip('!')}", entry)
                                stack.append((k, item))
                        elif isinstance(v, list):
                            stack.extend((key, item) for item in v)
                        elif key in DECODED_BY and isinstance(v, str) and GAME_ID.match(v) and v in gone:
                            found.setdefault(f"data id gone: {gone[v]} {v}", entry)

    out = {}
    for jar in mods:
        found = {}
        try:
            scan(jar.read_bytes(), jar.name, found)
        except (OSError, zipfile.BadZipFile):
            continue
        out[jar.name] = sorted(f"{ref} (in {where})" for ref, where in found.items())
    return out


LOADER_REF = re.compile(r": (?:net\.neoforged|net\.fabricmc)\.")


def platform_ids(loader, to):
    """The mod ids the platform a pack runs on provides for the new release: Fabric API's modules. Fabric Loader keeps the
    newest copy of each mod id, so a copy of one nested in a mod is not what runs; NeoForge has no such modules."""
    if loader != "fabric":
        return set()
    api = fabric_api(to)
    return set(nested_ids(api.read_bytes())) if api else set()


def nested_ids(data):
    """Every fabric.mod.json id in a jar and the jars nested in it, by the nested jar's file name (the jar's own as "")."""
    import io
    import zipfile
    out = {}
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for entry in z.namelist():
            if entry == "fabric.mod.json":
                meta = json.loads(z.read(entry).decode("utf-8", "replace"), strict=False)
                out[meta.get("id")] = ""
                for p in meta.get("provides") or []:
                    out[p] = ""
            elif entry.startswith("META-INF/jars/") and entry.endswith(".jar"):
                for i in nested_ids(z.read(entry)):
                    out[i] = entry.rsplit("/", 1)[1]
    return out


def hidden_code(data):
    """The name of a jar inside a mod that holds game code and no loader metadata lists as nested (fabric.mod.json "jars",
    NeoForge's jarjar list): code the mod's own loader unpacks at start (Essential), which the port never reads. None if none."""
    import io
    import zipfile
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        names = z.namelist()
        listed = set()
        if "fabric.mod.json" in names:
            try:
                listed |= {j.get("file") for j in json.loads(z.read("fabric.mod.json").decode("utf-8", "replace"), strict=False).get("jars") or [] if isinstance(j, dict)}
            except ValueError:
                pass
        if "META-INF/jarjar/metadata.json" in names:
            try:
                listed |= {j.get("path") for j in json.loads(z.read("META-INF/jarjar/metadata.json")).get("jars") or [] if isinstance(j, dict)}
            except ValueError:
                pass
        for entry in names:
            if not entry.endswith(".jar"):
                continue
            inner = z.read(entry)
            if entry not in listed:
                with zipfile.ZipFile(io.BytesIO(inner)) as i:
                    if any(n.endswith(".class") and b"net/minecraft/" in i.read(n) for n in i.namelist()):
                        return entry
            else:
                deeper = hidden_code(inner)
                if deeper:
                    return entry + "!" + deeper
    return None


def with_hidden_code_unread(report, jars):
    """A mod whose game code is in a jar its own loader unpacks is reported as not read, not as linking: the port never
    saw that code (Essential links with none of its code read, and fails on 1.21.2 on a removed class)."""
    for m in report["mods"]:
        if "error" in m or m["mod"] not in jars:
            continue
        try:
            inner = hidden_code(jars[m["mod"]].read_bytes())
        except Exception:  # a jar zipfile cannot open: JarPort judged it already
            continue
        if inner:
            m["error"] = f"its game code is in {inner}, a jar no loader metadata lists (its own loader unpacks it at start), which the port does not read"
    report["linked"] = sum(1 for m in report["mods"] if "error" not in m and not m["unlinked"])
    return report


def class_java(data):
    """The newest Java a jar's classes, and those of the jars nested in it, are compiled for (a class file's major version
    less 44), or 0. A multi-release jar's META-INF/versions/ copies and module-info only load on the Java that reads them."""
    import io
    import zipfile
    newest = 0
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for entry in z.namelist():
            if entry.endswith(".jar"):
                newest = max(newest, class_java(z.read(entry)))
            elif entry.endswith(".class") and not entry.startswith("META-INF/versions/") and not entry.endswith("module-info.class"):
                head = z.open(entry).read(8)
                if head[:4] == b"\xca\xfe\xba\xbe":
                    newest = max(newest, int.from_bytes(head[6:8], "big") - 44)
    return newest


def release_java(version):
    """The Java a release runs on: its version.json's javaVersion (8 before 1.17, 16, 17 from 1.18, 21 from 1.20.5...)."""
    kept = Path(os.path.expanduser("~")) / ".mmt" / "mappings" / f"version-{version}.json"
    if kept.is_file():
        meta = json.loads(kept.read_text(encoding="utf-8"))
    else:
        manifest = json.load(urllib.request.urlopen(urllib.request.Request("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", headers=AGENT), timeout=60))
        meta = json.load(urllib.request.urlopen(urllib.request.Request(next(v["url"] for v in manifest["versions"] if v["id"] == version), headers=AGENT), timeout=60))
    return int((meta.get("javaVersion") or {}).get("majorVersion") or 8)


def with_target_java(report, jars, to):
    """A mod compiled for a newer Java than the release runs on does not link there: the release's JVM refuses its
    classes before any reference is read. Ports back to an older release meet it (a 1.21.1 mod's Java 21 classes on
    1.20.2's Java 17, a 1.20.1 mod's Java 17 on 1.16.5's Java 8), and a real 1.20.2 server started without such a mod
    while the link count called it linked. The port keeps each class's version, so the mod as it came says it."""
    runs_on = release_java(to)
    for m in report["mods"]:
        if "error" in m or m["mod"] not in jars:
            continue
        try:
            needs = class_java(jars[m["mod"]].read_bytes())
        except Exception:  # a jar zipfile cannot open: JarPort judged it already
            continue
        if needs > runs_on:
            m["unlinked"] = m["unlinked"] + [f"{m['mod']}: built for a newer Java: Java {needs} classes, Java {runs_on} runs {to}"]
    counts = {}
    for m in report["mods"]:
        for member in left(m):
            counts[member] = counts.get(member, 0) + 1
    report["unlinkedByMods"] = [{"member": k, "mods": v} for k, v in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))]
    report["linked"] = sum(1 for m in report["mods"] if "error" not in m and not m["unlinked"])
    return report


def without_platform_copies(report, provided, jars):
    """JarPort's report without what mods' nested copies of platform modules reference (a nested jar named
    "MOD.jar!INNER.jar: ..."): that copy never runs. Each mod's counts are made again from what is left."""
    if not provided:
        return report
    for m in report["mods"]:
        if "error" in m or not any("!" in u.split(": ", 1)[0] for u in m["unlinked"]):
            continue
        names = {}
        try:
            import zipfile
            with zipfile.ZipFile(jars[m["mod"]]) as z:
                for entry in z.namelist():
                    if entry.startswith("META-INF/jars/") and entry.endswith(".jar"):
                        for i, inner in nested_ids(z.read(entry)).items():
                            names.setdefault(inner or entry.rsplit("/", 1)[1], set()).add(i)
        except (OSError, KeyError, ValueError):
            continue
        m["unlinked"] = [u for u in m["unlinked"]
                         if not any(names.get(part, set()) & provided for part in u.split(": ", 1)[0].split("!")[1:])]
    counts = {}
    for m in report["mods"]:
        for member in left(m):
            counts[member] = counts.get(member, 0) + 1
    report["unlinkedByMods"] = [{"member": k, "mods": v} for k, v in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))]
    report["linked"] = sum(1 for m in report["mods"] if "error" not in m and not m["unlinked"])
    return report


WHERE = re.compile(r" \(in [^)]*\)$")


def left(row, data=None):
    """What one mod still needs: each reference once (a mod and its nested jars name it once a class), and the game ids its data lost."""
    return {WHERE.sub("", u.split(": ", 1)[1]) for u in row.get("unlinked", [])} | {WHERE.sub("", ref) for ref in (data or {}).get(row["mod"], [])}


def nearest(rows, data, said):
    """The to-do list: the mods in the order they finish when every turn completes the one with the fewest references
    left, each with the references that turn adds and what the game's own code did about them. The references the
    most mods share are the wrong order: a mod held back has 15 at the median, so on NeoForge 1.21.1 -> 1.21.2 the
    100 most shared freed 2 mods, and the first 33 in this order freed 21."""
    todo = {m["mod"]: left(m, data) for m in rows}
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
    for ref in sorted(left(row, data)):
        by.setdefault(kind_of(ref, said), []).append(ref)
    return by


def by_type(rows, data, said):
    """Each type of break with the mods that have one, the mods it alone holds back (handled, they are done), and
    its most-used references as examples; the types that finish the most mods first."""
    types = {}
    for m in rows:
        refs = left(m, data)
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
    commit = subprocess.run(["git", "rev-parse", "--short=10", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip() or "unknown"
    return ROOT / "bench" / "exposure" / f"{kind}-{loader}-{frm}-{to}-{commit}.json"


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

    pinned = timed("download", fetch, loader, frm)
    work = ROOT / ".runs" / "exposure" / f"jars-{loader}-{frm}-{to}"
    last = work / "last.json"
    if kind:
        if not last.is_file():
            raise SystemExit(f"--type needs a run of the whole update first: python bench/exposure.py jars --loader {loader} --from {frm} --to {to}")
        held = {mod for mod, v in json.loads(last.read_text(encoding="utf-8")).get("verdicts", {}).items() if any(kind in t for t in v.get("types", []))}
        pinned = [p for p in pinned if p.name in held]
        if not pinned:
            raise SystemExit(f"no mod of the last run is held back by a type of break with '{kind}' in it")
    compile_tools()  # once, before the threads below that run it
    # Reading the jars' names, the game's change maps and the data reports need nothing from each other.
    with ThreadPoolExecutor(3) as pool:
        named = pool.submit(timed, "names", mojang_names, loader, frm, pinned)
        mapped = pool.submit(timed, "map", routes, loader, frm, to)
        checked = pool.submit(timed, "data", data_check, frm, to, pinned)
        (mods, unread), data = named.result(), checked.result()
        mapped.result()
    # A run of some mods only (kind) keeps its report apart: load reads the whole update's.
    written = work / ("report.json" if not kind else "report-type.json")
    report = timed("port", jar_port, loader, frm, to, mods, work / "mods", written, False, True) if mods else {"linked": 0, "mods": [], "unlinkedByMods": []}
    shutil.rmtree(work / "mods", ignore_errors=True)  # only the report is read; load ports the mods it starts itself
    if mods:
        report = without_platform_copies(report, platform_ids(loader, to), {m.name: m for m in mods})
        report = with_hidden_code_unread(report, {m.name: m for m in mods})
        report = with_target_java(report, {m.name: m for m in mods}, to)
        written.write_text(json.dumps(report, indent=1), encoding="utf-8")
    said = timed("game", game_says, loader, frm, to, written) if report["unlinkedByMods"] else {}
    rows = [m for m in report["mods"] if "error" not in m]
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
        "loaderUses": sum(r["mods"] for r in report["unlinkedByMods"] if LOADER_REF.search(r["member"])),
        "modsHeldOnlyByLoader": sum(1 for m in rows if m["unlinked"] and all(LOADER_REF.search(u) for u in m["unlinked"])),
        "unreadable": {**unread, **{m["mod"]: m["error"] for m in report["mods"] if "error" in m}},
        "top": [{**r, **said.get(r["member"], {})} for r in report["unlinkedByMods"][:40]],
        # Where this run's time went: download, names, map and data ran side by side; port and game after them.
        "seconds": seconds,
    }
    order = nearest(rows, data, said)
    summary["types"] = by_type(rows, data, said)
    # Every blocked mod's references by type, so a way to handle several types can be counted by the mods it frees.
    summary["held"] = {m["mod"]: held_by(m, data, said) for m in rows if left(m, data)}
    # Why each blocked mod's mixin handlers did not follow their targets (JarPort.why), for designing the mixin types.
    summary["refused"] = {m["mod"]: m["refused"] for m in rows if left(m, data) and m.get("refused")}
    summary["nearest"] = [{**turn, "members": turn["members"][:25]} for turn in order[:40]]
    summary["finishing"] = [[turn["fixes"], turn["modsDone"]] for turn in order]
    # Each mod's verdict: the references it still needs, the types of break they are, and what its port wrote. Two runs
    # agree on a mod when these are equal; a mod whose port wrote the same jar needs no new proof in game.
    summary["links"] = sorted(m["mod"] for m in rows if not left(m, data))
    summary["verdicts"] = {m["mod"]: {"left": sorted(left(m, data)), "types": sorted({kind_of(r, said) for r in left(m, data)}), "ported": m.get("ported")}
                           for m in rows}
    summary["porter"] = port_key(loader, frm, to) + " " + content_key(*(p.read_bytes() for p in mods))
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
        if VERIFY:
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
    todo = [(lo, a, b) for lo in loaders for a, b in runs(lo) if not only or f"{lo}:{a}" in only]
    jar_tools()
    compile_tools()
    global CORES
    CORES = max(1, (os.cpu_count() or 1) // max(1, jobs))
    out = ROOT / ".runs" / "exposure" / "all"
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
        single = updates(loader)
        for parity in (0, 1):
            wave = [t for t in todo if t[0] == loader and t[1:] in single and single.index(t[1:]) % 2 == parity]
            with ThreadPoolExecutor(max(1, jobs)) as pool:
                list(pool.map(one, wave))
        # A jump reads every release on its way. The updates above fetched them, so jumps side by side write no file
        # twice; one that crosses an update that did not run (--only) goes alone.
        fetched = {v for t in todo if t[0] == loader and t[1:] in single for v in t[1:]}
        far = [t for t in todo if t[0] == loader and t[1:] not in single]
        warm = [t for t in far if set(chain(*t[1:])) <= fetched]
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
    single = {(lo, *u): i for lo in FIRST for i, u in enumerate(updates(lo))}
    wanted = {(lo, *u): i for lo in FIRST for i, u in enumerate(jumps(lo))}
    pair = lambda s: (s["loader"], s["from"], s["to"])
    rows = sorted((s for s in found if pair(s) in single), key=lambda s: (s["loader"], single[pair(s)]))
    far = sorted((s for s in found if pair(s) in wanted), key=lambda s: (s["loader"], wanted[pair(s)]))

    def count(some):
        return {"updates": len(some), "mods": sum(s["modsRead"] for s in some), "link": sum(s["modsThatLink"] for s in some)}

    total = {"mods": sum(s["modsRead"] for s in rows), "link": sum(s["modsThatLink"] for s in rows)}
    # One update at a time is two different jobs: the updates that start a new family of releases, and the rest,
    # across which nearly every mod links untouched.
    total["big"] = count([s for s in rows if big(s["from"], s["to"])])
    total["small"] = count([s for s in rows if not big(s["from"], s["to"])])
    # What people ask for: a port over several updates.
    total["jumps"] = {"jumps": len(far), "mods": sum(s["modsRead"] for s in far), "link": sum(s["modsThatLink"] for s in far)}
    # The jump asked for most: the most-used release's mods on the newest game.
    newest = [s for s in far if s["to"] == updates(s["loader"])[-1][1]]
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
    family = families()
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
    targets = {(lo, *p) for lo in FIRST for p in any_version(lo)}
    missing = [f"{lo} {a} -> {b}" for lo, a, b in dict.fromkeys(list(single) + list(wanted) + sorted(targets))
               if not any(pair(s) == (lo, a, b) for s in found)]
    anyv = any_version_table({pair(s): s for s in found if pair(s) in targets})
    keep = ("loader", "from", "to", "modsRead", "modsThatLink", "modsThatLinkAndData", "brokenUses", "loaderUses", "modsHeldOnlyByLoader", "links")
    table = {
        "date": date.today().isoformat(), "total": total, "loaders": loaders, "missing": missing, "anyVersion": anyv,
        "updates": [{**{k: s.get(k) for k in keep}, "big": big(s["from"], s["to"])} for s in rows],
        "jumps": [{**{k: s.get(k) for k in keep}, "updatesCrossed": len(chain(s["from"], s["to"])) - 1} for s in far],
        "types": kinds,
        "typesNotRead": untyped,
        "nearest": turns[:60],
        "top": top,
    }

    def share(part, whole):
        return f"{part} of {whole} ({part / whole:.1%})" if whole else "0 of 0"

    print(any_version_lines(anyv)[0] + "\n")
    print(f"| Loader | Update | Mods that link | And their data |\n|---|---|---|---|")
    for s in rows:
        data = "-" if s.get("modsThatLinkAndData") is None else s["modsThatLinkAndData"]
        print(f"| {s['loader']} | {s['from']} -> {s['to']}{' (big)' if big(s['from'], s['to']) else ''} | {s['modsThatLink']}/{s['modsRead']} | {data} |")
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
    print("\nAny version:\n" + "\n".join(any_version_lines(anyv)))
    if out:
        Path(out).write_text(json.dumps(table, indent=1) + "\n", encoding="utf-8")
        print(f"saved {out}")
    return table


def any_version_table(by_pair):
    """Any mod, any version: for each loader, the most-used release's mods (the pinned 150) ported to every other release
    it is read for, and the mods whose port links (classes, mixins, access transformers and data) on every one. A target
    whose job did not run, or where a mod could not be read, counts as not reached. For each mod that misses: the releases
    it misses and the types of break holding it there. Then what would free the most mods for every release: each type
    of break and each release, by the mods it alone holds back (handled, they reach every release) and the mods it holds."""
    loaders, held, everywhere = {}, [], {}
    for lo in FIRST:
        frm, targets = most_used(lo), [b for _, b in any_version(lo)]
        here = {t: by_pair.get((lo, frm, t)) for t in targets}
        # Every pinned mod, so a target or a whole loader whose jobs did not run lowers the count instead of leaving it out.
        pinned = PINS / f"{lo}-{frm}.json"
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
                    missing[t] = ["unreadable" if jar in s.get("unreadable", {}) else "not read"]
            mod = jar.split("--", 1)[0]
            reached[mod] = len(targets) - len(missing)
            if missing:
                types = {}
                for kinds in missing.values():
                    for k in kinds:
                        types[k] = types.get(k, 0) + 1
                held.append({"loader": lo, "mod": mod, "reached": reached[mod], "of": len(targets), "missing": list(missing),
                             "types": dict(sorted(types.items(), key=lambda kv: (-kv[1], kv[0])))})
        older = [t for t in targets if vkey(t) < vkey(frm)]
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
            "types": sorted(kinds.values(), key=lambda t: (-t["finishes"], -t["mods"], t["type"])),
            "releases": sorted(releases.values(), key=lambda t: (-t["finishes"], -t["mods"], t["loader"], vkey(t["release"]))),
            "held": held}


def any_version_lines(a, top=10):
    """The any-version count as lines: the headline, then what would free the most mods, then the mods nearest to it."""
    lo = a.get("loaders", {})
    split = "; ".join(f"{k.capitalize()} {t['every']} of {t['mods']} from {t['from']} to all {t['targets']} other releases "
                      f"({t['everyNewer']} reach every newer one, {t['everyOlder']} every older one)" for k, t in lo.items())
    lines = [f"{a.get('every', 0)} of {a.get('mods', 0)} mods go to any version (their ported jar links on every release): {split}"]
    not_run = [f"{k} {r}" for k, t in lo.items() for r in t["notRun"]]
    if not_run:
        lines.append(f"not run, so counted as not reached: {', '.join(not_run)}")
    lines.append("types of break, by the mods each one alone keeps from every release (and the mods and mod-releases it holds):")
    lines += [f"  frees {t['finishes']:>3}, in {t['mods']:>3} mods, {t['releases']:>5} mod-releases  {t['type']}" for t in a.get("types", [])[:top]]
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
           "**" + any_version_lines(a)[0] + "**", ""]
    out += ["## What would free the most mods", "", "Types of break. *Frees* counts the mods it alone keeps from every release: "
            "handle it and they go to any version. *Mods* counts every mod it holds somewhere.", "",
            "| Frees | Mods | Mod-releases | Type of break |", "|---|---|---|---|"]
    out += [f"| {t['finishes']} | {t['mods']} | {t['releases']} | {t['type']} |" for t in a.get("types", [])[:30]]
    out += ["", "Releases. *Frees* counts the mods that miss only this release.", "", "| Frees | Mods missing it | Loader | Release |", "|---|---|---|---|"]
    out += [f"| {t['finishes']} | {t['mods']} | {t['loader']} | {t['release']} |" for t in a.get("releases", [])[:30]]
    out += ["", "## Every mod that misses a release, nearest first", "", "| Loader | Mod | Reaches | Misses | Held by |", "|---|---|---|---|---|"]
    out += [f"| {m['loader']} | {m['mod']} | {m['reached']} of {m['of']} | {', '.join(m['missing'])} | "
            f"{'; '.join(f'{k} ({n})' for k, n in m['types'].items())} |" for m in a.get("held", [])]
    out += ["", "## Mods that reach every release", ""]
    out += [f"- {lo.capitalize()}: {', '.join(mods) or 'none yet'}" for lo, mods in a.get("everywhere", {}).items()]
    return "\n".join(out) + "\n"

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
    sys.path.insert(0, str(ROOT / "bench"))
    import measures as M
    folder = Path(folder)
    folder.mkdir(parents=True, exist_ok=True)
    main_table, points_path = folder / "all.json", folder / "points.json"
    table = json.loads(Path(table_path).read_text(encoding="utf-8"))
    table = {"commit": commit, "run": run, **{k: v for k, v in table.items() if k not in ("commit", "run")}}
    previous = json.loads(main_table.read_text(encoding="utf-8")) if main_table.is_file() else None
    if previous and previous.get("commit") and previous["commit"] != commit and subprocess.run(
            ["git", "merge-base", "--is-ancestor", previous["commit"], commit], cwd=ROOT, capture_output=True).returncode != 0:
        # Sweeps of main can finish out of order: a newer commit's table is already there.
        print(f"main's table already holds {previous['commit'][:10]}, which {commit[:10]} does not follow; not recorded")
        return None
    kept = json.loads(points_path.read_text(encoding="utf-8")) if points_path.is_file() else {}
    subject = subprocess.run(["git", "log", "-1", "--format=%s", commit], cwd=ROOT, capture_output=True, text=True).stdout.strip()
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
    (folder / ANY_VERSION_PAGE).write_text(any_version_page(table), encoding="utf-8", newline="\n")
    print(f"main's table is {at}: " + "; ".join(compare(previous, table)[:HEADS] if previous else [f"{read(table)[0]:,}" for read in MAIN_MEASURES.values()]))
    return table


def fabric_meta(jar):
    """A jar's fabric.mod.json, or None."""
    import zipfile
    with zipfile.ZipFile(jar) as z:
        if "fabric.mod.json" not in z.namelist():
            return None
        try:
            return json.loads(z.read("fabric.mod.json").decode("utf-8", "replace"), strict=False)
        except ValueError:
            return None


def fabric_api(version):
    """The Fabric API players install on a release (a jar in ~/.mmt/mappings/), or None: Exposure.fabricApi chooses it, so
    the game a port is started in runs the jar the link check and the route map read."""
    return fabric_apis([version])[version]


def fabric_apis(versions):
    """fabric_api of each release, read with one Java process."""
    missing = [v for v in dict.fromkeys(versions) if v not in FABRIC_APIS]
    if missing:
        out = subprocess.run([java(), "-Xmx1g", "-cp", jar_tools(), "Exposure", "--fabric-api", ",".join(missing)],
                             capture_output=True, text=True, check=True).stdout
        for line in out.splitlines():
            v, _, path = line.partition("\t")
            FABRIC_APIS[v] = Path(path) if path else None
    return {v: FABRIC_APIS[v] for v in versions}


FABRIC_APIS = {}


# The loaders and the game a mod may ask for, which every pack has.
BUILT_IN = {"minecraft", "java", "neoforge", "forge", "fabricloader", "mixinextras"}
# NeoForge's own language loaders; another (kotlinforforge) comes with a mod.
OWN_LOADERS = {"javafml", "lowcodefml"}
# The service file NeoForge's loader (FML) reads language loaders from. FML 4 (NeoForge 21.0, Minecraft 1.21) replaced
# IModLanguageProvider with IModLanguageLoader, another interface with other methods, which no port rewrites: a loader
# registered only the old way is not there on 1.21 and later, and the reverse. Forge's own service NeoForge never reads.
LANGUAGE_SERVICES = {"old": "META-INF/services/net.neoforged.neoforgespi.language.IModLanguageProvider",
                     "new": "META-INF/services/net.neoforged.neoforgespi.language.IModLanguageLoader"}


def language_services(release):
    """The service files a NeoForge release reads language loaders from; both when the release is not known."""
    if release is None:
        return tuple(LANGUAGE_SERVICES.values())
    # FML 3.0 (NeoForge 20.5 and 20.6) reads IModLanguageLoader, as every later FML does; FML 2.0 (20.2 to 20.4) the provider.
    return (LANGUAGE_SERVICES["new" if vkey(release) >= vkey("1.20.5") else "old"],)


def metadata(z, loader):
    """A jar's own metadata for the loader the pack runs: fabric.mod.json for Fabric, else neoforge.mods.toml or mods.toml.
    A jar made for several loaders carries each; reading another loader's would ask for that loader's mods. None if none."""
    import tomllib
    names = z.namelist()
    try:
        if loader == "fabric":
            return json.loads(z.read("fabric.mod.json").decode("utf-8", "replace"), strict=False) if "fabric.mod.json" in names else None
        entry = next((n for n in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml") if n in names), None)
        return tomllib.loads(z.read(entry).decode("utf-8", "replace")) if entry else None
    except (ValueError, tomllib.TOMLDecodeError):
        return None


def needs(jar, loader):
    """What a mod asks for beyond the game and the loader: mod ids its metadata requires, "adapter:K" for a Fabric language
    adapter its entrypoints use, "loader:K" for a NeoForge language loader that is not NeoForge's own. None when neither the
    jar nor a jar nested in it has metadata for the loader (nothing the loader would start)."""
    import io
    import zipfile
    with zipfile.ZipFile(jar) as z:
        meta = metadata(z, loader)
        if meta is None:
            return set() if provides(jar, loader) else None
    own = provides(jar, loader)
    if loader == "fabric":
        asked = {d for d in (meta.get("depends") or {})}
        asked |= {"adapter:" + e.get("adapter") for es in (meta.get("entrypoints") or {}).values() for e in es
                  if isinstance(e, dict) and e.get("adapter", "default") != "default"}
    else:
        asked = {d.get("modId") for blocks in meta.get("dependencies", {}).values() for d in (blocks if isinstance(blocks, list) else [])
                 if str(d.get("type", "required" if d.get("mandatory", True) else "optional")).lower() == "required"}
        if meta.get("modLoader", "javafml") not in OWN_LOADERS:
            asked.add("loader:" + meta["modLoader"])
    return {a for a in asked if a and a not in BUILT_IN and a not in own}


def constant_name(data):
    """The string a class's name() method returns, when its body is only that constant (ldc, areturn), as javac and kotlinc
    write a NeoForge language loader's name; else None."""
    import struct
    if data[:4] != b"\xca\xfe\xba\xbe":
        return None
    pool, i, at = [None], 1, 10
    count = struct.unpack_from(">H", data, 8)[0]
    while i < count:
        tag = data[at]
        if tag == 1:
            size = struct.unpack_from(">H", data, at + 1)[0]
            pool.append(data[at + 3:at + 3 + size].decode("utf-8", "replace"))
            at += 3 + size
        else:
            pool.append((tag, struct.unpack_from(">H", data, at + 1)[0]) if tag == 8 else None)
            at += {3: 5, 4: 5, 5: 9, 6: 9, 7: 3, 8: 3, 9: 5, 10: 5, 11: 5, 12: 5, 15: 4, 16: 3, 17: 5, 18: 5, 19: 3, 20: 3}[tag]
        if tag in (5, 6):  # a long or a double takes two slots
            pool.append(None)
            i += 1
        i += 1
    at += 6
    at += 2 + 2 * struct.unpack_from(">H", data, at)[0]

    def members(at, look):
        found, count = None, struct.unpack_from(">H", data, at)[0]
        at += 2
        for _ in range(count):
            name, desc = (pool[k] for k in struct.unpack_from(">HH", data, at + 2))
            attrs = struct.unpack_from(">H", data, at + 6)[0]
            at += 8
            for _ in range(attrs):
                kind, size = pool[struct.unpack_from(">H", data, at)[0]], struct.unpack_from(">I", data, at + 2)[0]
                if look and kind == "Code" and name == "name" and desc == "()Ljava/lang/String;":
                    code = data[at + 14:at + 14 + struct.unpack_from(">I", data, at + 10)[0]]
                    index = code[1] if code[:1] == b"\x12" and code[2:3] == b"\xb0" else \
                        struct.unpack_from(">H", code, 1)[0] if code[:1] == b"\x13" and code[3:4] == b"\xb0" else None
                    entry = pool[index] if index is not None and index < len(pool) else None
                    found = pool[entry[1]] if isinstance(entry, tuple) else None
                at += 6 + size
        return at, found

    at, _ = members(at, False)
    return members(at, True)[1]


def language_loaders(z, release=None):
    """The names of the NeoForge language loaders a jar (an open ZipFile) registers on a release: "loader:NAME" for each
    class the service file that release reads lists whose name() is a constant, "loader:*" for one whose name only running
    it would tell."""
    out = set()
    for service in (n for n in language_services(release) if n in z.namelist()):
        for line in z.read(service).decode("utf-8", "replace").splitlines():
            cls = line.split("#")[0].strip()
            if not cls:
                continue
            path = cls.replace(".", "/") + ".class"
            name = constant_name(z.read(path)) if path in z.namelist() else None
            out.add("loader:" + (name or "*"))
    return out


def provides(jar, loader, release=None):
    """What a jar gives the mods beside it: its mod ids and those of the jars nested in it, "adapter:K" for each Fabric
    language adapter, and "loader:NAME" for each NeoForge language loader it or a nested jar registers on the release
    (language_loaders)."""
    import io
    import zipfile

    def read(z):
        out = set()
        meta = metadata(z, loader)
        if meta is not None and loader == "fabric":
            out |= {meta.get("id"), *(meta.get("provides") or []), *("adapter:" + k for k in (meta.get("languageAdapters") or {}))}
        elif meta is not None:
            out |= {m.get("modId") for m in meta.get("mods", [])}
        if loader != "fabric":
            out |= language_loaders(z, release)
        for entry in z.namelist():
            if entry.endswith(".jar") and entry.startswith(("META-INF/jars/", "META-INF/jarjar/")):
                try:
                    out |= read(zipfile.ZipFile(io.BytesIO(z.read(entry))))
                except zipfile.BadZipFile:
                    pass
        return out

    with zipfile.ZipFile(jar) as z:
        return {i for i in read(z) if i}


def own_ids(jar, loader):
    """The mod ids a jar's own metadata declares (its id and what it says it provides), without the jars nested in it."""
    import zipfile
    with zipfile.ZipFile(jar) as z:
        meta = metadata(z, loader)
    if meta is None:
        return set()
    if loader == "fabric":
        return {i for i in (meta.get("id"), *(meta.get("provides") or [])) if i}
    return {m.get("modId") for m in meta.get("mods", []) if m.get("modId")}


def unmet(asked, provided):
    """What of a mod's needs nothing provided answers. A language loader is answered by the jar that registers it under the
    name the mod's modLoader asks for (Kotlin for Forge's is kotlinforforge, KotlinLangForge's klf), or by one whose name
    only running it would tell."""
    return {a for a in asked if a not in provided and not (a.startswith("loader:") and ("loader:*" in provided or a[7:] in provided))}


def version_info(version_id):
    """One Modrinth version (its files and dependencies), kept for good: a version never changes."""
    cache = HOME / "modrinth" / f"version-{version_id}.json"
    if cache.is_file():
        return json.loads(cache.read_text(encoding="utf-8"))
    info = api(f"version/{version_id}")
    cache.parent.mkdir(parents=True, exist_ok=True)
    part = part_of(cache)
    part.write_text(json.dumps(info), encoding="utf-8")
    publish(part, cache)
    return info


def required_projects(version_id):
    """The Modrinth projects a version lists as required: what its author says players must install with it."""
    return [d["project_id"] for d in version_info(version_id).get("dependencies") or []
            if d.get("dependency_type") == "required" and d.get("project_id")]


def library(project, loader, version):
    """A library mod's newest file for a loader and release, downloaded once into ~/.mmt/exposure/libraries/, with the
    version it came from; None when the project has no file for that release."""
    for v in project_versions(project):
        if loader not in v["loaders"] or version not in v["game"]:
            continue
        f = next((f for f in v["files"] if f["primary"] and f["file"].endswith(".jar")), None) or next((f for f in v["files"] if f["file"].endswith(".jar")), None)
        if not f:
            continue
        target = HOME / "libraries" / f"{loader}-{version}" / f"{project}--{f['file']}"
        if not target.is_file():
            target.parent.mkdir(parents=True, exist_ok=True)
            with urllib.request.urlopen(urllib.request.Request(f["url"], headers=AGENT), timeout=300) as r:
                data = r.read()
            if f.get("sha1") and hashlib.sha1(data).hexdigest() != f["sha1"]:
                raise SystemExit(f"{f['url']}: the download is not the file Modrinth lists")
            part = part_of(target)
            part.write_bytes(data)
            publish(part, target)
        return target, v["id"]
    return None


def ranges(jar, loader):
    """The version ranges a NeoForge mod's metadata asks of each mod it requires ({mod id: "[21.1,21.2)"}); none for Fabric."""
    import zipfile
    if loader == "fabric":
        return {}
    with zipfile.ZipFile(jar) as z:
        meta = metadata(z, loader) or {}
    return {d.get("modId"): d.get("versionRange") for blocks in meta.get("dependencies", {}).values() for d in (blocks if isinstance(blocks, list) else [])
            if d.get("versionRange") and str(d.get("type", "required" if d.get("mandatory", True) else "optional")).lower() == "required"}


def versions(jar, loader):
    """{mod id: version} a jar declares, its manifest's Implementation-Version where the metadata leaves it to the build."""
    import zipfile
    if loader == "fabric":
        return {}
    with zipfile.ZipFile(jar) as z:
        meta = metadata(z, loader) or {}
        manifest = z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace") if "META-INF/MANIFEST.MF" in z.namelist() else ""
    built = next((line.split(":", 1)[1].strip() for line in manifest.splitlines() if line.startswith("Implementation-Version:")), None)
    return {m.get("modId"): (built if str(m.get("version", "")).startswith("$") else m.get("version")) for m in meta.get("mods", [])}


def in_range(version, spec):
    """Whether a version is inside a Maven range as NeoForge reads one ("[21.1,21.2)", "[1.0,)", "1.2" for at least 1.2)."""
    def key(v):
        return [int(p) if p.isdigit() else p for p in re.split(r"[.\-+]", str(v)) if p]

    def cmp(x, y):
        # As Maven orders them: a qualifier (beta, rc) comes before any number, so 0.6.0-beta.5 is below 0.6.0.
        a, b = key(x), key(y)
        for p, q in zip(a, b):
            if p != q:
                return (p > q) - (p < q) if type(p) is type(q) else (isinstance(q, str)) - (isinstance(p, str))
        rest = a[len(b):] or b[len(a):]
        if not rest or all(r == 0 for r in rest):
            return 0
        longer = 1 if len(a) > len(b) else -1
        return -longer if isinstance(rest[0], str) else longer

    if not version or not spec or spec.strip() == "*":
        return True
    for part in re.findall(r"[\[(][^\])]*[\])]|[^,\s]+", spec):
        if part[0] not in "[(":
            if cmp(version, part) >= 0:
                return True
            continue
        low, _, high = part[1:-1].partition(",") if "," in part else (part[1:-1], None, part[1:-1])
        ok_low = not low.strip() or (cmp(version, low.strip()) >= 0 if part[0] == "[" else cmp(version, low.strip()) > 0)
        ok_high = high is None or not high.strip() or (cmp(version, high.strip()) <= 0 if part[-1] == "]" else cmp(version, high.strip()) < 0)
        if ok_low and ok_high:
            return True
    return False


def pinned_ids(loader, frm, pins):
    """{mod id: [Modrinth project]} for the old release's pinned jars on disk, by each jar's own metadata (its id and what
    it says it provides; not the jars nested in it, which would name every mod that bundles a library)."""
    folder = HOME / "jars" / f"{loader}-{frm}"
    out = {}
    for m in pins:
        jar = folder / f"{m['slug']}--{m['file']}"
        if jar.is_file():
            for i in own_ids(jar, loader):
                out.setdefault(i, []).append(m["project"])
    return out


def projects_providing(mod_id, loader, frm, to, pins, index=None):
    """The Modrinth projects that may be the mod a metadata id names, most likely first: the old release's pinned mods
    that declare the id, then Modrinth's search for the id's words for either release (yet_another_config_lib_v3 is
    "yet another config lib": a trailing version is not in a project's name). Only candidates: choose takes one only when
    its file declares the id."""
    index = pinned_ids(loader, frm, pins) if index is None else index
    yield from index.get(mod_id, [])
    words = [w for w in re.split(r"[^a-z0-9]+", mod_id.lower()) if w]
    if len(words) > 1 and re.fullmatch(r"v?\d+", words[-1]):
        words.pop()
    facets = [[f"categories:{loader}"], [f"versions:{to}", f"versions:{frm}"], ["project_type:mod"]]
    for hit in api("search", {"query": " ".join(words), "facets": json.dumps(facets), "limit": 5}).get("hits", []):
        if hit["project_id"] not in index.get(mod_id, []):
            yield hit["project_id"]


def choose(loader, frm, to, linked, folder, pins, platform):
    """The linked mods to start, and the library mods they need. A mod starts when what it needs is provided: by the
    platform, by another chosen mod, or by a library. A library is a project the mod's Modrinth version lists as
    required (and each library's own, in turn), or else the project whose file declares a mod id the mod's metadata
    requires (projects_providing): its release for the new version when there is one, which is what players would
    install; else its release for the old one, ported with the mods. Returns (chosen jars, libraries for the new
    release, libraries to port, {mod: what it still needs} for the mods left out)."""
    provided, chosen, ready, to_port, asked_for = set(platform), [], {}, {}, {}
    looked = set()
    own = set(platform)
    candidates = [folder / n for n in linked if (folder / n).is_file()]
    unread = {j.name: [f"metadata for {loader}"] for j in candidates if needs(j, loader) is None}
    unread |= {n: [f"it is not started: its jar is not in {folder}"] for n in linked if not (folder / n).is_file()}
    # A top mod that is the platform itself (Fabric API) is not started twice. Only the jar's own ids say so: a mod that
    # nests a copy of one platform module (CraftPresence and MaLiLib carry fabric-resource-loader-v0) is a mod, and the
    # loader keeps the platform's newer copy of that module.
    unread |= {j.name: ["it is not started twice: it is the platform itself, which the pack runs for the new release"]
               for j in candidates if j.name not in unread and own_ids(j, loader) & own - {"loader:*"}}
    candidates = [j for j in candidates if j.name not in unread]
    by_jar = {folder / f"{m['slug']}--{m['file']}": m for m in pins}
    searched, index = set(), None

    def pick(project, waiting):
        """The file of a library project players would install: its release for the new version when that fits every
        range the waiting mods ask, else its old release, to port; None when it has neither, is a top mod of the old
        release (started, or not, on its own merits) or is the platform (a second Fabric API stops Fabric)."""
        if any(m["project"] == project and folder / f"{m['slug']}--{m['file']}" in candidates for m in pins):
            return None
        got, where = library(project, loader, to), ready
        asked_range = {}
        for w in waiting:
            for mid, r in ranges(w, loader).items():
                asked_range.setdefault(mid, []).append(r)
        if got and any(not in_range(v, r) for mid, v in versions(got[0], loader).items() for r in asked_range.get(mid, [])):
            got = None
        if not got:
            got, where = library(project, loader, frm), to_port
        if not got or provides(got[0], loader) & own - {"loader:*"}:
            return None
        return got, where

    def add(project, got, where):
        """Puts a library in the pack, then the libraries its Modrinth version lists, in turn."""
        todo = [(got, where)]
        asked_for[project] = got
        while todo:
            (jar, version), where = todo.pop()
            where[jar.name] = jar
            provided.update(provides(jar, loader, to))
            for p in required_projects(version):
                if p not in asked_for:
                    picked = pick(p, waiting)
                    asked_for[p] = picked[0] if picked else None
                    if picked:
                        todo.append(picked)

    while True:
        more = [j for j in candidates if j not in chosen and not unmet(needs(j, loader), provided)]
        for j in more:
            chosen.append(j)
            provided |= provides(j, loader, to)
        if more:
            continue
        waiting = [j for j in candidates if j not in chosen]
        found = False
        # What each mod's Modrinth version lists as required, which is what its author says players install.
        for j in waiting:
            listed = required_projects(by_jar[j]["version"]) if j in by_jar and j not in looked else []
            looked.add(j)
            for project in listed:
                if project not in asked_for:
                    picked = pick(project, waiting)
                    if picked:
                        add(project, *picked)
                        found = True
                    else:
                        asked_for[project] = None
        if found:
            continue
        # A mod id the metadata of a waiting mod (or of a library) requires that no listed project gave: authors leave
        # libraries off their Modrinth version (Sounds requires YACL's yet_another_config_lib_v3 and lists nothing). A
        # project found for the id is taken only when the file it would install declares that id.
        for j in waiting + list(ready.values()) + list(to_port.values()):
            for mod_id in sorted(unmet(needs(j, loader) or set(), provided) - searched):
                if ":" in mod_id:
                    continue  # a language loader or adapter comes with the library mod a mod requires by id
                searched.add(mod_id)
                index = pinned_ids(loader, frm, pins) if index is None else index
                for project in projects_providing(mod_id, loader, frm, to, pins, index):
                    picked = None if project in asked_for else pick(project, waiting)
                    if picked and mod_id in provides(picked[0][0], loader, to):
                        add(project, *picked)
                        found = True
                        break
        if not found:
            break
    left = {**unread, **{j.name: sorted(unmet(needs(j, loader), provided)) for j in candidates if j not in chosen}}
    return chosen, ready, to_port, left


def mod_ids(jar):
    """The mod ids a NeoForge, Forge or Fabric jar declares, in any loader's metadata it carries, and those of the jars nested
    in it (META-INF/jars/, META-INF/jarjar/): a log names a nested mod, and the jar that ships it is the one to drop."""
    import io
    import zipfile
    with zipfile.ZipFile(jar if isinstance(jar, Path) else io.BytesIO(jar)) as z:
        fabric, toml = metadata(z, "fabric"), metadata(z, "neoforge")
        nested = [z.read(n) for n in z.namelist() if n.startswith("META-INF/") and n.endswith(".jar")]
    own = ({fabric.get("id"), *(fabric.get("provides") or [])} if fabric else set()) | ({m.get("modId") for m in toml.get("mods", [])} if toml else set())
    return own.union(*(mod_ids(data) for data in nested))


def has_code(jar):
    """Whether any class of the jar (nested jars aside) names the game."""
    import zipfile
    with zipfile.ZipFile(jar) as z:
        return any(n.endswith(".class") and b"net/minecraft/" in z.read(n) for n in z.namelist())


def client_starts(loader, to, server_pack, names, platform=(), kept=frozenset(), run=None):
    """The same ported jars in a real client of the new release (mmt pack-check --side client): the title screen, a world, the
    inventory and every creative tab. A start that fails drops the jar its log names and starts again; each mod ends loaded or
    failed (with why). A server never runs client code, so this is where rendering and screen code is proven. The platform
    (Fabric API) goes in as it did on the server and is not counted; kept names the jars of it that no failure drops.
    run: a stand-in for one pack-check of the client pack folder (tests), returning its JSON."""
    pack = server_pack.with_name(server_pack.name + "-client")
    if pack.exists():
        shutil.rmtree(pack)
    (pack / "mods").mkdir(parents=True)
    (pack / "dropped").mkdir()
    for name in list(names) + list(platform):
        # A mod the server left out as client-only may be in dropped/; a library there failed and stays out.
        source = next((d / name for d in (server_pack / "mods", server_pack / "dropped")[:1 if name in platform else 2] if (d / name).is_file()), None)
        if source:
            shutil.copy2(source, pack / "mods" / name)
    mmt = str(ROOT / ("mmt.cmd" if os.name == "nt" else "mmt"))
    verdicts = {}

    def start():
        if run:
            return run(pack), subprocess.CompletedProcess([], 1, "", "")
        done = subprocess.run([mmt, "pack-check", "--pack", str(pack), "--minecraft", to, "--loader", loader, "--side", "client", "--json"],
                              capture_output=True, text=True, encoding="utf-8", errors="replace")
        at = done.stdout.find("{")
        return json.loads(done.stdout[at:]) if at >= 0 else {}, done

    # Each failed start takes at least one jar out (a mod or a library), or ends the client proof with why, so the starts
    # end; their number is not budgeted by the mods, which libraries failing would use up before every mod had a verdict.
    while True:
        result, done = start()
        res = result.get("result", {})
        present = sorted(p.name for p in (pack / "mods").glob("*.jar") if p.name not in platform)
        if res.get("client", {}).get("opened"):
            for name in present:
                verdicts.setdefault(name, "loaded")
            break
        # The libraries can be what fails too; the platform (kept: Fabric API, whose own id was "fabric" before 1.18.2)
        # cannot be left out.
        libraries = [n for n in platform if (pack / "mods" / n).is_file() and n not in kept]
        found = {}
        for finding in res.get("findings", []):
            if finding.get("severity") != "problem" or finding.get("kind") != "client-start":
                continue
            for s in finding.get("suspects", []):
                top = s.get("jar", "").split("!")[0]
                if top not in present + libraries:
                    top = next((n for n in present + libraries if s.get("mod") in mod_ids(pack / "mods" / n)), "")
                if top:
                    found.setdefault(top, "; ".join(s.get("why", []))[:400] + " | " + str(res.get("client", {}).get("failure", ""))[:300])
        if not found:
            found = {n: "the client log names it: " + why for n, why in named_in_errors(result.get("receipt"), pack / "mods", present + libraries, "*client*.log").items()}
        failure = str(res.get("client", {}).get("failure") or res.get("error") or done.stdout[-300:])[:400]
        if not found and not res.get("client", {}).get("toolError"):
            found = first_failing(pack, present + libraries, loader, start, failure)
        for library in [n for n in found if n in libraries]:
            gives = provides(pack / "mods" / library, loader, to)
            (pack / "mods" / library).rename(pack / "dropped" / library)
            platform = [n for n in platform if n != library]
            print(f"  {library}: the library fails in the client")
            for name in present:
                asked = needs(pack / "mods" / name, loader) or set()
                if asked & gives:
                    found.setdefault(name, f"needs the library {library}, which fails in the client: " + found[library][:300])
        blamed = {n: why for n, why in found.items() if n in present}
        if not blamed and any(n in libraries for n in found):
            continue  # only a library failed: the next start runs without it
        if not blamed:
            # The client itself could not be driven (no mod set makes it fail or pass, or mmt's own step does not fit this
            # release): the server's verdicts stand, and the client is reported as not run, with why.
            print(res.get("details", done.stdout[-2000:]))
            print(f"a client start failed and named no mod; the client is not run: {failure}")
            return {"mods": len(names), "loaded": 0, "verdicts": {}, "notRun": failure, "receipt": result.get("receipt")}
        java = built_for_newer_java(res)
        for name, why in blamed.items():
            verdicts[name] = "failed: " + (java[name] + "; " if name in java else "") + why
            (pack / "mods" / name).rename(pack / "dropped" / name)
            print(f"  {name}: failed in the client")
    # As on the server, a dropped mod gets one more start with the mods that loaded: a log can name a bystander.
    for name in sorted(n for n, v in verdicts.items() if v.startswith("failed")):
        (pack / "dropped" / name).rename(pack / "mods" / name)
        again, _ = start()
        if again.get("result", {}).get("client", {}).get("opened"):
            verdicts[name] = "loaded"
            print(f"  {name}: loads in the client with the others on its own start")
        else:
            (pack / "mods" / name).rename(pack / "dropped" / name)
    for name in names:
        verdicts.setdefault(name, "not started: its jar is in neither the server's pack nor what it dropped")
    loaded = sorted(n for n, v in verdicts.items() if v == "loaded")
    return {"mods": len(names), "loaded": len(loaded), "verdicts": verdicts}


def with_needs(chosen, asks, gives):
    """chosen and every jar that provides what they need, and what those need in turn: asks and gives map each jar to
    what it needs and provides."""
    out = list(chosen)
    while True:
        asked = set().union(*(asks[n] for n in out))
        more = [n for n in gives if n not in out and gives[n] & asked]
        if not more:
            return out
        out += more


def client_opened(result):
    return bool(result.get("result", {}).get("client", {}).get("opened"))


def server_opened(result):
    return bool(result.get("ok"))


def first_failing(pack, present, loader, start, failure, opened=client_opened):
    """When a start fails and nothing names a mod (a client sent back to the disconnected screen with no reason, a server
    whose new world does not open), the jar whose arrival makes it fail: the shortest run of present, each with the jars it needs, whose start fails, found by
    halving (about log2 of the mods in starts). Returns {jar: why}, or {} when the client fails with no mod at all: the
    fault is not a mod's."""
    mods, held = pack / "mods", pack / "held"
    held.mkdir(exist_ok=True)
    asks = {n: needs(mods / n, loader) or set() for n in present}
    gives = {n: provides(mods / n, loader) for n in present}

    def fails(count):
        keep = set(with_needs(present[:count], asks, gives))
        for n in present:
            if n not in keep and (mods / n).is_file():
                (mods / n).rename(held / n)
            elif n in keep and (held / n).is_file():
                (held / n).rename(mods / n)
        result, _ = start()
        return not opened(result)

    low, high = 0, len(present)
    try:
        if fails(0):
            return {}
        while high - low > 1:
            middle = (low + high) // 2
            if fails(middle):
                high = middle
            else:
                low = middle
    finally:
        for n in present:
            if (held / n).is_file():
                (held / n).rename(mods / n)
    culprit = present[high - 1]
    print(f"  {culprit}: the game starts with the jars before it and fails once it is added")
    return {culprit: f"the game starts without it and fails with it (named by halving; the start alone said: {failure[:300]})"}


def starts_alone(loader, to, pack, name, platform):
    """Whether a dropped mod starts a real server of the release on its own, with the platform the pack has."""
    alone = pack / "alone"
    shutil.rmtree(alone, ignore_errors=True)
    (alone / "mods").mkdir(parents=True)
    shutil.copy(pack / "dropped" / name, alone / "mods" / name)
    for jar in platform:
        if (pack / "mods" / jar).is_file():  # a library that failed was dropped with the mods that need it
            shutil.copy(pack / "mods" / jar, alone / "mods" / jar)
    mmt = str(ROOT / ("mmt.cmd" if os.name == "nt" else "mmt"))
    done = subprocess.run([mmt, "pack-check", "--pack", str(alone), "--minecraft", to, "--loader", loader, "--json"],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")
    start = done.stdout.find("{")
    return bool(start >= 0 and json.loads(done.stdout[start:]).get("ok"))


def display_name(jar):
    """The name a mod shows players (fabric.mod.json "name", or the first [[mods]] displayName), or None."""
    import tomllib
    import zipfile
    meta = fabric_meta(jar)
    if meta is not None:
        return meta.get("name")
    with zipfile.ZipFile(jar) as z:
        for entry in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
            if entry in z.namelist():
                try:
                    mods = tomllib.loads(z.read(entry).decode("utf-8", "replace")).get("mods", [])
                except tomllib.TOMLDecodeError:
                    return None
                return mods[0].get("displayName") if mods else None
    return None


def named_in_errors(receipt, mods, present, logs="*server*.log"):
    """The jars a failed start's errors name by the name a mod shows players ("Architectury API is a required dependency
    of Roughly Enough Items 14.0.680 but it is not installed!"), when the start's own findings name none: from the lines
    after the loader's "Found N error(s)" in the server log beside the receipt."""
    if not receipt:
        return {}
    errors, packs, mixins = [], [], []
    for log in sorted(Path(receipt).parent.glob(logs)):
        lines = log.read_text(encoding="utf-8", errors="replace").splitlines()
        at = next((i for i, line in enumerate(lines) if re.search(r"Found \d+ error\(s\)", line)), None)
        if at is not None:
            errors += [line for line in lines[at + 1:at + 20] if line.startswith("- ")]
        # A data file the release refuses names the data pack it came from, which a mod's own pack is named for.
        packs += [m.group(0) for line in lines for m in [re.search(r"Failed to parse \S+ from pack (\S+)", line)] if m]
        # A mixin that fails to apply names the mod whose config it is, in the exception that stopped the start ("Caused by:
        # ...MixinPreProcessorException: Attach error for x.mixins.json:Y from mod betterstats ..."); a warning about a
        # client class a server lacks, or a stack frame, is not that.
        mixins += [m for line in lines if re.match(r"(Caused by: )?[\w.$]+(Exception|Error)\b", line)
                   for m in [re.search(r"\b(\S+\.json:\S+) from mod ([\w.-]+)", line)] if m]
    blamed = {}
    for line in packs:
        pack_id = line.rsplit(" ", 1)[1]
        for name in present:
            if pack_id in mod_ids(mods / name):
                blamed[name] = "its data fails on this release: " + line[:300]
    for m in mixins:
        for name in present:
            if m.group(2) in mod_ids(mods / name):
                blamed.setdefault(name, f"its mixin {m.group(1)} fails to apply: " + m.string.strip()[:300])
    for line in errors:
        # The mod whose start the error is about: the one the line says needs something ("... of NAME VERSION ...").
        for name in present:
            shown = display_name(mods / name)
            if shown and re.search(r"\bof " + re.escape(shown) + r"\b", line):
                blamed[name] = "the loader names it: " + line[2:300]
    return blamed


def not_loaded(res):
    """{jar: why} for the jars pack-check says the loader skips (metadata of another loader or release: a 1.21.1 NeoForge
    jar ported back to 1.20.2 with only META-INF/neoforge.mods.toml). The game starts without them, so a start that opens
    a world says nothing about them."""
    return {j: f.get("summary", "")[:300] for f in res.get("findings", []) if f.get("kind") == "loader-mismatch" for j in f.get("jars", [])}


def built_for_newer_java(res):
    """{jar: why} for the jars pack-check's static warning says are built for a newer Java than the release runs on. It
    reads each jar's newest class, which the game may never load, so it is no evidence of a failure: it only says so
    plainly beside a jar something else blamed."""
    return {s["jar"]: "; ".join(s.get("why", [])) for f in res.get("findings", []) if f.get("kind") == "java"
            for s in f.get("suspects", []) if s.get("jar")}


def only_mods(linked, pins, wanted):
    """The linked jars of the wanted mods (Modrinth slugs), and {name: [why]} for the wanted ones left out: one that is
    pinned and does not link, as the whole run leaves it, and one this release does not pin (a proof of several updates
    asks each for the same slugs; load_plan refuses a slug no update pins)."""
    pinned = {m["slug"]: f"{m['slug']}--{m['file']}" for m in pins}
    skipped = {s: ["it is not among this release's pinned mods (bench/exposure/mods/)"] for s in sorted(set(wanted) - set(pinned))}
    for slug in skipped:
        print(f"  {slug}: is not among this release's pinned mods, so it is not started")
    skipped |= {pinned[s]: ["it does not link after the port"] for s in sorted(wanted) if s in pinned and pinned[s] not in linked}
    for name in [n for n in skipped if n not in wanted]:
        print(f"  {name}: does not link after the port, so it is not started")
    return [name for name in linked if name in {pinned[s] for s in wanted if s in pinned}], skipped


def load_plan(spec, mods=None):
    """The updates a game proof runs, as LOADER:FROM:TO: each item of spec is LOADER:FROM:TO, or LOADER:FROM for the next
    release the scoreboard reads for that loader. mods (Modrinth slugs) a proof asks of every update: a slug no update
    pins is a mistake, and stops the plan before any job starts; one only some pin is not started in the others."""
    out = []
    for item in [x.strip() for x in spec.split(",") if x.strip()]:
        parts = item.split(":")
        if parts[0] not in FIRST or len(parts) not in (2, 3):
            raise SystemExit(f"{item}: an update is LOADER:FROM:TO or LOADER:FROM, LOADER one of {', '.join(sorted(FIRST))}")
        if len(parts) == 2:
            nxt = dict(updates(parts[0])).get(parts[1])
            if not nxt:
                raise SystemExit(f"{item}: {parts[1]} is not a release the {parts[0]} scoreboard reads")
            parts.append(nxt)
        out.append(":".join(parts))
    pinned = set()
    for update in out:
        lo, frm, _ = update.split(":")
        pins = PINS / f"{lo}-{frm}.json"
        pinned |= {m["slug"] for m in json.loads(pins.read_text(encoding="utf-8"))["mods"]} if pins.is_file() else set()
    unknown = sorted(set(mods or ()) - pinned)
    if unknown:
        raise SystemExit(f"no update of {spec} pins {', '.join(unknown)} (bench/exposure/mods/LOADER-FROM.json): the proof would start no game for it")
    return out


def load(loader, frm, to, save, mods=None, out=None):
    """The mods the jar scoreboard says link, ported as jars and started on a real server of the new release with the library
    mods they need (choose), then in a real client of it (client_starts), with both verdicts side by side. mods: only these
    (Modrinth slugs), with the libraries they need.

    One start holds them all; a start that fails drops the jar its log names and starts again, until a start opens a world,
    places every block item and opens it again. Each mod ends loaded, failed (with why) or left out as client-only."""
    report = ROOT / ".runs" / "exposure" / f"jars-{loader}-{frm}-{to}" / "report.json"
    if not report.is_file():
        jars(loader, frm, to)
    linked = [m["mod"] for m in json.loads(report.read_text(encoding="utf-8"))["mods"] if "error" not in m and not m["unlinked"]]
    skipped = {}
    if mods:
        linked, skipped = only_mods(linked, json.loads((PINS / f"{loader}-{frm}.json").read_text(encoding="utf-8"))["mods"], mods)
    folder = HOME / "jars" / f"{loader}-{frm}"
    # A Fabric mod that needs Fabric API needs nothing beyond the platform the pack starts with. A linked mod that is the
    # platform itself (Fabric API among the top mods) is not started twice: the loader refuses two jars with one mod id,
    # and the platform's copy for the new release is the one players run. Other mods start with the mods and libraries
    # they need.
    pins = json.loads((PINS / f"{loader}-{frm}.json").read_text(encoding="utf-8"))["mods"]
    pack = ROOT / ".runs" / "exposure" / f"load-{loader}-{frm}-{to}"
    if pack.exists():
        shutil.rmtree(pack)
    # What a mod needs is read from its ported jar, the one the game is given: the port renames the platform modules it
    # asks for (Fabric API's 26.1 renames) and its game range, which its jar as it came still names the old way.
    # A Fabric jar is ported in Mojang's names (read as jars does) and handed back in the names Fabric runs.
    read, _ = mojang_names(loader, frm, [folder / n for n in linked if (folder / n).is_file()])
    ported = pack / "ported"
    report = jar_port(loader, frm, to, read, ported, pack / "report-mods.json", runtime=loader == "fabric") if read else {"mods": []}
    chosen, ready, to_port, left = choose(loader, frm, to, linked, ported, pins, set(platform_ids(loader, to)))
    for name, missing in sorted(left.items()):
        if missing[0].startswith("it is not started"):
            print(f"  {name}: {missing[0][3:]}")
        else:
            print(f"  {name}: not started, it needs {', '.join(missing)}, which neither a top mod nor a library (listed on its Modrinth version, or found for the id) provides")
    left = {**skipped, **left}
    # What the run was asked to prove: every mod that links, or these slugs; compare_loads reads a mod main started and
    # this run did not as a regression only inside it.
    scope = sorted(mods) if mods else "all"
    if not chosen:
        # Nothing to start (no asked mod links, or none has what it needs): the receipt says why each mod was not started,
        # and the run fails, so a proof that started no game never passes for one that did.
        why = "no mod to start: " + ("; ".join(f"{n}: {', '.join(w)}" for n, w in sorted(left.items())) or "none links")
        summary = {"loader": loader, "from": frm, "to": to, "scope": scope, "mods": 0, "loaded": 0, "linkedAndLoaded": 0, "linked": 0,
                   "failedButLinked": [], "clashOnly": [], "failedDataOnly": [], "verdicts": {}, "libraries": {}, "notStarted": left,
                   "client": {"mods": 0, "loaded": 0, "verdicts": {}}, "loadedOnServerFailedInClient": []}
        keep_load(summary, out, save)
        raise SystemExit(why)
    (pack / "mods").mkdir()
    for jar in chosen:
        shutil.move(jar, pack / "mods" / jar.name)
    rows = [m for m in report["mods"] if m["mod"] in {j.name for j in chosen}]
    if to_port:
        read, _ = mojang_names(loader, frm, list(to_port.values()))
        rows = rows + jar_port(loader, frm, to, read, pack / "mods", pack / "report-libraries.json", runtime=loader == "fabric")["mods"]
    links = {m["mod"]: not m.get("unlinked") and "error" not in m for m in rows}
    (pack / "dropped").mkdir()
    # The libraries are in the pack as the platform is, and not counted: the mods are the top mods that link.
    for name, jar in ready.items():
        shutil.copy(jar, pack / "mods" / name)
    platform = sorted(ready) + sorted(to_port)
    libraries = {**{n: f"its release for {to}" for n in ready}, **{n: f"its {frm} release, ported" for n in to_port}}
    if loader == "fabric":
        # Fabric API is the platform nearly every Fabric pack has, as NeoForge's own classes are to a NeoForge mod: mods
        # that call it optionally, or without listing it, start as they would for players. It is not counted.
        api_jar = fabric_api(to)
        if api_jar:
            shutil.copy(api_jar, pack / "mods" / api_jar.name)
            platform.append(api_jar.name)
    mmt = str(ROOT / ("mmt.cmd" if os.name == "nt" else "mmt"))
    verdicts = {}

    def start_server():
        done = subprocess.run([mmt, "pack-check", "--pack", str(pack), "--minecraft", to, "--loader", loader, "--json"],
                              capture_output=True, text=True, encoding="utf-8", errors="replace")
        at = done.stdout.find("{")
        return json.loads(done.stdout[at:]) if at >= 0 else {}

    # Each failed start takes at least one jar out or stops the proof with why, so the starts end without a budget.
    while True:
        done = subprocess.run([mmt, "pack-check", "--pack", str(pack), "--minecraft", to, "--loader", loader, "--json"],
                              capture_output=True, text=True, encoding="utf-8", errors="replace")
        text = done.stdout[done.stdout.find("{"):]
        result = json.loads(text) if text else {}
        res = result.get("result", {})
        present = sorted(p.name for p in (pack / "mods").glob("*.jar") if p.name not in platform)
        for name, why in (res.get("game", {}).get("leftOut") or {}).items():
            verdicts[name] = "client-only: " + str(why)[:200]
        if result.get("ok"):
            skipped = not_loaded(res)
            for name in present:
                if name in skipped:
                    verdicts[name] = "failed: " + skipped[name]
                    (pack / "mods" / name).rename(pack / "dropped" / name)
                    print(f"  {name}: failed, the loader did not load it")
                verdicts.setdefault(name, "loaded")
            break
        in_pack = [n for n in libraries if (pack / "mods" / n).is_file()]
        culprits = {}
        for finding in res.get("findings", []):
            if finding.get("severity") != "problem":
                continue
            for s in finding.get("suspects", []):
                top = s.get("jar", "").split("!")[0] or next((n for n in present + in_pack if s.get("mod") in mod_ids(pack / "mods" / n)), "")
                if top in present + in_pack:
                    culprits.setdefault(top, "; ".join(s.get("why", []))[:400])
        if not culprits:
            culprits = named_in_errors(result.get("receipt"), pack / "mods", present + in_pack)
        if not culprits:
            culprits = first_failing(pack, present + in_pack, loader, lambda: (start_server(), None),
                                     str(res.get("details") or result.get("error"))[-300:], server_opened)
        blamed = {n: why for n, why in culprits.items() if n in present}
        for top in (n for n in culprits if n in in_pack):
            # A library that fails takes the mods that need it along; each is failed with the library's reason.
            gives = provides(pack / "mods" / top, loader, to)
            (pack / "mods" / top).rename(pack / "dropped" / top)
            print(f"  {top}: the library failed")
            for name in present:
                asked = needs(pack / "mods" / name, loader) or set()
                if asked and len(unmet(asked, gives)) < len(asked):
                    blamed.setdefault(name, f"needs the library {top}, which fails: " + culprits[top][:300])
        if not culprits:
            print(res.get("details", done.stdout[-2000:]))
            raise SystemExit(f"a start failed and named no mod; receipt {result.get('receipt')}")
        java = built_for_newer_java(res)
        for name, why in blamed.items():
            verdicts[name] = "failed: " + (java[name] + "; " if name in java else "") + why
            (pack / "mods" / name).rename(pack / "dropped" / name)
            print(f"  {name}: failed")
    # A failed start can name a bystander along with the mod at fault, so each dropped mod that links gets one more start
    # with the mods that loaded; it counts as loaded only if that start opens a world too.
    if result.get("ok"):
        for name in sorted(n for n, v in verdicts.items() if v.startswith("failed") and links.get(n)):
            (pack / "dropped" / name).rename(pack / "mods" / name)
            done = subprocess.run([mmt, "pack-check", "--pack", str(pack), "--minecraft", to, "--loader", loader, "--json"],
                                  capture_output=True, text=True, encoding="utf-8", errors="replace")
            start = done.stdout.find("{")
            again = json.loads(done.stdout[start:]) if start >= 0 else {}
            left_out = again.get("result", {}).get("game", {}).get("leftOut") or {}
            if name in left_out:
                verdicts[name] = "client-only: " + str(left_out[name])[:200]
                (pack / "mods" / name).rename(pack / "dropped" / name)
            elif name in not_loaded(again.get("result", {})):
                (pack / "mods" / name).rename(pack / "dropped" / name)
            elif again.get("ok"):
                verdicts[name] = "loaded"
                print(f"  {name}: loads with the others on its own start")
            else:
                (pack / "mods" / name).rename(pack / "dropped" / name)
                if starts_alone(loader, to, pack, name, platform):
                    # Two mods that patch the same game code can refuse each other on any release, ported or not; the
                    # port is judged by the mod starting alone.
                    verdicts[name] = "clash: loads alone, fails next to the other mods: " + verdicts[name][len("failed: "):]
                    print(f"  {name}: loads alone, fails next to the others")
    client = client_starts(loader, to, pack, [n for n, v in verdicts.items() if v == "loaded" or v.startswith("client-only")],
                           [n for n in platform if (pack / "mods" / n).is_file()], set(platform) - set(libraries))
    loaded = [n for n, v in verdicts.items() if v == "loaded"]
    summary = {
        "loader": loader, "from": frm, "to": to, "scope": scope, "mods": len(chosen), "loaded": len(loaded),
        "linkedAndLoaded": sum(1 for n in loaded if links.get(n)), "linked": sum(links.values()),
        "failedButLinked": sorted(n for n, v in verdicts.items() if v.startswith("failed") and links.get(n) and has_code(folder / n)),
        "clashOnly": sorted(n for n, v in verdicts.items() if v.startswith("clash") and links.get(n)),
        # A mod with no game code links trivially; what fails it is its data, which the link check does not read.
        "failedDataOnly": sorted(n for n, v in verdicts.items() if v.startswith("failed") and links.get(n) and not has_code(folder / n)),
        "verdicts": verdicts,
        "libraries": libraries,
        "notStarted": left,
        "client": client,
        # What the roadmap's step 51 says would prove the server proof wrong: a mod that loads on the server and links, and fails in the client.
        "loadedOnServerFailedInClient": sorted(n for n, v in client["verdicts"].items() if v.startswith("failed") and verdicts.get(n) == "loaded" and links.get(n)),
    }
    print(f"{'mod':<60} {'server':<12} client")
    for name in sorted(verdicts):
        print(f"{name[:60]:<60} {verdicts[name].split(':')[0]:<12} {client['verdicts'].get(name, 'not started').split(':')[0]}")
    print(f"client: {client['loaded']}/{client['mods']} load in a real {to} client; loaded on the server and failed in the client: "
          f"{summary['loadedOnServerFailedInClient'] or 'none'}")
    print(f"{summary['loaded']}/{summary['mods']} load on a real {to} server; {summary['linked']} link, "
          f"{summary['linkedAndLoaded']} of them load; failed although they link: {summary['failedButLinked'] or 'none'}"
          + (f"; failed on their data, with no game code: {summary['failedDataOnly']}" if summary["failedDataOnly"] else "")
          + (f"; load alone but fail next to the others: {summary['clashOnly']}" if summary["clashOnly"] else ""))
    keep_load(summary, out, save)
    return summary


def keep_load(summary, out, save):
    """Writes a load summary to --out and, with --save, to its receipt in bench/exposure/."""
    for path in [p for p in (out, receipt("load", summary["loader"], summary["from"], summary["to"]) if save else None) if p]:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(summary, indent=1) + "\n", encoding="utf-8")
        print(f"saved {path}")


def kind(verdict):
    """A verdict's kind (loaded, failed, client-only, clash), without the reason a run words its own way."""
    return verdict.split(":")[0] if verdict else "not started"


def compare_loads(before, after):
    """What one game proof of an update changes against another, as lines: each mod's server and client verdict whose kind
    moved, and the counts, over the mods both started. A mod the first run started and the second did not counts as moved
    when the second was asked to prove it: every mod (scope "all", or a receipt from before scopes) or its slug."""
    both = sorted(set(before["verdicts"]) & set(after["verdicts"]))
    lines = [f"{after['loader']} {after['from']} -> {after['to']}: {len(both)} mods in both runs"]
    moved = 0
    for side in ("server", "client"):
        pick = (lambda r: r["verdicts"]) if side == "server" else (lambda r: r.get("client", {}).get("verdicts", {}))
        if side == "client" and (before.get("client", {}).get("notRun") or after.get("client", {}).get("notRun")):
            lines.append("client: not run in one of them: " + str(before["client"].get("notRun") or after["client"].get("notRun"))[:200])
            continue
        old, new = pick(before), pick(after)
        same = [n for n in both if kind(old.get(n)) == kind(new.get(n))]
        lines.append(f"{side}: {len(same)}/{len(both)} the same; loaded {sum(kind(old.get(n)) == 'loaded' for n in both)} -> {sum(kind(new.get(n)) == 'loaded' for n in both)}")
        for n in both:
            if kind(old.get(n)) != kind(new.get(n)):
                moved += 1
                lines.append(f"  {n}: {kind(old.get(n))} -> {kind(new.get(n))}" + (f" ({new[n][:200]})" if new.get(n) and ":" in new[n] else ""))
    scope = after.get("scope", "all")
    gone = sorted(n for n in set(before["verdicts"]) - set(after["verdicts"]) if scope == "all" or n.split("--", 1)[0] in scope)
    if gone:
        lines.append("started before and not now:")
    for n in gone:
        moved += 1
        why = after.get("notStarted", {}).get(n)
        lines.append(f"  {n}: {kind(before['verdicts'][n])} -> not started" + (f" ({', '.join(why)[:200]})" if why else ""))
    if not moved:
        lines.append("no verdict moved")
    return lines


HOP = re.compile(r"linked (\d+)/(\d+) classes, (\d+)/(\d+) methods, (\d+)/(\d+) fields")


def steps(frm, to):
    classes = compile_tools()
    versions = chain(frm, to)
    target = ROOT / ".runs" / "exposure" / f"steps-{frm}-{to}-{tools_key(*MAP_SOURCES)}.json"
    if not target.is_file():
        target.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run([java(), f"-Xmx{HEAP}g", "-cp", str(classes), "GameDiff", "--chain", ",".join(versions), "--out", str(target)], check=True)
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
    out = ROOT / "bench" / "exposure" / "steps.json"
    out.write_text(json.dumps(summary, indent=1) + "\n", encoding="utf-8")
    print(f"saved {out}")
    return summary


def tree_at(ref):
    """What a commit's sweep reads (the porter's sources, the rules, the pinned mods, the change index), unpacked once in
    .runs/exposure/at/SHA/ with this file beside it, so the commit's ports run here with the same cache and the same
    loader releases as this branch's: the base a branch is compared with."""
    sha = subprocess.run(["git", "rev-parse", "--verify", ref + "^{commit}"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    if not sha:
        raise SystemExit(f"no commit {ref}")
    tree = ROOT / ".runs" / "exposure" / "at" / sha
    if not (tree / "tools" / "JarPort.java").is_file():
        import io
        import tarfile
        packed = subprocess.run(["git", "archive", sha, "tools", "knowledge", "bench/exposure/mods", "coverage/changes/index.json"],
                                cwd=ROOT, capture_output=True, check=True).stdout
        part = part_of(tree)
        with tarfile.open(fileobj=io.BytesIO(packed)) as t:
            t.extractall(part, filter="data")
        try:
            os.rename(part, tree)
        except OSError:
            shutil.rmtree(part, ignore_errors=True)
    (tree / "bench").mkdir(exist_ok=True)
    shutil.copyfile(Path(__file__), tree / "bench" / "exposure.py")
    return sha, tree


def main():
    if "--at" in sys.argv:
        # Runs this command on another commit's porter: e.g. all --at origin/main for the base a branch is compared with.
        i = sys.argv.index("--at")
        sha, tree = tree_at(sys.argv[i + 1])
        rest = sys.argv[1:i] + sys.argv[i + 2:]
        rest = [str(Path(x).resolve()) if j > 0 and rest[j - 1] == "--out" else x for j, x in enumerate(rest)]
        print(f"running on {sha[:10]}'s porter, rules and pinned mods ({tree})", flush=True)
        sys.exit(subprocess.run([sys.executable, str(tree / "bench" / "exposure.py"), *rest], cwd=tree).returncode)
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    f = sub.add_parser("fetch")
    f.add_argument("--loader", required=True, choices=sorted(FIRST))
    f.add_argument("--version", required=True)
    f.add_argument("--repin", action="store_true", help="list the most-downloaded mods again instead of the pinned ones")
    for name in ("jars", "load"):
        j = sub.add_parser(name)
        j.add_argument("--loader", required=True, choices=sorted(FIRST))
        j.add_argument("--from", dest="frm", required=True)
        j.add_argument("--to", required=True)
        j.add_argument("--save", action="store_true", help="keep the summary in bench/exposure/, named for the update and this commit")
        j.add_argument("--out", type=Path, help="write the summary to this file")
        if name == "jars":
            j.add_argument("--verify", action="store_true", help="port and ask everything again, and fail where a cached answer differs")
            j.add_argument("--type", dest="kind", help="port only the mods the last whole run held back with this type of break (or part of its text)")
        if name == "load":
            j.add_argument("--mods", help="only these mods (Modrinth slugs, comma-separated), with the libraries they need")
    lp = sub.add_parser("load-plan", help="the updates a cloud game proof runs, as JSON (tools/cloud-run load's matrix)")
    lp.add_argument("updates", help="LOADER:FROM:TO or LOADER:FROM (the next release), comma-separated")
    lp.add_argument("--mods", default="", help="the slugs the proof asks of every update; one that no update pins stops the plan")
    ls = sub.add_parser("updates", help="every LOADER:FROM:TO all runs, updates and jumps, as JSON (the cloud run's matrix)")
    ls.add_argument("--loader", choices=sorted(FIRST))
    ls.add_argument("--only", help="LOADER:FROM or LOADER:FROM:TO, comma-separated")
    pn = sub.add_parser("pin")
    pn.add_argument("--loader", choices=sorted(FIRST), help="one loader (default both)")
    pn.add_argument("--version", help="one release (default every one the loader is read for)")
    pn.add_argument("--repin", action="store_true", help="list again releases already pinned")
    al = sub.add_parser("all")
    al.add_argument("--loader", choices=sorted(FIRST), help="one loader (default both)")
    al.add_argument("--only", help="LOADER:FROM,... the updates to run (default every one)")
    al.add_argument("--verify", action="store_true", help="port and ask everything again, and fail where a cached answer differs")
    al.add_argument("--jobs", type=int, default=4, help="updates run side by side (each JarPort takes up to 10 GB)")
    al.add_argument("--out", type=Path, help="write the table to this file (main's is on the generated branch, scoreboard/all.json)")
    jo = sub.add_parser("join")
    jo.add_argument("folder")
    jo.add_argument("--out", type=Path, help="write the table to this file")
    co = sub.add_parser("compare", help="what one table (a branch's) changes against another (main's)")
    co.add_argument("before", type=Path)
    co.add_argument("after", type=Path)
    st = sub.add_parser("steps")
    st.add_argument("--from", dest="frm", default="1.14.4")
    st.add_argument("--to", default="26.3")
    a = p.parse_args()
    global VERIFY
    VERIFY = bool(getattr(a, "verify", False))
    if a.command == "steps":
        steps(a.frm, a.to)
    elif a.command == "jars":
        jars(a.loader, a.frm, a.to, a.save, a.out, kind=a.kind)
    elif a.command == "updates":
        only = {x.strip() for x in a.only.split(",")} if a.only else None
        every = [(lo, x, y) for lo in ([a.loader] if a.loader else sorted(FIRST)) for x, y in runs(lo)]
        picked = [f"{lo}:{x}:{y}" for lo, x, y in every if not only or f"{lo}:{x}" in only or f"{lo}:{x}:{y}" in only]
        # A name that picks nothing ran an empty sweep that looked like a result (0 of 0); say which instead.
        unknown = sorted(o for o in only or () if not any(o in (f"{lo}:{x}", f"{lo}:{x}:{y}") for lo, x, y in every))
        if unknown:
            raise SystemExit(f"--only names no update: {', '.join(unknown)} (LOADER:FROM for every update from it, or LOADER:FROM:TO)")
        print(json.dumps(picked))
    elif a.command == "load":
        load(a.loader, a.frm, a.to, a.save, [m.strip() for m in a.mods.split(",") if m.strip()] if a.mods else None, a.out)
    elif a.command == "load-plan":
        print(json.dumps(load_plan(a.updates, [m.strip() for m in a.mods.split(",") if m.strip()])))
    elif a.command == "all":
        only = {x.strip() for x in a.only.split(",")} if a.only else None
        every_update([a.loader] if a.loader else sorted(FIRST), only, a.jobs, a.out)
    elif a.command == "pin":
        for lo in [a.loader] if a.loader else sorted(FIRST):
            for v in [a.version] if a.version else [frm for frm, _ in updates(lo)]:
                if a.repin or not (PINS / f"{lo}-{v}.json").is_file():
                    pin(lo, v)
    elif a.command == "join":
        join(a.folder, a.out)
    elif a.command == "compare":
        before, after = (json.loads(f.read_text(encoding="utf-8")) for f in (a.before, a.after))
        print("\n".join((compare_loads if "verdicts" in after else compare)(before, after)))
    else:
        print(f"{len(fetch(a.loader, a.version, a.repin))} {a.loader} mods for {a.version} in {HOME / 'jars' / f'{a.loader}-{a.version}'}")


if __name__ == "__main__":
    main()

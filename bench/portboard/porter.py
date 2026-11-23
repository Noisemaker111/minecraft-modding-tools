"""The porter run on a pile of jars: its classes compiled once, the route maps, the jars read in Mojang's names, the port itself and what the game's code says about what is left."""
import functools
import hashlib
import json
import os
import re
import shutil
import subprocess
import urllib.parse
import urllib.request
from pathlib import Path
import port_tools  # noqa: E402

from portboard import cachekeys, gamereleases, javalevels, settings


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


def java_version():
    return subprocess.run([java(), "-version"], capture_output=True, text=True).stderr.strip()


def route_map(frm, to, loader, classes):
    return route_maps([(frm, to)], loader, classes)[0]


def route_maps(updates, loader, classes):
    """The route map of each (from, to), kept in ~/.mmt/exposure/maps/. The missing ones are built by one GameDiff process,
    each as its own chain: a long port has a map per step (stops), and a process per map started cold and read both
    releases again every time."""
    folder = cachekeys.keyed_at("maps", cachekeys.content_key(cachekeys.tools_key(*settings.MAP_SOURCES), cachekeys.platform_bytes(), java_version()))
    # Fabric API is chosen per release (Exposure.FabricApi), not from a list platform_bytes holds: a map is kept per choice.
    jars = fabric_apis([v for frm, to in updates for v in gamereleases.chain(frm, to)]) if loader == "fabric" else {}
    apis = lambda frm, to: "-" + cachekeys.content_key(*(jars[v].name if jars[v] else "" for v in gamereleases.chain(frm, to))) if jars else ""
    targets = [folder / f"{loader}-{frm}-{to}{apis(frm, to)}.json" for frm, to in updates]
    missing = {t: (frm, to) for t, (frm, to) in zip(targets, updates) if not t.is_file()}
    if missing:
        parts = {t: cachekeys.part_of(t) for t in missing}
        args = [java(), f"-Xmx{settings.HEAP}g", "-cp", str(classes), "GameDiff"] + (["--loader", loader] if loader else [])
        for t, (frm, to) in missing.items():
            args += ["--chain", ",".join(gamereleases.chain(frm, to)), "--out", str(parts[t])]
        subprocess.run(args, check=True, stdout=subprocess.DEVNULL)
        for t, part in parts.items():
            cachekeys.publish(part, t)
    return targets


def tools_classes_key():
    """The porter's sources and the ASM it is built with, hashed: what its classes, and so every port, are made of."""
    if "key" not in settings.CLASSES:
        settings.CLASSES["key"] = cachekeys.content_key(cachekeys.tools_key(), *(a.read_bytes() for a in settings.ASM))
    return settings.CLASSES["key"]


def jar_tools():
    """The porter's classes, compiled once per content of its sources in ~/.mmt/exposure/classes/KEY/, so every worktree
    and every run with the same sources shares one compile (a compile is about 20 s; agents spent hours on them)."""
    if "path" in settings.CLASSES:
        return settings.CLASSES["path"]
    out = cachekeys.keyed_at("classes", tools_classes_key()) / "classes"
    if not (out / "JarPort.class").is_file():
        sources = [settings.ROOT / n for n in port_tools.sources(root=settings.ROOT)]
        javac = java().replace("java.exe", "javac.exe") if java().endswith("java.exe") else java() + "c"
        part = cachekeys.part_of(out)
        subprocess.run([javac, "-nowarn", "-encoding", "UTF-8", "-cp", os.pathsep.join(map(str, settings.ASM)), "-d", str(part)] + [str(s) for s in sources],
                       check=True)
        try:
            os.rename(part, out)
        except OSError:
            shutil.rmtree(part, ignore_errors=True)  # a run beside this one compiled the same sources first
    settings.CLASSES["path"] = os.pathsep.join([str(out)] + [str(a) for a in settings.ASM])
    return settings.CLASSES["path"]


def piles(jars):
    """The jars dealt into piles that take about as long, one per processor: every next-biggest jar goes on the lightest pile."""
    count = max(1, min(settings.CORES // settings.SPARE, len(jars) // settings.PILE))
    dealt = [[0, []] for _ in range(count)]
    for jar in sorted(jars, key=lambda j: (-j.stat().st_size, j.name)):
        pile = min(dealt, key=lambda d: d[0])
        pile[0] += jar.stat().st_size
        pile[1].append(jar)
    return [pile for _, pile in dealt]


def side_by_side(command, jars, report):
    """Runs a tool that reads jars one at a time (ModNames) on every processor at once: a jar's result does not depend on
    the jars beside it, and one process used one of this machine's processors (150 mods took 140 s on one of 24). Returns
    the reports' rows in the order the jars were given."""
    dealt = piles(jars)
    if len(dealt) > 1:
        # The piles share the memory one process had.
        command = [f"-Xmx{max(3, settings.HEAP // len(dealt))}g" if c.startswith("-Xmx") else c for c in command]
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
    """The jars in Mojang's names: Fabric's before 26.1 read through intermediary by tools/port/names/ModNames.java (kept in
    ~/.mmt/exposure/mojang/ until ModNames or VersionMap changes), every other one as it is. A jar it cannot read is
    left out, with why."""
    if not shipped_obfuscated(loader, version):
        return jars, {}
    out = cachekeys.keyed("mojang", *settings.NAME_SOURCES) / f"{loader}-{version}"
    out.mkdir(parents=True, exist_ok=True)
    missing = [j for j in jars if not (out / j.name).is_file()]
    failed = {}
    if missing:
        # ModNames writes each jar under a name of its own and moves it into place, so a run beside this one never reads half a jar.
        report = settings.ROOT / ".runs" / "exposure" / f"names-{loader}-{version}-{os.getpid()}.json"
        report.parent.mkdir(parents=True, exist_ok=True)
        rows = side_by_side([java(), f"-Xmx{min(4, settings.HEAP)}g", "-cp", jar_tools(), "ModNames", "--version", version, "--loader", loader, "--out", str(out)], missing, report)
        failed = {m["mod"]: m["error"] for m in rows if "error" in m}
    return [out / j.name for j in jars if (out / j.name).is_file()], failed


def shipped_obfuscated(loader, version):
    """Whether a loader's mods for a release name the game in other names than Mojang's: Fabric's in intermediary before 26.1,
    NeoForge's in SRG ids (m_N_, f_N_) before 1.20.5."""
    return gamereleases.vkey(version) < gamereleases.vkey(settings.UNOBFUSCATED) if loader == "fabric" else gamereleases.vkey(version) < gamereleases.vkey(settings.MOJANG_NEOFORGE)


def stops(loader, frm, to):
    """The releases a port from frm to to stops at (tools/port/jar/JarPort.java, hops): each update with stand-ins is a step of its
    own, so its stand-ins run and the steps after it port them; the updates between are one step on their composed map."""
    key = (loader, frm, to)
    if key not in settings.STOPS:
        if len(gamereleases.chain(frm, to)) <= 2:
            settings.STOPS[key] = [frm, to]
        else:
            planned = subprocess.run([java(), "-cp", jar_tools(), "JarPort", "--hops", "--chain", ",".join(gamereleases.chain(frm, to)),
                                      "--fixes", str(settings.ROOT / "knowledge" / "fixes.json"), "--loader", loader], capture_output=True, text=True, check=True)
            settings.STOPS[key] = planned.stdout.strip().splitlines()[-1].split(",")
    return settings.STOPS[key]


def routes(loader, frm, to):
    """The route map of each step of a port from frm to to."""
    steps = stops(loader, frm, to)
    return route_maps(list(zip(steps, steps[1:])), loader, compile_tools())


def port_key(loader, frm, to, runtime=False):
    """Everything one port reads beside the mod's own jar: the porter's classes, each step's route map and stand-ins, the
    port rules, the loaders' release lists, the JVM and the update. A mod's port with an equal key and an equal jar is the same port."""
    # The hand-back of an obfuscated release reads the jars as they came too: the release's pinned files.
    pinned = (settings.PINS / f"{loader}-{frm}.json").read_bytes() if shipped_obfuscated(loader, frm) and (settings.PINS / f"{loader}-{frm}.json").is_file() else b""
    steps = stops(loader, frm, to)
    maps = routes(loader, frm, to)
    # The registry reports the data pass reads (DataIds), or that a release has none.
    reports = [(settings.HOME / "registries" / f"{v}.json").read_bytes() if (settings.HOME / "registries" / f"{v}.json").is_file() else b"none" for v in steps]
    return cachekeys.content_key(tools_classes_key(), *(r.read_bytes() for r in maps), (settings.ROOT / "knowledge" / "fixes.json").read_bytes(),
                       *(standins_key(loader, a, b) for a, b in zip(steps, steps[1:])), standins_key(loader, "jdk", None), *(cachekeys.platform_key(loader, r) for r in maps),
                       *reports, javalevels.java_check_key(), java_version(), loader, frm, to, runtime, pinned)


def registry_reports(versions):
    """Each release's registry report (its own data generator's, in ~/.mmt/exposure/registries/), made by DataIds where
    missing: the port's data pass reads them, and port_key keys every port by them, so they are made before either."""
    if all((settings.HOME / "registries" / f"{v}.json").is_file() for v in versions):
        return
    subprocess.run([java(), "-cp", jar_tools(), "DataIds", *versions], check=True)


def standins_key(loader, frm, to):
    """The stand-ins JarPort reads for one update and loader (knowledge/standins/FROM-TO/ and FROM-TO.LOADER/), each file's
    path and content."""
    parts = []
    # to None: the JDK stand-ins (knowledge/standins/jdk/), which a port to a release on an older Java reads.
    folders = [settings.ROOT / "knowledge" / "standins" / frm] if to is None else [settings.ROOT / "knowledge" / "standins" / f"{frm}-{to}", settings.ROOT / "knowledge" / "standins" / f"{frm}-{to}.{loader}"]
    for folder in folders:
        if folder.is_dir():
            for f in sorted(folder.rglob("*.java")):
                parts += [f.relative_to(folder.parent).as_posix().encode(), f.read_bytes()]
    return cachekeys.content_key(*parts)


def jar_port(loader, frm, to, jars, out_dir, report, runtime=False, cache=False):
    """Ports jars with tools/port/jar/JarPort.java into out_dir and returns its report: jar_ports with one target."""
    return jar_ports(loader, frm, [(to, out_dir, report)], jars, runtime, cache)[to]


def jar_ports(loader, frm, targets, jars, runtime=False, cache=False):
    """Ports jars from frm to each (to, out_dir, report) of targets with tools/port/jar/JarPort.java, in one process that ports the
    steps the targets share once (JarPort.portAll), and returns each target's report. runtime: in the names the loader
    runs (a Fabric jar handed back in the target release's intermediary), for a game to start them. cache: only the
    reports are wanted, so a mod whose port to a target (port_key and its jar's content) was made before, by any run on
    this machine, is read from ~/.mmt/exposure/ports/; a mod with no kept port to some target is ported to all of them,
    and where a kept port is there too the two must be the same, as --verify checks for every mod."""
    plan, keys, rows, todo, checked = [], {}, {}, set(), 0
    for to, out_dir, report in targets:
        steps = stops(loader, frm, to)
        registry_reports(steps)
        out_dir.mkdir(parents=True, exist_ok=True)
        plan.append({"to": to, "via": steps[1:-1], "maps": [str(m) for m in routes(loader, frm, to)], "out": str(out_dir), "report": str(report)})
        rows[to] = {}
        folder = cachekeys.keyed_at("ports", port_key(loader, frm, to, runtime)) if cache else None
        for jar in jars:
            if cache:
                keys[to, jar.name] = folder / (hashlib.sha256(jar.read_bytes()).hexdigest()[:24] + ".json")
            if cache and keys[to, jar.name].is_file() and not settings.VERIFY:
                rows[to][jar.name] = json.loads(keys[to, jar.name].read_text(encoding="utf-8"))
            else:
                todo.add(jar.name)
    todo = [j for j in jars if j.name in todo]
    if todo:
        written = Path(targets[0][2]).with_name(f"plan-{os.getpid()}.json")
        written.write_text(json.dumps({"ports": plan}), encoding="utf-8")
        args = [java(), f"-Xmx{settings.HEAP}g", "-cp", jar_tools(), "JarPort", "--from", frm, "--plan", str(written), "--fixes", str(settings.ROOT / "knowledge" / "fixes.json"),
                "--loader", loader] + (["--runtime-names"] if runtime else [])
        if shipped_obfuscated(loader, frm):
            # The jars as they came, before mojang_names: the hand-back gives a class's own intermediary names back from them.
            args += ["--originals", str(settings.HOME / "jars" / f"{loader}-{frm}")]
        # One process: JarPort ports the jars of each step on every processor, and each process read every step's releases.
        code = jarport(args + [str(j) for j in todo])
        written.unlink()
        if code:
            raise SystemExit(f"JarPort failed on {loader} {frm} -> {', '.join(t[0] for t in targets)} (exit {code}); its output is above")
        for to, _, report in targets:
            runs = functools.cache(lambda to=to: javalevels.release_java(to))
            for m in json.loads(Path(report).read_text(encoding="utf-8"))["mods"]:
                m = javalevels.with_java_checked(m, loader, to, runs)
                if not cache:
                    rows[to][m["mod"]] = m
                    continue
                kept = {k: v for k, v in m.items() if k != "out"}  # where this run wrote it, not part of the answer
                if keys[to, m["mod"]].is_file():
                    checked += 1
                    if json.loads(keys[to, m["mod"]].read_text(encoding="utf-8")) != kept:
                        raise SystemExit(f"the cached port of {m['mod']} ({loader} {frm} -> {to}, {keys[to, m['mod']]}) differs from porting it again: "
                                         "the cache key misses an input of the port, or the port is not the same run to run")
                rows[to][m["mod"]] = kept
                part = cachekeys.part_of(keys[to, m["mod"]])
                part.write_text(json.dumps(kept), encoding="utf-8")
                cachekeys.publish(part, keys[to, m["mod"]])
    merged = {}
    for to, _, report in targets:
        found = [rows[to][j.name] for j in jars if j.name in rows[to]]
        counts = {}
        for m in found:
            for member in left(m):
                counts[member] = counts.get(member, 0) + 1
        merged[to] = {"from": frm, "to": to, "loader": loader, "linked": sum(1 for m in found if "error" not in m and not m["unlinked"]), "mods": found,
                      "unlinkedByMods": [{"member": k, "mods": v} for k, v in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))],
                      "ported": len(todo), "verified": checked}
        Path(report).write_text(json.dumps(merged[to], indent=1), encoding="utf-8")
    return merged


def jarport(command):
    """Runs JarPort and returns its exit code; its rows are in each target's report."""
    return subprocess.run(command, stdout=subprocess.DEVNULL).returncode


def classes_key(classpath, names):
    """The bytes of the named classes of the porter's classpath (its first entry), hashed with the ASM it runs with."""
    folder = Path(classpath.split(os.pathsep)[0])
    return cachekeys.content_key(*sorted(names), *((folder / (n.replace(".", "/") + ".class")).read_bytes() if (folder / (n.replace(".", "/") + ".class")).is_file() else b"gone" for n in sorted(names)),
                       *(a.read_bytes() for a in settings.ASM))


LOADED = re.compile(r"\[class,load\] (\S+) source: (?:file:)?(.*)$")


def game_says(loader, frm, to, report):
    """What the game's own code did about each reference JarPort's report ranks (tools/port/Exposure.java). Exposure answers each
    reference on its own, from the two releases alone, and runs only some of the porter's classes (not JarPort's port), so
    every answer is kept in ~/.mmt/exposure/game/ under the bytes of the classes it loaded the last time and the loaders'
    release lists: an edit to the port leaves it, and it runs again only for a reference it never answered. A class it
    starts to load is loaded from code that changed, so its key changes with it."""
    classes = compile_tools()
    asked = [r["member"] for r in json.loads(report.read_text(encoding="utf-8"))["unlinkedByMods"]]
    # The loader releases of each step: a port that stops on the way reads no map of the whole jump, and none is built for it.
    folder = cachekeys.keyed_at("game", cachekeys.content_key(*(cachekeys.platform_key(loader, r) for r in routes(loader, frm, to)), java_version())) / f"{loader}-{frm}-{to}"
    folder.mkdir(parents=True, exist_ok=True)
    loaded = folder / "loaded.txt"
    known = folder / (classes_key(classes, loaded.read_text(encoding="utf-8").split()) + ".json") if loaded.is_file() else None
    said = json.loads(known.read_text(encoding="utf-8")) if known and known.is_file() else {"answered": [], "said": {}}
    if settings.VERIFY or not set(asked) <= set(said["answered"]):
        out = report.with_name("game.json")
        log = report.with_name("game-classes.log")
        subprocess.run([java(), f"-Xmx{settings.HEAP}g", f"-Xlog:class+load=info:file={log}", "-cp", str(classes), "Exposure", "--from", frm, "--to", to,
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
            part = cachekeys.part_of(path)
            part.write_text(text, encoding="utf-8")
            os.replace(part, path)  # a later answer only adds rows; each row is the same in every run with this key
    return {m: said["said"][m] for m in asked if m in said["said"]}


WHERE = re.compile(r" \(in [^)]*\)$")


def left(row, data=None):
    """What one mod still needs: each reference once (a mod and its nested jars name it once a class), and the game ids its data lost."""
    return {WHERE.sub("", u.split(": ", 1)[1]) for u in row.get("unlinked", [])} | {WHERE.sub("", ref) for ref in (data or {}).get(row["mod"], [])}


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
    """The Fabric API players install on a release (a jar in ~/.mmt/mappings/), or None: Exposure.FabricApi chooses it (and keeps its answer in maps/fabric-api/), so
    the game a port is started in runs the jar the link check and the route map read."""
    return fabric_apis([version])[version]


def fabric_apis(versions):
    """fabric_api of each release, read with one Java process."""
    missing = [v for v in dict.fromkeys(versions) if v not in settings.FABRIC_APIS]
    if missing:
        done = subprocess.run([java(), "-Xmx1g", "-cp", jar_tools(), "Exposure", "--fabric-api", ",".join(missing)],
                              capture_output=True, text=True)
        if done.returncode:
            # Exposure's own error names the release and the download or file; without it a cloud job said only "exit 1".
            said = "\n".join(done.stderr.strip().splitlines()[-20:]) or "it printed nothing"
            raise SystemExit(f"the Fabric API lookup for {', '.join(missing)} failed (exit {done.returncode}):\n{said}")
        for line in done.stdout.splitlines():
            v, _, path = line.partition("\t")
            settings.FABRIC_APIS[v] = Path(path) if path else None
    return {v: settings.FABRIC_APIS[v] for v in versions}

"""The in-game proof: ported mods started on a real client and server of the new release, and who is to blame when it does not start."""
import json
import os
import re
import shutil
import subprocess
import time
from pathlib import Path

from portboard import board, dependencies, gamereleases, javalevels, porter, settings


# Every pack-check a game proof starts, in order: its side, its seconds and mmt's own stages (load reports them).
STARTS = []


def pack_check(pack, to, loader, side="server"):
    """One mmt pack-check of a pack folder on a side (server, or client): its JSON ({} when it printed none) and the
    finished process. Its time and mmt's stages in it are kept in STARTS."""
    mmt = str(settings.ROOT / ("mmt.cmd" if os.name == "nt" else "mmt"))
    started = time.monotonic()
    done = subprocess.run([mmt, "pack-check", "--pack", str(pack), "--minecraft", to, "--loader", loader] + (["--side", "client"] if side == "client" else []) + ["--json"],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")
    at = done.stdout.find("{")
    result = json.loads(done.stdout[at:]) if at >= 0 else {}
    stages = {}
    for stage in result.get("stages") or []:
        stages[stage.get("name", "?")] = round(stages.get(stage.get("name", "?"), 0) + float(stage.get("seconds") or 0), 1)
    STARTS.append({"side": side, "seconds": round(time.monotonic() - started, 1), "stages": stages})
    return result, done


def start_times(starts):
    """STARTS summed by side: {side: {"starts", "seconds", "stages": {name: seconds}}}."""
    out = {}
    for one in starts:
        side = out.setdefault(one["side"], {"starts": 0, "seconds": 0.0, "stages": {}})
        side["starts"] += 1
        side["seconds"] = round(side["seconds"] + one["seconds"], 1)
        for name, seconds in one["stages"].items():
            side["stages"][name] = round(side["stages"].get(name, 0) + seconds, 1)
    return out


def time_line(times):
    """A load receipt's time, as one line: the port before any start, then each side's starts and mmt's stages in them."""
    parts = [f"port {times.get('port', 0):.0f} s"]
    for side in ("server", "client"):
        t = times.get(side)
        if t:
            stages = ", ".join(f"{n} {v:.0f} s" for n, v in sorted(t["stages"].items(), key=lambda kv: -kv[1]))
            parts.append(f"{side} {t['starts']} start{'s' if t['starts'] != 1 else ''} {t['seconds']:.0f} s" + (f" ({stages})" if stages else ""))
    return "time: " + "; ".join(parts)


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
    verdicts = {}

    def start():
        if run:
            return run(pack), subprocess.CompletedProcess([], 1, "", "")
        return pack_check(pack, to, loader, "client")

    # As on the server, a jar whose client start classes need a newer Java than the release's is failed before any start
    # (a mod whose only too-new class is its client entrypoint loads on a server).
    present = sorted(p.name for p in (pack / "mods").glob("*.jar") if p.name not in platform)
    misfits = javalevels.newer_java(pack / "mods", present + [n for n in platform if (pack / "mods" / n).is_file() and n not in kept], loader, to, "client")
    for name, why in sorted(misfits.items()):
        print(f"  {name}: {why}")
    found, result, res, done = dict(misfits), {}, {}, None
    # Each failed start takes at least one jar out (a mod or a library), or ends the client proof with why, so the starts
    # end; their number is not budgeted by the mods, which libraries failing would use up before every mod had a verdict.
    while True:
        present = sorted(p.name for p in (pack / "mods").glob("*.jar") if p.name not in platform)
        # The libraries can be what fails too; the platform (kept: Fabric API, whose own id was "fabric" before 1.18.2)
        # cannot be left out.
        libraries = [n for n in platform if (pack / "mods" / n).is_file() and n not in kept]
        if not found:
            found = client_culprits(pack, present, libraries, loader, start)
            if found is None:
                for name in present:
                    verdicts.setdefault(name, "loaded")
                break
            found, result, res, done = found
        for library in [n for n in found if n in libraries]:
            gives = dependencies.provides(pack / "mods" / library, loader, to)
            (pack / "mods" / library).rename(pack / "dropped" / library)
            platform = [n for n in platform if n != library]
            print(f"  {library}: the library fails in the client")
            for name in present:
                asked = dependencies.needs(pack / "mods" / name, loader) or set()
                if asked & gives:
                    found.setdefault(name, f"needs the library {library}, which fails in the client: " + found[library][:300])
                    if library in misfits:
                        misfits.setdefault(name, found[name])
        blamed = {n: why for n, why in found.items() if n in present}
        if not blamed and any(n in libraries for n in found):
            found = {}
            continue  # only a library failed: the next start runs without it
        if not blamed:
            # The client itself could not be driven (no mod set makes it fail or pass, or mmt's own step does not fit this
            # release): the server's verdicts stand, and the client is reported as not run, with why.
            failure = str(res.get("client", {}).get("failure") or res.get("error") or done.stdout[-300:])[:400]
            print(res.get("details", done.stdout[-2000:]))
            print(f"a client start failed and named no mod; the client is not run: {failure}")
            return {"mods": len(names), "loaded": 0, "verdicts": {}, "notRun": failure, "receipt": result.get("receipt")}
        java = built_for_newer_java(res)
        for name, why in blamed.items():
            verdicts[name] = "failed: " + (java[name] + "; " if name in java and name not in misfits else "") + why
            (pack / "mods" / name).rename(pack / "dropped" / name)
            if name not in misfits:
                print(f"  {name}: failed in the client")
        found = {}
    # As on the server, a dropped mod gets one more start with the mods that loaded: a log can name a bystander. One its
    # class files fail is not started again.
    for name in sorted(n for n, v in verdicts.items() if v.startswith("failed") and n not in misfits):
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
    return {"mods": len(names), "loaded": len(loaded), "verdicts": verdicts, "newerJava": sorted(misfits)}


def client_culprits(pack, present, libraries, loader, start):
    """None when a client start opens; else ({jar: why} for the jars a failed one names, by its findings, its log, or
    halving, and {} when none), with the start's result, its result block and the finished process."""
    result, done = start()
    res = result.get("result", {})
    if res.get("client", {}).get("opened"):
        return None
    found = {}
    for finding in res.get("findings", []):
        if finding.get("severity") != "problem" or finding.get("kind") != "client-start":
            continue
        for s in finding.get("suspects", []):
            top = s.get("jar", "").split("!")[0]
            if top not in present + libraries:
                top = next((n for n in present + libraries if s.get("mod") in dependencies.mod_ids(pack / "mods" / n)), "")
            if top:
                found.setdefault(top, "; ".join(s.get("why", []))[:400] + " | " + str(res.get("client", {}).get("failure", ""))[:300])
    if not found:
        found = {n: "the client log names it: " + why for n, why in named_in_errors(result.get("receipt"), pack / "mods", present + libraries, "*client*.log").items()}
    failure = str(res.get("client", {}).get("failure") or res.get("error") or done.stdout[-300:])[:400]
    if not found and not res.get("client", {}).get("toolError"):
        found = first_failing(pack, present + libraries, loader, start, failure)
    return found, result, res, done


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
    asks = {n: dependencies.needs(mods / n, loader) or set() for n in present}
    gives = {n: dependencies.provides(mods / n, loader) for n in present}

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
    return bool(pack_check(alone, to, loader)[0].get("ok"))


def display_name(jar):
    """The name a mod shows players (fabric.mod.json "name", or the first [[mods]] displayName), or None."""
    import tomllib
    import zipfile
    meta = porter.fabric_meta(jar)
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
            if pack_id in dependencies.mod_ids(mods / name):
                blamed[name] = "its data fails on this release: " + line[:300]
    for m in mixins:
        for name in present:
            if m.group(2) in dependencies.mod_ids(mods / name):
                blamed.setdefault(name, f"its mixin {m.group(1)} fails to apply: " + m.string.strip()[:300])
    for line in errors:
        # The mod whose start the error is about: the one the line says needs something ("... of NAME VERSION ...").
        for name in present:
            shown = display_name(mods / name)
            if shown and re.search(r"\bof " + re.escape(shown) + r"\b", line):
                blamed[name] = "the loader names it: " + line[2:300]
    return blamed


def start_culprits(result, pack, present, in_pack, loader, start_server):
    """{jar: why} for a failed server start: the jars its findings name, else the ones its log's errors name, else the
    one halving finds."""
    res = result.get("result", {})
    culprits = {}
    for finding in res.get("findings", []):
        if finding.get("severity") != "problem":
            continue
        for s in finding.get("suspects", []):
            top = s.get("jar", "").split("!")[0] or next((n for n in present + in_pack if s.get("mod") in dependencies.mod_ids(pack / "mods" / n)), "")
            if top in present + in_pack:
                culprits.setdefault(top, "; ".join(s.get("why", []))[:400])
    if not culprits:
        culprits = named_in_errors(result.get("receipt"), pack / "mods", present + in_pack)
    if not culprits:
        culprits = first_failing(pack, present + in_pack, loader, lambda: (start_server(), None),
                                 str(res.get("details") or result.get("error"))[-300:], server_opened)
    return culprits


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
        if parts[0] not in settings.FIRST or len(parts) not in (2, 3):
            raise SystemExit(f"{item}: an update is LOADER:FROM:TO or LOADER:FROM, LOADER one of {', '.join(sorted(settings.FIRST))}")
        if len(parts) == 2:
            nxt = dict(gamereleases.updates(parts[0])).get(parts[1])
            if not nxt:
                raise SystemExit(f"{item}: {parts[1]} is not a release the {parts[0]} scoreboard reads")
            parts.append(nxt)
        out.append(":".join(parts))
    pinned = set()
    for update in out:
        lo, frm, _ = update.split(":")
        pins = settings.PINS / f"{lo}-{frm}.json"
        pinned |= {m["slug"] for m in json.loads(pins.read_text(encoding="utf-8"))["mods"]} if pins.is_file() else set()
    unknown = sorted(set(mods or ()) - pinned)
    if unknown:
        raise SystemExit(f"no update of {spec} pins {', '.join(unknown)} (bench/exposure/mods/LOADER-FROM.json): the proof would start no game for it")
    return out


def proof_report(work, mods):
    """The jars report a game proof reads: the whole update's, or for a proof of some mods (mods: slugs) a newer report of
    some mods (jars --mods or --type, as a port check runs it) when it read every one of them."""
    whole, part = work / "report.json", work / "report-part.json"
    if mods and part.is_file() and (not whole.is_file() or part.stat().st_mtime >= whole.stat().st_mtime):
        read = {m["mod"].split("--", 1)[0] for m in json.loads(part.read_text(encoding="utf-8"))["mods"]}
        if set(mods) <= read:
            return part
    return whole


def load(loader, frm, to, save, mods=None, out=None):
    """The mods the jar scoreboard says link, ported as jars and started on a real server of the new release with the library
    mods they need (choose), then in a real client of it (client_starts), with both verdicts side by side. mods: only these
    (Modrinth slugs), with the libraries they need.

    One start holds them all; a start that fails drops the jar its log names and starts again, until a start opens a world,
    places every block item and opens it again. Each mod ends loaded, failed (with why) or left out as client-only. The
    receipt's time says where the proof's time went: the port before any start, and each side's starts with mmt's stages."""
    STARTS.clear()
    began = time.monotonic()
    work = settings.ROOT / ".runs" / "exposure" / f"jars-{loader}-{frm}-{to}"
    report = proof_report(work, mods)
    if not report.is_file():
        board.jars(loader, frm, [to])
    linked = [m["mod"] for m in json.loads(report.read_text(encoding="utf-8"))["mods"] if "error" not in m and not m["unlinked"]]
    skipped = {}
    if mods:
        linked, skipped = only_mods(linked, json.loads((settings.PINS / f"{loader}-{frm}.json").read_text(encoding="utf-8"))["mods"], mods)
    folder = settings.HOME / "jars" / f"{loader}-{frm}"
    # A Fabric mod that needs Fabric API needs nothing beyond the platform the pack starts with. A linked mod that is the
    # platform itself (Fabric API among the top mods) is not started twice: the loader refuses two jars with one mod id,
    # and the platform's copy for the new release is the one players run. Other mods start with the mods and libraries
    # they need.
    pins = json.loads((settings.PINS / f"{loader}-{frm}.json").read_text(encoding="utf-8"))["mods"]
    pack = settings.ROOT / ".runs" / "exposure" / f"load-{loader}-{frm}-{to}"
    if pack.exists():
        shutil.rmtree(pack)
    # What a mod needs is read from its ported jar, the one the game is given: the port renames the platform modules it
    # asks for (Fabric API's 26.1 renames) and its game range, which its jar as it came still names the old way.
    # A Fabric jar is ported in Mojang's names (read as jars does) and handed back in the names Fabric runs.
    read, _ = porter.mojang_names(loader, frm, [folder / n for n in linked if (folder / n).is_file()])
    ported = pack / "ported"
    report = porter.jar_port(loader, frm, to, read, ported, pack / "report-mods.json", runtime=loader == "fabric") if read else {"mods": []}
    chosen, ready, to_port, left = dependencies.choose(loader, frm, to, linked, ported, pins, set(javalevels.platform_ids(loader, to)))
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
                   "newerJava": [], "failedButLinked": [], "clashOnly": [], "failedDataOnly": [], "verdicts": {}, "libraries": {}, "notStarted": left,
                   "client": {"mods": 0, "loaded": 0, "verdicts": {}}, "loadedOnServerFailedInClient": [],
                   "time": {"port": round(time.monotonic() - began, 1)}}
        keep_load(summary, out, save)
        raise SystemExit(why)
    (pack / "mods").mkdir()
    for jar in chosen:
        shutil.move(jar, pack / "mods" / jar.name)
    rows = [m for m in report["mods"] if m["mod"] in {j.name for j in chosen}]
    if to_port:
        read, _ = porter.mojang_names(loader, frm, list(to_port.values()))
        rows = rows + porter.jar_port(loader, frm, to, read, pack / "mods", pack / "report-libraries.json", runtime=loader == "fabric")["mods"]
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
        api_jar = porter.fabric_api(to)
        if api_jar:
            shutil.copy(api_jar, pack / "mods" / api_jar.name)
            platform.append(api_jar.name)
    verdicts = {}

    def start_server():
        return pack_check(pack, to, loader)[0]

    ported_in = round(time.monotonic() - began, 1)
    # A jar built for a newer Java than the release runs on is failed from its class files before the first start, a
    # library with the mods that need it, so the starts hold only jars the release's Java can load.
    present = sorted(p.name for p in (pack / "mods").glob("*.jar") if p.name not in platform)
    misfits = javalevels.newer_java(pack / "mods", present + list(libraries), loader, to, "server")
    for name, why in sorted(misfits.items()):
        print(f"  {name}: {why}")
    culprits = dict(misfits)
    result, res, done = {}, {}, None
    # Each failed start takes at least one jar out or stops the proof with why, so the starts end without a budget.
    while True:
        present = sorted(p.name for p in (pack / "mods").glob("*.jar") if p.name not in platform)
        in_pack = [n for n in libraries if (pack / "mods" / n).is_file()]
        if not culprits:
            result, done = pack_check(pack, to, loader)
            res = result.get("result", {})
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
            culprits = start_culprits(result, pack, present, in_pack, loader, start_server)
        blamed = {n: why for n, why in culprits.items() if n in present}
        for top in (n for n in culprits if n in in_pack):
            # A library that fails takes the mods that need it along; each is failed with the library's reason.
            gives = dependencies.provides(pack / "mods" / top, loader, to)
            (pack / "mods" / top).rename(pack / "dropped" / top)
            print(f"  {top}: the library failed")
            for name in present:
                asked = dependencies.needs(pack / "mods" / name, loader) or set()
                if asked and len(dependencies.unmet(asked, gives)) < len(asked):
                    blamed.setdefault(name, f"needs the library {top}, which fails: " + culprits[top][:300])
                    if top in misfits:
                        misfits.setdefault(name, blamed[name])
        if not culprits:
            print(res.get("details", done.stdout[-2000:]))
            raise SystemExit(f"a start failed and named no mod; receipt {result.get('receipt')}")
        java = built_for_newer_java(res)
        for name, why in blamed.items():
            verdicts[name] = "failed: " + (java[name] + "; " if name in java and name not in misfits else "") + why
            (pack / "mods" / name).rename(pack / "dropped" / name)
            if name not in misfits:
                print(f"  {name}: failed")
        culprits = {}
    # A failed start can name a bystander along with the mod at fault, so each dropped mod that links gets one more start
    # with the mods that loaded; it counts as loaded only if that start opens a world too. One its class files fail is
    # not started again: the release's Java refuses it whatever else is in the pack.
    if result.get("ok"):
        for name in sorted(n for n, v in verdicts.items() if v.startswith("failed") and links.get(n) and n not in misfits):
            (pack / "dropped" / name).rename(pack / "mods" / name)
            again, done = pack_check(pack, to, loader)
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
        # Built for a newer Java than the release runs on, read from their class files and not started: no port of
        # their game references makes them load, so the lists below that judge the port leave them out.
        "newerJava": sorted(misfits),
        "failedButLinked": sorted(n for n, v in verdicts.items() if v.startswith("failed") and links.get(n) and dependencies.has_code(folder / n) and n not in misfits),
        "clashOnly": sorted(n for n, v in verdicts.items() if v.startswith("clash") and links.get(n)),
        # A mod with no game code links trivially; what fails it is its data, which the link check does not read.
        "failedDataOnly": sorted(n for n, v in verdicts.items() if v.startswith("failed") and links.get(n) and not dependencies.has_code(folder / n) and n not in misfits),
        "verdicts": verdicts,
        "libraries": libraries,
        "notStarted": left,
        "client": client,
        # What the roadmap's step 51 says would prove the server proof wrong: a mod that loads on the server and links, and fails in the client.
        # A mod whose client start classes need a newer Java (its client entrypoint) is no sign of that.
        "loadedOnServerFailedInClient": sorted(n for n, v in client["verdicts"].items() if v.startswith("failed") and verdicts.get(n) == "loaded" and links.get(n)
                                               and n not in client.get("newerJava", ())),
        "time": {"port": ported_in, **start_times(STARTS)},
    }
    print(f"{'mod':<60} {'server':<12} client")
    for name in sorted(verdicts):
        print(f"{name[:60]:<60} {verdicts[name].split(':')[0]:<12} {client['verdicts'].get(name, 'not started').split(':')[0]}")
    # Each line counts what its side did: a client-only mod is not loaded by a server, so it is counted apart there, and
    # one that fails in the client is named in the client's line whatever the server said.
    client_failed = sorted(n for n, v in client["verdicts"].items() if v.startswith("failed"))
    client_only = sum(1 for v in verdicts.values() if v.startswith("client-only"))
    print(f"client: {client['loaded']}/{client['mods']} load in a real {to} client; failed in the client: {client_failed or 'none'}"
          f"; of them loaded on the server: {summary['loadedOnServerFailedInClient'] or 'none'}")
    print(f"{summary['loaded']}/{summary['mods']} load on a real {to} server" + (f" ({client_only} client-only, not loaded by a server)" if client_only else "")
          + f"; {summary['linked']} link, {summary['linkedAndLoaded']} of them load on the server; failed on the server although they link: {summary['failedButLinked'] or 'none'}"
          + (f"; failed on their data, with no game code: {summary['failedDataOnly']}" if summary["failedDataOnly"] else "")
          + (f"; load alone but fail next to the others: {summary['clashOnly']}" if summary["clashOnly"] else "")
          + (f"; built for a newer Java than {to} runs on, not started: {summary['newerJava']}" if summary["newerJava"] else ""))
    print(time_line(summary["time"]))
    keep_load(summary, out, save)
    return summary


def keep_load(summary, out, save):
    """Writes a load summary to --out and, with --save, to its receipt in bench/exposure/."""
    for path in [p for p in (out, board.receipt("load", summary["loader"], summary["from"], summary["to"]) if save else None) if p]:
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

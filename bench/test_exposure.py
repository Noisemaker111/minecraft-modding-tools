"""The port scoreboard (bench/exposure.py) counts what users would see.
- Its shared cache (~/.mmt/exposure) is safe for worktrees that run at once: entries are keyed by the content of the
  tools that build them, and a finished file is moved into place whole, the first one kept. Before, entries were keyed
  by file times, and one worktree's newer tools wiped a folder another was reading (#126).
- A copy of a platform module (a Fabric API module) nested in a mod is not what runs: Fabric Loader keeps the newest copy
  of a mod id, the platform's. What only that copy references does not hold the mod back; a copy of a module the platform
  no longer has does.
Run: python bench/test_exposure.py"""
import io
import hashlib
import json
import os
import shutil
import zipfile
import sys
import tempfile
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import exposure as E  # noqa: E402
import portboard.board  # noqa: E402
import portboard.cachekeys  # noqa: E402
import portboard.dependencies  # noqa: E402
import portboard.gameload  # noqa: E402
import portboard.gamereleases  # noqa: E402
import portboard.javalevels  # noqa: E402
import portboard.mainrecord  # noqa: E402
import portboard.modrinth  # noqa: E402
import portboard.portcheck  # noqa: E402
import portboard.porter  # noqa: E402
import portboard.settings  # noqa: E402

checks = 0


def check(ok, label):
    global checks
    if not ok:
        raise AssertionError(label)
    checks += 1


# The sweep's plan: an --only naming a whole update picks it, and one that names nothing stops the sweep, where it ran no
# job and printed "0 of 0" as if that were a result.
def plan(only):
    out, argv = io.StringIO(), sys.argv
    sys.argv, sys.stdout = ["exposure.py", "updates", "--only", only], out
    try:
        E.main()
        return json.loads(out.getvalue())
    except SystemExit as stop:
        return str(stop)
    finally:
        sys.argv, sys.stdout = argv, sys.__stdout__


newest = portboard.gamereleases.updates("fabric")[-1]
check(plan(f"fabric:{newest[0]}:{newest[1]}") == [f"fabric:{newest[0]}:{newest[1]}"], "--only LOADER:FROM:TO picks that update")
check(f"fabric:{newest[0]}:{newest[1]}" in plan(f"fabric:{newest[0]}"), "--only LOADER:FROM picks the updates from it")
check("names no update: fabric:1.20.1:99.9" in plan("fabric:1.20.1:99.9"), f"--only naming no update stops: {plan('fabric:1.20.1:99.9')}")

# The sweep's jobs: every pair runs() lists is in exactly one job, a job's targets are one release's ports in one
# direction (so JarPort ports the steps they share once), and --only keeps only the pairs it names.
for lo in portboard.settings.FIRST:
    jobs = portboard.board.plan(lo)
    pairs = [(frm, to) for _, frm, tos in jobs for to in tos]
    check(sorted(pairs) == sorted(portboard.gamereleases.runs(lo)) and len(pairs) == len(set(pairs)), f"{lo}: every pair runs in one job")
    check(all(len({portboard.gamereleases.vkey(to) > portboard.gamereleases.vkey(frm) for to in tos}) == 1 for _, frm, tos in jobs), f"{lo}: a job's targets lie one way from its release")
    check(len(jobs) < len(pairs), f"{lo}: ports from one release share jobs ({len(jobs)} jobs for {len(pairs)} pairs)")
    frm, to = portboard.gamereleases.runs(lo)[-1]
    check([(f, t) for _, f, tos in portboard.board.plan(lo, {f"{lo}:{frm}:{to}"}) for t in tos] == [(frm, to)], f"{lo}: --only LOADER:FROM:TO runs that pair alone")

REPO, EXPOSURE_HOME = portboard.settings.ROOT, portboard.settings.HOME

with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    # Two worktrees: the same tools, then one with an edited GameDiff.
    for tree in ("a", "b"):
        for name in ("port/learn/GameDiff.java", "port/names/VersionMap.java", "port/jar/JarPort.java", "cli/Json.java", "cli/Route.java", "cli/Versions.java"):
            (root / tree / "tools" / name).parent.mkdir(parents=True, exist_ok=True)
            (root / tree / "tools" / name).write_text(f"// {name}\n", encoding="utf-8")
    portboard.settings.HOME = root / "home"

    def key_in(tree):
        portboard.settings.ROOT = root / tree
        return portboard.cachekeys.tools_key(*portboard.settings.MAP_SOURCES)

    check(key_in("a") == key_in("b"), "worktrees with the same tools share entries")
    time.sleep(0.01)
    os.utime(root / "a" / "tools" / "port" / "learn" / "GameDiff.java")  # a newer file time alone changes nothing
    check(key_in("a") == key_in("b"), "a file touched without a change keeps its entries")
    (root / "b" / "tools" / "port" / "jar" / "JarPort.java").write_text("// JarPort, edited\n", encoding="utf-8")
    check(key_in("a") == key_in("b"), "a route map's entries do not change with the jar porter")
    (root / "b" / "tools" / "port" / "learn" / "GameDiff.java").write_text("// GameDiff, edited\n", encoding="utf-8")
    check(key_in("a") != key_in("b"), "worktrees with other tools get entries of their own")
    edited = key_in("b")
    (root / "b" / "tools" / "port" / "learn" / "LearnNew.java").write_text("// a learner file nothing lists\n", encoding="utf-8")
    check(key_in("b") != edited, "a file added under tools/port/learn is one of the learner's sources by being there")

    portboard.settings.ROOT = root / "a"
    first = portboard.cachekeys.keyed("maps", *portboard.settings.MAP_SOURCES)
    (first / "neoforge-1.0-2.0.json").write_text("{}", encoding="utf-8")
    portboard.settings.ROOT = root / "b"
    second = portboard.cachekeys.keyed("maps", *portboard.settings.MAP_SOURCES)
    check(first != second and (first / "neoforge-1.0-2.0.json").is_file(), "making one worktree's entry leaves the other's in place")
    # A key no run has used for over a day is removed when a new one is made; a fresh one is kept.
    stale = first / "used"
    os.utime(stale, (time.time() - 2 * 86400, time.time() - 2 * 86400))
    (root / "b" / "tools" / "port" / "names" / "VersionMap.java").write_text("// VersionMap, edited\n", encoding="utf-8")
    third = portboard.cachekeys.keyed("maps", *portboard.settings.MAP_SOURCES)
    check(not first.exists() and second.exists() and third.exists(), "only a key unused for a day is cleared")

    # Runs beside each other finishing the same file: every reader sees a whole file, and the first one is kept.
    target = third / "same.json"
    seen, start = [], threading.Barrier(8)

    def writer(i):
        part = portboard.cachekeys.part_of(target)
        part.write_text(f'{{"writer": {i}, "pad": "{"x" * 200000}"}}', encoding="utf-8")
        start.wait()
        portboard.cachekeys.publish(part, target)
        seen.append(target.read_text(encoding="utf-8"))

    threads = [threading.Thread(target=writer, args=(i,)) for i in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    check(len(seen) == 8 and all(s.endswith('"}') for s in seen), "no reader sees half a file")
    check(len(set(seen)) == 1, "one writer's file is kept and the others are dropped")
    check(not list(third.glob("*.part")), "no part file is left behind")
portboard.settings.ROOT, portboard.settings.HOME = REPO, EXPOSURE_HOME


def jar(files):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as z:
        for name, data in files.items():
            z.writestr(name, data)
    return buffer.getvalue()


def loader_class(name, returns):
    """A class file with a field, a long constant (two pool slots) and name(): returning the constant `returns`, or, when
    it is None, a value it reads from a field."""
    import struct

    def utf(s):
        b = s.encode()
        return b"\x01" + struct.pack(">H", len(b)) + b
    pool = [utf(name), b"\x07\x00\x01", utf("java/lang/Object"), b"\x07\x00\x03", utf("name"), utf("()Ljava/lang/String;"),
            utf("Code"), utf(returns or "unused"), b"\x08\x00\x08", b"\x05" + struct.pack(">q", 7), utf("f"), utf("Ljava/lang/String;")]
    count = len(pool) + 2  # the long's second slot, and slot 0
    code = b"\x12\x09\xb0" if returns else b"\x2a\xb4\x00\x01\xb0"
    body = struct.pack(">HHI", 1, 1, len(code)) + code + struct.pack(">HH", 0, 0)
    method = struct.pack(">HHHH", 1, 5, 6, 1) + struct.pack(">HI", 7, len(body)) + body
    field = struct.pack(">HHHH", 2, 11, 12, 0)
    return (b"\xca\xfe\xba\xbe" + struct.pack(">HHH", 0, 61, count) + b"".join(pool)
            + struct.pack(">HHHH", 0x21, 2, 4, 0) + struct.pack(">H", 1) + field + struct.pack(">H", 1) + method + struct.pack(">H", 0))


def module(mod_id):
    return jar({"fabric.mod.json": json.dumps({"schemaVersion": 1, "id": mod_id, "version": "1"})})


with tempfile.TemporaryDirectory() as tmp:
    mod = Path(tmp) / "m.jar"
    mod.write_bytes(jar({"fabric.mod.json": json.dumps({"id": "m", "jars": [{"file": "META-INF/jars/api-base-1.jar"}]}),
                         "META-INF/jars/api-base-1.jar": module("fabric-api-base"),
                         "META-INF/jars/old-module-1.jar": module("fabric-gone-v1")}))
    report = {"linked": 0, "mods": [
        {"mod": "m.jar", "silent": [], "unlinked": [
            "m.jar!api-base-1.jar: class gone: net.fabricmc.fabric.impl.base.Phase (in net.fabricmc.fabric.impl.base.Event)",
            "m.jar!old-module-1.jar: call: net.minecraft.Gone.run()V"]},
        {"mod": "n.jar", "silent": [], "unlinked": ["n.jar: call: net.minecraft.Gone.run()V (in n.Main)"]},
        {"mod": "o.jar", "silent": [], "unlinked": []}],
        "unlinkedByMods": []}
    out = portboard.javalevels.without_platform_copies(report, {"fabric-api-base", "fabric-api"}, {"m.jar": mod})
    m = out["mods"][0]["unlinked"]
    check(len(m) == 1 and "old-module" in m[0], f"a nested copy of a platform module is not counted; one of a module the platform lacks is: {m}")
    check(out["unlinkedByMods"] == [{"member": "call: net.minecraft.Gone.run()V", "mods": 2}] and out["linked"] == 1,
          f"each reference counts once a mod and the mods that link are counted again: {out['unlinkedByMods']} {out['linked']}")
    check(portboard.javalevels.nested_ids(mod.read_bytes()) == {"m": "", "fabric-api-base": "api-base-1.jar", "fabric-gone-v1": "old-module-1.jar"},
          "every id in a jar and its nested jars is read with the nested jar's name")


with tempfile.TemporaryDirectory() as tmp:
    capes = Path(tmp) / "capes.jar"
    capes.write_bytes(jar({"fabric.mod.json": json.dumps({"id": "capes", "depends": {"fabricloader": "*", "fabric-api": "*", "minecraft": "1.19.4"}})}))
    other = Path(tmp) / "other.jar"
    other.write_bytes(jar({"fabric.mod.json": json.dumps({"id": "o", "depends": {"cloth-config": "*"}})}))
    fa = lambda jar: portboard.dependencies.unmet(portboard.dependencies.needs(jar, "fabric"), {"fabric-api", "fabric-api-base"})  # noqa: E731
    check(fa(capes) == set() and portboard.dependencies.needs(capes, "fabric") == {"fabric-api"} and fa(other) == {"cloth-config"},
          "a mod that needs only Fabric API is started with the platform; one that needs another mod waits for it")
    kotlin = Path(tmp) / "kt.jar"
    kotlin.write_bytes(jar({"fabric.mod.json": json.dumps({"id": "kt", "depends": {"fabric-language-kotlin": "*"},
                                                           "entrypoints": {"main": [{"adapter": "kotlin", "value": "a.B"}]}})}))
    flk = Path(tmp) / "flk.jar"
    flk.write_bytes(jar({"fabric.mod.json": json.dumps({"id": "fabric-language-kotlin", "languageAdapters": {"kotlin": "a.Adapter"}})}))
    check(portboard.dependencies.unmet(portboard.dependencies.needs(kotlin, "fabric"), portboard.dependencies.provides(flk, "fabric")) == set() and portboard.dependencies.unmet(portboard.dependencies.needs(kotlin, "fabric"), {"fabric-language-kotlin"}) == {"adapter:kotlin"},
          "a mod whose language adapter comes with a mod started beside it is started with it")
    # NeoForge: a mod written for a language loader that comes in a library (nested several jars deep, with no metadata
    # of its own at the top) and a required library mod; a jar made for both loaders is read as the pack's loader.
    toml = lambda mod_id, loader="javafml", deps=(): ('modLoader="%s"\n[[mods]]\nmodId="%s"\n' % (loader, mod_id)  # noqa: E731
                                                     + "".join('[[dependencies.%s]]\nmodId="%s"\ntype="required"\n' % (mod_id, d) for d in deps))
    kt_mod = Path(tmp) / "ktmod.jar"
    kt_mod.write_bytes(jar({"META-INF/neoforge.mods.toml": toml("ipn", "kotlinlang", ["neoforge", "minecraft", "libipn"])}))
    lang = Path(tmp) / "lang-all.jar"
    lang.write_bytes(jar({"META-INF/jarjar/lang.jar": jar({"META-INF/services/net.neoforged.neoforgespi.language.IModLanguageLoader": "a.L"}),
                          "META-INF/jarjar/mod.jar": jar({"META-INF/neoforge.mods.toml": toml("kotlinlangmod", "kotlinlang")})}))
    lib = Path(tmp) / "libipn.jar"
    lib.write_bytes(jar({"META-INF/neoforge.mods.toml": toml("libipn")}))
    both = Path(tmp) / "both.jar"
    both.write_bytes(jar({"fabric.mod.json": json.dumps({"id": "b", "depends": {"fabric-api": "*"}}), "META-INF/neoforge.mods.toml": toml("b", "lowcodefml", ["neoforge"])}))
    check(portboard.dependencies.needs(kt_mod, "neoforge") == {"loader:kotlinlang", "libipn"} and portboard.dependencies.needs(lang, "neoforge") == set() and "loader:*" in portboard.dependencies.provides(lang, "neoforge"),
          "a NeoForge mod needs its library mods and its language loader; a library with only nested metadata needs nothing")
    check(portboard.dependencies.unmet(portboard.dependencies.needs(kt_mod, "neoforge"), portboard.dependencies.provides(lang, "neoforge")) == {"libipn"}
          and portboard.dependencies.unmet(portboard.dependencies.needs(kt_mod, "neoforge"), portboard.dependencies.provides(lang, "neoforge") | portboard.dependencies.provides(lib, "neoforge")) == set(),
          "the language loader is answered by the jar that carries one, the library by its mod id")
    # Two Kotlin language loaders, each registered under its own name: a mod written for one does not start on the other
    # (veinminer asks for KotlinLangForge's klf; Kotlin for Forge registers kotlinforforge).
    service = "META-INF/services/net.neoforged.neoforgespi.language.IModLanguageLoader"
    kff = Path(tmp) / "kff-all.jar"
    kff.write_bytes(jar({"META-INF/jarjar/lang.jar": jar({service: "# loader\na.Kff\n", "a/Kff.class": loader_class("a/Kff", "kotlinforforge")})}))
    klf = Path(tmp) / "klf.jar"
    klf.write_bytes(jar({"META-INF/neoforge.mods.toml": toml("klf"), service: "b.Klf", "b/Klf.class": loader_class("b/Klf", "klf")}))
    unnamed = Path(tmp) / "unnamed.jar"
    unnamed.write_bytes(jar({service: "c.L", "c/L.class": loader_class("c/L", None)}))
    vein = Path(tmp) / "vein.jar"
    vein.write_bytes(jar({"META-INF/neoforge.mods.toml": toml("veinminer", "klf", ["neoforge"])}))
    check(portboard.dependencies.provides(kff, "neoforge") == {"loader:kotlinforforge"} and portboard.dependencies.provides(klf, "neoforge") == {"klf", "loader:klf"}
          and portboard.dependencies.provides(unnamed, "neoforge") == {"loader:*"},
          "a language loader is known by the name its name() returns, read from its class; one that computes it is any name")
    check(portboard.dependencies.unmet(portboard.dependencies.needs(vein, "neoforge"), portboard.dependencies.provides(kff, "neoforge")) == {"loader:klf"}
          and portboard.dependencies.unmet(portboard.dependencies.needs(vein, "neoforge"), portboard.dependencies.provides(klf, "neoforge")) == set()
          and portboard.dependencies.unmet(portboard.dependencies.needs(kt_mod, "neoforge"), portboard.dependencies.provides(kff, "neoforge") | {"libipn"}) == {"loader:kotlinlang"},
          "a mod waits for the language loader it names; another language's loader does not start it")
    old_service = "META-INF/services/net.neoforged.neoforgespi.language.IModLanguageProvider"
    old_only = Path(tmp) / "old-only.jar"
    old_only.write_bytes(jar({old_service: "b.Klf", "b/Klf.class": loader_class("b/Klf", "klf")}))
    check(portboard.dependencies.provides(old_only, "neoforge", "1.20.4") == {"loader:klf"} and portboard.dependencies.provides(old_only, "neoforge", "1.20.5") == set()
          and portboard.dependencies.provides(old_only, "neoforge", "1.21") == set() and portboard.dependencies.provides(klf, "neoforge", "1.20.4") == {"klf"}
          and portboard.dependencies.provides(klf, "neoforge", "1.20.6") == {"klf", "loader:klf"} and portboard.dependencies.provides(klf, "neoforge", "26.3") == {"klf", "loader:klf"},
          "a language loader counts on the releases whose loader reads its service: IModLanguageProvider up to 1.20.4 (FML 2), IModLanguageLoader from 1.20.5 (FML 3)")
    check(portboard.dependencies.needs(both, "neoforge") == set() and portboard.dependencies.needs(both, "fabric") == {"fabric-api"}, "a jar for both loaders is read as the pack's loader")
    check(portboard.dependencies.needs(Path(tmp) / "other.jar", "neoforge") is None, "a jar with no metadata for the loader is nothing the loader starts")
    # The libraries come from what each mod's Modrinth version lists as required, and from what those list in turn; the
    # release for the new version first, the old one (ported) when the project has none.
    folder = Path(tmp) / "top"
    folder.mkdir()
    (folder / "ipn--ktmod.jar").write_bytes(kt_mod.read_bytes())
    (folder / "lone--lone.jar").write_bytes(jar({"META-INF/neoforge.mods.toml": toml("lone", deps=["nowhere"])}))
    pins = [{"slug": "ipn", "file": "ktmod.jar", "project": "P-ipn", "version": "v-ipn"}, {"slug": "lone", "file": "lone.jar", "project": "P-lone", "version": "v-lone"}]
    listed = {"v-ipn": ["P-libipn"], "v-libipn": ["P-lang"], "v-lone": []}
    files = {("P-libipn", "2.0"): (lib, "v-libipn"), ("P-lang", "1.0"): (lang, "v-lang")}
    real = portboard.dependencies.required_projects, portboard.dependencies.library
    real_api, real_home = portboard.modrinth.api, portboard.settings.HOME
    searched = []
    portboard.modrinth.api = lambda path, params=None: searched.append(params["query"]) or {"hits": []}  # noqa: E731
    portboard.settings.HOME = Path(tmp) / "home"
    portboard.dependencies.required_projects, portboard.dependencies.library = (lambda v: listed.get(v, [])), (lambda project, loader, version: files.get((project, version)))
    try:
        chosen, ready, to_port, left = portboard.dependencies.choose("neoforge", "1.0", "2.0", ["ipn--ktmod.jar", "lone--lone.jar"], folder, pins, set())
    finally:
        portboard.dependencies.required_projects, portboard.dependencies.library = real
    check([j.name for j in chosen] == ["ipn--ktmod.jar"] and list(ready) == ["libipn.jar"] and list(to_port) == ["lang-all.jar"],
          f"a mod is started with the libraries it needs, each library's own found through it: {chosen} {ready} {to_port}")
    check(left == {"lone--lone.jar": ["nowhere"]}, f"a mod whose need nothing answers is named with what it needs: {left}")
    # A Fabric mod's Modrinth version lists Fabric API, which the pack already runs as its platform: a second copy as a
    # library stops Fabric ("Mod id fabric-api is in 2 jars"), so only its other library is added.
    api = Path(tmp) / "fabric-api.jar"
    api.write_bytes(jar({"fabric.mod.json": json.dumps({"id": "fabric-api"}), "META-INF/jars/base.jar": module("fabric-api-base")}))
    cloth = Path(tmp) / "cloth.jar"
    cloth.write_bytes(module("cloth-config"))
    (folder / "hud--hud.jar").write_bytes(jar({"fabric.mod.json": json.dumps({"id": "hud", "depends": {"fabric-api": "*", "cloth-config": "*"}})}))
    pins = [{"slug": "hud", "file": "hud.jar", "project": "P-hud", "version": "v-hud"}]
    listed = {"v-hud": ["P-api", "P-cloth"]}
    files = {("P-api", "2.0"): (api, "v-api"), ("P-cloth", "2.0"): (cloth, "v-cloth")}
    portboard.dependencies.required_projects, portboard.dependencies.library = (lambda v: listed.get(v, [])), (lambda project, loader, version: files.get((project, version)))
    try:
        chosen, ready, to_port, left = portboard.dependencies.choose("fabric", "1.0", "2.0", ["hud--hud.jar"], folder, pins, {"fabric-api", "fabric-api-base"})
    finally:
        portboard.dependencies.required_projects, portboard.dependencies.library = real
    check([j.name for j in chosen] == ["hud--hud.jar"] and list(ready) == ["cloth.jar"] and not to_port,
          f"a library the platform already is is not added again: {chosen} {ready} {to_port}")
    # A mod whose metadata asks for nothing still gets what its Modrinth version lists as required: REI checks for
    # Architectury and Cloth Config itself at start and stops the game without them.
    (folder / "rei--rei.jar").write_bytes(jar({"fabric.mod.json": json.dumps({"id": "rei", "depends": {"minecraft": "*"}})}))
    pins = [{"slug": "rei", "file": "rei.jar", "project": "P-rei", "version": "v-rei"}]
    listed = {"v-rei": ["P-cloth"]}
    portboard.dependencies.required_projects, portboard.dependencies.library = (lambda v: listed.get(v, [])), (lambda project, loader, version: files.get((project, version)))
    try:
        chosen, ready, to_port, left = portboard.dependencies.choose("fabric", "1.0", "2.0", ["rei--rei.jar"], folder, pins, {"fabric-api", "fabric-api-base"})
    finally:
        portboard.dependencies.required_projects, portboard.dependencies.library = real
    check([j.name for j in chosen] == ["rei--rei.jar"] and list(ready) == ["cloth.jar"],
          f"a mod that names no library in its metadata gets the ones its Modrinth version requires: {chosen} {ready}")
    # A library's file for the new release can answer under another id than the one the mod asks (Balm 6 for 1.19.4 is
    # balm-fabric, Balm 7, which Waystones asks for as balm, is balm): its old file is ported instead.
    (folder / "stones--stones.jar").write_bytes(jar({"fabric.mod.json": json.dumps({"id": "stones", "depends": {"balm": ">=7.0.0"}})}))
    balm_new, balm_old = Path(tmp) / "balm-new.jar", Path(tmp) / "balm-old.jar"
    balm_new.write_bytes(jar({"fabric.mod.json": json.dumps({"id": "balm-fabric", "version": "6.0.2"})}))
    balm_old.write_bytes(jar({"fabric.mod.json": json.dumps({"id": "balm", "version": "7.3.44"})}))
    pins = [{"slug": "stones", "file": "stones.jar", "project": "P-stones", "version": "v-stones"}]
    listed = {"v-stones": ["P-balm"]}
    balm_files = {("P-balm", "2.0"): (balm_new, "v-bn"), ("P-balm", "1.0"): (balm_old, "v-bo")}
    portboard.dependencies.required_projects, portboard.dependencies.library = (lambda v: listed.get(v, [])), (lambda project, loader, version: balm_files.get((project, version)))
    try:
        chosen, ready, to_port, left = portboard.dependencies.choose("fabric", "1.0", "2.0", ["stones--stones.jar"], folder, pins, {"fabric-api", "fabric-api-base"})
    finally:
        portboard.dependencies.required_projects, portboard.dependencies.library = real
    check([j.name for j in chosen] == ["stones--stones.jar"] and not ready and list(to_port) == ["balm-old.jar"],
          f"a library whose new file drops the id a mod asks is ported from its old file: {chosen} {ready} {to_port} {left}")
    # On a port back the new release's file is the older version: REI checks for Cloth Config 11 itself, and 1.19.4's is
    # 10, so the one REI was built with is ported.
    cloth_ten, cloth_eleven = Path(tmp) / "cloth-10.jar", Path(tmp) / "cloth-11.jar"
    cloth_ten.write_bytes(jar({"fabric.mod.json": json.dumps({"id": "cloth-config", "version": "10.1.135+fabric"})}))
    cloth_eleven.write_bytes(jar({"fabric.mod.json": json.dumps({"id": "cloth-config", "version": "11.1.136+fabric"})}))
    pins = [{"slug": "rei", "file": "rei.jar", "project": "P-rei", "version": "v-rei"}]
    listed = {"v-rei": ["P-cloth"]}
    cloth_files = {("P-cloth", "2.0"): (cloth_ten, "v-c10"), ("P-cloth", "1.0"): (cloth_eleven, "v-c11")}
    portboard.dependencies.required_projects, portboard.dependencies.library = (lambda v: listed.get(v, [])), (lambda project, loader, version: cloth_files.get((project, version)))
    try:
        chosen, ready, to_port, left = portboard.dependencies.choose("fabric", "1.0", "2.0", ["rei--rei.jar"], folder, pins, {"fabric-api", "fabric-api-base"})
    finally:
        portboard.dependencies.required_projects, portboard.dependencies.library = real
    check(not ready and list(to_port) == ["cloth-11.jar"],
          f"a library whose file for the new release is older than the mod's is ported from the mod's release: {ready} {to_port}")
    # A mod that nests a copy of one platform module (CraftPresence and MaLiLib carry fabric-resource-loader-v0) is a mod
    # and starts; a top mod that is the platform itself is left out with that reason, never silently.
    (folder / "presence--presence.jar").write_bytes(jar({"fabric.mod.json": json.dumps({"id": "presence"}), "META-INF/jars/base.jar": module("fabric-api-base")}))
    (folder / "api--fabric-api.jar").write_bytes(api.read_bytes())
    pins = [{"slug": "presence", "file": "presence.jar", "project": "P-presence", "version": "v-presence"},
            {"slug": "api", "file": "fabric-api.jar", "project": "P-api", "version": "v-api"}]
    portboard.dependencies.required_projects, portboard.dependencies.library = (lambda v: []), (lambda project, loader, version: None)
    try:
        chosen, ready, to_port, left = portboard.dependencies.choose("fabric", "1.0", "2.0", ["presence--presence.jar", "api--fabric-api.jar", "gone--gone.jar"], folder, pins, {"fabric-api", "fabric-api-base"})
    finally:
        portboard.dependencies.required_projects, portboard.dependencies.library = real
    check([j.name for j in chosen] == ["presence--presence.jar"] and sorted(left) == ["api--fabric-api.jar", "gone--gone.jar"]
          and "platform itself" in left["api--fabric-api.jar"][0] and "not in" in left["gone--gone.jar"][0],
          f"a mod nesting a platform module starts; the platform itself and a missing jar are each named with why: {chosen} {left}")
    # A library's release for the new version outside the range the mod asks for is not taken: its old release is ported.
    check(portboard.dependencies.in_range("21.1.9", "[21.1,21.2)") and not portboard.dependencies.in_range("21.2.3", "[21.1,21.2)") and portboard.dependencies.in_range("1.3", "1.2") and not portboard.dependencies.in_range("1.21.2", "[1.21.1]"),
          "versions are read against Maven ranges as NeoForge reads them")
    check(not portboard.dependencies.in_range("0.6.0-beta.5", "[0.6.0,)") and portboard.dependencies.in_range("0.6.0", "[0.6.0,)") and portboard.dependencies.in_range("0.6.1-beta.1", "[0.6.0,)")
          and portboard.dependencies.in_range("1.0.0", "[1.0,)") and not portboard.dependencies.in_range("1.0-rc1", "1.0"),
          "a prerelease is below the release it leads to, as NeoForge orders versions")
    strict = Path(tmp) / "strict.jar"
    strict.write_bytes(jar({"META-INF/neoforge.mods.toml": toml("strict") + '[[dependencies.strict]]\nmodId="libipn"\ntype="required"\nversionRange="[1.0,2.0)"\n'}))
    (folder / "strict--strict.jar").write_bytes(strict.read_bytes())
    lib_new = Path(tmp) / "libipn-2.jar"
    lib_new.write_bytes(jar({"META-INF/neoforge.mods.toml": 'modLoader="javafml"\n[[mods]]\nmodId="libipn"\nversion="2.0.1"\n'}))
    lib_old = Path(tmp) / "libipn-1.jar"
    lib_old.write_bytes(jar({"META-INF/neoforge.mods.toml": 'modLoader="javafml"\n[[mods]]\nmodId="libipn"\nversion="1.4"\n'}))
    pins.append({"slug": "strict", "file": "strict.jar", "project": "P-strict", "version": "v-strict"})
    listed["v-strict"] = ["P-libipn"]
    files = {("P-libipn", "2.0"): (lib_new, "v-libipn"), ("P-libipn", "1.0"): (lib_old, "v-libipn-old")}
    portboard.dependencies.required_projects, portboard.dependencies.library = (lambda v: listed.get(v, [])), (lambda project, loader, version: files.get((project, version)))
    try:
        chosen, ready, to_port, left = portboard.dependencies.choose("neoforge", "1.0", "2.0", ["strict--strict.jar"], folder, pins, set())
    finally:
        portboard.dependencies.required_projects, portboard.dependencies.library = real
    check([j.name for j in chosen] == ["strict--strict.jar"] and not ready and list(to_port) == ["libipn-1.jar"],
          f"a library outside the mod's range for the new version is ported from the old one: {ready} {to_port}")
    # The same for Fabric, whose depends are predicates (">=3.6.6"), compared without build metadata (3.1.1+1.19.4 is 3.1.1):
    # Zoomify for 1.20.1 asks YACL >=3.6.6, and YACL's 1.19.4 release is 3.1.1, so the loader would refuse to start.
    check(portboard.dependencies.fabric_in("7.2.1", ">=7.2") and not portboard.dependencies.fabric_in("3.1.1+1.19.4", ">=3.6.6")
          and portboard.dependencies.fabric_in("1.5", "^1.2") and not portboard.dependencies.fabric_in("2.0", "^1.2")
          and not portboard.dependencies.fabric_in("1.3.0", "~1.2") and portboard.dependencies.fabric_in("1.2.4", "1.2.x")
          and not portboard.dependencies.fabric_in("1.2.1", "1.2") and portboard.dependencies.fabric_in("0.5", [">=1", "<0.6"])
          and not portboard.dependencies.fabric_in("5.0", ">=4 <5") and not portboard.dependencies.fabric_in("1.0.0-beta.2", ">=1.0.0"),
          "versions are read against Fabric's depends predicates as Fabric Loader reads them")
    zoom = Path(tmp) / "zoom.jar"
    zoom.write_bytes(jar({"fabric.mod.json": json.dumps({"schemaVersion": 1, "id": "zoom", "version": "2.15", "depends": {"minecraft": "*", "yacl": ">=3.6.6"}})}))
    (folder / "zoom--zoom.jar").write_bytes(zoom.read_bytes())
    yacl_new, yacl_old = Path(tmp) / "yacl-new.jar", Path(tmp) / "yacl-old.jar"
    yacl_new.write_bytes(jar({"fabric.mod.json": json.dumps({"schemaVersion": 1, "id": "yacl", "version": "3.1.1+1.19.4"})}))
    yacl_old.write_bytes(jar({"fabric.mod.json": json.dumps({"schemaVersion": 1, "id": "yacl", "version": "3.6.6+1.20.1"})}))
    pins.append({"slug": "zoom", "file": "zoom.jar", "project": "P-zoom", "version": "v-zoom"})
    listed["v-zoom"] = ["P-yacl"]
    yacls = {("P-yacl", "2.0"): (yacl_new, "v-yacl"), ("P-yacl", "1.0"): (yacl_old, "v-yacl-old")}
    portboard.dependencies.required_projects, portboard.dependencies.library = (lambda v: listed.get(v, [])), (lambda project, loader, version: yacls.get((project, version)))
    try:
        chosen, ready, to_port, left = portboard.dependencies.choose("fabric", "1.0", "2.0", ["zoom--zoom.jar"], folder, pins, set())
    finally:
        portboard.dependencies.required_projects, portboard.dependencies.library = real
    check([j.name for j in chosen] == ["zoom--zoom.jar"] and not ready and list(to_port) == ["yacl-old.jar"],
          f"a Fabric library older than the mod's depends for the new version is ported from the old one: {ready} {to_port} {left}")
    # A mod may ask a library by an id the library "provides" (YUNG's mods ask cloth-config2 of Cloth Config, whose own id
    # is cloth-config): its version is checked under that id too, or the too-old release is taken and the loader refuses.
    zoom.write_bytes(jar({"fabric.mod.json": json.dumps({"schemaVersion": 1, "id": "zoom", "version": "2.15", "depends": {"minecraft": "*", "yacl2": ">=3.6.6"}})}))
    (folder / "zoom--zoom.jar").write_bytes(zoom.read_bytes())
    yacl_new.write_bytes(jar({"fabric.mod.json": json.dumps({"schemaVersion": 1, "id": "yacl", "version": "3.1.1+1.19.4", "provides": ["yacl2"]})}))
    yacl_old.write_bytes(jar({"fabric.mod.json": json.dumps({"schemaVersion": 1, "id": "yacl", "version": "3.6.6+1.20.1", "provides": ["yacl2"]})}))
    portboard.dependencies.required_projects, portboard.dependencies.library = (lambda v: listed.get(v, [])), (lambda project, loader, version: yacls.get((project, version)))
    try:
        chosen, ready, to_port, left = portboard.dependencies.choose("fabric", "1.0", "2.0", ["zoom--zoom.jar"], folder, pins, set())
    finally:
        portboard.dependencies.required_projects, portboard.dependencies.library = real
    check([j.name for j in chosen] == ["zoom--zoom.jar"] and not ready and list(to_port) == ["yacl-old.jar"],
          f"a library asked by an id it provides is checked under that id: {ready} {to_port} {left}")
    # Two mods needing the same library: the release for the new version has to fit both ranges, not the last one read.
    loose = Path(tmp) / "loose.jar"
    loose.write_bytes(jar({"META-INF/neoforge.mods.toml": toml("loose") + '[[dependencies.loose]]\nmodId="libipn"\ntype="required"\nversionRange="[1.0,)"\n'}))
    (folder / "loose--loose.jar").write_bytes(loose.read_bytes())
    pins.append({"slug": "loose", "file": "loose.jar", "project": "P-loose", "version": "v-loose"})
    listed["v-loose"] = ["P-libipn"]
    portboard.dependencies.required_projects, portboard.dependencies.library = (lambda v: listed.get(v, [])), (lambda project, loader, version: files.get((project, version)))
    try:
        chosen, ready, to_port, left = portboard.dependencies.choose("neoforge", "1.0", "2.0", ["strict--strict.jar", "loose--loose.jar"], folder, pins, set())
    finally:
        portboard.dependencies.required_projects, portboard.dependencies.library = real
    check(not ready and list(to_port) == ["libipn-1.jar"],
          f"a library two mods need fits every range they ask for: {ready} {to_port}")
    check(searched == ["nowhere"], f"an id no listed library gives is looked for once, by its words: {searched}")
    # A library the mod's metadata requires that its Modrinth version does not list (Sounds requires YACL's
    # yet_another_config_lib_v3 and lists nothing): the project is found for the id, among the old release's pinned mods
    # or by Modrinth's search for its words, and taken only when the file it would install declares the id. A search hit
    # whose file does not (a mod that only names the library) is not added.
    def fabric_mod(mod_id, depends=(), provides=()):
        return jar({"fabric.mod.json": json.dumps({"id": mod_id, "depends": {d: "*" for d in depends}, "provides": list(provides)})})
    (folder / "sound--sound.jar").write_bytes(fabric_mod("sounds", ["fabric-api", "yet_another_config_lib_v3"]))
    (folder / "ui--ui.jar").write_bytes(fabric_mod("ui", ["owo"]))
    (folder / "chat--chat.jar").write_bytes(fabric_mod("chat", ["nolib_v2"]))
    pinned_old = portboard.settings.HOME / "jars" / "fabric-1.0"
    pinned_old.mkdir(parents=True)
    (pinned_old / "yacl--yacl-old.jar").write_bytes(fabric_mod("yet_another_config_lib_v3"))
    yacl_new, owo_new, decoy = Path(tmp) / "yacl-new.jar", Path(tmp) / "owo-new.jar", Path(tmp) / "decoy.jar"
    yacl_new.write_bytes(fabric_mod("yet_another_config_lib_v3", ["owo"]))
    owo_new.write_bytes(fabric_mod("owo-lib", provides=["owo"]))
    decoy.write_bytes(fabric_mod("owo-addon", ["owo"]))
    pins = [{"slug": "sound", "file": "sound.jar", "project": "P-sound", "version": "v-sound"},
            {"slug": "ui", "file": "ui.jar", "project": "P-ui", "version": "v-ui"},
            {"slug": "chat", "file": "chat.jar", "project": "P-chat", "version": "v-chat"},
            {"slug": "yacl", "file": "yacl-old.jar", "project": "P-yacl", "version": "v-yacl-old"}]
    files = {("P-yacl", "2.0"): (yacl_new, "v-yacl"), ("P-owo", "2.0"): (owo_new, "v-owo"), ("P-decoy", "2.0"): (decoy, "v-decoy")}
    hits = {"owo": ["P-decoy", "P-owo"], "nolib": ["P-decoy"]}
    searched.clear()
    portboard.modrinth.api = lambda path, params=None: searched.append(params["query"]) or {"hits": [{"project_id": h} for h in hits.get(params["query"], [])]}  # noqa: E731
    portboard.dependencies.required_projects, portboard.dependencies.library = (lambda v: []), (lambda project, loader, version: files.get((project, version)))
    try:
        chosen, ready, to_port, left = portboard.dependencies.choose("fabric", "1.0", "2.0", ["sound--sound.jar", "ui--ui.jar", "chat--chat.jar"], folder, pins, {"fabric-api"})
    finally:
        portboard.dependencies.required_projects, portboard.dependencies.library = real
        portboard.modrinth.api, portboard.settings.HOME = real_api, real_home
    check(sorted(j.name for j in chosen) == ["sound--sound.jar", "ui--ui.jar"] and sorted(ready) == ["owo-new.jar", "yacl-new.jar"] and not to_port,
          f"a library is found for the id a mod's metadata requires, and the library's own in turn: {chosen} {ready} {to_port}")
    check(left == {"chat--chat.jar": ["nolib_v2"]} and "decoy.jar" not in ready,
          f"a found project whose file does not declare the id is not taken: {left} {ready}")
    check("yet another config lib" not in searched and sorted(searched) == ["nolib", "owo"],
          f"an id a pinned mod of the old release declares is not searched for; a trailing version is not searched: {searched}")

# A start stopped by a mixin that cannot apply names the mod whose mixin config it is; a warning about a client class the
# server lacks, from another mod, does not.
with tempfile.TemporaryDirectory() as tmp:
    mods = Path(tmp) / "mods"
    mods.mkdir()
    for mod_id in ("stats", "hud"):
        (mods / f"{mod_id}.jar").write_bytes(jar({"META-INF/neoforge.mods.toml": f'modLoader="javafml"\n[[mods]]\nmodId="{mod_id}"\n'}))
    (Path(tmp) / "result.json").write_text("{}", encoding="utf-8")
    (Path(tmp) / "3-server-place.log").write_text("\n".join([
        "[main/WARN] [mixin/]: @Mixin target net.minecraft.client.Minecraft was not found hud.mixins.json:client.A from mod hud",
        "Caused by: org.spongepowered.asm.mixin.transformer.throwables.MixinPreProcessorException: Attach error for stats.mixins.json:B from mod stats during activity: [x]",
        "\tat org.spongepowered.asm.mixin.transformer.MixinProcessor.applyMixins(MixinProcessor.java:392) hud.mixins.json:client.A from mod hud"]), encoding="utf-8")
    blamed = portboard.gameload.named_in_errors(Path(tmp) / "result.json", mods, ["hud.jar", "stats.jar"])
check(list(blamed) == ["stats.jar"] and "stats.mixins.json:B" in blamed["stats.jar"], f"the mixin that stopped the start names its mod: {blamed}")

# A log names a mod nested in a library (Forgified Fabric API's data attachment module); the library that ships it is
# the jar to drop, not a start that "named no mod".
with tempfile.TemporaryDirectory() as tmp:
    mods = Path(tmp) / "mods"
    mods.mkdir()
    toml_of = lambda i: f'modLoader="javafml"\n[[mods]]\nmodId="{i}"\n'.encode()  # noqa: E731
    (mods / "top.jar").write_bytes(jar({"META-INF/neoforge.mods.toml": toml_of("top")}))
    (mods / "ffapi.jar").write_bytes(jar({"META-INF/neoforge.mods.toml": toml_of("fabric_api"),
                                          "META-INF/jars/attach.jar": jar({"META-INF/neoforge.mods.toml": toml_of("fabric_data_attachment_api_v1")})}))
    (Path(tmp) / "result.json").write_text("{}", encoding="utf-8")
    (Path(tmp) / "3-server-place.log").write_text(
        "Caused by: org.spongepowered.asm.mixin.throwables.MixinApplyError: Mixin [fabric-data-attachment-api-v1.mixins.json:BaseMappedRegistryAccessor "
        "from mod fabric_data_attachment_api_v1] from phase [DEFAULT] FAILED during APPLY", encoding="utf-8")
    check(portboard.dependencies.mod_ids(mods / "ffapi.jar") == {"fabric_api", "fabric_data_attachment_api_v1"}, "a jar's mod ids include those of the jars it nests")
    blamed = portboard.gameload.named_in_errors(Path(tmp) / "result.json", mods, ["top.jar", "ffapi.jar"])
check(list(blamed) == ["ffapi.jar"], f"a failing nested mod blames the jar that ships it: {blamed}")

# A game proof runs the updates asked for, LOADER:FROM taking the next release; --mods picks pinned mods by slug, and one
# that does not link is left out, as the whole run leaves it out.
check(portboard.gameload.load_plan("neoforge:1.21.1:1.21.2, fabric:1.21.1") == ["neoforge:1.21.1:1.21.2", "fabric:1.21.1:1.21.2"], "an update is LOADER:FROM:TO, or LOADER:FROM for the next release")
for bad in ("forge:1.21.1", "neoforge", "fabric:0.1"):
    try:
        portboard.gameload.load_plan(bad)
        check(False, f"{bad} is refused")
    except SystemExit:
        pass
pinned = [{"slug": "chunky", "file": "Chunky.jar"}, {"slug": "clumps", "file": "Clumps.jar"}, {"slug": "jei", "file": "jei.jar"}]
sys.stdout = io.StringIO()
try:
    picked, skipped = portboard.gameload.only_mods(["chunky--Chunky.jar", "jei--jei.jar"], pinned, ["chunky", "clumps"])
    said = sys.stdout.getvalue()
finally:
    sys.stdout = sys.__stdout__
check(picked == ["chunky--Chunky.jar"] and list(skipped) == ["clumps--Clumps.jar"] and "Clumps.jar: does not link" in said,
      f"--mods starts the asked mods that link and names the rest: {picked} {skipped} {said!r}")
# One proof of several updates asks each for the same slugs: a slug this release does not pin is not started here, with
# why, and the other asked mods still start (fabric 1.20.4 pins fastquit and not sound, and stopped before any game).
sys.stdout = io.StringIO()
try:
    picked, skipped = portboard.gameload.only_mods(["chunky--Chunky.jar"], pinned, ["chunky", "sound"])
finally:
    sys.stdout = sys.__stdout__
check(picked == ["chunky--Chunky.jar"] and skipped == {"sound": ["it is not among this release's pinned mods (bench/exposure/mods/)"]},
      f"a slug another update pins is not started on this one, and the rest start: {picked} {skipped}")
with tempfile.TemporaryDirectory() as tmp:
    real_pins = portboard.settings.PINS
    portboard.settings.PINS = Path(tmp)
    for release, slugs in (("1.0", ["fastquit"]), ("2.0", ["fastquit", "sound"])):
        (portboard.settings.PINS / f"fabric-{release}.json").write_text(json.dumps({"mods": [{"slug": x} for x in slugs]}), encoding="utf-8")
    try:
        check(portboard.gameload.load_plan("fabric:1.0:2.0,fabric:2.0:3.0", ["fastquit", "sound"]) == ["fabric:1.0:2.0", "fabric:2.0:3.0"],
              "a slug one update of a proof pins is asked of each")
        try:
            portboard.gameload.load_plan("fabric:1.0:2.0,fabric:2.0:3.0", ["fastquit", "nonesuch"])
            check(False, "a slug no update pins stops the plan")
        except SystemExit as stop:
            check("nonesuch" in str(stop), f"the plan names the slug no update pins: {stop}")
    finally:
        portboard.settings.PINS = real_pins

# --mods where no asked mod links keeps a receipt with no game verdicts and why each mod was not started, and fails with
# that why: a proof that started no game never passes as one.
with tempfile.TemporaryDirectory() as tmp:
    real = portboard.settings.ROOT, portboard.settings.PINS, portboard.settings.HOME
    portboard.settings.ROOT, portboard.settings.PINS, portboard.settings.HOME = Path(tmp), Path(tmp) / "pins", Path(tmp) / "home"
    portboard.settings.PINS.mkdir()
    (portboard.settings.PINS / "neoforge-9.1.json").write_text(json.dumps({"mods": pinned}), encoding="utf-8")
    report = Path(tmp) / ".runs" / "exposure" / "jars-neoforge-9.1-9.2" / "report.json"
    report.parent.mkdir(parents=True)
    report.write_text(json.dumps({"mods": [{"mod": "chunky--Chunky.jar", "unlinked": []}, {"mod": "clumps--Clumps.jar", "unlinked": ["x"]}]}), encoding="utf-8")
    sys.stdout = io.StringIO()
    stopped = None
    try:
        portboard.gameload.load("neoforge", "9.1", "9.2", False, ["clumps"], Path(tmp) / "out" / "load.json")
    except SystemExit as stop:
        stopped = str(stop)
    finally:
        sys.stdout = sys.__stdout__
        portboard.settings.ROOT, portboard.settings.PINS, portboard.settings.HOME = real
    kept = json.loads((Path(tmp) / "out" / "load.json").read_text(encoding="utf-8"))
check(stopped == "no mod to start: clumps--Clumps.jar: it does not link after the port", f"a proof that starts no game fails with why: {stopped}")
check(kept["mods"] == 0 and kept["verdicts"] == {} and kept["client"]["verdicts"] == {} and kept["scope"] == ["clumps"]
      and kept["notStarted"] == {"clumps--Clumps.jar": ["it does not link after the port"]},
      f"a proof with nothing to start keeps a receipt saying why: {kept}")

# A game proof starts the client beside the server, and no server when every mod to start is client-only by its own
# metadata: fabric 1.18.1 -> 1.18.2 spent 147 s starting a server with neither of its two client-only mods on it. Run
# through load with the game's starts stood in for; a client start that fails still fails the gate.
def proof(mods, client_ok, server_waits_for_client, refused=None):
    """refused: what start-check (stood in for) fails before any start, {side: {jar: why}}."""
    S, G = portboard.settings, portboard.gameload
    refused, asked = refused or {}, []
    stubs = [(portboard.porter, "mojang_names"), (portboard.porter, "jar_port"), (portboard.porter, "fabric_api"), (portboard.dependencies, "choose"),
             (portboard.javalevels, "platform_ids"), (portboard.javalevels, "newer_java"), (G, "pack_check")]
    kept = [(m, n, getattr(m, n)) for m, n in stubs]
    with tempfile.TemporaryDirectory() as tmp:
        real = S.ROOT, S.PINS, S.HOME
        S.ROOT, S.PINS, S.HOME = Path(tmp), Path(tmp) / "pins", Path(tmp) / "home"
        S.PINS.mkdir()
        (S.PINS / "fabric-9.1.json").write_text(json.dumps({"mods": [{"slug": s, "file": f"{s}.jar"} for s in mods]}), encoding="utf-8")
        report = Path(tmp) / ".runs" / "exposure" / "jars-fabric-9.1-9.2" / "report.json"
        report.parent.mkdir(parents=True)
        report.write_text(json.dumps({"mods": [{"mod": f"{s}--{s}.jar", "unlinked": []} for s in mods]}), encoding="utf-8")
        folder = S.HOME / "jars" / "fabric-9.1"
        folder.mkdir(parents=True)
        for s, env in mods.items():
            (folder / f"{s}--{s}.jar").write_bytes(jar({"fabric.mod.json": json.dumps({"id": s, "environment": env})}))
        starts, client_started = [], threading.Event()

        def ported(loader, frm, to, read, out, rep, runtime=False):
            out.mkdir(parents=True, exist_ok=True)
            for j in read:
                shutil.copy(j, out / j.name)
            return {"mods": [{"mod": j.name, "unlinked": []} for j in read]}

        def start(pack, to, loader, side="server"):
            starts.append(side)
            done = G.subprocess.CompletedProcess([], 0, "", "")
            if side == "client":
                client_started.set()
                suspects = [{"jar": n.name, "why": ["crash"]} for n in (pack / "mods").glob("*.jar")]
                return {"result": {"client": {"opened": client_ok, "failure": "Description: Rendering screen"},
                                   "findings": [] if client_ok else [{"kind": "client-start", "severity": "problem", "suspects": suspects}]}}, done
            if server_waits_for_client:
                check(client_started.wait(10), "the client starts while the server runs")
            return {"ok": True, "result": {"game": {"leftOut": {}}}}, done
        portboard.porter.mojang_names = lambda loader, version, jars: (jars, {})
        portboard.porter.jar_port, portboard.porter.fabric_api = ported, (lambda to: None)
        portboard.dependencies.choose = lambda loader, frm, to, linked, out, pins, platform: ([out / n for n in linked], {}, {}, {})
        portboard.javalevels.platform_ids, portboard.javalevels.newer_java, G.pack_check = (lambda loader, to: []), (lambda *a: {}), start
        sys.stdout = io.StringIO()
        try:
            summary = G.load("fabric", "9.1", "9.2", False, sorted(mods), check=lambda pack, to, loader, jars: asked.append(sorted(jars)) or {
                "seconds": 1.0, "receipt": None, "sides": {side: {"failed": dict(refused.get(side, {}))} for side in ("server", "client")}})
        finally:
            sys.stdout = sys.__stdout__
            S.ROOT, S.PINS, S.HOME = real
            for m, n, f in kept:
                setattr(m, n, f)
    check(asked == [sorted(f"{s}--{s}.jar" for s in mods)], f"start-check is asked once, for the mods and not the platform: {asked}")
    return summary, starts


summary, starts = proof({"csl": "client", "jec": "client"}, True, False)
check(starts == ["client"] and all(v.startswith("client-only: its fabric.mod.json says environment client") for v in summary["verdicts"].values())
      and portboard.portcheck.load_gate(summary)[1], f"client-only mods start no server, only the client: {starts} {summary['verdicts']}")
summary, starts = proof({"csl": "client"}, False, False)
check(starts[0] == "client" and "server" not in starts and not portboard.portcheck.load_gate(summary)[1],
      f"a client-only mod that fails in the client still fails the gate with no server started: {starts} {portboard.portcheck.load_gate(summary)[0]}")
summary, starts = proof({"csl": "client", "chunky": "*"}, True, True)
check(sorted(set(starts)) == ["client", "server"] and summary["verdicts"]["chunky--chunky.jar"] == "loaded" and portboard.portcheck.load_gate(summary)[1]
      and summary["time"]["starts"] >= 0, f"with a mod a server runs, the server and the client start side by side: {starts} {summary['verdicts']}")
# A mod start-check fails is not started on that side, and fails the proof with start-check's reason; the other side still starts it.
for side, other in (("server", "client"), ("client", "server")):
    summary, starts = proof({"chunky": "*"}, True, False, {side: {"chunky--chunky.jar": "Mixin refuses its mixin"}})
    said = {"server": summary["verdicts"], "client": summary["client"]["verdicts"]}
    check(starts == [other] and said[side]["chunky--chunky.jar"].startswith("failed: Mixin refuses its mixin (decided by start-check") and said[other]["chunky--chunky.jar"] == "loaded"
          and summary["startCheck"]["failed"][side] == {"chunky--chunky.jar": "Mixin refuses its mixin"} and not portboard.portcheck.load_gate(summary)[1],
          f"a mod start-check fails on the {side} is not started there and fails the proof: {starts} {said}")

# Two game proofs of an update compare by each mod's verdict kind on both sides, over the mods both started.
main_run = {"loader": "neoforge", "from": "1.21.1", "to": "1.21.2", "mods": 3,
            "verdicts": {"a--a.jar": "loaded", "b--b.jar": "failed: its mixin x", "c--c.jar": "client-only: y"},
            "client": {"verdicts": {"a--a.jar": "loaded", "c--c.jar": "failed: z"}}}
branch_run = {"loader": "neoforge", "from": "1.21.1", "to": "1.21.2", "scope": ["a", "b"], "mods": 2,
              "verdicts": {"a--a.jar": "loaded", "b--b.jar": "loaded"}, "client": {"verdicts": {"a--a.jar": "failed: crash", "b--b.jar": "loaded"}}}
lines = portboard.gameload.compare_loads(main_run, branch_run)
check("server: 1/2 the same; loaded 1 -> 2" in lines and "  b--b.jar: failed -> loaded" in lines and "  a--a.jar: loaded -> failed (failed: crash)" in lines
      and "  b--b.jar: not started -> loaded" in lines and not any("c--c.jar" in x for x in lines),
      f"compare names each verdict that moved; a mod a focused run was not asked for is not one: {lines}")
check(portboard.gameload.compare_loads(main_run, main_run)[-1] == "no verdict moved", "a proof against itself moves nothing")
# A full run that no longer starts a mod main loaded (it now stops at the link check) has moved, whatever its count.
full = {"loader": "neoforge", "from": "1.21.1", "to": "1.21.2", "scope": "all", "mods": 2,
        "verdicts": {"a--a.jar": "loaded", "b--b.jar": "failed: its mixin x"}, "client": {"verdicts": {"a--a.jar": "loaded"}},
        "notStarted": {"c--c.jar": ["it does not link after the port"]}}
lines = portboard.gameload.compare_loads(main_run, full)
check("  c--c.jar: client-only -> not started (it does not link after the port)" in lines and lines[-1] != "no verdict moved",
      f"a full run names each mod main started that it did not: {lines}")
focused = {**full, "scope": ["a", "c"], "verdicts": {"a--a.jar": "loaded"}}
lines = portboard.gameload.compare_loads(main_run, focused)
check(any(x.startswith("  c--c.jar: client-only -> not started") for x in lines) and not any(x.startswith("  b--b.jar") for x in lines),
      f"a focused run names the asked mods it did not start, and no others: {lines}")

# The to-do list finishes mods. The reference the most mods share (g.Shared.one, 2 mods) finishes none of them.
rows = [
    {"mod": "a.jar", "unlinked": ["a.jar: call: g.Shared.one()V (in a.A)", "a.jar!in.jar: call: g.Shared.one()V (in a.B)", "a.jar: call: g.A.x()V", "a.jar: call: g.A.y()V"]},
    {"mod": "b.jar", "unlinked": ["b.jar: call: g.Shared.one()V", "b.jar: call: g.B.x()V"]},
    {"mod": "c.jar", "unlinked": ["c.jar: call: g.C.only()V (in c.C)"]},
    {"mod": "d.jar", "unlinked": ["d.jar: call: g.C.only()V"]},
    {"mod": "w.jar", "unlinked": ["w.jar: call: g.W.gone()V"]},
    {"mod": "e.jar", "unlinked": []},
]
said = {"call: g.C.only()V": {"game": "replaced"}, "call: g.W.gone()V": {"game": "redesigned"}}
order = portboard.board.nearest(rows, {"e.jar": ["data id gone: minecraft:thing x (in data/e.json)"]}, said)
check([t["mod"] for t in order] == ["c.jar", "e.jar", "w.jar", "b.jar", "a.jar"],
      f"mods come in the order they finish, the ones the game answers first among equals: {[t['mod'] for t in order]}")
check(order[0]["alsoFrees"] == ["d.jar"] and [t["modsDone"] for t in order] == [2, 3, 4, 5, 6] and [t["fixes"] for t in order] == [1, 2, 3, 5, 7],
      f"each turn counts the fixes so far and every mod they finish: {[(t['fixes'], t['modsDone']) for t in order]}")
check([m["member"] for m in order[4]["members"]] == ["call: g.A.x()V", "call: g.A.y()V"] and order[0]["members"][0]["game"] == "replaced",
      "a turn lists only what earlier turns left, each reference once with what the game did about it")
longer = {"mod": "b.jar", "unlinked": [f"b.jar: call: g.B.m{i}()V" for i in range(5)]}
check([t["mod"] for t in portboard.board.nearest([rows[0], longer], None, {})] == ["a.jar", "b.jar"], "a mod with more left than another comes after it")

# The same list by type of break: a way to handle a type finishes every mod only that type holds back.
kinds = [
    {"mod": "a.jar", "unlinked": ["a.jar: call: g.X.one()V (in a.A)", "a.jar: call: g.Y.two()V"]},
    {"mod": "b.jar", "unlinked": ["b.jar: call: g.X.one()V", "b.jar: field: g.F.gone"]},
    {"mod": "c.jar", "unlinked": ["c.jar: field: g.F.gone"]},
    {"mod": "d.jar", "unlinked": ["d.jar: call: g.Z.three()V"]},
    {"mod": "e.jar", "unlinked": []},
]
verdicts = {"call: g.X.one()V": {"game": "linked"}, "call: g.Y.two()V": {"game": "linked"}, "call: g.Z.three()V": {"game": "linked"}, "field: g.F.gone": {"game": "split"}}
table = portboard.board.by_type(kinds, None, verdicts)
check([(t["type"], t["finishes"], t["mods"], t["references"]) for t in table] == [("call, game: linked", 2, 3, 3), ("field, game: split", 1, 2, 1)],
      f"a type counts the mods it alone holds back, the mods that have it and its references: {table}")
check(table[0]["examples"][0] == "call: g.X.one()V", "a type's first example is its most-used reference")
verdicts["call: g.Y.two()V"] = {"game": "redesigned"}
check(next(t for t in portboard.board.by_type(kinds, None, verdicts) if t["type"] == "call, game: linked")["finishes"] == 1,
      "a mod held by two types is finished by neither alone")
verdicts["call: g.Z.three()V"] = {"game": "linked", "part": "library"}
check([t["type"] for t in portboard.board.by_type(kinds, None, verdicts) if t["examples"] == ["call: g.Z.three()V"]] == ["call (library), game: linked"],
      "a reference into a library the release ships is its own type, apart from the game's")

# Jumps: the ports people ask for, from each family's most-used release to the next family's and to the newest.
with tempfile.TemporaryDirectory() as tmp:
    portboard.settings.INDEX = Path(tmp) / "index.json"
    portboard.settings.INDEX.write_text(json.dumps({
        "allVersions": [{"id": v, "status": "indexed"} for v in ("1.0", "1.1", "2.0", "2.1", "3.0")],
        "families": {"list": [{"id": "1", "versions": ["1.0", "1.1"], "hub": "1.0", "mods": {"1.0": 9, "1.1": 2}},
                              {"id": "2", "versions": ["2.0", "2.1"], "hub": "2.1", "mods": {"2.0": 1, "2.1": 5}},
                              {"id": "3", "versions": ["3.0"], "hub": "3.0", "mods": {"3.0": 4}}]}}), encoding="utf-8")
    portboard.settings.FIRST = {"fabric": "1.0", "neoforge": "2.0"}
    check(portboard.gamereleases.jumps("fabric") == [("1.0", "2.1"), ("1.0", "3.0")],
          f"each hub to the next hub, and the most-used release to the newest, without the pairs one update apart: {portboard.gamereleases.jumps('fabric')}")
    check(portboard.gamereleases.jumps("neoforge") == [], "a loader read from a later release has only its own hubs, and a pair one update apart is no jump")
    check(portboard.gamereleases.big("1.1", "2.0") and not portboard.gamereleases.big("1.0", "1.1"), "an update into another family is a big one")

    def summary(loader, frm, to, link, turns=()):
        return {"loader": loader, "from": frm, "to": to, "modsRead": 10, "modsThatLink": link, "modsThatLinkAndData": link, "top": [],
                "nearest": [{"mod": mod, "members": [{"member": m, "game": "replaced"} for m in members]} for mod, members in turns]}

    runs = Path(tmp) / "runs"
    runs.mkdir()
    for s in (summary("fabric", "1.0", "1.1", 10), summary("fabric", "1.1", "2.0", 4, [("far.jar", ["a", "b", "c"]), ("near.jar", ["a"])]),
              summary("fabric", "1.0", "3.0", 1), summary("fabric", "1.1", "3.0", 7)):
        (runs / f"{s['loader']}-{s['from']}-{s['to']}.json").write_text(json.dumps(s), encoding="utf-8")
    sys.stdout = io.StringIO()
    try:
        table = portboard.board.join(runs)
    finally:
        sys.stdout = sys.__stdout__
    total = table["total"]
    check((total["link"], total["mods"]) == (14, 20), f"a jump is not counted among the single updates: {total['link']}/{total['mods']}")
    check((total["big"]["link"], total["small"]["link"], total["jumps"]["link"], total["jumps"]["mods"], total["newest"]["link"]) == (4, 10, 1, 10, 1),
          f"big updates, small ones, jumps and the jump to the newest release are counted apart: {total['big']} {total['small']} {total['jumps']} {total['newest']}")
    check(len(table["jumps"]) == 1 and table["jumps"][0]["updatesCrossed"] == 4 and "fabric 1.0 -> 2.1" in table["missing"] and "fabric 2.0 -> 2.1" in table["missing"],
          f"a jump says how many updates it crosses, one that did not run is named, and a pair that is no jump is left out: {table['jumps']} {table['missing']}")
    check([t["mod"] for t in table["nearest"]] == ["near.jar", "far.jar"], "over every update the mods nearest to done come first")
    check(table["typesNotRead"] == ["fabric 1.0 -> 1.1", "fabric 1.1 -> 2.0"] and table["types"] == [],
          f"summaries with no types are named, so a ranking over fewer updates than the counts is not read as all of them: {table['typesNotRead']}")
    typed = summary("fabric", "1.0", "1.1", 10)
    typed["types"] = [{"type": "call, game: linked", "finishes": 2, "mods": 3, "references": 4, "examples": ["call: g.X.one()V"]}]
    (runs / "fabric-1.0-1.1.json").write_text(json.dumps(typed), encoding="utf-8")
    sys.stdout = io.StringIO()
    try:
        table = portboard.board.join(runs)
    finally:
        sys.stdout = sys.__stdout__
    check(table["typesNotRead"] == ["fabric 1.1 -> 2.0"] and [(t["type"], t["finishes"], t["updates"]) for t in table["types"]] == [("call, game: linked", 2, 1)],
          f"types are summed over the updates that list them: {table['types']} {table['typesNotRead']}")

    # Main's table is recorded on main, once per commit: the table with the commit and run it came from, and a point on
    # each port.jar-links measure whose note says what moved against the table it replaces. A branch's table is compared
    # against it the same way.
    before = table
    (runs / "fabric-1.1-2.0.json").write_text(json.dumps(summary("fabric", "1.1", "2.0", 6)), encoding="utf-8")
    sys.stdout = io.StringIO()
    try:
        after = portboard.board.join(runs)
        lines = portboard.mainrecord.compare(before, after)
        same = portboard.mainrecord.compare(after, after)
    finally:
        sys.stdout = sys.__stdout__
    check(lines[0] == "every update: 14 -> 16 of 20 (+2)" and lines[1] == "big updates: 4 -> 6 of 10 (+2)" and "updates higher: fabric 1.1 -> 2.0 4 -> 6" in lines,
          f"compare names the counts and the update that moved: {lines}")
    check(same[portboard.mainrecord.HEADS:] == ["no update, jump or type of break moved"], f"a table against itself moves nothing: {same}")
    board = Path(tmp) / "scoreboard"
    board.mkdir()
    measures = board / "points.json"
    measures.write_text(json.dumps({m: [{"date": "2026-01-01", "value": 14 if m.endswith("every-update") else 4 if m.endswith("big-updates") else 1, "source": "x"}]
                                    for m in portboard.mainrecord.MAIN_MEASURES}), encoding="utf-8")
    main_table = board / "all.json"
    main_table.write_text(json.dumps(before), encoding="utf-8")
    (Path(tmp) / "sweep.json").write_text(json.dumps(after), encoding="utf-8")
    repo, portboard.settings.ROOT = portboard.settings.ROOT, Path(__file__).resolve().parent.parent
    head = os.popen(f'git -C "{portboard.settings.ROOT}" rev-parse HEAD').read().strip()
    sys.stdout = io.StringIO()
    try:
        portboard.mainrecord.record_main(Path(tmp) / "sweep.json", head, "https://example.invalid/runs/1", board, "2026-02-01")
        portboard.mainrecord.record_main(Path(tmp) / "sweep.json", head, "https://example.invalid/runs/2", board, "2026-02-02")
    finally:
        sys.stdout = sys.__stdout__
        portboard.settings.ROOT = repo
    saved = json.loads(main_table.read_text(encoding="utf-8"))
    points = json.loads(measures.read_text(encoding="utf-8"))
    every = points["port.jar-links.every-update"]
    check(saved["commit"] == head and saved["run"].endswith("/runs/2") and saved["total"]["link"] == 16, "main's table says the commit and run it came from")
    check(saved["losses"] == portboard.mainrecord.losses(None) and len(saved["losses"]) >= 7,
          "main's table carries main's losses from before it kept them, and adds none when no mod stopped linking")
    check([p["value"] for p in every] == [14, 16, 16] and every[1]["n"] == 20 and every[1]["note"].startswith("+2 on 14: ") and "fabric 1.1 -> 2.0 4 -> 6" in every[1]["note"]
          and head[:10] in every[1]["source"], f"each sweep of main adds a point saying what moved: {every}")
    check(every[2]["note"] == "the same", f"a sweep that moved nothing says so: {every[2]}")
    check([p["value"] for p in points["port.jar-links.newest"]] == [1, 1, 1] and points["port.jar-links.big-updates"][1]["value"] == 6, "each measure reads its own total")
    # Sweeps of main may finish out of order: an older commit's table never replaces a newer one's.
    parent = os.popen(f'git -C "{Path(__file__).resolve().parent.parent}" rev-parse HEAD~1').read().strip()
    repo, portboard.settings.ROOT = portboard.settings.ROOT, Path(__file__).resolve().parent.parent
    sys.stdout = io.StringIO()
    try:
        late = portboard.mainrecord.record_main(Path(tmp) / "sweep.json", parent, "https://example.invalid/runs/0", board, "2026-02-03")
    finally:
        sys.stdout = sys.__stdout__
        portboard.settings.ROOT = repo
    check(late is None and json.loads(main_table.read_text(encoding="utf-8"))["commit"] == head
          and len(json.loads(measures.read_text(encoding="utf-8"))["port.jar-links.every-update"]) == 3, "a sweep of an older commit that finishes later is not recorded")
    check((board / "any-version.md").read_text(encoding="utf-8").startswith("# Any mod, any version") and len(points["port.jar-links.any-version"]) == 3,
          "main's sweep also writes the any-version page and its measure")

# Any mod, any version: the most-used release's mods to every other release, back as well as forward. A mod counts only
# when its port links on every one; a release whose job did not run, or where the mod was unreadable, is not reached.
with tempfile.TemporaryDirectory() as tmp:
    portboard.settings.INDEX = Path(tmp) / "index.json"
    portboard.settings.INDEX.write_text(json.dumps({
        "allVersions": [{"id": v, "status": "indexed"} for v in ("1.0", "1.1", "2.0", "2.1")],
        "families": {"list": [{"id": "1", "versions": ["1.0", "1.1"], "hub": "1.1", "mods": {"1.1": 9}},
                              {"id": "2", "versions": ["2.0", "2.1"], "hub": "2.0", "mods": {"2.0": 3}}]}}), encoding="utf-8")
    portboard.settings.FIRST = {"fabric": "1.0", "neoforge": "2.0"}
    pins, portboard.settings.PINS = portboard.settings.PINS, Path(tmp) / "pins"
    portboard.settings.PINS.mkdir()
    (portboard.settings.PINS / "neoforge-2.0.json").write_text(json.dumps({"mods": [{"slug": "e", "file": "e.jar"}, {"slug": "f", "file": "f.jar"}]}), encoding="utf-8")
    check(portboard.gamereleases.any_version("fabric") == [("1.1", "1.0"), ("1.1", "2.0"), ("1.1", "2.1")] and ("fabric", "1.1", "1.0") in
          {("fabric", *p) for p in portboard.gamereleases.runs("fabric")}, f"the most-used release goes to every other release, the older ones too: {portboard.gamereleases.any_version('fabric')}")

    def ported(frm, to, verdicts, unreadable=()):
        return {"loader": "fabric", "from": frm, "to": to, "modsRead": len(verdicts), "modsThatLink": 0, "top": [],
                "unreadable": {m: f"java.io.IOException: {m} has an entry that points outside the jar: ../x" for m in unreadable},
                "verdicts": {f"{m}--{m}.jar": {"left": ["x"] * len(t), "types": t} for m, t in verdicts.items()}}

    split, linked, field = "call, game: split", "call, game: linked", "field, game: split"
    runs = Path(tmp) / "runs"
    runs.mkdir()
    for s in (ported("1.1", "1.0", {"a": [], "b": [split], "c": [], "d": []}),
              ported("1.1", "2.0", {"a": [], "b": [], "c": [linked], "d": []}),
              ported("1.1", "2.1", {"a": [], "b": [], "c": [linked, field]}, ["d--d.jar"])):
        (runs / f"fabric-{s['from']}-{s['to']}.json").write_text(json.dumps(s), encoding="utf-8")
    sys.stdout = io.StringIO()
    try:
        a = portboard.board.join(runs)["anyVersion"]
    finally:
        sys.stdout = sys.__stdout__
        portboard.settings.PINS = pins
    fab, neo = a["loaders"]["fabric"], a["loaders"]["neoforge"]
    check((a["every"], a["mods"]) == (1, 6) and a["everywhere"]["fabric"] == ["a"],
          f"only the mod that links on every release counts, out of every pinned mod, a loader whose jobs did not run too: {a['every']} of {a['mods']}")
    check(fab["reached"] == {"a": 3, "b": 2, "c": 1, "d": 2} and (fab["everyNewer"], fab["everyOlder"]) == (2, 3),
          f"each mod's releases reached, a mod unreadable on one release missing it: {fab}")
    check(neo["notRun"] == ["2.1"] and all(m["missing"] == ["2.1"] for m in a["held"] if m["loader"] == "neoforge"), f"a release that did not run is named and not reached: {neo}")
    check([(t["type"], t["finishes"], t["mods"]) for t in a["types"]][:3] == [("not run", 2, 2), (split, 1, 1), ("not read: d--d.jar has an entry that points outside the jar: ../x", 1, 1)]
          and next(t for t in a["types"] if t["type"] == linked)["finishes"] == 0,
          f"a type frees the mods it alone keeps from every release; a mod held by two types is freed by neither; a mod not read is held by why: {a['types']}")
    check([(t["loader"], t["release"], t["finishes"], t["mods"]) for t in a["releases"]][:3] == [("neoforge", "2.1", 2, 2), ("fabric", "2.1", 1, 2), ("fabric", "1.0", 1, 1)],
          f"a release frees the mods that miss only it: {a['releases']}")
    # Breaks left counts one per mod and type holding it on some release, so a fix for one update must not lower it and a
    # type handled on every release must lower it by each mod it held, even before any mod reaches every release.
    pairs = sum(len(m["types"]) for m in a["held"])
    check(portboard.anyversion.breaks_left(a) == pairs, f"breaks left counts each type once per mod it holds: {portboard.anyversion.breaks_left(a)} vs {pairs}")

    def breaks(edit):
        runs2 = Path(tmp) / f"runs-{edit}"
        runs2.mkdir()
        made = {"1.0": {"a": [], "b": [split], "c": [], "d": []}, "2.0": {"a": [], "b": [], "c": [linked], "d": []},
                "2.1": {"a": [], "b": [], "c": [linked, field]}}
        if edit == "linked on one release":
            made["2.0"]["c"] = []
        if edit == "linked on every release":
            made["2.0"]["c"], made["2.1"]["c"] = [], [field]
        for to, v in made.items():
            s2 = ported("1.1", to, v, ["d--d.jar"] if to == "2.1" else ())
            (runs2 / f"fabric-1.1-{to}.json").write_text(json.dumps(s2), encoding="utf-8")
        sys.stdout = io.StringIO()
        try:
            portboard.settings.PINS = Path(tmp) / "pins"
            return portboard.board.join(runs2)["anyVersion"]
        finally:
            sys.stdout = sys.__stdout__
            portboard.settings.PINS = pins

    before, narrow, whole = (breaks(e) for e in ("none", "linked on one release", "linked on every release"))
    left = portboard.anyversion.breaks_left
    check(left(narrow) == left(before) and left(whole) == left(before) - 1 and before["every"] == narrow["every"] == whole["every"],
          f"a type handled on one release leaves the break; handled on every release it is gone, though no mod goes to any version yet: "
          f"{left(before)} -> {left(narrow)} (one release), {left(whole)} (every release)")
    check(any("breaks left" in line for line in portboard.anyversion.any_version_lines(a)[:2]), "the count's lines say the breaks left under the headline")

# Jars are read on every processor at once; the rows come back as one process would have given them.
with tempfile.TemporaryDirectory() as tmp:
    folder = Path(tmp)
    names = [f"mod{i:03}.jar" for i in range(150)]
    for i, name in enumerate(names):
        (folder / name).write_bytes(b"x" * (1 + (i * 37) % 97) * 1000)
    (folder / "big.jar").write_bytes(b"x" * 2_000_000)
    given = [folder / "big.jar"] + [folder / n for n in names]
    portboard.settings.CORES = 16
    dealt = portboard.porter.piles(given)
    sizes = [sum(j.stat().st_size for j in pile) for pile in dealt]
    check(len(dealt) == 4 and sorted(j.name for pile in dealt for j in pile) == sorted(j.name for j in given), "every jar is in exactly one of four piles")
    check(max(sizes) - min(sizes) <= 2_000_000 and [folder / "big.jar"] in [pile[:1] for pile in dealt],
          f"the piles weigh about the same, the biggest jar dealt first: {sizes}")
    portboard.settings.CORES = 4
    check(len(portboard.porter.piles(given)) == 1, "a 4-processor runner has none to spare: one pile")
    portboard.settings.CORES = 64
    check(len(portboard.porter.piles(given)) == 151 // portboard.settings.PILE and len(portboard.porter.piles(given[:5])) == 1, "a pile is not made for a handful of jars, however many processors there are")
    tool = folder / "tool.py"
    tool.write_text("import json, sys\nargs = sys.argv[1:]\nat = args.index('--report')\njars = args[at + 2:]\n"
                    "if any('mod013' in j for j in jars) and 'fail' in args:\n    sys.exit(3)\n"
                    "json.dump({'mods': [{'mod': j.replace(chr(92), '/').rsplit('/', 1)[1], 'heap': args[0]} for j in jars]}, open(args[at + 1], 'w'))\n", encoding="utf-8")
    portboard.settings.CORES = 16
    rows = portboard.porter.side_by_side([sys.executable, str(tool), "-Xmx10g"], given, folder / "report.json")
    check([r["mod"] for r in rows] == [j.name for j in given], "rows come back in the order the jars were given, each once")
    check(not list(folder.glob("report.*.json")), "the piles' own reports are removed")
    try:
        portboard.porter.side_by_side([sys.executable, str(tool), "fail"], given, folder / "report.json")
        failed = False
    except SystemExit:
        failed = True
    check(failed, "one pile failing fails the run")

# A mod's port is kept under everything the port reads, and only mods with no kept port go to JarPort: agents rerun the
# scoreboard after every edit, and porting every mod again is most of a run.
with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    saved = (portboard.settings.ROOT, portboard.settings.HOME, portboard.porter.jarport, portboard.porter.route_maps, portboard.porter.jar_tools, portboard.porter.compile_tools, portboard.porter.tools_classes_key, portboard.cachekeys.platform_key, portboard.porter.java_version, portboard.settings.VERIFY, portboard.porter.stops,
             portboard.porter.registry_reports)
    portboard.settings.ROOT, portboard.settings.HOME = root, root / "home"
    (root / "knowledge").mkdir()
    fixes = root / "knowledge" / "fixes.json"
    fixes.write_text("[]", encoding="utf-8")
    route = root / "route.json"
    route.write_text("{}", encoding="utf-8")
    jars = []
    for i in range(3):
        jars.append(root / f"m{i}.jar")
        jars[-1].write_bytes(f"jar {i}".encode())
    ported = []
    answer = {"m0.jar": []}

    commands = []

    def fake_jarport(command):
        given = [Path(c) for c in command if c.endswith(".jar")]
        plan = json.loads(Path(command[command.index("--plan") + 1]).read_text(encoding="utf-8"))
        ported.append([j.name for j in given])
        commands.append(plan)
        for target in plan["ports"]:
            Path(target["report"]).write_text(json.dumps({"mods": [{"mod": j.name, "out": str(Path(target["out"]) / j.name), "unlinked": answer.get(j.name, ["call: a.B.c()V"]),
                                                                      "silent": []} for j in given]}), encoding="utf-8")
        return 0

    # A port from 1.0 to 1.2 stops at 1.1, as JarPort plans it when 1.1 -> 1.2 has stand-ins: each step has a map of its own.
    step_routes = {("1.0", "1.1"): route, ("1.1", "1.2"): root / "route-1.1-1.2.json"}
    step_routes[("1.1", "1.2")].write_text("{}", encoding="utf-8")
    portboard.porter.stops = lambda loader, frm, to: ["1.0", "1.1", "1.2"] if to == "1.2" else [frm, to]
    portboard.porter.jarport, portboard.porter.route_maps, portboard.porter.jar_tools, portboard.porter.compile_tools = fake_jarport, (lambda updates, *a: [step_routes[u] for u in updates]), (lambda: "classes"), (lambda: "classes")
    portboard.porter.tools_classes_key, portboard.cachekeys.platform_key, portboard.porter.java_version = (lambda: "tools-1"), (lambda loader, r: b"platform"), (lambda: "java 25")
    asked_reports = []
    portboard.porter.registry_reports = asked_reports.append

    def port():
        return portboard.porter.jar_port("neoforge", "1.0", "1.1", jars, root / "out", root / "report.json", cache=True)

    first = port()
    again = port()
    check(ported == [["m0.jar", "m1.jar", "m2.jar"]] and again["mods"] == [{k: v for k, v in m.items() if k != "out"} for m in first["mods"]]
          and again["linked"] == first["linked"] == 1, f"a second run with the same inputs ports nothing and answers the same: {ported}")
    jars[1].write_bytes(b"jar 1, edited")
    port()
    check(ported[-1] == ["m1.jar"], f"a mod whose jar changed is the only one ported again: {ported[-1]}")
    standin = root / "knowledge" / "standins" / "1.0-1.1.neoforge" / "a" / "B.java"

    def write_standin():
        standin.parent.mkdir(parents=True, exist_ok=True)
        standin.write_text("package mmt.standin.a; public final class B {}", encoding="utf-8")

    def report_of(version):
        return portboard.settings.HOME / "registries" / f"{version}.json"

    def write_report():
        report_of("1.1").parent.mkdir(parents=True, exist_ok=True)
        report_of("1.1").write_text('{"minecraft:item": ["minecraft:a"]}', encoding="utf-8")

    check(asked_reports and asked_reports[0] == ["1.0", "1.1"], f"the port asks for each release's registry report first: {asked_reports}")
    for change in (lambda: fixes.write_text('[{"id": "x"}]', encoding="utf-8"), lambda: route.write_text('{"rewrites": []}', encoding="utf-8"),
                   write_standin, lambda: standin.write_text("package mmt.standin.a; public final class B { }", encoding="utf-8"),
                   lambda: setattr(portboard.porter, "tools_classes_key", lambda: "tools-2"), lambda: setattr(portboard.cachekeys, "platform_key", lambda loader, r: b"other loader"),
                   lambda: setattr(portboard.porter, "java_version", lambda: "java 26"), write_report, lambda: report_of("1.1").write_text('{"minecraft:item": ["minecraft:b"]}', encoding="utf-8")):
        runs = len(ported)
        change()
        port()
        check(len(ported) == runs + 1 and ported[-1] == ["m0.jar", "m1.jar", "m2.jar"], f"a change to what every port reads ports every mod again: {ported[runs:]}")
    def long_port():
        return portboard.porter.jar_port("neoforge", "1.0", "1.2", jars, root / "out", root / "report.json", cache=True)

    long_port()
    target = commands[-1]["ports"][0]
    check(target["via"] == ["1.1"] and target["maps"] == [str(route), str(step_routes[("1.1", "1.2")])],
          f"a port across several updates gives JarPort its stops and each step's map: {target}")
    # Ports from one release to several go to one JarPort, which ports the steps they share once; a mod kept for one
    # target and not the other is ported to both, and the kept port must be what porting it again gives.
    runs = len(ported)
    both = portboard.porter.jar_ports("neoforge", "1.0", [("1.1", root / "out-1.1", root / "report-1.1.json"), ("1.2", root / "out-1.2", root / "report-1.2.json")], jars, cache=True)
    check(len(ported) == runs and sorted(both) == ["1.1", "1.2"] and both["1.2"]["linked"] == 1,
          f"two targets whose ports were kept are read, not ported: {ported[runs:]}")
    jars[2].write_bytes(b"jar 2, edited")
    portboard.porter.jar_port("neoforge", "1.0", "1.1", jars, root / "out", root / "report.json", cache=True)
    both = portboard.porter.jar_ports("neoforge", "1.0", [("1.1", root / "out-1.1", root / "report-1.1.json"), ("1.2", root / "out-1.2", root / "report-1.2.json")], jars, cache=True)
    check(ported[-1] == ["m2.jar"] and [t["to"] for t in commands[-1]["ports"]] == ["1.1", "1.2"] and both["1.1"]["verified"] == 1,
          f"a mod with no kept port to one target goes to one JarPort with every target, and its kept port to the other is checked: {commands[-1]}")
    answer["m2.jar"] = ["field: a.B.d"]
    jars[2].write_bytes(b"jar 2, edited again")
    portboard.porter.jar_port("neoforge", "1.0", "1.1", jars, root / "out", root / "report.json", cache=True)
    answer["m2.jar"] = ["field: a.B.e"]
    try:
        portboard.porter.jar_ports("neoforge", "1.0", [("1.1", root / "out-1.1", root / "report-1.1.json"), ("1.2", root / "out-1.2", root / "report-1.2.json")], jars, cache=True)
        caught = False
    except SystemExit as failed:
        caught = "m2.jar" in str(failed) and "1.1" in str(failed)
    check(caught, "a kept port that differs from the same port made beside another target fails the run")
    answer.pop("m2.jar")
    jars[2].write_bytes(b"jar 2, as the checks below have it")
    later = root / "knowledge" / "standins" / "1.1-1.2" / "a" / "C.java"
    for change in (lambda: (later.parent.mkdir(parents=True, exist_ok=True), later.write_text("package mmt.standin.a; public final class C {}", encoding="utf-8")),
                   lambda: step_routes[("1.1", "1.2")].write_text('{"rewrites": []}', encoding="utf-8")):
        runs = len(ported)
        change()
        long_port()
        check(len(ported) == runs + 1, f"a change to a later step's stand-ins or map ports the long jump's mods again: {ported[runs:]}")
    portboard.settings.VERIFY = True
    port()
    check(ported[-1] == ["m0.jar", "m1.jar", "m2.jar"], "--verify ports every mod although each has a kept port")
    answer["m2.jar"] = []
    try:
        port()
        caught = False
    except SystemExit as failed:
        caught = "m2.jar" in str(failed)
    check(caught, "--verify fails on a mod whose kept port differs from porting it again")
    (portboard.settings.ROOT, portboard.settings.HOME, portboard.porter.jarport, portboard.porter.route_maps, portboard.porter.jar_tools, portboard.porter.compile_tools, portboard.porter.tools_classes_key, portboard.cachekeys.platform_key, portboard.porter.java_version, portboard.settings.VERIFY, portboard.porter.stops,
     portboard.porter.registry_reports) = saved

before = {"verdicts": {"a": {"left": ["x"]}, "b": {"left": [], "ported": "1"}, "c": {"left": ["x"]}, "d": {"left": [], "ported": "1"}, "e": {"left": []}}}
after = {"verdicts": {"a": {"left": []}, "b": {"left": ["y"]}, "c": {"left": ["y"]}, "d": {"left": [], "ported": "2"}, "e": {"left": []}, "f": {"left": []}}}
check(portboard.board.verdicts_moved(before, after) == {"newly link": ["a"], "no longer link": ["b"], "other references left": ["c"], "link, port changed": ["d"], "same": 1},
      f"each mod both runs read is named once by how its verdict moved: {portboard.board.verdicts_moved(before, after)}")

# A client sent back to the disconnected screen names no mod: the mod whose arrival makes it fail is found by halving,
# each set started with the jars it needs; a client that fails with no mod at all blames none.
with tempfile.TemporaryDirectory() as tmp:
    pack = Path(tmp)
    (pack / "mods").mkdir()
    names = ["a.jar", "krypton.jar", "b.jar", "lib.jar", "z.jar"]
    for n, meta in zip(names, [{"id": "a"}, {"id": "krypton", "depends": {"lib": "*"}}, {"id": "b", "depends": {"lib": "*"}}, {"id": "lib"}, {"id": "z"}]):
        (pack / "mods" / n).write_bytes(jar({"fabric.mod.json": json.dumps(meta)}))
    seen = []

    def start(bad=("krypton.jar",)):
        here = {p.name for p in (pack / "mods").glob("*.jar")}
        seen.append(sorted(here))
        if "krypton.jar" in here and "lib.jar" not in here:
            raise AssertionError("a mod was started without the jar it needs")
        return {"result": {"client": {"opened": not here & set(bad)}}}, None

    blamed = portboard.gameload.first_failing(pack, names, "fabric", start, "DisconnectedScreen")
    check(list(blamed) == ["krypton.jar"] and len(seen) <= 4 and sorted(p.name for p in (pack / "mods").glob("*.jar")) == sorted(names),
          f"halving names the mod a silent client fails on, in few starts, and puts every jar back: {blamed} {len(seen)}")
    check(portboard.gameload.first_failing(pack, names, "fabric", lambda: ({"result": {"client": {"opened": False}}}, None), "x") == {},
          "a client that fails with no mod at all blames none")

# The client proof gives every mod asked for a verdict, however many libraries fail on the way: two libraries failing on
# successive starts, with one mod asked for, used to spend the whole budget of starts (mods + 1) and leave it out unsaid.
# And pack-check's Java warning (a jar's newest class, which the game may never load) is no evidence of a failure: a
# library it lists stays when the start fails on another mod.
with tempfile.TemporaryDirectory() as tmp:
    server = Path(tmp) / "load"
    (server / "mods").mkdir(parents=True)
    (server / "dropped").mkdir()
    for n, meta in {"mod.jar": {"id": "mod", "depends": {"lib": "*"}}, "bad.jar": {"id": "bad"}, "lib.jar": {"id": "lib"},
                    "lib1.jar": {"id": "lib1"}, "lib2.jar": {"id": "lib2"}, "api.jar": {"id": "fabric"}}.items():
        (server / "mods" / n).write_bytes(jar({"fabric.mod.json": json.dumps(meta)}))

    def failing(jars, java=()):
        def run(pack):
            here = {p.name for p in (pack / "mods").glob("*.jar")}
            findings = [{"kind": "java", "severity": "warning", "suspects": [{"jar": j, "why": ["it is built for Java 17"]} for j in java]}]
            for j in jars:
                if j in here:
                    suspects = [] if j == "bad.jar" else [{"jar": j, "why": ["it crashes"]}]
                    return {"result": {"client": {"opened": False, "failure": "x"}, "findings": findings + [
                        {"kind": "client-start", "severity": "problem", "suspects": suspects}]}}
            return {"result": {"client": {"opened": True}, "findings": findings}}
        return run

    client = portboard.gameload.client_starts("fabric", "2.0", server, ["mod.jar"], ["lib.jar", "lib1.jar", "lib2.jar", "api.jar"], {"api.jar"},
                             run=failing(["lib1.jar", "lib2.jar"]))
    check(client["verdicts"] == {"mod.jar": "loaded"},
          f"a mod asked for keeps its verdict when libraries fail start after start: {client['verdicts']}")
    client = portboard.gameload.client_starts("fabric", "2.0", server, ["mod.jar", "bad.jar"], ["lib.jar", "api.jar"], {"api.jar"},
                             run=failing(["bad.jar"], java=["lib.jar"]))
    check(client["verdicts"].get("mod.jar") == "loaded" and client["verdicts"].get("bad.jar", "").startswith("failed")
          and (Path(tmp) / "load-client" / "mods" / "lib.jar").is_file(),
          f"a library the Java warning lists stays when the start fails on another mod: {client['verdicts']}")

    # A mod that fails is printed with why and with where that start's game logs are: an agent read "failed" in the table
    # and then looked for the crash on disk (17 searches, 7 minutes, in one session of 2026-11-25).
    portboard.gameload.FAILED_LOGS.clear()

    def crashing(pack):
        opened = not (pack / "mods" / "bad.jar").is_file()
        suspects = [] if opened else [{"jar": "bad.jar", "why": ["its mixin BadMixin fails to apply"]}]
        return {"receipt": str(Path(tmp) / "starts" / ("ok" if opened else "crash") / "result.json"),
                "result": {"client": {"opened": opened, "failure": "" if opened else "crashed at the title screen"},
                           "findings": [{"kind": "client-start", "severity": "problem", "suspects": suspects}]}}

    client = portboard.gameload.client_starts("fabric", "2.0", server, ["mod.jar", "bad.jar"], ["lib.jar", "api.jar"], {"api.jar"}, run=crashing)
    said = portboard.gameload.why_lines({"mod.jar": "loaded", "bad.jar": "loaded"}, client["verdicts"], portboard.gameload.FAILED_LOGS)
    check(len(said) == 4 and said[1] == "  bad.jar" and "client: its mixin BadMixin fails to apply | crashed at the title screen" in said[2]
          and said[3].strip() == "logs: " + str(Path(tmp) / "starts" / "crash"),
          f"a mod that fails in the client is printed with its reason and the failed start's log folder: {said}")
    both = portboard.gameload.why_lines({"a.jar": "failed: no world opened", "b.jar": "client-only: its metadata", "c.jar": "clash: loads alone"},
                                        {"a.jar": "loaded", "b.jar": "loaded"}, {("server", "a.jar"): "X"})
    check(both[1:] == ["  a.jar", "    server: no world opened", "      logs: X", "  c.jar", "    server: loads alone"],
          f"only the sides that failed are explained, a client-only mod is not a failure: {both}")
    check(portboard.gameload.why_lines({"mod.jar": "loaded"}, {"mod.jar": "loaded"}, {}) == [], "a run where every mod loads prints no reasons")

# A run of named mods ends with where each file of the port is, and names only what is there: a path that is not on this
# machine would be opened and found missing.
with tempfile.TemporaryDirectory() as tmp:
    here = Path(tmp)
    for name in ("shipped.jar", "named.jar", "ported.jar", "game-named.jar", "client.jar", "api.jar", "route.json"):
        (here / name).write_bytes(b"x")
    (here / "standins").mkdir()
    said = portboard.board.files_lines([("clumps", here / "shipped.jar", here / "named.jar", here / "ported.jar"), ("sound", here / "s.jar", here / "s.jar", here / "none.jar")],
                                       {"2.0": [here / "game-named.jar", here / "client.jar", here / "client.txt"], "1.0": [here / "gone.jar"]},
                                       {"2.0": here / "api.jar", "1.0": None}, [here / "route.json"], [here / "standins", here / "standins.fabric"])
    check(said[2:] == ["  clumps", f"    as shipped: {here / 'shipped.jar'}", f"    in Mojang's names: {here / 'named.jar'}", f"    ported (Mojang's names): {here / 'ported.jar'}",
                       "  sound", f"  game 2.0: {here / 'game-named.jar'}", f"  game 2.0: {here / 'client.jar'}", f"  Fabric API for 2.0: {here / 'api.jar'}",
                       f"  route map: {here / 'route.json'}", f"  stand-ins: {here / 'standins'}"],
          f"a run of named mods names each file of the port that is there, and no other: {said}")
# A jar a run of named mods kept is the next run's only when it is the jar the port's report names.
with tempfile.TemporaryDirectory() as tmp:
    kept = Path(tmp) / "clumps.jar"
    whole = b"ported jar " * 50
    row = {"mod": "clumps.jar", "ported": hashlib.sha256(whole).hexdigest()[:16]}
    check(not portboard.board.kept_jar(kept, row), "a mod with no kept jar is ported")
    kept.write_bytes(whole)
    check(portboard.board.kept_jar(kept, row), "a repeat run keeps the jar its port wrote, so it ports nothing")
    kept.write_bytes(whole[:200])
    check(not portboard.board.kept_jar(kept, row), "a jar cut short while it was written is ported again")
    kept.write_bytes(b"another porter's jar")
    check(not portboard.board.kept_jar(kept, row), "a jar a changed porter or stand-in wrote is ported again")
    check(not portboard.board.kept_jar(kept, {"mod": "clumps.jar", "error": "unreadable"}), "a mod whose port failed has no jar to keep")
read, standins = portboard.board.port_reads("fabric", ["3.0", "2.0", "1.0"], ["3.0", "1.0"])
check(read == ["3.0", "2.0", "1.0"] and [p.name for p in standins] == ["3.0-1.0", "3.0-1.0.fabric", "jdk"],
      f"a step across two updates lists the release between, whose game jar and Fabric API its composed map was built from: {read} {standins}")
read, standins = portboard.board.port_reads("neoforge", ["1.0", "2.0", "3.0"], ["1.0", "2.0", "3.0"])
check([p.name for p in standins] == ["1.0-2.0", "1.0-2.0.neoforge", "2.0-3.0", "2.0-3.0.neoforge", "jdk"], f"each step's stand-ins are listed: {standins}")

# Halving names a mod by starting fewer jars; the logs shown for it are of the smallest start that failed with it, not
# of the whole pack's start, which named no mod.
with tempfile.TemporaryDirectory() as tmp:
    pack = Path(tmp) / "pack"
    (pack / "mods").mkdir(parents=True)
    names = ["a.jar", "b.jar", "c.jar", "d.jar"]
    for n in names:
        (pack / "mods" / n).write_bytes(jar({"fabric.mod.json": json.dumps({"id": n[0]})}))
    for bad in ("b.jar", "d.jar"):
        portboard.gameload.ISOLATED.clear()
        portboard.gameload.FAILED_LOGS.clear()
        count = [0]

        def start(bad=bad):
            count[0] += 1
            here = sorted(p.name for p in (pack / "mods").glob("*.jar"))
            return {"receipt": str(Path(tmp) / f"start-{'-'.join(n[0] for n in here) or 'none'}" / "result.json"),
                    "result": {"client": {"opened": bad not in here}}}, None

        found = portboard.gameload.first_failing(pack, names, "fabric", start, "DisconnectedScreen")
        portboard.gameload.blame("client", bad, {"receipt": str(Path(tmp) / "start-whole" / "result.json")})
        smallest = "start-" + "-".join(n[0] for n in names[:names.index(bad) + 1])
        wanted = str(Path(tmp) / ("start-whole" if bad == "d.jar" else smallest))
        check(list(found) == [bad] and portboard.gameload.FAILED_LOGS[("client", bad)] == wanted,
              f"a mod named by halving shows the logs of the smallest start that failed with it: {portboard.gameload.FAILED_LOGS} wanted {wanted}")

# One Java check serves the link check and the game proof: a jar is failed when a class the JVM loads of it needs a newer
# Java than the release runs on (version.json's javaVersion). For a jar the loader starts, that is its start classes (its
# mixins, its entrypoints, NeoForge's @Mod classes) for the side; on Fabric 1.15 (Java 8) a Java 17 mixin crashed every
# start naming no mod (PR #206). Its other classes do not count: More Mobs ships Java 17-25 classes for Forge and
# NeoForge and loads on Java 8, and Boxlib's only too-new start class is its client entrypoint, so it loads on a server.
# A nested mod is loaded as a mod and counts the same way; a library the loader starts nothing of counts every class.
def class_file(major, body=b""):
    return b"\xca\xfe\xba\xbe\x00\x00" + bytes([major >> 8, major & 0xff]) + b"\x00" * 8 + body


with tempfile.TemporaryDirectory() as tmp:
    real_home = portboard.settings.HOME
    portboard.settings.HOME = Path(tmp) / "home"
    try:
        for version, java in {"1.15": 8, "1.20.4": 17, "1.21": 21}.items():
            (portboard.settings.HOME / "versions").mkdir(parents=True, exist_ok=True)
            (portboard.settings.HOME / "versions" / f"{version}.json").write_text(json.dumps({"javaVersion": {"majorVersion": java}}), encoding="utf-8")
        folder = Path(tmp) / "mods"
        folder.mkdir()
        mixins = json.dumps({"package": "m.mixin", "mixins": ["LivingMixin"], "server": ["sub.ServerMixin"]})
        (folder / "mixin.jar").write_bytes(jar({"fabric.mod.json": json.dumps({"id": "m", "mixins": ["m.mixins.json"], "entrypoints": {"main": ["m.Init"]}}),
                                                "m.mixins.json": mixins, "m/Init.class": class_file(52), "m/mixin/LivingMixin.class": class_file(61),
                                                "m/mixin/sub/ServerMixin.class": class_file(52)}))
        (folder / "server-mixin.jar").write_bytes(jar({"fabric.mod.json": json.dumps({"id": "s", "mixins": [{"config": "s.mixins.json", "environment": "*"}]}),
                                                       "s.mixins.json": json.dumps({"package": "s", "server": ["A"], "client": ["B"]}),
                                                       "s/A.class": class_file(61), "s/B.class": class_file(52)}))
        (folder / "client-entry.jar").write_bytes(jar({"fabric.mod.json": json.dumps({"id": "c", "entrypoints": {"client": ["c.Client"], "main": [{"value": "c.Main::init"}], "modmenu": ["c.Client"]}}),
                                                       "c/Client.class": class_file(61), "c/Main.class": class_file(52)}))
        (folder / "multi-loader.jar").write_bytes(jar({"fabric.mod.json": json.dumps({"id": "x", "entrypoints": {"main": ["x.fabric.Init"]}}),
                                                       "x/fabric/Init.class": class_file(52), "x/neoforge/Config.class": class_file(65)}))
        (folder / "data.jar").write_bytes(jar({"fabric.mod.json": "{}"}))
        # Fabric API's modules are jar-in-jar mods: their mixins load at start. A module whose own "java" dependency
        # refuses the release's Java is not loaded (C2ME's native math module asks Java 22), nor is a jar with no metadata.
        module = jar({"fabric.mod.json": json.dumps({"id": "api-module", "mixins": ["api.mixins.json"]}),
                      "api.mixins.json": json.dumps({"package": "api", "mixins": ["WorldMixin"]}), "api/WorldMixin.class": class_file(61)})
        gated = jar({"fabric.mod.json": json.dumps({"id": "natives", "depends": {"java": ">=22"}, "entrypoints": {"main": ["g.Natives"]}}),
                     "g/Natives.class": class_file(66)})
        (folder / "bundle.jar").write_bytes(jar({"fabric.mod.json": json.dumps({"id": "b", "entrypoints": {"main": ["b.Init"]}}), "b/Init.class": class_file(52),
                                                 "META-INF/jars/module.jar": module}))
        (folder / "gated.jar").write_bytes(jar({"fabric.mod.json": json.dumps({"id": "g", "entrypoints": {"main": ["g.Init"]}}), "g/Init.class": class_file(65),
                                                "META-INF/jars/natives.jar": gated, "META-INF/jars/plain.jar": jar({"p/P.class": class_file(66)})}))
        # A library starts nothing of its own: the mods that use it load its classes, so every one counts.
        (folder / "library.jar").write_bytes(jar({"fabric.mod.json": json.dumps({"id": "lib"}), "l/Api.class": class_file(52), "l/Impl.class": class_file(61)}))
        names = ["mixin.jar", "server-mixin.jar", "client-entry.jar", "multi-loader.jar", "data.jar", "bundle.jar", "library.jar"]
        server = portboard.javalevels.newer_java(folder, names, "fabric", "1.15", "server")
        check(sorted(server) == ["bundle.jar", "library.jar", "mixin.jar", "server-mixin.jar"] and "module.jar!api.WorldMixin" in server["bundle.jar"]
              and "l.Impl" in server["library.jar"],
              f"on a 1.15 server a nested module's Java 17 mixin and a Java 17 library class count: {server}")
        check(portboard.javalevels.newer_java(folder, ["gated.jar"], "fabric", "1.21", "server") == {},
              "a nested module asking a newer Java than the release's, and a nested jar no loader reads, are not loaded")
        check(sorted(server) == ["bundle.jar", "library.jar", "mixin.jar", "server-mixin.jar"] and server["mixin.jar"].startswith("built for Java 17; Minecraft 1.15 runs on Java 8")
              and "m.mixin.LivingMixin" in server["mixin.jar"],
              f"on a 1.15 server, the jars whose mixins need Java 17 are named with both Javas and the class: {server}")
        client = portboard.javalevels.newer_java(folder, names[:5], "fabric", "1.15", "client")
        check(sorted(client) == ["client-entry.jar", "mixin.jar"], f"in a 1.15 client, a too-new client entrypoint counts and a server mixin does not: {client}")
        check(portboard.javalevels.newer_java(folder, names, "fabric", "1.20.4", "client") == {}, "on 1.20.4 (Java 17) the same jars all start")
        (folder / "neo.jar").write_bytes(jar({"META-INF/neoforge.mods.toml": 'modLoader="javafml"\n[[mods]]\nmodId="neo"\n',
                                              "n/Neo.class": class_file(69, b"Lnet/neoforged/fml/common/Mod;"), "n/Other.class": class_file(52)}))
        (folder / "neo21.jar").write_bytes(jar({"META-INF/neoforge.mods.toml": 'modLoader="javafml"\n[[mods]]\nmodId="neo21"\n',
                                                "n/Neo.class": class_file(65, b"Lnet/neoforged/fml/common/Mod;"), "n/Later.class": class_file(69)}))
        found = portboard.javalevels.newer_java(folder, ["neo.jar", "neo21.jar"], "neoforge", "1.21", "server")
        check(list(found) == ["neo.jar"] and "Java 25" in found["neo.jar"],
              f"on NeoForge 1.21 (Java 21) a @Mod class built for Java 25 is named, and a Java 25 class nothing loads at start is not: {found}")
    finally:
        portboard.settings.HOME = real_home
# The link check reads the same classes, on both sides, of the jar the port wrote: the port lowers a mod's classes to the
# release's Java (Downgrade), so a mod built for a newer Java links once lowered, and does not when a class the JVM loads
# of the ported jar still needs the newer Java. A nested mod counts; a multi-release jar's newer copies, module-info and
# another loader's classes in a jar for several do not. The jars as they came all need Java 21, so a check that read them
# instead would fail every mod.
with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    came = {}
    for n in ("lowered.jar", "nested.jar", "plain.jar", "multi.jar"):
        came[n] = root / "in" / n
        came[n].parent.mkdir(exist_ok=True)
        came[n].write_bytes(jar({"fabric.mod.json": json.dumps({"id": n[:-4], "entrypoints": {"main": ["a.A"]}}), "a/A.class": class_file(65)}))
    wrote = {
        "lowered.jar": jar({"fabric.mod.json": json.dumps({"id": "lowered", "entrypoints": {"main": ["a.A"]}}), "a/A.class": class_file(61)}),
        "nested.jar": jar({"fabric.mod.json": json.dumps({"id": "n", "entrypoints": {"main": ["a.A"]}}), "a/A.class": class_file(61),
                           "META-INF/jars/lib.jar": jar({"fabric.mod.json": json.dumps({"id": "l", "entrypoints": {"client": ["lib.L"]}}), "lib/L.class": class_file(65)})}),
        "plain.jar": jar({"fabric.mod.json": json.dumps({"id": "p", "entrypoints": {"main": ["a.A"]}}), "a/A.class": class_file(61),
                          "META-INF/versions/21/a/A.class": class_file(65), "module-info.class": class_file(65)}),
        "multi.jar": jar({"fabric.mod.json": json.dumps({"id": "m", "entrypoints": {"main": ["m.fabric.Init"]}}), "m/fabric/Init.class": class_file(61),
                          "m/neoforge/Init.class": class_file(65)})}
    ported_now = []

    def fake_jarport(command):
        given = [Path(c) for c in command if c.endswith(".jar")]
        ported_now.extend(j.name for j in given)
        for target in json.loads(Path(command[command.index("--plan") + 1]).read_text(encoding="utf-8"))["ports"]:
            out = Path(target["out"])
            for j in given:
                (out / j.name).write_bytes(wrote[j.name])
            Path(target["report"]).write_text(json.dumps({"mods": [{"mod": j.name, "out": str(out / j.name), "unlinked": [], "silent": []} for j in given]}),
                                              encoding="utf-8")
        return 0

    saved = (portboard.settings.ROOT, portboard.settings.HOME, portboard.porter.jarport, portboard.porter.route_maps, portboard.porter.jar_tools, portboard.porter.compile_tools, portboard.porter.tools_classes_key, portboard.cachekeys.platform_key, portboard.porter.java_version, portboard.porter.stops, portboard.javalevels.release_java,
             portboard.porter.registry_reports)
    portboard.settings.ROOT, portboard.settings.HOME = root, root / "home"
    (root / "knowledge").mkdir()
    (root / "knowledge" / "fixes.json").write_text("[]", encoding="utf-8")
    (root / "route.json").write_text("{}", encoding="utf-8")
    portboard.porter.jarport, portboard.porter.route_maps, portboard.porter.jar_tools, portboard.porter.compile_tools = fake_jarport, (lambda updates, *a: [root / "route.json"]), (lambda: "classes"), (lambda: "classes")
    portboard.porter.tools_classes_key, portboard.cachekeys.platform_key, portboard.porter.java_version, portboard.porter.stops = (lambda: "tools"), (lambda loader, r: b"platform"), (lambda: "java 25"), (lambda loader, frm, to: [frm, to])
    portboard.javalevels.release_java = lambda v: {"old": 17, "new": 21}[v]
    portboard.porter.registry_reports = lambda versions: None  # these mods carry no data
    try:
        def port(to, cache=False):
            return portboard.porter.jar_port("fabric", "1.0", to, list(came.values()), root / f"out-{to}", root / f"report-{to}.json", cache=cache)

        back, forward = port("old"), port("new")
        ported_now.clear()
        first = port("old", cache=True)
        shutil.rmtree(root / "out-old")
        kept = first, port("old", cache=True)
    finally:
        (portboard.settings.ROOT, portboard.settings.HOME, portboard.porter.jarport, portboard.porter.route_maps, portboard.porter.jar_tools, portboard.porter.compile_tools, portboard.porter.tools_classes_key, portboard.cachekeys.platform_key, portboard.porter.java_version, portboard.porter.stops, portboard.javalevels.release_java,
         portboard.porter.registry_reports) = saved
    check([m["mod"] for m in back["mods"] if m["unlinked"]] == ["nested.jar"] and back["linked"] == 3
          and portboard.porter.left(back["mods"][1]) == {"built for a newer Java: Java 21 classes, Java 17 runs old"}
          and back["unlinkedByMods"] == [{"member": "built for a newer Java: Java 21 classes, Java 17 runs old", "mods": 1}],
          f"a ported mod whose nested mod still needs a newer Java than the release's does not link; lowered ones and ones whose too-new classes never load do: {back}")
    check(forward["linked"] == 4 and not forward["unlinkedByMods"], f"on a release whose Java reads its classes it still links: {forward}")
    check(ported_now == list(came) and kept[1]["mods"] == [{k: v for k, v in m.items() if k != "out"} for m in kept[0]["mods"]] and kept[1]["linked"] == 3,
          f"a kept port keeps the Java check of the jar it wrote, which is gone when it is read again: {ported_now}, {kept[1]}")
    # A bundle's module for a newer release, which JarPort left out as not loaded on this one, never runs on its Java.
    bundled = root / "bundle.jar"
    bundled.write_bytes(wrote["nested.jar"])

    def checked(skipped):
        row = {"mod": "bundle.jar", "out": str(bundled), "unlinked": [], "skipped": skipped}
        return portboard.javalevels.with_java_checked(row, "fabric", "old", lambda: 17)["unlinked"]

    check(checked(["bundle.jar: META-INF/jars/lib.jar: not loaded on 1.0 or 2.0 (minecraft \"3.0\")"]) == [],
          "a nested module the loader does not load on the release asks nothing of its Java")
    check(checked(["bundle.jar: META-INF/jars/other.jar: not loaded"]) == ["bundle.jar: built for a newer Java: Java 21 classes, Java 17 runs old"],
          "skipping another nested jar leaves the loaded module's Java counted")

# A start that opens a world says nothing of a jar the loader skipped (pack-check's loader-mismatch warning, as
# PackChecks.foreign writes it): 1.21.1 NeoForge jars ported back to 1.20.2 with only neoforge.mods.toml were called loaded.
skipped = portboard.gameload.not_loaded({"findings": [
    {"kind": "loader-mismatch", "severity": "warning", "summary": "a.jar is written for another loader (META-INF/neoforge.mods.toml)", "jars": ["a.jar"]},
    {"kind": "java", "severity": "warning", "suspects": [{"jar": "b.jar", "why": ["Java 21"]}]}]})
check(list(skipped) == ["a.jar"] and "another loader" in skipped["a.jar"], f"a jar the loader skips is named, a Java warning is not: {skipped}")

# The Fabric API lookup (Exposure --fabric-api). Searching for a release's build reads up to two jars per release of its
# family and the release's mappings, ~107 downloads for 1.20.1 to 26.3, and any one failing stopped a cloud port job with
# only "returned non-zero exit status 1". The answer is kept in the cache the cloud jobs restore, keyed by Fabric's release
# list: a kept answer is used without searching, and a changed list (a new build) gets a fresh one. A failure names the
# release and the download or file. Run on a planted ~/.mmt/mappings (Java's user.home) and ~/.mmt/exposure.
with tempfile.TemporaryDirectory() as tmp:
    home = Path(tmp)
    cache = home / ".mmt" / "mappings"
    cache.mkdir(parents=True)
    listed = cache / "fabric-api-maven-metadata.xml"

    def release_list(*builds):
        listed.write_text("<metadata><versioning><versions>" + "".join(f"<version>{b}</version>" for b in builds) + "</versions></versioning></metadata>",
                          encoding="utf-8")

    def api_jar(release):
        module = jar({"fabric.mod.json": json.dumps({"id": "fabric-api-base"})})
        return jar({"fabric.mod.json": json.dumps({"id": "fabric-api", "depends": {"minecraft": release}}), "META-INF/jars/base.jar": module})

    release_list("0.1.0+26.9", "0.2.0+26.8")
    (cache / "fabric-api-0.1.0+26.9.jar").write_bytes(api_jar("26.9"))
    whole = api_jar("26.8")
    (cache / "fabric-api-0.2.0+26.8.jar").write_bytes(whole[:len(whole) // 2])  # a download cut off and kept
    tools = portboard.porter.jar_tools()  # compiled before user.home moves, into this machine's shared classes
    saved_env = {k: os.environ.get(k) for k in ("JAVA_TOOL_OPTIONS", "MMT_EXPOSURE_HOME")}
    os.environ["JAVA_TOOL_OPTIONS"] = f"-Duser.home={home}"
    os.environ["MMT_EXPOSURE_HOME"] = str(home / "exposure")

    def lookup(release):
        portboard.settings.FABRIC_APIS.clear()  # each lookup a new run, as each cloud job is
        try:
            return portboard.porter.fabric_apis([release])[release]
        except BaseException as error:  # noqa: BLE001 - what the job printed, whatever it raised
            return str(error)

    try:
        found = lookup("26.9")
        answers = sorted((home / "exposure" / "maps" / "fabric-api").iterdir())
        # Searching again would turn this build down: a kept answer is used without reading the builds.
        (cache / "fabric-api-0.1.0+26.9.jar").write_bytes(api_jar("99.0"))
        kept = lookup("26.9")
        # Fabric publishes a newer build for 26.9: the list the answer came from changed, so it is not served.
        (cache / "fabric-api-0.3.0+26.9.jar").write_bytes(api_jar("26.9"))
        release_list("0.1.0+26.9", "0.3.0+26.9", "0.2.0+26.8")
        fresh = lookup("26.9")
        said = lookup("26.8")
    finally:
        for k, v in saved_env.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
        portboard.settings.FABRIC_APIS.clear()
    check(tools and found == cache / "fabric-api-0.1.0+26.9.jar" and len(answers) == 1, f"the lookup reads the release list and jar, and keeps its answer: {found}, {answers}")
    check(kept == found, f"a kept answer is used while Fabric's release list is the same: {kept}")
    check(fresh == cache / "fabric-api-0.3.0+26.9.jar", f"a changed release list gets a fresh answer, not the kept one: {fresh}")
    check(isinstance(said, str) and "26.8" in said and "fabric-api-0.2.0+26.8.jar" in said and "not a whole jar" in said
          and "returned non-zero exit status" not in said, f"a failed Fabric API lookup names the release and the file that failed: {said}")

# A pull request's port check runs only the ports its change can move, compared with main's row of each, and fails when
# a mod that links on main stops linking. Main's table is a made-up one with one row per loader, so the picks are its.
repo = Path(__file__).resolve().parent.parent
portboard.settings.ROOT, portboard.settings.INDEX, portboard.settings.PINS, portboard.settings.FIRST = repo, repo / "coverage" / "changes" / "index.json", repo / "bench" / "exposure" / "mods", {"fabric": "1.14.4", "neoforge": "1.20.2"}
lo_f, lo_n = portboard.gamereleases.updates("fabric"), portboard.gamereleases.updates("neoforge")
small_f = next(u for u in lo_f if not portboard.gamereleases.big(*u))
big_f = next(u for u in lo_f if portboard.gamereleases.big(*u))
table = {"commit": "c" * 40, "run": "r", "jumps": [], "updates": [
    {"loader": "fabric", "from": big_f[0], "to": big_f[1], "big": True, "modsThatLink": 3, "links": ["a--1.jar"], "heldBy": {"b--1.jar": ["call, game: linked"], "c--1.jar": ["mixin, game: split"]}},
    {"loader": "fabric", "from": lo_f[-1][0], "to": lo_f[-1][1], "big": True, "modsThatLink": 9, "links": ["a--2.jar"]},
    {"loader": "fabric", "from": small_f[0], "to": small_f[1], "big": False, "modsThatLink": 5, "links": []}]}
plan = lambda changed, body="": portboard.portcheck.check_plan(changed, body, table)  # noqa: E731
jobs, why = plan(["docs/index.html", "README.md", "app/src/main.rs", "tools/cli/PackCheck.java"])
check(jobs == [] and "nothing the scoreboard reads" in why[-1], f"a change to nothing the scoreboard reads runs no port: {jobs}")
# A porter change runs the fewest ports that would have caught every time main lost a mod. Here main lost mods three
# times: on the big update and the newest (one port catches neither alone), on the newest, and on a jump with the small
# update; the newest catches two and the jump and the small update one each, and the update goes before the jump.
jump = (portboard.gamereleases.most_used("fabric"), lo_f[-1][1])
table["jumps"] = [{"loader": "fabric", "from": jump[0], "to": jump[1], "modsThatLink": 50, "links": []}]
p = lambda lo, frm, to: f"{lo}:{frm}:{to}"  # noqa: E731
table["losses"] = [{"commit": "1", "ports": [p("fabric", *big_f), p("fabric", *lo_f[-1])]}, {"commit": "2", "ports": [p("fabric", *lo_f[-1])]},
                   {"commit": "3", "ports": [p("fabric", *jump), p("fabric", *small_f), "fabric:0.1:0.2"]}]
jobs, why = plan(["tools/port/jar/JarPort.java"])
pairs = [(j["loader"], j["from"], j["to"]) for j in jobs]
check(pairs == [("fabric", *lo_f[-1]), ("fabric", *small_f)] and all(j["mods"] == "" for j in jobs)
      and any("sample of 2 ports" in w and "each of the 3 times" in w for w in why),
      f"a porter change runs the fewest ports that catch every loss of main's, an update before a jump, and says so: {jobs} {why}")
del table["losses"]
check(portboard.portcheck.sample("fabric", {**table, "losses": []})[0]
      == [(lo_f[-1], "sample: main has lost no fabric mod yet; the update with the most mods linking on main (9)")],
      "with no loss yet, the sample is the update with the most mods linking on main")
del table["jumps"]
# main's table keeps the losses: a sweep that finds a mod no longer linking adds its ports, and one that finds none adds nothing.
before = {"updates": [{"loader": "fabric", "from": "1", "to": "2", "links": ["a", "b"]}, {"loader": "fabric", "from": "2", "to": "3", "links": ["c"]}]}
after = {"updates": [{"loader": "fabric", "from": "1", "to": "2", "links": ["a", "d"]}, {"loader": "fabric", "from": "2", "to": "3", "links": ["c", "e"]}]}
check(portboard.mainrecord.lost_ports(before, after) == ["fabric:1:2"] and portboard.mainrecord.lost_ports(after, after) == [],
      f"a port that lost a mod is named: {portboard.mainrecord.lost_ports(before, after)}")
jobs, _ = plan(["bench/exposure/mods/fabric-" + small_f[0] + ".json", f"knowledge/standins/{lo_n[0][0]}-{lo_n[0][1]}.neoforge/x/Y.java"])
check({(j["loader"], j["from"], j["to"]) for j in jobs} == {("fabric", *small_f), ("neoforge", *lo_n[0])},
      f"changed pins or stand-ins run the update they are for, and only for its loader's: {jobs}")
jobs, _ = plan([f"knowledge/standins/{lo_n[0][0]}-{lo_n[0][1]}/x/Y.java"])
check({j["loader"] for j in jobs} == {"fabric", "neoforge"}, f"stand-ins for both loaders run the update for both: {jobs}")
jobs, _ = plan(["docs/a.md"], f"Step 80.\n**Port check:** fabric:{big_f[0]} type=call, game: linked\nfabric:{small_f[0]}:{small_f[1]} is not named")
check(jobs == [{"loader": "fabric", "from": big_f[0], "to": big_f[1], "mods": "b", "why": "named in the description, type 'call, game: linked'"}],
      f"a named port with a type checks only the mods main says that type holds back: {jobs}")
jobs, _ = plan([], f"Port check: fabric:{small_f[0]}:{small_f[1]} mods=chunky,clumps")
check(jobs[0]["mods"] == "chunky,clumps", f"named mods are the only ones ported: {jobs}")
try:
    plan([], "Port check: fabric:0.1:0.2")
    check(False, "a port the scoreboard does not read stops the plan")
except SystemExit as no:
    check("not a port the scoreboard reads" in str(no), str(no))
row = portboard.mainrecord.main_row("fabric", big_f[0], big_f[1], table)
run = {"loader": "fabric", "from": big_f[0], "to": big_f[1], "modsRead": 3, "modsThatLink": 2, "links": ["b--1.jar", "c--1.jar"],
       "verdicts": {"a--1.jar": {}, "b--1.jar": {}, "c--1.jar": {}}}
run["againstMain"] = portboard.mainrecord.against_main(row, run)
lines, freed, ok = portboard.portcheck.gate(run)
check(not ok and freed == ["b", "c"] and "a--1.jar" in lines[2], f"a mod that links on main and not now fails the check; the newly linked are its game proof: {lines}")
run["links"] = ["a--1.jar", "b--1.jar"]
run["againstMain"] = portboard.mainrecord.against_main(row, {**run, "verdicts": {"a--1.jar": {}, "b--1.jar": {}}})
check(portboard.portcheck.gate(run)[2] and portboard.portcheck.gate(run)[1] == ["b"], "a check of some mods compares only those, and passes when none stops linking")
check(portboard.portcheck.gate({**run, "againstMain": None})[2], "a port main has no row for passes, and says there was nothing to compare")
run["againstMain"] = portboard.mainrecord.against_main(row, {**run, "links": ["b--1.jar"], "verdicts": {"b--1.jar": {}}, "unreadable": {"a--1.jar": "ZipException"}})
check(not portboard.portcheck.gate(run)[2] and run["againstMain"]["no longer link"] == ["a--1.jar"],
      f"a mod that links on main and that the port can no longer read fails the check: {run['againstMain']}")
# The check compares with the base commit's own port of the same pair and mods, not main's saved table: that table is
# remade only by hand or at a release, and a merge after it (#223 on fabric 1.18 -> 1.18.1: rechiseled and
# right-click-harvest stopped linking on main) failed every later pull request for main's change. Here main's table says
# a and b link; main since stopped a, and the pull request changes nothing: the check passes against the base and names
# what is the pull request's own. Against the stale table it would fail.
stale = {"loader": "fabric", "from": big_f[0], "to": big_f[1], "links": ["a--1.jar", "b--1.jar"]}
base = {"loader": "fabric", "from": big_f[0], "to": big_f[1], "modsRead": 3, "modsThatLink": 1, "links": ["b--1.jar"],
        "verdicts": {"a--1.jar": {}, "b--1.jar": {}, "c--1.jar": {}}}
head = {**base, "againstMain": portboard.mainrecord.against_main(stale, base)}
check(not portboard.portcheck.gate(head)[2], "against main's stale table the unchanged pull request fails (what the check no longer does)")
lines, freed, ok = portboard.portcheck.gate(head, base, "main at 1234567890")
check(ok and freed == [] and "against main at 1234567890" in lines[0], f"against the base's own port an unchanged pull request passes: {lines}")
moved = {**base, "modsThatLink": 1, "links": ["c--1.jar"]}
lines, freed, ok = portboard.portcheck.gate(moved, base, "main at 1234567890")
check(not ok and freed == ["c"] and lines[2] == "  no longer link: b--1.jar" and lines[-1] == "FAIL: 1 mods that link on main at 1234567890 stop linking",
      f"a mod that links on the base and not with the pull request fails it; one it frees goes to the game proof: {lines}")
with tempfile.TemporaryDirectory() as tmp:
    (Path(tmp) / "head.json").write_text(json.dumps(head), encoding="utf-8")
    (Path(tmp) / "base.json").write_text(json.dumps(base), encoding="utf-8")
    said = E.subprocess.run([sys.executable, str(repo / "bench" / "exposure.py"), "gate", str(Path(tmp) / "head.json"), "--base", str(Path(tmp) / "base.json"),
                             "--base-name", "main at abc", "--freed", str(Path(tmp) / "freed.txt")], capture_output=True, text=True)
    check(said.returncode == 0 and "against main at abc" in said.stdout, f"gate --base compares with the base's summary: {said.stdout} {said.stderr}")


# A changed change index runs every update it makes, beside the sample: a release newly indexed between two others makes
# two updates main's index did not have.
index_now = json.loads(portboard.settings.INDEX.read_text(encoding="utf-8"))
middle = next(b for (a, b), (_, c) in zip(lo_f, lo_f[1:]) if (portboard.settings.PINS / f"fabric-{a}.json").is_file() and (portboard.settings.PINS / f"fabric-{b}.json").is_file())
index_main = {**index_now, "allVersions": [v for v in index_now["allVersions"] if v["id"] != middle]}
jobs, why = portboard.portcheck.check_plan(["coverage/changes/index.json"], "", table, index_main)
made = {(j["from"], j["to"]) for j in jobs if j["loader"] == "fabric" and "change index" in j["why"]}
before_middle = next(a for a, b in lo_f if b == middle)
check(made == {(before_middle, middle), (middle, dict(lo_f)[middle])} and any("sample" in w for w in why),
      f"a change to the change index runs the updates it makes and the sample: {made} {why}")

# The game proof of a port check: every mod it started loads on the server and in the client, or the check fails.
proof = {"loader": "fabric", "from": "1.0", "to": "1.1", "scope": ["a", "b"], "mods": 3, "loaded": 3, "clashOnly": [],
         "verdicts": {"a--a.jar": "loaded", "b--b.jar": "client-only: no server", "lib--lib.jar": "loaded"}, "notStarted": {},
         "client": {"mods": 3, "loaded": 3, "verdicts": {"a--a.jar": "loaded", "b--b.jar": "loaded", "lib--lib.jar": "loaded"}}}
check(portboard.portcheck.load_gate(proof)[1], f"a proof whose mods load on both sides passes: {portboard.portcheck.load_gate(proof)[0]}")
for broken, what in ((dict(proof, verdicts={**proof["verdicts"], "a--a.jar": "failed: mixin"}), "a mod that fails on the server"),
                     (dict(proof, client={**proof["client"], "verdicts": {**proof["client"]["verdicts"], "b--b.jar": "failed: crash"}}), "a mod that fails in the client"),
                     (dict(proof, client={**proof["client"], "notRun": "no display"}), "a client that could not be run")):
    lines, ok = portboard.portcheck.load_gate(broken)
    check(not ok and any("FAIL" in x for x in lines), f"{what} fails the check: {lines}")
# A mod the proof could not start is said apart with why and fails nothing: neoforge 1.21.7 -> 1.21.8 started 1 of 3
# freed mods, which loaded on both sides, and failed counting the 2 it never started as "started and do not load".
unstarted = dict(proof, mods=1, loaded=1, verdicts={"a--a.jar": "loaded"}, client={"mods": 1, "loaded": 1, "verdicts": {"a--a.jar": "loaded"}},
                 notStarted={"b--b.jar": ["metadata for neoforge"], "c--c.jar": ["metadata for neoforge"]}, scope=["a", "b", "c"])
lines, ok = portboard.portcheck.load_gate(unstarted)
check(ok and not any("FAIL" in x for x in lines) and "1/1 load on the server" in lines[0]
      and lines[-3:] == ["  not started (2, not counted):", "    b--b.jar: metadata for neoforge", "    c--c.jar: metadata for neoforge"],
      f"mods the proof could not start are said apart and fail nothing: {lines}")
# Nothing it can start (neoforge 1.20.2 -> 1.20.3: the one freed mod has no NeoForge metadata): it says so and passes.
nothing = dict(proof, mods=0, loaded=0, verdicts={}, client={"mods": 0, "loaded": 0, "verdicts": {}}, notStarted={"a--a.jar": ["metadata for neoforge"]})
lines, ok = portboard.portcheck.load_gate(nothing)
check(ok and "nothing it can start" in lines[0] and "    a--a.jar: metadata for neoforge" in lines, f"a proof that could start nothing says so and passes: {lines}")
# The receipt says where the proof's time went, each side's starts summed with mmt's stages in them, and the gate says it.
times = {"port": 53.2, **portboard.gameload.start_times([{"side": "server", "seconds": 70.0, "stages": {"server-start": 50.0, "read": 12.0}},
                                         {"side": "client", "seconds": 60.5, "stages": {"read": 30.0}},
                                         {"side": "client", "seconds": 45.0, "stages": {"read": 2.0, "client": 40.0}}])}
check(times["client"] == {"starts": 2, "seconds": 105.5, "stages": {"read": 32.0, "client": 40.0}}, f"each side's starts are summed: {times}")
check(portboard.portcheck.load_gate(dict(proof, time=times))[0][1] == "  time: port 53 s; server 1 start 70 s (server-start 50 s, read 12 s); client 2 starts 106 s (client 40 s, read 32 s)",
      f"the gate says where the proof's time went: {portboard.portcheck.load_gate(dict(proof, time=times))[0]}")
# A client-only mod that fails in the client is one started mod that does not load (fabric 1.18.1 -> 1.18.2), and the
# first line counts it as client-only on the server, so the lines agree with the FAIL.
client_only = dict(proof, mods=1, loaded=0, verdicts={"b--b.jar": "client-only: its own metadata"}, scope=["b"],
                   client={"mods": 1, "loaded": 0, "verdicts": {"b--b.jar": "failed: Description: Rendering screen"}})
lines, ok = portboard.portcheck.load_gate(client_only)
check(not ok and "0/1 load on the server (1 client-only, not loaded by a server), 0/1 in the client" in lines[0]
      and lines[-1] == "FAIL: 1 of the 1 mods the check started do not load in game", f"a client-only mod failing in the client fails the check: {lines}")
clashed = dict(proof, clashOnly=["a--a.jar"], verdicts={**proof["verdicts"], "a--a.jar": "clash: loads alone, fails next to the other mods: x"})
check(portboard.portcheck.load_gate(clashed)[1], "a mod that loads alone and fails only next to the others is said and passes")

# A game proof reads the whole update's report unless it proves some mods and a newer part-run read each of them: a part
# of the update never stands for the whole.
with tempfile.TemporaryDirectory() as tmp:
    work = Path(tmp)
    whole, part = work / "report.json", work / "report-part.json"
    whole.write_text(json.dumps({"mods": [{"mod": "a--a.jar"}, {"mod": "b--b.jar"}]}), encoding="utf-8")
    part.write_text(json.dumps({"mods": [{"mod": "b--b.jar"}]}), encoding="utf-8")
    os.utime(whole, (1, 1))
    check(portboard.gameload.proof_report(work, None) == whole, "a proof of every mod reads the whole report though a part-run's is newer")
    check(portboard.gameload.proof_report(work, ["b"]) == part, "a proof of the mods a newer part-run read reads that run's report")
    check(portboard.gameload.proof_report(work, ["a", "b"]) == whole, "a proof of a mod the part-run did not read reads the whole report")

# Two sweep jobs from one release, side by side in one process, read the jars' names into reports of their own.
with tempfile.TemporaryDirectory() as tmp:
    saved = portboard.settings.ROOT, portboard.settings.HOME, portboard.porter.side_by_side, portboard.porter.jar_tools, portboard.porter.shipped_obfuscated, portboard.cachekeys.keyed
    portboard.settings.ROOT, portboard.settings.HOME = Path(tmp), Path(tmp) / "home"
    portboard.porter.jar_tools, portboard.porter.shipped_obfuscated, portboard.cachekeys.keyed = (lambda: "classes"), (lambda loader, version: True), (lambda kind, *names: Path(tmp) / kind)
    reports, both = [], threading.Barrier(2)

    def named(command, jars, report):
        reports.append(report)
        both.wait(5)
        return []
    portboard.porter.side_by_side = named
    try:
        jar = Path(tmp) / "m.jar"
        jar.write_bytes(b"x")
        threads = [threading.Thread(target=portboard.porter.mojang_names, args=("fabric", "1.0", [jar])) for _ in range(2)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
    finally:
        portboard.settings.ROOT, portboard.settings.HOME, portboard.porter.side_by_side, portboard.porter.jar_tools, portboard.porter.shipped_obfuscated, portboard.cachekeys.keyed = saved
check(len(set(reports)) == 2, f"two reads of one release's names at once write two reports: {reports}")

# A sweep of an old commit (--at) runs in its unpacked tree, which has no reader of main's table: it compares with nothing
# and still keeps its summaries.
with tempfile.TemporaryDirectory() as tmp:
    saved, kept_module = (portboard.settings.ROOT, dict(portboard.mainrecord.MAIN)), sys.modules.pop("generated", None)
    portboard.settings.ROOT = Path(tmp)
    (portboard.settings.ROOT / "bench").mkdir()
    portboard.mainrecord.MAIN.clear()
    try:
        none = portboard.mainrecord.main_table()
    finally:
        portboard.settings.ROOT = saved[0]
        portboard.mainrecord.MAIN.clear()
        portboard.mainrecord.MAIN.update(saved[1])
        if kept_module is not None:
            sys.modules["generated"] = kept_module
check(none is None, "a tree with no reader of main's table compares with no table")

print(f"exposure: {checks} checks passed")

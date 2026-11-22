"""The port scoreboard (bench/exposure.py) counts what users would see.
- Its shared cache (~/.mmt/exposure) is safe for worktrees that run at once: entries are keyed by the content of the
  tools that build them, and a finished file is moved into place whole, the first one kept. Before, entries were keyed
  by file times, and one worktree's newer tools wiped a folder another was reading (#126).
- A copy of a platform module (a Fabric API module) nested in a mod is not what runs: Fabric Loader keeps the newest copy
  of a mod id, the platform's. What only that copy references does not hold the mod back; a copy of a module the platform
  no longer has does.
Run: python bench/test_exposure.py"""
import io
import json
import os
import zipfile
import sys
import tempfile
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import exposure as E  # noqa: E402

checks = 0


def check(ok, label):
    global checks
    if not ok:
        raise AssertionError(label)
    checks += 1


with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    # Two worktrees: the same tools, then one with an edited GameDiff.
    for tree in ("a", "b"):
        (root / tree / "tools" / "cli").mkdir(parents=True)
        for name in E.MAP_SOURCES:
            (root / tree / "tools" / name).write_text(f"// {name}\n", encoding="utf-8")
    E.HOME = root / "home"

    def key_in(tree):
        E.ROOT = root / tree
        return E.tools_key(*E.MAP_SOURCES)

    check(key_in("a") == key_in("b"), "worktrees with the same tools share entries")
    time.sleep(0.01)
    os.utime(root / "a" / "tools" / "GameDiff.java")  # a newer file time alone changes nothing
    check(key_in("a") == key_in("b"), "a file touched without a change keeps its entries")
    (root / "b" / "tools" / "GameDiff.java").write_text("// GameDiff, edited\n", encoding="utf-8")
    check(key_in("a") != key_in("b"), "worktrees with other tools get entries of their own")

    E.ROOT = root / "a"
    first = E.keyed("maps", *E.MAP_SOURCES)
    (first / "neoforge-1.0-2.0.json").write_text("{}", encoding="utf-8")
    E.ROOT = root / "b"
    second = E.keyed("maps", *E.MAP_SOURCES)
    check(first != second and (first / "neoforge-1.0-2.0.json").is_file(), "making one worktree's entry leaves the other's in place")
    # A key no run has used for over a day is removed when a new one is made; a fresh one is kept.
    stale = first / "used"
    os.utime(stale, (time.time() - 2 * 86400, time.time() - 2 * 86400))
    (root / "b" / "tools" / "VersionMap.java").write_text("// VersionMap, edited\n", encoding="utf-8")
    third = E.keyed("maps", *E.MAP_SOURCES)
    check(not first.exists() and second.exists() and third.exists(), "only a key unused for a day is cleared")

    # Runs beside each other finishing the same file: every reader sees a whole file, and the first one is kept.
    target = third / "same.json"
    seen, start = [], threading.Barrier(8)

    def writer(i):
        part = E.part_of(target)
        part.write_text(f'{{"writer": {i}, "pad": "{"x" * 200000}"}}', encoding="utf-8")
        start.wait()
        E.publish(part, target)
        seen.append(target.read_text(encoding="utf-8"))

    threads = [threading.Thread(target=writer, args=(i,)) for i in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    check(len(seen) == 8 and all(s.endswith('"}') for s in seen), "no reader sees half a file")
    check(len(set(seen)) == 1, "one writer's file is kept and the others are dropped")
    check(not list(third.glob("*.part")), "no part file is left behind")



def jar(files):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as z:
        for name, data in files.items():
            z.writestr(name, data)
    return buffer.getvalue()


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
    out = E.without_platform_copies(report, {"fabric-api-base", "fabric-api"}, {"m.jar": mod})
    m = out["mods"][0]["unlinked"]
    check(len(m) == 1 and "old-module" in m[0], f"a nested copy of a platform module is not counted; one of a module the platform lacks is: {m}")
    check(out["unlinkedByMods"] == [{"member": "call: net.minecraft.Gone.run()V", "mods": 2}] and out["linked"] == 1,
          f"each reference counts once a mod and the mods that link are counted again: {out['unlinkedByMods']} {out['linked']}")
    check(E.nested_ids(mod.read_bytes()) == {"m": "", "fabric-api-base": "api-base-1.jar", "fabric-gone-v1": "old-module-1.jar"},
          "every id in a jar and its nested jars is read with the nested jar's name")

game_class = b"\xca\xfe\xba\xbe....net/minecraft/world/item/ElytraItem"
payload = jar({"gg/x/Mixin.class": game_class})
check(E.hidden_code(jar({"fabric.mod.json": json.dumps({"id": "e"}), "payload-1.jar": payload})) == "payload-1.jar",
      "game code in a jar no loader metadata lists is found: the port never reads it")
check(E.hidden_code(jar({"fabric.mod.json": json.dumps({"id": "e", "jars": [{"file": "META-INF/jars/payload.jar"}]}), "META-INF/jars/payload.jar": payload})) is None
      and E.hidden_code(jar({"META-INF/jarjar/metadata.json": json.dumps({"jars": [{"path": "META-INF/jarjar/p.jar"}]}), "META-INF/jarjar/p.jar": payload})) is None,
      "a jar fabric.mod.json or NeoForge's jarjar list nests is read by the port, so it is not hidden")
check(E.hidden_code(jar({"fabric.mod.json": "{}", "lib.jar": jar({"org/lib/A.class": b"\xca\xfe\xba\xbe org/lib"})})) is None,
      "a library jar with no game code is not the mod's hidden code")
rep = {"linked": 1, "mods": [{"mod": "e.jar", "unlinked": [], "silent": []}], "unlinkedByMods": []}
with tempfile.TemporaryDirectory() as tmp:
    e = Path(tmp) / "e.jar"
    e.write_bytes(jar({"fabric.mod.json": "{}", "payload-1.jar": payload}))
    out = E.with_hidden_code_unread(rep, {"e.jar": e})
check(out["linked"] == 0 and "payload-1.jar" in out["mods"][0].get("error", ""), "such a mod is reported as not read instead of linking")

with tempfile.TemporaryDirectory() as tmp:
    capes = Path(tmp) / "capes.jar"
    capes.write_bytes(jar({"fabric.mod.json": json.dumps({"id": "capes", "depends": {"fabricloader": "*", "fabric-api": "*", "minecraft": "1.19.4"}})}))
    other = Path(tmp) / "other.jar"
    other.write_bytes(jar({"fabric.mod.json": json.dumps({"id": "o", "depends": {"cloth-config": "*"}})}))
    fa = lambda jar: E.unmet(E.needs(jar, "fabric"), {"fabric-api", "fabric-api-base"})  # noqa: E731
    check(fa(capes) == set() and E.needs(capes, "fabric") == {"fabric-api"} and fa(other) == {"cloth-config"},
          "a mod that needs only Fabric API is started with the platform; one that needs another mod waits for it")
    kotlin = Path(tmp) / "kt.jar"
    kotlin.write_bytes(jar({"fabric.mod.json": json.dumps({"id": "kt", "depends": {"fabric-language-kotlin": "*"},
                                                           "entrypoints": {"main": [{"adapter": "kotlin", "value": "a.B"}]}})}))
    flk = Path(tmp) / "flk.jar"
    flk.write_bytes(jar({"fabric.mod.json": json.dumps({"id": "fabric-language-kotlin", "languageAdapters": {"kotlin": "a.Adapter"}})}))
    check(E.unmet(E.needs(kotlin, "fabric"), E.provides(flk, "fabric")) == set() and E.unmet(E.needs(kotlin, "fabric"), {"fabric-language-kotlin"}) == {"adapter:kotlin"},
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
    check(E.needs(kt_mod, "neoforge") == {"loader:kotlinlang", "libipn"} and E.needs(lang, "neoforge") == set() and "loader:*" in E.provides(lang, "neoforge"),
          "a NeoForge mod needs its library mods and its language loader; a library with only nested metadata needs nothing")
    check(E.unmet(E.needs(kt_mod, "neoforge"), E.provides(lang, "neoforge")) == {"libipn"}
          and E.unmet(E.needs(kt_mod, "neoforge"), E.provides(lang, "neoforge") | E.provides(lib, "neoforge")) == set(),
          "the language loader is answered by the jar that carries one, the library by its mod id")
    check(E.needs(both, "neoforge") == set() and E.needs(both, "fabric") == {"fabric-api"}, "a jar for both loaders is read as the pack's loader")
    check(E.needs(Path(tmp) / "other.jar", "neoforge") is None, "a jar with no metadata for the loader is nothing the loader starts")
    # The libraries come from what each mod's Modrinth version lists as required, and from what those list in turn; the
    # release for the new version first, the old one (ported) when the project has none.
    folder = Path(tmp) / "top"
    folder.mkdir()
    (folder / "ipn--ktmod.jar").write_bytes(kt_mod.read_bytes())
    (folder / "lone--lone.jar").write_bytes(jar({"META-INF/neoforge.mods.toml": toml("lone", deps=["nowhere"])}))
    pins = [{"slug": "ipn", "file": "ktmod.jar", "project": "P-ipn", "version": "v-ipn"}, {"slug": "lone", "file": "lone.jar", "project": "P-lone", "version": "v-lone"}]
    listed = {"v-ipn": ["P-libipn"], "v-libipn": ["P-lang"], "v-lone": []}
    files = {("P-libipn", "2.0"): (lib, "v-libipn"), ("P-lang", "1.0"): (lang, "v-lang")}
    real = E.required_projects, E.library
    E.required_projects, E.library = (lambda v: listed.get(v, [])), (lambda project, loader, version: files.get((project, version)))
    try:
        chosen, ready, to_port, left = E.choose("neoforge", "1.0", "2.0", ["ipn--ktmod.jar", "lone--lone.jar"], folder, pins, set())
    finally:
        E.required_projects, E.library = real
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
    E.required_projects, E.library = (lambda v: listed.get(v, [])), (lambda project, loader, version: files.get((project, version)))
    try:
        chosen, ready, to_port, left = E.choose("fabric", "1.0", "2.0", ["hud--hud.jar"], folder, pins, {"fabric-api", "fabric-api-base"})
    finally:
        E.required_projects, E.library = real
    check([j.name for j in chosen] == ["hud--hud.jar"] and list(ready) == ["cloth.jar"] and not to_port,
          f"a library the platform already is is not added again: {chosen} {ready} {to_port}")

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
    blamed = E.named_in_errors(Path(tmp) / "result.json", mods, ["hud.jar", "stats.jar"])
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
    check(E.mod_ids(mods / "ffapi.jar") == {"fabric_api", "fabric_data_attachment_api_v1"}, "a jar's mod ids include those of the jars it nests")
    blamed = E.named_in_errors(Path(tmp) / "result.json", mods, ["top.jar", "ffapi.jar"])
check(list(blamed) == ["ffapi.jar"], f"a failing nested mod blames the jar that ships it: {blamed}")

# A game proof runs the updates asked for, LOADER:FROM taking the next release; --mods picks pinned mods by slug, and one
# that does not link is left out, as the whole run leaves it out.
check(E.load_plan("neoforge:1.21.1:1.21.2, fabric:1.21.1") == ["neoforge:1.21.1:1.21.2", "fabric:1.21.1:1.21.2"], "an update is LOADER:FROM:TO, or LOADER:FROM for the next release")
for bad in ("forge:1.21.1", "neoforge", "fabric:0.1"):
    try:
        E.load_plan(bad)
        check(False, f"{bad} is refused")
    except SystemExit:
        pass
pinned = [{"slug": "chunky", "file": "Chunky.jar"}, {"slug": "clumps", "file": "Clumps.jar"}, {"slug": "jei", "file": "jei.jar"}]
sys.stdout = io.StringIO()
try:
    picked, skipped = E.only_mods(["chunky--Chunky.jar", "jei--jei.jar"], pinned, ["chunky", "clumps"])
    said = sys.stdout.getvalue()
finally:
    sys.stdout = sys.__stdout__
check(picked == ["chunky--Chunky.jar"] and list(skipped) == ["clumps--Clumps.jar"] and "Clumps.jar: does not link" in said,
      f"--mods starts the asked mods that link and names the rest: {picked} {skipped} {said!r}")
try:
    E.only_mods([], pinned, ["nonesuch"])
    check(False, "a slug that is not pinned stops the run")
except SystemExit:
    pass

# --mods where no asked mod links is a finished proof: a receipt with no game verdicts and why each mod was not started,
# not a port of zero jars that fails with none.
with tempfile.TemporaryDirectory() as tmp:
    real = E.ROOT, E.PINS, E.HOME
    E.ROOT, E.PINS, E.HOME = Path(tmp), Path(tmp) / "pins", Path(tmp) / "home"
    E.PINS.mkdir()
    (E.PINS / "neoforge-9.1.json").write_text(json.dumps({"mods": pinned}), encoding="utf-8")
    report = Path(tmp) / ".runs" / "exposure" / "jars-neoforge-9.1-9.2" / "report.json"
    report.parent.mkdir(parents=True)
    report.write_text(json.dumps({"mods": [{"mod": "chunky--Chunky.jar", "unlinked": []}, {"mod": "clumps--Clumps.jar", "unlinked": ["x"]}]}), encoding="utf-8")
    sys.stdout = io.StringIO()
    try:
        empty = E.load("neoforge", "9.1", "9.2", False, ["clumps"], Path(tmp) / "out" / "load.json")
    finally:
        sys.stdout = sys.__stdout__
        E.ROOT, E.PINS, E.HOME = real
    kept = json.loads((Path(tmp) / "out" / "load.json").read_text(encoding="utf-8"))
check(kept == empty and kept["mods"] == 0 and kept["verdicts"] == {} and kept["client"]["verdicts"] == {} and kept["scope"] == ["clumps"]
      and kept["notStarted"] == {"clumps--Clumps.jar": ["it does not link after the port"]},
      f"a proof with nothing to start keeps a receipt saying why: {kept}")

# Two game proofs of an update compare by each mod's verdict kind on both sides, over the mods both started.
main_run = {"loader": "neoforge", "from": "1.21.1", "to": "1.21.2", "mods": 3,
            "verdicts": {"a--a.jar": "loaded", "b--b.jar": "failed: its mixin x", "c--c.jar": "client-only: y"},
            "client": {"verdicts": {"a--a.jar": "loaded", "c--c.jar": "failed: z"}}}
branch_run = {"loader": "neoforge", "from": "1.21.1", "to": "1.21.2", "scope": ["a", "b"], "mods": 2,
              "verdicts": {"a--a.jar": "loaded", "b--b.jar": "loaded"}, "client": {"verdicts": {"a--a.jar": "failed: crash", "b--b.jar": "loaded"}}}
lines = E.compare_loads(main_run, branch_run)
check("server: 1/2 the same; loaded 1 -> 2" in lines and "  b--b.jar: failed -> loaded" in lines and "  a--a.jar: loaded -> failed (failed: crash)" in lines
      and "  b--b.jar: not started -> loaded" in lines and not any("c--c.jar" in x for x in lines),
      f"compare names each verdict that moved; a mod a focused run was not asked for is not one: {lines}")
check(E.compare_loads(main_run, main_run)[-1] == "no verdict moved", "a proof against itself moves nothing")
# A full run that no longer starts a mod main loaded (it now stops at the link check) has moved, whatever its count.
full = {"loader": "neoforge", "from": "1.21.1", "to": "1.21.2", "scope": "all", "mods": 2,
        "verdicts": {"a--a.jar": "loaded", "b--b.jar": "failed: its mixin x"}, "client": {"verdicts": {"a--a.jar": "loaded"}},
        "notStarted": {"c--c.jar": ["it does not link after the port"]}}
lines = E.compare_loads(main_run, full)
check("  c--c.jar: client-only -> not started (it does not link after the port)" in lines and lines[-1] != "no verdict moved",
      f"a full run names each mod main started that it did not: {lines}")
focused = {**full, "scope": ["a", "c"], "verdicts": {"a--a.jar": "loaded"}}
lines = E.compare_loads(main_run, focused)
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
order = E.nearest(rows, {"e.jar": ["data id gone: minecraft:thing x (in data/e.json)"]}, said)
check([t["mod"] for t in order] == ["c.jar", "e.jar", "w.jar", "b.jar", "a.jar"],
      f"mods come in the order they finish, the ones the game answers first among equals: {[t['mod'] for t in order]}")
check(order[0]["alsoFrees"] == ["d.jar"] and [t["modsDone"] for t in order] == [2, 3, 4, 5, 6] and [t["fixes"] for t in order] == [1, 2, 3, 5, 7],
      f"each turn counts the fixes so far and every mod they finish: {[(t['fixes'], t['modsDone']) for t in order]}")
check([m["member"] for m in order[4]["members"]] == ["call: g.A.x()V", "call: g.A.y()V"] and order[0]["members"][0]["game"] == "replaced",
      "a turn lists only what earlier turns left, each reference once with what the game did about it")
longer = {"mod": "b.jar", "unlinked": [f"b.jar: call: g.B.m{i}()V" for i in range(5)]}
check([t["mod"] for t in E.nearest([rows[0], longer], None, {})] == ["a.jar", "b.jar"], "a mod with more left than another comes after it")

# The same list by type of break: a way to handle a type finishes every mod only that type holds back.
kinds = [
    {"mod": "a.jar", "unlinked": ["a.jar: call: g.X.one()V (in a.A)", "a.jar: call: g.Y.two()V"]},
    {"mod": "b.jar", "unlinked": ["b.jar: call: g.X.one()V", "b.jar: field: g.F.gone"]},
    {"mod": "c.jar", "unlinked": ["c.jar: field: g.F.gone"]},
    {"mod": "d.jar", "unlinked": ["d.jar: call: g.Z.three()V"]},
    {"mod": "e.jar", "unlinked": []},
]
verdicts = {"call: g.X.one()V": {"game": "linked"}, "call: g.Y.two()V": {"game": "linked"}, "call: g.Z.three()V": {"game": "linked"}, "field: g.F.gone": {"game": "split"}}
table = E.by_type(kinds, None, verdicts)
check([(t["type"], t["finishes"], t["mods"], t["references"]) for t in table] == [("call, game: linked", 2, 3, 3), ("field, game: split", 1, 2, 1)],
      f"a type counts the mods it alone holds back, the mods that have it and its references: {table}")
check(table[0]["examples"][0] == "call: g.X.one()V", "a type's first example is its most-used reference")
verdicts["call: g.Y.two()V"] = {"game": "redesigned"}
check(next(t for t in E.by_type(kinds, None, verdicts) if t["type"] == "call, game: linked")["finishes"] == 1,
      "a mod held by two types is finished by neither alone")

# Jumps: the ports people ask for, from each family's most-used release to the next family's and to the newest.
with tempfile.TemporaryDirectory() as tmp:
    E.INDEX = Path(tmp) / "index.json"
    E.INDEX.write_text(json.dumps({
        "allVersions": [{"id": v, "status": "indexed"} for v in ("1.0", "1.1", "2.0", "2.1", "3.0")],
        "families": {"list": [{"id": "1", "versions": ["1.0", "1.1"], "hub": "1.0", "mods": {"1.0": 9, "1.1": 2}},
                              {"id": "2", "versions": ["2.0", "2.1"], "hub": "2.1", "mods": {"2.0": 1, "2.1": 5}},
                              {"id": "3", "versions": ["3.0"], "hub": "3.0", "mods": {"3.0": 4}}]}}), encoding="utf-8")
    E.FIRST = {"fabric": "1.0", "neoforge": "2.0"}
    check(E.jumps("fabric") == [("1.0", "2.1"), ("1.0", "3.0")],
          f"each hub to the next hub, and the most-used release to the newest, without the pairs one update apart: {E.jumps('fabric')}")
    check(E.jumps("neoforge") == [], "a loader read from a later release has only its own hubs, and a pair one update apart is no jump")
    check(E.big("1.1", "2.0") and not E.big("1.0", "1.1"), "an update into another family is a big one")

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
        table = E.join(runs)
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
        table = E.join(runs)
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
        after = E.join(runs)
        lines = E.compare(before, after)
        same = E.compare(after, after)
    finally:
        sys.stdout = sys.__stdout__
    check(lines[0] == "every update: 14 -> 16 of 20 (+2)" and lines[1] == "big updates: 4 -> 6 of 10 (+2)" and "updates higher: fabric 1.1 -> 2.0 4 -> 6" in lines,
          f"compare names the counts and the update that moved: {lines}")
    check(same[3:] == ["no update, jump or type of break moved"], f"a table against itself moves nothing: {same}")
    measures = Path(tmp) / "measures.json"
    measures.write_text(json.dumps({"measures": [{"id": m, "points": [{"date": "2026-01-01", "value": 14 if m.endswith("every-update") else 4 if m.endswith("big-updates") else 1, "source": "x"}]}
                                                 for m in E.MAIN_MEASURES]}), encoding="utf-8")
    main_table = Path(tmp) / "all.json"
    main_table.write_text(json.dumps(before), encoding="utf-8")
    (Path(tmp) / "sweep.json").write_text(json.dumps(after), encoding="utf-8")
    repo, E.ROOT = E.ROOT, Path(__file__).resolve().parent.parent
    head = os.popen(f'git -C "{E.ROOT}" rev-parse HEAD').read().strip()
    sys.stdout = io.StringIO()
    try:
        E.record_main(Path(tmp) / "sweep.json", head, "https://example.invalid/runs/1", measures, main_table, "2026-02-01")
        E.record_main(Path(tmp) / "sweep.json", head, "https://example.invalid/runs/2", measures, main_table, "2026-02-02")
    finally:
        sys.stdout = sys.__stdout__
        E.ROOT = repo
    saved = json.loads(main_table.read_text(encoding="utf-8"))
    points = {m["id"]: m["points"] for m in json.loads(measures.read_text(encoding="utf-8"))["measures"]}
    every = points["port.jar-links.every-update"]
    check(saved["commit"] == head and saved["run"].endswith("/runs/2") and saved["total"]["link"] == 16, "main's table says the commit and run it came from")
    check([p["value"] for p in every] == [14, 16, 16] and every[1]["n"] == 20 and every[1]["note"].startswith("+2 on 14: ") and "fabric 1.1 -> 2.0 4 -> 6" in every[1]["note"]
          and head[:10] in every[1]["source"], f"each sweep of main adds a point saying what moved: {every}")
    check(every[2]["note"] == "the same", f"a sweep that moved nothing says so: {every[2]}")
    check([p["value"] for p in points["port.jar-links.newest"]] == [1, 1, 1] and points["port.jar-links.big-updates"][1]["value"] == 6, "each measure reads its own total")
    # Sweeps of main may finish out of order: an older commit's table never replaces a newer one's.
    parent = os.popen(f'git -C "{Path(__file__).resolve().parent.parent}" rev-parse HEAD~1').read().strip()
    repo, E.ROOT = E.ROOT, Path(__file__).resolve().parent.parent
    sys.stdout = io.StringIO()
    try:
        late = E.record_main(Path(tmp) / "sweep.json", parent, "https://example.invalid/runs/0", measures, main_table, "2026-02-03")
    finally:
        sys.stdout = sys.__stdout__
        E.ROOT = repo
    check(late is None and json.loads(main_table.read_text(encoding="utf-8"))["commit"] == head
          and len(json.loads(measures.read_text(encoding="utf-8"))["measures"][0]["points"]) == 3, "a sweep of an older commit that finishes later is not recorded")

# Jars are read on every processor at once; the rows come back as one process would have given them.
with tempfile.TemporaryDirectory() as tmp:
    folder = Path(tmp)
    names = [f"mod{i:03}.jar" for i in range(150)]
    for i, name in enumerate(names):
        (folder / name).write_bytes(b"x" * (1 + (i * 37) % 97) * 1000)
    (folder / "big.jar").write_bytes(b"x" * 2_000_000)
    given = [folder / "big.jar"] + [folder / n for n in names]
    E.CORES = 16
    dealt = E.piles(given)
    sizes = [sum(j.stat().st_size for j in pile) for pile in dealt]
    check(len(dealt) == 4 and sorted(j.name for pile in dealt for j in pile) == sorted(j.name for j in given), "every jar is in exactly one of four piles")
    check(max(sizes) - min(sizes) <= 2_000_000 and [folder / "big.jar"] in [pile[:1] for pile in dealt],
          f"the piles weigh about the same, the biggest jar dealt first: {sizes}")
    E.CORES = 4
    check(len(E.piles(given)) == 1, "a 4-processor runner has none to spare: one pile")
    E.CORES = 64
    check(len(E.piles(given)) == 151 // E.PILE and len(E.piles(given[:5])) == 1, "a pile is not made for a handful of jars, however many processors there are")
    tool = folder / "tool.py"
    tool.write_text("import json, sys\nargs = sys.argv[1:]\nat = args.index('--report')\njars = args[at + 2:]\n"
                    "if any('mod013' in j for j in jars) and 'fail' in args:\n    sys.exit(3)\n"
                    "json.dump({'mods': [{'mod': j.replace(chr(92), '/').rsplit('/', 1)[1], 'heap': args[0]} for j in jars]}, open(args[at + 1], 'w'))\n", encoding="utf-8")
    E.CORES = 16
    rows = E.side_by_side([sys.executable, str(tool), "-Xmx10g"], given, folder / "report.json")
    check([r["mod"] for r in rows] == [j.name for j in given], "rows come back in the order the jars were given, each once")
    check(not list(folder.glob("report.*.json")), "the piles' own reports are removed")
    try:
        E.side_by_side([sys.executable, str(tool), "fail"], given, folder / "report.json")
        failed = False
    except SystemExit:
        failed = True
    check(failed, "one pile failing fails the run")

print(f"exposure: {checks} checks passed")

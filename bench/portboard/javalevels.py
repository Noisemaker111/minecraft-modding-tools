"""Which Java a release runs on and which a mod's classes need, so a mod built for a newer Java is told apart from one that does not port."""
import functools
import json
import os
import re
import urllib.parse
import urllib.request
from pathlib import Path

from portboard import cachekeys, porter, settings


def java_check_key():
    """The source of the Java check jar_port runs on each ported jar (JAVA_CHECK): a kept port holds its answer."""
    import inspect
    return cachekeys.content_key(*(inspect.getsource(globals()[name]).encode() for name in JAVA_CHECK))


def version_meta(version):
    """The release's own version.json from Mojang, kept in ~/.mmt/exposure/versions/."""
    cache = settings.HOME / "versions" / f"{version}.json"
    if cache.is_file():
        return json.loads(cache.read_text(encoding="utf-8"))
    kept = Path(os.path.expanduser("~")) / ".mmt" / "mappings" / f"version-{version}.json"
    if kept.is_file():
        return json.loads(kept.read_text(encoding="utf-8"))
    manifest = json.load(urllib.request.urlopen(urllib.request.Request("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", headers=settings.AGENT), timeout=60))
    url = next(v["url"] for v in manifest["versions"] if v["id"] == version)
    meta = json.load(urllib.request.urlopen(urllib.request.Request(url, headers=settings.AGENT), timeout=60))
    cache.parent.mkdir(parents=True, exist_ok=True)
    part = cachekeys.part_of(cache)
    part.write_text(json.dumps(meta), encoding="utf-8")
    cachekeys.publish(part, cache)
    return meta


def release_java(version):
    """The Java the release ships for (its version.json's javaVersion), as the launcher and pack-check run it."""
    return int((version_meta(version).get("javaVersion") or {}).get("majorVersion") or 8)


# The annotations that make NeoForge (and Forge) load a class while it loads mods: the mod's own class and its event
# subscribers. Matched as descriptors in the class file's constant pool.
MOD_ANNOTATIONS = (b"Lnet/neoforged/fml/common/Mod;", b"Lnet/neoforged/fml/common/EventBusSubscriber;", b"Lnet/neoforged/fml/common/Mod$EventBusSubscriber;",
                   b"Lnet/minecraftforge/fml/common/Mod;", b"Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;")


def start_classes(z, loader, side):
    """{class file: what it is} for the classes the loader loads while a game of side ("server" or "client") starts:
    the mixins of the jar's mixin configs for that side, Fabric's own entrypoints for it (main, preLaunch and the side's),
    and NeoForge's @Mod classes and event subscribers. A class a jar ships that none
    of these is (a multi-loader jar's Forge classes, a Quilt plugin, a config screen) is only loaded if something calls it."""
    names = set(z.namelist())
    meta = metadata(z, loader) or {}
    other = "server" if side == "client" else "client"
    out, configs = {}, []
    if loader == "fabric":
        for kind, entries in (meta.get("entrypoints") or {}).items():
            # Another mod's entrypoint (Mod Menu's, ViaFabric's) is loaded only when that mod is in the pack and calls it.
            if kind not in ("main", "preLaunch", side):
                continue
            for entry in entries if isinstance(entries, list) else [entries]:
                value = entry if isinstance(entry, str) else entry.get("value", "") if isinstance(entry, dict) else ""
                out[value.split("::")[0].replace(".", "/") + ".class"] = f"{kind} entrypoint"
        for m in meta.get("mixins") or []:
            config = m if isinstance(m, str) else m.get("config", "") if isinstance(m, dict) else ""
            if isinstance(m, dict) and m.get("environment") == other:
                continue
            configs.append(config)
    else:
        configs += [m.get("config", "") for m in meta.get("mixins", []) if isinstance(m, dict)]
        if "META-INF/MANIFEST.MF" in names:
            for line in z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace").splitlines():
                if line.startswith("MixinConfigs:"):
                    configs += [c.strip() for c in line.split(":", 1)[1].split(",")]
        for n in names:
            if n.endswith(".class") and not n.startswith("META-INF/"):
                data = z.read(n)
                if any(a in data for a in MOD_ANNOTATIONS):
                    out[n] = "@Mod class"
    for config in configs:
        if config not in names:
            continue
        try:
            mixins = json.loads(z.read(config).decode("utf-8", "replace"), strict=False)
        except ValueError:
            continue
        package = (mixins.get("package") or "").replace(".", "/")
        for group in ("mixins", side):
            for name in mixins.get(group) or []:
                if isinstance(name, str):
                    out[f"{package}/{name.replace('.', '/')}.class"] = f"mixin ({config})"
    return {n: why for n, why in out.items() if n in names}


def admits_java(spec, java):
    """Whether a Fabric "java" dependency (">=22", ">=17 <21", "1.8", "*", or a list of these) lets a mod load on that
    Java. One it cannot read lets it."""
    if spec is None:
        return True
    if isinstance(spec, list):
        return any(admits_java(one, java) for one in spec)
    for part in str(spec).split():
        m = re.fullmatch(r"(>=|<=|>|<|=|\^|~)?(\d+)(?:\.(\d+))?[.\dx]*", part)
        if not m:
            continue
        op, major, minor = m.group(1) or "=", int(m.group(2)), m.group(3)
        wanted = int(minor) if major == 1 and minor else major
        if not {">=": java >= wanted, "<=": java <= wanted, ">": java > wanted, "<": java < wanted}.get(op, java >= wanted if op in "^~" else java == wanted):
            return False
    return True


def newest_loaded_class(z, loader, sides, runs, inside="", skipped=frozenset(), release=None):
    """(java, class, what it is) for the class needing the newest Java of those the JVM is asked to load of a jar on the
    sides given, (0, "", "") if none. The JVM refuses a class compiled for a newer Java when it is loaded, not when the jar
    is, so only these count:
    - For a jar the loader starts (start_classes on either side): the start classes of the sides given. Its other classes
      are another loader's (More Mobs' Forge and NeoForge classes in its Fabric jar), another mod's integration (a Mod
      Menu entrypoint), or reached only on a path the mod guards (More Mobs calls its Java 25 class only beside MidnightLib
      on a release with Mojang's names; MCSR Ranked loads its Java 16 Discord SDK only when the Java is 16 or newer).
    - For a jar the loader starts nothing of (a library, another mod's add-on): every class, as what loads it calls them.
    - The same for each nested jar with the loader's own metadata (jar-in-jar, JarJar): the loader loads it as a mod. One
      whose "java" dependency refuses the release's Java (runs(), asked only then) is not loaded (C2ME's Java 22 native
      math module on Java 21). Nor is a Fabric one whose "minecraft" dependency refuses the release (fabric_accepts): Fabric's
      resolver passes it over, which is how a bundle of one module per release (ViaFabric's viafabric-mc1144 to -mc12111,
      macOS Input Fixes' module for 26.x) runs only its module for the release it starts on, ported or as it came. Nor is
      one in skipped (the jars holding it as inside names them, its path): a platform module whose platform copy runs,
      and the like, that the port left out as not loaded on the release.
    A multi-release jar's META-INF/versions/ copies and module-info only load on a Java that reads them."""
    import io
    import zipfile
    names = z.namelist()
    every = {}
    for side in ("server", "client"):
        every[side] = start_classes(z, loader, side)
    if any(every.values()):
        counted = {n: why for side in sides for n, why in every[side].items()}
    else:
        counted = {n: "class" for n in names if n.endswith(".class") and not n.startswith("META-INF/versions/") and not n.endswith("module-info.class")}
    best = (0, "", "")
    for path, what in sorted(counted.items()):
        with z.open(path) as f:
            head = f.read(8)
        java = int.from_bytes(head[6:8], "big") - 44 if head[:4] == b"\xca\xfe\xba\xbe" else 0
        if java > best[0]:
            best = (java, inside + path[:-6].replace("/", "."), what)
    for entry in names:
        if not entry.endswith(".jar") or not entry.startswith(("META-INF/jars/", "META-INF/jarjar/")) or (inside, entry) in skipped:
            continue
        try:
            with zipfile.ZipFile(io.BytesIO(z.read(entry))) as nested:
                meta = metadata(nested, loader)
                asks = (meta or {}).get("depends") or {} if loader == "fabric" else {}
                if meta is None or "java" in asks and not admits_java(asks["java"], runs()):
                    continue
                if release and "minecraft" in asks and not fabric_accepts(asks["minecraft"], release):
                    continue
                found = newest_loaded_class(nested, loader, sides, runs, inside + entry.rsplit("/", 1)[-1] + "!", skipped, release)
        except zipfile.BadZipFile:
            continue
        if found[0] > best[0]:
            best = found
    return best


def version_key(version):
    """A release's place in Fabric Loader's order: numeric dot parts, a missing part zero, a pre-release ("-pre1",
    "-rc1", "-snapshot-2") before its release; build metadata after "+" does not count. Pre-releases among themselves
    compare as text, which a fabric.mod.json range never needs finer."""
    core, dash, pre = version.split("+", 1)[0].strip().partition("-")
    parts = [int(p) if p.isdigit() else 0 for p in core.split(".")]
    while parts and parts[-1] == 0:
        parts.pop()
    return parts, (0, pre) if dash else (1, "")  # a bare "-" is the lowest pre-release


def fabric_accepts(spec, release):
    """Whether a fabric.mod.json version predicate accepts a release, as Fabric Loader reads one: a list any one of which
    may hold, a string of "||" alternatives, each of terms that must all hold (">=1.14", "<26.1", "~1.15.2", "^1.16",
    "1.16.x", "1.20.6", "*"). One it cannot read accepts."""
    if isinstance(spec, list):
        return not spec or any(fabric_accepts(one, release) for one in spec)
    for alternative in str(spec).split("||"):
        terms = alternative.split()
        if all(fabric_term(t, release) for t in terms):
            return True
    return False


def fabric_term(term, release):
    """One term of a Fabric version predicate (fabric_accepts)."""
    m = re.fullmatch(r"(>=|<=|>|<|=|~|\^)?(.+)", term.split("+", 1)[0])
    if not m or m.group(2) in ("*", "x", "X"):
        return True
    op, wanted = m.group(1) or "", m.group(2)
    parts = wanted.split(".")
    wild = next((i for i, p in enumerate(parts) if p in ("x", "X", "*")), None)
    if wild is not None:
        fixed = version_key(".".join(parts[:wild]))[0]
        if op in ("", "="):
            return wild == 0 or (version_key(release)[0] + [0] * wild)[:wild] == (fixed + [0] * wild)[:wild]
        wanted = ".".join(parts[:wild] + ["0"] * (len(parts) - wild))
    a, b = version_key(release), version_key(wanted)
    if op == ">=":
        return a >= b
    if op == ">":
        return a > b
    if op == "<=":
        return a <= b
    if op == "<":
        return a < b
    if op in ("~", "^"):
        keep = 2 if op == "~" else 1
        return a >= b and (a[0] + [0] * keep)[:keep] == (b[0] + [0] * keep)[:keep]
    return a == b


def newer_java(folder, names, loader, version, side):
    """{jar: why} for the jars a class of which the JVM loads while a game of side starts (newest_loaded_class) needs a
    newer Java than the release runs on. Such a jar cannot load there: a mixin of it merged into a game class makes that
    game class too new as well, so the start crashes naming no mod, and every other mod in it goes without a verdict. The
    proof leaves these out before any start."""
    runs, out = functools.cache(lambda: release_java(version)), {}
    for name in names:
        found = too_new_class(folder / name, loader, (side,), runs, release=version)
        if found:
            java, cls, what = found
            out[name] = f"built for Java {java}; Minecraft {version} runs on Java {runs()} (its {what} {cls}), so it is not started in the {side}"
    return out


def too_new_class(jar, loader, sides, runs, skipped=frozenset(), release=None):
    """(java, class, what it is) for the class needing the newest Java of those the JVM loads of a jar on the sides given
    (newest_loaded_class), when that Java is newer than the release's (runs()); None when the release's Java loads them.
    The one Java check: the game proof (newer_java) and the link check (jar_port) both ask it, of the jar the game is
    given, so a port that lowers a mod's classes (JarPort's lowerJava) is judged by what it wrote. Nested jars in
    skipped (skipped_jars: the port left them out as not loaded on the release), and nested modules the loader does not
    load on release, ask nothing of its Java."""
    import zipfile
    with zipfile.ZipFile(jar) as z:
        found = newest_loaded_class(z, loader, sides, runs, skipped=skipped, release=release)
    # Every release runs on Java 8 or newer, so the release's version.json is read only for a newer class.
    return found if found[0] > 8 and found[0] > runs() else None


# The Python that judges a ported jar's Java (too_new_class and what it reads): part of every kept port's key.
JAVA_CHECK = ("too_new_class", "newest_loaded_class", "start_classes", "admits_java", "fabric_accepts", "version_key", "metadata", "skipped_jars")


def with_java_checked(row, loader, to, runs):
    """JarPort's row for one mod with the Java check of its ported jar (row["out"]): a mod with a class the release's JVM
    loads that still needs a newer Java once ported does not link there, as the JVM refuses that class before any
    reference in it is read (a 1.21.1 mod's Java 21 classes on 1.20.2's Java 17, a real 1.20.2 server started without
    one while the link count called it linked). The port lowers a mod's classes to the release's Java (Downgrade), and
    what that Java cannot run is in the row already, so the jar as it came no longer says it."""
    import zipfile
    out = Path(row.get("out") or "")
    if "error" in row or not out.is_file():
        return row
    try:
        found = too_new_class(out, loader, ("server", "client"), runs, skipped_jars(row), to)
    except zipfile.BadZipFile:  # JarPort wrote it, so it reads; one it could not is an error row
        return row
    if found:
        row["unlinked"] = row["unlinked"] + [f"{row['mod']}: built for a newer Java: Java {found[0]} classes, Java {runs()} runs {to}"]
    return row


LOADER_REF = re.compile(r": (?:net\.neoforged|net\.fabricmc)\.")


def platform_ids(loader, to):
    """The mod ids the platform a pack runs on provides for the new release: Fabric API's modules. Fabric Loader keeps the
    newest copy of each mod id, so a copy of one nested in a mod is not what runs; NeoForge has no such modules."""
    if loader != "fabric":
        return set()
    api = porter.fabric_api(to)
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


def skipped_jars(row):
    """The nested jars JarPort left out of a mod's port as not loaded on the release (a bundle's modules for other
    releases, a platform module whose platform copy runs), as (the jars holding it inside the mod, as newest_loaded_class
    names them, its path)."""
    out = set()
    for note in row.get("skipped") or []:
        parts = note.split(": ", 2)
        if len(parts) == 3 and parts[1].endswith(".jar"):
            holder = parts[0].split("!", 1)
            out.add((holder[1] + "!" if len(holder) == 2 else "", parts[1]))
    return out


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
        for member in porter.left(m):
            counts[member] = counts.get(member, 0) + 1
    report["unlinkedByMods"] = [{"member": k, "mods": v} for k, v in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))]
    report["linked"] = sum(1 for m in report["mods"] if "error" not in m and not m["unlinked"])
    return report


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

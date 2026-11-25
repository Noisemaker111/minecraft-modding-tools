"""What a mod needs beside itself to start (libraries, loader APIs, language loaders), chosen from Modrinth for the release it is ported to."""
import hashlib
import json
import re
import urllib.parse
import urllib.request
from pathlib import Path

from portboard import cachekeys, gamereleases, javalevels, modrinth, settings


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
    return (LANGUAGE_SERVICES["new" if gamereleases.vkey(release) >= gamereleases.vkey("1.20.5") else "old"],)


def needs(jar, loader):
    """What a mod asks for beyond the game and the loader: mod ids its metadata requires, "adapter:K" for a Fabric language
    adapter its entrypoints use, "loader:K" for a NeoForge language loader that is not NeoForge's own. None when neither the
    jar nor a jar nested in it has metadata for the loader (nothing the loader would start)."""
    import io
    import zipfile
    with zipfile.ZipFile(jar) as z:
        meta = javalevels.metadata(z, loader)
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
        meta = javalevels.metadata(z, loader)
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
        meta = javalevels.metadata(z, loader)
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
    cache = settings.HOME / "modrinth" / f"version-{version_id}.json"
    if cache.is_file():
        return json.loads(cache.read_text(encoding="utf-8"))
    info = modrinth.api(f"version/{version_id}")
    cache.parent.mkdir(parents=True, exist_ok=True)
    part = cachekeys.part_of(cache)
    part.write_text(json.dumps(info), encoding="utf-8")
    cachekeys.publish(part, cache)
    return info


def required_projects(version_id):
    """The Modrinth projects a version lists as required: what its author says players must install with it."""
    return [d["project_id"] for d in version_info(version_id).get("dependencies") or []
            if d.get("dependency_type") == "required" and d.get("project_id")]


def library(project, loader, version):
    """A library mod's newest file for a loader and release, downloaded once into ~/.mmt/exposure/libraries/, with the
    version it came from; None when the project has no file for that release."""
    for v in modrinth.project_versions(project):
        if loader not in v["loaders"] or version not in v["game"]:
            continue
        f = next((f for f in v["files"] if f["primary"] and f["file"].endswith(".jar")), None) or next((f for f in v["files"] if f["file"].endswith(".jar")), None)
        if not f:
            continue
        target = settings.HOME / "libraries" / f"{loader}-{version}" / f"{project}--{f['file']}"
        if not target.is_file():
            target.parent.mkdir(parents=True, exist_ok=True)
            with urllib.request.urlopen(urllib.request.Request(f["url"], headers=settings.AGENT), timeout=300) as r:
                data = r.read()
            if f.get("sha1") and hashlib.sha1(data).hexdigest() != f["sha1"]:
                raise SystemExit(f"{f['url']}: the download is not the file Modrinth lists")
            part = cachekeys.part_of(target)
            part.write_bytes(data)
            cachekeys.publish(part, target)
        return target, v["id"]
    return None


def ranges(jar, loader):
    """The versions a mod's metadata asks of each mod it requires: a NeoForge range ({mod id: "[21.1,21.2)"}), or Fabric's
    depends ({mod id: ">=7.2"}, a list where any one will do)."""
    import zipfile
    with zipfile.ZipFile(jar) as z:
        meta = javalevels.metadata(z, loader) or {}
    if loader == "fabric":
        return {i: r for i, r in (meta.get("depends") or {}).items() if i not in ("minecraft", "java", "fabricloader") and r not in ("*", "")}
    return {d.get("modId"): d.get("versionRange") for blocks in meta.get("dependencies", {}).values() for d in (blocks if isinstance(blocks, list) else [])
            if d.get("versionRange") and str(d.get("type", "required" if d.get("mandatory", True) else "optional")).lower() == "required"}


def versions(jar, loader):
    """{mod id: version} a jar declares, its manifest's Implementation-Version where the metadata leaves it to the build."""
    import zipfile
    with zipfile.ZipFile(jar) as z:
        meta = javalevels.metadata(z, loader) or {}
        if loader == "fabric":
            return {meta["id"]: str(meta.get("version", ""))} if meta.get("id") else {}
        manifest = z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace") if "META-INF/MANIFEST.MF" in z.namelist() else ""
    built = next((line.split(":", 1)[1].strip() for line in manifest.splitlines() if line.startswith("Implementation-Version:")), None)
    return {m.get("modId"): (built if str(m.get("version", "")).startswith("$") else m.get("version")) for m in meta.get("mods", [])}


def cmp(x, y):
    """Orders two versions as Maven does: a qualifier (beta, rc) comes before any number, so 0.6.0-beta.5 is below 0.6.0."""
    def key(v):
        return [int(p) if p.isdigit() else p for p in re.split(r"[.\-+]", str(v)) if p]

    a, b = key(x), key(y)
    for p, q in zip(a, b):
        if p != q:
            return (p > q) - (p < q) if type(p) is type(q) else (isinstance(q, str)) - (isinstance(p, str))
    rest = a[len(b):] or b[len(a):]
    if not rest or all(r == 0 for r in rest):
        return 0
    longer = 1 if len(a) > len(b) else -1
    return -longer if isinstance(rest[0], str) else longer


def fits(version, spec, loader):
    """Whether a version is one a mod's metadata asks for, read as its loader reads it."""
    return fabric_in(version, spec) if loader == "fabric" else in_range(version, spec)


def fabric_in(version, spec):
    """Whether a version meets a Fabric depends value as Fabric Loader reads it: a list is any of its entries, an entry
    every space-separated predicate (">=7.2", "<8", "^3.1" same major, "~3.1" same minor, "1.2.x", "=1.2" or "1.2"
    exactly, "*"). Build metadata after "+" is not compared (3.1.1+1.19.4 is 3.1.1)."""
    if isinstance(spec, list):
        return not spec or any(fabric_in(version, s) for s in spec)
    v = str(version).split("+")[0]
    if not v:
        return True
    for term in str(spec).split():
        op = re.match(r"(>=|<=|>|<|=|\^|~)?(.*)", term)
        sign, want = op.group(1) or "", op.group(2).split("+")[0]
        if want in ("*", "x", "X", ""):
            continue
        if re.search(r"\.[xX*]$", want):
            if v.split(".")[:want.count(".")] != want.split(".")[:-1]:
                return False
            continue
        c = cmp(v, want)
        parts = [int(p) for p in re.findall(r"\d+", want)[:2]] or [0]
        ok = {">=": c >= 0, "<=": c <= 0, ">": c > 0, "<": c < 0, "": c == 0, "=": c == 0,
              "^": c >= 0 and cmp(v, f"{parts[0] + 1}") < 0,
              "~": c >= 0 and cmp(v, f"{parts[0]}.{(parts[1] if len(parts) > 1 else 0) + 1}") < 0}[sign]
        if not ok:
            return False
    return True


def in_range(version, spec):
    """Whether a version is inside a Maven range as NeoForge reads one ("[21.1,21.2)", "[1.0,)", "1.2" for at least 1.2)."""
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
    folder = settings.HOME / "jars" / f"{loader}-{frm}"
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
    for hit in modrinth.api("search", {"query": " ".join(words), "facets": json.dumps(facets), "limit": 5}).get("hits", []):
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
        if got and any(not fits(v, r, loader) for mid, v in versions(got[0], loader).items() for r in asked_range.get(mid, [])):
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
        fabric, toml = javalevels.metadata(z, "fabric"), javalevels.metadata(z, "neoforge")
        nested = [z.read(n) for n in z.namelist() if n.startswith("META-INF/") and n.endswith(".jar")]
    own = ({fabric.get("id"), *(fabric.get("provides") or [])} if fabric else set()) | ({m.get("modId") for m in toml.get("mods", [])} if toml else set())
    return own.union(*(mod_ids(data) for data in nested))


def has_code(jar):
    """Whether any class of the jar (nested jars aside) names the game."""
    import zipfile
    with zipfile.ZipFile(jar) as z:
        return any(n.endswith(".class") and b"net/minecraft/" in z.read(n) for n in z.namelist())

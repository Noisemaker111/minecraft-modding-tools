"""The mods a scoreboard reads: the most downloaded of a loader and release on Modrinth, pinned in bench/exposure/mods and downloaded once."""
import hashlib
import json
import os
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import date

from portboard import cachekeys, settings


def api(path, params=None):
    url = "https://api.modrinth.com/v2/" + path + ("?" + urllib.parse.urlencode(params) if params else "")
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=settings.AGENT), timeout=60) as r:
                return json.load(r)
        except Exception as error:  # a 429 or a dropped connection: back off and try again; a 404 is Modrinth's answer
            if attempt == 3 or getattr(error, "code", None) == 404:
                raise
            time.sleep(5 * (attempt + 1))


def project_versions(project):
    """A Modrinth project's versions, newest first, with what choosing a file needs; kept a day, so pinning every release asks once a project."""
    cache = settings.HOME / "modrinth" / f"{project}.json"
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


def pin(loader, version, count=settings.COUNT):
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
    path = settings.PINS / f"{loader}-{version}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    write_pin(path, {"loader": loader, "version": version, "date": date.today().isoformat(), "mods": mods})
    print(f"pinned {len(mods)} {loader} mods for {version} in {path.relative_to(settings.ROOT)}")
    return path


def write_pin(path, pin):
    """One mod a line, so a repinned release reads as the mods that changed."""
    head = json.dumps({k: v for k, v in pin.items() if k != "mods"})[:-1]
    path.write_text(head + ', "mods": [\n' + ",\n".join(json.dumps(m) for m in pin["mods"]) + "\n]}\n", encoding="utf-8")


def fetch(loader, version, repin=False):
    """The pinned mods of one loader and release, downloaded once into ~/.mmt/exposure/jars/LOADER-VERSION/ (pinned first if they are not)."""
    path = settings.PINS / f"{loader}-{version}.json"
    if repin or not path.is_file():
        pin(loader, version)
    mods = json.loads(path.read_text(encoding="utf-8"))["mods"]
    folder = settings.HOME / "jars" / f"{loader}-{version}"
    folder.mkdir(parents=True, exist_ok=True)

    def one(m):
        target = folder / f"{m['slug']}--{m['file']}"
        if target.is_file():
            return target
        for attempt in range(3):
            try:
                with urllib.request.urlopen(urllib.request.Request(m["url"], headers=settings.AGENT), timeout=300) as r:
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
        part = cachekeys.part_of(target)
        part.write_bytes(data)
        return cachekeys.publish(part, target)

    with ThreadPoolExecutor(8) as pool:
        jars = [j for j in pool.map(one, mods) if j]
    return jars

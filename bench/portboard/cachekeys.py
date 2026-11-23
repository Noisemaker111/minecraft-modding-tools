"""Cache folders under ~/.mmt/exposure, named for the content of what made them, and finished whole or not at all."""
import hashlib
import json
import os
import shutil
import time
import urllib.parse
import urllib.request
import uuid
import port_tools  # noqa: E402

from portboard import settings


def tools_key(*folders):
    """The content of the tools that build a cache entry, hashed: all of the porter, or the tools/port folders named.
    Several worktrees share ~/.mmt/exposure and run at once; keyed by file times, one worktree's newer tools wiped what
    another was reading (#126). Keyed by content, worktrees with the same tools share entries and worktrees with other
    tools never touch each other's."""
    h = hashlib.sha256()
    for name in port_tools.sources(*folders, root=settings.ROOT):
        h.update(name.encode() + b"\0" + (settings.ROOT / name).read_bytes() + b"\0")
    return h.hexdigest()[:16]


def keyed(kind, *folders):
    """~/.mmt/exposure/KIND/KEY/ for the tools/port folders named."""
    return keyed_at(kind, tools_key(*folders))


def keyed_at(kind, key):
    """~/.mmt/exposure/KIND/KEY/; a key no run has used for a day is removed when a new one is made."""
    base = settings.HOME / kind
    folder = base / key
    fresh = not folder.is_dir()
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "used").write_text(str(os.getpid()))
    if fresh:
        for old in base.iterdir():
            used = old / "used"
            if old != folder and settings.KEY.match(old.name) and used.is_file() and time.time() - used.stat().st_mtime > 86400:
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


def platform_bytes():
    """The loaders' release lists the tools will read, downloaded first if this machine has none, as VersionMap.fetch would."""
    out = b""
    for name, url in settings.PLATFORM.items():
        path = settings.MAPPINGS / name
        if not path.is_file() or not path.stat().st_size:
            settings.MAPPINGS.mkdir(parents=True, exist_ok=True)
            with urllib.request.urlopen(urllib.request.Request(url, headers=settings.AGENT), timeout=60) as r:
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
    loader_list = (settings.MAPPINGS / "fabric-loader-maven-metadata.xml").read_bytes() if loader == "fabric" else b""
    return json.dumps(json.loads(route.read_text(encoding="utf-8")).get("loader"), sort_keys=True).encode() + loader_list


def content_key(*parts):
    """A cache key from every input's content: equal keys mean equal inputs, so a cached answer is the answer."""
    h = hashlib.sha256()
    for p in parts:
        h.update(p if isinstance(p, bytes) else str(p).encode())
        h.update(b"\0")
    return h.hexdigest()[:16]

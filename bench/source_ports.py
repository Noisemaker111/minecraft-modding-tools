"""Any mod, any version, from source: the jar scoreboard's any-version count (bench/portboard/anyversion.py) asked of the
same mods' own source, ported with `mmt port` instead of as jars.

    python bench/source_ports.py pin [--loader fabric|neoforge] [--only SLUG,...]   find each mod's source and pin a commit
    python bench/source_ports.py plan [--loader L] [--size N]                       the baseline jobs, as JSON
    python bench/source_ports.py baseline --loader L --mods SLUG,... --out DIR       build each pinned source on its own release
    python bench/source_ports.py port-plan DIR [--size N]                           the port jobs for the mods whose baseline built
    python bench/source_ports.py port --loader L --to T --mods SLUG,... --out DIR    port each to T, build, and link the built jar
    python bench/source_ports.py join DIR [--out FILE]                              the any-version table from every job's summary
    python bench/source_ports.py prove --loader L --to T --mod SLUG                 port one and start it in a hidden real game

The mods are the jar scoreboard's: the 150 most downloaded of each loader's most-used release (Fabric 1.20.1, NeoForge
1.21.1), pinned in bench/exposure/mods/. pin reads where each one publishes its source from the mod's own metadata (the
jar's fabric.mod.json contact.sources, its mods.toml displayURL and issueTrackerURL, then the Modrinth project's source
and issues links) and pins the commit that made the pinned jar: the tag naming the jar's version, else the last commit
before the jar was published on the branch whose build is for that release. Each pin says how it was chosen and the
game release its build files name at that commit, which must be the pinned jar's. The pins are kept in
bench/exposure/sources/LOADER-VERSION.json, so every run ports the same commits. A mod's own source is the input of its
port; nothing in it becomes a rule (rules come only from the game, the loaders and their migration notes).

A port counts when `mmt port --fix` builds it with no hand edits (its verdict includes Mixin itself applying the mod's
mixins against the target game, and the metadata check) and the jar it built passes the jar scoreboard's own check
against the target (portboard.porter.jar_port from the target to itself, with its Java, hidden-code and data checks), so
"works" means the same on both sides. That check reports what an update broke: references the old release has and the
new one lacks. On a jar compiled for its own release that half finds nothing (a 1.20.1 jar checked as if built for
1.21.5 passes it), and javac and Mixin's own check stand in for it; what it adds there is the JVM's verifier, the
classes' Java against the release's and the game ids in the mod's data the source release had and the target lacks. A mod counts when every target passes. A target that did not run counts as not
reached, and so does every target of a mod with no published source, no commit for its jar, or a pinned source that
does not build on its own release (the baseline). join ranks what holds the rest back by type of break, as the jar
count does (anyversion.any_version_table).

On GitHub's runners: tools/cloud-run source (the source-* jobs of .github/workflows/cloud-ports.yml, corpus=source),
one job per group of mods for the baseline and one per loader, target and group of mods for the ports: first to the
newest and the oldest release (ends), then to every other release on the side a mod passed (rest), since a mod that
misses an end cannot go to any version (port_plan). Main's table is kept on the generated branch as
scoreboard/source.json next to the jar table, and its count is port.source.any-version (bench/generated.py).
"""
import argparse
import io
import json
import os
import re
import shutil
import subprocess
import sys
import time
import tomllib
import urllib.parse
import zipfile
from concurrent.futures import ThreadPoolExecutor
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import port_tools  # noqa: E402
from portboard import anyversion, gamereleases, javalevels, modrinth, porter, settings  # noqa: E402

ROOT = port_tools.ROOT
SOURCES = ROOT / "bench" / "exposure" / "sources"
WORK = ROOT / ".runs" / "source-ports"
WINDOWS = os.name == "nt"
MMT = [str(ROOT / ("mmt.cmd" if WINDOWS else "mmt"))]
QUIET_GIT = dict(os.environ, GIT_TERMINAL_PROMPT="0", GIT_ASKPASS="echo", GCM_INTERACTIVE="never")
HOSTS = ("github.com", "gitlab.com", "codeberg.org", "git.sr.ht", "bitbucket.org")
# Not a mod's source, though linked as if it were: a loader's or a wiki's repository, or a host's front page.
NOT_SOURCE = re.compile(r"github\.com/(FabricMC|neoforged|MinecraftForge|Fabricators-of-Create)/(fabric|NeoForge|MinecraftForge)(\.git)?$", re.I)


# ---- where each mod's source is --------------------------------------------------------------------------------------
def repo_url(link):
    """A git host link (the repository, its issues, a file in it) as the repository's clone URL, or None."""
    if not isinstance(link, str):
        return None
    link = link.strip()
    m = re.match(r"(?:https?://)?(?:www\.)?(github\.com|codeberg\.org|bitbucket\.org)/([\w.-]+)/([\w.-]+)", link)
    if m:
        name = re.sub(r"\.git$", "", m.group(3))
        url = f"https://{m.group(1)}/{m.group(2)}/{name}"
        return None if NOT_SOURCE.search(url) or m.group(2).lower() in ("orgs", "sponsors", "users") else url
    m = re.match(r"(?:https?://)?(gitlab\.com)/(.+?)(?:/-/.*|/issues.*|\.git)?/?$", link)
    if m and m.group(2).count("/") >= 1:
        return f"https://gitlab.com/{m.group(2)}"
    m = re.match(r"(?:https?://)?git\.sr\.ht/(~[\w.-]+)/([\w.-]+)", link)
    if m:
        return f"https://git.sr.ht/{m.group(1)}/{m.group(2)}"
    return None


def jar_links(jar, loader):
    """The links a jar's own metadata gives for the loader, sources first: [(field, link)]."""
    with zipfile.ZipFile(jar) as z:
        meta = javalevels.metadata(z, loader)
        manifest = z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace") if "META-INF/MANIFEST.MF" in z.namelist() else ""
    if not meta:
        return [], None, manifest
    if loader == "fabric":
        contact = meta.get("contact") or {}
        links = [(k, contact.get(k)) for k in ("sources", "issues", "homepage") if contact.get(k)]
        return links, str(meta.get("version") or ""), manifest
    links = [("issueTrackerURL", meta.get("issueTrackerURL"))]
    version = ""
    for mod in meta.get("mods") or []:
        links += [("displayURL", mod.get("displayURL")), ("modUrl", mod.get("modUrl"))]
        version = version or str(mod.get("version") or "")
    return [(k, v) for k, v in links if v], version, manifest


def modrinth(kind, ids):
    """Modrinth's projects or versions by id, asked 100 at a time."""
    out = {}
    ids = list(dict.fromkeys(ids))
    for i in range(0, len(ids), 100):
        for row in modrinth.api(kind, {"ids": json.dumps(ids[i:i + 100])}):
            out[row["id"]] = row
    return out


def git(*args, cwd=None, timeout=600):
    try:
        done = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace",
                              env=QUIET_GIT, timeout=timeout)
    except subprocess.TimeoutExpired:
        return 124, "", f"git {args[0]} took over {timeout} s"
    return done.returncode, done.stdout, done.stderr


def refs(url):
    """{tag or branch name: commit} of a repository, peeled tags preferred; None when it cannot be read."""
    code, out, _ = git("ls-remote", "--tags", "--heads", url, timeout=120)
    if code:
        return None
    tags, heads = {}, {}
    for line in out.splitlines():
        sha, _, ref = line.partition("\t")
        if ref.startswith("refs/heads/"):
            heads[ref[len("refs/heads/"):]] = sha
        elif ref.startswith("refs/tags/"):
            name = ref[len("refs/tags/"):]
            if name.endswith("^{}"):
                tags[name[:-3]] = sha  # the commit an annotated tag points at
            else:
                tags.setdefault(name, sha)
    return {"tags": tags, "heads": heads}


def version_words(*versions):
    """The version strings a tag may carry for this jar, longest first, each also without a leading v, and each also as the
    mod's own number alone: without the game releases and loader names a Modrinth version number puts around it. A word
    that is only a game release (1.21.1, mc1.21.1) names every build of that release, so it is never one."""
    game = {r for r in gamereleases.releases()} | {".".join(r.split(".")[:2]) for r in gamereleases.releases()}
    loaders = r"fabric|neoforge|forge|quilt|neo"
    out = set()
    for v in versions:
        v = (v or "").strip()
        if not v or "$" in v:
            continue
        out |= {v, v.lstrip("vV")}
        parts = [t for t in re.split(r"[-+_ ]", v) if t and not re.fullmatch(loaders, t, re.I) and re.sub(r"(?i)^mc", "", t) not in game]
        if parts:
            out.add("-".join(parts).lstrip("vV"))
    return sorted((w for w in out if re.search(r"\d", w) and re.sub(r"(?i)^v?(mc)?", "", w) not in game), key=len, reverse=True)


def tag_for(tags, words, game, loader):
    """The tag naming the jar's version: equal to one of the words, else containing the longest one whole (not as part of
    a longer number); among several, the one naming the game release and the loader."""
    def whole(word, tag):
        return re.search(r"(?<![\w.])" + re.escape(word) + r"(?![\w]|\.\d)", tag) is not None

    for word in words:
        exact = [t for t in tags if t.lstrip("vV") == word.lstrip("vV")]
        if exact:
            return exact[0], f"tag {exact[0]}"
        found = [t for t in tags if whole(word, t)]
        if not found:
            continue
        others = {"fabric", "neoforge", "forge", "quilt"} - {loader}
        # A tag naming another game release or another loader is that build's, not this one's.
        found = [t for t in found if not any(o in t.lower() and loader not in t.lower() for o in others)]
        rank = lambda t: (game not in t, loader not in t.lower(), len(t))  # noqa: E731
        found.sort(key=rank)
        if found:
            return found[0], f"tag {found[0]}"
    return None, None


# A build naming its game release: a property or constant whose name says minecraft or mc, set to a release, or the
# game's own dependency (com.mojang:minecraft:1.21.5, minecraft(name = "minecraft", version = "1.21.5")).
GAME_KEYS = re.compile(r"(?i)(?:(?:minecraft|\bmc)\w*\s*[=:]\s*['\"]?|com\.mojang:minecraft:|\"minecraft\",\s*version\s*=\s*\")(\d+\.\d+(?:\.\d+)?)(?![\w.])")
BUILD_FILE = re.compile(r"(^|/)(gradle\.properties|libs\.versions\.toml|build\.gradle(\.kts)?|settings\.gradle(\.kts)?|[^/]*\.kts?)$")


def build_games(clone, commit):
    """The game releases the build files at a commit name (gradle.properties, version catalogs, build scripts and buildSrc
    constants, Stonecutter's versions/ folders): a pin is right when the pinned jar's release is among them."""
    found = set()
    code, files, _ = git("ls-tree", "-r", "--name-only", commit, cwd=clone)
    if code:
        return found, []
    names = files.splitlines()
    wanted = [n for n in names if BUILD_FILE.search(n) and "/src/main/java/" not in n and (n.count("/") <= 2 or n.startswith("buildSrc/"))]
    wanted.sort(key=lambda n: (not n.endswith("gradle.properties"), n.count("/"), n))
    for name in wanted[:30]:
        code, text, _ = git("show", f"{commit}:{name}", cwd=clone)
        if not code:
            found |= set(GAME_KEYS.findall(text))
    found |= {n.split("/")[1].split("-")[0] for n in names if n.startswith("versions/") and n.count("/") >= 2 and re.match(r"\d", n.split("/")[1])}
    return found, names


def project_of(names, loader, game):
    """The Gradle project of the loader's build in a multi-loader or multi-version checkout ("" for a single build):
    Stonecutter's :GAME-LOADER or :GAME, else a folder (two deep at most) with its own build script named for the loader,
    the one naming the game release first when several do (platforms/neoforge-1.21.1, neoforge/, fabric-1.20.1/)."""
    for candidate in (f"versions/{game}-{loader}", f"versions/{game}"):
        if any(n.startswith(candidate + "/") for n in names):
            return ":" + candidate.split("/", 1)[1]
    builds = {n.rsplit("/", 1)[0] for n in names if re.search(r"(^|/)build\.gradle(\.kts)?$", n) and 1 <= n.count("/") <= 2}
    own = re.compile(r"(^|[^a-z])(neoforge|neo)([^a-z]|$)" if loader == "neoforge" else r"(^|[^a-z])fabric([^a-z]|$)")
    found = [b for b in builds if own.search(b.rsplit("/", 1)[-1].lower())
             and not any(r in b and r != game for r in re.findall(r"\d+\.\d+(?:\.\d+)?", b))]
    found.sort(key=lambda b: (game not in b, b.count("/"), b))
    return ":" + found[0].replace("/", ":") if found else ""


def same_family(game, games):
    """A release of the game's family among games (the jar is published for its whole family), or None."""
    family = gamereleases.families()
    here = family.get(game, {}).get("id")
    return next((g for g in sorted(games, key=gamereleases.vkey, reverse=True) if here and family.get(g, {}).get("id") == here), None)


def release_of(game, games):
    """The release a pinned source builds: the jar's own when its build names it, else the one of its family it names."""
    return game if game in games or not games else same_family(game, games) or game


def pin_commit(url, words, game, loader, published):
    """The commit that made the jar: (commit, how, games its build names, project) or (None, why, ...)."""
    seen = refs(url)
    if seen is None:
        return None, "the repository cannot be read anonymously", [], ""
    clone = WORK / "pins" / re.sub(r"[^\w.-]+", "_", url.split("://", 1)[1])
    if not (clone / "HEAD").is_file():
        clone.parent.mkdir(parents=True, exist_ok=True)
        # Commits and trees only (blobs come when a file is read): enough to choose a commit and read its build files.
        code, _, err = git("clone", "--bare", "--quiet", "--filter=blob:none", url, str(clone), timeout=900)
        if code:
            shutil.rmtree(clone, ignore_errors=True)
            return None, f"clone failed: {err.strip()[-200:]}", [], ""
    else:
        git("fetch", "--quiet", "--tags", "origin", "+refs/heads/*:refs/heads/*", cwd=clone, timeout=900)
    tag, how = tag_for(seen["tags"], words, game, loader)
    tried = []
    if tag:
        commit = seen["tags"][tag]
        games, names = build_games(clone, commit)
        # A tag of the jar's version names its commit; its build must not name a release of another family instead
        # (a jar built on 1.21 is published for 1.21.1 too: its source builds 1.21, and the port starts there).
        if game in games or not games or same_family(game, games):
            return commit, how, sorted(games), project_of(names, loader, game)
        tried.append(f"{how} builds {', '.join(sorted(games))}")
    # No tag for the jar: the last commit before it was published, on the branch whose build is for its release, or
    # whose name is the release when the build names none (a release set by a Gradle plugin).
    family = ".".join(game.split(".")[:2])
    rank = lambda b: (game not in re.split(r"[^\w.]", b), family not in b, loader not in b.lower(), b.split("/")[-1] not in ("main", "master", "dev", "develop"), b)  # noqa: E731
    near = None
    for branch in sorted(seen["heads"], key=rank)[:40]:
        code, out, _ = git("rev-list", "-1", f"--before={published}", f"refs/heads/{branch}", cwd=clone)
        commit = out.strip()
        if code or not commit:
            continue
        games, names = build_games(clone, commit)
        named = set(re.split(r"[^\w.]", branch))
        if game in games or (not games and game in named):
            return commit, f"branch {branch} before {published[:10]}", sorted(games), project_of(names, loader, game)
        # A build for a release of the same family (the jar of a 1.21 build published for 1.21.1 as well): kept in
        # case no branch builds the release itself.
        mate = same_family(game, games) or (None if games else same_family(game, named))
        if mate and near is None:
            near = (commit, f"branch {branch} before {published[:10]}", sorted(games) or [mate], project_of(names, loader, mate))
    if near:
        return near
    return None, "no tag for the jar's version and no branch whose build is for " + game + (f" ({'; '.join(tried)})" if tried else ""), [], ""


def pin(loader, only=None):
    """bench/exposure/sources/LOADER-FROM.json: for each pinned mod of the loader's most-used release, its repository
    and the commit of its pinned jar, or why there is none."""
    frm = gamereleases.most_used(loader)
    pinned = json.loads((settings.PINS / f"{loader}-{frm}.json").read_text(encoding="utf-8"))["mods"]
    if only:
        pinned = [m for m in pinned if m["slug"] in only]
    jars = {p.name.split("--", 1)[0]: p for p in modrinth.fetch(loader, frm)}
    projects = modrinth("projects", [m["project"] for m in pinned])
    versions = modrinth("versions", [m["version"] for m in pinned])

    def one(m):
        jar = jars.get(m["slug"])
        links, own_version, manifest = jar_links(jar, loader) if jar else ([], "", "")
        project, version = projects.get(m["project"], {}), versions.get(m["version"], {})
        links += [("modrinth source_url", project.get("source_url")), ("modrinth issues_url", project.get("issues_url")),
                  ("modrinth wiki_url", project.get("wiki_url"))]
        row = {"slug": m["slug"], "file": m["file"]}
        candidates = list(dict.fromkeys(u for u in (repo_url(link) for _, link in links) if u))
        if not candidates:
            return {**row, "none": "no published source", "links": [link for _, link in links if link][:4]}
        implementation = re.search(r"(?m)^Implementation-Version:\s*(\S+)", manifest)
        words = version_words(own_version, implementation.group(1) if implementation else "", version.get("version_number"))
        why = []
        for url in candidates[:2]:
            commit, how, games, project_path = pin_commit(url, words, frm, loader, version.get("date_published") or "now")
            if commit:
                return {**row, "repo": url, "commit": commit, "how": how, "builds": release_of(frm, games), "games": games, "project": project_path,
                        "from": next(f for f, link in links if repo_url(link) == url)}
            why.append(f"{url}: {how}")
        return {**row, "repo": candidates[0], "none": "; ".join(why)}

    with ThreadPoolExecutor(8) as pool:
        rows = list(pool.map(one, pinned))
    path = SOURCES / f"{loader}-{frm}.json"
    if only and path.is_file():
        kept = {r["slug"]: r for r in json.loads(path.read_text(encoding="utf-8"))["mods"]}
        kept.update({r["slug"]: r for r in rows})
        rows = [kept[m["slug"]] for m in json.loads((settings.PINS / f"{loader}-{frm}.json").read_text(encoding="utf-8"))["mods"] if m["slug"] in kept]
    path.parent.mkdir(parents=True, exist_ok=True)
    modrinth.write_pin(path, {"loader": loader, "version": frm, "date": date.today().isoformat(), "mods": rows})
    have = sum(1 for r in rows if "commit" in r)
    print(f"{loader} {frm}: {have} of {len(rows)} mods have source pinned to their jar's commit; "
          f"{sum(1 for r in rows if 'repo' in r)} publish a repository; kept in {path.relative_to(ROOT)}")
    return rows


def pins(loader):
    path = SOURCES / f"{loader}-{gamereleases.most_used(loader)}.json"
    return json.loads(path.read_text(encoding="utf-8"))["mods"] if path.is_file() else []



# ---- running ----------------------------------------------------------------------------------------------------------
import sweep as SW  # noqa: E402  the port sweep's runner and its error classes: one way to run and judge a source port

# The jars a build writes that are not the mod: sources, javadoc, the unremapped dev jar, shadow inputs.
NOT_THE_MOD = re.compile(r"-(sources|javadoc|dev|dev-shadow|all-dev|api|slim|plain)\.jar$", re.I)


def checkout(row):
    """The pinned commit, shallow-fetched once into .runs/source-ports/clones/SLUG and reset before every build."""
    work = WORK / "clones" / row["slug"]
    if (work / ".git").exists():
        SW.sh(MMT + ["port", "--root", work, "--undo"], timeout=600)
    else:
        work.mkdir(parents=True, exist_ok=True)
        git("init", "-q", cwd=work)
        git("config", "core.longpaths", "true", cwd=work)
        git("remote", "add", "origin", row["repo"], cwd=work)
    if git("cat-file", "-e", row["commit"] + "^{commit}", cwd=work)[0]:
        code, _, err = git("fetch", "-q", "--depth", "1", "origin", row["commit"], cwd=work, timeout=1800)
        if code:
            raise RuntimeError(f"git fetch {row['commit'][:10]} failed: {err.strip()[-300:]}")
    code, _, err = git("checkout", "-q", "--force", "--detach", row["commit"], cwd=work)
    if code:
        raise RuntimeError(f"git checkout failed: {err.strip()[-300:]}")
    git("clean", "-fdxq", "-e", ".gradle", cwd=work)
    git("submodule", "update", "--init", "--depth", "1", "--recursive", cwd=work, timeout=1800)
    return work


def project_args(row):
    return ["--project", row["project"]] if row.get("project") else []


def built_jar(work, row, since):
    """The jar the build just wrote for the loader's project: the biggest in its build/libs written after since."""
    folder = work / row["project"].strip(":").replace(":", "/") if row.get("project") and row["project"] != ":" else work
    if row.get("project", "").startswith(":") and not (folder / "build").is_dir() and (work / "versions" / row["project"].strip(":")).is_dir():
        folder = work / "versions" / row["project"].strip(":")  # Stonecutter keeps each version's build under versions/
    jars = [j for j in (folder / "build" / "libs").glob("*.jar") if not NOT_THE_MOD.search(j.name) and j.stat().st_mtime >= since - 1]
    return max(jars, key=lambda j: j.stat().st_size) if jars else None


def jar_task(row):
    """assemble, not build: build also runs the mod's own checks (its unit tests, linters), which are not what a port is
    asked; Biomes O' Plenty's NeoForge test task has no JUnit engine, MidnightLib's 1.20.1 tests don't compile."""
    return (row["project"].rstrip(":") + ":assemble") if row.get("project") and row["project"] != ":" else "assemble"


def baseline_one(loader, row, out, timeout):
    """Does the pinned source build its jar on its own release, with mmt compile and the project's assemble task?"""
    rec = {"slug": row["slug"], "loader": loader, "builds": row["builds"]}
    started = time.time()
    try:
        work = checkout(row)
        code, stdout, err = SW.sh(MMT + ["compile", "--root", work, "--task", jar_task(row), "--json"] + project_args(row), timeout=timeout)
        res = SW.last_json(stdout) or {}
        r = res.get("result", res)
        jar = built_jar(work, row, started) if code == 0 else None
        rec["ok"] = jar is not None
        if not rec["ok"]:
            text = " ".join(str(r.get("error") or r.get("details") or r.get("reason") or err[-600:] or "no answer").split())
            rec["class"] = "no jar written" if code == 0 else "no answer in time" if code is None else "compile-errors" if re.search(r"error: |compiler error", text) else SW.gradle_class(text)
            rec["detail"] = text[:400]
    except RuntimeError as e:
        rec.update(ok=False, **{"class": "checkout", "detail": str(e)[:400]})
    rec["seconds"] = round(time.time() - started, 1)
    out.mkdir(parents=True, exist_ok=True)
    (out / f"baseline-{loader}-{row['slug']}.json").write_text(json.dumps(rec, indent=1), encoding="utf-8")
    SW.sh(MMT + ["game-stop", "--root", WORK / "clones" / row["slug"], "--json"], timeout=120)
    print(f"  {row['slug']}: {'builds' if rec['ok'] else 'does not build: ' + rec['class']} on {row['builds']} ({rec['seconds']:.0f} s)", flush=True)
    return rec


def port_one(loader, row, to, out, timeout):
    """mmt port --fix from the pinned source's release to one target; the jar it built, copied to out/jars/, when it built."""
    rec = {"slug": row["slug"], "loader": loader, "from": row["builds"], "to": to, "commit": row["commit"], "id": row["slug"],
           "mod": row["slug"], "names": "", "kinds": [], "jump": "any-version"}
    started = time.time()
    try:
        work = checkout(row)
        if to == row["builds"]:
            # The source's own release (a jar built on 1.21 published for 1.21.1): its build is the port.
            cmd = MMT + ["compile", "--root", work, "--task", jar_task(row), "--json"] + project_args(row)
        else:
            cmd = MMT + ["port", "--root", work, "--from", row["builds"], "--to", to, "--fix", "--json"] + project_args(row)
        code, stdout, err = SW.sh(cmd, timeout=timeout)
        receipt = SW.last_json(stdout)
        if code is None:
            rec["crash"] = f"no answer in {timeout} s"
        elif receipt is None:
            rec["crash"] = "mmt printed no JSON: " + " ".join((err or stdout).split())[-600:]
        elif to == row["builds"]:
            r = receipt.get("result", receipt)
            rec["result"] = {"ok": code == 0, "built": code == 0, "compiled": code == 0, "error": None if code == 0 else str(r.get("error") or r.get("details") or "")[-2000:]}
            if code == 0:
                # What mmt port's verdict checks and a build alone does not: Mixin itself applying the mod's mixins.
                mcode, mout, merr = SW.sh(MMT + ["mixin-check", "--root", work, "--json"] + project_args(row), timeout=timeout)
                mixins = SW.last_json(mout) or {}
                mixins = mixins.get("result", mixins)
                rec["result"]["mixinCheck"] = {"ok": mcode == 0, "checked": True, "details": str(mixins.get("details") or mixins.get("error") or merr[-400:])[:2000]}
                if mcode != 0:
                    rec["result"].update(ok=False, built=False)
        else:
            rec["result"] = SW.slim(receipt)
    except RuntimeError as e:
        rec["crash"] = "checkout: " + str(e)
    SW.classify(rec)
    jar = built_jar(WORK / "clones" / row["slug"], row, started) if rec["build"] else None
    if rec["build"] and jar is None:
        rec["build"] = False
        rec["blocker"] = {"class": "no jar written", "detail": "the port built, but its project wrote no jar to build/libs"}
    if jar:
        # Named for the pinned jar, as the jar scoreboard names it, so both sides' verdicts are of one mod; the content's
        # hash in the name keeps a cache keyed by name from reading another build.
        import hashlib
        digest = hashlib.sha256(jar.read_bytes()).hexdigest()[:10]
        target = out / "jars" / f"{row['slug']}--src{digest}--{row['file']}"
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(jar, target)
        rec["jar"] = target.name
    rec["seconds"] = round(time.time() - started, 1)
    SW.sh(MMT + ["game-stop", "--root", WORK / "clones" / row["slug"], "--json"], timeout=120)
    return rec


def source_name(jar_name):
    """The pinned jar's name (SLUG--FILE) of a built jar named SLUG--srcHASH--FILE."""
    return re.sub(r"^([^-]+(?:-[^-]+)*?)--src[0-9a-f]{10}--", r"\1--", jar_name)


def link(loader, frm, to, jars, work):
    """The jar scoreboard's check on jars built for to: JarPort from to to itself (what an update broke, which on a jar
    compiled for to is nothing; the JVM's verifier over every class; the classes' Java against the release's; code in a
    jar no loader lists; and, judged against frm with --data-from, the game ids in the mod's data that frm has and to
    lacks). {pinned name: {left, types}}."""
    if not jars:
        return {}
    porter.compile_tools()
    named, unread = porter.mojang_names(loader, to, jars)
    report_path = work / "link.json"
    report = (porter.jar_port(loader, to, to, named, work / "linked", report_path, False, False, data_from=frm if frm != to else None) if named
              else {"mods": [], "unlinkedByMods": [], "linked": 0})
    shutil.rmtree(work / "linked", ignore_errors=True)
    by_name = {j.name: j for j in named}
    report = javalevels.without_platform_copies(report, javalevels.platform_ids(loader, to), by_name)
    rows = [m for m in report["mods"] if "error" not in m]
    data = {m["mod"]: [u.split(": ", 1)[1] for u in m.get("data", [])] for m in rows}
    out = {}
    for name, why in unread.items():
        out[source_name(name)] = {"left": [f"unreadable: {why}"[:300]], "types": ["built jar unreadable by the link check"]}
    for m in report["mods"]:
        if "error" in m:
            out[source_name(m["mod"])] = {"left": [m["error"][:300]], "types": ["built jar unreadable by the link check"]}
            continue
        refs = sorted(porter.left(m, data))
        out[source_name(m["mod"])] = {"left": refs, "types": sorted({f"builds, does not link: {r.split(': ', 1)[0]}" for r in refs})}
    return out


def build_types(rec):
    """The types of break a port that did not build has: the sweep's error classes of what is left, or what stopped it."""
    kinds = sorted({e["class"] for e in rec.get("errors") or []})
    if not kinds and rec.get("blocker"):
        kinds = [rec["blocker"]["class"]]
    return [f"does not build: {k}" for k in kinds] or ["does not build: other"]


def port_job(loader, to, slugs, out, timeout, budget):
    """Ports each mod to one target, then links every jar that built; writes out/source-LOADER-TO-FIRSTSLUG.json in the
    jar scoreboard's summary shape (verdicts keyed by the pinned jar's name). Mods left when the job's time budget runs
    out are not run: they count as not reached."""
    rows = {r["slug"]: r for r in pins(loader) if "commit" in r}
    frm = gamereleases.most_used(loader)
    started = time.time()
    verdicts, records, built = {}, [], []
    work = out / f"work-{loader}-{to}"
    for slug in slugs:
        row = rows[slug]
        name = f"{slug}--{row['file']}"
        if time.time() - started > budget:
            verdicts[name] = {"left": ["not run: the job's time ran out"], "types": ["not run"]}
            continue
        rec = port_one(loader, row, to, work, timeout)
        records.append({k: rec.get(k) for k in ("slug", "from", "to", "build", "blocker", "classes", "errorsBefore", "errorsAfter", "seconds", "jar")}
                       | {"errors": (rec.get("errors") or [])[:30]})
        if rec.get("jar"):
            built.append(work / "jars" / rec["jar"])
        else:
            verdicts[name] = {"left": [f"{e['class']}: {e.get('api')}: {e.get('message', '')[:120]}" for e in (rec.get("errors") or [])[:20]]
                              or [f"{(rec.get('blocker') or {}).get('class')}: {(rec.get('blocker') or {}).get('detail', '')[:200]}"], "types": build_types(rec)}
        print(f"  {slug} {row['builds']} -> {to}: {'built' if rec.get('jar') else ', '.join(build_types(rec))} ({rec['seconds']:.0f} s)", flush=True)
    try:
        linked = link(loader, rows[slugs[0]]["builds"] if slugs else frm, to, built, work)
    except (SystemExit, subprocess.CalledProcessError, OSError) as failed:
        linked = {source_name(j.name): {"left": [f"the link check failed: {failed}"[:300]], "types": ["link check failed"]} for j in built}
    verdicts.update(linked)
    summary = {"loader": loader, "from": frm, "to": to, "kind": "source", "modsRead": len(verdicts),
               "modsThatLink": sum(1 for v in verdicts.values() if not v["left"]), "verdicts": verdicts, "unreadable": {},
               "ports": records, "seconds": round(time.time() - started, 1)}
    out.mkdir(parents=True, exist_ok=True)
    path = out / f"source-{loader}-{to}-{slugs[0] if slugs else 'none'}.json"
    path.write_text(json.dumps(summary, indent=1) + "\n", encoding="utf-8")
    print(f"{summary['modsThatLink']} of {len(slugs)} {loader} mods port from source to {to}, build and link ({summary['seconds']:.0f} s); {path}")
    return summary


# ---- planning the cloud run -------------------------------------------------------------------------------------------
MATRIX = 256  # GitHub's most jobs in one matrix
NAMES = {"fabric": "Fabric", "neoforge": "NeoForge"}


def groups(items, size):
    return [items[i:i + size] for i in range(0, len(items), size)]


def plan(loaders, size):
    """The baseline jobs: LOADER:SLUG,SLUG,... for every mod with a pinned commit."""
    jobs = []
    for lo in loaders:
        have = [r["slug"] for r in pins(lo) if "commit" in r]
        jobs += [f"{lo}:{','.join(g)}" for g in groups(have, size)]
    return jobs


def baselines(folder):
    """Every baseline record under folder, by (loader, slug)."""
    out = {}
    for f in Path(folder).rglob("baseline-*.json"):
        r = json.loads(f.read_text(encoding="utf-8"))
        out[(r["loader"], r["slug"])] = r
    return out


def ends(loader):
    """The two targets every mod is ported to first: the newest release and the oldest. A port across the most updates
    in its direction fails first, and a mod that misses either cannot go to any version."""
    targets = [b for _, b in gamereleases.any_version(loader)]
    return [targets[-1], targets[0]]


def side(loader, target):
    """newer or older: the direction of a port from the loader's most-used release to target."""
    return "newer" if gamereleases.vkey(target) > gamereleases.vkey(gamereleases.most_used(loader)) else "older"


def summaries(folder):
    """Every port job's summary under folder, joined per (loader, from, to)."""
    found = {}
    for f in Path(folder).rglob("source-*.json"):
        s = json.loads(f.read_text(encoding="utf-8"))
        if s.get("kind") != "source":
            continue
        key = (s["loader"], s["from"], s["to"])
        if key in found:
            found[key]["verdicts"].update(s["verdicts"])
            found[key]["ports"] = found[key].get("ports", []) + s.get("ports", [])
        else:
            found[key] = s
    return found


def port_plan(folder, size, loaders, phase="ends"):
    """The port jobs, one matrix for both loaders: [LOADER:TO:SLUG,...]. ends: every mod whose baseline built, to the
    newest and the oldest release. rest (folder also holding the ends' summaries): each mod that passed the newest, to
    every other newer release, and each that passed the oldest, to every other older one; a mod that missed an end
    cannot go to any version, and the releases on that side are not run for it (counted as not reached). The whole grid
    was about 5,000 ports for some 200 mods, most of a day of every runner the account has; this is about a fifth.
    Groups are sized so every job fits one matrix."""
    built = baselines(folder)
    done = summaries(folder)
    todo = {}
    for lo in loaders:
        frm = gamereleases.most_used(lo)
        rows = {r["slug"]: r for r in pins(lo) if "commit" in r and built.get((lo, r["slug"]), {}).get("ok")}
        if phase == "ends":
            todo.update({(lo, t): list(rows) for t in ends(lo) if rows})
            continue
        passed = {t: {n.split("--", 1)[0] for n, v in (done.get((lo, frm, t)) or {}).get("verdicts", {}).items() if not v["left"]} for t in ends(lo)}
        for _, t in gamereleases.any_version(lo):
            end = ends(lo)[0] if side(lo, t) == "newer" else ends(lo)[1]
            mods = [m for m in rows if m in passed[end]]
            if mods and t not in ends(lo):
                todo[(lo, t)] = mods
    per = size
    while sum(-(-len(m) // per) for m in todo.values()) > MATRIX:
        per += 1
    return [f"{lo}:{t}:{','.join(g)}" for (lo, t), mods in todo.items() for g in groups(mods, per)]


# ---- the table --------------------------------------------------------------------------------------------------------
def join(folder, out=None):
    """The any-version table of the source ports: every pinned mod of each loader, with the targets its port built and
    linked on; the mods with no source, no commit or a source that does not build on its own release held back on every
    target by that. Ranked by anyversion.any_version_table, as the jar count is."""
    folder = Path(folder)
    built = baselines(folder)
    found = summaries(folder)
    counts, by_pair = {}, {}
    for lo in settings.FIRST:
        frm = gamereleases.most_used(lo)
        rows = pins(lo)
        pinned = json.loads((settings.PINS / f"{lo}-{frm}.json").read_text(encoding="utf-8"))["mods"]
        by_slug = {r["slug"]: r for r in rows}
        held = {}
        for m in pinned:
            name, r = f"{m['slug']}--{m['file']}", by_slug.get(m["slug"], {})
            if "commit" not in r:
                why = "no published source" if not r.get("repo") else "no commit for the jar's version"
                held[name] = {"left": [r.get("none", "no published source")[:300]], "types": [why]}
            elif (lo, m["slug"]) not in built:
                held[name] = {"left": ["baseline not run"], "types": ["not run"]}
            elif not built[(lo, m["slug"])]["ok"]:
                b = built[(lo, m["slug"])]
                held[name] = {"left": [f"{b['class']}: {b.get('detail', '')[:200]}"], "types": [f"source does not build on its own release: {b['class']}"]}
        counts[lo] = {"pinned": len(pinned), "repository": sum(1 for r in rows if r.get("repo")), "source": sum(1 for r in rows if "commit" in r),
                      "builds": sum(1 for (l, _), b in built.items() if l == lo and b["ok"]), "baselines": sum(1 for (l, _) in built if l == lo)}
        first = {t: (found.get((lo, frm, t)) or {}).get("verdicts", {}) for t in ends(lo)}
        for _, t in gamereleases.any_version(lo):
            s = found.get((lo, frm, t))
            verdicts = dict(held)
            ran = (s or {}).get("verdicts", {})
            end = ends(lo)[0] if side(lo, t) == "newer" else ends(lo)[1]
            for m in pinned:
                name = f"{m['slug']}--{m['file']}"
                if name in held:
                    continue
                if name not in ran and t != end and first[end].get(name, {}).get("left"):
                    # Not run because the mod already misses the release at that end: not reached, and no type of its own,
                    # so what holds the mod back is ranked by the break it met.
                    verdicts[name] = {"left": [f"not run: misses {end} first"], "types": []}
                else:
                    verdicts[name] = ran.get(name) or {"left": ["not run"], "types": ["not run"]}
            by_pair[(lo, frm, t)] = {"loader": lo, "from": frm, "to": t, "verdicts": verdicts, "unreadable": {}}
    anyv = anyversion.any_version_table(by_pair)
    builds_on = {}
    for (lo, _, t), s in by_pair.items():
        builds_on.setdefault(lo, {})[t] = sum(1 for v in s["verdicts"].values() if not v["left"])
    table = {"date": date.today().isoformat(), "kind": "source", "sources": counts, "anyVersion": anyv, "perTarget": builds_on,
             "ports": sorted((p for s in found.values() for p in s.get("ports", [])), key=lambda p: (p["slug"], gamereleases.vkey(p["to"])))}
    for line in lines(table):
        print(line)
    if out:
        Path(out).write_text(json.dumps(table, indent=1) + "\n", encoding="utf-8")
        print(f"saved {out}")
    return table


def headline(table):
    a = table.get("anyVersion") or {}
    return (a.get("every", 0), a.get("mods", 0))


def lines(table, top=10):
    """The source count as lines: the headline, how many have source and build, then the jar count's any-version lines."""
    a = table.get("anyVersion") or {}
    every, mods = headline(table)
    out = [f"{every} of {mods} mods go to any version from source (mmt port builds them with no hand edits and the built jar links on every release)"]
    for lo, c in (table.get("sources") or {}).items():
        out.append(f"  {NAMES[lo]}: {c['source']} of {c['pinned']} have source pinned to their jar's commit ({c['repository']} publish a repository); "
                   f"{c['builds']} of the {c['baselines']} built build on their own release")
    for lo, per in (table.get("perTarget") or {}).items():
        out.append(f"  {NAMES[lo]} per target: " + ", ".join(f"{t} {n}" for t, n in per.items()))
    out += anyversion.any_version_lines(a, top)[1:]
    return out


SOURCE_PAGE = "source-any-version.md"


def page(table):
    """scoreboard/source-any-version.md on the generated branch, written with main's source table."""
    a = table.get("anyVersion") or {}
    out = ["# Any mod, any version, from source: what is not there yet", "",
           f"Written with main's source table (scoreboard/source.json on the generated branch) at {(table.get('commit') or '?')[:10]}, "
           f"run {table.get('run', '?')}. Do not edit by hand.", "",
           "The jar scoreboard's mods (the 150 most-downloaded of each loader's most-used release), each ported from its own "
           "published source with `mmt port --fix` to every other release of its loader, forward and back. A port counts when "
           "it builds with no hand edits and the jar it built links by the jar scoreboard's check.", "",
           "**" + lines(table)[0] + "**", ""]
    out += [f"- {line.strip()}" for line in lines(table)[1:1 + len(table.get("sources") or {})]]
    out += ["", "## What would free the most mods", "", "| Frees | Mods | Mod-releases | Type of break |", "|---|---|---|---|"]
    out += [f"| {t['finishes']} | {t['mods']} | {t['releases']} | {t['type']} |" for t in a.get("types", [])[:30]]
    out += ["", "## Every mod that misses a release, nearest first", "", "| Loader | Mod | Reaches | Held by |", "|---|---|---|---|"]
    out += [f"| {m['loader']} | {m['mod']} | {m['reached']} of {m['of']} | {'; '.join(f'{k} ({n})' for k, n in m['types'].items())} |" for m in a.get("held", [])]
    return "\n".join(out) + "\n"


MEASURE = "port.source.any-version"


def record_main(table_path, commit, run, folder, day=None):
    """Saves a whole run of a main commit as main's source table (FOLDER/source.json, FOLDER being scoreboard/ in a
    checkout of the generated branch), adds its count to the port.source.any-version points (FOLDER/points.json) and
    writes FOLDER/source-any-version.md. A table of an older commit than the one kept is not recorded."""
    import measures as M
    folder = Path(folder)
    folder.mkdir(parents=True, exist_ok=True)
    kept_path, points_path = folder / "source.json", folder / "points.json"
    table = json.loads(Path(table_path).read_text(encoding="utf-8"))
    table = {"commit": commit, "run": run, **{k: v for k, v in table.items() if k not in ("commit", "run")}}
    previous = json.loads(kept_path.read_text(encoding="utf-8")) if kept_path.is_file() else None
    if previous and previous.get("commit") and previous["commit"] != commit and subprocess.run(
            ["git", "merge-base", "--is-ancestor", previous["commit"], commit], cwd=ROOT, capture_output=True).returncode != 0:
        print(f"main's source table already holds {previous['commit'][:10]}, which {commit[:10]} does not follow; not recorded")
        return None
    kept = json.loads(points_path.read_text(encoding="utf-8")) if points_path.is_file() else {}
    every, mods = headline(table)
    points = kept.setdefault(MEASURE, [])
    last = points[-1] if points else None
    subject = subprocess.run(["git", "log", "-1", "--format=%s", commit], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    pr = re.search(r"\(#(\d+)\)\s*$", subject)
    at = f"main at {commit[:10]}" + (f" (#{pr.group(1)})" if pr else "")
    have = sum(c["source"] for c in table.get("sources", {}).values())
    note = (f"{every - last['value']:+,} on {last['value']:,}" if last and last["value"] != every else "the same" if last else "first run") + f"; {have} of {mods} have source"
    points.append({"date": day or date.today().isoformat(), "value": every, "n": mods, "note": note, "source": f"tools/cloud-run source {run} on {at}"})
    points_path.write_text(M.dump(kept), encoding="utf-8", newline="\n")
    kept_path.write_text(json.dumps(table, indent=1) + "\n", encoding="utf-8")
    (folder / SOURCE_PAGE).write_text(page(table), encoding="utf-8", newline="\n")
    print(f"main's source table is {at}: {every} of {mods} go to any version from source")
    return table


# ---- one mod in a real game -------------------------------------------------------------------------------------------
def prove(loader, to, slug, timeout):
    """Ports one mod from source to a target and, when it builds, starts it hidden in a real client of that release
    (the port sweep's game check: ui-check, the loader's mod list, and the blocks and items its assets name in the
    game's registries). Writes .runs/source-ports/prove-LOADER-SLUG-TO.json."""
    row = next(r for r in pins(loader) if r["slug"] == slug and "commit" in r)
    out = WORK / "prove"
    rec = port_one(loader, row, to, out, timeout)
    if rec.get("jar"):
        rec["linked"] = link(loader, row["builds"], to, [out / "jars" / rec["jar"]], out).get(f"{slug}--{row['file']}")
        mod = {"id": slug, "project": row.get("project", ""), "loader": loader, "behaviour": []}
        rec["seconds"] = {"port": rec["seconds"]}
        SW.game_checks(mod, rec, WORK / "clones" / slug)
    path = WORK / f"prove-{loader}-{slug}-{to}.json"
    path.write_text(json.dumps(rec, indent=1, default=str), encoding="utf-8")
    load = rec.get("load") or {}
    print(f"{slug} {row['builds']} -> {to}: {'built' if rec.get('jar') else 'did not build'}; links: "
          f"{'yes' if rec.get('linked') and not rec['linked']['left'] else (rec.get('linked') or {}).get('left')}; "
          f"game: {load.get('ok')} ({load.get('step')}: {load.get('detail')}); behaviour: {rec.get('behaviour')}")
    return 0 if load.get("ok") else 1


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("pin", help="find each mod's source and pin the commit of its jar")
    p.add_argument("--loader", choices=sorted(settings.FIRST))
    p.add_argument("--only", help="slugs, comma-separated")
    p = sub.add_parser("plan", help="the baseline jobs, as JSON")
    p.add_argument("--loader", choices=sorted(settings.FIRST))
    p.add_argument("--only", help="slugs, comma-separated")
    p.add_argument("--size", type=int, default=4)
    p = sub.add_parser("baseline", help="build each pinned source on its own release")
    p.add_argument("--loader", required=True, choices=sorted(settings.FIRST))
    p.add_argument("--mods", required=True)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--timeout", type=int, default=2400)
    p = sub.add_parser("port-plan", help="the port jobs for the mods whose baseline built, as JSON per loader")
    p.add_argument("folder", type=Path)
    p.add_argument("--phase", choices=("ends", "rest"), default="ends")
    p.add_argument("--size", type=int, default=8)
    p.add_argument("--loader", choices=sorted(settings.FIRST))
    p.add_argument("--to", help="only these targets, comma-separated")
    p = sub.add_parser("port", help="port each mod to one target and link the jars that built")
    p.add_argument("--loader", required=True, choices=sorted(settings.FIRST))
    p.add_argument("--to", required=True)
    p.add_argument("--mods", required=True)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--timeout", type=int, default=2400, help="seconds for one port")
    p.add_argument("--budget", type=int, default=18000, help="seconds after which no new port starts")
    p = sub.add_parser("join", help="the any-version table from every job's summary")
    p.add_argument("folder", type=Path)
    p.add_argument("--out", type=Path)
    p = sub.add_parser("prove", help="port one mod and start it in a hidden real game")
    p.add_argument("--loader", required=True, choices=sorted(settings.FIRST))
    p.add_argument("--to", required=True)
    p.add_argument("--mod", required=True)
    p.add_argument("--timeout", type=int, default=3600)
    args = ap.parse_args(argv)
    loaders = [args.loader] if getattr(args, "loader", None) else sorted(settings.FIRST)
    if args.cmd == "pin":
        for lo in loaders:
            pin(lo, set(args.only.split(",")) if args.only else None)
    elif args.cmd == "plan":
        jobs = plan(loaders, args.size)
        if args.only:
            wanted = set(args.only.split(","))
            jobs = [f"{j.split(':')[0]}:{','.join(s for s in j.split(':')[1].split(',') if s in wanted)}" for j in jobs]
            jobs = [j for j in jobs if not j.endswith(":")]
        print(json.dumps(jobs))
    elif args.cmd == "baseline":
        rows = {r["slug"]: r for r in pins(args.loader) if "commit" in r}
        for slug in args.mods.split(","):
            baseline_one(args.loader, rows[slug], args.out, args.timeout)
    elif args.cmd == "port-plan":
        jobs = port_plan(args.folder, args.size, loaders, args.phase)
        if args.to:
            jobs = [j for j in jobs if j.split(":")[1] in set(args.to.split(","))]
        print(json.dumps(jobs))
    elif args.cmd == "port":
        port_job(args.loader, args.to, args.mods.split(","), args.out, args.timeout, args.budget)
    elif args.cmd == "join":
        join(args.folder, args.out)
    elif args.cmd == "prove":
        return prove(args.loader, args.to, args.mod, args.timeout)
    return 0


if __name__ == "__main__":
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    sys.exit(main())

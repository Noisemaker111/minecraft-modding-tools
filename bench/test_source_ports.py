"""The source scoreboard (bench/source_ports.py) counts what users would see.
- A mod's source is pinned to the commit of its pinned jar: the tag naming the jar's own version, never a tag that only
  names the game release (every build of that release carries it) or another loader's build.
- A mod counts only when its port builds and links on every target; a target whose job did not run, a mod with no
  source and a source that does not build on its own release count as not reached, each ranked by why.
Run: python bench/test_source_ports.py"""
import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from portboard import gamereleases, settings  # noqa: E402
import source_ports as S  # noqa: E402

checks = 0


def check(ok, label):
    global checks
    if not ok:
        raise AssertionError(label)
    checks += 1


# Tags as Sodium, Jade and FerriteCore publish them: the jar's version is in the tag with the game release or the loader
# around it, and other builds of the same release or of the other loader sit beside it.
sodium = {"mc1.21.1-0.6.0": "a", "mc1.21.1-0.8.13": "b", "mc1.20.1-0.5.13": "c", "mc1.21.1-0.8.1": "d"}
words = S.version_words("0.8.13+mc1.21.1", "mc1.21.1-0.8.13-neoforge")
check(S.tag_for(sodium, words, "1.21.1", "neoforge")[0] == "mc1.21.1-0.8.13", f"Sodium 0.8.13 on 1.21.1 is tag mc1.21.1-0.8.13, not {S.tag_for(sodium, words, '1.21.1', 'neoforge')}")
check(not any(w in ("1.21.1", "mc1.21.1", "1.21") for w in words), f"a word that is only the game release names every build of it: {words}")
jade = {"fabric-15.10.6": "f", "neoforge-15.10.6": "n", "neoforge-15.10.5": "o"}
check(S.tag_for(jade, S.version_words("15.10.6+neoforge"), "1.21.1", "neoforge")[0] == "neoforge-15.10.6", "Jade's NeoForge jar is the neoforge- tag, not Fabric's")
check(S.tag_for(jade, S.version_words("15.10.6+fabric"), "1.21.1", "fabric")[0] == "fabric-15.10.6", "Jade's Fabric jar is the fabric- tag")
# 0.8.1 is not 0.8.13, nor the reverse.
check(S.tag_for({"mc1.21.1-0.8.13": "b"}, S.version_words("0.8.1"), "1.21.1", "neoforge")[0] is None, "0.8.1 matched the tag of 0.8.13")
check(S.tag_for({"v1.2.3": "x"}, S.version_words("1.2.3"), "1.20.1", "fabric")[0] == "v1.2.3", "a v-prefixed tag of the version")
check(S.tag_for({"1.20.1": "x", "release": "y"}, S.version_words("1.20.1"), "1.20.1", "fabric")[0] is None,
      "a jar whose version is the game release has no tag of its own")

# Links in a mod's metadata: the repository, its issues page, a file in it; a loader's own repository is not the mod's.
check(S.repo_url("https://github.com/squeek502/AppleSkin/issues") == "https://github.com/squeek502/AppleSkin", "an issues link gives its repository")
check(S.repo_url("https://github.com/Owner/Mod.git") == "https://github.com/Owner/Mod", "a .git link")
check(S.repo_url("https://gitlab.com/group/sub/mod/-/issues") == "https://gitlab.com/group/sub/mod", "a GitLab issues link in a subgroup")
check(S.repo_url("https://github.com/FabricMC/fabric/issues") is None, "Fabric API's own repository is not the mod's source")
check(S.repo_url("https://modrinth.com/mod/sodium") is None, "a Modrinth page is not a repository")

# A built jar is named for the pinned jar, with its content's hash between, and read back as the pinned jar's name.
check(S.source_name("fabric-api--src0123456789--fabric-api-0.92.12+1.20.1.jar") == "fabric-api--fabric-api-0.92.12+1.20.1.jar", "a hyphenated slug's built jar")


# The table: mods of a loader whose ports all ran, one with a target that did not, one with no source, one whose source
# does not build on its own release, and one that missed the newest release and so was not run on the other newer ones.
# The jar is the question, not the mod's own tests: assemble, for the pinned project or the whole build.
check(S.jar_task({"project": ":NeoForge"}) == ":NeoForge:assemble" and S.jar_task({"project": ""}) == "assemble",
      f"a source builds its jar with assemble: {S.jar_task({'project': ':NeoForge'})}")


# A jar Gradle built counts though mmt's own check of it found a data problem; a Gradle stop never does.
bop = {"stages": [{"name": "metadata", "exit": 0}, {"name": "compile", "exit": 0}], "result": {"modChecks": {"ok": False}}}
stopped = {"stages": [{"name": "metadata", "exit": 0}, {"name": "compile", "exit": 1}], "result": {"modChecks": {"ok": False}}}
check(S.gradle_built(1, bop) and S.gradle_built(0, {}) and not S.gradle_built(1, stopped) and not S.gradle_built(2, bop),
      "a built jar counts despite mmt's data checks; a failed Gradle run or an mmt error does not")


def table(drop_target=False):
    with tempfile.TemporaryDirectory() as folder:
        folder = Path(folder)
        pins_dir, runs = folder / "pins", folder / "runs"
        pins_dir.mkdir()
        runs.mkdir()
        mods = ["ok", "late", "missing", "nosource", "nobuild", "far"]
        loader = "neoforge"
        frm = gamereleases.most_used(loader)
        (pins_dir / f"{loader}-{frm}.json").write_text(json.dumps({"mods": [{"slug": m, "file": f"{m}-1.jar"} for m in mods]}), encoding="utf-8")
        (pins_dir / f"fabric-{gamereleases.most_used('fabric')}.json").write_text(json.dumps({"mods": []}), encoding="utf-8")
        rows = [{"slug": m, "file": f"{m}-1.jar", "repo": "https://github.com/o/" + m, "commit": "c" * 40, "builds": frm} for m in ("ok", "late", "missing", "nobuild", "far")]
        rows.append({"slug": "nosource", "file": "nosource-1.jar", "none": "no published source"})
        for m in ("ok", "late", "missing", "nobuild", "far"):
            ok = m != "nobuild"
            (runs / f"baseline-{loader}-{m}.json").write_text(json.dumps({"slug": m, "loader": loader, "ok": ok, "class": None if ok else "compile-errors", "detail": ""}), encoding="utf-8")
        targets = [t for _, t in gamereleases.any_version(loader)]
        for i, t in enumerate(targets):
            if drop_target and i == 0:
                continue  # this target's job never ran
            verdicts = {"ok-1.jar": {"left": [], "types": []}, "late-1.jar": {"left": [], "types": []}}
            verdicts = {f"{k.split('-')[0]}--{k}": v for k, v in verdicts.items()}
            if i == len(targets) - 1:
                verdicts["late--late-1.jar"] = {"left": ["signature-change: X: y"], "types": ["does not build: signature-change"]}
                verdicts["far--far-1.jar"] = {"left": ["mixin: X"], "types": ["does not build: mixin-target-change"]}
            elif gamereleases.vkey(t) < gamereleases.vkey(frm):
                verdicts["far--far-1.jar"] = {"left": [], "types": []}
            # "missing" was in the job's group but its verdict is not in the summary (the job lost it).
            (runs / f"source-{loader}-{t}-ok.json").write_text(json.dumps({"kind": "source", "loader": loader, "from": frm, "to": t, "verdicts": verdicts}), encoding="utf-8")
        old_pins, old_dir = S.pins, settings.PINS
        S.pins = lambda lo: rows if lo == loader else []
        settings.PINS = pins_dir
        try:
            import io
            import contextlib
            with contextlib.redirect_stdout(io.StringIO()):
                return S.join(runs)
        finally:
            S.pins, settings.PINS = old_pins, old_dir


t = table()
a = t["anyVersion"]
check(a["everywhere"]["neoforge"] == ["ok"], f"only the mod that built and linked on every target counts: {a['everywhere']}")
check(a["loaders"]["neoforge"]["mods"] == 6, f"every pinned mod is counted, with or without source: {a['loaders']['neoforge']['mods']}")
held = {m["mod"]: m for m in a["held"]}
check(held["late"]["reached"] == held["late"]["of"] - 1, "a mod that misses one target reaches all but one")
check(list(held["nosource"]["types"]) == ["no published source"], f"a mod with no source is held by that on every target: {held['nosource']['types']}")
check(list(held["nobuild"]["types"]) == ["source does not build on its own release: compile-errors"], f"{held['nobuild']['types']}")
check(list(held["missing"]["types"]) == ["not run"] and held["missing"]["reached"] == 0, "a mod whose verdicts are missing is not reached, not dropped")
older = [x for _, x in gamereleases.any_version("neoforge") if gamereleases.vkey(x) < gamereleases.vkey(gamereleases.most_used("neoforge"))]
check(held["far"]["types"] == {"does not build: mixin-target-change": 1} and held["far"]["reached"] == len(older),
      f"a mod not run past the end it missed is held by that end's break alone: {held['far']}")
check(any(k["type"] == "does not build: mixin-target-change" and k["finishes"] == 1 for k in a["types"]), "the end's break frees that mod in the ranking")
check(t["sources"]["neoforge"]["source"] == 5 and t["sources"]["neoforge"]["builds"] == 4, f"how many have source and build: {t['sources']['neoforge']}")
check("ok" not in table(drop_target=True)["anyVersion"]["everywhere"]["neoforge"], "a target whose job did not run counts as not reached")

print(f"source scoreboard: {checks} checks passed")

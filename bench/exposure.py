"""What real mods reference of the game, ported as jars and linked against the next release: the porter's scoreboard and its to-do list.

    python bench/exposure.py jars --loader neoforge|fabric --from 1.21.1 --to 1.21.2 [--type TYPE] [--save] [--out FILE]
    python bench/exposure.py all [--loader neoforge|fabric] [--only fabric:1.20.1] [--jobs 4] [--out FILE] [--verify]
    python bench/exposure.py all --at origin/main --out BASE.json    (any command, on another commit's porter)
    python bench/exposure.py join DIR [--out FILE]
    python bench/exposure.py compare BEFORE.json AFTER.json
    python bench/exposure.py fetch --loader neoforge|fabric --version 1.21.1 [--repin]
    python bench/exposure.py pin [--loader neoforge|fabric] [--version 1.21.1] [--repin]
    python bench/exposure.py load --loader neoforge --from 1.21.1 --to 1.21.2 [--mods SLUG,...] [--save] [--out FILE]
    python bench/exposure.py load-plan neoforge:1.21.1:1.21.2,fabric:1.21.1
    python bench/exposure.py steps [--from 1.14.4] [--to 26.3]

The mods are the 150 most downloaded of one loader and release on Modrinth, each in its newest file for that release,
listed once in bench/exposure/mods/LOADER-VERSION.json so every run reads the same jars, here or in the cloud (pin
lists them for every release a loader is read for, without downloading; --repin lists them again). They are downloaded into ~/.mmt/exposure/jars/ and only read, never built or shipped: rules still come only from the game and
the loaders.

jars ports one release's mods to the next with tools/port/jar/JarPort.java (what mmt port --jar runs) and links every reference
their classes, mixins and access transformers make to the game against the new release, as the JVM and Mixin will. It
prints the mods that link and the references that hold the most back, each with what the game's own code did about it
(tools/port/Exposure.java: replaced, template, split, linked, redesigned, unused). Fabric mods ship in intermediary names
before 26.1; tools/port/names/ModNames.java reads them in Mojang's names first, through the game's own mappings.

all runs jars for every update a loader's jars can be read across (Fabric from 1.14.4, NeoForge from its first release,
1.20.2; its mods before 1.20.5 are read through NeoForm's SRG names) and joins them: per update the share of mods that link, in total, and the references that
hold the most mods back across every update. join does the joining for summaries made elsewhere (the cloud run,
tools/cloud-run exposure). --out writes the table to a file.

Main's table is kept on the generated branch as scoreboard/all.json, never on main (bench/generated.py): every push to
main runs the cloud sweep (.github/workflows/cloud-exposure.yml, which reuses a whole sweep of the same tree), and the
record job of generated.yml saves the table there when it ends, with the commit it ran, and adds its totals to the port.jar-links points in scoreboard/points.json. A commit to main for each would
put every open pull request behind. `python bench/generated.py show scoreboard/all.json` prints the newest; compare
prints what a branch's table changes against main's (tools/cloud-run exposure finds main's for the branch's base).

Every stage is kept in ~/.mmt/exposure/ under the content of everything it reads (the porter's classes, the route map,
the rules, the loaders' release lists, the JVM, each mod's jar), shared by every worktree and run on the machine, so a
run computes only what an edit can change: an edit to JarPort ports every mod again (each one runs the code it touched),
but reuses the downloads, the names, the map, the registry reports and most of Exposure's answers; an edit to anything else
reuses the ports. jars prints which mods newly link, stop linking or changed their port against the run before, and the
load --mods that proves only those in game. --verify computes everything again and fails where a kept answer differs (main's sweep).

--save keeps a jars or load summary in bench/exposure/, named for the update and the commit it ran on, so two pull
requests proving the same update keep two receipts instead of conflicting on one.

load is the proof that the list predicts the game: the mods jars says link, with the library mods each one's Modrinth
version lists as required (their release for the new version, else their old one ported along), are ported and
started together on a real server of the new release (mmt pack-check), then in a real client of it (pack-check --side
client: the title screen, a world, the inventory and every creative tab), since a server never runs client code; a
failed start drops the mod its log names and starts again. It reports each mod's server and client verdicts side by
side, and any mod that loaded on the server and failed in the client.

jars also runs the JVM's verifier (the JDK's copy of HotSpot's) over every ported class against the new release, as the
game would when it loads the class: a value of a type the instruction using it does not accept, or a method declared
twice, is a VerifyError or ClassFormatError in game though every reference links, and the mod does not link. A class
route (a removed class moved to the class the game put in its place) the verifier refuses for a mod is left out for that
mod, and the old class is then a stand-in's (knowledge/standins) or reported gone.

The port also carries each mod's data files to the new release (tools/port/jar/DataIds.java): ids the game's own data fixers
rename, files the game would skip, tag entries made optional; what is left is each mod's "data" in the report, a
built-in game id (an enchantment effect type, a feature, a data component) the old release registers and the new one
does not, read from each release's own data generator report. The server refuses such a file, which the link check
cannot see; modsThatLinkAndData counts the mods that pass both.

steps measures the porter on every update at once, with no mods: GameDiff links each release to the next from the
game's own code, and for every update it counts the classes, methods and fields with no counterpart in the next release (renames,
moves and new signatures are linked and carried), and how many of them have a replacement learned from how the game's
own code changed (call rewrites, call chains, class replacements). A new kind of change the learner understands shows up in every
update here, not only in the one being worked on. Kept in bench/exposure/steps.json.
"""
import argparse
import functools
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import port_tools  # noqa: E402
from portboard import board, cachekeys, gameload, gamereleases, gamesteps, mainrecord, modrinth, settings  # noqa: E402


def tree_at(ref):
    """What a commit's sweep reads (the porter's sources, the rules, the pinned mods, the change index), unpacked once in
    .runs/exposure/at/SHA/ with this file beside it, so the commit's ports run here with the same cache and the same
    loader releases as this branch's: the base a branch is compared with."""
    sha = subprocess.run(["git", "rev-parse", "--verify", ref + "^{commit}"], cwd=settings.ROOT, capture_output=True, text=True).stdout.strip()
    if not sha:
        raise SystemExit(f"no commit {ref}")
    tree = settings.ROOT / ".runs" / "exposure" / "at" / sha
    if not (tree / "tools").is_dir():
        import io
        import tarfile
        packed = subprocess.run(["git", "archive", sha, "tools", "knowledge", "bench/exposure/mods", "coverage/changes/index.json"],
                                cwd=settings.ROOT, capture_output=True, check=True).stdout
        part = cachekeys.part_of(tree)
        with tarfile.open(fileobj=io.BytesIO(packed)) as t:
            t.extractall(part, filter="data")
        try:
            os.rename(part, tree)
        except OSError:
            shutil.rmtree(part, ignore_errors=True)
    (tree / "bench").mkdir(exist_ok=True)
    for name in ("exposure.py", "port_tools.py"):
        shutil.copyfile(Path(__file__).parent / name, tree / "bench" / name)
    shutil.copytree(Path(__file__).parent / "portboard", tree / "bench" / "portboard", dirs_exist_ok=True, ignore=shutil.ignore_patterns("__pycache__"))
    return sha, tree


def main():
    if "--at" in sys.argv:
        # Runs this command on another commit's porter: e.g. all --at origin/main for the base a branch is compared with.
        i = sys.argv.index("--at")
        sha, tree = tree_at(sys.argv[i + 1])
        rest = sys.argv[1:i] + sys.argv[i + 2:]
        rest = [str(Path(x).resolve()) if j > 0 and rest[j - 1] == "--out" else x for j, x in enumerate(rest)]
        print(f"running on {sha[:10]}'s porter, rules and pinned mods ({tree})", flush=True)
        sys.exit(subprocess.run([sys.executable, str(tree / "bench" / "exposure.py"), *rest], cwd=tree).returncode)
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    f = sub.add_parser("fetch")
    f.add_argument("--loader", required=True, choices=sorted(settings.FIRST))
    f.add_argument("--version", required=True)
    f.add_argument("--repin", action="store_true", help="list the most-downloaded mods again instead of the pinned ones")
    for name in ("jars", "load"):
        j = sub.add_parser(name)
        j.add_argument("--loader", required=True, choices=sorted(settings.FIRST))
        j.add_argument("--from", dest="frm", required=True)
        j.add_argument("--to", required=True, help="the release to port to" + (" (jars: several, comma-separated, ported in one go)" if name == "jars" else ""))
        j.add_argument("--save", action="store_true", help="keep the summary in bench/exposure/, named for the update and this commit")
        j.add_argument("--out", type=Path, help="write the summary to this file (a folder that is there, or jars with several --to: LOADER-FROM-TO.json each in it)")
        if name == "jars":
            j.add_argument("--verify", action="store_true", help="port and ask everything again, and fail where a cached answer differs")
            j.add_argument("--type", dest="kind", help="port only the mods the last whole run held back with this type of break (or part of its text)")
        if name == "load":
            j.add_argument("--mods", help="only these mods (Modrinth slugs, comma-separated), with the libraries they need")
    lp = sub.add_parser("load-plan", help="the updates a cloud game proof runs, as JSON (tools/cloud-run load's matrix)")
    lp.add_argument("updates", help="LOADER:FROM:TO or LOADER:FROM (the next release), comma-separated")
    lp.add_argument("--mods", default="", help="the slugs the proof asks of every update; one that no update pins stops the plan")
    ls = sub.add_parser("updates", help="the jobs all runs, each LOADER:FROM:TO,TO... (plan), as JSON (the cloud run's matrix)")
    ls.add_argument("--loader", choices=sorted(settings.FIRST))
    ls.add_argument("--only", help="LOADER:FROM or LOADER:FROM:TO, comma-separated")
    pn = sub.add_parser("pin")
    pn.add_argument("--loader", choices=sorted(settings.FIRST), help="one loader (default both)")
    pn.add_argument("--version", help="one release (default every one the loader is read for)")
    pn.add_argument("--repin", action="store_true", help="list again releases already pinned")
    al = sub.add_parser("all")
    al.add_argument("--loader", choices=sorted(settings.FIRST), help="one loader (default both)")
    al.add_argument("--only", help="LOADER:FROM,... the updates to run (default every one)")
    al.add_argument("--verify", action="store_true", help="port and ask everything again, and fail where a cached answer differs")
    al.add_argument("--jobs", type=int, default=4, help="updates run side by side (each JarPort takes up to 10 GB)")
    al.add_argument("--out", type=Path, help="write the table to this file (main's is on the generated branch, scoreboard/all.json)")
    jo = sub.add_parser("join")
    jo.add_argument("folder")
    jo.add_argument("--out", type=Path, help="write the table to this file")
    co = sub.add_parser("compare", help="what one table (a branch's) changes against another (main's)")
    co.add_argument("before", type=Path)
    co.add_argument("after", type=Path)
    st = sub.add_parser("steps")
    st.add_argument("--from", dest="frm", default="1.14.4")
    st.add_argument("--to", default="26.3")
    a = p.parse_args()
    settings.VERIFY = bool(getattr(a, "verify", False))
    if a.command == "steps":
        gamesteps.steps(a.frm, a.to)
    elif a.command == "jars":
        board.jars(a.loader, a.frm, [t.strip() for t in a.to.split(",") if t.strip()], a.save, a.out, kind=a.kind)
    elif a.command == "updates":
        only = {x.strip() for x in a.only.split(",")} if a.only else None
        loaders = [a.loader] if a.loader else sorted(settings.FIRST)
        every = [(lo, x, y) for lo in loaders for x, y in gamereleases.runs(lo)]
        # A name that picks nothing ran an empty sweep that looked like a result (0 of 0); say which instead.
        unknown = sorted(o for o in only or () if not any(o in (f"{lo}:{x}", f"{lo}:{x}:{y}") for lo, x, y in every))
        if unknown:
            raise SystemExit(f"--only names no update: {', '.join(unknown)} (LOADER:FROM for every update from it, or LOADER:FROM:TO)")
        print(json.dumps([f"{lo}:{x}:{','.join(tos)}" for lo in loaders for _, x, tos in board.plan(lo, only)]))
    elif a.command == "load":
        gameload.load(a.loader, a.frm, a.to, a.save, [m.strip() for m in a.mods.split(",") if m.strip()] if a.mods else None, a.out)
    elif a.command == "load-plan":
        print(json.dumps(gameload.load_plan(a.updates, [m.strip() for m in a.mods.split(",") if m.strip()])))
    elif a.command == "all":
        only = {x.strip() for x in a.only.split(",")} if a.only else None
        board.every_update([a.loader] if a.loader else sorted(settings.FIRST), only, a.jobs, a.out)
    elif a.command == "pin":
        for lo in [a.loader] if a.loader else sorted(settings.FIRST):
            for v in [a.version] if a.version else [frm for frm, _ in gamereleases.updates(lo)]:
                if a.repin or not (settings.PINS / f"{lo}-{v}.json").is_file():
                    modrinth.pin(lo, v)
    elif a.command == "join":
        board.join(a.folder, a.out)
    elif a.command == "compare":
        before, after = (json.loads(f.read_text(encoding="utf-8")) for f in (a.before, a.after))
        print("\n".join((gameload.compare_loads if "verdicts" in after else mainrecord.compare)(before, after)))
    else:
        print(f"{len(modrinth.fetch(a.loader, a.version, a.repin))} {a.loader} mods for {a.version} in {settings.HOME / 'jars' / f'{a.loader}-{a.version}'}")


if __name__ == "__main__":
    main()

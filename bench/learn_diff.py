"""What a change to the porter's learner learns differently: every line of the route map's "learned" list, from the
working tree's GameDiff and from another commit's (main by default), on each update, side by side.

    python bench/learn_diff.py [--base origin/main] [--loader neoforge|fabric] [--hops 1.21.4,1.21.5 26.2,26.3] [--jobs 3]
    python bench/learn_diff.py --repeat [--loader ...] [--hops ...]   the working tree against itself, run again

A sweep counts mods that link, which hides a reading that is wrong but links (kill() learned as discard()) and one lost
where another rule happened to cover it. This lists, per update, each member whose reading is new (+), gone (-) or
changed (~), with both expressions, so each can be judged before the sweep. With no --hops it reads every update the
loader's mods are read across. The other commit's maps are kept in .runs/learn-diff/ by its commit, so a second run
reads only the working tree again. --repeat compares two fresh runs of the working tree instead: a port must come out
the same every time, so any line listed there is a bug.
"""
import argparse
import concurrent.futures
import io
import json
import subprocess
import sys
import tarfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import exposure  # noqa: E402
import port_tools  # noqa: E402

ROOT = exposure.ROOT
OUT = ROOT / ".runs" / "learn-diff"


def base_classes(ref):
    """The other commit's tools compiled once, under its commit id."""
    sha = subprocess.run(["git", "rev-parse", ref], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()
    folder = OUT / sha[:12]
    classes = folder / "classes"
    if not (classes / "GameDiff.class").is_file():
        src = folder / "src"
        packed = subprocess.run(["git", "archive", sha, port_tools.PORT, *port_tools.WITH], cwd=ROOT, capture_output=True, check=True).stdout
        with tarfile.open(fileobj=io.BytesIO(packed)) as t:
            t.extractall(src, filter="data")
        sources = port_tools.sources(root=src)
        javac = exposure.java().replace("java.exe", "javac.exe") if exposure.java().endswith("java.exe") else exposure.java() + "c"
        subprocess.run([javac, "-nowarn", "-encoding", "UTF-8", "-cp", ";".join(map(str, exposure.ASM)) if sys.platform == "win32" else ":".join(map(str, exposure.ASM)),
                        "-d", str(classes)] + [str(src / n) for n in sources], check=True, capture_output=True)
    sep = ";" if sys.platform == "win32" else ":"
    return folder, sep.join([str(classes)] + [str(a) for a in exposure.ASM])


def learned(classes, frm, to, loader, out):
    if not out.is_file():
        part = out.with_suffix(".part")
        subprocess.run([exposure.java(), f"-Xmx{min(8, exposure.HEAP)}g", "-cp", classes, "GameDiff", "--chain", f"{frm},{to}", "--loader", loader, "--out", str(part)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        part.replace(out)
    lines = {}
    for w in json.loads(out.read_text(encoding="utf-8")).get("learned", []):
        then = w.get("then") or {}
        key = (w["owner"], w["from"], w.get("desc", ""), w.get("when"), then.get("owner"), then.get("from") and f'{then.get("from")}@{then.get("at")}:{w.get("result")}')
        lines[key] = w.get("expr", "")
    return lines


def rest(one, two, name):
    """Whether the two runs' whole maps match too, the timings in "hops" apart."""
    x, y = (json.loads((f / name).read_text(encoding="utf-8")) for f in (one, two))
    differ = sorted(k for k in set(x) | set(y) if k != "hops" and x.get(k) != y.get(k))
    return "; the rest of the map the same" if not differ else "; the rest of the map differs in " + ", ".join(differ)


def shown(key):
    owner, name, desc, when, then_owner, then_name = key
    text = f"{owner}.{name}{desc}"
    if when:
        text += f" when ${when.replace(':', ' is ', 1)}"
    if then_owner:
        text += f" then {then_owner}.{then_name}"
    return text


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base", default="origin/main")
    p.add_argument("--loader", default="neoforge", choices=["neoforge", "fabric"])
    p.add_argument("--hops", nargs="*", help="updates as FROM,TO; every update of the loader when left out")
    p.add_argument("--jobs", type=int, default=3)
    p.add_argument("--repeat", action="store_true", help="the working tree against a second run of itself")
    a = p.parse_args()
    hops = [tuple(h.split(",")) for h in a.hops] if a.hops else exposure.updates(a.loader)
    head = exposure.jar_tools()
    work, again = OUT / "work", OUT / "again"
    for folder in (work, again):
        folder.mkdir(parents=True, exist_ok=True)
        for old in folder.glob("*.json"):
            old.unlink()
    if a.repeat:
        base_folder, base, a.base = again, head, "a second run of the working tree"
    else:
        base_folder, base = base_classes(a.base)

    def one(hop):
        frm, to = hop
        before = learned(base, frm, to, a.loader, base_folder / f"{a.loader}-{frm}-{to}.json")
        after = learned(head, frm, to, a.loader, work / f"{a.loader}-{frm}-{to}.json")
        return hop, before, after

    totals = {"+": 0, "-": 0, "~": 0}
    with concurrent.futures.ThreadPoolExecutor(a.jobs) as pool:
        for (frm, to), before, after in pool.map(one, hops):
            rows = []
            for key in sorted(set(before) | set(after), key=lambda k: tuple(x or "" for x in k)):
                if key not in before:
                    rows.append(("+", key, None, after[key]))
                elif key not in after:
                    rows.append(("-", key, before[key], None))
                elif before[key] != after[key]:
                    rows.append(("~", key, before[key], after[key]))
            print(f"{a.loader} {frm} -> {to}: {len(rows)} changed of {len(after)} learned" + (rest(base_folder, work, f"{a.loader}-{frm}-{to}.json") if a.repeat else ""))
            for mark, key, was, now in rows:
                totals[mark] += 1
                print(f"  {mark} {shown(key)}")
                if was is not None:
                    print(f"      was {was}")
                if now is not None:
                    print(f"      now {now}")
    print(f"{len(hops)} updates: {totals['+']} new, {totals['-']} gone, {totals['~']} changed against {a.base}")


if __name__ == "__main__":
    main()

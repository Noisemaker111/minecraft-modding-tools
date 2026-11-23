"""The bot's output never lands on main (bench/generated.py).
- A regenerate and a scoreboard record each push one commit to the generated branch of a real git remote, and main's ref
  there does not move: main requires pull requests to be up to date, so a bot commit on main put every open one behind.
- When another run pushed first, the record takes that commit and records again on top: no scoreboard point is lost.
- The recorded points reach every number shown (measures.Book), and coverage/measures.json may not hold them itself.
- No workflow pushes to main.
Run: python bench/test_generated.py"""
import io
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generated as G  # noqa: E402
import measures as M  # noqa: E402

ROOT = G.ROOT
checks = 0


def check(ok, label):
    global checks
    if not ok:
        raise AssertionError(label)
    checks += 1


def git(cwd, *args):
    return subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True, encoding="utf-8", check=True).stdout.strip()


def table(link):
    return {"date": "2026-11-23", "total": {"link": link, "mods": 300, "big": {"link": link // 2, "mods": 150}, "newest": {"link": 7, "mods": 294}},
            "loaders": {}, "updates": [], "jumps": [], "types": []}


def quiet(fn, *args, **kwargs):
    sys.stdout = io.StringIO()
    try:
        return fn(*args, **kwargs)
    finally:
        sys.stdout = sys.__stdout__


head = git(ROOT, "rev-parse", "HEAD")
with tempfile.TemporaryDirectory() as tmp:
    tmp = Path(tmp)
    remote = tmp / "origin.git"
    git(tmp, "init", "--quiet", "--bare", str(remote))
    seed = tmp / "seed"
    git(tmp, "init", "--quiet", str(seed))
    for k, v in (("user.name", "t"), ("user.email", "t@example.invalid"), ("core.autocrlf", "false")):
        git(seed, "config", k, v)
    (seed / "src.txt").write_text("main\n", encoding="utf-8")
    git(seed, "add", "-A")
    git(seed, "commit", "--quiet", "-m", "main")
    git(seed, "push", "--quiet", str(remote), "HEAD:refs/heads/main")
    git(seed, "checkout", "--quiet", "--orphan", "generated")
    git(seed, "rm", "--quiet", "-rf", "--cached", ".")
    (seed / "src.txt").unlink()
    (seed / "scoreboard").mkdir()
    (seed / "scoreboard" / "all.json").write_text(json.dumps({"commit": head, **table(100)}), encoding="utf-8")
    first = {"date": "2026-11-22", "value": 100, "n": 300, "source": "seed"}
    (seed / "scoreboard" / "points.json").write_text(json.dumps({mid: [first] for mid in ("port.jar-links.every-update", "port.jar-links.big-updates", "port.jar-links.newest")}), encoding="utf-8")
    git(seed, "add", "-A")
    git(seed, "commit", "--quiet", "-m", "seed")
    git(seed, "push", "--quiet", str(remote), "HEAD:refs/heads/generated")
    main_before = git(remote, "rev-parse", "main")

    def clone(name):
        out = tmp / name
        git(tmp, "clone", "--quiet", "--branch", "generated", str(remote), str(out))
        for k, v in (("user.name", "t"), ("user.email", "t@example.invalid"), ("core.autocrlf", "false")):
            git(out, "config", k, v)
        return out

    # A regenerate: the site and ROADMAP.md, one commit on generated, main untouched.
    gen = clone("regenerate")
    quiet(G.publish, gen)
    tip = git(remote, "rev-parse", "generated")
    files = git(remote, "ls-tree", "-r", "--name-only", "generated").split()
    check({"docs/index.html", "docs/logo.svg", "docs/porting-map.html", "ROADMAP.md", "scoreboard/all.json"} <= set(files), f"a regenerate writes the site and ROADMAP.md: {files[:8]}")
    check(git(remote, "rev-parse", "main") == main_before, "a regenerate leaves main where it was")
    check(git(remote, "log", "-1", "--format=%s", "generated").startswith("Site and ROADMAP.md at main"), "the regenerate is one commit on generated")
    quiet(G.publish, gen)
    check(git(remote, "rev-parse", "generated") == tip, "a regenerate with nothing new pushes nothing")

    # A scoreboard record whose push loses a race: another run pushed first, and both survive.
    board = clone("record")
    other = clone("other")
    (other / "note.txt").write_text("pushed first\n", encoding="utf-8")
    git(other, "add", "-A")
    git(other, "commit", "--quiet", "-m", "another run")
    git(other, "push", "--quiet", "origin", "HEAD:generated")
    sweep = tmp / "sweep.json"
    sweep.write_text(json.dumps(table(120)), encoding="utf-8")
    quiet(G.publish, board, sweep, head, "https://example.invalid/runs/9")
    log = git(remote, "log", "--format=%s", "generated").splitlines()
    check(log[0] == f"Main's jar scoreboard at {head[:10]}" and "another run" in log, f"the record goes on top of the run that pushed first: {log[:3]}")
    check(git(remote, "show", "generated:note.txt") == "pushed first", "the other run's commit is kept")
    saved = json.loads(git(remote, "show", "generated:scoreboard/all.json"))
    points = json.loads(git(remote, "show", "generated:scoreboard/points.json"))
    every = points["port.jar-links.every-update"]
    check(saved["commit"] == head and saved["total"]["link"] == 120, "main's table on generated is the new sweep, with its commit")
    check([p["value"] for p in every] == [100, 120] and every[1]["note"].startswith("+20 on 100"), f"one point added, after the earlier ones: {every}")
    check(git(remote, "rev-parse", "main") == main_before, "a scoreboard record leaves main where it was")

    # The recorded points are what every number shows, wherever it is shown.
    fresh = clone("reader")
    book = M.Book(scoreboard=G.points(fresh))
    check("120 mods" in M.table(book, [book.by_id["port.jar-links.every-update"]]), "a recorded point reaches the measures table")
    check(M.Book(scoreboard={}).points(book.by_id["port.jar-links.every-update"]) == [], "a recordedBy measure takes its points only from the generated branch")
    data = json.loads(json.dumps(book.data))
    next(m for m in data["measures"] if m.get("recordedBy"))["points"] = [first]
    check(any("generated branch" in p for p in M.problems(M.Book(data, book.roadmap, book.ledger, book.index, scoreboard={}))),
          "a recordedBy point written into coverage/measures.json is refused")
    check(not any(m.get("recordedBy") and m.get("points") for m in M.load(M.MEASURES)["measures"]), "coverage/measures.json holds no recordedBy points")

# No workflow pushes to main: a checkout that can push (ssh-key) is of the generated branch, and nothing runs git push.
for wf in sorted((ROOT / ".github" / "workflows").glob("*.yml")):
    text = wf.read_text(encoding="utf-8")
    check("git push" not in text, f"{wf.name} runs git push itself")
    for block in re.split(r"\n\s*- uses: ", text):
        if "ssh-key:" in block:
            check(re.search(r"ref: generated\b", block) is not None, f"{wf.name}: a checkout with a push key is not of the generated branch")

print(f"generated: {checks} checks passed")

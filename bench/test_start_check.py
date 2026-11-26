"""The game proof starts only what start-check passed, and start-check's answer is never read as a game's.
- A jar start-check fails is out of the pack before any start, with start-check's reason and the words "not started in a
  game": a mod that cannot load never costs a game start (one failing mod cost 4 server and 3 client starts before).
- A mod start-check passes is "passed" with the checks that ran, never "loaded": no game was started.
- Its agreement with a saved game proof counts a mod the game loaded and it failed apart from one the game failed and it
  passed, so neither kind hides in a total.
Run: python bench/test_start_check.py"""
import json
import subprocess
import sys
import tempfile
import zipfile
import io
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import portboard.gameload as gameload  # noqa: E402
import portboard.startcheck as startcheck  # noqa: E402

checks = 0


def check(ok, label):
    global checks
    if not ok:
        raise AssertionError(label)
    checks += 1


def jar(meta):
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w") as z:
        z.writestr("fabric.mod.json", json.dumps(meta))
    return data.getvalue()


def side(failed=(), passed=(), left=(), checks_ran=("dependencies", "mixins"), clash=()):
    names = {"dependencies": "the loader resolved them", "mixins": "Mixin applied them", "start code": "it ran", "data": "the game read it"}
    return {"failed": {n: f"{n} is refused" for n in failed}, "clash": {n: f"{n} and another redirect one call" for n in clash},
            "leftOut": {n: "client-only" for n in left}, "passed": list(passed), "undecided": None,
            "checks": {n: names[n] if n in checks_ran else "not checked: no run reached it" for n in names}, "runs": []}


# What start-check passes is said to have passed the checks that ran, by name, and never to have loaded.
for ran in (("dependencies", "mixins"), ("dependencies", "mixins", "start code", "data"), ()):
    said = startcheck.verdicts(side(failed=["bad.jar"], passed=["good.jar"], left=["hud.jar"], checks_ran=ran))
    check(said["bad.jar"] == "failed: bad.jar is refused" and said["hud.jar"] == "left out: client-only",
          f"a failed jar carries start-check's reason, a client-only one is left out: {said}")
    check(said["good.jar"].startswith("passed: " + "; ".join(ran)) and "loaded" not in said["good.jar"] and "no world or window" in said["good.jar"],
          f"a passed jar names the checks that ran ({ran}) and never says loaded: {said['good.jar']}")

# Agreement with a game proof: each kind of difference is counted on its own, on each side.
receipt = {"verdicts": {"a.jar": "loaded", "b.jar": "failed: its mixin fails", "c.jar": "loaded", "d.jar": "failed: crashes in a world", "e.jar": "client-only: its metadata",
                        "f.jar": "clash: loads alone, fails next to the other mods", "g.jar": "loaded"},
           "client": {"verdicts": {"a.jar": "loaded", "b.jar": "failed: its mixin fails"}}}
ours = {"server": startcheck.verdicts(side(failed=["b.jar", "c.jar"], clash=["f.jar", "g.jar"], passed=["a.jar", "d.jar"], left=["e.jar"])),
        "client": startcheck.verdicts(side(failed=["a.jar"], passed=["b.jar"]))}
check(ours["server"]["f.jar"].startswith("clash: its mixins apply with only the jars it requires") and "another redirect one call" in ours["server"]["f.jar"],
      f"a jar that fails only next to the others is a clash, with what Mixin said: {ours['server']['f.jar']}")
found = startcheck.agreement(receipt, ours)
check((found["server"]["both"], found["server"]["same"]) == (7, 4), f"the server's verdicts are compared over the mods both judged, failed and clash as one fact of the pack: {found['server']}")
check(list(found["server"]["wronglyFailed"]) == ["c.jar", "g.jar"] and list(found["server"]["missed"]) == ["d.jar"] and not found["server"]["other"],
      f"a mod the game loaded and start-check failed (or found clashing) is named apart from one the game failed and it passed: {found['server']}")
check(list(found["client"]["wronglyFailed"]) == ["a.jar"] and list(found["client"]["missed"]) == ["b.jar"] and found["client"]["same"] == 0,
      f"the client is judged on its own verdicts: {found['client']}")
lines = startcheck.agreement_lines(found)
check(lines[0].startswith("server: 4/7 verdicts the same") and any("game loaded, start-check failed: c.jar" in line for line in lines)
      and any("crashes in a world" in line for line in lines), f"every disagreement is printed with both verdicts: {lines}")
same = startcheck.agreement(receipt, {"server": {n: ("passed: x" if v.startswith("loaded") else "left out: x" if v.startswith("client-only") else "failed: x")
                                               for n, v in receipt["verdicts"].items()}})
check(same["server"]["same"] == same["server"]["both"] == 7 and same["client"]["both"] == 0, f"verdicts of the same kinds all agree, and a side with no answer compares nothing: {same}")
# A jar the proof did not start because start-check had decided it says nothing of the game: it is not counted as agreement.
predicted = dict(receipt, startCheck={"decided": {"server": {"b.jar": "failed: its mixin fails (decided by start-check)"}, "client": {}}})
found = startcheck.agreement(predicted, ours)
check((found["server"]["both"], found["server"]["same"], found["server"]["notStarted"]) == (6, 3, 1) and found["client"]["both"] == 2 and found["client"]["notStarted"] == 0,
      f"a verdict start-check gave the proof is left out of that side's count, and only of that side's: {found}")
check(any("not compared" in line and ": 1" in line for line in startcheck.agreement_lines(found)), f"and the lines say how many were not compared: {startcheck.agreement_lines(found)}")

# start-check keeps no game proof, so it offers no --save (it took the option and ignored it).
refused = subprocess.run([sys.executable, str(Path(__file__).with_name("exposure.py")), "start-check", "--loader", "fabric", "--from", "1.21.1", "--to", "1.21.2", "--save"],
                         capture_output=True, text=True)
check(refused.returncode == 2 and "--save" in refused.stderr, f"start-check refuses --save instead of ignoring it: {refused.returncode} {refused.stderr[-200:]}")

# The client proof never starts a jar start-check failed, and says where its verdict came from.
with tempfile.TemporaryDirectory() as tmp:
    for failed in (["bad.jar"], ["bad.jar", "worse.jar"], []):
        server = Path(tmp) / ("load" + str(len(failed)))
        (server / "mods").mkdir(parents=True)
        (server / "dropped").mkdir()
        mods = ["mod.jar", "bad.jar", "worse.jar"]
        for n in mods + ["api.jar"]:
            (server / "mods" / n).write_bytes(jar({"id": n[:-4]}))
        client = gameload.client_pack(server, mods, ["api.jar"])
        decided = startcheck.decided({"failed": {n: f"Mixin refuses {n}" for n in failed[:1]}, "clash": {n: f"Mixin refuses {n}" for n in failed[1:]}})
        gameload.leave_out(client, decided)
        check(sorted(p.name for p in (client / "dropped").glob("*.jar")) == sorted(failed), f"the jars start-check failed are out of the pack: {failed}")
        started = []

        def run(pack):
            here = sorted(p.name for p in (pack / "mods").glob("*.jar"))
            started.append(here)
            return {"result": {"client": {"opened": True}}}

        said = gameload.client_starts("fabric", "2.0", server, mods, ["api.jar"], {"api.jar"}, run=run, made=True, decided=decided)
        check(len(started) == 1 and not set(started[0]) & set(failed), f"one start, without the jars start-check failed ({failed}): {started}")
        for n in mods:
            verdict = said["verdicts"][n]
            if n in failed:
                check(verdict.startswith("failed: Mixin refuses bad.jar" if n == "bad.jar" else startcheck.CLASH + "Mixin refuses worse.jar") and "not started in a game" in verdict,
                      f"a jar start-check failed, or found clashing, says so and was not started: {verdict}")
            else:
                check(verdict == "loaded", f"a jar start-check passed is judged by the game: {verdict}")

    # With every mod failed by start-check there is no client start at all.
    server = Path(tmp) / "none"
    (server / "mods").mkdir(parents=True)
    (server / "dropped").mkdir()
    for n in ("bad.jar", "api.jar"):
        (server / "mods" / n).write_bytes(jar({"id": n[:-4]}))
    client = gameload.client_pack(server, ["bad.jar"], ["api.jar"])
    gameload.leave_out(client, {"bad.jar": "x"})

    def never(pack):
        raise AssertionError("a client was started with no mod to start")

    said = gameload.client_starts("fabric", "2.0", server, ["bad.jar"], ["api.jar"], {"api.jar"}, run=never, made=True, decided=startcheck.decided({"failed": {"bad.jar": "the loader refuses it"}}))
    check(said["loaded"] == 0 and said["verdicts"]["bad.jar"].startswith("failed: the loader refuses it"), f"a mod start-check failed costs no client start: {said}")

# The receipt's time line says what start-check took, beside the port and the starts.
line = gameload.time_line({"port": 11.2, "startCheck": 6.4, "server": {"starts": 1, "seconds": 30.0, "stages": {}}})
check("port 11 s; start-check 6 s; server 1 start 30 s" in line, f"the time line names start-check: {line}")
check("start-check" not in gameload.time_line({"port": 3.0}), "a receipt from before start-check prints as it did")

print(f"start-check: {checks} checks passed")

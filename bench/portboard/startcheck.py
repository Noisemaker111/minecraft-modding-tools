"""The answer with no game: ported jars through mmt start-check (the loader, Mixin and the game's own start-up code, no world and no window), and how its verdicts agree with a game proof's."""
import json
import os
import subprocess
import time

from portboard import settings


def run(pack, to, loader, jars=()):
    """One mmt start-check of a pack folder, both sides: {"sides": {side: {"failed": {jar: why}, "leftOut": {jar: why},
    "passed": [jar], "undecided": why or None, "checks": {name: what ran}, "runs": [...]}}, "wall": {"up", "says"}, "seconds", "receipt"}. jars:
    the jars the answer is asked for (the mods; the libraries and the platform beside them are only there for them).
    "error" instead of "sides" when mmt printed no answer: the tool's fault, and no jar's."""
    mmt = str(settings.ROOT / ("mmt.cmd" if os.name == "nt" else "mmt"))
    started = time.monotonic()
    done = subprocess.run([mmt, "start-check", "--pack", str(pack), "--minecraft", to, "--loader", loader] + (["--jars", ",".join(jars)] if jars else []) + ["--json"],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")
    at = done.stdout.find("{")
    answer = json.loads(done.stdout[at:]) if at >= 0 else {}
    sides = answer.get("result", {}).get("sides")
    out = {"seconds": round(time.monotonic() - started, 1), "receipt": answer.get("receipt")}
    if sides is None:
        out["error"] = str(answer.get("error") or done.stdout[-400:] or done.stderr[-400:])[:600]
    else:
        out["sides"] = sides
        # Whether the operating system kept the starts off the network and off the screen, in mmt's words.
        out["wall"] = answer["result"].get("wall")
    return out


def verdicts(side):
    """One side's answer as verdicts in the words of a game proof's, so the two read side by side: failed (with why),
    passed (never "loaded": no game was started; with the checks that ran), left out (a server leaves a client-only mod
    out) and undecided."""
    out = {name: "failed: " + why for name, why in side["failed"].items()}
    out |= {name: CLASH + why for name, why in side.get("clash", {}).items()}
    out |= {name: "left out: " + why for name, why in side["leftOut"].items()}
    ran = "; ".join(name for name, what in side["checks"].items() if not what.startswith("not checked"))
    out |= {name: f"passed: {ran} (no world or window was opened)" for name in side["passed"]}
    out |= {name: "undecided: a start that failed named it next to other jars, and no start of the rest got far enough to start it again: " + why
            for name, why in side.get("unresolved", {}).items()}
    return out


CLASH = "clash: its mixins apply with only the jars it requires, and not next to the other jars: "
NOT_STARTED = " (decided by start-check with no world or window; not started in a game)"


def decided(side):
    """The verdicts a game proof gives the jars start-check says do not start next to the others on a side, {jar:
    verdict}: failed, or clash for one whose mixins apply without the others. Neither is started in a game."""
    out = {name: "failed: " + why + NOT_STARTED for name, why in side.get("failed", {}).items()}
    return out | {name: CLASH + why + NOT_STARTED for name, why in side.get("clash", {}).items()}


# A game proof's verdict kinds beside start-check's. Failed and clash are one fact of the pack both ran (the mod does not
# start next to the others); which of the two it is hangs on a second start alone, which each makes only for some mods.
SAME = {"loaded": ("passed",), "failed": ("failed", "clash"), "clash": ("failed", "clash"), "client-only": ("left out",)}


def agreement_of(receipt, summary):
    """agreement of a game proof (a load receipt) with a start-check summary (load with game False)."""
    return agreement(receipt, {"server": summary["verdicts"], "client": summary["client"]["verdicts"]})


def agreement(receipt, said):
    """How start-check's verdicts ({side: {mod: verdict}}) agree with a game proof's (a load receipt), per side, over the mods both judged:
    {"both", "same", "wronglyFailed": {mod: [game verdict, ours]}, "missed": {...}, "other": {...}}. wronglyFailed is a
    mod the game loaded and start-check failed: a bug of the check or a port that changed since. missed is one the game
    failed and start-check passed: what no start without a world shows. A jar the proof never started because
    start-check had decided it (the receipt's startCheck.decided) is no evidence of the game's: it is counted in
    "notStarted" and compared with nothing."""
    out = {}
    for side, game in (("server", receipt.get("verdicts", {})), ("client", receipt.get("client", {}).get("verdicts", {}))):
        ours = said.get(side, {})
        predicted = set(receipt.get("startCheck", {}).get("decided", {}).get(side, {}))
        row = {"both": 0, "same": 0, "wronglyFailed": {}, "missed": {}, "other": {}, "notStarted": len(predicted & set(game) & set(ours))}
        for name in sorted((set(game) & set(ours)) - predicted):
            theirs, mine = game[name].split(":")[0], ours[name].split(":")[0]
            if theirs not in SAME:
                continue
            row["both"] += 1
            if mine in SAME[theirs]:
                row["same"] += 1
            elif theirs == "loaded" and mine in ("failed", "clash"):
                row["wronglyFailed"][name] = [game[name], ours[name]]
            elif theirs in ("failed", "clash") and mine == "passed":
                row["missed"][name] = [game[name], ours[name]]
            else:
                row["other"][name] = [game[name], ours[name]]
        out[side] = row
    return out


def agreement_lines(found):
    """agreement, as lines to print: the counts for each side, then every disagreement with both verdicts."""
    lines = []
    for side, row in found.items():
        lines.append(f"{side}: {row['same']}/{row['both']} verdicts the same as the game's; the game loaded and start-check failed: {len(row['wronglyFailed'])}; "
                     f"the game failed and start-check passed: {len(row['missed'])}; other: {len(row['other'])}"
                     + (f"; not compared, the proof did not start them (start-check had decided): {row['notStarted']}" if row.get("notStarted") else ""))
        for kind, title in (("wronglyFailed", "game loaded, start-check failed"), ("missed", "game failed, start-check passed"), ("other", "other")):
            for name, (theirs, mine) in row[kind].items():
                lines += [f"  {title}: {name}", f"    game: {theirs[:300]}", f"    start-check: {mine[:300]}"]
    return lines

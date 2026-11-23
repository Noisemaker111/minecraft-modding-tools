"""A pull request's port check: the ports its change can move, compared with main's row of each; it fails when a mod that links on main stops linking."""
import re

import port_tools
from portboard import gameload, gamereleases, mainrecord, settings


# What every port reads: the porter's sources, the port rules, this file and the change index (the releases a port crosses,
# and which updates are big). A change to one can move any mod of any port.
SHARED = ({"knowledge/fixes.json", "bench/exposure.py", "bench/port_tools.py", settings.INDEX.relative_to(settings.ROOT).as_posix()}
          | set(port_tools.sources(root=settings.ROOT)) | {p.relative_to(settings.ROOT).as_posix() for p in (settings.ROOT / "bench" / "portboard").glob("*.py")})
NAMED = re.compile(r"^[ \t*_>-]*port check:?[*_]*\s*(.+)$", re.I | re.M)
PAIR = re.compile(r"\b(fabric|neoforge):(\d[\w.]*)(?::(\d[\w.]*))?\b")


def check_plan(changed, body="", table=None, index_before=None):
    """What a pull request's port check runs: a list of jobs {loader, from, to, mods, why} (mods: Modrinth slugs, empty
    for all the release pins) and the lines that say why, from the files it changed, the ports its description names and
    main's table. Each job ports one pair and compares it with main's row, so the check takes about one port's time.

    - A line "Port check: LOADER:FROM:TO [...] [mods=SLUG,...] [type=TEXT]" in the description: those ports (LOADER:FROM
      for the next release); with mods, only those; with a type, only the mods main's row says that type holds back.
    - A changed stand-in folder (knowledge/standins/FROM-TO/, or FROM-TO.LOADER/): that update, for each loader it is for.
    - A changed pin list (bench/exposure/mods/LOADER-FROM.json): the update from that release.
    - A change to what every port reads (SHARED): a sample, not every port, and the lines say so: per loader the big
      update and the small update with the most mods linking on main, and the jump from the most-used release to the
      newest, every mod of each. A changed change index also runs each update it makes (index_before: main's copy).
    Anything else changes nothing the scoreboard reads, and runs nothing."""
    jobs, why = {}, []

    def add(loader, frm, to, mods, reason):
        job = jobs.setdefault((loader, frm, to), {"loader": loader, "from": frm, "to": to, "mods": set(), "all": False, "why": []})
        job["all"] |= not mods
        job["mods"] |= set(mods or ())
        job["why"].append(reason)

    table = mainrecord.main_table() if table is None else table
    for line in NAMED.findall(body or ""):
        typed = re.search(r"\btype=[\"']?(.+?)[\"'`]*\s*$", line)
        kind = typed.group(1).strip() if typed else None
        head = line[:typed.start()] if typed else line
        named = re.search(r"\bmods=([\w.,+-]+)", head)
        for lo, frm, to in PAIR.findall(head):
            to = to or dict(gamereleases.updates(lo)).get(frm)
            if not to or not (settings.PINS / f"{lo}-{frm}.json").is_file():
                raise SystemExit(f"Port check: {lo}:{frm}{':' + to if to else ''} is not a port the scoreboard reads (no next release or no bench/exposure/mods/{lo}-{frm}.json)")
            mods = [m for m in named.group(1).split(",") if m] if named else []
            if kind:
                row = mainrecord.main_row(lo, frm, to, table)
                if row and row.get("heldBy") is not None:
                    held = sorted({m.split("--", 1)[0] for m, types in row["heldBy"].items() if any(kind in t for t in types)})
                    if not held:
                        raise SystemExit(f"Port check: main's {lo} {frm} -> {to} has no mod held back by a type with '{kind}' in it")
                    mods += held
                else:
                    why.append(f"{lo} {frm} -> {to}: main's table lists no types for it yet, so every mod is ported, not only '{kind}'")
            add(lo, frm, to, mods, "named in the description" + (f", type '{kind}'" if kind else ""))
    shared = sorted(f for f in changed if f in SHARED)
    for f in changed:
        stand = re.match(r"knowledge/standins/([\w.]+)-([\w.]+?)(?:\.(fabric|neoforge))?/", f)
        if stand:
            frm, to, only = stand.groups()
            for lo in [only] if only else sorted(settings.FIRST):
                if (frm, to) in gamereleases.updates(lo):
                    add(lo, frm, to, [], f"its stand-ins changed ({f})")
        pins = re.fullmatch(r"bench/exposure/mods/(fabric|neoforge)-([\w.]+)\.json", f)
        if pins and dict(gamereleases.updates(pins.group(1))).get(pins.group(2)):
            add(pins.group(1), pins.group(2), dict(gamereleases.updates(pins.group(1)))[pins.group(2)], [], f"its pinned mods changed ({f})")
    if index_before is not None and settings.INDEX.relative_to(settings.ROOT).as_posix() in changed:
        for lo in sorted(settings.FIRST):
            for frm, to in sorted(set(gamereleases.updates(lo)) - set(gamereleases.updates(lo, index_before)), key=lambda u: gamereleases.vkey(u[0])):
                if (settings.PINS / f"{lo}-{frm}.json").is_file():
                    add(lo, frm, to, [], "the change index makes it an update")
    if shared:
        for lo in sorted(settings.FIRST):
            rows = [r for r in (table or {}).get("updates", []) if r["loader"] == lo]
            for size in (True, False):
                some = [r for r in rows if r.get("big") == size]
                if some:
                    r = max(some, key=lambda r: r["modsThatLink"])
                    add(lo, r["from"], r["to"], [], f"sample: the {'big' if size else 'small'} update with the most mods linking on main ({r['modsThatLink']})")
            newest = gamereleases.updates(lo)[-1][1]
            add(lo, gamereleases.most_used(lo), newest, [], "sample: the most-used release's mods on the newest game")
        why.append(f"{', '.join(shared)} changed, which every port reads: this checks a sample of {len(settings.FIRST) * 3} ports, not every one. "
                   "The whole scoreboard runs by hand (tools/cloud-run exposure) and with each release.")
    out = [{"loader": j["loader"], "from": j["from"], "to": j["to"], "mods": "" if j["all"] else ",".join(sorted(j["mods"])),
            "why": "; ".join(dict.fromkeys(j["why"]))} for j in jobs.values()]
    for j in out:
        why.append(f"{j['loader']} {j['from']} -> {j['to']}{' (' + j['mods'] + ')' if j['mods'] else ''}: {j['why']}")
    if not out:
        why.append("nothing the scoreboard reads changed: no port to check")
    return out, why


def gate(summary):
    """A port check's verdict on one summary: its lines, the mods it freed (slugs, for the game proof) and whether it
    passes, which it does unless a mod that links in main's row stops linking."""
    m = summary.get("againstMain")
    pair = f"{summary['loader']} {summary['from']} -> {summary['to']}"
    if m is None:
        return [f"{pair}: main's table has no row for it, so nothing to compare; {summary['modsThatLink']}/{summary['modsRead']} link"], [], True
    lines = [f"{pair}: {summary['modsThatLink']}/{summary['modsRead']} link" + (f" ({summary['partial']})" if summary.get("partial") else ""),
             f"  newly link: {', '.join(m['newly link']) or 'none'}", f"  no longer link: {', '.join(m['no longer link']) or 'none'}"]
    if m["no longer link"]:
        lines.append(f"FAIL: {len(m['no longer link'])} mods that link on main stop linking")
    return lines, sorted({n.split("--", 1)[0] for n in m["newly link"]}), not m["no longer link"]


def load_gate(summary):
    """A port check's verdict on its game proof (a load receipt): its lines and whether it passes, which it does when
    every mod it started (in its scope) loads on the server, or is client-only there, and then loads in the client. It
    fails only on those: a mod it started that does not load, or a client that could not be run for them. A mod that
    loads alone and fails only next to the others (clash) is said and does not fail it. A mod it could not start (its
    metadata is for another loader, a library it needs is missing) is said with why, apart, and does not fail it either:
    nothing about the port was tried in game for it. With nothing started the check says so and passes."""
    asked = set(summary["scope"]) if isinstance(summary.get("scope"), list) else None
    client = summary.get("client") or {}
    verdicts = {n: v for n, v in summary.get("verdicts", {}).items() if asked is None or n.split("--", 1)[0] in asked}
    clash = set(summary.get("clashOnly", [])) & set(verdicts)
    pair = f"{summary['loader']} {summary['from']} -> {summary['to']}"
    failed = []
    for name, verdict in sorted(verdicts.items()):
        if verdict.startswith("failed"):
            failed.append(f"{name}: server {verdict}"[:500])
            continue
        if not (verdict == "loaded" or verdict.startswith("client-only")):
            continue
        if client.get("notRun"):
            failed.append(f"{name}: the client was not run: {client['notRun']}"[:500])
        elif not str(client.get("verdicts", {}).get(name, "")).startswith("loaded"):
            failed.append(f"{name}: client {client.get('verdicts', {}).get(name, 'gave no verdict')}"[:500])
    unstarted = [f"{name}: {'; '.join(why)}"[:500] for name, why in sorted((summary.get("notStarted") or {}).items())
                 if asked is None or name.split("--", 1)[0] in asked]
    what = ', '.join(sorted(asked)) if asked else 'every mod that links'
    if not summary.get("mods"):
        lines = [f"{pair}: game proof of {what}: nothing it can start, so no game was started and nothing was proven in game"]
    else:
        server_only = sum(1 for v in verdicts.values() if v.startswith("client-only"))
        lines = [f"{pair}: game proof of {what}: started {summary['mods']}: {summary.get('loaded', 0)}/{summary['mods']} load on the server"
                 + (f" ({server_only} client-only, not loaded by a server)" if server_only else "")
                 + f", {client.get('loaded', 0)}/{client.get('mods', 0)} in the client"]
    if clash:
        lines.append(f"  load alone but fail next to the others: {', '.join(sorted(clash))}")
    lines += [f"  {f}" for f in failed]
    if summary.get("time"):
        lines.append(f"  {gameload.time_line(summary['time'])}")
    if unstarted:
        lines.append(f"  not started ({len(unstarted)}, not counted):")
        lines += [f"    {u}" for u in unstarted]
    if failed:
        lines.append(f"FAIL: {len(failed)} of the {summary['mods']} mods the check started do not load in game")
    return lines, not failed

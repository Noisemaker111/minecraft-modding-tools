# Minecraft Modding Tools

Every agent reads this whole file before every reply, so each line costs every task and outlives the change that added it. It was cut from 14 KB to 2.8 KB on 2026-11-10 and has grown since, mostly from PRs adding their own rules. Before you add to it:
- A line belongs here only if an agent would get most tasks wrong without it and can't learn it from the code, a command's `--help` or the doc a line points to. Goals and the reasoning behind them belong in `CONTRIBUTING.md`.
- How something works goes next to the code or in its own doc (`PERFORMANCE.md`, `bench/README.md`); what a change did goes in its PR.
- When agents keep making the same mistake, fix the tool that lets them make it. Add a line only when that isn't possible, and rewrite or delete the line it replaces in the same PR.

`mmt` tests Minecraft mods and plugins, ports mod source between versions, converts between loaders, and diagnoses modpacks. What it does and why: `README.md`, `SCOPE.md`, `CONTRIBUTING.md`. Traps: `KNOWN_ISSUES.md`.

## Commands

JDK 17+ and Python 3.12+; no install step. On Windows use `mmt.cmd` and `python` for `./mmt` and `python3`.

```sh
./mmt help                            # every command
python3 bench/run_tests.py            # the offline checks your change can affect (--all: every one, as CI runs them)
tools/cloud-run packs|ports|convert|load   # sweeps and port game proofs on GitHub's runners
```

Use mmt for builds and game launches, never raw Gradle or `--no-daemon`: launches outside it outlive their command.

## Rules

- **One roadmap step = one branch = one PR** (`CONTRIBUTING.md`, "How the work runs"), using `.github/pull_request_template.md`. A new session or agent on the step pushes to its open PR; a new type of break is a commit, not a PR. Keep the PR a draft while you work: a draft's pushes start no check, so prove each commit here with `python3 bench/run_tests.py`. Marking it ready starts every check once and each later push starts them all again, so merge main in and mark it ready on the commit to merge (more commits coming: `gh pr ready --undo` first), then end the turn, never watching CI: the coordinator merges (`gh pr merge --squash`) when every check is green. A PR's claims (tests pass, numbers) must be ones CI, a linked cloud run or a local in-game run's receipt shows.
- **Prove every change in game before merge, at the smallest real scope.** Ports: measure locally with `bench/exposure.py jars --type/--mods` (it prints what moved against main's row), then mark the PR ready: its Port check (`port-check.yml`, a `Port check:` line in the description names the ports) re-checks those ports against main and fails when a mod that links there stops. A PR never waits on a game start: its check starts none, `tools/cloud-run load -f updates=L:A:B -f mods=SLUG` starts one by hand, and Minecraft is never started on this PC for a port. Other work: `pack-check --focus MODID`, one mod plus its dependencies, a headless server when the metric is server-side; `game-stop` afterwards. Time A/B runs locally and interleaved, never in the cloud (its runners have software graphics). "Not tested in game" is never a PR result.
- **Sweeps go to the cloud** (all packs, the whole port corpus, every convert): push, then `tools/cloud-run packs|ports|convert [-f KEY=VALUE]`; results land in `.runs/cloud/`. The whole jar scoreboard runs only by hand (`tools/cloud-run exposure`, the coordinator) and with each release.
- **A test proves something users depend on,** fails on a wrong answer (vary inputs; change cached inputs mid-run) and runs against the packaged jar. Delete tests that can't fail. A new check is added to `bench/run_tests.py`, nowhere else.
- **Never search the whole disk.** Gradle's jars are under `~/.gradle/caches` (`modules-2/files-2.1/GROUP/NAME`, Loom's game jars in `fabric-loom/`); ours are in `~/.mmt`.
- Scratch, logs and clones go in ignored `.runs/`. Third-party files (game jars, mappings, mods) are downloaded at run time, never committed.

## Where things are

- Where each kind of thing goes, and what a new one needs: `ARCHITECTURE.md`. One file per kind of thing; a file is found by its folder, so no list is edited to add one. CLI: `tools/cli/Mmt.java` (entry and help), one class per command.
- Game probes: `tools/{agent,datacheck,mixincheck,nogame,packcheck,plugincheck,servercheck}/`. Modpacks: `tools/cli/Pack*.java` (`tools/packcheck/CONTRIBUTING.md`).
- Port rules: `knowledge/fixes.json`, each with a `test {error,before,after}` (`python3 bench/rule_test.py --help`); the game's change map `coverage/changes/`.
- Perf: `perf`, `perf-optimize` (`PERFORMANCE.md`); kept runs `coverage/perf-runs.json`, patterns `knowledge/perf-patterns.json`, each citing a kept run.
- Receipts: `coverage/{ledger,measures}.json` (`bench/README.md`). Desktop app: `app/` (Rust on Rune, `cargo test`). The website, `ROADMAP.md` and main's jar scoreboard are on the `generated` branch, never main (`bench/generated.py`).
- CI: `.github/workflows/portable.yml` (required: linux, windows), `integration.yml`, `app.yml`, `port-check.yml`: on a ready PR, never again on main. `cloud-*.yml` are started by `tools/cloud-run`.

## Gotchas

- Builds, MockBukkit and loading prove no behaviour: assert mod state in a real client, or a plugin on real Paper.
- Keep JBR's `-XX:TieredStopAtLevel=1` (C2 with hot redefinition crashes). Registry and mixin edits need a restart.
- The pack probe is written in Mojang names; each ART pass needs `--lib` in its input's names.
- Java: one statement per line. The older PowerShell and `Lab*.java` lanes are kept; don't extend them.

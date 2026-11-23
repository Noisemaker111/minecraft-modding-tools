# Where things live

One page: where each kind of thing goes, so two changes that add two different things edit two different files. It
was written when six files held most of the product (JarPort.java was 12,554 lines and took 45 commits in a month)
and every pull request collided in them. The rule behind the layout is to split by what changes together, never by
size: there is no line limit anywhere.

## Adding something

| You are adding | It goes in | Nothing else to edit, because |
| --- | --- | --- |
| An answer to a kind of break in a mod's call sites (a moved call, a new parameter, a retyped value) | its own file in `tools/port/jar/sites/`, tried from `Sites.call` | the porter compiles every file under `tools/port` |
| Another kind of break the jar porter answers (overrides, mixins, metadata, access files) | the file for that kind in `tools/port/jar/`, or a new one beside it | same |
| A way the learner learns a replacement from the game's own code | its own `Learn*.java` in `tools/port/learn/`, called from `GameLearner.learn` | same |
| A way two releases are linked (classes, members, rewrites) | the `Hop*.java` for it in `tools/port/learn/` | same |
| A stand-in for a game class an update rewrote | `knowledge/standins/UPDATE/…/Class.java` | the porter reads the folder for the update it crosses |
| A built-in source migration for one game change | its own file in `tools/cli/port/migrations/` | `tools/cli` is compiled with its subfolders |
| Something `mmt port` does to source (mixins, access, metadata, maps, undo) | the `Port*.java` for it in `tools/cli/port/` | same |
| Something `mmt convert` does | `tools/cli/convert/`: `ToNeoForge`, `ToFabric`, the two build-script files, the two bridges | same |
| A build system or loader a port retargets | the `Target*.java` for it in `tools/cli/target/` | same |
| A game probe or a version layer of one | its folder under `tools/` (`packcheck/game/VERSION/`, `mixincheck/`, …) | the package ships those folders whole |
| A self-test suite | a class in `tools/tests/` and one line in `Installation.SUITES` | the package ships each suite and every file it names |
| A porter test | `tools/tests/JarPort*Test.java` (or `Game*`, `ModNames*`, `SourceAnswers*`) | `bench/run_tests.py` finds them by name |
| A scoreboard number or table | the module for it in `bench/portboard/` (`board`, `anyversion`, `gameload`, …) | `bench/exposure.py` holds only the commands |
| An offline check | `bench/test_*.py`, or an entry in `bench/run_tests.py` | the python check runs every `bench/**/test_*.py` |

## The porter: `tools/port`

Everything under `tools/port` is one program, compiled together. Two functions list it, and nothing else may:
`port_tools.sources()` (bench/port_tools.py) for the checks and the scoreboard, `JavaTools.portSources` for mmt.

- `names/` what the game called things in each release: `VersionMap` (mapping tables), `MapChain`, `ModNames`, `ChangeIndex`.
- `learn/` what the game's own code did between two releases: `GameDiff` reads the jars, `GameHop` links one update, `GameLearner` learns replacements.
- `jar/` a compiled mod moved to another release: `JarPort` holds the port's state and its order of work; each kind of break is a class beside it that reaches the port through its `port` field. `sites/` is the same one level down, for `Sites`.
- `source/` a mod's source moved with the same answers: `SourceAnswers`, and the older fixer driven by compiler errors (`MapFix`, `ApiFix`).
- `Exposure.java`, `FixExpr.java` are read by all of them.

`names` and `learn` do not depend on how a jar is ported: a route map is cached by their content alone, so a change
under `jar/` never rebuilds one.

A big class is split as a host and its parts: the host keeps the state and the order things happen in, a part is one
kind of thing (`final class MixinPoints { final JarPort port; … }`), and the host holds it as a field
(`mixinPoints`). A part calls the host's members through `port.` and another part through `port.otherPart.`.

## What keeps it this way

`python bench/run_tests.py` (what CI runs) fails with what to do instead when:

- code is copied: `bench/copies.py` runs jscpd and fails on a file with more copied lines than `bench/copies.json` records. Merging a copy lowers the record.
- a second list of the porter's sources appears: `bench/test_architecture.py`.
- a porter source would compile here and not in the shipped package: `CheckoutDataTest`.
- a moved name is used the old way: the compiler.

Nothing limits how long a file may be. A file that every change edits is one that holds a list or several kinds of
thing: give the list a folder to read, or move each kind to its own file, in the pull request that noticed.

Not merged, on purpose: the version layers under `tools/packcheck/game` and `tools/packcheck/fingerprint` are whole
files that differ in a few lines, because each is compiled alone inside one generation of the game; and the
`bench/**/*.java` scripts are handed to a running game one file at a time.

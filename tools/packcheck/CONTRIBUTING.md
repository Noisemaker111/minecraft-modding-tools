# Extending pack-check

`pack-check` runs a modpack on its own loader's real server. A new loader, input, check or repair goes into one
registry, not the core (`tools/cli/PackCheck.java`); `mmt pack-check --list` prints them. Copy an existing entry.

| Add | Where | Test it in |
|---|---|---|
| Pack input (export, `.mrpack`, instance, folder) | `PackInputs.ALL`, before any shape it overlaps | `PackMetadataTests.inputs` |
| Loader | `knowledge/pack-loaders.json` (`style`, `aliases`, installer, `metadata`, `failed`, `clientOnly`); a new style is a `Style` in `PackLoaders.java` | `PackServerTests.loaders` |
| Jar metadata | `PackMods.read` | a jar layout in `PackMetadataTests` |
| Check | `new Check(id, stage, what, body)` in `PackChecks.ALL`; `problem` only for what the game showed, `warning` for a metadata prediction, `note` never fails | a real log or jar layout in `PackLogTests` |
| Repair | `knowledge/pack-fixes.json` (`match`, `action`, `check`, `evidence`, `test`), only after a real run proved it; new matchers/actions in `PackRepairs.java` | its own `test`, run by `self-test` |
| Game version whose API moved | a layer `tools/packcheck/game/VERSION/MmtPackGame.java`, listed in `Installation.RESOURCES` | `probe-compile` |

`start-check` (`tools/cli/StartCheck.java`) reads and installs a pack the same way and decides with no world or window
whether its jars start: the loader's own launch in one cold JVM a side, under the agent `tools/nogame/MmtNoGame.java`. A
loader takes part through four more keys of its entry in `knowledge/pack-loaders.json`: `firstStart` (the files its
first start writes into a new folder, made once per installed loader and linked into each run), `startsModsBeforeWindow`
(true when its client runs the mods' start code before the game asks for a window), `ownWindow` (the setting that
turns off a window the loader opens itself) and `refusedInLog` (what its resolver logs when it refuses jars and its
client still goes on to the window to show them). Its tests: `StartCheckTest` and `bench/test_start_check.py`.

Every start-check start runs behind a wall the operating system puts up (`tools/cli/StartWall.java`), because JVM
proxy settings stop only the classes that read them. On Windows that is an AppContainer with no capability on a desktop
of its own, started by `tools/nogame/MmtWall.java` on a Java 22 or later; the container reads the Java it runs on, the
`loaders`, `minecraft`, `assets` and `agents` folders of the cache and the start's own folder, and nothing else. Two
things about Windows shaped it: Smart App Control blocks a program compiled on the machine, so the launcher is Java,
not a small `.exe`; and no container may read `C:\` or `C:\Users`, which Java's `Path.toRealPath` (every loader) and
`File.createTempFile` (the game's level save, JNA) need, so those two lookups are answered by `MmtListed` and
`MmtFileSystem` under `tools/nogame`, put into `java.base` for walled starts only (`MmtPathPatch`). A grant on
a folder walks everything under it, so only folders a start is made from are ever granted. On Linux the wall is a
network namespace with no interface (`unshare`) where a user may make one; elsewhere the answer says there is none.

The probe (`tools/packcheck/MmtPackProbe.java`) is loader-neutral, written in Mojang names, and reaches the
loader only by reflection; keep loader code out of it and its game layers.

Offline: `python bench/run_tests.py --only self-test`. In game, locally, before merge: `pack-check --pack PACK --focus
MODID [--fix]` starts one mod and its required mods on the real server (a repair is proven when the repaired server
makes a world, reopens it and has no problem the shipped one lacks). Every pack at once: `tools/cloud-run packs`.

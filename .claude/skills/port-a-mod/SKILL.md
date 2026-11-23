---
name: port-a-mod
description: Make mmt port carry any mod across Minecraft versions with no hand edits, by teaching the porter how to handle a type of change in the game, so the mod in front of you and every other mod with that type is fixed, rather than fixing one mod's errors or one game member at a time. Use when Jon names a mod and a target version, says "next mod", "bigger mod", "next version", "keep going" on porting, or asks to push the porting map further.
---

You never work on "this mod's errors". You teach the porter a type of change in the game (a method that took another
parameter, a mixin target with a new signature, an abstract method added), and every mod with that type is fixed with
it, in every update. One changed part of the game as a rule comes second, for what no type covers. Read this whole file before the first command: it is short, and each part was measured.

## Where porting stands (2026-11-20)

- A mod comes in as source or as a `.jar`. `mmt port --jar MOD.jar --to 1.21.2` (tools/JarPort.java) moves a compiled
  NeoForge or Fabric mod with no source and no build, and names every reference that does not link instead of guessing.
  A Fabric jar is read in Mojang's names and handed back in the target release's intermediary (`ModNames.Back`).
- **The number to move:** `tools/cloud-run exposure` (`python bench/exposure.py all` runs the same here) ports the
  150 most-downloaded mods of each release and links them against the target release and its loader (NeoForge with FML
  and its event bus; Fabric Loader and Fabric API). In main's table (`python bench/generated.py show scoreboard/all.json`) it counts, apart: the **jump to the
  newest game** from the most-used release (Fabric 1.20.1, NeoForge 1.21.1 to 26.3: **11 of 294 link**,
  `port.jar-links.newest`), which is what people ask for; the **35 big updates**, each into a new family of releases
  (**1,861 of 5,149**, `port.jar-links.big-updates`); the 21 jumps from each family's most-used release to the next
  family's; and the 29 small updates, across which 93% link untouched. All updates together was the old headline (62%,
  `port.jar-links.every-update`) and flattered the porter. Run `tools/cloud-run exposure` on your PR's last commit
  and put what it prints against main in the PR. It runs only your branch: main's table for your base was made when
  that commit reached main, and is found (or made once, for every PR on that base) without you running main. Never
  add the `port.jar-links` points: main's sweep writes them and its table to the `generated` branch after the merge.
- One update or one jump: `python bench/exposure.py jars --loader neoforge|fabric --from 1.21.1 --to 1.21.2` (a
  minute or two the first time, seconds when nothing it reads changed; `--to 1.21.4` or `--to 26.3` is a jump). It prints **the mods nearest to done**, each with the
  references it still needs and what the game's own code did about each one: that is the to-do list. Fabric jars are
  read in Mojang's names through intermediary first (tools/ModNames.java).
- `tools/cloud-run load -f updates=neoforge:1.21.1:1.21.2` is the proof (`python bench/exposure.py load` on GitHub's
  runners, one job per update; `-f mods=SLUG,...` for a few mods): the mods `jars` says link, with the libraries they
  require, are ported and started on a real 1.21.2 server and then in a real 1.21.2 client (`mmt pack-check --side
  client`), each mod's two verdicts side by side, in `.runs/cloud/load-RUNID/`, with what moved against the receipt main
  keeps for the update in `compare.md`. Server: 23 of 25 load (one client-only, one with no code fails on its data). Client: 23 of 24 load; Puzzle loads
  on the server and links but fails in the client, because its injection point (a call 1.21.2's loading overlay no
  longer makes) is inside a method that still exists. The client covers the title screen, a world, the inventory and
  every creative tab, not a mod's own screens.
  On Fabric (`--loader fabric`) Fabric API is in the pack, and the client is Fabric's own (intermediary, read by mmt's
  agent through Mojang's and Fabric's mappings): 13 link; server 7 of 7 that a server runs, client 12 of 13 (Better Mount HUD:
  its @ModifyVariable finds no target in 1.21.2's HUD code, step 55).
- `jars` also checks each mod's data files for built-in game ids the new release dropped (`modsThatLinkAndData`).
- 16 of the 126 corpus ports build (`python bench/measures.py table port.corpus-builds`). 23 of the 126 are mods
  whose untouched original does not build on the runner, and 72 cross many updates at once, so this number only
  moves after single updates work. Use it as the proof at the end of a PR, never to choose what to do next.

## What was tried, with its result. Don't repeat these

| Tried | Result |
|---|---|
| A rule for each compiler error of the mod in front of you (Nov 14 to 17, 355 rules) | 10 to 15 ports in a day; a rule freed 0 to 2 ports |
| Building from the loader's starter project instead of the mod's build script (Nov 18, PR #109, closed) | 16 to 14 ports: it freed none |
| Choosing work from `exposure.py run` (Nov 18) | 58 cleared; 6 of its 35 standalone mods fail on a real server, on mixins and field types it cannot see |
| A rule with `inClass` for each top-ranked changed member of 1.21.2 (Nov 18, PR #118, 57 replacements) | 31 to 41 of 150 link. The rest are held back by 1.21.2's rendering and recipe rewrites (render states, toasts, recipe book), which one-member replacements cannot express; fixing every mixin signature change would add about 4 |
| JarPort fixes that need nothing learned, every update (Nov 18, step 47b): mixin @Inject moved to a target's new signature, override bridges, access transformers opened or cleaned | 41 to 43 of 150 link on 1.21.2, 33 to 36 on 1.21.5. What is left is learned replacements and redesigned APIs; the other mixin signature changes capture locals, wrap the changed call, or inject where the new method no longer has the point |
| Choosing work from `mmt port-plan`'s list (Nov 18) | of the 21 ports it said had nothing left, 7 built. It reads NeoForge mods (109 changed game parts a port) but almost nothing in Fabric mods (5 a port) |
| GameDiff's Learner: what the game's new code computes at each use of a changed member, as an `inClass` expression (Nov 19, PR #121) | 1,413 -> 2,462 of 49,503 gone members get a replacement over all 43 updates; 43 -> 45 of 150 link. What is left is mostly redesigns, mixins, overrides and value-to-value changes (`false` -> `TriState.DEFAULT`) an expression cannot say |
| Members the route map follows that still break, learned too: a closed member from its exact public wrapper, a closed field from its accessor, moved fields, generic results; readings seen only with literal arguments refused (Nov 19, PR #126) | every update 5,302 -> 5,365 of 9,149, none lower. Overrides with retyped parameters freed 1-2 mods per update when measured, so they wait for passing the new argument into the old method. The link check does not read NeoForge's loader (FML) classes: 2 mods that link failed on `ModLoadingContext.registerConfig` |
| Calls the game removed learned as dropped (uses vanish, or the statement is only the receiver chain), and replaced classes as a class map from the types the game's own members and values switched to (Nov 20, PR #128) | every update 5,826 -> 5,854 of 9,423, none lower; Fabric 1.18.2 -> 1.19 34 -> 49 (TextComponent). Most mods that drop endVertex() still don't link on 1.21: getBuilder() + begin() became one call, which no one-member replacement says. JOML (1.19.3) is a library the snapshot doesn't read |
| Taking the reference the most mods share first, which every row above did (measured Nov 20 on NeoForge 1.21.1 -> 1.21.2) | a mod held back has 15 references at the median, and 1,241 of the 2,060 are used by one mod each: the 100 most shared freed 2 mods, the 500 most shared 16. The mods nearest to done first: 15 mods are one reference away, 26 are done after 40 fixes. The first one taken that way (ServerPlayer.teleportTo) freed Chunky, which loads on a real 1.21.2 server |
| Linking against the loader too, reading NeoForge 1.20.2-1.20.4, and counting mods whose code the port never reads as not read (Nov 20, PR #127) | every update 5,775 -> 5,586 on the same 61 updates (loader breaks -28 net; hidden code -165), 5,826 of 9,423 over 64. The compasses' FML break is now porting work; loader breaks are ranked with game ones |
| Overrides whose parameters changed type moved whole to the new signature, each old parameter worked out from the new ones as the game's own overrides do (graphics.pose(), event.key()), and calls in the body given the new arguments; every injector's @At checked against the new method, a gone point a break only where require says so (Nov 20, steps 55 and 59) | every update 5,854 -> 5,838 of 9,423: +1 from moved overrides, -17 real breaks (required injection points gone; Better Mount HUD crashes on one in a real client). The screen family stays blocked by calls whose receiver changed (Font.draw(pose, ...) -> graphics.drawString(font, ...)), which need the new parameter as an operand of a learned replacement |
| Calls on a moved screen's parameter rewritten as the game's own new bodies compute them (GameDiff.Retyped: font.draw(pose, ...) -> graphics.drawString(font, ..., false)); the mod's helpers and mixin handlers move with the body; a mixin casting its this to a type its target stopped being is a break (Nov 20, step 61, PR #139) | every update 5,846 -> 5,846 of 9,423: Fabric 1.19.4 -> 1.20 42 -> 44, FerriteCore -1 on both 1.20.6 -> 1.21 (a real break). Most 1.20 screen mods still break outside moved bodies: classes extending the removed GuiComponent, Fabric API render callbacks, lambdas |
| A removed superclass replaced by what the game's own subclasses extend now; lambdas move with a captured parameter; other injectors follow a retyped target when they capture nothing that changed; closed fields in learned rewrites opened through the access widener (Nov 20, step 63, PR #143) | every update 5,865 -> 5,883, big updates 1,878 -> 1,896, newest 11 -> 15, none lower. Each 1.20 screen mod left needs its own piece: HUDs building their own PoseStack, mod-own render APIs, Fabric API render callbacks |
| The type `call, game: linked` handled as a type (Nov 21, step 65, PR #147): each new parameter read from where the old and new bodies line up (an old operand, a fixed value, a fixed value solved through a sum, a value of the receiver narrowed to the new type, a callee that changed the same way), converted through the new type's constructor or one factory; an unread parameter gets zero only where nothing overrides the method | the type finishes 186 -> 177 and is in 1,984 -> 1,960 mods; every update 5,884 -> 5,893, big updates 1,896 -> 1,904, newest 15 -> 15, none lower. What it can't do: a value the old body never had (CycleButton.builder's initial value, 1.21.9's permission levels), a redesigned type (String -> ResourceKey), and any method an override may change, where a base body proves nothing |
| The type `abstract method not written, game: linked` (Nov 21, step 66, PR #146): most of it was breaks the porter made up or that were always there. Intermediary ids given to two methods named by descriptor; classes a jar carries for another loader left out; methods the old class also left unwritten (loader-patched defaults) not counted; callback lambdas moved to the new signature, or reported instead of linking silently | the type's finishes 102 -> 12, mods 316 -> 100; every update 5,893 -> 5,972, big updates 1,904 -> 1,910, newest 15. Lambdas whose new argument has no conversion (DeltaTracker for a float, a Codec now a MapCodec) stay reported |
| The type `call, game: split` handled by what lines up at each use (Nov 21, step 67, PR #149): a use making the same call with the same arguments on a value only its own method has counts for the reading the other uses show; uses split by what they pass take the reading of the uses whose arguments are call or field results | the type finishes 80 -> 73, in 1,555 -> 1,518 mods; every update 5,972 -> 5,979, big updates 1,910 -> 1,917, newest 15 -> 15, none lower; 1.21.8 -> 1.21.9 Fabric 35 -> 39, NeoForge 35 -> 38. `call, game: linked` does not fall to it. Loader-event redesigns that need an id per listener stay split |
| The type `mixin target changed signature, game: linked` (Nov 21, step 69, PR #150), designed from -Djarport.why's reasons: @At at a replaced call follows the replacement, MixinExtras sugar follows when each @Local's parameter is still there, primitive and constant-argument conversions (1.21's tick delta), mixins into game lambdas handed back by intermediary name and read from the game's classes, nested platform modules not linked | finishes 55 -> 51, mods 872 -> 853; every update 6,045 -> 6,051, big updates 1,925 -> 1,931. Most of the type left captures locals, wraps calls whose arguments changed, or names a conversion the game shows two ways (getGameTimeDeltaPartialTick(true) and (false)) |
| A value the new release takes where an object is made, which old code set later on the same object (Nov 21, step 70, PR #155): learned from how the game builds the same object; JarPort finds the setter by data flow through calls that give the object back and moves its value into the factory call when it can be read there | newest 16 -> 17, every update 6,051 -> 6,056, big updates 1,931 -> 1,936, none lower; mods with these calls unlinked on NeoForge 1.21.10 -> 1.21.11 8 -> 3. A setter on an object kept in a local, or one whose value is made right there, is not moved |
| The type `call, game: unused` (Nov 21, step 72): a dropped method no game code called. Retargeted where the game's and loader's own files say it went: a static method exactly one class of the project gained under its name and signature, or the one method an old alias forwarded to | type finishes 46 -> 24, mods 957 -> 923; every update 6,056 -> 6,082, big updates 1,936 -> 1,943, newest 17, none lower (NeoForge 1.21.7's PacketDistributor.sendToServer alone was 19 mods). Renamed-and-moved helpers and APIs removed outright have no answer in the files |
| The type `mixin field gone, game: linked` (Nov 21, step 73, PR #151): most of it was never a break. Every mixin field was checked as a @Shadow (a mixin's own LOGGER or cloudRenderer was reported), and shadows of loader-patched fields never matched vanilla in either release. Only a @Shadow the update broke counts now, ported as the target's field (the learner's answer, or a field report with its evidence) | type finishes 68 -> 0, mods 328 -> 0; every update 5,979 -> 6,044, big updates 1,917 -> 1,924, newest 15, none lower. The real ones are mostly redesigned private fields the learner answers about 2% of. 'game: linked' on a type can mean no evidence at all: check a type's examples against both game versions before designing |
| Game fields the update retyped (Nov 21, step 74, PR #157): a read as the new type where every use of the value means the same of it (ReadUses follows the value on the verifier's stack), else through what the game's code reads from a holder of the new type (GameCode.unwraps) | on #156: finishes 19 -> 13, mods 536 -> 392; every update 6,118 -> 6,124, newest 17 -> 18. Left: values stored in locals or compared with ==, writes, and types the game changed in meaning (DimensionType -> ResourceKey) |
| A removed method with nothing of the game's code to read, and a removed member the game's own uses passed narrower values to (Nov 21, step 75, PR #160): answered from the new release's own types (the one getter whose object answers it, its argument made by the only constructor or plain-value factory at each step), and read only where a reading compiles for the declared type | newest 18 -> 19 (Coroutil), every update 6,124 -> 6,134, big updates 1,958 -> 1,968, none lower; 'call, game: template' finishes 32 -> 28. First tries learned wrong lines (RenderType.endPortal(int) as an int's RenderType, kill() as discard(), a fixed enum constant for a many-argument member): factories only from plain values and only a class's one, receivers never counted as narrower, one-argument members only. Diff the learned lines against main's learner on several releases before sweeping |
| Abstract methods the update added (Nov 21, step 76, PR #161): an override moves where the game's own subclasses moved theirs (JarPort.takeover: they stopped writing the old method, which is gone or final, and their new bodies are mostly the old), through override()'s rename or bridge; a parameter the object now answers is read from this (Bridge THIS); an override of a method the update made final is reported (finals) | finishes 39 -> 29, mods 750 -> 700; every update 6,134 -> 6,131 (9 freed, 12 false links caught), newest 19 -> 20. Left: overrides calling the now-final method back (1.21.11's buttons calling super.renderWidget, split into renderDefaultSprite and renderDefaultLabel), and new abstract methods with no old one behind them |
| A call whose constant picks its replacement, and a moved method reached through what holds it (Nov 21, step 77, PR #162): per-constant readings from the game's uses or the class the enum builds the constant with; JarPort reads the constant at each call; a method moved to another class goes through the old class's one holder field or getter, never on the old receiver | newest 20 -> 21 (e4mc), every update 6,131 -> 6,132, big updates 1,965 -> 1,966; 5 named false links now reported. First tries: the holder pass asked Entity.getAllSlots() of getControllingPassenger() (a move down to a subclass, now refused); the receiver guard keyed on the call's class dropped mods calling withStyle on 1.18's TextComponent (now either class); a null placeholder fired on classes a mod ships itself. A new of an interface links but throws in game: check the verifier's view, not only the links |
| Overrides of methods the update made final (Nov 21, step 78, PR #163): moved to the hook the final method calls (JarPort.finalHook, moveToHook), the super call to the hook above, the class's own renamed hook, or a copy of the parts the game split it into (defaultBody, copyBody) | finishes 11 -> 0, mods 131 -> 35; every update 6,132 -> 6,145. Left: final methods with no hook (1.21's Model.renderToBuffer) |
| Readings lost to uses that said nothing about them (Nov 21, step 79, PR #166): unread uses of another value, the same call with a fixed default, an insisted value stand aside; a varied literal the old body only tests may drop; a late chain needs a removed setter; a plain result wrapped in a record reads back through the part its factory fills | newest 21 -> 22 (veinminer, a false link: Kotlin for Forge's language loader does not register on 26.3), every update 6,145 -> 6,146, big updates 1,979 -> 1,980; 1.21.5's NBT getters learned. First tries let abstentions carry wrong readings (CauldronInteraction.LAVA as EMPTY.map(), permute(axis) as axis, setUniform for bindTexture): abstentions now need three agreeing uses, every operand kept and a compiling reading. learn_diff caught each one |

What they share: the work was chosen from a list nobody had checked against real builds. **A list may choose the
work only after it predicts the result:** the mods it clears build, and the ones it doesn't have errors where it says.

## What comes first: jobs on the tool

Step 50 is done: a `.jar` is ported by changing its classes. Step 44 is done: one scoreboard, read from jars, for every
update and both loaders. What remains on the tool (`coverage/roadmap/goal-2/`) needs someone who can change
`tools/JarPort.java` safely. If that is not you, say so to Jon and stop; adding text rules instead is the mistake in
the first row above.

1. **Step 47 (doing): a fix names a game part and is applied in the class.** What the game's own change map knows
   (calls switched, added arguments, chains, narrower fields) and every rule's `inClass` (a Java expression for the
   old member, compiled by JarPort against the new release, see `tools/FixExpr.java`) are applied in classes. Still to
   do: the source port applies the same `inClass` where the compiler resolved the member, instead of a regex.
2. **Step 52: `mmt port --jar` ports Fabric mods too.** The scoreboard reads them; the port must hand them back in
   the names Fabric runs.

## The loop. One type of break per turn

1. `python bench/exposure.py jars --loader neoforge|fabric --from A --to B`. It downloads the pinned mods
   the first time and prints two lists: the **types of break** (what a reference is, and what the game's own code did
   about it: `call, game: linked` is a call whose method is still there with other parameters), each with the mods it
   alone holds back; and the mods nearest to done with what each still needs. Over every update the same two lists
   are `types` and `nearest` in main's table (`python bench/generated.py show scoreboard/all.json`).
2. Take the type that finishes the most mods, and design the way the porter handles that type: in the learner
   (tools/GameDiff.java) when the game's own code shows the answer, in tools/JarPort.java when the answer is in how the
   mod's class is written. Use the nearest-to-done mods that have the type as your cases: the fix is right when it
   finishes them and the other mods with that type, in this update and the others. Before designing, see why the
   porter gave no answer for three of the type's references: run GameDiff with `-Dgamediff.learnlog=PREFIX` and read
   each member's line (game uses lined up, uses read, votes, readings).
   A rule in `knowledge/fixes.json` is for what no type covers: one game part with one answer that you can read in
   both versions of the game class. It frees the mods that use that part and nothing else, so it comes second. Skip a
   `redesigned` or `unused` reference unless you can say what replaces it. A wrong answer is worse than none.
3. For a rule: write it in `knowledge/fixes.json` with `members` naming that game part, then
   `python bench/rule_test.py --only ID --write`. Copy the nearest rule that has `members`; never name a mod.
4. Run step 1 again, or with `--type "TYPE"` to port only the mods that type held back (seconds). It prints which mods
   newly link, stopped linking or changed their port against the run before your edit: the type's mods must have moved
   and none may stop linking. If nothing moved, the change does not do what you think: fix it or take it out. For a learner change, also run
   `python bench/learn_diff.py --loader neoforge|fabric` and read every reading that is new, gone or changed against
   main before the sweep: a wrong reading that links is invisible to the count (kill() learned as discard()).
5. Push, then `tools/cloud-run load -f updates=LOADER:A:B -f mods=...`, with the mods step 4 printed as moved, starts
   them on a real server and in a real client, with the library mods they require (each one's release for B from Modrinth, or its A release ported with
   them); no mod that links may fail, and `compare.md` must show no mod that loaded on main failing now. To keep the
   receipt, copy its `load-LOADER-A-B-COMMIT.json` into `bench/exposure/`. `python bench/port_board.py --changed` builds
   the pinned source ports your rule crosses. Commit.

Stop and tell Jon when: three parts in a row each freed no mod (the list has reached parts that need the game class
read in both versions; say which); a rule needs to name a mod; or the count of mods with nothing left drops.

## Rules that don't bend

- Sources are the game's and the loaders' own files (jars, mappings, starter projects), and for stand-ins
  (`knowledge/standins/README.md`) the loaders' published migration notes too (NeoForge's primers, Fabric's update
  posts; Jon, 2026-11-21), quoted in the stand-in and checked against both releases' code. Never how another mod was
  ported. Mod jars are only counted.
- No AI call inside the tool. What you work out is saved as a rule with a test.
- Prove in game before the PR ends, on GitHub's runners and never by starting Minecraft here: many agents share one PC.
  `tools/cloud-run load` for the updates you changed (step 5); the whole corpus once, on the PR's final commit:
  `tools/cloud-run ports`; then hand back, never watching CI. Locally: edit, compile and the offline checks (`exposure.py jars`, `python bench/run_tests.py`).
- Each type of break is a commit on the step's open PR, not a new step or PR (`CONTRIBUTING.md`, "How the work
  runs"). End the session by writing the state back in the step's own file (`coverage/roadmap/goal-2/NNN-ID.json`):
  its result says what the method freed and what it left, which is the table above from step 79 on (`ROADMAP.md` on the
  `generated` branch shows every step). This file changes only when the method does; the table above takes no new rows.

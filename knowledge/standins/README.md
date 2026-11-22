# Stand-ins

A stand-in is 1.21.1's API (or any old release's) written on top of the next release, for an old member the game's own
code has no answer for. `tools/JarPort.java` (`standins`) reads the ones for the update it ports across, compiles them
against the new release, turns them into port rules, and carries the ones a mod uses inside the ported jar under a package
of its own (`mmt.standin.mID`). The next update ports them like the rest of the mod's code.

- **Where:** `FROM-TO/` + the old class's path, e.g. `1.21.1-1.21.2/net/minecraft/world/level/Level.java`, package
  `mmt.standin.` + the old class's package, class named as the old class (`ReloadableServerRegistries$Holder` for a
  nested one). One file per old class, never per mod. A stand-in that uses a loader's own classes goes in
  `FROM-TO.LOADER/` (`1.21.1-1.21.2.neoforge/`), read only for that loader; `FROM-TO/` is read for every loader.
- **What answers what** (public static methods; types in the new release's names):
  - `R name(Owner self, A...)` the old instance method `Owner.name(A...)R`; `T name(Owner self)` and `void name(Owner self, T)` read and write the old field `name`
  - `R name(A...)` the old static method (or static field)
  - `Owner init(A...)` the old constructor `new Owner(A...)` (not a `super(...)` call)
  - `boolean isInstance(Object)` what `x instanceof Owner` meant
  - A stand-in for a class the new release dropped, with nothing the route or a rule moves it to, stands in for the class.
  A method that answers no old member stops the port with its name.
- **Closed members:** a stand-in may call what the new game keeps private or protected; the port opens it through the
  mod's access transformer, or reports it when the mod has none.
- **Sources:** the two releases' own code (decompiled game and loader jars) and the loaders' published migration notes
  (NeoForge's primers, Fabric's update posts). Each file says what it read in both releases and quotes the note it agrees
  with. Never another mod's port.
- **Compile against:** stubs the port makes from its snapshot of the new release (declarations, no generic signatures),
  so write raw types where the game uses generics (`for (Object o : self.slots)`).
- **Check:** `python bench/exposure.py jars --loader neoforge --from FROM --to TO` (the mods it frees link), then
  `python bench/exposure.py load ...` (they start on a real server).

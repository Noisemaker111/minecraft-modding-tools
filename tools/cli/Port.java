import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.*;

/** Bounded repairs use the existing typed mapper and rule owner, with byte-exact guarded undo. */
final class Port {

    final PortJournal journal = new PortJournal(this);
    final PortMaps maps = new PortMaps(this);
    final PortHierarchy hierarchy = new PortHierarchy(this);
    final PortCarriedClasses carriedClasses = new PortCarriedClasses(this);
    final PortAnswers answers = new PortAnswers(this);
    final PortMixins sourceMixins = new PortMixins(this);
    final PortAccess access = new PortAccess(this);
    final PortMetadata metadata = new PortMetadata(this);
    final PortRules rules = new PortRules(this);
    final PayloadMigration payloadMigration = new PayloadMigration(this);
    final NeoConfigMigration neoConfigMigration = new NeoConfigMigration(this);
    final NeoEntryMigration neoEntryMigration = new NeoEntryMigration(this);
    final PortExplain explainer = new PortExplain(this);
    final PortDesugar desugarer = new PortDesugar(this);

    final Build build;
    Map<String, Object> model;
    String targetVersion = "";
    /** The hub route this port walks (null for the one-jump path), its source namespace, and the rules that changed a file. */
    Route route;
    String namespace = "mojmap";
    final Map<String, List<String>> rulesApplied = new LinkedHashMap<>();

    Port(Build build, Map<String, Object> model) {
        this.build = build;
        this.model = model;
    }

    /**
     * A build log as text: javac on Windows writes in the console's code page, so bytes that are not UTF-8 never stop a
     * port, and ends lines with \r\n, which become \n: a rule's match spans lines with \n.
     */
    static String logText(Path log) throws IOException {
        // javac calls a missing class a variable when the code names it as a qualifier (BlockEntityType.Builder.of(..)):
        // a capitalised name with lower case in it is a class by Java's naming, so rules written for "class X" match it.
        return new String(Files.readAllBytes(log), StandardCharsets.UTF_8).replace("\r\n", "\n")
            .replaceAll("(symbol:\\s+)variable ([A-Z][a-z][\\w$]*)", "$1class $2");
    }

    static List<Path> sources(ProjectModel project) throws IOException {
        List<Path> paths = new ArrayList<>(project.inputs());
        Files.walkFileTree(
            project.root,
            new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    return (
                            !dir.equals(project.root) &&
                            (ProjectModel.IGNORED.contains(dir.getFileName().toString()) ||
                                Files.exists(dir.resolve(".git")) ||
                                ProjectModel.separateBuild(dir))
                        )
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (
                        attributes.isRegularFile() &&
                        (file.toString().endsWith(".java") || file.toString().endsWith(".kt"))
                    ) paths.add(file);
                    return FileVisitResult.CONTINUE;
                }
            }
        );
        return new TreeSet<>(paths).stream().toList();
    }

    Map<String, Object> run(Mmt.Options options) throws Exception {
        if (options.values.containsKey("undo")) return journal.undo(options.get("undo", ""));
        String pending = PortJournal.interruptedText(build.project);
        if (pending != null) throw new IOException(
            pending +
            "\nUndo it before porting again, so the next port starts from the original files: mmt port --root \"" +
            build.project.root +
            "\" --undo"
        );
        String from = options.get("from", ""), to = options.get("to", "");
        if (from.isBlank() || to.isBlank()) throw new IllegalArgumentException(
            "Port needs --from <version> --to <version>; --fix selects supported target dependencies and repairs sources with guarded undo."
        );
        if (
            !from.matches("[A-Za-z0-9._+\\-]+") || !to.matches("[A-Za-z0-9._+\\-]+")
        ) throw new IllegalArgumentException("Invalid Minecraft version");
        targetVersion = to;
        rules.learnFromMods = options.has("learn-from-mods");
        Map<String, Object> refusal = Route.blocked(build.harness, from, to);
        if (refusal != null) return refusal;
        // --check fingerprint: what the mod does on its own version, recorded before any file changes (FINGERPRINT.md).
        if ("fingerprint".equals(options.get("check", "")) && !options.has("dry-run")) behaviourBefore = behaviour(from, "fingerprint-before");
        // --direct keeps the one-jump map (A -> B joined in one diff); an explicit --map is used as given.
        route = options.has("direct") || options.values.containsKey("map") ? null : Route.plan(build.harness, from, to);
        Set<String> actual = versions(model);
        PluginCheck.Api plugin = actual.isEmpty() ? PluginCheck.api(model) : null;
        if (plugin != null) throw new IllegalArgumentException(
            "This is a server plugin: it builds against " +
            plugin.kind() +
            " " +
            plugin.version() +
            " (Paper " +
            PluginCheck.line(plugin.version()) +
            "), not a mod. " +
            "port does not move plugins between Paper versions yet. The move is usually config only: the " +
            plugin.kind() +
            " version in the build script, api-version in plugin.yml, " +
            "and the Java version (Paper 1.21.x runs on Java 21, 26.x on Java 25). Then mmt compile --task :jar and mmt plugin-check show whether it builds and enables. Nothing was changed."
        );
        if (!actual.equals(Set.of(to)) && !actual.equals(Set.of(from))) {
            String builds = actual.isEmpty()
                ? "no Minecraft version mmt could read"
                : "Minecraft " + String.join(", ", actual);
            throw new IllegalArgumentException(
                "--from " +
                from +
                " does not match this project: it builds for " +
                builds +
                ". " +
                (actual.size() == 1 ? "Use --from " + actual.iterator().next() + ", or select" : "Select") +
                " the project that builds " +
                from +
                " with --project (mmt setup lists them). Nothing was changed."
            );
        }
        Path selectionFile = selectionFile(options);
        List<Dependencies.Selection> selections = Dependencies.selections(selectionFile);
        long targetStart = System.nanoTime();
        // A required library with no target release may be ported from its own source on the way (LibraryPort).
        if (options.has("fix") && !options.has("dry-run")) Dependencies.portFrom = from;
        Target.Setup setup = planTargets(build, model, to, selections, actual, options);
        List<Target.Edit> target = setup == null ? TargetManifest.manifestEditsForModel(model, to) : setup.edits();
        List<Path> excluded = setup == null ? List.of() : setup.excluded;
        List<Map<String, Object>> applied = setup == null ? List.of() : setup.applied;
        List<Map<String, Object>> unchecked = setup == null ? List.of() : setup.unchecked;
        build.report.stages.add(
            Json.map(
                "name",
                "target-dependencies",
                "seconds",
                (System.nanoTime() - targetStart) / 1e9,
                "changedFiles",
                target.size(),
                "resolved",
                applied.size(),
                "blocked",
                setup == null ? 0 : setup.blockers.size(),
                "selections",
                selections.size()
            )
        );
        if (setup != null && !setup.blockers.isEmpty()) return Json.map(
            "ok",
            false,
            "tier",
            "dependency-blockers",
            "blocked",
            setup.blockers,
            "resolvedDependencies",
            applied,
            "selectionFile",
            selectionFile == null ? null : selectionFile.toString(),
            "sourcesChanged",
            false,
            "details",
            blockerText(setup.blockers)
        );
        if (!target.isEmpty() && (!options.has("fix") || options.has("dry-run"))) return Json.map(
            "ok",
            true,
            "tier",
            "target-preview",
            "route",
            route == null ? null : route.record(),
            "sourcesChanged",
            false,
            "edits",
            target
                .stream()
                .map(e -> Json.map("file", e.file().toString(), "before", e.before(), "after", e.after()))
                .toList(),
            "resolvedDependencies",
            applied,
            "excludedSources",
            relative(build.project.root, excluded),
            "selectionFile",
            selectionFile == null ? null : selectionFile.toString(),
            "details",
            "Published target dependencies resolved; --fix applies them with guarded undo."
        );

        JavaTools tools = new JavaTools(build);
        String names = options.get("names", maps.inferNames(from));
        // A route touching the SRG world is read in MCP names, the only names its source was written
        // against; --names mcp:stable_39 pins the published set, as VersionMap accepts.
        boolean legacy = Route.legacy(from) || Route.legacy(to);
        boolean mcp = names.equals("mcp") || names.startsWith("mcp:");
        if (
            !(Set.of("yarn", "mojmap").contains(names) || mcp) ||
            !from.matches("[A-Za-z0-9._+\\-]+") ||
            !to.matches("[A-Za-z0-9._+\\-]+")
        ) throw new IllegalArgumentException("Invalid version or mapping namespace");
        if (Route.legacy(from) && !mcp) throw new IllegalArgumentException(
            "A port from a release before " +
            Route.FIRST_MAPPED +
            " reads its source in MCP names (Forge SRG ids), not " +
            names +
            ": pass --names mcp (or mcp:stable_39 to pin the published name set). Nothing was changed."
        );
        if (mcp && !legacy && !from.equals(Route.FIRST_MAPPED) && !to.equals(Route.FIRST_MAPPED)) throw new IllegalArgumentException(
            "MCP names serve releases up to " + Route.FIRST_MAPPED + "; this route is in Mojang's names. Nothing was changed."
        );
        namespace = names;
        String mapNames = names.replace(':', '-'); // a pinned channel (mcp:stable_39) cannot sit in a file name
        Path map = options.values.containsKey("map")
            ? Path.of(options.get("map", "")).toAbsolutePath()
            : build.project.state().resolve("maps/" + mapNames + "-" + from + "-" + to + ".json");
        // A routed port joins every update it crosses into one map: old names resolve straight to final
        // names and each removal keeps the update that removed it. Only releases before 26.1 share
        // intermediary ids; the rest of a route into 26.x is left to the compiler and the rules. Yarn has no
        // names past 1.21.11, so a yarn source bound for 26.x keeps the identity map (a name bridge is step 14).
        // A Mojang-named source gets a map derived from the game itself (tools/port/learn/GameDiff.java): Mojang's client jars and
        // mappings for every release on the route, 26.x included, linked release to release from their own bytecode, plus
        // NeoForge's own published classes; no intermediary. A yarn source's names are Fabric's, keyed by intermediary,
        // so it keeps the intermediary map; so does --intermediary.
        Set<String> sourceLoaders = new TreeSet<>();
        for (Object p : Json.array(model.get("projects"))) sourceLoaders.addAll(Support.loaders(Json.object(p)));
        boolean own = names.equals("mojmap") && route != null && !options.has("intermediary");
        // A yarn source bound for the unobfuscated game (26.1+) crosses into Mojang's names: its map joins the source
        // release's yarn -> Mojang table with the game's own route map (composeMaps).
        boolean bridge =
            names.equals("yarn") &&
            route != null &&
            !options.has("intermediary") &&
            Versions.compare(to, Route.FIRST_UNOBFUSCATED) >= 0 &&
            Versions.compare(from, Route.FIRST_UNOBFUSCATED) < 0;
        List<String> chain = route == null ? List.of() : own || bridge ? route.versions : mcp ? route.srgChain() : route.mappedChain();
        // A route reaching past 1.14.4 on both sides crosses the seam: the SRG run, the name
        // translation at 1.14.4, then the intermediary run, in walking order either direction.
        boolean seam = route != null && route.crossesSeam();
        if (seam && !own && !bridge) chain = route.seamChain();
        boolean routed =
            chain.size() >= 2 && (bridge || !(names.equals("yarn") && Versions.compare(to, Route.FIRST_UNOBFUSCATED) >= 0));
        // A generated map is named after the tools that made it, so a changed generator makes a new one.
        String generator = own || bridge ? tools.classes.getFileName().toString() : "";
        // A bridged source is in Mojang's names once the map has run, so the Mojang-named rules are the ones that fit it.
        if (bridge) namespace = "mojmap";
        if (routed) map = build.project.state().resolve(
            "maps/" +
            (own
                    ? "game-" + generator.substring(0, Math.min(12, generator.length())) + "-"
                    : bridge ? "bridge-" + generator.substring(0, Math.min(12, generator.length())) + "-" : "route-") +
            mapNames +
            "-" +
            from +
            "-" +
            to +
            ".json"
        );
        String loaderDiff = own && sourceLoaders.contains("neoforge") ? "neoforge" : "";
        // 26.1 and newer ship with the game's own names and publish no intermediary, so there is nothing to
        // generate: a source already in that namespace compiles, and every difference left is an API change
        // the compiler names. The identity map keeps the repair rounds working on such a target.
        boolean unobfuscated = !routed && Versions.compare(to, "26.1") >= 0 && !options.values.containsKey("map");
        if (routed) {
            boolean current = false;
            if (Files.isRegularFile(map)) {
                var cached = Json.read(map);
                String cachedLoader = cached.get("loader") instanceof Map<?, ?> loader
                    ? String.valueOf(loader.get("name"))
                    : "";
                current =
                    chain.equals(ProjectModel.strings(cached.get("chain"))) &&
                    names.equals(cached.get("names")) &&
                    (bridge ? "yarn-bridge".equals(cached.get("linked")) : own == "game-bytecode".equals(cached.get("linked"))) &&
                    (!own || loaderDiff.equals(cachedLoader));
            }
            if (!current && bridge) {
                Files.createDirectories(map.getParent());
                Path game = map.resolveSibling(map.getFileName().toString().replace(".json", ".game.json"));
                Path yarnNames = map.resolveSibling(map.getFileName().toString().replace(".json", ".names.json"));
                var routeMap = tools.run("GameDiff", List.of("--chain", String.join(",", chain), "--out", game.toString()), "mapping-route");
                if (routeMap.exit() != 0) throw new IOException(
                    "No verified mapping along the route " + String.join(" -> ", chain) + ". " + Run.tail(routeMap.log())
                );
                var namesMap = tools.run(
                    "VersionMap",
                    List.of(from, from, "--from-names", "yarn", "--to-names", "mojmap", "--names", "yarn", "--out", yarnNames.toString()),
                    "mapping-yarn-to-mojang"
                );
                if (namesMap.exit() != 0) throw new IOException("No yarn -> Mojang table for " + from + ". " + Run.tail(namesMap.log()));
                PortMaps.saveMap(map, PortMaps.composeMaps(Json.read(yarnNames), Json.read(game)));
            } else if (!current) {
                Files.createDirectories(map.getParent());
                List<String> arguments = new ArrayList<>(List.of("--chain", String.join(",", chain), "--out", map.toString()));
                if (own && !loaderDiff.isEmpty()) arguments.addAll(List.of("--loader", loaderDiff));
                if (!own) arguments.addAll(List.of("--names", names));
                if (!own && seam) arguments.addAll(List.of("--to-names", maps.targetNames()));
                var mapped = tools.run(own ? "GameDiff" : "VersionMap", arguments, "mapping-route");
                if (mapped.exit() != 0) throw new IOException(
                    "No verified mapping along the route " + String.join(" -> ", chain) + ". " + Run.tail(mapped.log())
                );
                if (!own && names.equals("yarn")) PortMaps.yarnRewrites(tools, chain, from, to, map);
            }
            // A yarn route map made before the game's rewrites were added to it gets them now (once: the key marks it).
            if (current && !own && !bridge && names.equals("yarn") && !Json.read(map).containsKey("rewrites")) PortMaps.yarnRewrites(tools, chain, from, to, map);
            build.report.stages.add(
                Json.map("name", "route", "waypoints", route.waypoints, "mapped", chain, "cached", current)
            );
        } else if (unobfuscated) {
            map = build.project.state().resolve("maps/identity-" + names + "-" + to + ".json");
            if (!Files.isRegularFile(map)) {
                Files.createDirectories(map.getParent());
                Files.writeString(
                    map,
                    "{\n  \"from\": \"" +
                    to +
                    "\", \"to\": \"" +
                    to +
                    "\", \"names\": \"" +
                    names +
                    "\",\n" +
                    "  \"classes\": [], \"classesRemoved\": [], \"methods\": [], \"methodsMoved\": [], \"signatures\": [], \"methodsRemoved\": [], \"fields\": [], \"fieldsRemoved\": []\n}\n"
                );
            }
        } else if (!Files.isRegularFile(map)) {
            var mapped = tools.run(
                "VersionMap",
                List.of(from, to, "--names", names, "--out", map.toString()),
                "mapping"
            );
            if (mapped.exit() != 0) throw new IOException(
                "No verified mapping for this jump. " + Run.tail(mapped.log())
            );
        }
        int meaningChanges = PortMaps.withoutMeaningChanges(map, build.harness.resolve("knowledge/meaning-changes.json"));
        if (meaningChanges > 0) build.report.stages.add(Json.map("name", "meaning-changes", "dropped", meaningChanges, "source", "knowledge/meaning-changes.json"));
        var mapInfo = Json.read(map);
        maps.mapFile = map;
        String mapFrom = routed ? chain.get(0) : from, mapTo = routed ? chain.get(chain.size() - 1) : to;
        if (
            !unobfuscated &&
            (!mapFrom.equals(mapInfo.get("from")) ||
                !mapTo.equals(mapInfo.get("to")) ||
                !names.equals(mapInfo.get("names")))
        ) throw new IOException("Mapping identity does not match the requested source, target and namespace.");
        Map<String, Object> routeRecord = route == null ? null : route.record();
        if (routeRecord != null) routeRecord.put(
            "mapping",
            routed && bridge
                ? "yarn names of " + from + " joined to Mojang's (Fabric's yarn and intermediary with Mojang's mappings), then one map across " +
                String.join(" -> ", chain) +
                " from the game itself"
                : routed && own
                ? "one map joined across " +
                String.join(" -> ", chain) +
                " from the game itself: Mojang's jars and mappings linked release to release by their own bytecode" +
                (loaderDiff.isEmpty() ? "" : ", with " + loaderDiff + "'s own published classes")
                : routed && mcp
                ? seam
                    ? "one map joined across " +
                    String.join(" -> ", chain) +
                    " by Forge SRG ids to 1.14.4 (MCP names), translated to Mojang's names at the seam through the obfuscated names both lanes share, then by Fabric intermediary ids" +
                    (chain.size() < route.versions.size()
                            ? "; updates past " + chain.get(chain.size() - 1) + " have no shared ids and are left to the compiler and rules"
                            : "")
                    : "one map joined across " +
                    String.join(" -> ", chain) +
                    " by Forge SRG ids (MCP names); a class rename the links miss reads as a removal and an addition"
                : routed
                ? "one map joined across " +
                String.join(" -> ", chain) +
                " by Fabric intermediary ids" +
                (chain.size() < route.versions.size()
                        ? "; updates past " +
                        chain.get(chain.size() - 1) +
                        " have no shared ids and are left to the compiler and rules"
                        : "")
                : "identity (no shared ids on this route)"
        );
        if (!options.has("fix")) {
            if (!options.values.containsKey("log")) return Json.map(
                "ok",
                true,
                "tier",
                "mapping",
                "map",
                map.toString(),
                "route",
                routeRecord,
                "gameVerified",
                false
            );
            var explain = tools.run(
                "VersionMap",
                List.of(
                    "--map",
                    map.toString(),
                    "--explain",
                    Path.of(options.get("log", "")).toAbsolutePath().toString()
                ),
                "port-diagnosis"
            );
            return Json.map(
                "ok",
                explain.exit() == 0,
                "tier",
                "diagnosis",
                "details",
                Run.tail(explain.log()),
                "gameVerified",
                false
            );
        }
        if (options.has("dry-run")) {
            // The preflight needs no compiler log: it reports what the map knows from the source
            // alone, so an uncompilable tree still gets checked. The fix preview additionally needs
            // the log to place each remaining error.
            var lint = tools.run(
                "VersionMap",
                List.of(
                    "--map",
                    map.toString(),
                    "--preflight",
                    "--repo",
                    build.project.root.toString(),
                    "--dry-run"
                ),
                "port-preview"
            );
            if (!options.values.containsKey("log")) return Json.map(
                "ok",
                lint.exit() == 0,
                "tier",
                "preview",
                "details",
                Run.tail(lint.log()),
                "sourcesChanged",
                false,
                "gameVerified",
                false
            );
            var preview = tools.run(
                "VersionMap",
                List.of(
                    "--map",
                    map.toString(),
                    "--fix",
                    Path.of(options.get("log", "")).toAbsolutePath().toString(),
                    "--repo",
                    build.project.root.toString(),
                    "--dry-run"
                ),
                "port-preview"
            );
            return Json.map(
                "ok",
                preview.exit() == 0,
                "tier",
                "preview",
                "details",
                Run.tail(preview.log()),
                "sourcesChanged",
                false,
                "gameVerified",
                false
            );
        }
        Path learned = options.values.containsKey("rules") ? Path.of(options.get("rules", "")).toAbsolutePath() : null;
        boolean corpusLoaded = learned != null;
        Target.requireWritable(target);
        Path transaction = build.project.state().resolve("ports/" + build.report.id);
        journal.transaction = transaction;
        Map<String, Object> before = journal.before;
        Set<Path> backupPaths = new TreeSet<>(sources(build.project));
        for (Target.Edit edit : target) {
            boolean created = edit.before() == null && !Files.exists(edit.file());
            Path file = created ? edit.file().toAbsolutePath().normalize() : edit.file().toRealPath();
            if (!file.startsWith(build.project.root)) throw new IOException(
                "Target edit is outside the source project: " + file
            );
            backupPaths.add(file);
        }
        List<String> takenOver = new ArrayList<>();
        for (Path file : backupPaths) {
            Path relative = build.project.root.relativize(file), backup = transaction
                .resolve("before")
                .resolve(relative);
            // A build script already changed before the transaction (a dead decompiler plugin removed) is backed up as it was.
            Path original = PortDevPlugins.pendingOriginals(build.project).resolve(relative.toString());
            Path source = Files.isRegularFile(original) ? original : file;
            if (source == original) takenOver.add(relative.toString().replace('\\', '/'));
            if (!Files.isRegularFile(source)) {
                before.put(relative.toString().replace(File.separatorChar, '/'), null);
                continue;
            }
            Files.createDirectories(backup.getParent());
            Files.copy(source, backup);
            before.put(relative.toString().replace('\\', '/'), Fingerprints.bytes(Files.readAllBytes(source)));
        }
        journal.planned.addAll(takenOver);
        PortDevPlugins.deletePending(build.project);
        // The restore plan is on disk before the first edit: an interrupted port still knows what it may have changed.
        for (Target.Edit edit : target) {
            Path edited = edit.before() == null && !Files.exists(edit.file())
                ? edit.file().toAbsolutePath().normalize()
                : edit.file().toRealPath();
            journal.planned.add(build.project.root.relativize(edited).toString().replace(File.separatorChar, '/'));
        }
        journal.plannedRemovals = relative(build.project.root, excluded);
        journal.saveInProgress();
        Path log;
        boolean compiled = false;
        int rounds = Integer.parseInt(options.get("rounds", "12"));
        List<String> removed = new ArrayList<>();
        if (rounds < 1 || rounds > 20) throw new IllegalArgumentException("--rounds must be 1–20");
        try {
            PortPlan.Compiled ownBuild = !target.isEmpty() && namespace.equals("mojmap") ? answers.ownCompile(model) : null;
            if (!target.isEmpty()) {
                Target.apply(target);
                build.refreshJdk();
            }
            // Planned on the sources as written (javac against the mod's own release); written after the preflight below,
            // which renames by text in the old names and would take an answer's new names for old ones.
            if (ownBuild != null) answers.sourceAnswers(tools, model, map, from, to, ownBuild);
            for (Path file : excluded) {
                Path owned = file.toRealPath();
                if (!owned.startsWith(build.project.root)) throw new IOException(
                    "Excluded source is outside the source project: " + owned
                );
                removed.add(build.project.root.relativize(owned).toString().replace('\\', '/'));
                Files.delete(owned);
            }
            model = build.model(options.has("refresh"), false);
            if (!versions(model).equals(Set.of(to))) throw new IOException(
                "Evaluated build did not select " + to + ": " + versions(model)
            );
            PortMetadata.verifyLoaderVersion(model, to);
            var preflight = tools.run(
                "VersionMap",
                List.of("--map", map.toString(), "--preflight", "--repo", build.project.root.toString()),
                "port-preflight"
            );
            if (preflight.exit() != 0) throw new IOException("Typed preflight stopped: " + Run.tail(preflight.log()));
            answers.applyAnswerPlan();
            boolean explicitTask = options.values.containsKey("task");
            List<String> tasks = new ArrayList<>(
                explicitTask ? List.of(options.get("task", "")) : build.tasks(model, "build")
            );
            if (tasks.isEmpty()) throw new IOException(
                explicitTask
                    ? "No compile task selected."
                    : "No evaluated build task; pass --task only for a compile-only port check."
            );
            excludeOtherTargets(tasks, model);
            tasks.add(0, "-Dmmt.recompile=true");
            // Every compile task whose inputs built reports its errors, not only the first one to fail.
            tasks.add(0, "--continue");
            // Repair rounds only need the compiler; the full build (tests, jar) runs once when the source is clean.
            List<String> repairTasks = new ArrayList<>(explicitTask ? tasks : build.tasks(model, "classes"));
            if (repairTasks.isEmpty()) repairTasks = tasks;
            else excludeOtherTargets(repairTasks, model);
            repairTasks.removeIf(argument -> argument.equals("--continue") || argument.equals("-Dmmt.recompile=true"));
            repairTasks.add(0, "-Dmmt.recompile=true");
            repairTasks.add(0, "--continue");
            rules.applySilentRules(from, to);
            rules.renameClassesInStrings(map);
            access.portAccessTransformer(maps.mapFile);
            var initial = build.gradle(tasks, explicitTask ? "port-initial-compile" : "port-initial-build");
            if (initial.exit() != 0 && access.dropMissingAccessTargets(initial.log()) > 0) initial = build.gradle(tasks, explicitTask ? "port-initial-compile-at" : "port-initial-build-at");
            log = initial.log();
            compiled = initial.exit() == 0;
            int answersReverted = compiled ? 0 : answers.revertRejectedAnswers(log);
            if (answersReverted > 0) {
                build.report.stages.add(Json.map("name", "source-answers-rejected", "reverted", answersReverted));
                initial = build.gradle(tasks, explicitTask ? "port-initial-compile-answers" : "port-initial-build-answers");
                log = initial.log();
                compiled = initial.exit() == 0;
            }
            answers.answersWritten.clear();
            // The errors the port started from (every one: port builds lift javac's 100-error cap), before any repair.
            int errorsBefore = compiled ? 0 : errors(log).size();
            Set<String> seen = new HashSet<>();
            int roundsTried = 0;
            for (int round = 0; !compiled && round < rounds; round++) {
                String state = Fingerprints.files(sources(build.project));
                if (!seen.add(state)) break;
                roundsTried = round + 1;
                // The desugar shifts line numbers, so it gets its own round and rebuild before the line-based
                // rules read positions from the same log.
                if (desugarer.desugar(log)) {
                    var desugared = build.gradle(repairTasks, "port-desugar-" + (round + 1));
                    log = desugared.log();
                    compiled = desugared.exit() == 0;
                    continue;
                }
                rules.applyRules(log);
                carriedClasses.importMissingClasses(log);
                carriedClasses.carryRemovedClasses(log, from);
                carriedClasses.undiamond(log);
                desugarer.restoreAnnotationLibraries(log);
                // Rules are not learned from other people's mods unless asked: the map comes from the game itself.
                if (!corpusLoaded && options.has("learn-from-mods")) {
                    learned = Corpus.rules(build, model, tools, map, from, to, names);
                    corpusLoaded = true;
                }
                var args = new ArrayList<>(
                    List.of("--map", map.toString(), "--fix", log.toString(), "--repo", build.project.root.toString())
                );
                if (learned != null) args.addAll(List.of("--rules", learned.toString()));
                Path supers = hierarchy.supertypes(log);
                if (supers != null) args.addAll(List.of("--supers", supers.toString()));
                var fix = tools.run("VersionMap", args, "port-fix-" + (round + 1));
                rules.flushImports();
                if (fix.exit() != 0) throw new IOException("Typed repair stopped: " + Run.tail(fix.log()));
                if (state.equals(Fingerprints.files(sources(build.project)))) break;
                var compile = build.gradle(repairTasks, (explicitTask ? "port-compile-" : "port-build-") + (round + 1));
                log = compile.log();
                compiled = compile.exit() == 0;
            }
            // The mod's mixins are applied by Mixin itself against the target game (no window, no world).
            // The hand audit still runs as a fast hint for the report, never as the verdict.
            Map<String, Object> mixins = compiled ? sourceMixins.mixinCheck(model, options) : null;
            // What Mixin itself reports against the target game and can be repaired from its words alone; Mixin checks each repair.
            for (
                int round = 0;
                mixins != null && !Boolean.TRUE.equals(mixins.get("ok")) && round < 3 && sourceMixins.repairMixins(mixins);
                round++
            ) {
                var compile = build.gradle(repairTasks, "port-mixin-build-" + (round + 1));
                log = compile.log();
                compiled = compile.exit() == 0;
                roundsTried++;
                mixins = compiled ? sourceMixins.mixinCheck(model, options) : null;
            }
            var audit = compiled ? tools.audit(model) : null;
            if (compiled) metadata.migrateLegacyMetadata(to);
            boolean passed = compiled && mixins != null && Boolean.TRUE.equals(mixins.get("ok"));
            boolean built = !explicitTask && compiled;
            if (passed && (!explicitTask ? roundsTried > 0 : true)) {
                List<String> buildTasks = new ArrayList<>(build.tasks(model, "build"));
                if (!buildTasks.isEmpty()) {
                    excludeOtherTargets(buildTasks, model);
                    var finalBuild = build.gradle(buildTasks, "port-build");
                    built = finalBuild.exit() == 0;
                    if (!built) {
                        passed = false;
                        log = finalBuild.log();
                    }
                }
            }
            Map<String, Object> metadataCheck = passed ? PortMetadata.verifyModMetadata(build.project.root, model, to) : null;
            if (metadataCheck != null && Boolean.FALSE.equals(metadataCheck.get("ok"))) passed = false;
            boolean mixinsApplied =
                mixins != null && Boolean.TRUE.equals(mixins.get("ok")) && Boolean.TRUE.equals(mixins.get("checked"));
            Map<String, Object> result = Json.map(
                "ok",
                passed,
                "tier",
                tier(compiled, mixins, built),
                "compiled",
                compiled,
                "errorsBefore",
                errorsBefore,
                "built",
                built,
                "mixinTargetsVerified",
                mixins != null && Boolean.TRUE.equals(mixins.get("ok")),
                "mixinsAppliedByMixin",
                mixinsApplied,
                "mixinCheck",
                mixins,
                "modMetadata",
                metadataCheck,
                "gameVerified",
                false,
                "behaviorVerified",
                false,
                "log",
                log.toString(),
                "undo",
                transaction.toString(),
                "resolvedDependencies",
                applied,
                "excludedSources",
                removed,
                "selectionFile",
                selectionFile == null ? null : selectionFile.toString(),
                "details",
                passed
                    ? (built
                            ? "Built, and " + PortMixins.mixinSummary(mixins) + ". Gameplay and behavior remain unverified."
                            : "Compiled, and " +
                            PortMixins.mixinSummary(mixins) +
                            ". No standard build task was available; gameplay remains unverified.") +
                    uncheckedText(unchecked) +
                    preReleaseText(applied)
                    : compiled && mixins != null && !Boolean.TRUE.equals(mixins.get("ok"))
                        ? String.valueOf(mixins.get("details"))
                        : Run.tail(log)
            );
            if (!unchecked.isEmpty()) result.put("uncheckedDependencies", unchecked);
            // A port is done when the mod works in the real game: --check runs the mod's own game check on the result.
            boolean checkFailed = false;
            if (passed && options.values.containsKey("check")) {
                Map<String, Object> check = behaviourBefore != null ? behaviourCheck(to) : gameCheck(options.get("check", ""));
                boolean works = Boolean.TRUE.equals(check.get("ok"));
                checkFailed = !works;
                result.put("gameCheck", check);
                result.put("gameVerified", works);
                result.put("behaviorVerified", works);
                result.put("ok", works);
                if (works) result.put("tier", "behaviour");
                else result.put("reason", "The port built, but the game check failed; see gameCheck.");
                result.put(
                    "details",
                    works
                        ? "Built, " + PortMixins.mixinSummary(mixins) + ", and the game check passed: " + check.get("command")
                        : "Built and " + PortMixins.mixinSummary(mixins) + ", but the game check failed: " + check.get("tail")
                );
            }
            if (audit != null) result.put(
                "mixinHint",
                Json.map(
                    "source",
                    "mmt's own bytecode audit (a guess: it reads class files, Mixin does not run)",
                    "summary",
                    PortMixins.auditSummary(audit.log()),
                    "log",
                    audit.log().toString()
                )
            );
            List<Object> named = passed ? List.of() : explainer.namedErrors(log, routed ? mapInfo : null);
            if (routeRecord != null) result.put("route", explainer.hopRecords(routeRecord, routed ? mapInfo : null, named));
            if (!passed) {
                if (metadataCheck != null && Boolean.FALSE.equals(metadataCheck.get("ok"))) {
                    result.put("stoppedSafely", true);
                    result.put("reason", metadataCheck.get("details"));
                    return result;
                }
                Path repair = transaction.resolve("repair-request.json");
                List<Map<String, Object>> guides = compiled
                    ? List.of()
                    : PortRules.repairGuides(build.harness, from, to, logText(log));
                Json.save(
                    repair,
                    Json.map(
                        "root",
                        build.project.root.toString(),
                        "from",
                        from,
                        "to",
                        to,
                        "namespace",
                        names,
                        "map",
                        map.toString(),
                        "model",
                        build.project.state().resolve("model.json").toString(),
                        "log",
                        log.toString(),
                        "mixinCheck",
                        mixins,
                        "diagnostics",
                        compiled && mixins != null ? String.valueOf(mixins.get("details")) : Run.tail(log),
                        "guides",
                        guides,
                        "requirements",
                        List.of(
                            "Preserve existing behavior, including debug hooks and rendering.",
                            "Do not remove required injections or disable checks to make a build pass.",
                            "Rebuild and exercise relevant real game scenarios after a semantic repair."
                        )
                    )
                );
                result.put("repairRequest", repair.toString());
                if (!guides.isEmpty()) result.put(
                    "repairGuides",
                    guides.stream().map(guide -> guide.get("id") + ": " + guide.get("title")).toList()
                );
                List<Object> unresolved = compiled ? List.of() : named;
                result.put("unresolved", unresolved);
                result.put("stoppedSafely", true);
                StringBuilder byUpdate = new StringBuilder();
                if (route != null) {
                    Map<String, Integer> counts = new LinkedHashMap<>();
                    for (Object item : unresolved) {
                        List<Object> changed = Json.array(Json.object(item).get("changedIn"));
                        String step = changed.isEmpty()
                            ? "not in the mapping history"
                            : Json.object(changed.get(0)).get("step") +
                            (String.valueOf(Json.object(changed.get(0)).get("match")).startsWith("name only")
                                    ? " (name only)"
                                    : "");
                        counts.merge(step, 1, Integer::sum);
                    }
                    counts.forEach((step, n) ->
                        byUpdate
                            .append(byUpdate.length() == 0 ? " By update: " : "; ")
                            .append(step)
                            .append(": ")
                            .append(n)
                    );
                    if (byUpdate.length() > 0) byUpdate.append('.');
                }
                result.put(
                    "reason",
                    failureReason(to, compiled, mixins, unresolved.size(), roundsTried, byUpdate.toString(), log)
                );
            }
            if (route != null) result.put("rulesApplied", rulesApplied);
            result.put("rulesByProvenance", rules.provenance(rulesApplied.keySet()));
            return result;
        } catch (Exception failure) {
            throw new IOException(
                failure.getMessage() + "\nUndo this port with: port --undo \"" + transaction + "\"",
                failure
            );
        } finally {
            removed.addAll(neoEntryMigration.repairedAway);
            PortJournal.finishTransaction(build.project, transaction, before, removed);
        }
    }

    /**
     * What a port reached, never more: a source that did not compile is "compile-failed" (a NeoForge mod's failed
     * 1.21.4 builds were once reported as compile-and-mixin-applied), mixins Mixin did not apply are
     * "compiled-mixins-failed", and only a packaged build is "build-and-mixin-applied".
     */
    static String tier(boolean compiled, Map<String, Object> mixins, boolean built) {
        if (!compiled) return "compile-failed";
        if (mixins == null || !Boolean.TRUE.equals(mixins.get("ok"))) return "compiled-mixins-failed";
        return built ? "build-and-mixin-applied" : "compile-and-mixin-applied";
    }

    /** Report every target dependency as resolved, selected, or blocked without changing the project. */
    Map<String, Object> dependencies(Mmt.Options options) throws Exception {
        String to = options.get("to", "");
        if (to.isBlank() || !to.matches("[A-Za-z0-9._+\\-]+")) throw new IllegalArgumentException(
            "dependencies needs --to <target Minecraft version>"
        );
        Path selectionFile = selectionFile(options);
        List<Dependencies.Selection> selections = Dependencies.selections(selectionFile);
        Target.Setup setup = versions(model).equals(Set.of(to)) ? null : Target.plan(build, model, to, selections);
        List<Map<String, Object>> applied = setup == null ? List.of() : setup.applied, blocked = setup == null
            ? List.of()
            : setup.blockers;
        Set<String> used = new HashSet<>();
        for (var record : applied) used.add(String.valueOf(record.get("artifact")));
        List<Object> unused = new ArrayList<>();
        for (Dependencies.Selection selection : selections) {
            String[] source = Dependencies.coordinate(selection.source());
            if (!used.contains(source[0] + ":" + source[1])) unused.add(selection.source());
        }
        boolean ready = blocked.isEmpty();
        return Json.map(
            "to",
            to,
            "selectionFile",
            selectionFile == null ? null : selectionFile.toString(),
            "selections",
            selections.size(),
            "resolved",
            applied,
            "blocked",
            blocked,
            "unchecked",
            setup == null ? List.of() : setup.unchecked,
            "unusedSelections",
            unused,
            "ready",
            ready,
            "details",
            (ready
                    ? (setup != null && !setup.unchecked.isEmpty()
                            ? "Every checked target dependency resolves; port --fix can apply these changes."
                            : "Every target dependency resolves; port --fix can apply these changes.")
                    : blockerText(blocked)) +
            (setup == null ? "" : uncheckedText(setup.unchecked) + preReleaseText(applied))
        );
    }

    Path selectionFile(Mmt.Options options) throws IOException {
        if (options.values.containsKey("selection")) return Path.of(options.get("selection", "")).toAbsolutePath();
        Path file = build.project.root.resolve(".mmt/dependencies.json");
        return Files.isRegularFile(file) ? file : null;
    }

    /**
     * Why a port stopped, by what actually failed: the mixin audit, a build that failed for a reason the
     * compiler did not report (shown from Gradle's own failure block), or compiler errors no rule matched.
     */
    static String failureReason(
        String to,
        boolean compiled,
        Map<String, Object> mixins,
        int unresolved,
        int rounds,
        String byUpdate,
        Path log
    ) throws IOException {
        if (compiled && mixins != null && !Boolean.TRUE.equals(mixins.get("ok"))) return (
            "The code compiled against " +
            to +
            ", but " +
            (Boolean.TRUE.equals(mixins.get("checked"))
                    ? "Mixin did not apply every mixin cleanly against the " +
                    to +
                    " game. Each failure is listed in details in Mixin's own words; fix the mixin, then rerun."
                    : "the mixins were not checked by Mixin, so the port cannot say they work: " + mixins.get("reason"))
        );
        if (compiled) return (
            "The code compiled against " +
            to +
            ", but the packaged build failed without a compiler error:\n" +
            Run.gradleFailure(log) +
            "\nLog: " +
            log
        );
        if (unresolved == 0) return (
            "The build failed without compiler errors, so this is not a source migration and no rule can fix it:\n" +
            Run.gradleFailure(log) +
            "\nLog: " +
            log
        );
        return (
            unresolved +
            " compiler error(s) remain after " +
            rounds +
            " repair round(s) and no typed rule matched." +
            byUpdate +
            " This needs a semantic migration; the port stopped instead of guessing. Resolve them in source, or add a verified recipe, then rerun."
        );
    }

    static String uncheckedText(List<Map<String, Object>> unchecked) {
        if (unchecked.isEmpty()) return "";
        StringBuilder text = new StringBuilder("\nNot checked for the target version (" + unchecked.size() + "):");
        for (var item : unchecked) text
            .append("\n  ")
            .append(item.get("artifact"))
            .append(" ")
            .append(item.get("version"))
            .append(": ")
            .append(item.get("reason"));
        return text.toString();
    }

    static String preReleaseText(List<Map<String, Object>> applied) {
        StringBuilder text = new StringBuilder();
        for (var item : applied) if (item.get("preRelease") != null) text
            .append("\nPre-release chosen: ")
            .append(item.get("preRelease"));
        for (var item : applied) if (item.get("warning") != null) text
            .append("\nWarning: ")
            .append(item.get("warning"));
        return text.toString();
    }

    static String blockerText(List<Map<String, Object>> blockers) {
        StringBuilder text = new StringBuilder(
            blockers.size() + " target dependencies need a verified selection before any edit:"
        );
        for (var blocker : blockers) text
            .append("\n  ")
            .append(blocker.get("artifact"))
            .append(" ")
            .append(blocker.get("version"))
            .append(": ")
            .append(blocker.get("reason"))
            .append("\n    add to .mmt/dependencies.json: ")
            .append(Json.write(blocker.get("suggestion")));
        return text.toString();
    }

    static List<String> relative(Path root, List<Path> files) {
        return files.stream().map(file -> root.relativize(file).toString().replace('\\', '/')).sorted().toList();
    }

    /** Distinct compiler errors left in a log, so a stopped port names exactly what a person must decide. */
    static List<Object> errors(Path log) throws IOException {
        List<Object> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher reported = ERROR.matcher(logText(log));
        while (reported.find()) {
            String key = reported.group(1) + ":" + reported.group(2) + ":" + reported.group(3);
            if (!seen.add(key)) continue;
            list.add(
                Json.map(
                    "file",
                    reported.group(1),
                    "line",
                    Integer.parseInt(reported.group(2)),
                    "message",
                    reported.group(3)
                )
            );
        }
        return list;
    }

    /** Every jar on the evaluated compile classpaths. */
    static List<Path> classpath(Map<String, Object> model) {
        Set<Path> jars = new LinkedHashSet<>();
        for (Object item : Json.array(model.get("projects"))) if (
            Json.object(item).get("compile") instanceof List<?> compiles
        ) for (Object c : compiles) for (String path : ProjectModel.strings(Json.object(c).get("classpath"))) if (
            path.endsWith(".jar")
        ) jars.add(Path.of(path));
        return new ArrayList<>(jars);
    }

    private static void excludeOtherTargets(List<String> tasks, Map<String, Object> model) {
        for (Object item : Json.array(model.get("projects"))) {
            Object composites = Json.object(item).get("composites");
            if (!(composites instanceof List<?> list)) continue;
            for (Object composite : list) {
                tasks.add("-x");
                tasks.add(composite.toString());
            }
        }
    }

    /** Target planning for a port. A dry run previews the source rewrite, which needs no target
        build, so a planning failure degrades to no build changes instead of throwing. */
    static Target.Setup planTargets(
        Build build, Map<String, Object> model, String to, List<Dependencies.Selection> selections, Set<String> actual, Mmt.Options options
    ) throws Exception {
        if (actual.equals(Set.of(to))) return null;
        if (!options.has("dry-run")) return Target.plan(build, model, to, selections);
        try {
            return Target.plan(build, model, to, selections);
        } catch (Exception planning) {
            build.report.stages.add(
                Json.map("name", "target-dependencies-skipped", "reason", String.valueOf(planning.getMessage()))
            );
            return null;
        }
    }

    static Set<String> versions(Map<String, Object> model) {
        Set<String> actual = new TreeSet<>();
        for (Object p : Json.array(model.get("projects"))) {
            var pins = Json.object(Json.object(p).get("pins"));
            for (String key : List.of(
                "minecraft_version",
                "minecraftVersion",
                "exactMinecraftVersion",
                "mc_version",
                "mcVersion"
            )) if (pins.containsKey(key)) actual.add(pins.get(key).toString());
            for (Object d : Json.array(Json.object(p).get("dependencies"))) {
                var dep = Json.object(d);
                if (
                    "com.mojang".equals(dep.get("group")) &&
                    "minecraft".equals(dep.get("name")) &&
                    dep.get("version") != null
                ) actual.add(dep.get("version").toString());
            }
        }
        return actual;
    }

    /**
     * The mod's own game check, run on the ported project: a command (a mmt eval/ui-check script, or a test program)
     * started in the project with MMT_PORTED_ROOT set to it. Its exit code is the verdict.
     */
    /** The fingerprint taken before the port (--check fingerprint), or null. */
    Path behaviourBefore;

    /** The mod's behaviour fingerprint on one game version, in its own development client (Fingerprint.record). */
    Path behaviour(String minecraft, String name) throws Exception {
        Path out = build.report.directory.resolve(name + ".json");
        Set<String> namespaces = Fingerprint.modIds(build.project.root);
        Map<String, Object> args = Json.map("ticks", 100, "every", 20, "seed", Fingerprint.SEED, "namespaces", new ArrayList<>(namespaces));
        Fingerprint.record(build.harness, build.project.root, "", minecraft, namespaces, args, build.report, out, name);
        return out;
    }

    /**
     * The port's behaviour check: the fingerprint after the port against the one before, across the two versions only
     * what the mod owns is compared, after the game's own renames (knowledge/fingerprint-normalise.json).
     */
    Map<String, Object> behaviourCheck(String to) throws Exception {
        long start = System.nanoTime();
        Path after = behaviour(to, "fingerprint-after");
        Map<String, Object> a = Json.read(behaviourBefore), b = Json.read(after);
        Set<String> namespaces = new TreeSet<>();
        for (Object ns : Json.array(Json.object(a.get("target")).get("namespaces"))) namespaces.add(ns.toString());
        FingerprintDiff.Normaliser normaliser = Json.text(Json.object(a.get("target")), "minecraft", "").equals(to)
            ? new FingerprintDiff.Normaliser("exact", Set.of())
            : FingerprintDiff.Normaliser.across(Json.read(build.harness.resolve("knowledge/fingerprint-normalise.json")), namespaces, Json.object(a.get("target")).get("minecraft") + " -> " + to);
        Map<String, Object> diff = FingerprintDiff.compare(a, b, normaliser);
        boolean same = Boolean.TRUE.equals(diff.get("same")) && ((Number) diff.get("compared")).intValue() > 0;
        return Json.map("ok", same, "command", "fingerprint " + behaviourBefore + " -> " + after, "before", behaviourBefore.toString(), "after", after.toString(),
            "seconds", (System.nanoTime() - start) / 1e9, "diff", diff, "tail", FingerprintDiff.text(diff));
    }

    Map<String, Object> gameCheck(String command) throws Exception {
        long start = System.nanoTime();
        List<String> shell = Runtimes.WINDOWS ? List.of("cmd.exe", "/d", "/s", "/c", command) : List.of("sh", "-c", command);
        Run.Result run = Run.command(
            shell,
            build.project.root,
            Map.of("MMT_PORTED_ROOT", build.project.root.toString(), "MODACCEL_PORTED_ROOT", build.project.root.toString()),
            build.report.log("port-game-check"),
            3600
        );
        build.report.stage("port-game-check", run, Map.of());
        return Json.map(
            "ok",
            run.exit() == 0,
            "command",
            command,
            "exit",
            run.exit(),
            "seconds",
            (System.nanoTime() - start) / 1e9,
            "log",
            run.log().toString(),
            "tail",
            Run.tail(run.log())
        );
    }

    static final String WRITTEN_MARK = "Written by mmt port";

    /**
     * A reported source file: an absolute path (C:\... or /...) or a relative one starting with a plain name. Gradle's
     * failure summary repeats javac's errors with the previous line glued on ("^C:\...", "^Note: ... outputC:\...",
     * "(actual and formal argument lists differ in length)C:\..."); those repeats do not start like a path, so they are
     * skipped rather than read as one. The errors themselves were already read where javac printed them.
     */
    static final String FILE =
        "((?:[A-Za-z]:[\\\\/]|/)[^:\\r\\n]*?\\.(?:java|kt)|[\\w.-]+(?:[\\\\/][\\w .-]+)*\\.(?:java|kt))";
    static final Pattern ERROR = Pattern.compile("(?m)^\\s*" + FILE + ":(\\d+): error: ([^\\r\\n]+)");
}

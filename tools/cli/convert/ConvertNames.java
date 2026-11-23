import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** A Fabric mod written in Yarn's names moved to Mojang's, refmaps included. */
final class ConvertNames {

    final Convert convert;

    ConvertNames(Convert convert) {
        this.convert = convert;
    }

    // ---- yarn's names to Mojang's ---------------------------------------------------------------------

    /** Whether the mod is written in Fabric's yarn names: its build maps the game with net.fabricmc:yarn (a yarn_mappings property alone may be left over). */
    boolean yarnNames() throws IOException {
        for (String file : List.of("build.gradle", "build.gradle.kts")) if (convert.projectFiles.read(file).contains("net.fabricmc:yarn:")) return true;
        return false;
    }

    /** Set once yarnToMojang has renamed the mod: its access widener is in Mojang's names from then on. */
    boolean renamedFromYarn;

    /** The untouched yarn mod's refmaps (mixin -> source string -> intermediary), and their names in Mojang's. */
    Map<String, Map<String, String>> originalRefmaps = Map.of();

    RefmapNames refmapNames;

    /**
     * A mod in yarn's names moves to Mojang's names first, on its own game version, since NeoForge (and the other
     * loader's bridge) is written in Mojang's names. The Java sources go through Loom's own migrateMappings to
     * net.minecraft:mappings (Mojang's official names), which renames every Java reference with the compiler's
     * types; what Mixin reads by name (targets, method = "...", @At targets, @Accessor, @Shadow), which migrateMappings
     * leaves, and the access widener, which these Loom versions do not migrate, through the yarn -> Mojang table port
     * uses (VersionMap --from-names yarn --to-names mojmap, MixinNames); and the build maps the game with
     * loom.officialMojangMappings().
     */
    void yarnToMojang(String minecraft) throws Exception {
        // Every source set (main, client, test, testmod, gametest ...): each is compiled against the same game.
        List<String> roots = new ArrayList<>();
        if (Files.isDirectory(convert.project.root.resolve("src"))) try (var sets = Files.list(convert.project.root.resolve("src"))) {
            for (Path set : sets.sorted().toList()) if (Files.isDirectory(set.resolve("java"))) roots.add("src/" + set.getFileName() + "/java");
        }
        // The untouched mod's own build resolves every mixin string to an intermediary member in its refmaps: built once
        // here, they rename those strings exactly (RefmapNames) and check the renamed build's refmaps after it compiles.
        List<String> tasks = new ArrayList<>();
        for (String root : roots) {
            String set = root.split("/")[1];
            tasks.add(set.equals("main") ? "classes" : set + "Classes");
        }
        tasks.add("--continue");
        var original = convert.build.gradle(tasks, "convert-yarn-refmap");
        originalRefmaps = RefmapNames.read(convert.project.root.resolve("build/classes"));
        // Build output only: the renamed build's refmaps must be its own when they are checked after it compiles.
        OwnCompile.deleteTree(convert.project.root.resolve("build/classes"));
        if (originalRefmaps.isEmpty() && original.exit() != 0) convert.notes.add(
            "Names: the untouched mod did not build (" + Run.tail(original.log()).strip().lines().findFirst().orElse("") +
            "), so its mixin strings are renamed from the yarn -> Mojang table instead of its refmap."
        );
        // migrateMappings resolves types from the sources it is given: a testmod's classes extend main's, so every set is
        // renamed in one run over their files together (each set alone when two sets hold a file at the same path).
        Map<String, String> origin = new HashMap<>();
        boolean together = true;
        for (String root : roots) try (var walk = Files.walk(convert.project.root.resolve(root))) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = convert.project.root.resolve(root).relativize(file).toString().replace('\\', '/');
                if (origin.putIfAbsent(rel, root) != null) together = false;
            }
        }
        List<List<String>> runs = together ? List.of(roots) : roots.stream().map(List::of).toList();
        for (List<String> group : runs) {
            if (group.isEmpty()) continue;
            Path input = convert.project.state().resolve("yarn-to-mojang/in");
            Path output = convert.project.state().resolve("yarn-to-mojang/out");
            OwnCompile.deleteTree(input);
            OwnCompile.deleteTree(output);
            Map<String, String> from = new HashMap<>();
            for (String root : group) try (var walk = Files.walk(convert.project.root.resolve(root))) {
                for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String rel = convert.project.root.resolve(root).relativize(file).toString().replace('\\', '/');
                    Files.createDirectories(input.resolve(rel).getParent());
                    Files.copy(file, input.resolve(rel));
                    from.put(rel, root);
                }
            }
            var run = convert.build.gradle(
                List.of("migrateMappings", "--mappings", "net.minecraft:mappings:" + minecraft, "--input", input.toString(), "--output", output.toString()),
                "convert-yarn-to-mojang"
            );
            if (run.exit() != 0) throw new IOException(
                "Loom's migrateMappings could not move " + group + " from yarn's names to Mojang's: " + Run.tail(run.log())
            );
            Map<String, Integer> renamed = new TreeMap<>();
            try (var walk = Files.walk(output)) {
                for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String rel = output.relativize(file).toString().replace('\\', '/');
                    if (!from.containsKey(rel)) continue;
                    String target = from.get(rel) + "/" + rel;
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    if (!text.equals(convert.projectFiles.read(target))) {
                        convert.projectFiles.write(target, text);
                        renamed.merge(from.get(rel), 1, Integer::sum);
                    }
                }
            }
            for (var e : renamed.entrySet()) convert.applied.computeIfAbsent("yarn-to-mojang", k -> new ArrayList<>()).add(e.getKey() + " (" + e.getValue() + " files)");
        }
        Path tableFile = convert.project.state().resolve("yarn-to-mojang/names-" + minecraft + ".json");
        var names = new JavaTools(convert.build).run(
            "VersionMap",
            List.of(minecraft, minecraft, "--from-names", "yarn", "--to-names", "mojmap", "--names", "yarn", "--hierarchy", "--out", tableFile.toString()),
            "convert-yarn-names"
        );
        if (names.exit() != 0) throw new IOException("No yarn -> Mojang table for " + minecraft + ": " + Run.tail(names.log()));
        Map<String, Object> table = Json.read(tableFile);
        // What Mixin reads by name when the game runs, which migrateMappings leaves in yarn's names.
        MixinNames mixins = new MixinNames(table);
        Set<String> exact = new HashSet<>();
        if (!originalRefmaps.isEmpty()) {
            Path interFile = convert.project.state().resolve("yarn-to-mojang/intermediary-" + minecraft + ".json");
            var inter = new JavaTools(convert.build).run("VersionMap", List.of(minecraft, "--intermediary", interFile.toString()), "convert-yarn-intermediary");
            if (inter.exit() != 0) throw new IOException("No intermediary -> Mojang table for " + minecraft + ": " + Run.tail(inter.log()));
            refmapNames = new RefmapNames(Json.read(interFile));
            Map<String, Map<String, String>> byFile = new TreeMap<>();
            for (var e : originalRefmaps.entrySet()) {
                String outer = e.getKey().split("\\$")[0] + ".java";
                for (String root : roots) if (convert.projectFiles.exists(root + "/" + outer)) {
                    byFile.computeIfAbsent(root + "/" + outer, k -> new TreeMap<>()).putAll(e.getValue());
                    break;
                }
            }
            for (var e : byFile.entrySet()) {
                String text = convert.projectFiles.read(e.getKey()), out = refmapNames.apply(text, e.getValue());
                if (!out.equals(text)) convert.projectFiles.write(e.getKey(), out);
                exact.add(e.getKey());
            }
            convert.applied.computeIfAbsent("yarn-to-mojang", k -> new ArrayList<>()).add("mixin strings from the mod's refmap (" + exact.size() + " files)");
        }
        int mixinFiles = 0;
        List<String> every = new ArrayList<>(convert.projectFiles.javaFiles());
        every.addAll(convert.projectFiles.testJavaFiles());
        for (String file : every) {
            mixins.stringsDone = exact.contains(file);
            String text = convert.projectFiles.read(file), out = mixins.remap(mixins.javaNames(text));
            if (!out.equals(text)) {
                convert.projectFiles.write(file, out);
                mixinFiles++;
            }
        }
        for (String l : mixins.left) convert.projectFiles.leave("mixins", 0, l, "a mixin names a member whose overloads Mojang names differently, with no descriptor to choose");
        if (mixinFiles > 0) convert.applied.computeIfAbsent("yarn-to-mojang", k -> new ArrayList<>()).add("mixin names (" + mixinFiles + " files)");
        for (String widener : convert.access.accessWideners()) {
            String text = convert.projectFiles.read(widener), out = ConvertAccess.widenerToMojang(text, table);
            if (!out.equals(text)) {
                convert.projectFiles.write(widener, out);
                convert.applied.computeIfAbsent("yarn-to-mojang", k -> new ArrayList<>()).add(widener);
            }
        }
        String gradle = convert.projectFiles.read("build.gradle");
        String mojang = gradle.replaceAll("(?m)^([ \\t]*)mappings\\s*\\(?\\s*[\"']net\\.fabricmc:yarn:[^\"']*[\"']\\s*\\)?[ \\t]*$", "$1mappings loom.officialMojangMappings()");
        if (!mojang.equals(gradle)) convert.projectFiles.write("build.gradle", mojang);
        renamedFromYarn = true;
        convert.notes.add(
            "Names: the mod was in yarn's names; Loom's migrateMappings moved its sources to Mojang's names (" + minecraft +
            ") and the yarn -> Mojang table its access widener, before the loader moved."
        );
    }

    /** Mojang's client mappings and Fabric's intermediary mappings (tiny v2) of a game version, as text. */
    String[] gameAndIntermediaryMappings(String minecraft) throws Exception {
        var version = Json.object(Json.parse(Target.fetch(convert.build, convert.access.gameVersionUrl(minecraft))));
        var downloads = Json.object(version.get("downloads"));
        String mojang = Target.fetch(convert.build, Json.text(Json.object(downloads.get("client_mappings")), "url", ""));
        Path dir = Path.of(System.getProperty("user.home"), ".mmt", "minecraft", minecraft);
        Path tiny = dir.resolve("intermediary.tiny");
        if (!Files.isRegularFile(tiny)) {
            Files.createDirectories(dir);
            Path jar = dir.resolve("intermediary-v2.jar");
            PackCheck.download("https://maven.fabricmc.net/net/fabricmc/intermediary/" + minecraft + "/intermediary-" + minecraft + "-v2.jar", jar, null);
            try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
                var entry = zip.getEntry("mappings/mappings.tiny");
                if (entry == null) throw new IOException(jar + " has no mappings/mappings.tiny");
                try (var in = zip.getInputStream(entry)) {
                    Files.write(tiny, in.readAllBytes());
                }
            }
        }
        return new String[] { mojang, Files.readString(tiny) };
    }
}

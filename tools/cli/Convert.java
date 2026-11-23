import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

/**
 * Goal 4: moves a mod's source between Fabric and NeoForge on the same Minecraft version. Every
 * change comes from knowledge/loaders.json or from the fixed steps here (metadata, entrypoints,
 * build); nothing is guessed. What has no rule is named with its file and line and left as it was,
 * never dropped. Each run is one transaction that convert --undo restores byte for byte.
 */
final class Convert {

    final ConvertFiles projectFiles = new ConvertFiles(this);
    final ConvertAccess access = new ConvertAccess(this);
    final ConvertNames names = new ConvertNames(this);
    final ToNeoForge asNeoForge = new ToNeoForge(this);
    final NeoForgeBuild neoForgeBuild = new NeoForgeBuild(this);
    final ToFabric asFabric = new ToFabric(this);
    final FabricBuild fabricBuild = new FabricBuild(this);
    final BuildScript buildScript = new BuildScript(this);
    final NeoForgeBridge neoForgeBridge = new NeoForgeBridge(this);
    final FabricBridge fabricBridge = new FabricBridge(this);
    final ConvertBridges bridges = new ConvertBridges(this);
    final ConvertCompile compiler = new ConvertCompile(this);

    static final String FOLDER = "converts";

    final Build build;
    final ProjectModel project;
    Map<String, Object> rules;
    final List<String> removed = new ArrayList<>(), changed = new ArrayList<>(), notes = new ArrayList<>();
    final Map<String, List<String>> applied = new TreeMap<>();
    String modid = "", fabricId = "", unit = "\t";

    Convert(Build build) {
        this.build = build;
        this.project = build.project;
    }

    Map<String, Object> run(Mmt.Options options) throws Exception {
        return run(options, true);
    }

    /** Whether this conversion is compiled afterwards (not for self-test, --no-compile or --dry-run). */
    boolean compiling;

    /** compile false writes the conversion without compiling it (self-test, which has no network). */
    Map<String, Object> run(Mmt.Options options, boolean compile) throws Exception {
        compiling = compile && !options.has("no-compile") && !options.has("dry-run");
        if (options.values.containsKey("undo")) return PortJournal.undo(project, options.get("undo", ""), FOLDER);
        String to = options.get("to", "").toLowerCase(Locale.ROOT);
        String from = loader(project.root);
        if (from == null) throw new IOException(
            "convert found neither src/main/resources/fabric.mod.json nor src/main/resources/META-INF/neoforge.mods.toml in " +
            project.root +
            ". It converts one-module Fabric and NeoForge mods."
        );
        if (!Set.of("fabric", "neoforge").contains(to)) throw new IllegalArgumentException(
            "convert needs --to fabric or --to neoforge. Forge, Quilt and Paper are not converted yet; see mmt help convert."
        );
        // --names mojang on a yarn-named Fabric mod: only its names move (yarn is not published past 1.21.11).
        boolean namesOnly = to.equals(from) && options.get("names", "").equalsIgnoreCase("mojang");
        if (to.equals(from) && !namesOnly) throw new IllegalArgumentException("This project is already a " + from + " mod.");
        if (namesOnly && !from.equals("fabric")) throw new IllegalArgumentException("--names mojang renames a yarn-named Fabric mod; NeoForge mods are in Mojang's names already.");
        if (
            Files.isRegularFile(project.root.resolve("build.gradle.kts")) ||
            !Files.isRegularFile(project.root.resolve("build.gradle"))
        ) {
            throw new IOException(
                "convert rewrites a Groovy build.gradle; this project has " +
                (Files.isRegularFile(project.root.resolve("build.gradle.kts"))
                        ? "build.gradle.kts"
                        : "no build.gradle") +
                "."
            );
        }
        rules = Json.read(build.harness.resolve("knowledge/loaders.json"));
        Map<String, String> gradleProperties = BuildScript.properties(projectFiles.read("gradle.properties"));
        String minecraft = gradleProperties.getOrDefault("minecraft_version", "");
        // A NeoForge build that names only its NeoForge release: NeoForge numbers each release by its Minecraft version.
        if (minecraft.isEmpty() && from.equals("neoforge")) minecraft = minecraftOfNeoForge(
            gradleProperties.getOrDefault("neo_version", gradleProperties.getOrDefault("neoforge_version", ""))
        );
        if (minecraft.isEmpty()) throw new IOException(
            "convert reads the Minecraft version from minecraft_version in gradle.properties (or, for NeoForge, neo_version); there is none."
        );
        if (from.equals("neoforge") && !gradleProperties.containsKey("minecraft_version")) notes.add(
            "Minecraft " + minecraft + ", read from the NeoForge release in gradle.properties (NeoForge numbers its releases by the Minecraft version)."
        );
        gameVersion = minecraft;
        projectFiles.dryRun = options.has("dry-run");
        if (!projectFiles.dryRun) {
            projectFiles.transaction = project.state().resolve(FOLDER).resolve(Instant.now().toString().replace(':', '-'));
            Files.createDirectories(projectFiles.transaction.resolve("before"));
            projectFiles.saveInProgress();
        }
        if (namesOnly) {
            if (!names.yarnNames()) throw new IllegalArgumentException("This Fabric mod's build does not map the game with net.fabricmc:yarn; it is in Mojang's names already.");
            names.yarnToMojang(minecraft);
        } else if (from.equals("fabric")) asNeoForge.toNeoForge(minecraft);
        else asFabric.toFabric(minecraft);
        if (!buildScript.neutralDependencies.isEmpty()) notes.add(
            "Dependencies kept as they are, since they name no loader (libraries, or a mod's loader-neutral API): " +
            String.join(", ", buildScript.neutralDependencies)
        );
        if (!projectFiles.dryRun) PortJournal.finishTransaction(project, projectFiles.transaction, projectFiles.before, removed);
        Map<String, Object> result = Json.map(
            "from",
            from,
            "to",
            to,
            "minecraft",
            minecraft,
            "changed",
            changed,
            "removed",
            removed,
            "rulesApplied",
            applied,
            "left",
            projectFiles.left,
            "notes",
            notes,
            "dryRun",
            projectFiles.dryRun
        );
        if (!projectFiles.dryRun) {
            result.put("undoConvert", projectFiles.transaction.getFileName().toString());
            result.put("transaction", projectFiles.transaction.toString());
        }
        if (projectFiles.dryRun || !compile || options.has("no-compile")) {
            result.put("compiled", null);
            // A dry run succeeds when it ran; what it could not convert is in left.
            result.put("ok", projectFiles.dryRun || projectFiles.left.isEmpty());
        } else {
            compiler.compile(result);
            // The build repair at compile time wrote files after the undo record was made: it is made again.
            if (repairedBuild) PortJournal.finishTransaction(project, projectFiles.transaction, projectFiles.before, removed);
            // A yarn mod renamed on Fabric: its new refmaps must resolve every mixin to what the original's did.
            if (namesOnly && names.refmapNames != null && Boolean.TRUE.equals(result.get("compiled"))) {
                List<String> differ = names.refmapNames.check(names.originalRefmaps, RefmapNames.read(project.root.resolve("build/classes")));
                result.put("refmapCheck", Json.map("mixins", names.originalRefmaps.size(), "differ", differ));
                for (String d : differ) projectFiles.leave("mixins", 0, d, "the renamed mixin resolves other game members than the original's refmap did");
                if (!differ.isEmpty()) result.put("ok", false);
            }
        }
        result.put("details", text(result));
        return result;
    }

    /**
     * convert --dry-run from the command line: the dry run (which stages every change in memory) runs on a copy of the
     * project outside it, so even the release lookups it caches and its receipt stay out of the project's folder. The
     * copy is removed afterwards; the result lists what would change and be left.
     */
    static Map<String, Object> preview(Path root, Path harness, Mmt.Options options) throws Exception {
        Path copy = Files.createTempDirectory("mmt-convert-preview-");
        try {
            copyProject(root, copy);
            Convert convert = new Convert(new Build(new ProjectModel(copy), harness, "", new Mmt.Report(copy, "convert")));
            Map<String, Object> result = convert.run(options);
            result.put(
                "details",
                String.valueOf(result.get("details")) + "\nDry run: nothing in " + root + " was written; this lists what convert would change."
            );
            return result;
        } finally {
            OwnCompile.deleteTree(copy);
        }
    }

    /** The project's sources and build, without Git, Gradle's and mmt's own state (but with its cached release lookups). */
    static void copyProject(Path from, Path to) throws IOException {
        Set<String> skip = Set.of(".git", ".gradle", "build", ".runs", ".mmt", ".modaccel", "run", "out", "bin");
        try (var walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path rel = from.relativize(p);
                if (rel.getNameCount() > 0 && skip.contains(rel.getName(0).toString())) continue;
                Path target = to.resolve(rel.toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path metadata = from.resolve(".mmt/portable/metadata");
        if (Files.isDirectory(metadata)) try (var walk = Files.walk(metadata)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                Path target = to.resolve(".mmt/portable/metadata").resolve(metadata.relativize(p).toString());
                Files.createDirectories(target.getParent());
                Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * The Minecraft version a NeoForge release is for, from NeoForge's own numbering: 21.1.x is 1.21.1 and 20.4.x is
     * 1.20.4 (21.0.x is 1.21); from 26.1, the first three parts are the Minecraft version (26.1.2.48 is 26.1.2, 26.1.0.x
     * is 26.1). Empty when the text is no NeoForge release.
     */
    static String minecraftOfNeoForge(String neo) {
        Matcher m = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:\\.(\\d+))?").matcher(neo.strip());
        if (!m.find()) return "";
        int major = Integer.parseInt(m.group(1));
        if (major >= 26) {
            if (m.group(4) == null) return "";
            return major + "." + m.group(2) + (m.group(3).equals("0") ? "" : "." + m.group(3));
        }
        if (major < 20) return "";
        return "1." + major + (m.group(2).equals("0") ? "" : "." + m.group(2));
    }

    /** The Minecraft version being converted, for the rules written for a range of versions. */
    String gameVersion;

    /** The project's loader by its metadata file; null when it has neither. */
    static String loader(Path root) {
        if (Files.isRegularFile(root.resolve("src/main/resources/fabric.mod.json"))) return "fabric";
        if (Files.isRegularFile(root.resolve(NEO_META))) return "neoforge";
        if (Files.isRegularFile(root.resolve(NEO_TEMPLATE))) return "neoforge";
        return null;
    }

    static final String NEO_META = "src/main/resources/META-INF/neoforge.mods.toml";
    /** NeoForge's MDK keeps neoforge.mods.toml as a template its build expands with gradle.properties (generateModMetadata). */
    static final String NEO_TEMPLATE = "src/main/templates/META-INF/neoforge.mods.toml";

    void rewriteSources(String from, String to) throws Exception {
        for (String file : projectFiles.javaFiles()) {
            String text = projectFiles.read(file);
            LoaderRules.Result result = new LoaderRules(rules, from, to, modid, gameVersion).apply(text);
            for (String id : result.applied) applied.computeIfAbsent(id, k -> new ArrayList<>()).add(file);
            for (LoaderRules.Left l : result.left) projectFiles.leave(file, l.line(), l.what(), l.why());
            access.accessTransformers.addAll(result.accessTransformers);
            projectFiles.write(file, result.text);
        }
    }

    /** Names every use of the old loader that no rule changed: the class and where. */
    void leftovers(List<String> prefixes) throws Exception {
        for (String file : projectFiles.javaFiles()) {
            String text = dropUnusedImports(projectFiles.read(file), prefixes);
            projectFiles.write(file, text);
            Matcher m = Pattern.compile("(?m)^import\\s+(?:static\\s+)?([\\w.]+)").matcher(text);
            while (m.find()) for (String prefix : prefixes) if (m.group(1).startsWith(prefix)) projectFiles.leave(
                file,
                LoaderRules.line(text, m.start()),
                m.group(1),
                "no rule in knowledge/loaders.json moves this to the other loader yet"
            );
        }
    }

    /** Imports of the old loader's classes that nothing in the file uses any more: they go, since nothing needs them moved. */
    static String dropUnusedImports(String text, List<String> prefixes) {
        boolean[] code = LoaderRules.code(text);
        Matcher m = Pattern.compile("(?m)^import\\s+([\\w.]+)\\s*;[ \\t]*\\n?").matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String simple = name.substring(name.lastIndexOf('.') + 1);
            boolean old = prefixes.stream().anyMatch(name::startsWith);
            boolean used = false;
            if (old) {
                Matcher use = Pattern.compile("(?<![\\w$.])" + Pattern.quote(simple) + "(?![\\w$])").matcher(text);
                while (use.find()) if (code[use.start()] && (use.start() < m.start() || use.start() >= m.end())) {
                    used = true;
                    break;
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(old && !used ? "" : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The comment on the no-argument constructor convert keeps for such a class; the way back removes it. */
    static final String KEPT_CONSTRUCTOR = "/** The no-argument constructor Fabric makes this class's other entrypoints with (mmt convert). */";

    static final Pattern CLASS = Pattern.compile(
        "(?m)^([ \\t]*)((?:public\\s+|final\\s+|abstract\\s+)*)class\\s+(\\w+)([^{]*)\\{"
    );

    /** Where Fabric data generation writes: the datagen run's fabric-api.datagen.output-dir, else Loom's src/main/generated. */
    String datagenOutput = null;

    String neoforgePrefix(String minecraft) {
        String[] parts = minecraft.split("\\.");
        if (parts[0].equals("1")) return (
            (parts.length > 1 ? parts[1] : "0") + "." + (parts.length > 2 ? parts[2] : "0") + "."
        );
        return parts[0] + "." + (parts.length > 1 ? parts[1] : "0") + "." + (parts.length > 2 ? parts[2] : "0") + ".";
    }

    /**
     * A test source set that is a test mod of the loader the mod comes from (a NeoForge @Mod class, or a Fabric mod
     * initializer, which the loader starts beside the mod in a development game) is not converted: the other loader's
     * build makes no second mod of it. Its sources move out of the build to src/LOADER-SET/java, so the mod itself
     * compiles, and the test mod is listed as left.
     */
    void setAsideTestMods(String loader) throws Exception {
        Pattern entry = loader.equals("neoforge")
            ? Pattern.compile("(?m)^@Mod\\(")
            : Pattern.compile("\\bimplements\\s+[^{]*\\b(?:Client|DedicatedServer)?ModInitializer\\b");
        Map<String, List<String>> sets = new TreeMap<>();
        for (String f : projectFiles.testJavaFiles()) sets.computeIfAbsent(f.substring(0, f.indexOf("/java/") + "/java/".length()), k -> new ArrayList<>()).add(f);
        for (var set : sets.entrySet()) {
            String mod = null;
            for (String f : set.getValue()) if (mod == null && entry.matcher(projectFiles.read(f)).find()) mod = f;
            if (mod == null) continue;
            String aside = set.getKey().replaceFirst("^src/", "src/" + loader + "-");
            for (String f : set.getValue()) {
                projectFiles.write(aside + f.substring(set.getKey().length()), projectFiles.read(f));
                projectFiles.delete(f);
            }
            projectFiles.leave(
                mod,
                0,
                (loader.equals("neoforge") ? "NeoForge" : "Fabric") + " test mod in " + set.getKey(),
                "its " + set.getValue().size() + " source file(s) are kept in " + aside + " outside the build: the other loader's build makes no second mod of a test source set"
            );
        }
    }

    static final List<String> HOLDER_METHODS = List.of("getData", "hasData", "setData", "removeData", "getExistingData", "getExistingDataOrNull");

    /** The class implements the Fabric entrypoint interface again, with @Override on its entry method. */
    static String implementFabric(String out, String simple, String api, String method) {
        Matcher c = CLASS.matcher(out);
        while (c.find()) if (c.group(3).equals(simple)) {
            String heritage = c.group(4);
            String next = heritage.contains("implements")
                ? heritage.replaceFirst("implements\\s+", "implements " + api + ", ")
                : heritage.strip().isEmpty()
                    ? " implements " + api + " "
                    : " " + heritage.strip() + " implements " + api + " ";
            out =
                out.substring(0, c.start()) +
                c.group(1) +
                c.group(2) +
                "class " +
                simple +
                next +
                "{" +
                out.substring(c.end());
            break;
        }
        out = out.replaceFirst("(?m)^([ \\t]*)(public\\s+void\\s+" + method + "\\s*\\()", "$1@Override\n$1$2");
        return LoaderRules.addImport(out, "net.fabricmc.api." + api);
    }

    /**
     * The load conditions of every data file the mod ships (each source set's resources, generated ones too) moved to
     * the other loader's form (DataConditions): the loader a file is not written for ignores the other's key and would
     * load a file meant only for when another mod is present.
     */
    void convertDataConditions(boolean toFabric) throws Exception {
        Path src = project.root.resolve("src");
        if (!Files.isDirectory(src)) return;
        String key = toFabric ? DataConditions.NEOFORGE : DataConditions.FABRIC;
        DataConditions conditions = new DataConditions();
        List<Path> files;
        try (var walk = Files.walk(src)) {
            // Data maps are NeoForge's own files, which the NeoForge bridge reads in NeoForge's form on Fabric too.
            files = walk.filter(p -> p.toString().endsWith(".json") && p.toString().replace('\\', '/').contains("/resources/") && !p.toString().replace('\\', '/').contains("/data_maps/")).toList();
        }
        int converted = 0;
        for (Path p : files) {
            String rel = project.root.relativize(p).toString().replace('\\', '/');
            String text = projectFiles.read(rel);
            if (!text.contains(key)) continue;
            Object parsed;
            try {
                parsed = Json.parse(text);
            } catch (RuntimeException notJson) {
                continue;
            }
            if (!(parsed instanceof Map<?, ?>)) continue;
            Map<String, Object> out = conditions.convert(Json.object(parsed), toFabric, rel);
            if (out == null) continue;
            projectFiles.write(rel, pretty(out, "").replaceAll("\t", "  ") + "\n");
            converted++;
        }
        for (String l : conditions.left) projectFiles.leave(l.substring(0, l.indexOf(": ")), 0, l.substring(l.indexOf(": ") + 2), "the other loader reads only its own condition types");
        if (converted > 0) applied.computeIfAbsent("data-conditions", k -> new ArrayList<>()).add(converted + " data files");
    }

    /** Custom ingredients in the mod's recipe files, in the other loader's form (DataIngredients). */
    void convertDataIngredients(boolean toFabric) throws Exception {
        Path src = project.root.resolve("src");
        if (!Files.isDirectory(src)) return;
        List<Path> files;
        try (var walk = Files.walk(src)) {
            files = walk.filter(p -> p.toString().endsWith(".json") && p.toString().replace('\\', '/').matches(".*/resources/data/[^/]+/recipes?/.*")).toList();
        }
        int converted = 0;
        for (Path p : files) {
            String rel = project.root.relativize(p).toString().replace('\\', '/');
            String text = projectFiles.read(rel);
            if (!text.contains(toFabric ? "\"type\"" : "\"fabric:type\"")) continue;
            Object parsed;
            try {
                parsed = Json.parse(text);
            } catch (RuntimeException notJson) {
                continue;
            }
            if (!(parsed instanceof Map<?, ?>)) continue;
            Map<String, Object> out = DataIngredients.convert(Json.object(parsed), toFabric);
            if (out == null) continue;
            projectFiles.write(rel, pretty(out, "").replaceAll("\t", "  ") + "\n");
            converted++;
        }
        if (converted > 0) applied.computeIfAbsent("data-ingredients", k -> new ArrayList<>()).add(converted + " recipe files");
    }

    /** JSON with one entry per line and tab indents, the way fabric.mod.json files are written. */
    static String pretty(Object value, String indent) {
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) return "{}";
            StringBuilder out = new StringBuilder("{\n");
            int i = 0;
            for (var entry : map.entrySet()) {
                out
                    .append(indent)
                    .append('\t')
                    .append(Json.write(String.valueOf(entry.getKey())))
                    .append(": ")
                    .append(pretty(entry.getValue(), indent + "\t"));
                out.append(++i < map.size() ? ",\n" : "\n");
            }
            return out.append(indent).append('}').toString();
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) return "[]";
            StringBuilder out = new StringBuilder("[\n");
            for (int i = 0; i < list.size(); i++) out
                .append(indent)
                .append('\t')
                .append(pretty(list.get(i), indent + "\t"))
                .append(i + 1 < list.size() ? ",\n" : "\n");
            return out.append(indent).append(']').toString();
        }
        return Json.write(value);
    }

    String fabricLoader = "0.16.0";
    /** The Fabric access widener became NeoForge access transformer lines (or, going back, the reverse). */
    boolean widenerConverted;
    /** The access widener file (in src/main/resources) a NeoForge mod's transformers became, or null. */
    String fabricWidener;
    /** True when neoforge.mods.toml had ${property} placeholders the build expanded: fabric.mod.json gets the values. */
    boolean metadataExpanded;

    // ---- dependencies ------------------------------------------------------------------------------------

    static final Pattern COORDINATE = Pattern.compile("([\"'])([\\w.\\-${}]+):([\\w.\\-${}]+):([^\"':]+)((?::[^\"']*)?)\\1");

    /** Set when the compile step commented out a statement of the build that needs Loom. */
    boolean repairedBuild;

    static String text(Map<String, Object> result) {
        StringBuilder out = new StringBuilder();
        boolean dryRun = Boolean.TRUE.equals(result.get("dryRun"));
        out
            .append(dryRun ? "Would convert " : "Converted ")
            .append(result.get("from"))
            .append(" to ")
            .append(result.get("to"))
            .append(" (Minecraft ")
            .append(result.get("minecraft"))
            .append("): ");
        out
            .append(Json.array(result.get("changed")).size())
            .append(dryRun ? " file(s) would change, " : " file(s) changed, ")
            .append(Json.array(result.get("removed")).size())
            .append(dryRun ? " would be removed.\n" : " removed.\n");
        if (dryRun) {
            for (Object file : Json.array(result.get("changed"))) out.append("  change ").append(file).append('\n');
            for (Object file : Json.array(result.get("removed"))) out.append("  remove ").append(file).append('\n');
        }
        Map<String, Object> rulesApplied = Json.object(result.get("rulesApplied"));
        if (!rulesApplied.isEmpty()) out
            .append("Rules applied: ")
            .append(String.join(", ", rulesApplied.keySet()))
            .append('\n');
        for (Object note : Json.array(result.get("notes"))) out.append("Note: ").append(note).append('\n');
        List<Object> left = Json.array(result.get("left"));
        if (!left.isEmpty()) {
            out.append("Not converted (").append(left.size()).append("), named instead of dropped:\n");
            for (Object item : left) {
                var l = Json.object(item);
                Object line = l.get("line");
                out
                    .append("  ")
                    .append(l.get("file"))
                    .append(line instanceof Number n && n.intValue() > 0 ? ":" + n : "")
                    .append("  ")
                    .append(l.get("what"))
                    .append(": ")
                    .append(l.get("why"))
                    .append('\n');
            }
        }
        if (Boolean.TRUE.equals(result.get("compiled"))) out
            .append("The converted source compiles on ")
            .append(result.get("to"))
            .append(". Prove it works in the game next: mmt ui-check or world-check with --assert.\n");
        else if (Boolean.FALSE.equals(result.get("compiled"))) {
            out.append("The converted source does not compile yet.");
            if (result.get("compileError") != null) out.append(' ').append(result.get("compileError"));
            List<Object> errors = result.get("errors") instanceof List<?> e ? Json.array(e) : List.of();
            for (Object e : errors.subList(0, Math.min(15, errors.size()))) {
                var error = Json.object(e);
                out
                    .append("\n  ")
                    .append(error.get("file"))
                    .append(':')
                    .append(error.get("line"))
                    .append(": ")
                    .append(error.get("message"));
            }
            out.append('\n');
        }
        if (dryRun) out.append("Dry run: nothing was written. Run it without --dry-run to convert and compile.");
        else out.append("Undo: mmt convert --undo ").append(result.get("undoConvert"));
        return out.toString();
    }
}

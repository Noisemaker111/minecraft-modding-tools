import java.io.*;
import java.util.*;
import java.util.regex.*;

/** A Loom build script rewritten for ModDevGradle. */
final class NeoForgeBuild {

    final Convert convert;

    NeoForgeBuild(Convert convert) {
        this.convert = convert;
    }

    /** The Java files of the source sets whose name starts with test (src/test/java ...), which javaFiles() leaves out. */
    /** Whether src/test/java names the game's classes (net.minecraft, com.mojang). */
    boolean testsUseGame() throws IOException {
        for (String file : convert.projectFiles.testJavaFiles()) if (
            file.startsWith("src/test/") && Pattern.compile("(?m)^import\\s+(?:static\\s+)?(?:net\\.minecraft|com\\.mojang)\\.").matcher(convert.projectFiles.read(file)).find()
        ) return true;
        return false;
    }

    /** gradle.properties keys of a Fabric build that a NeoForge build has no use for: convert removes them. */
    static final List<String> FABRIC_PROPERTIES = List.of(
        "loader_version",
        "fabric_loader_version",
        "fabric_version",
        "fabric_api_version",
        "loom_version",
        "fabric_loom_version",
        "yarn_mappings",
        "modmenu_version"
    );

    /**
     * A Groovy map entry (key: project.NAME, or key: NAME) for a property the conversion removes, dropped with its comma:
     * such entries only passed a loader's version to the metadata the other loader's build no longer has.
     */
    static String dropPropertyEntries(String gradle, List<String> names, List<String> dropped) {
        for (String name : names) {
            String value = "(?:project\\.)?" + Pattern.quote(name) + "(?![\\w.])";
            for (String form : List.of(",\\s*(\\w+)\\s*:\\s*" + value, "(\\w+)\\s*:\\s*" + value + "\\s*,\\s*")) {
                Matcher m = Pattern.compile(form).matcher(gradle);
                StringBuilder out = new StringBuilder();
                while (m.find()) {
                    dropped.add(m.group(1) + ": " + name);
                    m.appendReplacement(out, "");
                }
                m.appendTail(out);
                gradle = out.toString();
            }
        }
        return gradle;
    }

    /**
     * The way back to NeoForge removes that block: on ModDevGradle it would exclude NeoForge itself, and the game's
     * classes with it, from every configuration.
     */
    static String dropNeoForgeExclusion(String gradle) {
        int at = gradle.indexOf(BuildScript.NEOFORGE_EXCLUSION);
        if (at < 0) return gradle;
        int end = gradle.indexOf("\n}\n", at + BuildScript.NEOFORGE_EXCLUSION.length());
        if (end < 0) return gradle;
        String body = gradle.substring(at + BuildScript.NEOFORGE_EXCLUSION.length(), end).strip();
        if (!body.equals("exclude group: 'net.neoforged', module: 'neoforge'")) return gradle;
        return gradle.substring(0, at) + gradle.substring(end + 3);
    }

    /** Those dependency statements, as the marked comments the NeoForge build keeps. */
    final List<String> bridgedDependencies = new ArrayList<>();

    /** The library a dependency line names when the Fabric bridge mirrors its API, else null. */
    String bridgeReplaces(String line) {
        if (convert.fabricBridge.fabricBridgePackage == null) return null;
        Matcher c = Convert.COORDINATE.matcher(line);
        if (!c.find()) return null;
        String coordinate = c.group(2) + ":" + c.group(3);
        for (var e : convert.fabricBridge.fabricBridgeReplaces.entrySet()) if (coordinate.equals(e.getKey())) return e.getValue();
        return null;
    }

    String latestNeoForge(String minecraft) throws Exception {
        String prefix = convert.neoforgePrefix(minecraft);
        List<String> found = new ArrayList<>();
        for (String v : BuildScript.metadataVersions(
            Target.fetch(convert.build, "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml")
        )) if (v.startsWith(prefix) && !v.contains("-")) found.add(v);
        if (found.isEmpty()) throw new IOException(
            "NeoForge has published no release for Minecraft " +
            minecraft +
            " (no net.neoforged:neoforge " +
            prefix +
            "x on maven.neoforged.net)."
        );
        return BuildScript.newest(found);
    }

    String runToNeoForge(String name, String body) {
        StringBuilder out = new StringBuilder();
        String in = convert.unit + convert.unit + convert.unit;
        for (String raw : body.lines().toList()) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("//")) continue;
            Matcher arg = Pattern.compile("^(?:vmArg|jvmArg)\\s*\\(?\\s*[\"'](.*)[\"']\\s*\\)?$").matcher(line);
            Matcher prop = Pattern.compile(
                "^property\\s*\\(?\\s*[\"']([^\"']+)[\"']\\s*,\\s*[\"']([^\"']*)[\"']\\s*\\)?$"
            ).matcher(line);
            Matcher dir = Pattern.compile("^runDir\\s*=?\\s*\\(?\\s*[\"']([^\"']+)[\"']\\s*\\)?$").matcher(line);
            Matcher program = Pattern.compile("^programArgs?\\s*\\(?\\s*[\"'](.*)[\"']\\s*\\)?$").matcher(line);
            if (line.matches("client\\(\\)|server\\(\\)")) out.append(in).append(line).append('\n');
            else if (line.matches("(?:name|configName)\\s*=?\\s*\\(?\\s*[\"'].*")) out
                .append(in)
                .append("ideName = ")
                .append(line.replaceFirst("^(?:name|configName)\\s*=?\\s*\\(?\\s*", "").replaceFirst("\\)$", ""))
                .append('\n');
            else if (line.matches("ideConfigGenerated\\b.*")) continue;
            else if (arg.find()) {
                Matcher d = Pattern.compile("^-D([^=]+)=(.*)$").matcher(arg.group(1));
                if (d.find()) out
                    .append(in)
                    .append("systemProperty '")
                    .append(d.group(1))
                    .append("', '")
                    .append(d.group(2))
                    .append("'\n");
                else out.append(in).append("jvmArgument '").append(arg.group(1)).append("'\n");
            } else if (prop.find()) out
                .append(in)
                .append("systemProperty '")
                .append(prop.group(1))
                .append("', '")
                .append(prop.group(2))
                .append("'\n");
            else if (dir.find()) out
                .append(in)
                .append("gameDirectory = project.file('")
                .append(dir.group(1))
                .append("')\n");
            else if (program.find()) out.append(in).append("programArgument '").append(program.group(1)).append("'\n");
            else convert.projectFiles.leave(
                "build.gradle",
                0,
                "loom run " + name + ": " + line,
                "this Loom run setting has no ModDevGradle counterpart in the rules"
            );
        }
        return out.toString();
    }

    static final Pattern LOOM_PLUGIN = Pattern.compile(
        "(?m)^([ \\t]*)id\\s*\\(?\\s*['\"](?:net\\.fabricmc\\.)?fabric-loom(?:-remap)?['\"]\\s*\\)?(?:\\s*version\\s*\\(?\\s*['\"][^'\"]*['\"]\\s*\\)?)?[ \\t]*$"
    );

    /**
     * Loom's dependency configurations in one statement as ModDevGradle's, wherever they are in it (implementation
     * include("x") nests one in another); a configuration call only, never part of a longer name (includedLibrary).
     */
    static String loomConfigurationsToNeoForge(String raw) {
        Matcher mod = Pattern.compile(
            "\\b(mod(?:Implementation|Api|CompileOnly|CompileOnlyApi|RuntimeOnly|LocalRuntime)|client(?:Implementation|Api|CompileOnly|RuntimeOnly)|include|localRuntime)(?=\\s*[(\"']|\\s+\\w)"
        ).matcher(raw);
        StringBuilder renamed = new StringBuilder();
        while (mod.find()) mod.appendReplacement(
            renamed,
            switch (mod.group(1)) {
                case "modImplementation" -> "implementation";
                case "modApi" -> "api";
                case "modCompileOnly", "modCompileOnlyApi" -> "compileOnly";
                case "modRuntimeOnly", "modLocalRuntime", "localRuntime", "clientRuntimeOnly" -> "runtimeOnly";
                case "clientImplementation" -> "implementation";
                case "clientApi" -> "api";
                case "clientCompileOnly" -> "compileOnly";
                default -> "jarJar";
            }
        );
        mod.appendTail(renamed);
        return renamed.toString();
    }

    /** A top-level build statement that configures Loom through its extensions: loom.runs { }, delete loom.runs.x.runDirectory. */
    static boolean loomStatement(String statement) {
        String first = statement.strip();
        return first.matches("(?s)(?:loom|fabricApi)\\s*\\.\\s*\\w.*") || first.matches("(?s)\\w+\\s+(?:loom|fabricApi)\\.\\w.*");
    }

    /** The oldest Gradle ModDevGradle applies on: its own check refuses anything older. */
    static final String MODDEV_GRADLE_MIN = "8.8";

    void buildToNeoForge(String minecraft) throws Exception {
        String gradle = dropLoomTypes(convert.projectFiles.read("build.gradle"));
        Matcher plugin = LOOM_PLUGIN.matcher(gradle);
        if (!plugin.find()) throw new IOException(
            "build.gradle does not apply the Fabric Loom plugin with id 'fabric-loom' or 'net.fabricmc.fabric-loom' in its plugins block."
        );
        String neo = latestNeoForge(minecraft);
        String mdg = convert.buildScript.stablePlugin("https://maven.neoforged.net/releases", "net.neoforged", "moddev-gradle");
        // ModDevGradle refuses to apply below Gradle 8.8 ("To use the NeoForge plugin, please use at least Gradle 8.8"),
        // which some of its releases do not state in their published metadata.
        String wrapper = convert.project.wrapperVersion();
        if (wrapper != null && Versions.compare(wrapper, MODDEV_GRADLE_MIN) < 0) convert.buildScript.upgradeWrapper(MODDEV_GRADLE_MIN, "ModDevGradle " + mdg);
        gradle =
            gradle.substring(0, plugin.start()) +
            plugin.group(1) +
            "id 'net.neoforged.moddev' version '" +
            mdg +
            "'" +
            gradle.substring(plugin.end());
        boolean split = false;
        StringBuilder runs = new StringBuilder();
        runs
            .append(convert.unit)
            .append(convert.unit)
            .append("client {\n")
            .append(convert.unit)
            .append(convert.unit)
            .append(convert.unit)
            .append("client()\n")
            .append(convert.unit)
            .append(convert.unit)
            .append("}\n");
        runs
            .append(convert.unit)
            .append(convert.unit)
            .append("server {\n")
            .append(convert.unit)
            .append(convert.unit)
            .append(convert.unit)
            .append("server()\n")
            .append(convert.unit)
            .append(convert.unit)
            .append("}\n");
        // Loom configuration written as top-level statements (loom.runs { }, loom.runs.configureEach { }, delete
        // loom.runs.x.runDirectory, fabricApi.configureTests { }): Loom's own extensions, gone with the plugin.
        gradle = convert.buildScript.dropStatements(gradle, NeoForgeBuild::loomStatement, "Fabric Loom's own configuration, which ModDevGradle has no counterpart for");
        // A build can configure Loom in more than one loom block; every one is read below, the first in place.
        int[] loom = BuildScript.block(gradle, "loom");
        for (int[] more = loom == null ? null : BuildScript.nextBlock(gradle, "loom", loom[1]); more != null; more = BuildScript.nextBlock(gradle, "loom", loom[1])) {
            String extra = gradle.substring(more[2] + 1, more[3] - 1);
            gradle = gradle.substring(0, more[0]) + gradle.substring(more[1]);
            gradle = gradle.substring(0, loom[3] - 1) + extra + gradle.substring(loom[3] - 1);
            loom = BuildScript.block(gradle, "loom");
        }
        int insertAt;
        if (loom != null) {
            String body = gradle.substring(loom[2] + 1, loom[3] - 1);
            Map<String, String> parts = BuildScript.children(body);
            for (var part : parts.entrySet()) {
                if (part.getKey().equals("runs")) {
                    for (var run : BuildScript.children(part.getValue()).entrySet()) {
                        if (run.getValue().contains("fabric-api.datagen")) {
                            Matcher dir = Pattern.compile("fabric-api\\.datagen\\.output-dir=([^\"'\\s]+)").matcher(
                                run.getValue()
                            );
                            if (convert.datagenOutput != null && dir.find()) convert.datagenOutput = dir
                                .group(1)
                                .replaceFirst("^\\$\\{(?:project\\.)?projectDir}/", "");
                            if (convert.datagenOutput == null) convert.projectFiles.leave(
                                "build.gradle",
                                0,
                                "loom run " + run.getKey(),
                                "it runs Fabric data generation, which was not converted"
                            );
                            continue;
                        }
                        if (Set.of("client", "server").contains(run.getKey())) continue;
                        runs
                            .append(convert.unit)
                            .append(convert.unit)
                            .append(run.getKey())
                            .append(" {\n")
                            .append(runToNeoForge(run.getKey(), run.getValue()))
                            .append(convert.unit)
                            .append(convert.unit)
                            .append("}\n");
                    }
                } else if (!part.getKey().equals("mods")) convert.projectFiles.leave(
                    "build.gradle",
                    0,
                    "loom " + part.getKey() + " block",
                    "no ModDevGradle counterpart in the rules"
                );
            }
            String rest = BuildScript.withoutChildren(body);
            for (String raw : rest.lines().toList()) {
                String line = raw.strip().replaceAll("[\\w\"'${}]+\\s*\\{\\s*\\}", "").strip();
                if (line.isEmpty() || line.startsWith("//") || line.equals("}")) continue;
                if (line.startsWith("splitEnvironmentSourceSets")) split = true;
                else if (line.startsWith("accessWidenerPath") && convert.widenerConverted) continue;
                else if (line.startsWith("accessWidenerPath")) convert.projectFiles.leave(
                    "build.gradle",
                    0,
                    line,
                    "access wideners are not converted to NeoForge access transformers yet"
                );
                else convert.projectFiles.leave("build.gradle", 0, "loom: " + line, "no ModDevGradle counterpart in the rules");
            }
            gradle = gradle.substring(0, loom[0]) + gradle.substring(loom[1]);
            insertAt = loom[0];
        } else {
            int[] deps = BuildScript.block(gradle, "dependencies");
            insertAt = deps == null ? gradle.length() : deps[0];
        }
        int[] fabricApi = BuildScript.block(gradle, "fabricApi");
        if (fabricApi != null) {
            if (gradle.substring(fabricApi[0], fabricApi[1]).contains("DataGeneration") && convert.datagenOutput == null) convert.projectFiles.leave(
                "build.gradle",
                0,
                "fabricApi { configureDataGeneration }",
                "Fabric data generation was not converted"
            );
            if (fabricApi[0] < insertAt) insertAt -= fabricApi[1] - fabricApi[0];
            gradle = gradle.substring(0, fabricApi[0]) + gradle.substring(fabricApi[1]);
        }
        if (convert.datagenOutput != null) {
            runs
                .append(convert.unit)
                .append(convert.unit)
                .append("clientData {\n")
                .append(convert.unit)
                .append(convert.unit)
                .append(convert.unit)
                .append("clientData()\n");
            runs
                .append(convert.unit)
                .append(convert.unit)
                .append(convert.unit)
                .append("programArguments.addAll('--mod', '")
                .append(convert.modid)
                .append("', '--all', '--output', file('")
                .append(convert.datagenOutput)
                .append("').absolutePath, '--existing', file('src/main/resources').absolutePath)\n");
            runs.append(convert.unit).append(convert.unit).append("}\n");
        }
        StringBuilder neoForge = new StringBuilder("neoForge {\n");
        neoForge
            .append(convert.unit)
            .append("version = project.neo_version\n\n")
            .append(convert.unit)
            .append("runs {\n")
            .append(runs)
            .append(convert.unit)
            .append("}\n\n");
        neoForge
            .append(convert.unit)
            .append("mods {\n")
            .append(convert.unit)
            .append(convert.unit)
            .append(LoaderRules.quote(convert.modid))
            .append(" {\n")
            .append(convert.unit)
            .append(convert.unit)
            .append(convert.unit)
            .append("sourceSet sourceSets.main\n")
            .append(convert.unit)
            .append(convert.unit)
            .append("}\n")
            .append(convert.unit)
            .append("}\n");
        // ModDevGradle puts the game on the main source set only (Loom and NeoGradle on every one): tests that use the
        // game get it as ModDevGradle provides, through addModdingDependenciesTo.
        if (testsUseGame()) {
            neoForge.append('\n').append(convert.unit).append("addModdingDependenciesTo(sourceSets.test)\n");
            convert.notes.add("Build: the mod's tests use the game's classes, so ModDevGradle adds them to the test source set (addModdingDependenciesTo).");
        }
        // What Fabric injects into game classes (FabricItem on Item ...): the Fabric bridge's mixins add its copies when the
        // game runs, and ModDevGradle's interface injection adds them to the game the mod compiles against.
        if (!convert.bridges.bridgeInjected.isEmpty()) {
            convert.projectFiles.write(ConvertBridges.INJECTED_INTERFACES, Convert.pretty(new LinkedHashMap<String, Object>(convert.bridges.bridgeInjected), "") + "\n");
            neoForge
                .append('\n')
                .append(convert.unit)
                .append("interfaceInjectionData {\n")
                .append(convert.unit)
                .append(convert.unit)
                .append("from(file('")
                .append(ConvertBridges.INJECTED_INTERFACES)
                .append("'))\n")
                .append(convert.unit)
                .append("}\n");
            convert.notes.add("Build: ModDevGradle injects the Fabric bridge's interfaces into " + String.join(", ", convert.bridges.bridgeInjected.keySet()) + " (" + ConvertBridges.INJECTED_INTERFACES + ").");
        }
        neoForge.append("}\n");
        if (convert.datagenOutput != null) neoForge
            .append(
                "\n// Generated data (runClientData) ships with the mod's own resources.\nsourceSets.main.resources {\n"
            )
            .append(convert.unit)
            .append("srcDir '")
            .append(convert.datagenOutput)
            .append("'\n")
            .append(convert.unit)
            .append("exclude '.cache'\n}\n");
        splitClient = split;
        gradle =
            gradle.substring(0, insertAt) +
            neoForge +
            (insertAt < gradle.length() && gradle.charAt(insertAt) != '\n' ? "\n" : "") +
            gradle.substring(insertAt);
        int[] deps = BuildScript.block(gradle, "dependencies");
        if (deps != null) {
            // Whole statements, not lines: a dependency with a closure ({ exclude ... }) is one statement.
            List<String> kept = new ArrayList<>();
            boolean dropping = false;
            for (String raw : BuildScript.statements(gradle.substring(deps[2] + 1, deps[3] - 1))) {
                // What the statement depends on is in its first line; its closure (exclude(group: "net.fabricmc.fabric-api")) is not.
                String line = raw.strip().lines().findFirst().orElse("");
                boolean drop =
                    line.matches("(?s)(?:minecraft|mappings)\\b.*") ||
                    line.contains("net.fabricmc:fabric-loader") ||
                    line.contains("net.fabricmc.fabric-api") ||
                    line.contains("fabricApi.module") ||
                    line.contains("com.terraformersmc:modmenu") ||
                    // Loom's own configurations for its production run tasks, which go with Loom.
                    line.matches("(?s)productionRuntime(?:Mods)?\\b.*");
                if (line.matches("(?s)productionRuntime(?:Mods)?\\b.*")) convert.notes.add(
                    "Build: removed `" + line + "` (Loom's production run configuration)."
                );
                // A Fabric-only library whose API the Fabric bridge mirrors on NeoForge (bridges[].replaces).
                String replaced = bridgeReplaces(line);
                if (replaced != null) {
                    drop = true;
                    // Kept as a comment the way back to Fabric turns into the dependency again.
                    for (String part : raw.strip().split("\n")) bridgedDependencies.add(convert.unit + BuildScript.BRIDGED_DEPENDENCY + part.strip() + "\n");
                    convert.notes.add("Build: removed `" + line + "`; the Fabric bridge mirrors its API (" + replaced + ") on NeoForge's.");
                    convert.applied.computeIfAbsent("fabric-bridge-dependency", k -> new ArrayList<>()).add("build.gradle: " + replaced);
                }
                if (drop) {
                    // A comment that only introduced the dropped statements goes with them.
                    while (
                        !dropping &&
                        kept.size() >= 2 &&
                        kept.get(kept.size() - 1).strip().startsWith("//") &&
                        kept.get(kept.size() - 2).isBlank()
                    ) kept.remove(kept.size() - 1);
                    dropping = true;
                    continue;
                }
                dropping = false;
                if (line.startsWith("//") || line.startsWith("/*")) {
                    kept.add(raw);
                    continue;
                }
                // A Fabric build of another mod moves to its NeoForge build; Loom's own configurations are renamed after.
                raw = convert.buildScript.dependencyLine(raw, "fabric", "neoforge", BuildScript.repositories(gradle), false);
                kept.add(loomConfigurationsToNeoForge(raw));
            }
            String inner = String.join("", kept);
            if (!inner.endsWith("\n")) inner += "\n";
            inner += String.join("", bridgedDependencies);
            gradle =
                gradle.substring(0, deps[2] + 1) + "\n" + inner.replaceAll("^\\n+", "") + gradle.substring(deps[3] - 1);
        }
        gradle = gradle
            .replace("filesMatching(\"fabric.mod.json\")", "filesMatching(\"META-INF/neoforge.mods.toml\")")
            .replace("filesMatching('fabric.mod.json')", "filesMatching('META-INF/neoforge.mods.toml')");
        gradle = gradle.replaceAll("\\bremapJar\\b", "jar").replaceAll("\\bremapSourcesJar\\b", "sourcesJar");
        // The properties the Fabric build had and NeoForge's has not: a map entry that only passed one to the metadata goes.
        List<String> droppedEntries = new ArrayList<>();
        gradle = dropPropertyEntries(gradle, FABRIC_PROPERTIES, droppedEntries);
        if (!droppedEntries.isEmpty()) convert.notes.add("Build: removed " + String.join(", ", droppedEntries) + " (Fabric's properties, which only filled fabric.mod.json).");
        gradle = BuildScript.publishLoader(gradle, "fabric", "neoforge");
        gradle = convert.buildScript.dropPluginCode(gradle, "net.fabricmc.loom");
        String withExclusion = gradle;
        gradle = dropNeoForgeExclusion(gradle);
        if (!gradle.equals(withExclusion)) convert.notes.add("Build: removed the NeoForge exclusion the way to Fabric added for kept dependencies.");
        // A line of code (not a comment) that still names Fabric.
        boolean[] gradleCode = LoaderRules.code(gradle);
        for (Matcher named = Pattern.compile("(?m)^.*fabricmc.*$").matcher(gradle); named.find(); ) {
            int at = gradle.indexOf("fabricmc", named.start());
            if (gradleCode[at]) convert.projectFiles.leave("build.gradle", 0, named.group().strip(), "still names Fabric; no rule changes it");
        }
        // Loom puts the game on every source set, ModDevGradle on main only: each other set the build declares gets it,
        // at the end of the build, where every set exists (a build may declare its sets after the neoForge block).
        List<String> sets = declaredSourceSets(gradle);
        sets.removeAll(List.of("main", "test"));
        // NeoForge has no split client source set: the client set's sources (wherever the build put them) build with
        // main, at the end of the build, where the build's own source set settings are final.
        if (splitClient) {
            sets.remove("client");
            // What used the client classes uses main's now, which holds them.
            gradle = gradle.replaceAll("(?<![\\w.])(sourceSets\\.)?client\\.output\\b", "$1main.output /* client.output on Fabric */");
            gradle = gradle.stripTrailing() + "\n" + SPLIT_CLIENT;
        }
        if (!sets.isEmpty()) {
            StringBuilder extra = new StringBuilder("\n// Loom gave every source set the game's classes; ModDevGradle gives them to main, and to these as asked.\nneoForge {\n");
            for (String set : sets) extra.append("    addModdingDependenciesTo(sourceSets.").append(set).append(")\n");
            gradle = gradle.stripTrailing() + "\n" + extra + "}\n";
            convert.notes.add("Build: the source sets " + String.join(", ", sets) + " get the game's classes from ModDevGradle (addModdingDependenciesTo), as Loom gave them.");
        }
        convert.projectFiles.write("build.gradle", gradle.replaceAll("\\n{3,}", "\n\n"));
        Map<String, String> props = new LinkedHashMap<>();
        for (String key : FABRIC_PROPERTIES) props.put(key, null);
        props.put("neo_version", neo);
        convert.projectFiles.write("gradle.properties", BuildScript.setProperties(convert.projectFiles.read("gradle.properties"), props));
        String settings = convert.projectFiles.read("settings.gradle");
        if (settings.contains("pluginManagement") && !settings.contains("gradlePluginPortal()")) {
            convert.projectFiles.write(
                "settings.gradle",
                settings.replaceFirst(
                    "(pluginManagement\\s*\\{\\s*repositories\\s*\\{)",
                    "$1\n" + convert.unit + convert.unit + "gradlePluginPortal()"
                )
            );
        }
        convert.notes.add(
            "Build: ModDevGradle " +
            mdg +
            " with NeoForge " +
            neo +
            " (the newest release for Minecraft " +
            minecraft +
            ")."
        );
    }

    /**
     * Fabric Loom's own types imported into the build script (net.fabricmc.loom...): the imports go, and so does every
     * top-level statement that names one of them (a task of a Loom type has no ModDevGradle counterpart).
     */
    String dropLoomTypes(String gradle) {
        List<String> removed = new ArrayList<>();
        String out = dropLoomTypes(gradle, removed);
        for (String r : removed) convert.notes.add("Build: removed `" + r + "` (Fabric Loom's own types).");
        return out;
    }

    static String dropLoomTypes(String gradle, List<String> removed) {
        Matcher imports = Pattern.compile("(?m)^import\\s+net\\.fabricmc\\.loom\\.[\\w.]*?(\\w+)\\s*;?[ \\t]*$").matcher(gradle);
        List<String> types = new ArrayList<>();
        while (imports.find()) types.add(imports.group(1));
        if (types.isEmpty()) return gradle;
        Pattern named = Pattern.compile("\\b(?:" + String.join("|", types) + ")\\b");
        return BuildScript.dropStatements(gradle, statement -> named.matcher(statement).find(), removed);
    }

    /**
     * The source sets a Gradle build declares: each name at the top level of a sourceSets { } block, and each
     * sourceSets.create / register / maybeCreate. In the order declared.
     */
    static List<String> declaredSourceSets(String gradle) {
        Set<String> out = new LinkedHashSet<>();
        String code = gradle.replaceAll("(?m)//.*$", "");
        Matcher block = Pattern.compile("(?m)^\\s*sourceSets\\s*\\{").matcher(code);
        while (block.find()) {
            int depth = 1;
            int i = block.end();
            int lineStart = i;
            for (; i < code.length() && depth > 0; i++) {
                char c = code.charAt(i);
                if (c == '\n') lineStart = i + 1;
                if (c == '{') depth++;
                if (c == '}') depth--;
                // A name that starts a line at depth 1: "client {" or a bare "codeGen".
                if (depth == 1 && c == '\n') {
                    int lineEnd = code.indexOf('\n', lineStart) < 0 ? code.length() : code.indexOf('\n', lineStart);
                    Matcher name = Pattern.compile("^\\s*([A-Za-z_]\\w*)\\s*(\\{|\\r?$)").matcher(code.substring(lineStart, lineEnd));
                    if (name.find() && !name.group(1).matches("java|resources|output|compileClasspath|runtimeClasspath")) out.add(name.group(1));
                }
            }
        }
        Matcher made = Pattern.compile("sourceSets\\.(?:create|register|maybeCreate)\\(\\s*['\"](\\w+)['\"]").matcher(code);
        while (made.find()) out.add(made.group(1));
        return new ArrayList<>(out);
    }

    /** Whether the Fabric build split its client sources off (Loom's splitEnvironmentSourceSets). */
    boolean splitClient;

    static final String SPLIT_CLIENT =
        "\n// NeoForge has no split client source set: the client sources build with the rest.\n" +
        "if (sourceSets.findByName('client') != null) {\n" +
        "    sourceSets.main.java.srcDirs += sourceSets.client.java.srcDirs\n" +
        "    sourceSets.main.resources.srcDirs += sourceSets.client.resources.srcDirs\n" +
        "    sourceSets.client.java.srcDirs = []\n" +
        "    sourceSets.client.resources.srcDirs = []\n" +
        "    configurations.implementation.extendsFrom(configurations.clientImplementation)\n" +
        "    configurations.compileOnly.extendsFrom(configurations.clientCompileOnly)\n" +
        "    configurations.runtimeOnly.extendsFrom(configurations.clientRuntimeOnly)\n" +
        "} else {\n" +
        "    sourceSets.main.java.srcDir 'src/client/java'\n" +
        "    sourceSets.main.resources.srcDir 'src/client/resources'\n" +
        "}\n// End of the client sources.\n";
}

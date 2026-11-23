import java.io.*;
import java.util.*;
import java.util.regex.*;

/** A ModDevGradle or NeoGradle build script rewritten for Loom. */
final class FabricBuild {

    final Convert convert;

    FabricBuild(Convert convert) {
        this.convert = convert;
    }

    /** NightConfig as NeoForge 21.1.250's published module metadata requires it (com.electronwill.night-config core and toml). */
    static final String NIGHT_CONFIG = "3.8.3";

    static final Pattern MDG_PLUGIN = Pattern.compile(
        "(?m)^([ \\t]*)id\\s*\\(?\\s*['\"]net\\.neoforged\\.moddev['\"]\\s*\\)?(?:\\s*version\\s*\\(?\\s*['\"][^'\"]*['\"]\\s*\\)?)?[ \\t]*$"
    );

    static final Pattern NEOGRADLE_PLUGIN = Pattern.compile(
        "(?m)^([ \\t]*)id\\s*\\(?\\s*['\"]net\\.neoforged\\.gradle\\.userdev['\"]\\s*\\)?(?:\\s*version\\s*\\(?\\s*['\"][^'\"]*['\"]\\s*\\)?)?[ \\t]*$"
    );

    boolean parchmentRepository;

    /** A Groovy value as text inside a GString: 'x' or "x" is x, anything else (project.v, v) is ${...}. */
    static String groovyValue(String expression) {
        String e = expression.strip().replaceAll("\\s*//.*$", "");
        if (e.matches("'[^']*'|\"[^\"$]*\"")) return e.substring(1, e.length() - 1);
        return "${" + e + "}";
    }

    String addRepository(String gradle, String url) {
        int[] repos = BuildScript.block(gradle, "repositories");
        String repo = convert.unit + "maven { url = \"" + url + "\" }\n";
        if (repos != null) return gradle.substring(0, repos[2] + 1) + "\n" + repo + gradle.substring(repos[2] + 1).replaceFirst("^\\n", "");
        return gradle.replaceFirst("(?m)^dependencies\\s*\\{", Matcher.quoteReplacement("repositories {\n" + repo + "}\n\ndependencies {"));
    }

    /**
     * Removes the top-level build statements that only served NeoForge's metadata: NeoForge's MDK template expansion
     * (generateModMetadata, src/main/templates), the neoForge extension's own calls (neoForge.ideSyncTask ...), and, when
     * fabric.mod.json was written with the expanded values, a processResources expand of neoforge.mods.toml.
     */
    String dropMetadataExpansion(String gradle) {
        return convert.buildScript.dropStatements(
            gradle,
            statement ->
                statement.contains("generateModMetadata") ||
                statement.contains("src/main/templates") ||
                statement.strip().startsWith("neoForge.") ||
                (convert.metadataExpanded && statement.contains("neoforge.mods.toml") && statement.contains("expand")),
            "NeoForge metadata or ModDevGradle only"
        );
    }

    /**
     * A mod dependency held in a variable (def ctDep = "...:CraftTweaker-fabric-...") and named by it (compileOnly ctDep):
     * the configurations that name a variable whose value convert moved to the Fabric build become Loom's remapping ones.
     */
    static String remapVariableDependencies(String body) {
        Matcher def = Pattern.compile("(?m)^\\s*def\\s+(\\w+)\\s*=\\s*[\"']([^\"']+)[\"']").matcher(body);
        String out = body;
        while (def.find()) {
            String coordinate = def.group(2);
            // On the way to Fabric, a variable whose coordinate names a Fabric build holds a Fabric mod.
            if (!coordinate.toLowerCase(Locale.ROOT).contains("fabric")) continue;
            out = Pattern.compile("(?m)^(\\s*)(implementation|compileOnly|runtimeOnly|localRuntime|api)(\\s*\\(?\\s*" + Pattern.quote(def.group(1)) + "\\b)")
                .matcher(out)
                .replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "mod" + Character.toUpperCase(m.group(2).charAt(0)) + m.group(2).substring(1) + m.group(3)));
        }
        return out;
    }

    void buildToFabric(String minecraft, boolean menu) throws Exception {
        // Before 26.1 the game is obfuscated: Fabric's own template builds those versions with the remapping Loom
        // (net.fabricmc.fabric-loom-remap) on Mojang's names (loom.officialMojangMappings()), the names NeoForge mods use.
        boolean remap = Versions.compare(minecraft, Route.FIRST_UNOBFUSCATED) < 0;
        String gradle = convert.projectFiles.read("build.gradle");
        // Dependencies the Fabric bridge stood in for on NeoForge come back as they were.
        List<String> restored = new ArrayList<>();
        Matcher kept = Pattern.compile("(?m)^[ \\t]*" + Pattern.quote(BuildScript.BRIDGED_DEPENDENCY) + "(.*)\\R?").matcher(gradle);
        while (kept.find()) restored.add(kept.group(1));
        gradle = kept.reset().replaceAll("");
        Matcher plugin = MDG_PLUGIN.matcher(gradle);
        boolean neoGradle = false;
        if (!plugin.find()) {
            plugin = NEOGRADLE_PLUGIN.matcher(gradle);
            neoGradle = plugin.find();
            if (!neoGradle) throw new IOException(
                "build.gradle applies neither ModDevGradle (id 'net.neoforged.moddev') nor NeoGradle (id 'net.neoforged.gradle.userdev') in its plugins block."
            );
        }
        String loom = convert.buildScript.stablePlugin("https://maven.fabricmc.net", "net.fabricmc", "fabric-loom");
        String parchment = null;
        if (neoGradle) {
            // NeoGradle's top-level blocks: runs and minecraft go (Loom makes its own client and server runs), and its
            // parchment subsystem becomes a Loom mappings layer.
            for (String name : List.of("subsystems", "runs", "minecraft", "jarJar")) {
                int[] b = BuildScript.block(gradle, name);
                if (b == null) continue;
                String body = gradle.substring(b[2] + 1, b[3] - 1);
                if (name.equals("subsystems")) parchment = BuildScript.children(body).get("parchment");
                else if (name.equals("runs")) convert.notes.add("NeoGradle's runs block is replaced by Loom's own client and server runs.");
                // The usual file (META-INF/accesstransformer.cfg) becomes the access widener; another file is named.
                else if (name.equals("minecraft") && body.contains("accessTransformers") && !body.contains(ConvertAccess.AT_FILE)) convert.projectFiles.leave(
                    "build.gradle",
                    0,
                    "minecraft accessTransformers",
                    "access transformers are not converted to Fabric access wideners yet"
                );
                gradle = gradle.substring(0, b[0]) + gradle.substring(b[1]);
            }
        } else {
            int[] neoBlock = BuildScript.block(gradle, "neoForge");
            if (neoBlock != null) parchment = BuildScript.children(gradle.substring(neoBlock[2] + 1, neoBlock[3] - 1)).get("parchment");
        }
        String mappings = null;
        if (remap) {
            mappings = "mappings loom.officialMojangMappings()";
            if (parchment != null) {
                Matcher mc = Pattern.compile("minecraftVersion\\s*=?\\s*(.+)").matcher(parchment);
                Matcher mv = Pattern.compile("mappingsVersion\\s*=?\\s*(.+)").matcher(parchment);
                if (mc.find() && mv.find()) {
                    String a = groovyValue(mc.group(1)), b = groovyValue(mv.group(1));
                    mappings =
                        "mappings loom.layered() {\n" +
                        convert.unit +
                        convert.unit +
                        "officialMojangMappings()\n" +
                        convert.unit +
                        convert.unit +
                        "parchment(\"org.parchmentmc.data:parchment-" +
                        a +
                        ":" +
                        b +
                        "@zip\")\n" +
                        convert.unit +
                        "}";
                    parchmentRepository = true;
                }
            }
        } else if (parchment != null) convert.notes.add(
            "Parchment only adds parameter names; Loom on Minecraft 26.1 and later builds on the game's own names, so it is dropped."
        );
        gradle = dropMetadataExpansion(gradle);
        convert.fabricLoader = Json.text(
            Json.object(
                Json.array(Json.parse(Target.fetch(convert.build, "https://meta.fabricmc.net/v2/versions/loader")))
                    .stream()
                    .filter(v -> Boolean.TRUE.equals(Json.object(v).get("stable")))
                    .findFirst()
                    .orElseThrow()
            ),
            "version",
            ""
        );
        List<String> apis = new ArrayList<>();
        for (String v : BuildScript.metadataVersions(
            Target.fetch(convert.build, "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml")
        )) if (v.endsWith("+" + minecraft) || v.endsWith("+" + minecraft.replaceFirst("\\.0$", ""))) apis.add(v);
        if (apis.isEmpty()) throw new IOException("Fabric API has no release for Minecraft " + minecraft + ".");
        String api = BuildScript.newest(apis);
        gradle =
            gradle.substring(0, plugin.start()) +
            plugin.group(1) +
            (remap ? "id 'net.fabricmc.fabric-loom-remap' version '" : "id 'net.fabricmc.fabric-loom' version '") +
            loom +
            "'" +
            (neoGradle && BuildScript.block(gradle, "idea") != null && !gradle.matches("(?s).*id\\s*\\(?\\s*['\"]idea['\"].*")
                    ? "\n" + plugin.group(1) + "id 'idea'"
                    : "") +
            gradle.substring(plugin.end());
        if (neoGradle && gradle.contains("\n" + plugin.group(1) + "id 'idea'")) convert.notes.add(
            "Build: NeoGradle applies Gradle's idea plugin itself and Loom does not, so the build's idea block gets it from its plugins block."
        );
        int[] neo = BuildScript.block(gradle, "neoForge");
        StringBuilder loomBlock = new StringBuilder();
        if (neo != null) {
            Map<String, String> parts = BuildScript.children(gradle.substring(neo[2] + 1, neo[3] - 1));
            StringBuilder runs = new StringBuilder();
            for (var run : BuildScript.children(parts.getOrDefault("runs", "")).entrySet()) {
                if (
                    Set.of("client", "server").contains(run.getKey()) &&
                    run.getValue().strip().matches("client\\(\\)|server\\(\\)")
                ) continue;
                String body = run.getValue();
                // NeoForge's game test server is Fabric API's: a server run with -Dfabric-api.gametest, which runs the
                // game tests the mod registers with Fabric API (tests registered through NeoForge's events are named apart).
                if (body.matches("(?s).*\\btype\\s*=\\s*[\"']gameTestServer[\"'].*") || body.matches("(?s).*\\bgameTestServer\\(\\).*")) {
                    String rest = body.replaceAll("(?m)^\\s*(?:type\\s*=\\s*[\"']gameTestServer[\"']|gameTestServer\\(\\))\\s*$", "");
                    runs.append(convert.unit).append(convert.unit).append(run.getKey()).append(" {\n")
                        .append(convert.unit).append(convert.unit).append(convert.unit).append("server()\n")
                        .append(convert.unit).append(convert.unit).append(convert.unit).append("vmArg \"-Dfabric-api.gametest\"\n")
                        .append(runToLoom(run.getKey(), rest))
                        .append(convert.unit).append(convert.unit).append("}\n");
                    convert.notes.add("Build: NeoForge's game test server run is a Loom server run with Fabric API's -Dfabric-api.gametest.");
                    continue;
                }
                // A data run with nothing to generate: the mod adds no data providers (no GatherDataEvent), so it goes.
                if (body.matches("(?s).*\\b(?:clientData|serverData|data)\\(\\).*|(?s).*\\btype\\s*=\\s*[\"']data[\"'].*")) {
                    boolean providers = false;
                    for (String file : convert.projectFiles.javaFiles()) if (convert.projectFiles.read(file).contains("GatherDataEvent")) providers = true;
                    if (!providers) {
                        convert.notes.add("Build: the " + run.getKey() + " run is removed; the mod adds no data providers (GatherDataEvent), so it generated nothing.");
                        continue;
                    }
                }
                // A Loom run needs an environment (client() or server()); a data run has none on Fabric.
                if (
                    !run.getKey().equals("configureEach") &&
                    !run.getValue().matches("(?s).*\\b(?:client|server)\\(\\).*")
                ) {
                    convert.projectFiles.leave(
                        "build.gradle",
                        0,
                        "neoForge run " + run.getKey(),
                        "a run with no client() or server(): Loom has no counterpart for its type"
                    );
                    continue;
                }
                runs
                    .append(convert.unit)
                    .append(convert.unit)
                    .append(run.getKey())
                    .append(" {\n")
                    .append(runToLoom(run.getKey(), run.getValue()))
                    .append(convert.unit)
                    .append(convert.unit)
                    .append("}\n");
            }
            if (runs.length() > 0) loomBlock
                .append("loom {\n")
                .append(convert.unit)
                .append("runs {\n")
                .append(runs)
                .append(convert.unit)
                .append("}\n}\n");
            // The Fabric bridge's interface injection goes with the bridge (unbridgeFabric); Fabric API injects its own.
            if (parts.getOrDefault("interfaceInjectionData", "").contains(ConvertBridges.INJECTED_INTERFACES)) parts.remove("interfaceInjectionData");
            for (String key : parts.keySet()) if (!Set.of("runs", "mods", "parchment").contains(key)) convert.projectFiles.leave(
                "build.gradle",
                0,
                "neoForge " + key + " block",
                "no Loom counterpart in the rules"
            );
            gradle = gradle.substring(0, neo[0]) + loomBlock + gradle.substring(neo[1]);
        }
        if (convert.fabricWidener != null) {
            int[] dependencies = BuildScript.block(gradle, "dependencies");
            int at = dependencies == null ? gradle.length() : dependencies[0];
            gradle =
                gradle.substring(0, at) +
                "loom {\n" +
                convert.unit +
                "accessWidenerPath = file(\"src/main/resources/" +
                convert.fabricWidener +
                "\")\n}\n\n" +
                gradle.substring(at);
        }
        // The way to NeoForge built the client sources with main (SPLIT_CLIENT, or the two srcDir lines before it).
        gradle = gradle.replaceAll("(?s)\\n?// NeoForge has no split client source set[^\\n]*\\n.*?// End of the client sources\\.\\n", "\n");
        gradle = gradle.replaceAll("(?m)^// NeoForge has no split client source set[^\\n]*\\n(sourceSets\\.main\\.(java|resources)\\.srcDir 'src/client/(java|resources)'\\n)*", "");
        gradle = gradle.replace("main.output /* client.output on Fabric */", "client.output");
        // ModDevGradle's game classes for the sets Loom gave them to anyway.
        gradle = gradle.replaceAll("(?s)\\n?// Loom gave every source set the game's classes;[^\\n]*\\nneoForge \\{\\n(\\s*addModdingDependenciesTo\\([^)]*\\)\\n)*\\}\\n", "\n");
        int[] deps = BuildScript.block(gradle, "dependencies");
        String add =
            convert.unit +
            "minecraft \"com.mojang:minecraft:${project.minecraft_version}\"\n" +
            (mappings == null ? "" : convert.unit + mappings + "\n") +
            convert.unit +
            (remap ? "modImplementation" : "implementation") +
            " \"net.fabricmc:fabric-loader:${project.loader_version}\"\n" +
            convert.unit +
            (remap ? "modImplementation" : "implementation") +
            " \"net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}\"\n" +
            (menu
                    ? convert.unit +
                    "compileOnly \"com.terraformersmc:modmenu:${project.modmenu_version}\"\n" +
                    convert.unit +
                    "localRuntime \"com.terraformersmc:modmenu:${project.modmenu_version}\"\n"
                    : "");
        List<String> mavens = BuildScript.repositories(gradle);
        if (deps != null) {
            StringBuilder body = new StringBuilder();
            // Whole statements, not lines: a dependency with a closure ({ exclude ... }) is one statement.
            for (String raw : BuildScript.statements(gradle.substring(deps[2] + 1, deps[3] - 1))) {
                String line = raw.strip();
                // NeoForge's jarJar is Loom's include, wherever it is in the statement (implementation jarJar("x")).
                raw = raw.replaceAll("\\bjarJar(?=\\s*[(\"'])", "include");
                // NeoGradle names NeoForge itself as a dependency; the Loom lines above replace it.
                if (line.matches("(?s)\\w+\\s*\\(?\\s*[\"']net\\.neoforged:neoforge:.*")) continue;
                boolean comment = line.startsWith("//") || line.startsWith("/*") || line.startsWith("*");
                if (!line.isEmpty() && !comment) raw = convert.buildScript.dependencyLine(raw, "neoforge", "fabric", mavens, remap);
                body.append(raw);
            }
            if (body.length() > 0 && body.charAt(body.length() - 1) != '\n') body.append('\n');
            String converted = remap ? remapVariableDependencies(body.toString()) : body.toString();
            gradle =
                gradle.substring(0, deps[2] + 1) +
                "\n" +
                add +
                converted.replaceAll("^\\n+", "") +
                gradle.substring(deps[3] - 1);
        } else gradle += "\ndependencies {\n" + add + "}\n";
        if (!restored.isEmpty()) {
            int[] dependencies = BuildScript.block(gradle, "dependencies");
            StringBuilder back = new StringBuilder();
            for (String line : restored) back.append(convert.unit).append(line).append('\n');
            gradle = gradle.substring(0, dependencies[3] - 1) + back + gradle.substring(dependencies[3] - 1);
            convert.notes.add("Build: " + restored.size() + " dependency line(s) the Fabric bridge stood in for on NeoForge are back.");
        }
        // NeoForge's development classpath carries the JSR 305 annotations (javax.annotation.Nullable ...); Loom's does not.
        boolean jsr305 = false;
        for (String file : convert.projectFiles.javaFiles()) if (convert.projectFiles.read(file).contains("import javax.annotation.")) jsr305 = true;
        if (jsr305 && !gradle.contains("jsr305")) {
            int[] dependencies = BuildScript.block(gradle, "dependencies");
            if (dependencies != null) {
                gradle =
                    gradle.substring(0, dependencies[3] - 1) +
                    convert.unit +
                    "compileOnly \"com.google.code.findbugs:jsr305:3.0.2\"\n" +
                    gradle.substring(dependencies[3] - 1);
                convert.notes.add("Build: the JSR 305 annotations (javax.annotation) the code uses, which NeoForge's classpath carried, as a compile-only dependency.");
            }
        }
        // NeoForge ships NightConfig (its config files' TOML library) and mods use it directly; on Fabric the mod carries it.
        boolean nightConfig = false;
        for (String file : convert.projectFiles.javaFiles()) if (convert.projectFiles.read(file).contains("import com.electronwill.nightconfig.")) nightConfig = true;
        if (nightConfig && !gradle.contains("night-config")) {
            int[] dependencies = BuildScript.block(gradle, "dependencies");
            if (dependencies != null) {
                gradle =
                    gradle.substring(0, dependencies[3] - 1) +
                    convert.unit +
                    "include implementation(\"com.electronwill.night-config:core:" +
                    NIGHT_CONFIG +
                    "\")\n" +
                    convert.unit +
                    "include implementation(\"com.electronwill.night-config:toml:" +
                    NIGHT_CONFIG +
                    "\")\n" +
                    gradle.substring(dependencies[3] - 1);
                convert.notes.add(
                    "Build: NightConfig " + NIGHT_CONFIG + " (the version NeoForge 21.1 requires), which the code uses and NeoForge carried, inside the mod's jar."
                );
            }
        }
        if (parchmentRepository && !gradle.contains("maven.parchmentmc.org")) gradle = addRepository(
            gradle,
            "https://maven.parchmentmc.org"
        );
        if (menu && !gradle.contains("maven.terraformersmc.com")) {
            int[] repos = BuildScript.block(gradle, "repositories");
            String repo = convert.unit + "maven { url \"https://maven.terraformersmc.com/releases/\" }\n";
            if (repos != null) gradle =
                gradle.substring(0, repos[2] + 1) +
                "\n" +
                repo +
                gradle.substring(repos[2] + 1).replaceFirst("^\\n", "");
            else gradle = gradle.replaceFirst(
                "(?m)^dependencies\\s*\\{",
                "repositories {\n" + repo + "}\n\ndependencies {"
            );
        }
        gradle = gradle
            .replace("filesMatching(\"META-INF/neoforge.mods.toml\")", "filesMatching(\"fabric.mod.json\")")
            .replace("filesMatching('META-INF/neoforge.mods.toml')", "filesMatching('fabric.mod.json')");
        // ModDevGradle's Jar-in-Jar: Loom nests include dependencies in its own jar task, so the switch goes and the
        // jarJar task's output is Loom's jar (remapJar before 26.1).
        List<String> jarJarDropped = new ArrayList<>();
        gradle = BuildScript.dropStatements(gradle, s -> s.strip().matches("jarJar\\.enable\\(\\)\\s*;?"), jarJarDropped);
        if (!jarJarDropped.isEmpty()) convert.notes.add("Build: removed jarJar.enable(); Loom nests include dependencies in its jar.");
        gradle = gradle.replaceAll("\\btasks\\.jarJar\\b", remap ? "tasks.remapJar" : "tasks.jar");
        gradle = BuildScript.publishLoader(gradle, "neoforge", "fabric");
        gradle = convert.buildScript.dropPluginCode(gradle, "net.neoforged.moddevgradle");
        gradle = convert.buildScript.dropPluginCode(gradle, "net.neoforged.gradle");
        if (!convert.buildScript.neutralDependencies.isEmpty() && !gradle.contains("exclude group: 'net.neoforged', module: 'neoforge'")) {
            // A dependency kept as it is can be a NeoForge mod whose own metadata asks for NeoForge; Fabric's build has none.
            gradle +=
                BuildScript.NEOFORGE_EXCLUSION +
                convert.unit +
                "exclude group: 'net.neoforged', module: 'neoforge'\n}\n";
            convert.notes.add("Build: NeoForge itself is excluded from the dependencies the kept libraries bring with them.");
        }
        convert.projectFiles.write("build.gradle", gradle.replaceAll("\\n{3,}", "\n\n"));
        Map<String, String> props = new LinkedHashMap<>();
        props.put("neo_version", null);
        props.put("neoforge_version", null);
        props.put("loader_version", convert.fabricLoader);
        props.put("fabric_api_version", api);
        // Loom asks Mojang for the game by its version id: 26.2, not the 26.2.0 a NeoForge build may write.
        if (minecraft.matches("\\d+\\.\\d+\\.0")) props.put("minecraft_version", minecraft.replaceFirst("\\.0$", ""));
        // Loom's build names the game by minecraft_version; a NeoForge build that had only its NeoForge release gets it.
        else if (!BuildScript.properties(convert.projectFiles.read("gradle.properties")).containsKey("minecraft_version")) props.put("minecraft_version", minecraft);
        if (menu) {
            // The newest stable Mod Menu that Modrinth, where Mod Menu publishes, lists for this Minecraft version on Fabric.
            String modmenu = null;
            for (Object version : Json.array(
                Json.parse(
                    Target.fetch(
                        convert.build,
                        "https://api.modrinth.com/v2/project/modmenu/version?game_versions=%5B%22" +
                        minecraft +
                        "%22%5D&loaders=%5B%22fabric%22%5D"
                    )
                )
            )) {
                String number = Json.text(Json.object(version), "version_number", "");
                if (!number.contains("-") && (modmenu == null || Versions.compare(number, modmenu) > 0)) modmenu =
                    number;
            }
            if (modmenu == null) throw new IOException(
                "Mod Menu has no release for Minecraft " +
                minecraft +
                " on Modrinth, so the config screen cannot move back to it."
            );
            props.put("modmenu_version", modmenu);
        }
        convert.projectFiles.write("gradle.properties", BuildScript.setProperties(convert.projectFiles.read("gradle.properties"), props));
        String settings = convert.projectFiles.read("settings.gradle");
        if (!settings.contains("maven.fabricmc.net")) {
            String repo =
                convert.unit +
                convert.unit +
                "maven {\n" +
                convert.unit +
                convert.unit +
                convert.unit +
                "name = 'Fabric'\n" +
                convert.unit +
                convert.unit +
                convert.unit +
                "url = 'https://maven.fabricmc.net/'\n" +
                convert.unit +
                convert.unit +
                "}\n";
            if (settings.matches("(?s).*pluginManagement\\s*\\{\\s*repositories\\s*\\{.*")) settings =
                settings.replaceFirst(
                    "(pluginManagement\\s*\\{\\s*repositories\\s*\\{)\\n?",
                    "$1\n" + Matcher.quoteReplacement(repo)
                );
            else settings =
                "pluginManagement {\n" +
                convert.unit +
                "repositories {\n" +
                repo +
                convert.unit +
                convert.unit +
                "mavenCentral()\n" +
                convert.unit +
                convert.unit +
                "gradlePluginPortal()\n" +
                convert.unit +
                "}\n}\n\n" +
                settings;
            convert.projectFiles.write("settings.gradle", settings);
        }
        convert.notes.add("Build: Fabric Loom " + loom + ", Fabric Loader " + convert.fabricLoader + ", Fabric API " + api + ".");
    }

    String runToLoom(String name, String body) {
        StringBuilder out = new StringBuilder();
        String in = convert.unit + convert.unit + convert.unit;
        for (String raw : body.lines().toList()) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("//")) continue;
            Matcher prop = Pattern.compile(
                "^systemProperty\\s*\\(?\\s*['\"]([^'\"]+)['\"]\\s*,\\s*['\"]([^'\"]*)['\"]\\s*\\)?$"
            ).matcher(line);
            Matcher jvm = Pattern.compile("^jvmArgument\\s*\\(?\\s*['\"](.*)['\"]\\s*\\)?$").matcher(line);
            Matcher dir = Pattern.compile(
                "^gameDirectory\\s*=\\s*project\\.file\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)$"
            ).matcher(line);
            Matcher program = Pattern.compile("^programArgument\\s*\\(?\\s*['\"](.*)['\"]\\s*\\)?$").matcher(line);
            // NeoForge-only run switches with no effect on Fabric: which game test namespaces NeoForge enables (Fabric API
            // runs every test a mod registers), and ModDevGradle's console log level for its runs.
            if (line.matches("systemProperty\\s*\\(?\\s*['\"]neoforge\\.enabledGameTestNamespaces['\"].*")) {
                convert.notes.add("Build: run " + name + ": NeoForge's neoforge.enabledGameTestNamespaces is removed; Fabric API runs every game test a mod registers.");
                continue;
            }
            if (line.matches("logLevel\\s*=.*")) {
                convert.notes.add("Build: run " + name + ": ModDevGradle's run log level (" + line + ") is removed; Loom's runs log at Minecraft's own levels.");
                continue;
            }
            if (line.matches("client\\(\\)|server\\(\\)")) out.append(in).append(line).append('\n');
            else if (line.startsWith("ideName")) out
                .append(in)
                .append("name = ")
                .append(line.replaceFirst("^ideName\\s*=\\s*", ""))
                .append('\n');
            else if (prop.find()) out
                .append(in)
                .append("vmArg \"-D")
                .append(prop.group(1))
                .append('=')
                .append(prop.group(2))
                .append("\"\n");
            else if (jvm.find()) out.append(in).append("vmArg \"").append(jvm.group(1)).append("\"\n");
            else if (dir.find()) out.append(in).append("runDir = \"").append(dir.group(1)).append("\"\n");
            else if (program.find()) out.append(in).append("programArgs \"").append(program.group(1)).append("\"\n");
            else convert.projectFiles.leave(
                "build.gradle",
                0,
                "neoForge run " + name + ": " + line,
                "this ModDevGradle run setting has no Loom counterpart in the rules"
            );
        }
        return out.toString();
    }
}

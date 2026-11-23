import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import javax.xml.parsers.DocumentBuilderFactory;

/** A Fabric build moved to the target release: Loom (and Architectury Loom), its remap tasks and the Fabric pins. */
final class TargetFabric {

    /**
     * A Fabric build moving onto the unobfuscated game (26.1 and later) stops remapping: Minecraft ships with Mojang's names,
     * so Loom's plugin is net.fabricmc.fabric-loom (fabric-loom and fabric-loom-remap remap), the mappings line goes, the
     * remapping configurations become the plain ones (modImplementation -> implementation, ...), remapJar becomes jar, and
     * Java rises to the game's. Written to the build files that apply Loom; a backport onto an obfuscated game is not
     * changed here.
     */
    static void unobfuscatedFabric(Target.Setup setup, Build build, String to) throws Exception {
        String from = null;
        for (String key : List.of("minecraft_version", "minecraftVersion", "exactMinecraftVersion", "mc_version", "mcVersion")) if (
            setup.pins.get(key) != null
        ) from = setup.pins.get(key);
        if (from == null || Versions.compare(to, "26.1") < 0 || Versions.compare(from, "26.1") >= 0) return;
        Set<Path> files = new LinkedHashSet<>();
        Object own = setup.project.get("buildFile");
        if (own != null) files.add(Path.of(own.toString()));
        for (String name : List.of("build.gradle", "build.gradle.kts")) files.add(build.project.root.resolve(name));
        // Every module's own script: a multi-loader build ported through its NeoForge project still configures its Fabric
        // one (Waystones, Carry On), which must stop remapping too. Scripts that apply no Loom are left unchanged.
        Set<String> resourceDirs = new LinkedHashSet<>();
        for (Path input : build.project.inputs()) if (
            input.getFileName().toString().matches("build\\.gradle(\\.kts)?") &&
            !build.project.root.relativize(input).toString().replace('\\', '/').startsWith("buildSrc/")
        ) {
            files.add(input);
            resourceDirs.add(input.resolveSibling("src").toString());
        }
        // An access widener names the namespace its entries are in; on the unobfuscated game that is "official". The planning
        // model may list no resource folders (Origins), so the project's src folder is searched too.
        for (Object set : Json.array(setup.project.get("sourceSets"))) resourceDirs.addAll(
            ProjectModel.strings(Json.object(set).get("resources"))
        );
        Object projectDir = setup.project.get("directory");
        resourceDirs.add((projectDir == null ? build.project.root : Path.of(projectDir.toString())).resolve("src").toString());
        for (String resources : resourceDirs) {
            Path dir = Path.of(resources);
            if (!Files.isDirectory(dir)) continue;
            List<Path> wideners;
            try (var walk = Files.walk(dir, 6)) {
                wideners = walk.filter(p -> p.toString().endsWith(".accesswidener") || p.toString().endsWith(".classtweaker")).toList();
            }
            for (Path widener : wideners) {
                String before = setup.text(widener), text = before.replaceFirst(
                    "^(\\s*(?:accessWidener|classTweaker)\\s+v\\d+\\s+)named\\b",
                    "$1official"
                );
                if (text.equals(before)) continue;
                setup.originals.putIfAbsent(widener, before);
                setup.texts.put(widener, text);
                setup.applied.add(Json.map("file", widener.toString(), "change", "access widener namespace named -> official for " + to));
            }
        }
        // A script a Loom script applies (apply from: 'dependencies.gradle', Waystones' fabric module) declares its
        // dependencies with the same remapping configurations, though it names no plugin itself.
        Set<Path> applied = new LinkedHashSet<>();
        for (Path file : files) {
            if (!Files.isRegularFile(file) || !appliesLoom(setup.text(file))) continue;
            Matcher applies = Pattern.compile(
                "apply\\s*\\(?\\s*from\\s*[:=]\\s*(?:rootProject\\.file\\(\\s*)?[\"']([^\"'$]+\\.gradle(?:\\.kts)?)[\"']"
            ).matcher(setup.text(file));
            while (applies.find()) {
                Path base = applies.group().contains("rootProject") ? build.project.root : file.getParent();
                Path script = base.resolve(applies.group(1)).normalize();
                if (script.startsWith(build.project.root) && Files.isRegularFile(script) && !files.contains(script)) applied.add(script);
            }
        }
        // Convention plugins in buildSrc (the MultiLoader template: FerriteCore's remapJar.archivePath) configure the Loom
        // modules from outside them, naming the same remapping tasks and configurations.
        for (Path input : build.project.inputs()) {
            String relative = build.project.root.relativize(input).toString().replace('\\', '/');
            if (relative.matches("buildSrc/src/main/.+\\.gradle(\\.kts)?") && !files.contains(input)) applied.add(input);
        }
        for (Path script : applied) {
            // A convention plugin that applies Loom itself (ImmediatelyFast's base-conventions) gets the whole rewrite.
            String before = setup.text(script);
            String text = appliesLoom(before) ? unobfuscatedLoom(before, TargetGradle.javaFor(to)) : remapTasks(plainConfigurations(before));
            if (text.equals(before)) continue;
            setup.originals.putIfAbsent(script, before);
            setup.texts.put(script, text);
            setup.applied.add(Json.map("file", script.toString(), "change", "remapping configurations -> plain ones for " + to + " (applied by a Loom script)"));
        }
        fabricMavenFilter(setup, build);
        boolean anyLoom = false;
        for (Path file : files) if (Files.isRegularFile(file) && appliesLoom(setup.text(file))) anyLoom = true;
        String newestLoom = null, newestArchitectury = null, newestArchitecturyPlugin = null;
        for (Path file : files) {
            if (!Files.isRegularFile(file)) continue;
            String before = setup.text(file), text = unobfuscatedLoom(before, TargetGradle.javaFor(to));
            // A module script Loom reaches from elsewhere (MidnightLib's root applies it to every subproject, and
            // common/build.gradle names remapJar) uses the same names.
            if (!appliesLoom(before) && anyLoom) text = remapTasks(plainConfigurations(before));
            // A Loom older than the unobfuscated line is not published under net.fabricmc.fabric-loom (FerriteCore kept
            // 1.5-SNAPSHOT): it rises to the newest release.
            Matcher old = Pattern.compile(
                "(id\\s*\\(?\\s*[\"']net\\.fabricmc\\.fabric-loom[\"']\\s*\\)?\\s*version\\s*\\(?\\s*[\"'])([^\"'$]+)([\"'])"
            ).matcher(text);
            StringBuilder raised = new StringBuilder();
            while (old.find()) {
                String declared = old.group(2).replace("-SNAPSHOT", "");
                if (Versions.compare(declared, FIRST_UNOBFUSCATED_LOOM) >= 0) continue;
                if (newestLoom == null) newestLoom = newestLoom(build, newestLoomLine(build));
                old.appendReplacement(raised, Matcher.quoteReplacement(old.group(1) + newestLoom + old.group(3)));
            }
            text = old.appendTail(raised).toString();
            Matcher arch = Pattern.compile(
                "(id\\s*\\(?\\s*[\"']dev\\.architectury\\.loom-no-remap[\"']\\s*\\)?\\s*version\\s*\\(?\\s*[\"'])([^\"'$]+)([\"'])"
            ).matcher(text);
            StringBuilder archRaised = new StringBuilder();
            while (arch.find()) {
                String declared = arch.group(2).replace("-SNAPSHOT", "");
                if (Versions.compare(declared, FIRST_UNOBFUSCATED_LOOM) >= 0) continue;
                if (newestArchitectury == null) newestArchitectury = newestArchitecturyLoom(build);
                arch.appendReplacement(archRaised, Matcher.quoteReplacement(arch.group(1) + newestArchitectury + arch.group(3)));
            }
            text = arch.appendTail(archRaised).toString();
            // The Architectury plugin configures every platform from the common project and, before its newest line,
            // expects Loom's remap tasks there (MidnightLib: "Task with name 'remapJar' not found in project ':common'").
            Matcher plugin = Pattern.compile(
                "(id\\s*\\(?\\s*[\"']architectury-plugin[\"']\\s*\\)?\\s*version\\s*\\(?\\s*[\"'])([^\"'$]+)([\"'])"
            ).matcher(text);
            if (plugin.find()) {
                if (newestArchitecturyPlugin == null) newestArchitecturyPlugin = newestArchitecturyPlugin(build);
                if (!plugin.group(2).equals(newestArchitecturyPlugin)) {
                    text = text.substring(0, plugin.start()) + plugin.group(1) + newestArchitecturyPlugin + plugin.group(3) + text.substring(plugin.end());
                }
            }
            if (text.equals(before)) continue;
            setup.originals.putIfAbsent(file, before);
            setup.texts.put(file, text);
            setup.applied.add(
                Json.map(
                    "file",
                    file.toString(),
                    "change",
                    "Loom without remapping for " + to + ": plugin net.fabricmc.fabric-loom, no mappings, plain configurations, jar for remapJar, Java " + TargetGradle.javaFor(to),
                    "evidence",
                    "Minecraft " + to + " ships unobfuscated, with Mojang's names"
                )
            );
        }
        if (newestArchitectury != null) TargetGradle.gradleFollows(
            setup,
            build,
            "https://maven.architectury.dev/dev/architectury/architectury-loom/" + newestArchitectury + "/architectury-loom-" + newestArchitectury + ".module",
            "Architectury Loom " + newestArchitectury,
            "architectury-loom " + newestArchitectury
        );
        if (newestLoom != null) {
            loomInRoot(setup, build, newestLoom);
            TargetGradle.gradleFollows(
                setup,
                build,
                "https://maven.fabricmc.net/net/fabricmc/fabric-loom/" + newestLoom + "/fabric-loom-" + newestLoom + ".module",
                "Loom " + newestLoom,
                "fabric-loom " + newestLoom
            );
        }
    }

    /**
     * ModDevGradle keeps its separate additionalRuntimeClasspath only through Minecraft 1.21.9
     * (VersionCapabilitiesInternal.hasLegacyClasspath); later it refuses any dependency added there ("there is no
     * additional classpath anymore"), since a plain runtime dependency is on the game's class path. Ars Nouveau's Lucene
     * libraries move to runtimeOnly.
     */
    static void noAdditionalRuntimeClasspath(Target.Setup setup, Build build, String to) throws IOException {
        if (Versions.compare(to, "1.21.9") <= 0) return;
        for (Path file : build.project.inputs()) {
            String name = file.getFileName().toString();
            if (!name.matches(".+\\.gradle(\\.kts)?") || name.startsWith("settings")) continue;
            String before = setup.text(file), text = withoutAdditionalRuntimeClasspath(before);
            if (text.equals(before)) continue;
            setup.originals.putIfAbsent(file, before);
            setup.texts.put(file, text);
            setup.applied.add(
                Json.map(
                    "file",
                    file.toString(),
                    "change",
                    "additionalRuntimeClasspath -> runtimeOnly for " + to,
                    "evidence",
                    "ModDevGradle has no additional runtime classpath after Minecraft 1.21.9"
                )
            );
        }
    }

    /**
     * A settings filter that sends only named groups to Fabric's Maven (TorchMaster: includeGroup('net.fabricmc') and
     * includeGroup('fabric-loom')) must also pass what a newer Loom brings: its plugin marker for the unobfuscated id
     * (group net.fabricmc.fabric-loom) and its own libraries (net.fabricmc.unpick). net.fabricmc becomes
     * includeGroupAndSubgroups (Gradle 8.1+, which every Loom of that age needs).
     */
    static void fabricMavenFilter(Target.Setup setup, Build build) throws IOException {
        for (String name : List.of("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts")) {
            Path file = build.project.root.resolve(name);
            if (!Files.isRegularFile(file)) continue;
            String before = setup.text(file), text = fabricSubgroups(before);
            if (text.equals(before)) continue;
            setup.originals.putIfAbsent(file, before);
            setup.texts.put(file, text);
            setup.applied.add(Json.map("file", file.toString(), "change", "Fabric Maven filter passes net.fabricmc and its subgroups (Loom's plugin marker and libraries)"));
        }
    }

    static String fabricSubgroups(String text) {
        return text.replaceAll("includeGroup(\\s*\\(?\\s*([\"'])net\\.fabricmc\\2\\s*\\)?)", "includeGroupAndSubgroups$1");
    }

    static String withoutAdditionalRuntimeClasspath(String text) {
        return text.replaceAll("(?<![\\w$])additionalRuntimeClasspath(?![\\w$])", "runtimeOnly");
    }

    static final Pattern LOOM_WITH_VERSION = Pattern.compile(
        "(id\\s*\\(?\\s*([\"'])(fabric-loom|net\\.fabricmc\\.fabric-loom(?:-remap)?)\\2(?:\\s*\\))?)\\s*version\\s*\\(?\\s*[\"'][^\"']+[\"'](?:\\s*\\))?"
    );

    static String withoutLoomVersion(String text) {
        return LOOM_WITH_VERSION.matcher(text).replaceAll("$1");
    }

    /** A root build script that declares Loom (apply false) in its plugins block; null when it has a buildscript block and no plugins block. */
    static String rootLoom(String text, String id, String loom, String quote, boolean kotlin) {
        String line = kotlin
            ? "id(\"" + id + "\") version \"" + loom + "\" apply false"
            : "id " + quote + id + quote + " version " + quote + loom + quote + " apply false";
        Matcher plugins = Pattern.compile("(?m)^plugins\\s*\\{").matcher(text);
        if (plugins.find()) return text.substring(0, plugins.end()) + "\n    " + line + text.substring(plugins.end());
        return text.contains("buildscript") ? null : "plugins {\n    " + line + "\n}\n\n" + text;
    }

    /** The first Loom that applies its companion plugin (net.fabricmc.fabric-loom-companion) across a multi-project build. */
    static final String FIRST_COMPANION_LOOM = "1.12";

    /**
     * Loom 1.12 and later applies a companion plugin by id across the build, which only resolves when the root project has
     * Loom on its class path (Repurposed 1.21.5 -> 1.21.6: "Plugin with id 'net.fabricmc.fabric-loom-companion' not
     * found"). When only subprojects declare Loom, the root declares it once with apply false and the subprojects drop
     * their version, as Gradle requires of a plugin already on the class path.
     */
    static void loomInRoot(Target.Setup setup, Build build, String loom) throws IOException {
        if (Versions.compare(loom, FIRST_COMPANION_LOOM) < 0) return;
        Path root = null;
        for (String name : List.of("build.gradle", "build.gradle.kts")) if (Files.isRegularFile(build.project.root.resolve(name))) root = build.project.root.resolve(name);
        if (root == null || appliesLoom(setup.text(root))) return;
        Map<Path, String> subprojects = new LinkedHashMap<>();
        String id = null, quote = "'";
        for (Path file : build.project.inputs()) {
            String name = build.project.root.relativize(file).toString().replace('\\', '/');
            if (file.equals(root) || name.startsWith("buildSrc/") || !name.matches(".+/build\\.gradle(\\.kts)?")) continue;
            Matcher m = LOOM_WITH_VERSION.matcher(setup.text(file));
            if (!m.find()) continue;
            id = m.group(3);
            quote = m.group(2);
            subprojects.put(file, withoutLoomVersion(setup.text(file)));
        }
        if (id == null) return;
        String before = setup.text(root), text = rootLoom(before, id, loom, quote, root.toString().endsWith(".kts"));
        if (text == null) return; // a buildscript block and no plugins block: left as it is
        for (var sub : subprojects.entrySet()) {
            setup.originals.putIfAbsent(sub.getKey(), setup.text(sub.getKey()));
            setup.texts.put(sub.getKey(), sub.getValue());
        }
        setup.originals.putIfAbsent(root, before);
        setup.texts.put(root, text);
        setup.applied.add(
            Json.map(
                "file",
                root.toString(),
                "change",
                "Loom " + loom + " declared in the root project (apply false), so its companion plugin resolves",
                "evidence",
                "Loom " + FIRST_COMPANION_LOOM + "+ applies net.fabricmc.fabric-loom-companion across the build"
            )
        );
    }

    /** The first Loom published under net.fabricmc.fabric-loom, the plugin id of the unobfuscated game. */
    static final String FIRST_UNOBFUSCATED_LOOM = "1.14";

    static boolean appliesLoom(String text) {
        return Pattern.compile(
            "[\"'](?:fabric-loom|net\\.fabricmc\\.fabric-loom(?:-remap)?|dev\\.architectury\\.loom(?:-remap|-no-remap)?)[\"']"
        ).matcher(text).find();
    }

    /** The newest Loom minor line Fabric publishes (1.19 when this was written). */
    static String newestLoomLine(Build build) throws Exception {
        Matcher v = Pattern.compile("<version>(\\d+\\.\\d+)\\.\\d+</version>").matcher(
            Target.fetch(build, "https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml")
        );
        String line = "";
        while (v.find()) if (line.isEmpty() || Versions.compare(v.group(1), line) > 0) line = v.group(1);
        if (line.isEmpty()) throw new IOException("No published Fabric Loom release");
        return line;
    }

    /** Loom's remapping tasks as the plain ones (remapJar -> jar, remapSourcesJar -> sourcesJar). */
    static String remapTasks(String text) {
        // remapJar's input (Architectury's template: remapJar { inputFile.set shadowJar.archiveFile }) has no meaning once
        // nothing is remapped, and jar has no such property.
        text = Target.removeStatements(text, Pattern.compile("(?m)^[ \\t]*inputFile\\b"));
        return text.replaceAll("(?<![\\w$])remapSourcesJar(?![\\w$])", "sourcesJar").replaceAll("(?<![\\w$])remapJar(?![\\w$])", "jar");
    }

    /** Loom's remapping configurations as the plain ones the unobfuscated game uses. */
    static String plainConfigurations(String text) {
        Map<String, String> configurations = new LinkedHashMap<>();
        configurations.put("modImplementation", "implementation");
        configurations.put("modApi", "api");
        configurations.put("modCompileOnlyApi", "compileOnlyApi");
        configurations.put("modCompileOnly", "compileOnly");
        configurations.put("modRuntimeOnly", "runtimeOnly");
        configurations.put("modLocalRuntime", "localRuntime");
        for (var c : configurations.entrySet()) text = text.replaceAll("(?<![\\w$])" + c.getKey() + "(?![\\w$])", c.getValue());
        return text;
    }

    /** One build script rewritten for Loom on an unobfuscated game; unchanged when it does not apply Loom. */
    static String unobfuscatedLoom(String text, int java) {
        if (!appliesLoom(text)) return text;
        text = text.replaceAll("([\"'])(?:fabric-loom|net\\.fabricmc\\.fabric-loom-remap)([\"'])", "$1net.fabricmc.fabric-loom$2");
        // Architectury's fork splits the same way: dev.architectury.loom-no-remap is its plugin for the unobfuscated game.
        text = text.replaceAll("([\"'])dev\\.architectury\\.loom(?:-remap)?([\"'])", "$1dev.architectury.loom-no-remap$2");
        text = Target.removeStatements(text, Pattern.compile("(?m)^[ \\t]*mappings\\b"));
        Map<String, String> configurations = new LinkedHashMap<>();
        configurations.put("modImplementation", "implementation");
        configurations.put("modApi", "api");
        configurations.put("modCompileOnlyApi", "compileOnlyApi");
        configurations.put("modCompileOnly", "compileOnly");
        configurations.put("modRuntimeOnly", "runtimeOnly");
        configurations.put("modLocalRuntime", "localRuntime");
        configurations.put("remapSourcesJar", "sourcesJar");
        configurations.put("remapJar", "jar");
        text = Target.removeStatements(text, Pattern.compile("(?m)^[ \\t]*inputFile\\b"));
        for (var c : configurations.entrySet()) text = text.replaceAll("(?<![\\w$])" + c.getKey() + "(?![\\w$])", c.getValue());
        text = text.replaceAll("JavaVersion\\.VERSION_(?:1_8|1[0-9]|2[0-4])\\b", "JavaVersion.VERSION_" + java);
        text = text.replaceAll("JavaLanguageVersion\\.of\\((?:[89]|1[0-9]|2[0-4])\\)", "JavaLanguageVersion.of(" + java + ")");
        text = text.replaceAll("(options\\.release(?:\\.set\\(|\\s*=\\s*))(?:[89]|1[0-9]|2[0-4])\\b", "$1" + java);
        return text;
    }

    /** Yarn, Fabric loader and Fabric API pins of a Fabric build, each moved to its release for the target game. */
    static void fabricPins(Target.Setup setup, Build build, Map<String, Object> pins, String to) throws Exception {
        for (String key : List.of("yarn_mappings", "yarn_version")) if (pins.containsKey(key)) {
            // The unobfuscated game has no yarn: the port crosses into Mojang's names and drops the mappings line.
            if (Versions.compare(to, "26.1") >= 0) continue;
            var versions = Json.array(Json.parse(Target.fetch(build, "https://meta.fabricmc.net/v2/versions/yarn/" + to)));
            if (versions.isEmpty()) throw new IOException(
                "No published Yarn mappings for " + to + "; mapping namespace changes need a guided migration."
            );
            String yarn = Json.text(Json.object(versions.get(0)), "version", "");
            // A build that joins the game version to a build number (Cardinal Components: ${minecraft_version}+build.${yarn_mappings},
            // yarn_mappings=1) keeps only the number in the property.
            if (String.valueOf(pins.get(key)).matches("\\d+") && yarn.matches(".*\\+build\\.\\d+")) yarn = yarn.substring(yarn.lastIndexOf('.') + 1);
            setup.replacements.put(key, yarn);
        }
        if (pins.containsKey("loader_version")) {
            var versions = Json.array(Json.parse(Target.fetch(build, "https://meta.fabricmc.net/v2/versions/loader/" + to)));
            String loader = "";
            for (Object item : versions) {
                var value = Json.object(Json.object(item).get("loader"));
                if (Boolean.TRUE.equals(value.get("stable"))) {
                    loader = value.get("version").toString();
                    break;
                }
            }
            if (loader.isEmpty()) throw new IOException("No published stable Fabric loader for " + to);
            setup.replacements.put("loader_version", loader);
        }
        if (pins.containsKey("fabric_version") || pins.containsKey("fabric_api_version")) {
            String xml = Target.fetch(
                build,
                "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml"
            );
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var nodes = factory
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .getElementsByTagName("version");
            List<String> published = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                String value = nodes.item(i).getTextContent();
                if (value.endsWith("+" + to)) published.add(value);
            }
            if (published.isEmpty()) throw new IOException("No published Fabric API for " + to);
            // "+1.18" builds span 1.18 to 1.18.2 and the newest needs 1.18.2: take the newest whose own fabric.mod.json
            // accepts the target game, read from the jar (a few of the newest are tried; an unreadable one is skipped).
            String api = published.get(published.size() - 1);
            for (int i = published.size() - 1, tried = 0; i >= 0 && tried < 12; i--, tried++) {
                String candidate = published.get(i);
                try {
                    String accepts = Dependencies.minecraftConstraint(
                        Target.download(build, "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/" + candidate + "/fabric-api-" + candidate + ".jar")
                    );
                    if (accepts.isEmpty() || Versions.matches(accepts, to)) {
                        api = candidate;
                        break;
                    }
                } catch (Exception unread) {
                    break;
                }
            }
            for (String key : List.of("fabric_version", "fabric_api_version")) if (
                pins.containsKey(key)
            ) setup.replacements.put(key, api);
            try {
                String built = Dependencies.builtWithLoom(
                    Target.download(
                        build,
                        "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/" +
                        api +
                        "/fabric-api-" +
                        api +
                        ".jar"
                    )
                );
                if (!built.isEmpty()) setup.loomBuilt.put("Fabric API " + api, built);
            } catch (IOException unread) {
                setup.unchecked.add(
                    Json.map(
                        "artifact",
                        "net.fabricmc.fabric-api:fabric-api",
                        "version",
                        api,
                        "reason",
                        "Its jar could not be read for the Loom it needs: " + unread.getMessage()
                    )
                );
            }
        }
    }

    /**
     * Architectury Loom (dev.architectury.loom, Fabric Loom's fork for Architectury builds) follows the same Loom line the
     * target's jars were built with: BetterF3 1.20.4 -> 1.20.5 kept 1.5-SNAPSHOT while Fabric API was built with 1.7, and
     * the build would not load. The plugin's version moves to that line's newest release on maven.architectury.dev, and
     * Gradle to what its own bootstrap class asks. Returns the new version, or null when the build applies no such plugin.
     */
    /** The Architectury plugin's newest version line on maven.architectury.dev (its marker's "latest", e.g. 3.5-SNAPSHOT). */
    static String newestArchitecturyPlugin(Build build) throws Exception {
        Matcher latest = Pattern.compile("<latest>([^<]+)</latest>").matcher(
            Target.fetch(build, "https://maven.architectury.dev/architectury-plugin/architectury-plugin.gradle.plugin/maven-metadata.xml")
        );
        if (!latest.find()) throw new IOException("No published Architectury plugin");
        return latest.group(1);
    }

    /** Architectury Loom's newest release on maven.architectury.dev. */
    static String newestArchitecturyLoom(Build build) throws Exception {
        Matcher version = Pattern.compile("<version>([0-9]+\\.[0-9]+\\.[0-9]+)</version>").matcher(
            Target.fetch(build, "https://maven.architectury.dev/dev/architectury/architectury-loom/maven-metadata.xml")
        );
        String newest = null;
        while (version.find()) if (newest == null || Versions.compare(version.group(1), newest) > 0) newest = version.group(1);
        if (newest == null) throw new IOException("No published Architectury Loom release");
        return newest;
    }

    static String architecturyLoomFollows(Target.Setup setup, Build build, String line) throws Exception {
        Pattern plugin = Pattern.compile(
            "(id\\s*\\(?\\s*[\"']dev\\.architectury\\.loom[\"']\\s*\\)?\\s*version\\s*\\(?\\s*[\"'])([^\"']+)([\"'])"
        );
        List<Path> scripts = new ArrayList<>();
        for (String name : List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")) scripts.add(build.project.root.resolve(name));
        for (Path script : scripts) {
            if (!Files.isRegularFile(script)) continue;
            Matcher found = plugin.matcher(setup.text(script));
            if (!found.find()) continue;
            String current = found.group(2);
            if (Versions.compare(Dependencies.minor(current.replace("-SNAPSHOT", "")), line) >= 0) return null;
            String newest = null;
            Matcher version = Pattern.compile("<version>(" + Pattern.quote(line) + "\\.[0-9]+)</version>").matcher(
                Target.fetch(build, "https://maven.architectury.dev/dev/architectury/architectury-loom/maven-metadata.xml")
            );
            while (version.find()) if (newest == null || Versions.compare(version.group(1), newest) > 0) newest = version.group(1);
            if (newest == null) return null;
            setup.replace(script, found.group(0), found.group(1) + newest + found.group(3));
            TargetGradle.gradleFollows(
                setup,
                build,
                "https://maven.architectury.dev/dev/architectury/architectury-loom/" + newest + "/architectury-loom-" + newest + ".module",
                "Architectury Loom " + newest,
                "architectury-loom " + newest
            );
            return newest;
        }
        return null;
    }

    /**
     * Loom follows the target. A Fabric mod jar built by a newer Loom stops an older Loom while the build
     * loads ("Mod was built with a newer version of Loom"), so the project's Loom rises to the newest line its
     * target jars (Fabric API and every resolved mod) were built with, at that line's newest release on
     * maven.fabricmc.net. Gradle then rises to the version that Loom's own module metadata requires
     * (org.gradle.plugin.api-version). Neither is ever lowered, so a backport keeps its build tools.
     */
    static void loomFollows(Target.Setup setup, Build build, String to) throws Exception {
        String current = build.project.loomVersion(), need = "", needBy = "";
        for (var built : setup.loomBuilt.entrySet()) if (
            need.isEmpty() || Versions.compare(Dependencies.minor(built.getValue()), Dependencies.minor(need)) > 0
        ) {
            need = built.getValue();
            needBy = built.getKey();
        }
        if (
            need.isEmpty() ||
            current.isEmpty() ||
            Versions.compare(Dependencies.minor(need), Dependencies.minor(current)) <= 0
        ) {
            if (current.isEmpty()) for (var built : setup.loomBuilt.entrySet()) {
                String warning = Dependencies.loomWarning(
                    Json.map("version_number", built.getKey()),
                    built.getValue(),
                    "(not stated)"
                );
                if (warning != null) System.err.println("Warning: " + warning);
            }
            return;
        }
        String line = Dependencies.minor(need), loom = newestLoom(build, line);
        if (!loomEdit(setup, build, current, loom)) {
            String architectury = architecturyLoomFollows(setup, build, line);
            if (architectury != null) {
                setup.applied.add(Json.map("change", "Architectury Loom " + current + " -> " + architectury + " for " + to, "evidence", needBy + " was built with Loom " + need));
                return;
            }
            System.err.println(
                "Warning: " + Dependencies.loomWarning(Json.map("version_number", needBy), need, current)
            );
            return;
        }
        setup.applied.add(
            Json.map(
                "change",
                "Loom " + current + " -> " + loom + " for " + to,
                "evidence",
                needBy + " was built with Loom " + need
            )
        );
        loomInRoot(setup, build, loom);
        TargetGradle.gradleFollows(
            setup,
            build,
            "https://maven.fabricmc.net/net/fabricmc/fabric-loom/" + loom + "/fabric-loom-" + loom + ".module",
            "Loom " + loom,
            "fabric-loom " + loom
        );
    }

    /** The newest release of one Loom line (e.g. 1.10 -> 1.10.5), read from maven.fabricmc.net. */
    static String newestLoom(Build build, String line) throws Exception {
        String xml = Target.fetch(build, "https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var nodes = factory
            .newDocumentBuilder()
            .parse(new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .getElementsByTagName("version");
        String chosen = "";
        for (int i = 0; i < nodes.getLength(); i++) {
            String value = nodes.item(i).getTextContent();
            if (!value.startsWith(line + ".") || !value.matches("[0-9.]+")) continue;
            if (chosen.isEmpty() || Versions.compare(value, chosen) > 0) chosen = value;
        }
        if (chosen.isEmpty()) throw new IOException("No published Fabric Loom release in the " + line + " line");
        return chosen;
    }

    /** Writes the new Loom version where the build states it (the places ProjectModel.loomVersion reads); false when it cannot. */
    static boolean loomEdit(Target.Setup setup, Build build, String current, String loom) throws IOException {
        Path properties = build.project.root.resolve("gradle.properties");
        List<String> keys = new ArrayList<>(List.of("loom_version", "loomVersion", "fabric_loom_version"));
        Pattern plugin = Pattern.compile(
            "(id\\s*\\(?\\s*[\"'](?:fabric-loom|net\\.fabricmc\\.fabric-loom(?:-remap)?)[\"']\\s*\\)?\\s*version\\s*\\(?\\s*[\"'])([^\"']+)([\"'])"
        );
        for (Path file : build.project.inputs()) {
            String name = file.getFileName().toString();
            if (!name.matches("(?:build|settings)\\.gradle(?:\\.kts)?")) continue;
            Matcher found = plugin.matcher(setup.text(file));
            if (!found.find()) continue;
            Matcher property = Pattern.compile("^\\$\\{?(?:project\\.)?([A-Za-z_][A-Za-z0-9_]*)\\}?$").matcher(
                found.group(2)
            );
            if (property.find()) {
                keys.add(0, property.group(1));
                break;
            }
            if (!found.group(2).strip().equals(current)) continue;
            setup.replace(file, found.group(0), found.group(1) + loom + found.group(3));
            return true;
        }
        if (!Files.isRegularFile(properties)) return false;
        for (String key : keys) {
            Matcher pin = Pattern.compile(
                "(?m)^([ \\t]*" + Pattern.quote(key) + "[ \\t]*=[ \\t]*)(\\S+)[ \\t]*$"
            ).matcher(setup.text(properties));
            if (pin.find() && pin.group(2).equals(current)) {
                setup.replace(properties, pin.group(0), pin.group(1) + loom);
                return true;
            }
        }
        return false;
    }
}

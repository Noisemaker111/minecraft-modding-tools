import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import javax.xml.parsers.DocumentBuilderFactory;

/** A NeoForge build moved to the target release: ModDevGradle or NeoGradle, NeoForm, run types and unit tests. */
final class TargetNeoForge {

    /** NeoForge pins kept beside a Fabric project's (Architectury and MultiLoader builds) move to NeoForge's release for the target. */
    static void neoForgeSibling(Target.Setup setup, Build build, Map<String, Object> pins, String to) throws Exception {
        for (String key : List.of("neo_version", "neoforge_version", "neoForgeVersion", "neoforgeVersion")) if (pins.containsKey(key)) {
            try {
                String resolved = neoForgeVersion(build, to);
                setup.replacements.put(key, resolved);
                setup.applied.add(
                    Json.map("artifact", "net.neoforged:neoforge", "to", resolved, "evidence", "the build's NeoForge project follows the same game: NeoForge " + resolved + " publishes for " + to)
                );
            } catch (IOException none) {
                setup.unchecked.add(Json.map("artifact", "net.neoforged:neoforge (sibling project)", "version", to, "reason", none.getMessage()));
            }
        }
    }

    /**
     * ModDevGradle follows a forward port to the newest release of its line on maven.neoforged.net. An old release cannot set
     * up a newer game: a mod's moddev 1.0.0 moved to 1.21.4 failed every build on com.mojang:jtracy, a library
     * 1.21.4 added. The line is kept, so the build script's DSL stays the one it was written for; Gradle rises to what the
     * plugin's own module metadata asks. A backport keeps its build tools.
     */
    static void modDevFollows(Target.Setup setup, Build build, String to) throws Exception {
        String from = "";
        for (String key : List.of("minecraft_version", "minecraftVersion", "exactMinecraftVersion", "mc_version", "mcVersion")) if (
            setup.pins.containsKey(key)
        ) {
            from = setup.pins.get(key);
            break;
        }
        if (!from.isEmpty() && Versions.compare(to, from) <= 0) return;
        Pattern pluginLine = Pattern.compile(
            "(id\\s*\\(?\\s*[\"']net\\.neoforged\\.moddev[\"']\\s*\\)?\\s*version\\s*\\(?\\s*[\"'])([^\"']+)([\"'])"
        );
        Path properties = build.project.root.resolve("gradle.properties");
        List<Path> files = new ArrayList<>();
        Object own = setup.project.get("buildFile");
        if (own != null) files.add(Path.of(own.toString()));
        for (String name : List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")) files.add(build.project.root.resolve(name));
        files.add(properties);
        for (Path file : new LinkedHashSet<>(files)) {
            if (!Files.isRegularFile(file)) continue;
            Matcher pin = file.equals(properties)
                ? Pattern.compile(
                    "(?m)^([ \\t]*(?:moddev_version|moddevgradle_version|mdg_version|moddevVersion)[ \\t]*=[ \\t]*)(\\S+)()[ \\t]*$"
                ).matcher(setup.text(file))
                : pluginLine.matcher(setup.text(file));
            if (!pin.find() || !pin.group(2).matches("\\d+\\.\\d+\\.\\d+(?:-beta)?")) continue;
            // The unobfuscated game (26.1+) needs ModDevGradle 2: 1.x cannot set up its game artifacts (a NeoForge port 1.21.3 -> 26.3).
            String current = pin.group(2), line = current.substring(0, current.indexOf('.'));
            if (Versions.compare(to, "26.1") >= 0 && Integer.parseInt(line) < 2) line = "2";
            // 0.1.x was ModDevGradle's preview line, with 1.0 its stable continuation and the same DSL; 0.1.131 cannot set up
            // 1.21.2's game (TorchMaster: com.mojang:jtracy unresolved), 1.0.x can.
            if (line.equals("0")) line = "1";
            String newest = Target.newestInLine(
                Target.fetch(build, "https://maven.neoforged.net/releases/net/neoforged/moddev-gradle/maven-metadata.xml"),
                line
            );
            if (newest == null || Versions.compare(newest, current) <= 0) return;
            setup.replace(file, pin.group(0), pin.group(1) + newest + pin.group(3));
            setup.applied.add(
                Json.map(
                    "file",
                    file.toString(),
                    "change",
                    "ModDevGradle " + current + " -> " + newest + " for " + to,
                    "evidence",
                    "the newest ModDevGradle " + line + ".x on maven.neoforged.net"
                )
            );
            TargetGradle.gradleFollows(
                setup,
                build,
                "https://maven.neoforged.net/releases/net/neoforged/moddev-gradle/" + newest + "/moddev-gradle-" + newest + ".module",
                "ModDevGradle " + newest,
                "ModDevGradle " + newest
            );
            return;
        }
    }

    /**
     * A vanilla-only module (an Architectury or MultiLoader common built on ModDevGradle's neoForm) names a NeoForm release:
     * its full version, or a timestamp the build joins to the Minecraft version (JEI: ${minecraftVersion}-${neoformTimestamp}).
     * It moves to NeoForm's newest release for the target game on maven.neoforged.net; the old one does not exist for it.
     */
    /**
     * NeoForm's newest release for a game version: a timestamp through 1.21.x (1.21.1-20240808.144430), a build number from
     * 26.1 on (26.3-1); snapshots and pre-releases are not releases of that version. Null when there is none.
     */
    static String neoFormRelease(String metadata, String to) {
        String newest = null, key = null;
        Matcher version = Pattern.compile("<version>(" + Pattern.quote(to) + "-(\\d{8}\\.\\d{6}|\\d+))</version>").matcher(metadata);
        while (version.find()) {
            String k = version.group(2).contains(".") ? version.group(2) : String.format("%020d", Long.parseLong(version.group(2)));
            if (key == null || k.compareTo(key) > 0) {
                key = k;
                newest = version.group(1);
            }
        }
        return newest;
    }

    static void neoFormFollows(Target.Setup setup, Build build, String to) throws Exception {
        Path properties = build.project.root.resolve("gradle.properties");
        if (!Files.isRegularFile(properties)) return;
        String text = setup.text(properties);
        Matcher pin = Pattern.compile(
            "(?m)^([ \\t]*((?i:neo_?form_?(?:version|timestamp)))[ \\t]*=[ \\t]*)(\\S+)[ \\t]*$"
        ).matcher(text);
        if (!pin.find()) return;
        // TorchMaster: neo_form_version=1.21-20240613.152323 (a full version names its own game, which may lag the mod's).
        String newest = neoFormRelease(Target.fetch(build, "https://maven.neoforged.net/releases/net/neoforged/neoform/maven-metadata.xml"), to);
        if (newest == null) {
            setup.unchecked.add(Json.map("artifact", "net.neoforged:neoform", "version", to, "reason", "NeoForm publishes no release for Minecraft " + to));
            return;
        }
        boolean timestampKey = pin.group(2).toLowerCase(Locale.ROOT).contains("timestamp");
        if (timestampKey && !newest.matches(".+-\\d{8}\\.\\d{6}")) {
            setup.unchecked.add(
                Json.map(
                    "artifact",
                    "net.neoforged:neoform",
                    "version",
                    newest,
                    "reason",
                    "the build keeps a NeoForm timestamp, and NeoForm " + to + " is numbered without one; set the full version"
                )
            );
            return;
        }
        String value = timestampKey ? newest.substring(newest.indexOf('-') + 1) : newest;
        if (value.equals(pin.group(3))) return;
        setup.replace(properties, pin.group(0), pin.group(1) + value);
        setup.applied.add(Json.map("artifact", "net.neoforged:neoform", "from", pin.group(3), "to", newest, "evidence", "NeoForm's newest release for " + to + " on maven.neoforged.net"));
    }

    /**
     * NeoGradle follows the target on a forward port. NeoForge 21.6's userdev runs on ASM 9.8 while NeoGradle 7.0.145
     * still put ASM 9.5 on the client's class path, so Notes 1.21 -> 1.21.6 compiled and then stopped at launch
     * ("Module named org.objectweb.asm.tree.analysis was already on the JVMs module path"). The plugin rises to the
     * newest NeoGradle 7.x (the 7.1 line, which 26.1+ needs anyway), then Gradle to what that release requires. One
     * NeoGradle for every port is also what lets a second mod reuse the first one's game setup: NeoGradle's own
     * cross-project cache (~/.gradle/caches/ng_execute) keys each NeoForm step by its task class, input properties and
     * input file contents, so two NeoGradle versions decompile the same game twice. A backport keeps its build tools.
     */
    static void neoGradleFollows(Target.Setup setup, Build build, String to) throws Exception {
        String from = "";
        for (String key : List.of("minecraft_version", "minecraftVersion", "exactMinecraftVersion", "mc_version", "mcVersion")) if (
            setup.pins.containsKey(key)
        ) {
            from = setup.pins.get(key);
            break;
        }
        if (!from.isEmpty() && Versions.compare(to, from) <= 0) return;
        Pattern pluginLine = Pattern.compile(
            "(id\\s*\\(?\\s*[\"']net\\.neoforged\\.gradle\\.userdev[\"']\\s*\\)?\\s*version\\s*[\"'])([^\"']+)([\"'])"
        );
        Path properties = build.project.root.resolve("gradle.properties");
        for (Path file : List.of(
            build.project.root.resolve("build.gradle"),
            build.project.root.resolve("build.gradle.kts"),
            build.project.root.resolve("settings.gradle"),
            build.project.root.resolve("settings.gradle.kts"),
            properties
        )) {
            if (!Files.isRegularFile(file)) continue;
            Matcher pin = file.equals(properties)
                ? Pattern.compile(
                    "(?m)^([ \\t]*(?:neogradle_version|neoGradleVersion|neogradleVersion)[ \\t]*=[ \\t]*)(\\S+)()[ \\t]*$"
                ).matcher(setup.text(file))
                : pluginLine.matcher(setup.text(file));
            if (!pin.find() || !pin.group(2).matches("\\d+\\.\\d+\\.\\d+")) continue;
            String current = pin.group(2), newest = latestNeoGradle(build, "7");
            if (Versions.compare(newest, current) <= 0) return;
            setup.replace(file, pin.group(0), pin.group(1) + newest + pin.group(3));
            // NeoGradle's other plugins (mixin, vanilla, ...) are published with userdev and only work at its version:
            // a mixin plugin left on 7.0.145 beside userdev 7.1 failed to apply (net/minecraftforge/gdi/BaseDSLElement).
            if (!file.equals(properties) && Versions.compare(newest, "7.1") >= 0) dropMixinPlugin(setup, build, file);
            if (!file.equals(properties)) {
                Matcher sibling = Pattern.compile(
                    "(id\\s*\\(?\\s*[\"']net\\.neoforged\\.gradle\\.(?!userdev[\"'])[\\w.]+[\"']\\s*\\)?\\s*version\\s*[\"'])" +
                    Pattern.quote(current) +
                    "([\"'])"
                ).matcher(setup.text(file));
                List<String> siblings = new ArrayList<>();
                while (sibling.find()) siblings.add(sibling.group(0));
                for (String line : siblings) setup.replace(file, line, line.replace(current, newest));
            }
            setup.applied.add(
                Json.map(
                    "file",
                    file.toString(),
                    "change",
                    "NeoGradle " + current + " -> " + newest + " for " + to,
                    "evidence",
                    "the newest NeoGradle 7.x on maven.neoforged.net"
                )
            );
            TargetGradle.gradleFollows(
                setup,
                build,
                "https://maven.neoforged.net/releases/net/neoforged/gradle/userdev/" +
                newest +
                "/userdev-" +
                newest +
                ".module",
                "NeoGradle " + newest,
                "NeoGradle " + newest
            );
            return;
        }
    }

    /**
     * NeoGradle 7.1 has no mixin plugin (net.neoforged.gradle.mixin ends at 7.0.192): FML reads a mod's mixin configs from
     * its neoforge.mods.toml [[mixins]] entries, which is all the plugin's mixin { config ... } block was for. So the
     * plugin line and its block go, and every config the block named is declared in the mods.toml if it is not already.
     */
    static void dropMixinPlugin(Target.Setup setup, Build build, Path file) throws IOException {
        String text = setup.text(file);
        Matcher plugin = Pattern.compile(
            "(?m)^[ \\t]*id\\s*\\(?\\s*[\"']net\\.neoforged\\.gradle\\.mixin[\"']\\s*\\)?(?:\\s*version\\s*[\"'][^\"']*[\"'])?[ \\t]*\\r?\\n"
        ).matcher(text);
        if (!plugin.find()) return;
        Matcher block = Pattern.compile("(?m)^mixin\\s*\\{").matcher(text);
        List<String> configs = new ArrayList<>();
        String out = text.substring(0, plugin.start()) + text.substring(plugin.end());
        if (block.find()) {
            int depth = 0, end = -1;
            for (int i = block.end() - 1; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) {
                    end = i + 1;
                    break;
                }
            }
            if (end < 0) return;
            String body = text.substring(block.start(), end);
            Matcher config = Pattern.compile("(?m)^\\s*config\\s*\\(?\\s*[\"']([^\"']+)[\"']").matcher(body);
            while (config.find()) configs.add(config.group(1));
            String withoutPlugin = out;
            int at = withoutPlugin.indexOf(body);
            out = withoutPlugin.substring(0, at) + withoutPlugin.substring(at + body.length()).replaceFirst("^\\r?\\n", "");
        }
        Path toml = null;
        // NeoForge before 20.5 read META-INF/mods.toml (a 1.20.4 mod); later releases neoforge.mods.toml.
        for (String name : List.of("mods.toml", "neoforge.mods.toml")) for (String dir : List.of("src/main/resources/META-INF/", "src/main/templates/META-INF/")) {
            Path candidate = build.project.root.resolve(dir + name);
            if (Files.isRegularFile(candidate)) toml = candidate;
        }
        if (toml == null && !configs.isEmpty()) return;
        if (toml != null) {
            String metadata = setup.text(toml), added = metadata;
            for (String config : configs) {
                String literal = config.replace("${mod_id}", Objects.requireNonNullElse(modId(build.project.root), "${mod_id}"));
                if (added.contains("\"" + config + "\"") || added.contains("\"" + literal + "\"")) continue;
                added = added + (added.endsWith("\n") ? "" : "\n") + "\n[[mixins]]\nconfig = \"" + config + "\"\n";
            }
            if (!added.equals(metadata)) {
                setup.originals.putIfAbsent(toml, metadata);
                setup.texts.put(toml, added);
            }
        }
        setup.originals.putIfAbsent(file, text);
        setup.texts.put(file, out);
        setup.applied.add(
            Json.map(
                "file",
                file.toString(),
                "change",
                "NeoGradle's mixin plugin removed (NeoGradle 7.1 has none); its configs " + configs + " are mods.toml [[mixins]]",
                "evidence",
                "net.neoforged.gradle.mixin's newest release on maven.neoforged.net is 7.0.192"
            )
        );
    }

    /**
     * NeoForge 1.21.4 split the `data` run type into clientData/serverData. A build that still
     * declares `data { ... }` fails during configuration, so rename the run with the version pins.
     */
    static void runTypeRename(Target.Setup setup, Build build, String to) throws IOException {
        if (Versions.compare(to, "1.21.4") < 0) return;
        Pattern block = Pattern.compile("(?m)^([\\t ]*)data([\\t ]*)\\{");
        for (Path file : build.project.inputs()) {
            String name = file.getFileName().toString();
            if (!name.endsWith(".gradle") && !name.endsWith(".gradle.kts")) continue;
            String text = setup.text(file);
            Matcher match = block.matcher(text);
            if (!match.find()) continue;
            String updated =
                text.substring(0, match.start()) +
                match.group(1) +
                "clientData" +
                match.group(2) +
                "{" +
                text.substring(match.end());
            setup.replace(file, text, updated);
            setup.applied.add(
                Json.map("file", file.toString(), "change", "data run type renamed to clientData for NeoForge 1.21.4+")
            );
        }
    }

    /**
     * The mod's own JUnit tests keep running on NeoForge 26.x. Since 1.21.11 the game's Commands class builds permission
     * codecs from the built-in registries when it loads, so a plain test that registers a command tree stops with "Not
     * bootstrapped"; and on NeoForge 26.x the game can only be bootstrapped inside FancyModLoader (SharedConstants asks
     * FML whether it runs in production). NeoForge's userdev ships a junit run type for exactly this: the test task runs
     * on junit-fml, which starts FML, puts the mod and its tests in FML's class loader (MOD_CLASSES) and bootstraps the
     * game. A NeoForge mod ported 1.21.1 -> 26.3: 7 of 128 tests failed plain, all 128 pass this way. NeoGradle
     * projects only (ModDevGradle has its own unitTest block), forward ports onto 26.1+, and only when there are tests.
     */
    static void unitTestsInFml(Target.Setup setup, Build build, String to, String neoForge) throws Exception {
        if (Versions.compare(to, "26.1") < 0) return;
        String from = "";
        for (String key : List.of("minecraft_version", "minecraftVersion", "mc_version", "mcVersion")) if (setup.pins.containsKey(key)) {
            from = setup.pins.get(key);
            break;
        }
        if (from.isEmpty() || Versions.compare(from, "26.1") >= 0) return;
        Path file = build.project.root.resolve("build.gradle");
        if (!Files.isRegularFile(file)) return;
        String text = setup.text(file);
        if (
            !text.contains("net.neoforged.gradle.userdev") ||
            text.contains("junit-fml") ||
            text.contains("unitTestSource") ||
            !hasJUnitTests(build.project.root.resolve("src/test/java"))
        ) return;
        String modId = modId(build.project.root);
        if (modId == null) return;
        Matcher loader = Pattern.compile(
            "<artifactId>loader</artifactId>\\s*<version>([^<]+)</version>"
        ).matcher(
            Target.fetch(
                build,
                "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + neoForge + "/neoforge-" + neoForge + ".pom"
            )
        );
        if (!loader.find()) return;
        String block =
            "\n// Written by mmt port: on NeoForge 26.x the game can only be bootstrapped inside FancyModLoader, so the\n" +
            "// tests run on junit-fml, with the mod and its tests in FML's class loader.\n" +
            "runs {\n    junit {\n        junit true\n        modSource sourceSets.main\n        unitTestSource sourceSets.test\n    }\n}\n\n" +
            "dependencies {\n    testRuntimeOnly 'net.neoforged.fancymodloader:junit-fml:" +
            loader.group(1) +
            "'\n}\n\n" +
            "tasks.named('test', Test).configure {\n" +
            "    jvmArgs '--add-opens', 'java.base/java.lang.invoke=ALL-UNNAMED', '--enable-native-access=ALL-UNNAMED', '--sun-misc-unsafe-memory-access=allow'\n" +
            "    def roots = sourceSets.main.output.classesDirs.files + [sourceSets.main.output.resourcesDir] + sourceSets.test.output.classesDirs.files\n" +
            "    environment 'MOD_CLASSES', roots.collect { \"" +
            modId +
            "%%${it}\" }.join(File.pathSeparator)\n}\n";
        setup.originals.putIfAbsent(file, text);
        setup.texts.put(file, text + block);
        setup.applied.add(
            Json.map(
                "file",
                file.toString(),
                "change",
                "tests run on junit-fml " + loader.group(1) + " (FancyModLoader) for " + to,
                "evidence",
                "NeoForge " + neoForge + "'s published pom names FancyModLoader " + loader.group(1)
            )
        );
    }

    static boolean hasJUnitTests(Path tests) throws IOException {
        if (!Files.isDirectory(tests)) return false;
        try (var walk = Files.walk(tests)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) if (
                Files.readString(file).contains("org.junit")
            ) return true;
        }
        return false;
    }

    /** The first modId in the mod's neoforge.mods.toml (or mods.toml), literal or from a ${mod_id}-style property. */
    static String modId(Path root) throws IOException {
        for (String name : List.of("neoforge.mods.toml", "mods.toml")) for (String dir : List.of(
            "src/main/resources/META-INF/",
            "src/main/templates/META-INF/"
        )) {
            Path toml = root.resolve(dir + name);
            if (!Files.isRegularFile(toml)) continue;
            Matcher id = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"").matcher(Files.readString(toml));
            if (!id.find()) continue;
            String value = id.group(1);
            Matcher property = Pattern.compile("\\$\\{(\\w+)}").matcher(value);
            if (!property.matches()) return value;
            Path props = root.resolve("gradle.properties");
            if (!Files.isRegularFile(props)) return null;
            Matcher pin = Pattern.compile("(?m)^\\s*" + Pattern.quote(property.group(1)) + "\\s*=\\s*(\\S+)").matcher(
                Files.readString(props)
            );
            return pin.find() ? pin.group(1) : null;
        }
        return null;
    }

    /** NeoGradle's newest release whose version starts with line + "." (line "7" for the newest 7.x), from NeoForged's own published metadata. */
    static String latestNeoGradle(Build build, String line) throws Exception {
        String xml = Target.fetch(
            build,
            "https://maven.neoforged.net/releases/net/neoforged/gradle/userdev/maven-metadata.xml"
        );
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
        if (chosen.isEmpty()) throw new IOException("No published NeoGradle release in the " + line + ".x line");
        return chosen;
    }

    /** NeoForge versions are numbered by Minecraft minor: 1.21.6 publishes as 21.6.x (1.20.1 as 47.x). */
    static String neoForgePrefix(String minecraft) {
        return minecraft.equals("1.20.1") ? "47." : minecraft.replaceFirst("^1\\.", "") + ".";
    }

    static String neoForgeVersion(Build build, String minecraft) throws Exception {
        String prefix = neoForgePrefix(minecraft);
        String xml = Target.fetch(build, "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var nodes = factory
            .newDocumentBuilder()
            .parse(new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .getElementsByTagName("version");
        String stable = "", any = "";
        for (int i = 0; i < nodes.getLength(); i++) {
            String value = nodes.item(i).getTextContent();
            if (!value.startsWith(prefix)) continue;
            if (value.matches("[0-9.]+") && (stable.isEmpty() || Versions.compare(value, stable) > 0)) stable = value;
            else if (any.isEmpty() || Versions.compare(value, any) > 0) any = value;
        }
        String chosen = stable.isEmpty() ? any : stable;
        if (chosen.isEmpty()) throw new IOException(
            "No published NeoForge build for " + minecraft + " (looked for " + prefix + "x)"
        );
        return chosen;
    }
}

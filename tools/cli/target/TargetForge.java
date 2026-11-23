import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** A Forge build for the target release: the legacy scaffold (RetroFuturaGradle) and the Forge project beside a multiloader mod. */
final class TargetForge {

    /** GTNH nexus maven-metadata for RetroFuturaGradle; the plugin marker resolves through the scaffold's pluginManagement. */
    static final String RFG_METADATA =
        "https://nexus.gtnewhorizons.com/repository/public/com/gtnewhorizons/retrofuturagradle/maven-metadata.xml";

    /**
     * A NeoForge build ported to 1.7.10 cannot keep its build: NeoForge never published for that game.
     * The scaffold swaps the whole build for RetroFuturaGradle (which maps mcVersion 1.7.10 to Forge
     * 1.7.10-10.13.4.1614) instead of resolving same-loader artifacts, so a real run plans past the
     * target stage. Source incompatibilities stay compile errors for the bridge and sidecar passes.
     */
    static Target.Setup legacyForgeScaffold(Build build, Target.Setup setup, Map<String, Object> pins, String to)
        throws Exception {
        String rfg = rfgVersion(build);
        scaffoldFiles(setup, build, pins, rfg, to);
        return setup;
    }

    /** The newest final RFG 2.x release; RFG 2 serves 1.7.10 with a Java 25 daemon. */
    static String rfgVersion(Build build) throws Exception {
        String rfg = Target.newestInLine(Target.fetch(build, RFG_METADATA), "2");
        if (rfg == null) throw new IOException(
            "RetroFuturaGradle publishes no 2.x release (nexus.gtnewhorizons.com)"
        );
        return rfg;
    }

    /**
     * Swap the selected project's build for the RFG scaffold: rewrite the Groovy scripts in place,
     * replace Kotlin scripts with Groovy ones (Gradle refuses both spellings side by side), retarget
     * the owned game pin at mcVersion, and pin the daemon the RFG plugin needs. New files are tracked
     * as creations, so preview shows them and undo deletes them.
     */
    static void scaffoldFiles(Target.Setup setup, Build build, Map<String, Object> pins, String rfg, String to)
        throws Exception {
        Path root = build.project.root;
        Path directory = Path.of(Json.text(setup.project, "directory", root.toString()))
            .toAbsolutePath()
            .normalize();
        scaffoldScript(setup, root, "settings", rfgSettingsGradle(rootName(root)));
        Path buildFile = Path.of(
            Json.text(setup.project, "buildFile", directory.resolve("build.gradle").toString())
        );
        scaffoldScript(setup, buildFile.toAbsolutePath().getParent(), "build", rfgBuildGradle(rfg));
        retargetGamePin(setup, build, directory, pins, to);
        Path daemon = root.resolve("gradle/gradle-daemon-jvm.properties");
        if (Files.isRegularFile(daemon)) setup.rewrite(daemon, rfgDaemonJvmProperties());
        else setup.createFile(daemon, rfgDaemonJvmProperties());
        setup.applied.add(
            Json.map(
                "artifact",
                "com.gtnewhorizons.retrofuturagradle",
                "to",
                rfg,
                "identity",
                "resolved from nexus.gtnewhorizons.com",
                "gameVersion",
                to,
                "evidence",
                "RetroFuturaGradle " + rfg + " maps mcVersion 1.7.10 to Forge 1.7.10-10.13.4.1614"
            )
        );
        setup.applied.add(
            Json.map(
                "change",
                "RFG scaffold replaces the NeoForge build for " + to,
                "evidence",
                "RetroFuturaGradle example build (mcVersion, toolchain, processResources)"
            )
        );
    }

    /** One Gradle script: rewrite the Groovy spelling, or replace the Kotlin spelling with Groovy. */
    static void scaffoldScript(Target.Setup setup, Path directory, String base, String content) throws IOException {
        Path groovy = directory.resolve(base + ".gradle");
        Path kotlin = directory.resolve(base + ".gradle.kts");
        if (Files.isRegularFile(groovy)) setup.rewrite(groovy, content);
        else if (Files.isRegularFile(kotlin)) {
            setup.excluded.add(kotlin);
            setup.createFile(groovy, content);
        } else setup.createFile(groovy, content);
    }

    /** The settings' project name when it names one, else the folder name. */
    static String rootName(Path root) throws IOException {
        for (String name : List.of("settings.gradle", "settings.gradle.kts")) {
            Path file = root.resolve(name);
            if (!Files.isRegularFile(file)) continue;
            for (String line : Files.readAllLines(file)) {
                String stripped = line.strip();
                if (!stripped.startsWith("rootProject.name")) continue;
                String rest = stripped.substring("rootProject.name".length()).strip();
                if (!rest.startsWith("=")) continue;
                String value = rest.substring(1).strip();
                if (value.length() < 2) continue;
                char quote = value.charAt(0);
                if (quote != '"' && quote != '\'') continue;
                int last = value.lastIndexOf(quote);
                if (last > 0) return value.substring(1, last);
            }
        }
        return root.getFileName().toString();
    }

    /**
     * The RFG scaffold drives the game from the build script's mcVersion, while the model reads the
     * properties pin: the owned game line becomes mcVersion, and any second game pin goes, so the
     * re-evaluated build selects exactly the target.
     */
    static void retargetGamePin(Target.Setup setup, Build build, Path directory, Map<String, Object> pins, String to)
        throws Exception {
        List<Path> owners = new ArrayList<>();
        for (Path file : build.project.inputs()) {
            if (!file.getFileName().toString().equals("gradle.properties")) continue;
            if (gamePinOwner(setup, file, pins)) owners.add(file);
        }
        Path own = directory.resolve("gradle.properties");
        Path owner = owners.contains(own) ? own : owners.size() == 1 ? owners.get(0) : null;
        if (owner == null) throw new IOException(
            "Cannot choose an unambiguous gradle.properties owner for the game pin: " + owners
        );
        List<String> out = new ArrayList<>();
        boolean swapped = false;
        for (String line : setup.text(owner).split("\n", -1)) {
            if (!gamePin(line)) out.add(line);
            else if (!swapped) {
                out.add("mcVersion = " + to);
                swapped = true;
            }
        }
        if (!swapped) throw new IOException("Cannot find the game pin in " + owner);
        setup.rewrite(owner, String.join("\n", out));
    }

    /** A gradle.properties file carrying one of the model's game pins at its pinned value. */
    static boolean gamePinOwner(Target.Setup setup, Path file, Map<String, Object> pins) throws IOException {
        for (String key : List.of(
            "minecraft_version",
            "minecraftVersion",
            "exactMinecraftVersion",
            "mc_version",
            "mcVersion"
        )) {
            Object pin = pins.get(key);
            if (pin == null) continue;
            for (String line : setup.text(file).split("\n", -1)) {
                String stripped = line.strip();
                if (!stripped.startsWith(key)) continue;
                String rest = stripped.substring(key.length()).strip();
                if ((rest.startsWith("=") || rest.startsWith(":")) && rest.substring(1).strip().equals(
                    String.valueOf(pin)
                )) return true;
            }
        }
        return false;
    }

    /** A gradle.properties game line, whatever its spelling: the scaffold owns exactly one game pin. */
    static boolean gamePin(String line) {
        String stripped = line.strip();
        for (String key : List.of(
            "minecraft_version",
            "minecraftVersion",
            "exactMinecraftVersion",
            "mc_version",
            "mcVersion"
        )) {
            if (!stripped.startsWith(key)) continue;
            String rest = stripped.substring(key.length()).strip();
            if (rest.startsWith("=") || rest.startsWith(":")) return true;
        }
        return false;
    }

    /**
     * The scaffold build: the direct RFG plugin from the GTNH example, with the port's toolchain
     * (Java 17 compiles the modern sources, bytecode stays 8) instead of the example's Java 8. No vendor: RFG copies
     * this toolchain's vendor into its own game tasks, and the JDKs mmt hands Gradle are Temurin, so pinning Azul
     * stopped the build on any machine without one.
     */
    static String rfgBuildGradle(String rfg) {
        return String.join(
            "\n",
            "plugins {",
            "    id(\"java\")",
            "    id(\"com.gtnewhorizons.retrofuturagradle\") version \"" + rfg + "\"",
            "}",
            "",
            "// The port keeps the mod's own coordinates; the scaffold only swaps the game target.",
            "java {",
            "    toolchain {",
            "        languageVersion.set(JavaLanguageVersion.of(17))",
            "    }",
            "    withSourcesJar()",
            "}",
            "",
            "minecraft {",
            "    mcVersion.set(\"1.7.10\")",
            "    username.set(\"Developer\")",
            "}",
            "",
            "// Modern sources compile down to Java 8 bytecode; RFG's own Minecraft tasks keep theirs.",
            "tasks.named(\"compileJava\") {",
            "    options.release.set(8)",
            "}",
            "tasks.named(\"compileTestJava\") {",
            "    options.release.set(8)",
            "}",
            "",
            "tasks.processResources {",
            "    filesMatching(\"mcmod.info\") {",
            "        expand(project.properties)",
            "    }",
            "}",
            "",
            "repositories {",
            "    maven {",
            "        name = \"GTNH Maven\"",
            "        url = uri(\"https://nexus.gtnewhorizons.com/repository/public/\")",
            "    }",
            "    mavenCentral()",
            "}",
            ""
        );
    }

    /** The scaffold settings: the GTNH example's pluginManagement, keeping the project's name. */
    static String rfgSettingsGradle(String rootName) {
        String safe = rootName.replace("\\", "\\\\").replace("\"", "\\\"");
        return String.join(
            "\n",
            "// The pluginManagement block must come before any other statement in the file.",
            "pluginManagement {",
            "    repositories {",
            "        maven {",
            "            name = \"GTNH Maven\"",
            "            url = uri(\"https://nexus.gtnewhorizons.com/repository/public/\")",
            "            mavenContent {",
            "                includeGroupByRegex(\"com\\\\.gtnewhorizons\\\\..+\")",
            "                includeGroup(\"com.gtnewhorizons\")",
            "            }",
            "        }",
            "        gradlePluginPortal()",
            "        mavenCentral()",
            "        mavenLocal()",
            "    }",
            "    plugins {",
            "        id(\"org.gradle.toolchains.foojay-resolver-convention\") version \"1.0.0\"",
            "    }",
            "}",
            "",
            "rootProject.name = \"" + safe + "\"",
            ""
        );
    }

    /** RFG 2 resolves its daemon through this file; its plugin needs Java 25. */
    static String rfgDaemonJvmProperties() {
        return "toolchainVersion=25\n";
    }

    /**
     * A multi-loader build (Architectury, MultiLoader) keeps Forge's version beside Fabric's and NeoForge's, and Gradle
     * configures every project: a Forge project left on the old game stops the whole build (Chat Heads 1.21.4 -> 1.21.5
     * asked for forge 1.21.5-54.0.16, which does not exist). Forge's pin moves to Forge's newest release for the target
     * (maven.minecraftforge.net); when Forge publishes none, an Architectury enabled_platforms list drops forge, so the
     * other loaders still build. The game version ranges the build keeps for each loader's metadata follow the target.
     */
    static void forgeSibling(Target.Setup setup, Build build, Map<String, Object> pins, String to) throws Exception {
        // port moves Fabric and NeoForge projects, never Forge ones, and a Forge project left in breaks the whole build's
        // configuration once Gradle moves past what ForgeGradle supports (No Chat Reports, Sound Physics, Carry On). So a
        // Forge project included by name leaves the build (undo puts it back); the version pins are retargeted otherwise.
        if (dropForgeProject(setup, build, to, "mmt port moves only the Fabric and NeoForge projects; a Forge project left in stops the build's configuration")) {
            if (pins.containsKey("enabled_platforms") && String.valueOf(pins.get("enabled_platforms")).contains("forge")) setup.replacements.put(
                "enabled_platforms",
                String.join(",", Arrays.stream(String.valueOf(pins.get("enabled_platforms")).split(",")).map(String::trim).filter(p -> !p.equals("forge")).toList())
            );
            return;
        }
        for (String key : List.of("forge_version", "forgeVersion", "minecraft_forge_version")) if (pins.containsKey(key)) {
            String old = String.valueOf(pins.get(key)), release;
            try {
                release = forgeRelease(Target.fetch(build, "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml"), to);
            } catch (IOException unreachable) {
                setup.unchecked.add(Json.map("artifact", "net.minecraftforge:forge (sibling project)", "version", to, "reason", unreachable.getMessage()));
                continue;
            }
            if (release != null) {
                setup.replacements.put(key, old.contains("-") ? release : release.substring(release.indexOf('-') + 1));
                setup.applied.add(
                    Json.map("artifact", "net.minecraftforge:forge", "to", release, "evidence", "the build's Forge project follows the same game: Forge " + release + " publishes for " + to)
                );
            } else if (pins.containsKey("enabled_platforms") && String.valueOf(pins.get("enabled_platforms")).contains("forge")) {
                String platforms = String.join(
                    ",",
                    Arrays.stream(String.valueOf(pins.get("enabled_platforms")).split(",")).map(String::trim).filter(p -> !p.equals("forge")).toList()
                );
                setup.replacements.put("enabled_platforms", platforms);
                setup.applied.add(
                    Json.map("change", "enabled_platforms drops forge for " + to, "evidence", "Forge publishes no release for Minecraft " + to + " (maven.minecraftforge.net)")
                );
            } else if (!dropForgeProject(setup, build, to, "Forge publishes no release for Minecraft " + to + " (maven.minecraftforge.net)")) setup.unchecked.add(
                Json.map("artifact", "net.minecraftforge:forge (sibling project)", "version", to, "reason", "Forge publishes no release for Minecraft " + to)
            );
        }
        for (var pin : pins.entrySet()) {
            String key = pin.getKey(), value = String.valueOf(pin.getValue());
            if (key.equals("minecraft_version_range") || setup.replacements.containsKey(key)) continue;
            if (!key.matches("(?i)(?:forge|neoforge|neo|fabric|quilt)_?(?:game|minecraft|mc)_?version_?range")) continue;
            if (!value.matches("[\\[(][^,]*,[^,]*[\\])]") || value.contains(to)) continue;
            setup.replacements.put(key, "[" + to + "]");
        }
    }

    /** Forge's newest release for one game version in its maven-metadata.xml ("1.21.5-55.0.21"), or null. */
    static String forgeRelease(String xml, String to) {
        String newest = null;
        Matcher version = Pattern.compile("<version>(" + Pattern.quote(to) + "-[0-9][^<]*)</version>").matcher(xml);
        while (version.find()) {
            String v = version.group(1);
            if (newest == null || Versions.compare(v.substring(to.length() + 1), newest.substring(to.length() + 1)) > 0) newest = v;
        }
        return newest;
    }

    /**
     * A multi-loader build that includes its Forge project by name in settings.gradle (ImmediatelyFast: include(":forge"))
     * cannot configure it for a game Forge publishes nothing for, and Gradle configures every included project. The Forge
     * project leaves the include list, so the other loaders build; port does not port Forge projects in any case.
     */
    static boolean dropForgeProject(Target.Setup setup, Build build, String to, String evidence) throws IOException {
        boolean dropped = false;
        for (String name : List.of("settings.gradle", "settings.gradle.kts")) {
            Path settings = build.project.root.resolve(name);
            if (!Files.isRegularFile(settings)) continue;
            String before = setup.text(settings), text = withoutForgeInclude(before);
            if (text.equals(before)) continue;
            setup.originals.putIfAbsent(settings, before);
            setup.texts.put(settings, text);
            setup.applied.add(
                Json.map(
                    "file",
                    settings.toString(),
                    "change",
                    "Forge project left out of the build for " + to,
                    "evidence",
                    evidence
                )
            );
            dropped = true;
        }
        return dropped;
    }

    /** A settings script without its Forge project: a one-name include line goes, a name in a longer list leaves it. */
    static String withoutForgeInclude(String text) {
        String forge = "[\"']:?[Ff]orge[\"']";
        text = text.replaceAll("(?m)^[ \\t]*include\\s*\\(?\\s*" + forge + "\\s*\\)?[ \\t]*(?:\\r?\\n|\\z)", "");
        text = text.replaceAll("(?m)^([ \\t]*include\\b[^\\n]*?),\\s*" + forge, "$1");
        text = text.replaceAll("(?m)^([ \\t]*include\\s*\\(?\\s*)" + forge + "\\s*,\\s*", "$1");
        // A line that configures the Forge project by path (No Chat Reports: project(":forge").name = "Forge") goes with it.
        return text.replaceAll("(?m)^[ \\t]*project\\s*\\(\\s*" + forge + "\\s*\\)[^\\n]*(?:\\r?\\n|\\z)", "");
    }
}

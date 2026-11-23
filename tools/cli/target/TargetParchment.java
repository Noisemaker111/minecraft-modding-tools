import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** The Parchment mappings a build names, moved to the release that has them. */
final class TargetParchment {

    /**
     * A parchment { mappingsVersion = project.KEY } block whose property is not one of the conventional names (Ars Nouveau:
     * parchment_version, beside minecraftVersion = project.minecraft_version): the property moves to Parchment's release for
     * the target, a separate Minecraft property to the target, and the block goes when Parchment has no release for it.
     */
    static void parchmentBlockFollows(Target.Setup setup, Build build, String to) throws Exception {
        Path properties = build.project.root.resolve("gradle.properties");
        if (!Files.isRegularFile(properties)) return;
        for (String name : List.of("build.gradle", "build.gradle.kts")) {
            Path script = build.project.root.resolve(name);
            if (!Files.isRegularFile(script)) continue;
            String text = setup.text(script);
            Matcher block = Pattern.compile("(?m)^[ \\t]*parchment\\s*\\{([^{}]*)\\}").matcher(text);
            if (!block.find()) continue;
            Matcher mappings = Pattern.compile("mappingsVersion\\s*(?:=|\\.set\\()\\s*(?:project\\.)?(?:property\\(\\s*[\"'])?(\\w+)").matcher(block.group(1));
            if (!mappings.find()) continue;
            String key = mappings.group(1);
            if (key.equals("parchment_mappings_version") || !Pattern.compile("(?m)^[ \\t]*" + Pattern.quote(key) + "[ \\t]*=").matcher(setup.text(properties)).find()) continue;
            String release = parchmentRelease(build, to);
            if (release.isEmpty()) {
                setup.originals.putIfAbsent(script, text);
                setup.texts.put(script, Target.removeStatements(text, Pattern.compile("(?m)^[ \\t]*parchment\\s*\\{")));
                setup.applied.add(
                    Json.map(
                        "file",
                        script.toString(),
                        "change",
                        "parchment block removed for " + to,
                        "evidence",
                        "Parchment publishes no parameter names for " + to + " (maven.parchmentmc.org)"
                    )
                );
                continue;
            }
            Target.setProperty(setup, properties, key, release);
            Matcher minecraft = Pattern.compile("minecraftVersion\\s*(?:=|\\.set\\()\\s*(?:project\\.)?(?:property\\(\\s*[\"'])?(\\w+)").matcher(block.group(1));
            if (minecraft.find() && !minecraft.group(1).matches("minecraft_version|minecraftVersion|mc_version")) Target.setProperty(setup, properties, minecraft.group(1), to);
            setup.applied.add(Json.map("change", "Parchment " + key + " -> " + release + " for " + to, "evidence", "Parchment " + release + " is its newest release for " + to));
        }
    }

    /** Parchment's newest release of parameter names for exactly this Minecraft version, or "" when it publishes none. */
    static String parchmentRelease(Build build, String to) throws Exception {
        String xml;
        try {
            xml = Target.fetch(
                build,
                "https://maven.parchmentmc.org/org/parchmentmc/data/parchment-" + to + "/maven-metadata.xml"
            );
        } catch (Target.LookupFailure couldNotAsk) {
            throw couldNotAsk;
        } catch (IOException answered) {
            if (answered.getMessage() != null && answered.getMessage().contains("(HTTP 404)")) return "";
            throw answered;
        }
        Matcher release = Pattern.compile("<release>([^<]+)</release>").matcher(xml);
        if (release.find()) return release.group(1);
        String last = "";
        Matcher version = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        while (version.find()) if (!version.group(1).contains("SNAPSHOT")) last = version.group(1);
        return last;
    }

    /**
     * Parchment follows the target. Parchment names a game's parameters by method, for one Minecraft version; applied to
     * another version its names can collide with the real ones. Seen on 2026-11-08: 1.21.5 names on the 26.3 game turned
     * BundlerInfo's "packet == delimiterPacket" into "packet == packet", so every packet started a bundle and a
     * singleplayer world never loaded. So the names move to Parchment's release for exactly the target version, and when
     * Parchment has none (26.x ships its own names) the setting is removed: without it only decompiled names are plainer.
     * Covers NeoGradle's neogradle.subsystems.parchment.* properties, ModDevGradle's parchment_* properties and their
     * parchment { } block, and Loom's layered parchment("org.parchmentmc.data:parchment-VERSION:...") coordinate.
     */
    static void parchmentFollows(Target.Setup setup, Build build, String to) throws Exception {
        parchmentEdit(setup, build, to, null);
        parchmentBlockFollows(setup, build, to);
    }

    /** parchmentFollows with Parchment's release for the target given ("" for none), or null to look it up only if the build states Parchment. */
    static boolean parchmentEdit(Target.Setup setup, Build build, String to, String known) throws Exception {
        Path properties = build.project.root.resolve("gradle.properties");
        String props = Files.isRegularFile(properties) ? setup.text(properties) : "";
        Matcher neoGradle = Pattern.compile(
            "(?m)^[ \\t]*neogradle\\.subsystems\\.parchment\\.minecraftVersion[ \\t]*=[ \\t]*(\\S+)"
        ).matcher(props);
        Matcher modDev = Pattern.compile("(?m)^[ \\t]*parchment_minecraft_version[ \\t]*=[ \\t]*(\\S+)").matcher(props);
        List<Path> scripts = new ArrayList<>();
        for (String name : List.of("build.gradle", "build.gradle.kts")) if (
            Files.isRegularFile(build.project.root.resolve(name))
        ) scripts.add(build.project.root.resolve(name));
        // A multi-module build keeps its parchment block in a module's script (Repurposed Structures' :common).
        for (Path input : build.project.inputs()) if (input.getFileName().toString().matches("build\\.gradle(\\.kts)?") && !scripts.contains(input)) scripts.add(input);
        Pattern layered = Pattern.compile(
            "parchment\\(\\s*[\"']org\\.parchmentmc\\.data:parchment-([^:\"']+):([^@\"']+)@zip[\"']\\s*\\)"
        );
        Path loomScript = null;
        Matcher loom = null;
        for (Path script : scripts) {
            String scriptText = setup.text(script);
            Matcher found = layered.matcher(scriptText);
            boolean active = false;
            // A commented-out layer (FerriteCore: //parchment(...)) is not in the build.
            while (!active && found.find()) active = !scriptText.substring(scriptText.lastIndexOf('\n', found.start()) + 1, found.start()).trim().startsWith("//");
            if (active) {
                loomScript = script;
                loom = found;
                break;
            }
        }
        boolean hasNeoGradle = neoGradle.find(), hasModDev = modDev.find();
        if (!hasNeoGradle && !hasModDev && loom == null) return false;
        String stated = hasNeoGradle ? neoGradle.group(1) : hasModDev ? modDev.group(1) : loom.group(1);
        String release = known != null ? known : parchmentRelease(build, to);
        String evidence = release.isEmpty()
            ? "Parchment publishes no parameter names for " + to + " (maven.parchmentmc.org)"
            : "Parchment " + release + " is its newest release for " + to;
        if (hasNeoGradle) {
            if (release.isEmpty()) removeLines(
                setup,
                properties,
                "neogradle\\.subsystems\\.parchment\\.[\\w.]+[ \\t]*=.*"
            );
            else {
                Target.setProperty(setup, properties, "neogradle.subsystems.parchment.minecraftVersion", to);
                Target.setProperty(setup, properties, "neogradle.subsystems.parchment.mappingsVersion", release);
            }
        }
        if (hasModDev) {
            if (release.isEmpty()) {
                boolean removed = false;
                for (Path script : scripts) {
                    String text = setup.text(script);
                    Matcher block = Pattern.compile(
                        "(?m)^[ \\t]*parchment\\s*\\{[^{}]*parchment_(?:minecraft|mappings)_version[^{}]*\\}[ \\t]*\\r?\\n"
                    ).matcher(text);
                    if (block.find()) {
                        setup.replace(script, block.group(), "");
                        removed = true;
                    }
                }
                if (!removed) throw new IOException(
                    "gradle.properties sets parchment_minecraft_version=" +
                    stated +
                    ", Parchment publishes no names for " +
                    to +
                    ", and no parchment { } block reading it was found to remove. Remove the Parchment setting from the build, then rerun port."
                );
                removeLines(setup, properties, "parchment_(?:minecraft|mappings)_version[ \\t]*=.*");
            } else {
                Target.setProperty(setup, properties, "parchment_minecraft_version", to);
                Target.setProperty(setup, properties, "parchment_mappings_version", release);
            }
        }
        if (loom != null) {
            String text = setup.text(loomScript);
            Matcher line = Pattern.compile("(?m)^[ \\t]*" + Pattern.quote(loom.group()) + "[ \\t]*\\r?\\n").matcher(
                text
            );
            if (release.isEmpty() && line.find()) setup.replace(loomScript, line.group(), "");
            else if (release.isEmpty()) throw new IOException(
                loomScript.getFileName() +
                " layers Parchment " +
                loom.group(1) +
                " into its mappings and Parchment publishes no names for " +
                to +
                "; remove that parchment(...) layer, then rerun port."
            );
            else setup.replace(
                loomScript,
                loom.group(),
                "parchment(\"org.parchmentmc.data:parchment-" + to + ":" + release + "@zip\")"
            );
        }
        setup.applied.add(
            Json.map(
                "change",
                release.isEmpty()
                    ? "Parchment " + stated + " parameter names removed for " + to
                    : "Parchment " + stated + " -> " + to + " " + release,
                "evidence",
                evidence
            )
        );
        return true;
    }

    private static void removeLines(Target.Setup setup, Path properties, String line) throws IOException {
        Matcher found = Pattern.compile("(?m)^[ \\t]*" + line + "(?:\\r?\\n|$)").matcher(setup.text(properties));
        List<String> lines = new ArrayList<>();
        while (found.find()) lines.add(found.group());
        for (String each : lines) setup.replace(properties, each, "");
    }
}

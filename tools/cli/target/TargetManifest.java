import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** The game version ranges a mod's manifests declare, moved to the target release. */
final class TargetManifest {

    /** Retarget one owned source manifest; a range that already accepts the target is left byte-exact. */
    /**
     * A Minecraft range written out in neoforge.mods.toml (Corpse: versionRange = "[1.21,1.21.1]") that does not take the
     * target becomes the target, as a property-held range does; NeoForge would refuse the mod at load otherwise.
     */
    static void literalGameRange(Target.Setup setup, String to) throws IOException {
        Object dir = setup.project.get("directory");
        if (dir == null) return;
        for (String name : List.of("src/main/resources/META-INF/neoforge.mods.toml", "src/main/templates/META-INF/neoforge.mods.toml", "src/main/resources/META-INF/mods.toml")) {
            Path file = Path.of(dir.toString()).resolve(name);
            if (!Files.isRegularFile(file)) continue;
            String text = setup.text(file);
            Matcher block = Pattern.compile("(?s)\\[\\[dependencies\\.[^\\]]+\\]\\](.*?)(?=\\[\\[|\\z)").matcher(text);
            while (block.find()) {
                String body = block.group(1);
                if (!body.matches("(?s).*\\bmodId\\s*=\\s*\"minecraft\".*")) continue;
                Matcher range = Pattern.compile("versionRange\\s*=\\s*\"([^\"$]*)\"").matcher(body);
                if (!range.find()) continue;
                boolean accepted;
                try {
                    accepted = range.group(1).isBlank() || range.group(1).equals("*") || MavenRange.own(range.group(1), to);
                } catch (RuntimeException unreadable) {
                    accepted = true;
                }
                if (accepted) continue;
                setup.replace(file, block.group(), block.group().replace(range.group(), "versionRange = \"[" + to + "]\""));
                String change = "Minecraft range " + range.group(1) + " -> [" + to + "]";
                setup.applied.add(Json.map("file", file.toString(), "change", change, "evidence", "the range is written in the mods.toml and does not take " + to));
            }
        }
    }

    static void manifest(Target.Setup setup) throws Exception {
        for (Path file : manifests(setup.project)) {
            String before = setup.text(file);
            Map<String, Object> metadata;
            try {
                metadata = Json.object(Json.parse(before));
            } catch (IllegalArgumentException invalid) {
                throw new IOException(
                    "Cannot safely edit source Fabric manifest " + file + ": " + invalid.getMessage(),
                    invalid
                );
            }
            if (!(metadata.get("depends") instanceof Map<?, ?>)) continue;
            var depends = Json.object(metadata.get("depends"));
            if (!depends.containsKey("minecraft")) continue;
            // A list of alternatives (Sound Physics: ["1.21", "1.21.1"]) accepts the target when one of them does; when none
            // does, the list becomes the target release, as a single constraint would.
            if (depends.get("minecraft") instanceof List<?> alternatives && alternatives.stream().allMatch(a -> a instanceof String)) {
                boolean accepted = false;
                for (Object a : alternatives) if (String.valueOf(a).contains("${") || Versions.matches(String.valueOf(a), setup.to)) accepted = true;
                if (accepted) continue;
                Matcher list = Pattern.compile("(\"minecraft\"\\s*:\\s*)\\[[^\\]]*\\]").matcher(before);
                if (!list.find()) throw new IOException("Cannot locate depends.minecraft in " + file + "; update its owner manually.");
                int at = list.start();
                if (list.find()) throw new IOException("Fabric manifest names minecraft lists twice in " + file + "; update its owner manually.");
                Matcher only = Pattern.compile("(\"minecraft\"\\s*:\\s*)\\[[^\\]]*\\]").matcher(before);
                only.find(at);
                setup.replace(file, before, before.substring(0, only.start()) + only.group(1) + "\"" + setup.to + "\"" + before.substring(only.end()));
                continue;
            }
            if (!(depends.get("minecraft") instanceof String constraint)) throw new IOException(
                "Fabric manifest depends.minecraft is not one literal string in " +
                file +
                "; update its owner manually."
            );
            // The range filled in from a property when the jar is built (Trash Cans: "${minecraft_dependency}", with
            // minecraft_dependency=>=1.21.4 <1.21.5): the property is the owner, retargeted the same way.
            Matcher placeholder = Pattern.compile("^\\$\\{(\\w+)\\}$").matcher(constraint.trim());
            if (placeholder.find() && setup.build != null) {
                Path properties = setup.build.project.root.resolve("gradle.properties");
                Matcher owner = Pattern.compile("(?m)^[ \\t]*" + Pattern.quote(placeholder.group(1)) + "[ \\t]*=[ \\t]*([^\\r\\n]*?)[ \\t]*$").matcher(
                    Files.isRegularFile(properties) ? setup.text(properties) : ""
                );
                if (owner.find() && !owner.group(1).contains("$") && !Versions.matches(owner.group(1), setup.to)) {
                    Target.setProperty(setup, properties, placeholder.group(1), setup.to);
                }
                continue;
            }
            if (constraint.contains("${") || constraint.equals(toRange(setup.to))) continue;
            if (Versions.matches(constraint, setup.to)) continue;
            // A range that excludes the target (">=26.3" ported to 26.2, ">=1.20.1 <1.21" to 1.21.6) would make Fabric refuse
            // the ported mod, so it becomes the target release, as a plain version does; the edit is one literal in the receipt.
            setup.replace(file, before, retargetMinecraft(before, constraint, setup.to, file));
        }
    }

    /**
     * Change only the depends.minecraft literal and keep every other byte, so the diff a mod author
     * reviews is one line instead of a re-serialized manifest.
     */
    static String retargetMinecraft(String text, String constraint, String to, Path file) throws IOException {
        Matcher depends = Pattern.compile("\"depends\"\\s*:\\s*\\{").matcher(text);
        int start = -1, end = -1;
        while (depends.find()) {
            if (depth(text, depends.start()) != 1) continue;
            if (start >= 0) throw new IOException(
                "Fabric manifest has two depends objects in " + file + "; update its owner manually."
            );
            start = depends.end();
            end = closing(text, depends.end() - 1);
        }
        if (start < 0 || end < 0) throw new IOException(
            "Cannot locate depends in Fabric manifest " + file + "; update its owner manually."
        );
        String block = text.substring(start, end);
        Matcher entry = Pattern.compile("(\"minecraft\"\\s*:\\s*\")" + Pattern.quote(constraint) + "\"").matcher(block);
        if (!entry.find()) throw new IOException(
            "Cannot locate depends.minecraft \"" + constraint + "\" in " + file + "; update its owner manually."
        );
        int at = entry.start(), after = entry.end();
        String key = entry.group(1);
        if (entry.find()) throw new IOException(
            "Fabric manifest names minecraft twice in depends in " + file + "; update its owner manually."
        );
        return text.substring(0, start + at) + key + to + "\"" + text.substring(start + after);
    }

    /** JSON object depth at a position, ignoring braces inside strings. */
    private static int depth(String text, int position) {
        int depth = 0;
        boolean string = false;
        for (int i = 0; i < position; i++) {
            char c = text.charAt(i);
            if (string) {
                if (c == '\\') i++;
                else if (c == '"') string = false;
            } else if (c == '"') string = true;
            else if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
        }
        return depth;
    }

    /** Index of the brace that closes the object opened at {@code open}, or -1. */
    private static int closing(String text, int open) {
        int depth = 0;
        boolean string = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (string) {
                if (c == '\\') i++;
                else if (c == '"') string = false;
            } else if (c == '"') string = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    private static String toRange(String to) {
        return to;
    }

    static List<Target.Edit> manifestEditsForModel(Map<String, Object> model, String to) throws Exception {
        List<Target.Edit> edits = new ArrayList<>();
        for (Object item : Json.array(model.get("projects"))) {
            var project = Json.object(item);
            if (!Support.loaders(project).contains("fabric")) continue;
            Target.Setup setup = new Target.Setup(null, project, to, List.of());
            manifest(setup);
            edits.addAll(setup.edits());
        }
        return edits;
    }

    static List<Target.Edit> manifestEdits(Map<String, Object> project, String to) throws Exception {
        Target.Setup setup = new Target.Setup(null, project, to, List.of());
        manifest(setup);
        return setup.edits();
    }

    static Set<Path> manifests(Map<String, Object> project) throws Exception {
        Path directory = Path.of(project.get("directory").toString()).toRealPath();
        Set<Path> manifests = new TreeSet<>();
        for (Object item : Json.array(project.get("sourceSets"))) {
            var set = Json.object(item);
            if (!Set.of("main", "client").contains(Json.text(set, "name", ""))) continue;
            for (String resource : ProjectModel.strings(set.get("resources"))) {
                Path root = Path.of(resource).toAbsolutePath().normalize();
                // A model can report a Windows 8.3 or symlinked path for the same directory; compare real paths.
                try {
                    root = root.toRealPath();
                } catch (IOException missing) {}
                if (!root.startsWith(directory)) throw new IOException(
                    "Project " +
                    project.get("path") +
                    " reads its resources from " +
                    root +
                    ", a folder it shares with other projects" +
                    " (a Stonecutter build does this: every version node compiles the same src/). Porting one node would change the files every other node uses, so mmt does not edit them." +
                    " To build another Minecraft version, select the node that already targets it (mmt setup lists them, e.g. --project :26.2), or add a node for it in settings.gradle.kts. Nothing was changed."
                );
                Path file = root.resolve("fabric.mod.json");
                if (Files.isRegularFile(file)) {
                    Path owned = file.toRealPath();
                    if (!owned.startsWith(directory)) throw new IOException(
                        "Fabric manifest resolves outside the selected project: " + owned
                    );
                    manifests.add(owned);
                }
            }
        }
        if (manifests.size() > 1) throw new IOException(
            "Multiple source fabric.mod.json files in selected project; choose the Minecraft dependency owner manually: " +
            manifests
        );
        return manifests;
    }
}

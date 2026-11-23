import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** What both bridges share: the bridge layer in use, its mixins, and filling in only the classes a mod uses. */
final class ConvertBridges {

    final Convert convert;

    ConvertBridges(Convert convert) {
        this.convert = convert;
    }

    /** The interfaces the Fabric bridge injects into game classes, as ModDevGradle reads them (interfaceInjectionData). */
    static final String INJECTED_INTERFACES = "fabricbridge-interfaces.json";
    Map<String, Object> bridge;

    /** The bridge's version layer for a game older than the bridge's own files, or null (knowledge/loaders.json bridges[].layers). */
    Map<String, Object> bridgeLayer;

    /**
     * The newest layer written for a game version at or below this one, when the version is older than the bridge's own
     * files: its files replace the bridge's files of the same name, since the game's and Fabric API's names differ there.
     */
    static Map<String, Object> bridgeLayer(Map<String, Object> bridge, String minecraft) {
        if (Versions.compare(minecraft, Json.text(bridge, "minecraft", "99")) >= 0) return null;
        Map<String, Object> best = null;
        for (Object item : LoaderRules.list(bridge.get("layers"))) {
            var layer = Json.object(item);
            String from = Json.text(layer, "minecraft", "99");
            if (Versions.compare(minecraft, from) < 0) continue;
            // A layer is checked against the releases of its own range only.
            if (layer.get("below") != null && Versions.compare(minecraft, Json.text(layer, "below", "")) >= 0) continue;
            if (best == null || Versions.compare(from, Json.text(best, "minecraft", "")) > 0) best = layer;
        }
        return best;
    }

    /** The mixin config of the bridge's mixins this mod needs (src/main/resources), or null when it needs none. */
    String bridgeMixinConfig;

    /** Interfaces the bridge's mixins add to game classes, by class (fabric.mod.json loom:injected_interfaces). */
    final Map<String, List<String>> bridgeInjected = new LinkedHashMap<>();

    /** The mod's own sources (outside the bridge), which decide the bridge's "// bridge: when NAME" lines. */
    String bridgeUsers = "";

    /** A bridge template with the mod's own names written in, and only the lines for what the mod uses. */
    String fillBridge(String template, String pkg) {
        // Line ends as the bridge's files are written (a Windows checkout may give the templates CRLF).
        String text = template.replace("\r\n", "\n").replace("${package}", pkg).replace("${modid}", convert.modid == null ? "" : convert.modid);
        return whenUsed(text, bridgeUsers);
    }

    /**
     * A bridge line ending in "// bridge: when NAME" is kept (without the marker) only when the mod's own code names the
     * bridge class NAME: what the bridge registers for a feature (an attachment type, a listener) is then registered only
     * for mods that use the feature, as the other loader registers nothing for it.
     */
    static String whenUsed(String text, String users) {
        Matcher m = Pattern.compile("(?m)^(.*?)[ \\t]*//[ \\t]*bridge: when (\\w+)[ \\t]*\\n").matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            boolean used = Pattern.compile("\\b" + Pattern.quote(m.group(2)) + "\\b").matcher(users).find();
            m.appendReplacement(out, Matcher.quoteReplacement(used ? m.group(1) + "\n" : ""));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The mod's own sources, outside a bridge folder. */
    String sourcesOutside(String folder) throws IOException {
        StringBuilder all = new StringBuilder();
        for (String file : convert.projectFiles.javaFiles()) if (!file.startsWith(folder + "/")) all.append(convert.projectFiles.read(file)).append('\n');
        return all.toString();
    }

    /**
     * Whether a version layer leaves out one of the bridge's files (bridges[].layers[].drops: path prefixes under the
     * bridge folder, such as "transfer/"): parts of the bridge written for a newer game than the layer's.
     */
    /**
     * A version layer's "rewrites" (knowledge/loaders.json): whole-word renames applied to the bridge's base files for that
     * game, for names that differ there and nothing else (Identifier for ResourceLocation), so such a file needs no copy.
     */
    static String layerRewrites(String text, Map<String, Object> rewrites) {
        String out = text;
        for (var r : rewrites.entrySet()) out = out.replaceAll(
            "(?<![\\w$])" + Pattern.quote(r.getKey()) + "(?![\\w$])",
            Matcher.quoteReplacement(String.valueOf(r.getValue()))
        );
        return out;
    }

    static boolean dropped(List<String> drops, String rel) {
        for (String prefix : drops) if (rel.startsWith(prefix)) return true;
        return false;
    }

    /**
     * A bridge's template files by their path under its folder (sub-packages kept), the version layer's files replacing
     * the bridge's own of the same path. Version layers are the bridge folder's sub-folders named by a Minecraft version.
     */
    static Map<String, Path> bridgeTemplates(Path folder, Path layer) throws IOException {
        Map<String, Path> files = new TreeMap<>();
        for (Path root : layer == null ? List.of(folder) : List.of(folder, layer)) {
            try (var walk = Files.walk(root)) {
                for (Path template : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    Path rel = root.relativize(template);
                    // The bridge's own folder holds its version layers (1.21.1/...): those are read only as the layer.
                    if (root == folder && rel.getNameCount() > 1 && Character.isDigit(rel.getName(0).toString().charAt(0))) continue;
                    files.put(rel.toString().replace('\\', '/'), template);
                }
            }
        }
        return files;
    }

    /**
     * The bridge's mixins (knowledge/loaders.json bridges[].mixins, or its version layer's) that the converted code needs:
     * a mixin is listed when a source file outside the bridge imports one of the bridge classes it fires ("for"). They go
     * in one mixin config, so a mod that uses none of those events gets none of the bridge's mixins. Returns its file name.
     */
    String bridgeMixins(Map<String, Object> b, Map<String, Object> layer, String pkg, List<String> sources) throws Exception {
        Object list = layer != null && layer.get("mixins") != null ? layer.get("mixins") : b.get("mixins");
        List<String> common = new ArrayList<>(), client = new ArrayList<>(), texts = new ArrayList<>();
        StringBuilder all = new StringBuilder();
        for (String file : sources) {
            String source = convert.projectFiles.read(file);
            texts.add(source);
            all.append(source).append('\n');
        }
        String text = all.toString();
        for (Object item : LoaderRules.list(list)) {
            var mixin = Json.object(item);
            boolean needed = uses(mixin.get("uses"), texts);
            for (String target : LoaderRules.strings(mixin.get("for"))) if (
                Pattern.compile("(?m)^import\\s+(?:static\\s+)?" + Pattern.quote(pkg + "." + target) + "\\b").matcher(text).find()
            ) needed = true;
            if (!needed) continue;
            // A mixin of a game version this one does not have (its file is only in another version layer) is not listed.
            if (!convert.projectFiles.exists("src/main/java/" + pkg.replace('.', '/') + "/mixin/" + Json.text(mixin, "mixin", "").replace('.', '/') + ".java")) continue;
            (Boolean.TRUE.equals(mixin.get("client")) ? client : common).add(Json.text(mixin, "mixin", ""));
            // A mixin that adds one of the bridge's interfaces to a game class: Loom injects it for the mod's compile.
            if (mixin.get("injects") instanceof Map<?, ?> injects) for (var e : Json.object(injects).entrySet()) bridgeInjected
                .computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                .add((pkg + "." + e.getValue()).replace('.', '/'));
        }
        if (common.isEmpty() && client.isEmpty()) return null;
        String name = (convert.fabricId == null || convert.fabricId.isEmpty() ? convert.modid : convert.fabricId) + "." + pkg.substring(pkg.lastIndexOf('.') + 1) + ".mixins.json";
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("required", true);
        config.put("minVersion", "0.8");
        config.put("package", pkg + ".mixin");
        config.put("compatibilityLevel", Versions.compare(convert.gameVersion, Route.FIRST_UNOBFUSCATED) >= 0 ? "JAVA_25" : "JAVA_21");
        if (!common.isEmpty()) config.put("mixins", common);
        if (!client.isEmpty()) config.put("client", client);
        config.put("injectors", Json.map("defaultRequire", 1));
        convert.projectFiles.write("src/main/resources/" + name, Convert.pretty(config, "") + "\n");
        List<String> listed = new ArrayList<>(common);
        for (String mixin : client) listed.add("client " + mixin);
        convert.notes.add(
            "Bridge mixins (" + name + "): " + String.join(", ", listed) +
            ", which fire the events the code listens to at the point the other loader fires them."
        );
        convert.applied.computeIfAbsent("bridge-mixins", k -> new ArrayList<>()).add("src/main/resources/" + name);
        return name;
    }

    /**
     * Whether a bridge mixin's "uses" finds the code that needs it: alternatives, each a list of patterns all found in one
     * source file. Code that calls what Fabric injects into a game class (builder.contains(type)) imports no bridge class.
     */
    static boolean uses(Object uses, List<String> texts) {
        for (Object alternative : LoaderRules.list(uses)) {
            List<Pattern> all = LoaderRules.strings(alternative).stream().map(Pattern::compile).toList();
            for (String text : texts) if (!all.isEmpty() && all.stream().allMatch(p -> p.matcher(text).find())) return true;
        }
        return false;
    }
}

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Fabric's API kept working on NeoForge: the mirrored classes copied into the converted mod and its code pointed at them. */
final class FabricBridge {

    final Convert convert;

    FabricBridge(Convert convert) {
        this.convert = convert;
    }

    // ---- the Fabric bridge on NeoForge ------------------------------------------------------------------------

    /** The package the Fabric bridge goes in (beside the main entrypoint), once the code uses it; null otherwise. */
    String fabricBridgePackage;

    /** The bridge from Fabric to NeoForge for this game version (knowledge/loaders.json bridges[]), or null. */
    Map<String, Object> fabricBridgeFor(String minecraft) {
        for (Object item : LoaderRules.list(convert.rules.get("bridges"))) {
            var b = Json.object(item);
            if (!"fabric".equals(b.get("from")) || !"neoforge".equals(b.get("to"))) continue;
            if (Versions.compare(minecraft, Json.text(b, "minecraft", "99")) >= 0 || ConvertBridges.bridgeLayer(b, minecraft) != null) return b;
        }
        return null;
    }

    /**
     * The Fabric API classes a bridge mirrors, from its template files: a file at fabric/api/event/Event.java mirrors
     * net.fabricmc.fabric.api.event.Event. Files at the bridge folder's root, and in mixin/, are the bridge's own.
     */
    static Set<String> mirroredClasses(Map<String, Path> templates) {
        Set<String> out = new TreeSet<>();
        for (String rel : templates.keySet()) {
            if (!rel.contains("/") || rel.startsWith("mixin/")) continue;
            String name = rel.substring(0, rel.length() - ".java".length()).replace('/', '.');
            out.add(FABRIC_ROOTS.contains(name.substring(0, name.indexOf('.'))) ? "net.fabricmc." + name : name);
        }
        return out;
    }

    /** Fabric's own packages under net.fabricmc: a bridge file under one of these mirrors net.fabricmc.FILE. */
    static final Set<String> FABRIC_ROOTS = Set.of("fabric", "loader", "api");

    /**
     * The bridge's name for a class or package it mirrors, or null: net.fabricmc.X becomes PACKAGE.X, and a Fabric-only
     * library's API the bridge mirrors (Team Reborn Energy, Forge Config API Port's Fabric API) keeps its name under PACKAGE.
     */
    static String mirrored(String name, Set<String> classes, String pkg) {
        // An import of a nested class names its outer class's file: the longest mirrored prefix.
        for (String prefix = name; prefix.contains("."); prefix = prefix.substring(0, prefix.lastIndexOf('.'))) {
            if (classes.contains(prefix)) return pkg + "." + (name.startsWith("net.fabricmc.") ? name.substring("net.fabricmc.".length()) : name);
        }
        return null;
    }

    /**
     * Fabric API classes no rule rewrote, mirrored on NeoForge (knowledge/loaders.json bridges, from fabric to neoforge):
     * the bridge's classes keep Fabric's names and members, written on NeoForge's API in knowledge/bridges/fabric-on-neoforge
     * and copied into the mod under its main entrypoint's package (net.fabricmc.X becomes PACKAGE.fabricbridge.X, so two
     * converted mods never share a package). Imports and qualified names of a mirrored class point at it; the @Mod
     * constructors call FabricBridge.init(modBus), which listens for the NeoForge events the Fabric callbacks are posted from.
     */
    void applyFabricBridge(Map<String, Object> meta, String minecraft) throws Exception {
        Map<String, Object> b = fabricBridgeFor(minecraft);
        if (b == null) return;
        Map<String, Object> layer = Versions.compare(minecraft, Json.text(b, "minecraft", "99")) >= 0 ? null : ConvertBridges.bridgeLayer(b, minecraft);
        Map<String, Path> templates = ConvertBridges.bridgeTemplates(
            convert.build.harness.resolve(Json.text(b, "folder", "")),
            layer == null ? null : convert.build.harness.resolve(Json.text(layer, "folder", ""))
        );
        // A version layer leaves out what its game's Fabric API or NeoForge lacks, and renames words (Identifier) in the rest.
        List<String> layerDrops = layer == null ? List.of() : LoaderRules.strings(layer.get("drops"));
        templates.keySet().removeIf(rel -> ConvertBridges.dropped(layerDrops, rel));
        Map<String, Object> layerWords = layer != null && layer.get("rewrites") instanceof Map<?, ?> words ? Json.object(words) : Map.of();
        Set<String> classes = mirroredClasses(templates);
        String entry = null;
        if (meta.get("entrypoints") instanceof Map<?, ?> eps) for (String side : List.of("main", "client", "server")) {
            for (Object item : Json.array(Json.object(eps).getOrDefault(side, List.of()))) {
                String name = item instanceof String s ? s : item instanceof Map<?, ?> ? Json.text(Json.object(item), "value", "") : "";
                if (entry == null && name.contains(".") && !name.contains("::")) entry = name;
            }
        }
        if (entry == null) return;
        String pkg = entry.substring(0, entry.lastIndexOf('.')) + ".fabricbridge";
        Pattern imports = Pattern.compile("(?m)^import\\s+(static\\s+)?([a-z]\\w*\\.[\\w.]+?)(\\.\\*)?\\s*;");
        // Qualified names start with the first two parts of a mirrored class (net.fabricmc, team.reborn ...).
        Set<String> heads = new TreeSet<>();
        for (String c : classes) heads.add(Pattern.quote(c.substring(0, c.indexOf('.', c.indexOf('.') + 1))));
        Pattern qualified = Pattern.compile("\\b(?:" + String.join("|", heads) + ")\\.[\\w.]*\\w");
        boolean used = false;
        for (String file : convert.projectFiles.javaFiles()) {
            String text = convert.projectFiles.read(file);
            StringBuilder out = new StringBuilder();
            Matcher m = imports.matcher(text);
            while (m.find()) {
                String name = m.group(2);
                String to = mirrored(name, classes, pkg);
                // A wildcard import of a package the bridge mirrors classes of.
                if (to == null && m.group(3) != null && classes.stream().anyMatch(c -> c.startsWith(name + ".") && c.indexOf('.', name.length() + 1) < 0)) to =
                    pkg + "." + (name.startsWith("net.fabricmc.") ? name.substring("net.fabricmc.".length()) : name);
                m.appendReplacement(out, Matcher.quoteReplacement(
                    to == null ? m.group() : "import " + (m.group(1) == null ? "" : m.group(1)) + to + (m.group(3) == null ? "" : m.group(3)) + ";"
                ));
            }
            m.appendTail(out);
            String next = out.toString();
            boolean[] code = LoaderRules.code(next);
            StringBuilder fq = new StringBuilder();
            Matcher q = qualified.matcher(next);
            while (q.find()) {
                String to = code[q.start()] && !next.startsWith("import", next.lastIndexOf('\n', q.start()) + 1) ? mirrored(q.group(), classes, pkg) : null;
                q.appendReplacement(fq, Matcher.quoteReplacement(to == null ? q.group() : to));
            }
            q.appendTail(fq);
            next = fq.toString();
            if (!next.equals(text)) {
                convert.projectFiles.write(file, next);
                used = true;
                convert.applied.computeIfAbsent("fabric-bridge", k -> new ArrayList<>()).add(file);
            }
        }
        // Code that only calls what Fabric injects into game classes needs the bridge too (bridges[].mixins[].uses).
        if (!used) {
            List<String> texts = new ArrayList<>();
            for (String file : convert.projectFiles.javaFiles()) texts.add(convert.projectFiles.read(file));
            Object mixins = layer != null && layer.get("mixins") != null ? layer.get("mixins") : b.get("mixins");
            for (Object mixin : LoaderRules.list(mixins)) used |= ConvertBridges.uses(Json.object(mixin).get("uses"), texts);
        }
        if (!used) return;
        fabricBridgePackage = pkg;
        if (b.get("replaces") instanceof Map<?, ?> replaces) for (var e : Json.object(replaces).entrySet()) fabricBridgeReplaces.put(e.getKey(), String.valueOf(e.getValue()));
        fabricBridgeReplacedMods.addAll(LoaderRules.strings(b.get("replacesMods")));
        String folder = "src/main/java/" + pkg.replace('.', '/');
        convert.bridges.bridgeUsers = convert.bridges.sourcesOutside(folder);
        for (var template : templates.entrySet()) convert.projectFiles.write(
            folder + "/" + template.getKey(),
            convert.bridges.fillBridge(ConvertBridges.layerRewrites(Files.readString(template.getValue(), StandardCharsets.UTF_8), layerWords), pkg)
        );
        convert.bridges.bridgeMixinConfig = convert.bridges.bridgeMixins(b, layer, pkg, convert.projectFiles.javaFiles().stream().filter(f -> !f.startsWith(folder + "/")).toList());
        convert.notes.add(
            "Fabric bridge: " +
            pkg +
            " holds the Fabric API classes the code uses that no rule rewrites, written on NeoForge's API with Fabric's names and members; the @Mod constructors start it."
        );
    }

    /** Fabric-only libraries the Fabric bridge mirrors, by group:artifact, when the code uses the bridge (bridges[].replaces). */
    final Map<String, String> fabricBridgeReplaces = new LinkedHashMap<>();

    /** The mod ids of those libraries (bridges[].replacesMods), which the converted mod no longer depends on. */
    final Set<String> fabricBridgeReplacedMods = new HashSet<>();

    /** The Fabric bridge's marker: its FabricBridge class says this in its first comment. */
    static final String FABRIC_BRIDGE_MARK = "The Fabric bridge on NeoForge";

    /**
     * A mod convert moved to NeoForge through the Fabric bridge, going back to Fabric: imports and names of the bridge's
     * classes are Fabric's again, the @Mod constructors' FabricBridge.init goes (entryToFabric), and the bridge's files
     * and mixin config are removed.
     */
    void unbridgeFabric() throws Exception {
        String pkg = null;
        for (String file : convert.projectFiles.javaFiles()) if (file.endsWith("/FabricBridge.java") && convert.projectFiles.read(file).contains(FABRIC_BRIDGE_MARK)) {
            Matcher m = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;").matcher(convert.projectFiles.read(file));
            if (m.find()) pkg = m.group(1);
        }
        if (pkg == null) return;
        String folder = "src/main/java/" + pkg.replace('.', '/') + "/";
        Pattern names = Pattern.compile("\\b" + Pattern.quote(pkg) + "\\.([a-z]\\w*)((?:\\.\\w+)*(?:\\.\\*)?)");
        for (String file : convert.projectFiles.javaFiles()) {
            if (file.startsWith(folder)) continue;
            String text = convert.projectFiles.read(file);
            String out = text.replaceAll("(?m)^[ \\t]*FabricBridge\\.init\\(modBus\\);[ \\t]*\\n", "");
            out = LoaderRules.dropImport(out, pkg + ".FabricBridge");
            out = names.matcher(out).replaceAll(r -> Matcher.quoteReplacement(
                (FABRIC_ROOTS.contains(r.group(1)) ? "net.fabricmc." : "") + r.group(1) + r.group(2)
            ));
            if (!out.equals(text)) {
                convert.projectFiles.write(file, out);
                convert.applied.computeIfAbsent("fabric-bridge-back", k -> new ArrayList<>()).add(file);
            }
        }
        for (String file : convert.projectFiles.javaFiles()) if (file.startsWith(folder)) convert.projectFiles.delete(file);
        Path resources = convert.project.root.resolve("src/main/resources");
        if (Files.isDirectory(resources)) try (var list = Files.list(resources)) {
            for (Path config : list.filter(p -> p.getFileName().toString().endsWith(".fabricbridge.mixins.json")).toList()) convert.projectFiles.delete(convert.projectFiles.relative(config));
        }
        if (Files.isRegularFile(convert.project.root.resolve(ConvertBridges.INJECTED_INTERFACES))) convert.projectFiles.delete(ConvertBridges.INJECTED_INTERFACES);
        convert.notes.add("Fabric bridge: " + pkg + " is removed; the code names Fabric API's own classes again.");
    }

    /**
     * The NeoForge classes the bridge mirrors for this game: a class is mirrored only when the bridge or its version layer
     * has its file (a class only the 1.21.1 layer writes is NeoForge's own copy on 26.1, or nothing).
     */
    Map<String, Object> mirroredClasses() throws IOException {
        Path templates = convert.build.harness.resolve(Json.text(convert.bridges.bridge, "folder", ""));
        Set<String> present = ConvertBridges.bridgeTemplates(templates, convert.bridges.bridgeLayer == null ? null : convert.build.harness.resolve(Json.text(convert.bridges.bridgeLayer, "folder", ""))).keySet();
        Map<String, Object> classes = new LinkedHashMap<>();
        for (var c : Json.object(convert.bridges.bridge.get("classes")).entrySet()) if (present.contains(String.valueOf(c.getValue()).replace('.', '/') + ".java")) classes.put(c.getKey(), c.getValue());
        return classes;
    }
}

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** NeoForge's API kept working on Fabric: the bridge classes copied into the converted mod and its code pointed at them. */
final class NeoForgeBridge {

    final Convert convert;

    NeoForgeBridge(Convert convert) {
        this.convert = convert;
    }

    /**
     * NeoForge adds withTabsBefore and withTabsAfter to the game's creative tab builder to order tabs; Fabric's builder has
     * neither, so the calls go (the tab keeps its items and title, and takes the next free place) and a note says so.
     */
    /**
     * A wildcard import of a NeoForge package (import net.neoforged.neoforge.client.event.*;) becomes an import of each
     * mirrored class of that package the file names, as the package itself does not exist on Fabric; a class the bridge
     * does not mirror is then named in the compiler's errors, as with an explicit import.
     */
    static String wildcardBridgeImports(String text, Map<String, Object> classes, List<String> drops, String bridgePackage) {
        Matcher wild = Pattern.compile("(?m)^import\\s+(net\\.neoforged\\.[\\w.]+)\\.\\*\\s*;[ \\t]*\\n").matcher(text);
        StringBuilder out = new StringBuilder();
        while (wild.find()) {
            StringBuilder imports = new StringBuilder();
            // import ...ModConfigSpec.*; names a mirrored class's nested types: the bridge class's own.
            Object mirrored = classes.get(wild.group(1));
            if (mirrored != null && !ConvertBridges.dropped(drops, String.valueOf(mirrored).replace('.', '/') + ".java")) {
                wild.appendReplacement(out, Matcher.quoteReplacement("import " + bridgePackage + "." + mirrored + ".*;\n"));
                continue;
            }
            for (var c : classes.entrySet()) {
                String name = c.getKey();
                int dot = name.lastIndexOf('.');
                if (!name.substring(0, dot).equals(wild.group(1))) continue;
                if (ConvertBridges.dropped(drops, String.valueOf(c.getValue()).replace('.', '/') + ".java")) continue;
                if (!Pattern.compile("(?<![\\w.])" + Pattern.quote(name.substring(dot + 1)) + "\\b").matcher(text).find()) continue;
                imports.append("import ").append(bridgePackage).append('.').append(c.getValue()).append(";\n");
            }
            wild.appendReplacement(out, Matcher.quoteReplacement(imports.toString()));
        }
        wild.appendTail(out);
        return out.toString();
    }

    // ---- the NeoForge bridge on Fabric ---------------------------------------------------------------------

    /** A class NeoForge finds by its @EventBusSubscriber annotation: its name in source and whether only the client has it. */
    record Subscriber(String type, boolean client) {}

    /** The package the bridge goes in (beside the mod's first @Mod class), or null when there is no bridge for this game. */
    String bridgePackage;
    boolean bridgeUsed, clientEntry;
    final List<Subscriber> subscribers = new ArrayList<>();

    /**
     * Where the bridge goes: it holds registration and the event bus, which both sides use, so beside the common @Mod
     * class (one with no dist), else in the package every @Mod class shares; null when there is no @Mod class.
     */
    static String bridgePackage(List<String> sources) {
        List<String> common = new ArrayList<>(), all = new ArrayList<>();
        Pattern pkg = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;");
        for (String text : sources) {
            Matcher annotation = Pattern.compile("@(?:net\\.neoforged\\.fml\\.common\\.)?Mod\\(([^)]*)\\)").matcher(text);
            Matcher p = pkg.matcher(text);
            if (!annotation.find() || !p.find()) continue;
            all.add(p.group(1));
            if (!annotation.group(1).contains("dist")) common.add(p.group(1));
        }
        if (!common.isEmpty()) return common.stream().min(Comparator.comparingInt(String::length)).orElseThrow() + ".loaderbridge";
        if (all.isEmpty()) return null;
        String shared = all.get(0);
        for (String other : all) while (!(other.equals(shared) || other.startsWith(shared + ".")) && shared.contains(".")) shared = shared.substring(0, shared.lastIndexOf('.'));
        // Only client @Mod classes: the bridge still serves both sides, so not in their client package.
        if (shared.endsWith(".client")) shared = shared.substring(0, shared.length() - ".client".length());
        return shared + ".loaderbridge";
    }

    String bridgeFolder() {
        return "src/main/java/" + bridgePackage.replace('.', '/');
    }

    /**
     * The NeoForge bridge (knowledge/loaders.json "bridges"): NeoForge's registration, event bus, mod container, config
     * and the commonest lifecycle events written on Fabric's API, for the Minecraft versions it is written for. A mod
     * written for NeoForge first keeps its own code; its entrypoints get what NeoForge passed their constructors from it.
     */
    void prepareBridge(String minecraft) throws Exception {
        for (Object item : LoaderRules.list(convert.rules.get("bridges"))) {
            var b = Json.object(item);
            if (!"neoforge".equals(b.get("from")) || !"fabric".equals(b.get("to"))) continue;
            Map<String, Object> layer = ConvertBridges.bridgeLayer(b, minecraft);
            if (Versions.compare(minecraft, Json.text(b, "minecraft", "99")) < 0 && layer == null) continue;
            convert.bridges.bridge = b;
            convert.bridges.bridgeLayer = layer;
        }
        if (convert.bridges.bridge == null) return;
        bridgePackage = bridgePackage(convert.projectFiles.javaFiles().stream().map(f -> {
            try {
                return convert.projectFiles.read(f);
            } catch (IOException e) {
                return "";
            }
        }).toList());
        // NeoForge's own classes the code names go in before the source rules run, which rewrite them as the mod's code.
        if (bridgePackage != null) copied = copyNeoForgeSources(convert.fabricBridge.mirroredClasses(), LoaderRules.strings(convert.bridges.bridgeLayer == null ? null : convert.bridges.bridgeLayer.get("drops")));
        for (String file : convert.projectFiles.javaFiles()) {
            // NeoForge's own subscribers (in its copied classes) are NeoForge wiring itself up, which on Fabric is the
            // bridge's job; many are package-private, so the mod's entrypoint could not name them anyway.
            if (copiedFiles.contains(file)) continue;
            String text = convert.projectFiles.read(file);
            Matcher mod = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;").matcher(text);
            Matcher s = Pattern.compile(
                "@EventBusSubscriber\\b(\\s*\\([^)]*\\))?\\s*(?:@[\\w.]+(?:\\([^)]*\\))?\\s*)*(?:(?:public|protected|private|static|final|abstract)\\s+)*(?:class|interface|enum|record)\\s+(\\w+)"
            ).matcher(text);
            String pkg = mod.reset().find() ? mod.group(1) + "." : "";
            String top = file.substring(file.lastIndexOf('/') + 1).replace(".java", "");
            // A subscriber in a comment (a file kept commented out) is not one.
            boolean[] code = LoaderRules.code(text);
            while (s.find()) {
                if (!code[s.start()]) continue;
                String args = s.group(1) == null ? "" : s.group(1);
                boolean client = args.contains("Dist.CLIENT") && !args.contains("DEDICATED_SERVER");
                String type = pkg + (s.group(2).equals(top) ? top : top + "." + s.group(2));
                subscribers.add(new Subscriber(type, client));
            }
        }
    }

    /**
     * A NeoForge @Mod class as a Fabric entrypoint through the bridge: its constructor's body runs in the entrypoint
     * method, with the constructor's IEventBus and ModContainer parameters as locals the bridge supplies; the
     * entrypoint then registers the @EventBusSubscriber classes of its side and ends as NeoForge's start-up does.
     */
    String bridgeEntry(String file, String text, Matcher annotation, String simple, String api, String method) {
        boolean client = api.equals("ClientModInitializer");
        String id = annotation.group(1).strip();
        convert.unit = LoaderRules.unit(text);
        boolean[] code = LoaderRules.code(text);
        Matcher ctor = Pattern.compile(
            "(?m)^([ \\t]*)(?:(?:public|protected|private)\\s+)?" + simple + "\\s*\\(([^)]*)\\)\\s*\\{"
        ).matcher(text);
        int start = -1, open = -1, end = -1;
        String indent = convert.unit, params = "";
        int found = 0;
        while (ctor.find()) if (code[ctor.start(2) - 1]) {
            found++;
            start = ctor.start();
            open = ctor.end() - 1;
            indent = ctor.group(1);
            params = ctor.group(2).strip();
        }
        if (found > 1) {
            convert.projectFiles.leave(file, LoaderRules.line(text, annotation.start()), "@Mod class " + simple, "it has more than one constructor; Fabric makes an entrypoint with none");
            return null;
        }
        StringBuilder locals = new StringBuilder();
        List<String> sides = new ArrayList<>();
        String in = indent + convert.unit;
        if (!params.isEmpty()) for (String param : params.split(",")) {
            String[] p = param.strip().replaceAll("^final\\s+", "").split("\\s+");
            if (p.length != 2) return leaveEntry(file, text, annotation, simple, "parameter " + param.strip());
            switch (p[0]) {
                case "IEventBus" -> locals.append(in).append("IEventBus ").append(p[1]).append(" = Bridge.bus();\n");
                case "ModContainer" -> locals.append(in).append("ModContainer ").append(p[1]).append(" = Bridge.container(").append(id).append(");\n");
                // A client @Mod class runs on the client only; a common one on either side, which FMLLoader tells.
                case "Dist" -> {
                    locals.append(in).append("Dist ").append(p[1]).append(" = ").append(client ? "Dist.CLIENT" : "FMLLoader.getDist()").append(";\n");
                    sides.add(p[1]);
                }
                default -> {
                    return leaveEntry(file, text, annotation, simple, "parameter " + param.strip());
                }
            }
        }
        String body = "";
        String before = text, after = "";
        if (start >= 0) {
            end = LoaderRules.close(text, code, open);
            body = text.substring(open + 1, end - 1).replaceAll("^\\s*\\n", "").replaceAll("\\s+$", "");
            before = text.substring(0, start);
            after = text.substring(end);
        } else {
            // No constructor: the entrypoint method goes at the end of the class.
            int last = text.lastIndexOf('}');
            before = text.substring(0, last).replaceAll("\\s+$", "") + "\n";
            after = "\n" + text.substring(last);
            indent = convert.unit;
            in = indent + convert.unit;
        }
        StringBuilder entry = new StringBuilder();
        entry.append(indent).append("/** NeoForge's constructor of this @Mod class, run as Fabric's entrypoint (mmt convert, NeoForge bridge). */\n");
        entry.append(indent).append("public void ").append(method).append("() {\n").append(locals);
        // Dist.isClient() and isDedicatedServer() on the side parameter: Fabric's EnvType has neither, so the side is compared.
        for (String side : sides) body = body
            .replaceAll("\\b" + side + "\\.isClient\\(\\)", "(" + side + " == Dist.CLIENT)")
            .replaceAll("\\b" + side + "\\.isDedicatedServer\\(\\)", "(" + side + " == Dist.DEDICATED_SERVER)");
        if (!body.isEmpty()) entry.append(body.replaceAll("(?m)^" + Pattern.quote(indent + convert.unit), in)).append('\n');
        for (Subscriber s : subscribers) if (s.client() == client) entry.append(in).append("Bridge.subscribe(").append(s.type()).append(".class);\n");
        entry.append(in).append(client ? "ClientBridge.client(" : "Bridge.common(").append(id).append(");\n");
        entry.append(indent).append("}");
        String out = before + entry + after;
        int lineStart = out.lastIndexOf('\n', out.indexOf(annotation.group())) + 1;
        int lineEnd = out.indexOf('\n', out.indexOf(annotation.group())) + 1;
        out = out.substring(0, lineStart) + out.substring(lineEnd);
        out = Convert.implementFabric(out, simple, api, method);
        out = LoaderRules.dropImport(out, "net.neoforged.fml.common.Mod");
        if (!client && !sides.isEmpty()) out = LoaderRules.addImport(out, "net.neoforged.fml.loading.FMLLoader");
        out = LoaderRules.addImport(out, bridgePackage + ".Bridge");
        if (client) {
            out = LoaderRules.addImport(out, bridgePackage + ".ClientBridge");
            clientEntry = true;
        }
        bridgeUsed = true;
        convert.applied.computeIfAbsent("neoforge-bridge-entrypoint", k -> new ArrayList<>()).add(file);
        return out;
    }

    String leaveEntry(String file, String text, Matcher annotation, String simple, String what) {
        convert.projectFiles.leave(file, LoaderRules.line(text, annotation.start()), "@Mod class " + simple, "its constructor takes " + what + ", which the NeoForge bridge does not supply");
        return null;
    }

    /**
     * The bridge's own files, and every import of a NeoForge class it mirrors pointed at it. Listeners added by method
     * reference or typed lambda get their event class written in (NeoForge reads it from the generic signature).
     */
    void applyBridge() throws Exception {
        Path templates = convert.build.harness.resolve(Json.text(convert.bridges.bridge, "folder", ""));
        Set<String> attachmentFields = new TreeSet<>();
        for (String file : convert.projectFiles.javaFiles()) for (Matcher f = ATTACHMENT_FIELD.matcher(convert.projectFiles.read(file)); f.find(); ) attachmentFields.add(f.group(1));
        List<String> drops = LoaderRules.strings(convert.bridges.bridgeLayer == null ? null : convert.bridges.bridgeLayer.get("drops"));
        Map<String, Object> classes = convert.fabricBridge.mirroredClasses();
        // Wildcard imports of a package name its copied classes too.
        Map<String, Object> named = new LinkedHashMap<>(classes);
        for (String c : copied) named.put(c, NeoForgeSources.FOLDER + "." + c.substring(NeoForgeSources.PREFIX.length()));
        // The mod's tests use the same NeoForge classes as its code, so their imports and qualified names move too.
        List<String> sources = new ArrayList<>(convert.projectFiles.javaFiles());
        sources.addAll(convert.projectFiles.testJavaFiles());
        for (String file : sources) {
            String text = convert.projectFiles.read(file);
            String out = wildcardBridgeImports(text, named, drops, bridgePackage);
            // Plain, static and nested-class imports of a mirrored class (import static ...FluidType.BUCKET_VOLUME;).
            for (var c : classes.entrySet()) if (!ConvertBridges.dropped(drops, String.valueOf(c.getValue()).replace('.', '/') + ".java")) out = out.replaceAll(
                "(?m)^import(\\s+static)?\\s+" + Pattern.quote(c.getKey()) + "((?:\\.\\w+)*(?:\\.\\*)?)\\s*;",
                "import$1 " + Matcher.quoteReplacement(bridgePackage + "." + c.getValue()) + "$2;"
            );
            for (var c : classes.entrySet()) out = out.replaceAll(
                "(?<![\\w.])" + Pattern.quote(c.getKey()) + "(?![\\w$])",
                Matcher.quoteReplacement(bridgePackage + "." + c.getValue())
            );
            if (!copied.isEmpty()) out = NeoForgeSources.point(out, copied, bridgePackage);
            for (var c : Json.object(convert.bridges.bridge.getOrDefault("constructors", Map.of())).entrySet()) if (!ConvertBridges.dropped(drops, c.getValue() + ".java")) {
                out = constructorsToFactories(out, c.getKey(), String.valueOf(c.getValue()), bridgePackage);
            }
            // Static methods NeoForge's patches add to game classes: called on the bridge's class instead.
            for (var s : Json.object(convert.bridges.bridge.getOrDefault("statics", Map.of())).entrySet()) {
                Map<String, Object> to = Json.object(s.getValue());
                if (ConvertBridges.dropped(drops, Json.text(to, "to", "") + ".java")) continue;
                String moved = staticsToBridge(out, s.getKey(), Json.text(to, "to", ""), bridgePackage);
                if (!moved.equals(out)) for (String line : LoaderRules.strings(to.get("accessTransformers"))) if (!convert.access.accessTransformers.contains(line)) convert.access.accessTransformers.add(line);
                out = moved;
            }
            out = eventClassesInListeners(file, out);
            out = sideQueries(out);
            String held = attachmentCalls(out, attachmentFields);
            if (!held.equals(out)) out = LoaderRules.addImport(held, bridgePackage + ".Attachments");
            if (!out.equals(text)) {
                convert.projectFiles.write(file, out);
                convert.applied.computeIfAbsent("neoforge-bridge", k -> new ArrayList<>()).add(file);
            }
        }
        Map<String, Path> files = ConvertBridges.bridgeTemplates(templates, convert.bridges.bridgeLayer == null ? null : convert.build.harness.resolve(Json.text(convert.bridges.bridgeLayer, "folder", "")));
        files.keySet().removeIf(rel -> ConvertBridges.dropped(drops, rel));
        if (convert.bridges.bridgeLayer != null) {
            convert.notes.add(
                "NeoForge bridge: the layer for Minecraft " +
                Json.text(convert.bridges.bridgeLayer, "minecraft", "") +
                " (" +
                Json.text(convert.bridges.bridgeLayer, "folder", "") +
                ") replaces the files whose game or Fabric API names differ there."
            );
        }
        // NeoForge's own copied classes use the bridge as the mod's code does.
        convert.bridges.bridgeUsers = convert.bridges.sourcesOutside(bridgeFolder());
        for (String file : copiedFiles) convert.bridges.bridgeUsers += convert.projectFiles.read(file) + "\n";
        Map<String, Object> rewrites = convert.bridges.bridgeLayer == null ? Map.of() : Json.object(convert.bridges.bridgeLayer.getOrDefault("rewrites", Map.of()));
        // The classes standing for NeoForge's constructors and static methods on game classes are written only for a mod
        // that calls them: they open game members (an access widener line, which needs the game's mappings) that other
        // mods never touch.
        Set<String> onlyWhenCalled = new HashSet<>();
        for (Object f : Json.object(convert.bridges.bridge.getOrDefault("constructors", Map.of())).values()) onlyWhenCalled.add(f + ".java");
        for (Object s : Json.object(convert.bridges.bridge.getOrDefault("statics", Map.of())).values()) onlyWhenCalled.add(Json.text(Json.object(s), "to", "") + ".java");
        for (String f : onlyWhenCalled) {
            String name = f.replaceAll("\\.java$", "");
            if (!Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(convert.bridges.bridgeUsers).find()) files.remove(f);
        }
        dropUnusedGenerated(files);
        for (var template : files.entrySet()) {
            // For an older game, the layer's whole-word renames (Identifier is ResourceLocation there).
            String text = ConvertBridges.layerRewrites(Files.readString(template.getValue(), StandardCharsets.UTF_8), rewrites);
            convert.projectFiles.write(bridgeFolder() + "/" + template.getKey(), convert.bridges.fillBridge(text, bridgePackage));
        }
        convert.bridges.bridgeMixinConfig = convert.bridges.bridgeMixins(
            convert.bridges.bridge,
            convert.bridges.bridgeLayer,
            bridgePackage,
            convert.projectFiles.javaFiles().stream().filter(f -> !f.startsWith(bridgeFolder() + "/") || copiedFiles.contains(f)).toList()
        );
        convert.notes.add(
            "NeoForge bridge: " +
            bridgePackage +
            " holds NeoForge's registration, event bus, mod container, config and lifecycle events written on Fabric's API, for the code that uses them."
        );
    }

    /**
     * The generated game-class methods (bench/bridge_generate.py: a mixin and the interface it adds, per game class) are
     * written only for the game classes whose methods the code calls (the mixin's "uses"), with the generated interfaces
     * those extend: a mod gets tens of them, not all 500.
     */
    void dropUnusedGenerated(Map<String, Path> files) throws IOException {
        Object list = convert.bridges.bridgeLayer != null && convert.bridges.bridgeLayer.get("mixins") != null ? convert.bridges.bridgeLayer.get("mixins") : convert.bridges.bridge.get("mixins");
        List<String> texts = new ArrayList<>();
        for (String file : convert.projectFiles.javaFiles()) if (!file.startsWith(bridgeFolder() + "/") || copiedFiles.contains(file)) texts.add(convert.projectFiles.read(file));
        Set<String> generated = new HashSet<>();
        Deque<String> keep = new ArrayDeque<>();
        for (Object item : LoaderRules.list(list)) {
            var mixin = Json.object(item);
            if (!Boolean.TRUE.equals(mixin.get("generated"))) continue;
            String mixinFile = "mixin/" + Json.text(mixin, "mixin", "").replace('.', '/') + ".java";
            generated.add(mixinFile);
            List<String> interfaces = new ArrayList<>();
            if (mixin.get("injects") instanceof Map<?, ?> injects) for (Object i : injects.values()) interfaces.add(String.valueOf(i).replace('.', '/') + ".java");
            generated.addAll(interfaces);
            if (ConvertBridges.uses(mixin.get("uses"), texts)) {
                keep.add(mixinFile);
                keep.addAll(interfaces);
            }
        }
        Set<String> kept = new HashSet<>();
        while (!keep.isEmpty()) {
            String file = keep.poll();
            if (!kept.add(file) || !files.containsKey(file)) continue;
            String folder = file.substring(0, file.lastIndexOf('/') + 1);
            for (Matcher m = Pattern.compile("\\b(\\w+Generated)\\b").matcher(Files.readString(files.get(file))); m.find(); ) keep.add(folder + m.group(1) + ".java");
        }
        files.keySet().removeIf(f -> generated.contains(f) && !kept.contains(f));
    }

    /** NeoForge's own source files convert copied into the bridge's package (NeoForgeSources), and their classes. */
    final List<String> copiedFiles = new ArrayList<>();
    Set<String> copied = Set.of();

    /** The members of NeoForge's classes that do not compile on Fabric, which come out of their copies, by class. */
    final Map<String, Set<String>> copiedMembersGone = new HashMap<>();

    /**
     * NeoForge's own classes that the mod names and the bridge does not mirror, from NeoForge's published sources jar
     * (bridges[].sources or its version layer's: a "published" key, read with the sources classifier), copied into the
     * bridge's package when the bridge's folder lists them as compiling on Fabric (neoforge-sources.txt). The
     * environment variable MMT_NEOFORGE_SOURCES=all copies every class the jar has, for bench/bridge_import.py to measure. Returns
     * the copied classes by NeoForge name.
     */
    Set<String> copyNeoForgeSources(Map<String, Object> classes, List<String> drops) throws Exception {
        Map<String, Object> from = convert.bridges.bridgeLayer != null && convert.bridges.bridgeLayer.get("sources") != null ? convert.bridges.bridgeLayer : convert.bridges.bridge;
        String key = Json.text(from, "sources", "");
        if (key.isEmpty()) return Set.of();
        boolean all = "all".equals(System.getenv("MMT_NEOFORGE_SOURCES"));
        Path list = convert.build.harness.resolve(Json.text(from, "folder", "")).resolve("neoforge-sources.txt");
        if (!all && !Files.isRegularFile(list)) return Set.of();
        Set<String> mirrored = new TreeSet<>();
        for (var c : classes.entrySet()) if (!ConvertBridges.dropped(drops, String.valueOf(c.getValue()).replace('.', '/') + ".java")) mirrored.add(c.getKey());
        Set<String> allowed = new TreeSet<>();
        // A line is a class, or a class and one of its members that does not compile on Fabric (NeoForgeSources.Member).
        if (!all) for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] parts = line.strip().split(" ", 2);
            if (parts.length == 1) allowed.add(parts[0]);
            else copiedMembersGone.computeIfAbsent(parts[0], k -> new TreeSet<>()).add(parts[1].replace("${package}", bridgePackage));
        }
        allowed.removeAll(mirrored);
        List<String> texts = new ArrayList<>();
        for (String file : convert.projectFiles.javaFiles()) texts.add(convert.projectFiles.read(file));
        for (String file : convert.projectFiles.testJavaFiles()) texts.add(convert.projectFiles.read(file));
        // Nothing the mod names is listed: no download.
        if (!all && NeoForgeSources.closure(texts, Map.of(), allowed).isEmpty()) return Set.of();
        Map<String, Object> entry = Json.object(Json.object(convert.rules.get("published")).get(key));
        String[] gav = Json.text(entry, "coordinate", "").split(":");
        String url = Json.text(entry, "maven", "") + "/" + gav[0].replace('.', '/') + "/" + gav[1] + "/" + gav[2] + "/" + gav[1] + "-" + gav[2] + "-sources.jar";
        Map<String, String> sources = NeoForgeSources.read(Target.download(convert.build, url));
        sources.keySet().removeAll(mirrored);
        // NeoForge's own @Mod classes are NeoForge itself starting, never the converted mod's entrypoints: what else they
        // hold (NeoForgeMod.MOD_ID) stays.
        sources.replaceAll((name, text) -> text.replaceAll("(?m)^@Mod\\(.*\\)[ \\t]*\\r?\\n", ""));
        // A copied class that needs a NeoForge patch's member (RegistryOps(RegistryOps)) is written on the game's own
        // members instead (neoforge-patches.json: text replaced, and the access transformer lines that needs).
        Path patchFile = list.resolveSibling("neoforge-patches.json");
        if (!Files.isRegularFile(patchFile)) patchFile = convert.build.harness.resolve(Json.text(convert.bridges.bridge, "folder", "")).resolve("neoforge-patches.json");
        Map<String, Object> patches = Files.isRegularFile(patchFile) ? Json.read(patchFile) : Map.of();
        for (var p : patches.entrySet()) {
            String text = sources.get(p.getKey());
            if (text == null || p.getKey().startsWith("about")) continue;
            for (Object pair : LoaderRules.list(Json.object(p.getValue()).get("replace"))) {
                List<String> fromTo = LoaderRules.strings(pair);
                text = text.replace(fromTo.get(0), fromTo.get(1));
            }
            sources.put(p.getKey(), text);
        }
        if (all) allowed.addAll(sources.keySet());
        allowed.retainAll(sources.keySet());
        Set<String> copied = all ? allowed : NeoForgeSources.closure(texts, sources, allowed);
        for (String name : copied) if (patches.get(name) instanceof Map<?, ?> p) convert.access.accessTransformers.addAll(LoaderRules.strings(p.get("accessTransformers")));
        for (String name : copied) {
            String file = bridgeFolder() + "/" + NeoForgeSources.FOLDER + "/" + name.substring(NeoForgeSources.PREFIX.length()).replace('.', '/') + ".java";
            convert.projectFiles.write(file, NeoForgeSources.relocate(sources.get(name).replace("\r\n", "\n"), bridgePackage, mirrored));
            copiedFiles.add(file);
        }
        if (!copied.isEmpty()) {
            convert.notes.add(
                "NeoForge bridge: " + copied.size() + " of NeoForge's own classes (LGPL-2.1, from " + url.substring(url.lastIndexOf('/') + 1) +
                ") are copied into " + bridgePackage + "." + NeoForgeSources.FOLDER +
                "; events among them are posted on Fabric only where the bridge posts them."
            );
            convert.applied.computeIfAbsent("neoforge-sources", k -> new ArrayList<>()).addAll(copiedFiles);
        }
        return copied;
    }

    /**
     * NeoForge's Dist answers isClient() and isDedicatedServer(); on Fabric the side is EnvType, which has neither (the
     * physical-side rule makes Dist EnvType). The side the bridge's FMLEnvironment and FMLLoader give is compared instead,
     * or Fabric Loader's own, which the environment-type rule writes for FMLEnvironment.getDist().
     */
    static String sideQueries(String text) {
        Matcher m = Pattern.compile(
            "(?<![\\w.])((?:[a-z]\\w*\\.)*(?:FMLEnvironment\\.getDist\\(\\)|FMLEnvironment\\.dist|FMLLoader\\.getCurrent\\(\\)\\.getDist\\(\\)|FMLLoader\\.getDist\\(\\)|FabricLoader\\.getInstance\\(\\)\\.getEnvironmentType\\(\\)))\\.(isClient|isDedicatedServer)\\(\\)"
        ).matcher(text);
        if (!m.find()) return text;
        String out = m.reset().replaceAll(r -> Matcher.quoteReplacement("(" + r.group(1) + " == EnvType." + (r.group(2).equals("isClient") ? "CLIENT" : "SERVER") + ")"));
        return LoaderRules.addImport(out, "net.fabricmc.api.EnvType");
    }

    /** A field the mod declares as a NeoForge attachment type (Supplier<AttachmentType<X>> NAME = ..., DeferredHolder<...> NAME = ...). */
    static final Pattern ATTACHMENT_FIELD = Pattern.compile("\\bAttachmentType<[^;=]*>\\s+(\\w+)\\s*=");

    /**
     * NeoForge adds IAttachmentHolder's methods to the game's entities, block entities, levels and chunks; Fabric API adds
     * its AttachmentTarget to the same classes under other names. holder.getData(TYPE) becomes Attachments.getData(holder,
     * TYPE) when TYPE (or TYPE.get()) is a field the mod declares as an attachment type, so other getData methods are left.
     */
    static String attachmentCalls(String text, Set<String> fields) {
        if (fields.isEmpty()) return text;
        boolean[] code = LoaderRules.code(text);
        Matcher m = Pattern.compile("\\.\\s*(" + String.join("|", Convert.HOLDER_METHODS) + ")\\s*\\(").matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            int dot = m.start();
            if (!code[dot] || dot < last) continue;
            int open = m.end() - 1;
            int close = LoaderRules.close(text, code, open);
            if (close < 0) continue;
            String args = text.substring(open + 1, close - 1);
            List<String> parts = LoaderRules.split(args);
            if (parts.isEmpty()) continue;
            String type = parts.get(0).replaceAll("\\s*\\.\\s*get\\(\\)$", "");
            if (!fields.contains(type.replaceAll("^.*?(\\w+)$", "$1"))) continue;
            int start = LoaderRules.chainStart(text, code, dot);
            if (start >= dot || start < last) continue;
            out.append(text, last, start).append("Attachments.").append(m.group(1)).append('(').append(text, start, dot).append(", ").append(args).append(')');
            last = close;
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    /** addListener(this::method) and addListener((Type event) -> ...) with the event class written in, as the bridge's IEventBus takes it. */
    String eventClassesInListeners(String file, String text) throws IOException {
        boolean[] code = LoaderRules.code(text);
        Matcher m = Pattern.compile(
            "\\.addListener\\(\\s*((?:EventPriority\\.\\w+\\s*,\\s*)?(?:(?:true|false)\\s*,\\s*)?)(?:(this|[A-Z]\\w*(?:\\.[A-Z]\\w*)*)::(\\w+)|\\(\\s*(?:final\\s+)?([A-Z][\\w.]*)(?:<[^>]*>)?\\s+\\w+\\s*\\)\\s*->)"
        ).matcher(text);
        StringBuilder out = new StringBuilder();
        List<String> imports = new ArrayList<>();
        while (m.find()) {
            if (!code[m.start()]) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                continue;
            }
            String type = m.group(4);
            if (type == null) {
                String owner = m.group(2).equals("this") ? text : classSource(text, m.group(2));
                Matcher method = owner == null
                    ? null
                    : Pattern.compile(
                        "\\b" + Pattern.quote(m.group(3)) + "\\s*\\(\\s*(?:(?:final|@[\\w.]+(?:\\([^)]*\\))?)\\s+)*([A-Z][\\w.]*)(?:<[^>]*>)?\\s+\\w+\\s*\\)"
                    ).matcher(owner);
                if (method != null && method.find()) {
                    type = method.group(1);
                    // The type is named in the listener's own class: this file imports it as that class does.
                    if (owner != text) imports.add(importOf(owner, type));
                }
            }
            if (type == null) {
                convert.projectFiles.leave(file, LoaderRules.line(text, m.start()), "addListener" + m.group().substring(m.group().indexOf('(')), "the listener's event type could not be read from its method");
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                continue;
            }
            int at = m.end(1) - m.start();
            m.appendReplacement(out, Matcher.quoteReplacement(m.group().substring(0, at) + type + ".class, " + m.group().substring(at)));
        }
        m.appendTail(out);
        String result = out.toString();
        for (String name : imports) {
            if (name == null) continue;
            String simple = name.substring(name.lastIndexOf('.') + 1);
            if (Pattern.compile("(?m)^import\\s+[\\w.]+\\." + Pattern.quote(simple) + "\\s*;").matcher(result).find()) continue;
            result = LoaderRules.addImport(result, name);
        }
        return result;
    }

    /** How a class's source imports the class a type name starts with, as the bridge names it; null when it does not import it. */
    String importOf(String source, String type) {
        String simple = type.contains(".") ? type.substring(0, type.indexOf('.')) : type;
        Matcher imported = Pattern.compile("(?m)^import\\s+([\\w.]+\\." + Pattern.quote(simple) + ")\\s*;").matcher(source);
        if (!imported.find()) return null;
        String name = imported.group(1);
        Object mirrored = convert.bridges.bridge == null ? null : Json.object(convert.bridges.bridge.get("classes")).get(name);
        return mirrored == null ? name : bridgePackage + "." + mirrored;
    }

    /** The source of a class named in a file (its own package or an import), or null. */
    String classSource(String text, String name) throws IOException {
        String simple = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        Matcher imported = Pattern.compile("(?m)^import\\s+([\\w.]+\\." + Pattern.quote(simple) + ")\\s*;").matcher(text);
        Matcher pkg = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;").matcher(text);
        String full = imported.find() ? imported.group(1) : pkg.find() ? pkg.group(1) + "." + simple : simple;
        String file = convert.projectFiles.classFile(full);
        // A class reached through a wildcard import (import a.b.*;).
        for (Matcher wild = Pattern.compile("(?m)^import\\s+([\\w.]+)\\.\\*\\s*;").matcher(text); file == null && wild.find(); ) file = convert.projectFiles.classFile(
            wild.group(1) + "." + simple
        );
        return file == null ? null : convert.projectFiles.read(file);
    }

    /**
     * NeoForge's patches add constructors to some of the game's generic classes (new BlockEntityType<>(factory, blocks...));
     * on Fabric the bridge has a class of static of(...) overloads standing for every constructor NeoForge gives the class
     * (bridges[].constructors: game class to bridge class), so new X<>(args) becomes Factory.of(args) and Java picks the
     * overload as it picked the constructor. Only a class the source imports or names in full, never an anonymous subclass.
     */
    static String constructorsToFactories(String text, String game, String factory, String pkg) {
        String simple = game.substring(game.lastIndexOf('.') + 1);
        boolean imported = Pattern.compile("(?m)^import\\s+" + Pattern.quote(game) + "\\s*;").matcher(text).find();
        Matcher m = Pattern.compile("\\bnew\\s+(" + Pattern.quote(game) + (imported ? "|" + simple : "") + ")\\s*(<[^()]*>)?\\s*\\(").matcher(text);
        boolean[] code = LoaderRules.code(text);
        StringBuilder out = new StringBuilder();
        boolean changed = false;
        while (m.find()) {
            if (!code[m.start()]) continue;
            int close = closingParen(text, code, m.end() - 1);
            int after = close + 1;
            while (after < text.length() && Character.isWhitespace(text.charAt(after))) after++;
            // An anonymous subclass needs the constructor itself.
            if (close < 0 || after < text.length() && text.charAt(after) == '{') continue;
            String args = m.group(2) == null || m.group(2).replaceAll("\\s", "").equals("<>") ? "" : m.group(2).strip();
            m.appendReplacement(out, Matcher.quoteReplacement(factory + "." + args + "of("));
            changed = true;
        }
        if (!changed) return text;
        m.appendTail(out);
        return LoaderRules.addImport(out.toString(), pkg + "." + factory);
    }

    /**
     * A static method NeoForge's patch adds to a game class (bridges[].statics, "game.Class#method"): Class.method(...) in a
     * source that imports the class, or names it in full, calls the bridge's class of that name instead.
     */
    static String staticsToBridge(String text, String member, String to, String pkg) {
        String game = member.substring(0, member.indexOf('#'));
        String method = member.substring(member.indexOf('#') + 1);
        String simple = game.substring(game.lastIndexOf('.') + 1);
        boolean imported = Pattern.compile("(?m)^import\\s+" + Pattern.quote(game) + "\\s*;").matcher(text).find();
        String owner = Pattern.quote(game) + (imported ? "|" + Pattern.quote(simple) : "");
        Matcher m = Pattern.compile("(?<![\\w.$])(?:" + owner + ")\\s*\\.\\s*(<[^()]*>\\s*)?" + Pattern.quote(method) + "\\s*\\(").matcher(text);
        boolean[] code = LoaderRules.code(text);
        StringBuilder out = new StringBuilder();
        boolean changed = false;
        while (m.find()) {
            if (!code[m.start()]) continue;
            m.appendReplacement(out, Matcher.quoteReplacement(to + "." + (m.group(1) == null ? "" : m.group(1).strip()) + method + "("));
            changed = true;
        }
        if (!changed) return text;
        m.appendTail(out);
        return LoaderRules.addImport(out.toString(), pkg + "." + to);
    }

    /** The index of the ')' that closes the '(' at open, or -1 (strings and comments skipped). */
    static int closingParen(String text, boolean[] code, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            if (!code[i]) continue;
            char c = text.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }
}

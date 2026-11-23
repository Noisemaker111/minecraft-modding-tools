import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** A Fabric mod's sources and metadata as a NeoForge mod: entry points, menus, datagen and neoforge.mods.toml. */
final class ToNeoForge {

    final Convert convert;

    ToNeoForge(Convert convert) {
        this.convert = convert;
    }

    /** A Fabric mod id as NeoForge accepts it (^[a-z][a-z0-9_]{1,63}$): Fabric also allows "-". */
    static String neoforgeModId(String id) {
        String out = id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (out.isEmpty() || !Character.isLetter(out.charAt(0))) out = "mod_" + out;
        return out.length() > 64 ? out.substring(0, 64) : out;
    }

    // ---- Fabric to NeoForge ----------------------------------------------------------------------------

    void toNeoForge(String minecraft) throws Exception {
        String metaFile = "src/main/resources/fabric.mod.json";
        Map<String, Object> meta = Json.object(Json.parse(convert.projectFiles.read(metaFile)));
        convert.fabricId = Json.text(meta, "id", "");
        if (convert.fabricId.isEmpty()) throw new IOException("fabric.mod.json has no id.");
        convert.modid = neoforgeModId(convert.fabricId);
        if (!convert.modid.equals(convert.fabricId)) convert.notes.add(
            "NeoForge mod ids allow only a-z, 0-9 and _, so " + convert.fabricId + " is " + convert.modid + " on NeoForge; " +
            "its resources and registry ids keep the " + convert.fabricId + " namespace, and neoforge.mods.toml keeps the Fabric id for the way back."
        );
        // NeoForge is in Mojang's names: a yarn-named mod is renamed first, while its build is still Loom's.
        if (convert.names.yarnNames()) convert.names.yarnToMojang(minecraft);
        Set<String> unbridged = unbridge();
        if (Versions.compare(minecraft, ONLY_IN_ERROR) >= 0) dropSideOnly();
        convert.setAsideTestMods("fabric");
        convert.rewriteSources("fabric", "neoforge");
        convert.convertDataConditions(false);
        convert.convertDataIngredients(false);
        convert.fabricBridge.applyFabricBridge(meta, minecraft);
        Map<String, Object> entrypoints = new LinkedHashMap<>();
        if (meta.get("entrypoints") instanceof Map<?, ?>) for (var e : Json.object(meta.get("entrypoints")).entrySet()) {
            // Entrypoints the NeoForge bridge made of @Mod classes are @Mod classes again (unbridge).
            List<Object> kept = new ArrayList<>();
            for (Object item : Json.array(e.getValue())) {
                String name = item instanceof String str ? str : item instanceof Map<?, ?> ? Json.text(Json.object(item), "value", "") : "";
                if (!unbridged.contains(name)) kept.add(item);
            }
            if (!kept.isEmpty()) entrypoints.put(e.getKey(), kept);
            // A class named by a key other mods read, as well as by main, client or server, keeps a no-argument constructor.
            if (!Set.of("main", "client", "server", "modmenu", "fabric-datagen").contains(e.getKey())) for (Object item : kept) sharedEntrypoints.add(
                item instanceof String str ? str : Json.text(Json.object(item), "value", "")
            );
        }
        String datagen = datagenToNeoForge(entrypoints, minecraft);
        String menu = null;
        for (var entry : entrypoints.entrySet()) {
            List<String> classes = new ArrayList<>();
            for (Object item : Json.array(entry.getValue())) {
                if (item instanceof String s) classes.add(s);
                else if (item instanceof Map<?, ?>) classes.add(Json.text(Json.object(item), "value", ""));
            }
            for (String name : classes) {
                if (name.contains("::")) {
                    convert.projectFiles.leave(
                        metaFile,
                        0,
                        "entrypoint " + entry.getKey() + ": " + name,
                        "a method or field entrypoint has no NeoForge counterpart; make it a class"
                    );
                    continue;
                }
                switch (entry.getKey()) {
                    case "main" -> entryToNeoForge(name, "ModInitializer", "onInitialize", null, datagen);
                    case "client" -> entryToNeoForge(
                        name,
                        "ClientModInitializer",
                        "onInitializeClient",
                        "CLIENT",
                        null
                    );
                    case "server" -> entryToNeoForge(
                        name,
                        "DedicatedServerModInitializer",
                        "onInitializeServer",
                        "DEDICATED_SERVER",
                        null
                    );
                    case "modmenu" -> menu = name;
                    case "fabric-datagen" -> {
                        if (datagen == null) convert.projectFiles.leave(
                            metaFile,
                            0,
                            "entrypoint fabric-datagen: " + name,
                            "Fabric's data generation is converted for Minecraft 1.21.4 to 26.2 (the PackOutput providers); this one was not"
                        );
                        else if (!entrypoints.containsKey("main")) convert.projectFiles.leave(
                            metaFile,
                            0,
                            "entrypoint fabric-datagen: " + name,
                            "NeoForge runs data generation from the mod's @Mod class, and this mod has no main entrypoint to become one"
                        );
                    }
                    default -> {
                        // Kept in neoforge.mods.toml's modproperties, where the Fabric bridge's FabricLoader reads it. That
                        // serves the mod itself (an entrypoint named by its own id) and converted mods; others are named.
                        ownEntrypoints.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(name);
                        if (!entry.getKey().equals(convert.fabricId) || convert.fabricBridge.fabricBridgePackage == null) convert.projectFiles.leave(
                            metaFile,
                            0,
                            "entrypoint " + entry.getKey() + ": " + name,
                            "an entrypoint another mod reads; NeoForge has no counterpart (kept in neoforge.mods.toml as modproperties fabric.entrypoints." + entry.getKey() + ", which converted mods read)"
                        );
                    }
                }
            }
        }
        if (menu != null) menuToNeoForge(menu, entrypoints);
        if (meta.get("accessWidener") instanceof String widener) {
            String path = "src/main/resources/" + widener;
            String text = convert.projectFiles.read(path);
            if (text.isEmpty()) convert.projectFiles.leave(metaFile, 0, "accessWidener " + widener, "the file is not in src/main/resources");
            else {
                // An access widener in yarn's names cannot become a transformer in Mojang's names.
                boolean yarn = !convert.names.renamedFromYarn && BuildScript.properties(convert.projectFiles.read("gradle.properties")).keySet().stream().anyMatch(k -> k.startsWith("yarn_"));
                List<String> unconverted = new ArrayList<>();
                List<String> lines = ConvertAccess.widenerToTransformer(text, !yarn, unconverted);
                // Members NeoForge's own access transformer opens need no line of the mod's (the way back from Fabric).
                Set<String> opened = new HashSet<>();
                for (String line : convert.access.neoforgeAccessTransformers(minecraft)) {
                    String[] t = line.split("\\s+");
                    if (t.length >= 3) opened.add(t[1] + " " + t[2]);
                }
                lines.removeIf(line -> {
                    String[] t = line.split("\\s+");
                    return t.length >= 3 && opened.contains(t[1] + " " + t[2]);
                });
                convert.access.accessTransformers.addAll(ConvertAccess.withOwners(lines));
                for (String u : unconverted) convert.projectFiles.leave(path, 0, u, "an access widener entry with no access transformer counterpart");
                convert.projectFiles.delete(path);
                convert.widenerConverted = true;
                convert.applied.computeIfAbsent("access-widener", k -> new ArrayList<>()).add(path);
            }
        }
        convert.access.fabricApiWideners(minecraft);
        if (!convert.access.accessTransformers.isEmpty()) {
            // NeoForge applies a mod's access transformers when its mods.toml names the file (modsToml adds that).
            String existing = convert.projectFiles.read(ConvertAccess.AT_FILE);
            StringBuilder lines = new StringBuilder(
                existing.isEmpty() || existing.endsWith("\n") ? existing : existing + "\n"
            );
            for (String line : convert.access.accessTransformers) if (!existing.contains(line)) lines.append(line).append('\n');
            convert.projectFiles.write(ConvertAccess.AT_FILE, lines.toString());
            convert.notes.add(
                "Access transformer " +
                ConvertAccess.AT_FILE +
                " opens " +
                convert.access.accessTransformers.size() +
                " member(s) the converted code calls."
            );
        }
        convert.projectFiles.write("src/main/resources/META-INF/neoforge.mods.toml", modsToml(meta, minecraft));
        convert.projectFiles.delete(metaFile);
        convert.neoForgeBuild.buildToNeoForge(minecraft);
        String toml = convert.projectFiles.read("src/main/resources/META-INF/neoforge.mods.toml");
        String settled = unexpandedVersion(toml, convert.projectFiles.exists("build.gradle") ? convert.projectFiles.read("build.gradle") : "");
        if (!settled.equals(toml)) {
            convert.projectFiles.write("src/main/resources/META-INF/neoforge.mods.toml", settled);
            convert.notes.add("Version: fabric.mod.json's version was a Gradle placeholder that no step of the converted build expands (it was in a script that needs Loom), so neoforge.mods.toml reads the jar's version (${file.jarVersion}).");
        }
        // Fabric's loader and API packages: net.fabricmc's libraries (mapping-io, tiny-remapper) are plain dependencies
        // the build keeps, which compile on any loader.
        convert.leftovers(List.of("net.fabricmc.api.", "net.fabricmc.loader.", "net.fabricmc.fabric.", "com.terraformersmc.modmenu."));
    }

    /** Classes Fabric's metadata names under a key other than main, client, server, modmenu and fabric-datagen. */
    final Set<String> sharedEntrypoints = new HashSet<>();

    /** Fabric entrypoints other than main, client, server, modmenu and fabric-datagen, by key: kept in modproperties. */
    final Map<String, List<String>> ownEntrypoints = new LinkedHashMap<>();

    /** A static field whose initializer registers something: Registry.register, buildAndRegister, registerBlock ... */
    static final Pattern STATIC_REGISTRATION = Pattern.compile(
        "(?m)^[ \\t]*(?:(?:public|protected|private)\\s+)?static\\s+(?:final\\s+)?[^;=(){}]+=[^;]*?\\b\\w*[Rr]egister\\w*\\s*\\("
    );

    /** The file of the NeoForge @Mod class each Fabric entrypoint became, by its Fabric interface. */
    final Map<String, String> modClasses = new HashMap<>();

    /** True when the file imports a ModContainer that is not NeoForge's (the Fabric bridge mirrors Fabric Loader's under that name). */
    static boolean otherModContainer(String text) {
        Matcher m = Pattern.compile("(?m)^import\\s+([\\w.]+\\.ModContainer)\\s*;").matcher(text);
        while (m.find()) if (!m.group(1).equals("net.neoforged.fml.ModContainer")) return true;
        return false;
    }

    /** Makes a Fabric entrypoint class a NeoForge @Mod class whose constructor runs the entrypoint where NeoForge lets mods register. */
    void entryToNeoForge(String name, String api, String method, String dist, String extra) throws Exception {
        String file = convert.projectFiles.classFile(name);
        if (file == null) {
            convert.projectFiles.leave(
                "src/main/resources/fabric.mod.json",
                0,
                "entrypoint " + name,
                "its source file is not in this project"
            );
            return;
        }
        String text = convert.projectFiles.read(file), simple = name.substring(name.lastIndexOf('.') + 1);
        convert.unit = LoaderRules.unit(text);
        Matcher c = Convert.CLASS.matcher(text);
        boolean found = false;
        while (c.find()) if (c.group(3).equals(simple)) {
            found = true;
            break;
        }
        if (!found) {
            convert.projectFiles.leave(file, 0, "entrypoint " + name, "the class declaration was not found");
            return;
        }
        if (text.contains("@Mod(") || text.contains("@net.neoforged.fml.common.Mod(")) {
            convert.projectFiles.leave(
                file,
                LoaderRules.line(text, c.start()),
                "entrypoint " + name,
                "this class is already a NeoForge @Mod class, so it cannot also take " +
                api +
                "; give each entrypoint its own class"
            );
            return;
        }
        String heritage = c.group(4);
        String cleaned = heritage
            .replaceAll("\\bimplements\\s+" + api + "\\s*$", "")
            .replaceAll("\\bimplements\\s+" + api + "\\s*,\\s*", "implements ")
            .replaceAll(",\\s*" + api + "\\b", "");
        if (cleaned.equals(heritage)) {
            convert.projectFiles.leave(file, LoaderRules.line(text, c.start()), "entrypoint " + name, "it does not implement " + api);
            return;
        }
        String indent = c.group(1);
        String inner = indent + convert.unit;
        // A mod type of its own named Mod (imported, or in the same package) would be what @Mod names: the annotation is qualified.
        String dir = file.substring(0, file.lastIndexOf('/') + 1);
        boolean modShadowed = otherTypeNamed(text, "Mod", "net.neoforged.fml.common.Mod") || convert.projectFiles.exists(dir + "Mod.java");
        String annotation = (modShadowed ? "@net.neoforged.fml.common.Mod(" : "@Mod(") + (dist == null
            ? LoaderRules.quote(convert.modid) + ")"
            : "value = " + LoaderRules.quote(convert.modid) + ", dist = Dist." + dist + ")");
        // NeoForge loads a @Mod class before any registry is open. An entry class that registers in a static field
        // (a game rule, a block) must not be that class: a small generated holder runs it once registries are open.
        boolean holder = STATIC_REGISTRATION.matcher(text).find();
        String modClass = holder ? simple + "NeoForge" : simple, call = holder
            ? "new " + simple + "()." + method + "()"
            : method + "()";
        if (holder) inner = convert.unit;
        boolean noArgs = !holder && Pattern.compile("\\b" + simple + "\\s*\\(\\s*\\)\\s*\\{").matcher(text).find();
        StringBuilder ctor = new StringBuilder();
        boolean client = "CLIENT".equals(dist);
        // Fabric runs main entrypoints where mods register, and client ones once Minecraft exists, before its options load.
        // On NeoForge those are the first RegisterEvent and the first client event, RegisterKeyMappingsEvent (posted one
        // priority phase at a time, so key mappings the entrypoint adds at normal priority are still registered).
        ctor
            .append('\n')
            .append(inner)
            .append(
                client
                    ? "/** Runs the Fabric client entrypoint at NeoForge's first client event, once Minecraft exists and before its options load. */\n"
                    : "/** Runs the Fabric entrypoint where NeoForge lets a mod register: inside its first registry event. */\n"
            );
        ctor
            .append(inner)
            .append("public ")
            .append(modClass)
            .append("(IEventBus modBus")
            // The file may import another ModContainer (the Fabric bridge's mirror of Fabric Loader's): NeoForge's is then written in full.
            .append(dist == null ? "" : otherModContainer(text) ? ", net.neoforged.fml.ModContainer container" : ", ModContainer container")
            .append(") {\n");
        if (noArgs) ctor.append(inner).append(convert.unit).append("this();\n");
        // The Fabric bridge listens on the mod's bus for the events its Fabric API classes are posted from.
        if (convert.fabricBridge.fabricBridgePackage != null) ctor.append(inner).append(convert.unit).append("FabricBridge.init(modBus);\n");
        if (extra != null) ctor.append(inner).append(convert.unit).append(extra).append('\n');
        if (client) ctor
            .append(inner)
            .append(convert.unit)
            .append("modBus.addListener(EventPriority.HIGHEST, RegisterKeyMappingsEvent.class, event -> ")
            .append(call)
            .append(");\n");
        else {
            ctor
                .append(inner)
                .append(convert.unit)
                .append("modBus.addListener(")
                .append(dist == null ? "" : "EventPriority.LOW, ")
                .append("RegisterEvent.class, event -> {\n");
            ctor
                .append(inner)
                .append(convert.unit)
                .append(convert.unit)
                .append("if (event.getRegistryKey().equals(Registries.ATTRIBUTE)) ")
                .append(call)
                .append(";\n");
            ctor.append(inner).append(convert.unit).append("});\n");
        }
        ctor.append(inner).append("}\n");
        // A class that is also another key's entrypoint (a plugin other mods read) is made by Fabric with its no-argument
        // constructor. Its implicit one would go with the constructor above, and NeoForge allows one public constructor:
        // a package-private one stays for FabricLoader.make, which opens it.
        boolean anyConstructor = Pattern.compile("(?m)^[ \\t]*(?:(?:public|protected|private)\\s+)?" + simple + "\\s*\\(").matcher(text).find();
        if (!holder && !anyConstructor && sharedEntrypoints.contains(name)) ctor
            .append('\n')
            .append(inner)
            .append(Convert.KEPT_CONSTRUCTOR)
            .append('\n')
            .append(inner)
            .append(simple)
            .append("() {\n")
            .append(inner)
            .append("}\n");
        String declaration =
            c.group(1) + c.group(2) + "class " + simple + (cleaned.isBlank() ? " " : " " + cleaned.strip() + " ") + "{";
        String out =
            text.substring(0, c.start()) +
            (holder ? "" : indent + annotation + "\n") +
            declaration +
            text.substring(c.end());
        out = out.replaceAll(
            "(?m)^([ \\t]*)@Override[ \\t]*\\n[ \\t]*(public\\s+void\\s+" + method + "\\s*\\()",
            "$1$2"
        );
        out = Pattern.compile("(?m)^import\\s+net\\.fabricmc\\.api\\." + api + "\\s*;[^\\n]*\\n?")
            .matcher(out)
            .replaceAll("");
        List<String> needed = new ArrayList<>(List.of("net.neoforged.bus.api.IEventBus"));
        if (!modShadowed) needed.add("net.neoforged.fml.common.Mod");
        if (client) needed.add("net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent");
        else needed.addAll(
            List.of("net.neoforged.neoforge.registries.RegisterEvent", "net.minecraft.core.registries.Registries")
        );
        if (dist != null) needed.addAll(
            List.of(
                "net.neoforged.api.distmarker.Dist",
                "net.neoforged.fml.ModContainer",
                "net.neoforged.bus.api.EventPriority"
            )
        );
        if (extra != null && extra.contains("GatherDataEvent")) needed.add(
            "net.neoforged.neoforge.data.event.GatherDataEvent"
        );
        if (convert.fabricBridge.fabricBridgePackage != null) needed.add(convert.fabricBridge.fabricBridgePackage + ".FabricBridge");
        if (holder) {
            convert.projectFiles.write(file, LoaderRules.tidy(out));
            Matcher pkg = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;").matcher(text);
            String generated =
                (pkg.find() ? "package " + pkg.group(1) + ";\n\n" : "") +
                "/** NeoForge's entry for " +
                simple +
                ", which registers in its static fields: it may load only once NeoForge opens the registries. */\n" +
                annotation +
                "\npublic final class " +
                modClass +
                " {\n" +
                ctor.substring(1) +
                "}\n";
            for (String name2 : needed) generated = LoaderRules.addImport(generated, name2);
            String holderFile = file.substring(0, file.lastIndexOf('/') + 1) + modClass + ".java";
            convert.projectFiles.write(holderFile, generated);
            modClasses.put(api, holderFile);
            convert.notes.add(
                simple +
                " registers in its static fields, so its NeoForge @Mod class is the generated " +
                modClass +
                ", which runs " +
                simple +
                "." +
                method +
                "() once the registries are open."
            );
            convert.applied.computeIfAbsent("entrypoint-" + api, k -> new ArrayList<>()).add(holderFile);
            return;
        }
        // The constructor goes just before the entry method it runs, with its own blank line.
        Matcher entry = Pattern.compile(
            "(?m)^[ \\t]*(?:/\\*\\*(?:[^*]|\\*(?!/))*\\*/\\s*)?public\\s+void\\s+" + method + "\\s*\\("
        ).matcher(out);
        if (entry.find()) out =
            out.substring(0, entry.start()) + ctor.substring(1) + "\n" + out.substring(entry.start());
        else {
            int open = out.indexOf('{', out.indexOf(annotation) + annotation.length()) + 1;
            out = out.substring(0, open) + ctor + out.substring(open);
        }
        for (String name2 : needed) out = LoaderRules.addImport(out, name2);
        convert.projectFiles.write(file, out);
        modClasses.put(api, file);
        convert.applied.computeIfAbsent("entrypoint-" + api, k -> new ArrayList<>()).add(file);
    }

    /**
     * Fabric data generation onto NeoForge's: each provider class onto the vanilla provider it wraps, the
     * fabric-datagen entrypoint onto a GatherDataEvent.Client listener the main @Mod class adds. Returns that
     * listener's line for the main constructor, or null when the mod has no datagen or it cannot be converted.
     */
    String datagenToNeoForge(Map<String, Object> entrypoints, String minecraft) throws Exception {
        List<String> names = new ArrayList<>();
        for (Object item : Json.array(entrypoints.getOrDefault("fabric-datagen", List.of()))) names.add(
            item instanceof String s ? s : Json.text(Json.object(item), "value", "")
        );
        if (names.size() != 1 || !entrypoints.containsKey("main")) return null;
        // The providers' PackOutput shape is Minecraft's from 1.21.4 to 26.2; 26.3 changed the vanilla provider constructors.
        if (Versions.compare(minecraft, "1.21.4") < 0 || Versions.compare(minecraft, "26.3") >= 0) return null;
        Map<String, String> kinds = new HashMap<>();
        boolean complete = true;
        for (String file : convert.projectFiles.javaFiles()) {
            LoaderRules.Provider provider = LoaderRules.datagenProvider(convert.projectFiles.read(file), convert.modid);
            if (provider == null) continue;
            if (provider.kind().isEmpty()) {
                convert.projectFiles.leave(
                    file,
                    0,
                    "data provider " + file.substring(file.lastIndexOf('/') + 1),
                    "it extends a Fabric datagen base with no rule yet (models, block loot, recipes and advancements have one)"
                );
                complete = false;
                continue;
            }
            convert.projectFiles.write(file, provider.text());
            kinds.put(file.substring(file.lastIndexOf('/') + 1).replace(".java", ""), provider.kind());
            convert.applied.computeIfAbsent("datagen-" + provider.kind(), k -> new ArrayList<>()).add(file);
        }
        String file = convert.projectFiles.classFile(names.get(0));
        if (file == null) return null;
        List<String> unknown = new ArrayList<>();
        String entry = LoaderRules.datagenEntry(convert.projectFiles.read(file), kinds, unknown);
        if (entry == null) {
            convert.projectFiles.leave(file, 0, "entrypoint fabric-datagen", "onInitializeDataGenerator(FabricDataGenerator) was not found");
            return null;
        }
        for (String provider : unknown) convert.projectFiles.leave(
            file,
            0,
            "data provider " + provider,
            "its kind has no rule, so it is not registered with GatherDataEvent"
        );
        convert.projectFiles.write(file, entry);
        convert.applied.computeIfAbsent("datagen-entrypoint", k -> new ArrayList<>()).add(file);
        convert.datagenOutput = "src/main/generated";
        if (complete && unknown.isEmpty()) convert.notes.add(
            "Data generation: mmt compile --task :runClientData writes the generated files to the folder the Fabric datagen used."
        );
        return (
            "modBus.addListener(GatherDataEvent.Client.class, event -> new " +
            names.get(0) +
            "().onInitializeDataGenerator(event));"
        );
    }

    /** Mod Menu's config screen becomes NeoForge's IConfigScreenFactory, registered by the client entrypoint. */
    void menuToNeoForge(String menu, Map<String, Object> entrypoints) throws Exception {
        String file = convert.projectFiles.classFile(menu);
        if (file == null) {
            convert.projectFiles.leave(
                "src/main/resources/fabric.mod.json",
                0,
                "entrypoint modmenu: " + menu,
                "its source file is not in this project"
            );
            return;
        }
        String text = convert.projectFiles.read(file);
        if (
            Pattern.compile("getProvidedConfigScreenFactories|getUpdateChecker|attachModpackBadges")
                .matcher(text)
                .find()
        ) convert.projectFiles.leave(
            file,
            0,
            "Mod Menu API",
            "only getModConfigScreenFactory has a NeoForge counterpart (IConfigScreenFactory)"
        );
        // Mod Menu itself (or a mod shipping its API): ModMenuApi and ConfigScreenFactory are the mod's own types, kept.
        boolean ownApi = convert.projectFiles.javaFiles().stream().anyMatch(f -> f.contains("/com/terraformersmc/modmenu/api/"));
        if (!ownApi) {
            convert.projectFiles.write(file, LoaderRules.modMenuToNeoForge(text));
            for (String other : convert.projectFiles.javaFiles()) {
                String body = convert.projectFiles.read(other);
                String next = body.replace(".getModConfigScreenFactory().create(", ".getModConfigScreenFactory().apply(");
                convert.projectFiles.write(other, next);
            }
        }
        String register =
            "container.registerExtensionPoint(IConfigScreenFactory.class, (modContainer, parent) -> new " +
            menu +
            "().getModConfigScreenFactory()." + (ownApi ? "create" : "apply") + "(parent));";
        String client = null;
        for (Object item : Json.array(entrypoints.getOrDefault("client", List.of()))) client = item instanceof String s
            ? s
            : Json.text(Json.object(item), "value", "");
        String clientFile = client == null ? null : modClasses.getOrDefault("ClientModInitializer", convert.projectFiles.classFile(client));
        if (clientFile == null) {
            convert.projectFiles.leave(
                file,
                0,
                "Mod Menu entry " + menu,
                "the mod has no client entrypoint to register NeoForge's config screen from"
            );
            return;
        }
        String text2 = convert.projectFiles.read(clientFile);
        Matcher ctor = Pattern.compile("ModContainer container\\) \\{\\n").matcher(text2);
        if (!ctor.find()) {
            convert.projectFiles.leave(
                clientFile,
                0,
                "Mod Menu entry " + menu,
                "the client entrypoint was not converted, so the config screen is not registered"
            );
            return;
        }
        int at = ctor.end();
        text2 = text2.substring(0, at) + LoaderRules.indent(text2, at) + register + "\n" + text2.substring(at);
        text2 = LoaderRules.addImport(text2, "net.neoforged.neoforge.client.gui.IConfigScreenFactory");
        convert.projectFiles.write(clientFile, text2);
        convert.applied.computeIfAbsent("modmenu-config-screen", k -> new ArrayList<>()).add(file);
        convert.notes.add(
            "Mod Menu's config screen opens from NeoForge's own mod list (IConfigScreenFactory); the modmenu dependency is gone."
        );
    }

    static String tomlString(String value) {
        if (value.contains("\n")) return "'''\n" + value + "\n'''";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** A Fabric version predicate as a Maven range: >=1.2 is [1.2,), ~26.3 is [26.3,26.4), * is any. */
    static String mavenRange(Object predicate) {
        String value = predicate instanceof List<?> list && !list.isEmpty()
            ? String.valueOf(list.get(0))
            : String.valueOf(predicate);
        value = value.strip();
        if (value.equals("*") || value.isEmpty()) return "[0,)";
        Matcher tilde = Pattern.compile("^~(\\d+)\\.(\\d+)(.*)$").matcher(value);
        if (tilde.find()) return (
            "[" + value.substring(1) + "," + tilde.group(1) + "." + (Integer.parseInt(tilde.group(2)) + 1) + ")"
        );
        Matcher caret = Pattern.compile("^\\^(\\d+)(.*)$").matcher(value);
        if (caret.find()) return "[" + value.substring(1) + "," + (Integer.parseInt(caret.group(1)) + 1) + ")";
        String low = null, high = null;
        boolean lowIn = true, highIn = false;
        for (String part : value.split("\\s+")) {
            if (part.startsWith(">=")) low = part.substring(2);
            else if (part.startsWith(">")) {
                low = part.substring(1);
                lowIn = false;
            } else if (part.startsWith("<=")) {
                high = part.substring(2);
                highIn = true;
            } else if (part.startsWith("<")) high = part.substring(1);
            else if (part.startsWith("=")) return "[" + part.substring(1) + "]";
            else return "[" + part + "]";
        }
        return (
            (low == null ? "(" : lowIn ? "[" : "(") +
            (low == null ? "" : low) +
            "," +
            (high == null ? "" : high) +
            (high == null ? ")" : highIn ? "]" : ")")
        );
    }

    static final Set<String> FABRIC_ONLY = Set.of(
        "fabricloader",
        "fabric",
        "fabric-api",
        "fabric-api-base",
        "java",
        "modmenu"
    );

    /** Fabric's loader and Fabric API, whole or one of its modules (fabric-screen-api-v1 ...): no NeoForge mod of that id. */
    static boolean fabricOnly(String id) {
        return FABRIC_ONLY.contains(id) || id.matches("fabric-[a-z0-9-]+-v\\d+");
    }

    String modsToml(Map<String, Object> meta, String minecraft) {
        StringBuilder out = new StringBuilder();
        out.append("modLoader = \"javafml\"\nloaderVersion = \"[1,)\"\n");
        Object license = meta.get("license");
        String licence = license instanceof List<?> l
            ? String.join(", ", l.stream().map(String::valueOf).toList())
            : license == null ? "All Rights Reserved" : String.valueOf(license);
        out.append("license = ").append(tomlString(licence)).append('\n');
        Map<String, Object> contact = meta.get("contact") instanceof Map<?, ?>
            ? Json.object(meta.get("contact"))
            : Map.of();
        if (contact.get("issues") != null) out
            .append("issueTrackerURL = ")
            .append(tomlString(String.valueOf(contact.get("issues"))))
            .append('\n');
        out.append("\n[[mods]]\nmodId = ").append(tomlString(convert.modid)).append('\n');
        out.append("version = ").append(tomlString(Json.text(meta, "version", "${version}"))).append('\n');
        out.append("displayName = ").append(tomlString(Json.text(meta, "name", convert.modid))).append('\n');
        List<String> authors = new ArrayList<>();
        for (Object a : meta.get("authors") instanceof List<?>
            ? Json.array(meta.get("authors"))
            : List.of()) authors.add(
            a instanceof Map<?, ?> ? Json.text(Json.object(a), "name", "") : String.valueOf(a)
        );
        if (!authors.isEmpty()) out.append("authors = ").append(tomlString(String.join(", ", authors))).append('\n');
        if (contact.get("homepage") != null) out
            .append("displayURL = ")
            .append(tomlString(String.valueOf(contact.get("homepage"))))
            .append('\n');
        Object icon = meta.get("icon");
        // NeoForge 26.2 reads a square iconFile and warns about logoFile; earlier releases read only logoFile.
        String iconKey = Versions.compare(minecraft, "26.2") >= 0 ? "iconFile" : "logoFile";
        if (icon instanceof String s) out.append(iconKey + " = ").append(tomlString(s)).append('\n');
        else if (icon instanceof Map<?, ?> sizes && !sizes.isEmpty()) out
            .append(iconKey + " = ")
            .append(tomlString(String.valueOf(new TreeMap<>(Json.object(icon)).lastEntry().getValue())))
            .append('\n');
        out.append("description = ").append(tomlString(Json.text(meta, "description", ""))).append('\n');
        StringBuilder properties = new StringBuilder();
        if (!convert.modid.equals(convert.fabricId)) properties.append("fabricId = ").append(tomlString(convert.fabricId)).append('\n');
        // The side fabric.mod.json names, which the Fabric bridge's ModMetadata.getEnvironment reads.
        if (meta.get("environment") instanceof String side && (side.equals("client") || side.equals("server"))) properties
            .append("fabricEnvironment = ")
            .append(tomlString(side))
            .append('\n');
        for (var e : ownEntrypoints.entrySet()) properties
            .append(tomlString("fabric.entrypoints." + e.getKey()))
            .append(" = [")
            .append(String.join(", ", e.getValue().stream().map(ToNeoForge::tomlString).toList()))
            .append("]\n");
        Map<String, Object> custom = meta.get("custom") instanceof Map<?, ?> values ? withoutBridgeInterfaces(Json.object(values)) : Map.of();
        if (!custom.isEmpty()) properties
            .append(tomlString("fabric.custom"))
            .append(" = ")
            .append(tomlString(Json.write(custom)))
            .append('\n');
        // A required Fabric-only library the Fabric bridge stands in for is no NeoForge dependency; the way back restores it.
        if (convert.fabricBridge.fabricBridgePackage != null && meta.get("depends") instanceof Map<?, ?> depends) for (var dep : Json.object(depends).entrySet()) if (
            convert.fabricBridge.fabricBridgeReplacedMods.contains(dep.getKey())
        ) properties.append(tomlString("fabric.depends." + dep.getKey())).append(" = ").append(tomlString(Json.write(dep.getValue()))).append('\n');
        if (properties.length() > 0) out.append("\n[modproperties.").append(convert.modid).append("]\n").append(properties);
        if ("client".equals(meta.get("environment"))) convert.notes.add(
            "fabric.mod.json said environment client: on NeoForge the mod's only entry class is @Mod(dist = Dist.CLIENT), so a server loads none of its code."
        );
        if ("server".equals(meta.get("environment"))) convert.notes.add(
            "fabric.mod.json said environment server: on NeoForge the mod's only entry class is @Mod(dist = Dist.DEDICATED_SERVER)."
        );
        String neoRange = "[" + convert.neoforgePrefix(minecraft).replaceAll("\\.$", "") + ",)";
        out
            .append("\n[[dependencies.")
            .append(convert.modid)
            .append("]]\nmodId = \"neoforge\"\ntype = \"required\"\nversionRange = ")
            .append(tomlString(neoRange))
            .append("\nordering = \"NONE\"\nside = \"BOTH\"\n");
        Map<String, String> kinds = new LinkedHashMap<>();
        kinds.put("depends", "required");
        kinds.put("recommends", "optional");
        kinds.put("suggests", "optional");
        kinds.put("breaks", "incompatible");
        kinds.put("conflicts", "discouraged");
        boolean minecraftDone = false;
        for (var kind : kinds.entrySet()) {
            if (!(meta.get(kind.getKey()) instanceof Map<?, ?>)) continue;
            for (var dep : Json.object(meta.get(kind.getKey())).entrySet()) {
                String id = dep.getKey();
                if (fabricOnly(id)) continue;
                if (id.equals("another-mod")) continue;
                // A Fabric-only library the Fabric bridge carries in this mod (bridges[].replacesMods) is no dependency there.
                if (convert.fabricBridge.fabricBridgePackage != null && convert.fabricBridge.fabricBridgeReplacedMods.contains(id)) {
                    convert.notes.add("Dependency " + id + " is removed; the Fabric bridge carries the API the code uses from it.");
                    continue;
                }
                String range = mavenRange(dep.getValue());
                if (id.equals("minecraft")) {
                    minecraftDone = true;
                    if (range.equals("[0,)")) range = "[" + minecraft + ",)";
                }
                out
                    .append("\n[[dependencies.")
                    .append(convert.modid)
                    .append("]]\nmodId = ")
                    .append(tomlString(id))
                    .append("\ntype = ")
                    .append(tomlString(kind.getValue()))
                    .append("\nversionRange = ")
                    .append(tomlString(range))
                    .append("\nordering = \"NONE\"\nside = \"BOTH\"\n");
                if (!id.equals("minecraft")) convert.notes.add(
                    "Dependency " +
                    id +
                    " (" +
                    kind.getKey() +
                    ") is kept by id; it needs a NeoForge build of that mod."
                );
            }
        }
        if (!minecraftDone) out
            .append("\n[[dependencies.")
            .append(convert.modid)
            .append("]]\nmodId = \"minecraft\"\ntype = \"required\"\nversionRange = ")
            .append(tomlString("[" + minecraft + ",)"))
            .append("\nordering = \"NONE\"\nside = \"BOTH\"\n");
        if (!convert.access.accessTransformers.isEmpty()) out.append(
            "\n[[accessTransformers]]\nfile = \"META-INF/accesstransformer.cfg\"\n"
        );
        for (Object m : meta.get("mixins") instanceof List<?> ? Json.array(meta.get("mixins")) : List.of()) {
            String config = m instanceof Map<?, ?> ? Json.text(Json.object(m), "config", "") : String.valueOf(m);
            // The NeoForge bridge's own mixins go with the bridge (unbridge removes their config).
            if (config.endsWith(".loaderbridge.mixins.json")) continue;
            out.append("\n[[mixins]]\nconfig = ").append(tomlString(config)).append('\n');
        }
        if (convert.bridges.bridgeMixinConfig != null) out.append("\n[[mixins]]\nconfig = ").append(tomlString(convert.bridges.bridgeMixinConfig)).append('\n');
        if (meta.get("accessWidener") != null && !convert.widenerConverted) convert.projectFiles.leave(
            "src/main/resources/fabric.mod.json",
            0,
            "accessWidener " + meta.get("accessWidener"),
            "access wideners are not converted to NeoForge access transformers yet"
        );
        if (meta.get("jars") != null) convert.projectFiles.leave(
            "src/main/resources/fabric.mod.json",
            0,
            "jars",
            "nested jars are declared by the build (jarJar) on NeoForge; convert them by hand"
        );
        return out.toString();
    }

    /**
     * From NeoForge for Minecraft 26.1 (26.1.1.15-beta, the first with net.neoforged.neoforge.common.OnlyInWarningsHandler)
     * a mod class marked @OnlyIn logs an ERROR on every launch: NeoForge no longer strips by side, and client code is kept
     * apart by where it is called from. Fabric's @Environment therefore has no counterpart there and goes.
     */
    static final String ONLY_IN_ERROR = "26.1";

    static final Pattern SIDE_ONLY = Pattern.compile(
        "(?m)^[ \\t]*@(?:net\\.fabricmc\\.api\\.)?Environment\\(\\s*(?:net\\.fabricmc\\.api\\.)?EnvType\\.\\w+\\s*\\)[ \\t]*\\n|" +
        "@(?:net\\.fabricmc\\.api\\.)?Environment\\(\\s*(?:net\\.fabricmc\\.api\\.)?EnvType\\.\\w+\\s*\\)\\s*"
    );

    /** A source without Fabric's @Environment(EnvType.X) marks (in code, not comments) and their import. */
    static String withoutSideOnly(String text) {
        boolean[] code = LoaderRules.code(text);
        Matcher m = SIDE_ONLY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, code[m.start() + m.group().indexOf('@')] ? "" : Matcher.quoteReplacement(m.group()));
        m.appendTail(out);
        return LoaderRules.dropImport(out.toString(), "net.fabricmc.api.Environment");
    }

    /** Fabric's @Environment(EnvType.X) marks removed (NeoForge 26.1 and later), with their import. */
    void dropSideOnly() throws Exception {
        int dropped = 0;
        for (String file : convert.projectFiles.javaFiles()) {
            String text = convert.projectFiles.read(file), out = withoutSideOnly(text);
            if (out.equals(text)) continue;
            convert.projectFiles.write(file, out);
            dropped += SIDE_ONLY.matcher(text).results().count();
            convert.applied.computeIfAbsent("side-only-dropped", k -> new ArrayList<>()).add(file);
        }
        if (dropped > 0) convert.notes.add(
            "@Environment: NeoForge for Minecraft " + ONLY_IN_ERROR + " and later logs an error for @OnlyIn on mod classes, " +
            "so the " + dropped + " side mark(s) are removed rather than written as @OnlyIn; client code stays apart by where it is called from."
        );
    }

    /** The way back: Attachments.getData(holder, TYPE) is holder.getData(TYPE) again. */
    static String attachmentCallsBack(String text) {
        boolean[] code = LoaderRules.code(text);
        Matcher m = Pattern.compile("\\bAttachments\\.(" + String.join("|", Convert.HOLDER_METHODS) + ")\\(").matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            if (!code[m.start()] || m.start() < last) continue;
            int open = m.end() - 1;
            int close = LoaderRules.close(text, code, open);
            if (close < 0) continue;
            List<String> parts = LoaderRules.split(text.substring(open + 1, close - 1));
            if (parts.size() < 2) continue;
            out.append(text, last, m.start()).append(parts.get(0)).append('.').append(m.group(1)).append('(').append(String.join(", ", parts.subList(1, parts.size()))).append(')');
            last = close;
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    /** fabric.mod.json's custom values without the NeoForge bridge's injected interfaces, which go with the bridge. */
    static Map<String, Object> withoutBridgeInterfaces(Map<String, Object> custom) {
        if (!(custom.get("loom:injected_interfaces") instanceof Map<?, ?> injected)) return custom;
        Map<String, Object> out = new LinkedHashMap<>(custom), kept = new LinkedHashMap<>();
        for (var e : Json.object(injected).entrySet()) {
            List<Object> names = new ArrayList<>();
            for (Object name : e.getValue() instanceof List<?> list ? Json.array(list) : List.of()) if (!String.valueOf(name).contains("/loaderbridge/")) names.add(name);
            if (!names.isEmpty()) kept.put(e.getKey(), names);
        }
        if (kept.isEmpty()) out.remove("loom:injected_interfaces");
        else out.put("loom:injected_interfaces", kept);
        return out;
    }

    // ---- back through the NeoForge bridge ---------------------------------------------------------------------

    static final Pattern BRIDGED_ENTRY = Pattern.compile(
        "(?m)^([ \\t]*)/\\*\\* NeoForge's constructor of this @Mod class, run as Fabric's entrypoint \\(mmt convert, NeoForge bridge\\)\\. \\*/\\n[ \\t]*@Override\\n[ \\t]*public void (onInitialize|onInitializeClient|onInitializeServer)\\(\\) \\{"
    );

    static final Pattern BRIDGED_LOCAL = Pattern.compile(
        // The side local reads EnvType here: the way back undoes the bridge before the rules turn EnvType into Dist.
        "^\\s*(IEventBus|ModContainer|Dist|EnvType) (\\w+) = (?:Bridge\\.bus\\(\\)|Bridge\\.container\\(.*\\)|(?:Dist|EnvType)\\.\\w+|FMLLoader\\.getDist\\(\\));\\s*$"
    );

    static final Pattern BRIDGED_END = Pattern.compile("^\\s*(?:Bridge\\.common|ClientBridge\\.client)\\((.+)\\);\\s*$");

    /** An event class convert wrote into addListener for the bridge, before a method reference or typed lambda. */
    static final Pattern WRITTEN_EVENT_CLASS = Pattern.compile(
        "\\.addListener\\(\\s*((?:EventPriority\\.\\w+\\s*,\\s*)?(?:(?:true|false)\\s*,\\s*)?)[A-Z][\\w.]*\\.class,\\s*(?=(?:this|[A-Z]\\w*(?:\\.[A-Z]\\w*)*)::\\w+\\s*\\)|\\(\\s*(?:final\\s+)?[A-Z][\\w.]*(?:<[^>]*>)?\\s+\\w+\\s*\\)\\s*->)"
    );

    /** The way back: Factory.of(args) is new X<>(args) again, and the import is the game class's. */
    static String factoriesToConstructors(String text, String game, String factory, String pkg) {
        Matcher imported = Pattern.compile("(?m)^import\\s+" + Pattern.quote(pkg + "." + factory) + "\\s*;[ \\t]*\\n?").matcher(text);
        if (!imported.find()) return text;
        String simple = game.substring(game.lastIndexOf('.') + 1);
        boolean hasGame = Pattern.compile("(?m)^import\\s+" + Pattern.quote(game) + "\\s*;").matcher(text).find();
        String out = imported.replaceFirst(hasGame ? "" : Matcher.quoteReplacement("import " + game + ";\n"));
        return Pattern.compile("(?<![\\w.])" + Pattern.quote(factory) + "\\.(<[^()]*>)?of\\(").matcher(out).replaceAll(
            r -> Matcher.quoteReplacement("new " + simple + (r.group(1) == null ? "<>" : r.group(1)) + "(")
        );
    }

    /** The way back: the bridge's class is the game class again for that method. */
    static String bridgeToStatics(String text, String member, String to, String pkg) {
        Matcher imported = Pattern.compile("(?m)^import\\s+" + Pattern.quote(pkg + "." + to) + "\\s*;[ \\t]*\\n?").matcher(text);
        if (!imported.find()) return text;
        String game = member.substring(0, member.indexOf('#'));
        String simple = game.substring(game.lastIndexOf('.') + 1);
        boolean hasGame = Pattern.compile("(?m)^import\\s+" + Pattern.quote(game) + "\\s*;").matcher(text).find();
        String out = imported.replaceFirst(hasGame ? "" : Matcher.quoteReplacement("import " + game + ";\n"));
        return out.replaceAll("(?<![\\w.])" + Pattern.quote(to) + "\\.", Matcher.quoteReplacement(simple + "."));
    }

    /**
     * A mod convert moved to Fabric through the NeoForge bridge, going back to NeoForge: every entrypoint the bridge made
     * of a @Mod class is that @Mod class again (its constructor takes back the IEventBus, ModContainer and Dist the bridge
     * supplied, without the bridge's own calls), imports of the bridge's classes name NeoForge's again, the event class
     * written into addListener goes, and the bridge's files and the client entrypoint it added are removed. Returns the
     * entrypoint classes that became @Mod classes, which the Fabric entrypoints then leave alone.
     */
    Set<String> unbridge() throws Exception {
        Set<String> done = new HashSet<>();
        String pkg = null;
        for (String file : convert.projectFiles.javaFiles()) if (
            file.endsWith("/Bridge.java") && convert.projectFiles.read(file).contains("What a NeoForge @Mod class's constructor received")
        ) {
            Matcher m = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;").matcher(convert.projectFiles.read(file));
            if (m.find()) pkg = m.group(1);
        }
        if (pkg == null) return done;
        Map<String, String> back = new HashMap<>();
        for (Object item : LoaderRules.list(convert.rules.get("bridges"))) {
            var b = Json.object(item);
            if (!"neoforge".equals(b.get("from"))) continue;
            for (var c : Json.object(b.get("classes")).entrySet()) back.put(String.valueOf(c.getValue()), c.getKey());
        }
        String folder = "src/main/java/" + pkg.replace('.', '/') + "/";
        List<String> sources = new ArrayList<>(convert.projectFiles.javaFiles());
        sources.addAll(convert.projectFiles.testJavaFiles());
        for (String file : sources) {
            if (file.startsWith(folder)) continue;
            String text = convert.projectFiles.read(file), out = text;
            Matcher entry = BRIDGED_ENTRY.matcher(out);
            if (entry.find()) {
                String converted = unbridgeEntry(file, out, entry);
                if (converted != null) {
                    out = converted;
                    done.add(file.replaceFirst("^src/\\w+/java/", "").replaceAll("\\.java$", "").replace('/', '.'));
                }
            }
            for (var c : back.entrySet()) out = out.replaceAll(
                "(?m)^import(\\s+static)?\\s+" + Pattern.quote(pkg + "." + c.getKey()) + "((?:\\.\\w+)*(?:\\.\\*)?)\\s*;",
                "import$1 " + Matcher.quoteReplacement(c.getValue()) + "$2;"
            );
            for (var c : back.entrySet()) out = out.replaceAll(
                "(?<![\\w.])" + Pattern.quote(pkg + "." + c.getKey()) + "(?![\\w$])",
                Matcher.quoteReplacement(c.getValue())
            );
            out = NeoForgeSources.back(out, pkg);
            for (Object item : LoaderRules.list(convert.rules.get("bridges"))) {
                for (var c : Json.object(Json.object(item).getOrDefault("constructors", Map.of())).entrySet()) out = factoriesToConstructors(out, c.getKey(), String.valueOf(c.getValue()), pkg);
                for (var s : Json.object(Json.object(item).getOrDefault("statics", Map.of())).entrySet()) out = bridgeToStatics(out, s.getKey(), Json.text(Json.object(s.getValue()), "to", ""), pkg);
            }
            out = LoaderRules.dropImport(LoaderRules.dropImport(out, pkg + ".Bridge"), pkg + ".ClientBridge");
            out = LoaderRules.dropImport(attachmentCallsBack(out), pkg + ".Attachments");
            // The side local of a common entrypoint read FMLLoader; the constructor takes Dist again.
            out = LoaderRules.dropImport(out, "net.neoforged.fml.loading.FMLLoader");
            out = WRITTEN_EVENT_CLASS.matcher(out).replaceAll(".addListener($1");
            if (!out.equals(text)) {
                convert.projectFiles.write(file, out);
                convert.applied.computeIfAbsent("neoforge-bridge-back", k -> new ArrayList<>()).add(file);
            }
        }
        for (String file : convert.projectFiles.javaFiles()) if (file.startsWith(folder)) convert.projectFiles.delete(file);
        Path resources = convert.project.root.resolve("src/main/resources");
        if (Files.isDirectory(resources)) try (var list = Files.list(resources)) {
            for (Path config : list.filter(p -> p.getFileName().toString().endsWith(".loaderbridge.mixins.json")).toList()) convert.projectFiles.delete(convert.projectFiles.relative(config));
        }
        done.add(pkg + ".ClientEntrypoint");
        convert.notes.add("NeoForge bridge: " + pkg + " is removed; the @Mod classes it ran as Fabric entrypoints are @Mod classes again.");
        return done;
    }

    /** One entrypoint the bridge made, back as NeoForge's @Mod class constructor; null (and named) when it is not as the bridge wrote it. */
    String unbridgeEntry(String file, String text, Matcher entry) {
        String simple = file.substring(file.lastIndexOf('/') + 1).replace(".java", "");
        boolean[] code = LoaderRules.code(text);
        int open = entry.end() - 1;
        int end = LoaderRules.close(text, code, open);
        if (end < 0) return null;
        String indent = entry.group(1), method = entry.group(2);
        List<String> params = new ArrayList<>(), body = new ArrayList<>();
        String id = null;
        for (String line : text.substring(open + 1, end - 1).split("\n", -1)) {
            Matcher local = BRIDGED_LOCAL.matcher(line), last = BRIDGED_END.matcher(line);
            if (body.stream().allMatch(String::isBlank) && local.matches()) params.add(local.group(1) + " " + local.group(2));
            else if (last.matches()) id = last.group(1);
            else if (!line.matches("^\\s*Bridge\\.subscribe\\([\\w.]+\\.class\\);\\s*$")) body.add(line);
        }
        if (id == null) {
            convert.projectFiles.leave(file, LoaderRules.line(text, entry.start()), "entrypoint " + simple, "the NeoForge bridge's entrypoint no longer ends as the bridge wrote it");
            return null;
        }
        String inner = String.join("\n", body).replaceAll("^(?:[ \\t]*\\n)+", "").replaceAll("\\s+$", "");
        String ctor = params.isEmpty() && inner.isEmpty()
            ? ""
            : indent + "public " + simple + "(" + String.join(", ", params) + ") {\n" + (inner.isEmpty() ? "" : inner + "\n") + indent + "}";
        String out = text.substring(0, entry.start()) + ctor + text.substring(end);
        String side = method.equals("onInitializeClient") ? "CLIENT" : method.equals("onInitializeServer") ? "DEDICATED_SERVER" : null;
        String api = side == null ? "ModInitializer" : side.equals("CLIENT") ? "ClientModInitializer" : "DedicatedServerModInitializer";
        Matcher c = Convert.CLASS.matcher(out);
        while (c.find()) if (c.group(3).equals(simple)) {
            String heritage = c.group(4)
                .replaceAll("\\bimplements\\s+" + api + "\\s*$", "")
                .replaceAll("\\bimplements\\s+" + api + "\\s*,\\s*", "implements ")
                .replaceAll(",\\s*" + api + "\\b", "");
            String at = otherTypeNamed(out, "Mod", "net.neoforged.fml.common.Mod") ? "@net.neoforged.fml.common.Mod(" : "@Mod(";
            String annotation = side == null ? at + id + ")" : at + "value = " + id + ", dist = Dist." + side + ")";
            out =
                out.substring(0, c.start()) +
                c.group(1) +
                annotation +
                "\n" +
                c.group(1) +
                c.group(2) +
                "class " +
                simple +
                (heritage.isBlank() ? " " : " " + heritage.strip() + " ") +
                "{" +
                out.substring(c.end());
            break;
        }
        out = LoaderRules.dropImport(out, "net.fabricmc.api." + api);
        if (!out.contains("@net.neoforged.fml.common.Mod(")) out = LoaderRules.addImport(out, "net.neoforged.fml.common.Mod");
        return side == null ? out : LoaderRules.addImport(out, "net.neoforged.api.distmarker.Dist");
    }

    /**
     * neoforge.mods.toml whose mod version is a Gradle placeholder ($version, ${version}) that the build does not expand
     * (no processResources expand of it): NeoForge refuses such a version, so it becomes NeoForge's own ${file.jarVersion}.
     */
    static String unexpandedVersion(String toml, String gradle) {
        boolean expands = gradle.contains("neoforge.mods.toml") && gradle.contains("expand");
        if (expands) return toml;
        return toml.replaceAll("(?m)^(version\\s*=\\s*)\"\\$\\{?(?:project\\.)?version}?\"", "$1\"\\${file.jarVersion}\"");
    }

    /** Whether the file imports a type of that simple name other than the one given. */
    static boolean otherTypeNamed(String text, String simple, String qualified) {
        Matcher imported = Pattern.compile("(?m)^import\\s+([\\w.]+\\." + Pattern.quote(simple) + ")\\s*;").matcher(text);
        while (imported.find()) if (!imported.group(1).equals(qualified)) return true;
        return false;
    }
}

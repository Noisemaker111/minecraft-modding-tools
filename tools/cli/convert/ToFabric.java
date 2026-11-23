import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** A NeoForge mod's sources and metadata as a Fabric mod: entry points, mixins and fabric.mod.json. */
final class ToFabric {

    final Convert convert;

    ToFabric(Convert convert) {
        this.convert = convert;
    }

    /** A Maven range as a Fabric version predicate. */
    static String fabricPredicate(String range) {
        String value = range.strip();
        if (value.equals("[0,)") || value.equals("*") || value.isEmpty()) return "*";
        Matcher m = Pattern.compile("^([\\[(])([^,]*)(?:,([^\\])]*))?([\\])])$").matcher(value);
        if (!m.find()) return value;
        if (m.group(3) == null) return m.group(2);
        Matcher minor = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.0)?$").matcher(m.group(2));
        if (
            m.group(1).equals("[") &&
            m.group(4).equals(")") &&
            minor.find() &&
            m.group(3).equals(minor.group(1) + "." + (Integer.parseInt(minor.group(2)) + 1))
        ) return "~" + m.group(2);
        List<String> parts = new ArrayList<>();
        if (!m.group(2).isEmpty()) parts.add((m.group(1).equals("[") ? ">=" : ">") + m.group(2));
        if (!m.group(3).isEmpty()) parts.add((m.group(4).equals("]") ? "<=" : "<") + m.group(3));
        return parts.isEmpty() ? "*" : String.join(" ", parts);
    }

    // ---- NeoForge to Fabric ------------------------------------------------------------------------------

    /** A small TOML reader for neoforge.mods.toml: top-level keys, [[mods]], [[dependencies.id]] and [[mixins]] tables. */
    static Map<String, Object> toml(String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> current = out;
        List<String> lines = text.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Matcher table = Pattern.compile("^\\[\\[([\\w.\\-]+)]]").matcher(line);
            if (table.find()) {
                current = new LinkedHashMap<>();
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) out.computeIfAbsent("[[" + table.group(1) + "]]", k ->
                    new ArrayList<>()
                );
                list.add(current);
                continue;
            }
            if (line.startsWith("[")) {
                current = new LinkedHashMap<>();
                out.put(line, current);
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).strip(), value = line.substring(eq + 1).strip();
            for (String quote : List.of("'''", "\"\"\"")) if (value.startsWith(quote)) {
                StringBuilder s = new StringBuilder(value.substring(3));
                while (!s.toString().contains(quote) && i + 1 < lines.size()) s.append('\n').append(lines.get(++i));
                String all = s.toString();
                value = all.substring(0, all.indexOf(quote)).replaceAll("^\\n", "").replaceAll("\\n$", "");
                current.put(key, value);
                key = null;
                break;
            }
            if (key == null) continue;
            if (value.startsWith("\"")) {
                Matcher m = Pattern.compile("^\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(value);
                current.put(key, m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : value);
            } else if (value.startsWith("'")) current.put(key, value.substring(1, value.indexOf('\'', 1)));
            else current.put(key, value.replaceAll("\\s*#.*$", ""));
        }
        return out;
    }

    void toFabric(String minecraft) throws Exception {
        String metaFile = Files.isRegularFile(convert.project.root.resolve(Convert.NEO_META)) ? Convert.NEO_META : Convert.NEO_TEMPLATE;
        String metaText = convert.projectFiles.read(metaFile);
        String expanded = convert.buildScript.expandProperties(metaText);
        convert.metadataExpanded = !expanded.equals(metaText);
        if (convert.metadataExpanded) convert.notes.add(
            metaFile +
            " is expanded by the build from gradle.properties; fabric.mod.json gets the expanded values, and the build's expand step for it is removed."
        );
        Map<String, Object> toml = toml(expanded);
        List<Object> mods = toml.get("[[mods]]") instanceof List<?> l ? Json.array(l) : List.of();
        if (mods.size() != 1) throw new IOException(
            "neoforge.mods.toml declares " + mods.size() + " mods; convert moves a jar with one mod."
        );
        Map<String, Object> mod = Json.object(mods.get(0));
        convert.modid = Json.text(mod, "modId", "");
        convert.fabricId = toml.get("[modproperties." + convert.modid + "]") instanceof Map<?, ?> properties ? Json.text(Json.object(properties), "fabricId", convert.modid) : convert.modid;
        // The @Mod classes first: their annotation names Dist, which the source rules would otherwise rename.
        Map<String, List<String>> entrypoints = new LinkedHashMap<>();
        String menu = null;
        convert.fabricBridge.unbridgeFabric();
        convert.neoForgeBridge.prepareBridge(minecraft);
        for (String file : convert.projectFiles.javaFiles()) {
            String text = convert.projectFiles.read(file);
            Matcher annotation = Pattern.compile(
                "@(?:net\\.neoforged\\.fml\\.common\\.)?Mod\\(\\s*(?:value\\s*=\\s*)?([^,)]+?)\\s*(?:,\\s*dist\\s*=\\s*Dist\\.(\\w+)\\s*)?\\)"
            ).matcher(text);
            if (!annotation.find()) continue;
            String name = file.replaceFirst("^src/\\w+/java/", "").replaceAll("\\.java$", "").replace('/', '.');
            String dist = annotation.group(2);
            String api = dist == null
                ? "ModInitializer"
                : dist.equals("CLIENT") ? "ClientModInitializer" : "DedicatedServerModInitializer";
            String method = dist == null
                ? "onInitialize"
                : dist.equals("CLIENT") ? "onInitializeClient" : "onInitializeServer";
            Matcher menuLine = Pattern.compile(
                "container\\.registerExtensionPoint\\(IConfigScreenFactory\\.class, \\(modContainer, parent\\) -> new ([\\w.]+)\\(\\)\\.getModConfigScreenFactory\\(\\)\\.apply\\(parent\\)\\);\\n"
            ).matcher(text);
            if (menuLine.find()) {
                menu = menuLine.group(1);
                text = text.substring(0, text.lastIndexOf('\n', menuLine.start()) + 1) + text.substring(menuLine.end());
                text = LoaderRules.dropImport(text, "net.neoforged.neoforge.client.gui.IConfigScreenFactory");
            }
            Matcher made = Pattern.compile(
                "/\\*\\* NeoForge's entry for (\\w+), which registers in its static fields"
            ).matcher(text);
            if (made.find()) {
                String target = file.substring(0, file.lastIndexOf('/') + 1) + made.group(1) + ".java";
                if (
                    !convert.projectFiles.exists(target) ||
                    !text.contains("new " + made.group(1) + "()." + method + "()")
                ) {
                    convert.projectFiles.leave(
                        file,
                        0,
                        "@Mod class " + made.group(1) + "NeoForge",
                        "it names an entry class this conversion cannot find"
                    );
                    continue;
                }
                convert.projectFiles.delete(file);
                convert.projectFiles.write(target, Convert.implementFabric(convert.projectFiles.read(target), made.group(1), api, method));
                entrypoints
                    .computeIfAbsent(dist == null ? "main" : dist.equals("CLIENT") ? "client" : "server", k ->
                        new ArrayList<>()
                    )
                    .add(name.substring(0, name.lastIndexOf('.') + 1) + made.group(1));
                convert.applied.computeIfAbsent("entrypoint-" + api, k -> new ArrayList<>()).add(target);
                continue;
            }
            String converted = entryToFabric(file, text, annotation, api, method);
            if (converted == null) continue;
            convert.projectFiles.write(file, converted);
            entrypoints
                .computeIfAbsent(dist == null ? "main" : dist.equals("CLIENT") ? "client" : "server", k ->
                    new ArrayList<>()
                )
                .add(name);
            convert.applied.computeIfAbsent("entrypoint-" + api, k -> new ArrayList<>()).add(file);
        }
        if (convert.neoForgeBridge.bridgeUsed && !convert.neoForgeBridge.clientEntry) {
            // NeoForge posts FMLClientSetupEvent and runs client subscribers without a client @Mod class; Fabric needs an entrypoint for that.
            String name = convert.neoForgeBridge.bridgePackage + ".ClientEntrypoint";
            StringBuilder body = new StringBuilder();
            for (NeoForgeBridge.Subscriber s : convert.neoForgeBridge.subscribers) if (s.client()) body.append("\t\tBridge.subscribe(").append(s.type()).append(".class);\n");
            convert.projectFiles.write(
                convert.neoForgeBridge.bridgeFolder() + "/ClientEntrypoint.java",
                "package " +
                convert.neoForgeBridge.bridgePackage +
                ";\n\n/** The client entrypoint mmt convert added: NeoForge's client subscribers and FMLClientSetupEvent. */\npublic final class ClientEntrypoint implements net.fabricmc.api.ClientModInitializer {\n\n\t@Override\n\tpublic void onInitializeClient() {\n" +
                body +
                "\t\tClientBridge.client(" +
                LoaderRules.quote(convert.modid) +
                ");\n\t}\n}\n"
            );
            entrypoints.computeIfAbsent("client", k -> new ArrayList<>()).add(name);
        }
        convert.rewriteSources("neoforge", "fabric");
        convert.convertDataConditions(true);
        convert.convertDataIngredients(true);
        dropNeoForgeTabOrder();
        // Before the bridge points NeoForge's names at its own classes and copies, which a mixin's descriptors then hide.
        neoforgeParametersOutOfMixins();
        if (convert.neoForgeBridge.bridgeUsed) convert.neoForgeBridge.applyBridge();
        if (convert.neoForgeBridge.bridgeUsed) setAsideDatagen();
        convert.setAsideTestMods("neoforge");
        lambdaMixinTargets(minecraft);
        enumExtensionLookups();
        superHookCalls();
        // The members of NeoForge's copied classes that do not compile on Fabric come out once the rules have run, as
        // bench/bridge_import.py measured them.
        for (String file : convert.neoForgeBridge.copiedFiles) {
            String name = NeoForgeSources.PREFIX + file.substring((convert.neoForgeBridge.bridgeFolder() + "/" + NeoForgeSources.FOLDER + "/").length(), file.length() - ".java".length()).replace('/', '.');
            Set<String> gone = convert.neoForgeBridge.copiedMembersGone.get(name);
            if (gone != null && convert.projectFiles.exists(file)) convert.projectFiles.write(file, NeoForgeSources.remove(convert.projectFiles.read(file), gone));
        }
        // NeoForge generates data from GatherDataEvent listeners; Fabric from fabric-datagen entrypoints: the bridge's
        // DataEntrypoint posts the event (when the bridge has it for this game version).
        if (convert.neoForgeBridge.bridgeUsed && convert.projectFiles.exists(convert.neoForgeBridge.bridgeFolder() + "/DataEntrypoint.java")) {
            boolean generates = false;
            for (String file : convert.projectFiles.javaFiles()) if (!file.startsWith(convert.neoForgeBridge.bridgeFolder() + "/")) generates |= convert.projectFiles.read(file).contains("GatherDataEvent");
            if (generates) entrypoints.computeIfAbsent("fabric-datagen", k -> new ArrayList<>()).add(convert.neoForgeBridge.bridgePackage + ".DataEntrypoint");
        }
        if (menu != null) {
            String file = convert.projectFiles.classFile(menu);
            if (file != null) {
                convert.projectFiles.write(file, LoaderRules.modMenuToFabric(convert.projectFiles.read(file)));
                for (String other : convert.projectFiles.javaFiles()) convert.projectFiles.write(
                    other,
                    convert.projectFiles.read(other).replace(".getModConfigScreenFactory().apply(", ".getModConfigScreenFactory().create(")
                );
                entrypoints.computeIfAbsent("modmenu", k -> new ArrayList<>()).add(menu);
                convert.applied.computeIfAbsent("modmenu-config-screen", k -> new ArrayList<>()).add(file);
            }
        }
        // Access transformer lines a rule added for NeoForge go away with the code that needed them; the mod's own
        // lines become a Fabric access widener.
        Set<String> known = new HashSet<>();
        for (Object rule : LoaderRules.list(convert.rules.get("expressions"))) if (
            Json.object(rule).get("accessTransformers") instanceof Map<?, ?> at
        ) known.addAll(LoaderRules.strings(Json.object(at).get("neoforge")));
        // NeoForge's own access transformer opened members for every mod; those the code names stay open on Fabric.
        List<String> neoforgeAccess = convert.access.usedNeoForgeAccess(minecraft);
        // And the lines a rule's Fabric code needs (ShapedRecipePattern.MAX_SIZE for NeoForge's getMaxHeight()).
        if (convert.projectFiles.exists(ConvertAccess.AT_FILE) || !neoforgeAccess.isEmpty() || !convert.access.accessTransformers.isEmpty()) {
            List<String> keep = new ArrayList<>();
            if (convert.projectFiles.exists(ConvertAccess.AT_FILE)) for (String line : convert.projectFiles.read(ConvertAccess.AT_FILE).lines().toList()) if (!known.contains(line.strip())) keep.add(line);
            for (String line : neoforgeAccess) if (!keep.contains(line)) keep.add(line);
            for (String line : convert.access.accessTransformers) if (!keep.contains(line)) keep.add(line);
            if (!neoforgeAccess.isEmpty()) convert.notes.add(
                "Access widener: " +
                neoforgeAccess.size() +
                " member(s) NeoForge's own access transformer opens for every mod, which the code uses: " +
                String.join("; ", neoforgeAccess) +
                "."
            );
            if (!keep.stream().allMatch(l -> l.isBlank() || l.strip().startsWith("#"))) {
                List<String> unconverted = new ArrayList<>();
                String widener = ConvertAccess.transformerToWidener(
                    String.join("\n", keep),
                    Versions.compare(minecraft, Route.FIRST_UNOBFUSCATED) >= 0,
                    unconverted,
                    convert.access.gameFieldTypes(minecraft)
                );
                for (String u : unconverted) {
                    if (u.endsWith("; dropped)")) convert.notes.add("Access widener: " + u + ".");
                    else convert.projectFiles.leave(ConvertAccess.AT_FILE, 0, u, "an access transformer line with no access widener counterpart");
                }
                convert.fabricWidener = convert.fabricId + ".accesswidener";
                convert.projectFiles.write("src/main/resources/" + convert.fabricWidener, widener);
                convert.widenerConverted = true;
                convert.applied.computeIfAbsent("access-widener", k -> new ArrayList<>()).add(ConvertAccess.AT_FILE);
            }
            if (convert.projectFiles.exists(ConvertAccess.AT_FILE)) convert.projectFiles.delete(ConvertAccess.AT_FILE);
        }
        toml.remove("[[accessTransformers]]");
        convert.fabricBuild.buildToFabric(minecraft, menu != null);
        convert.projectFiles.write("src/main/resources/fabric.mod.json", fabricJson(toml, mod, entrypoints, minecraft, menu != null));
        convert.projectFiles.delete(metaFile);
        convert.leftovers(List.of("net.neoforged."));
    }

    /**
     * A mod mixin whose target is a game method NeoForge's patches give an extra parameter (BlockRenderDispatcher's
     * renderBreakingTexture with NeoForge's ModelData): on Fabric the game's method has no such parameter, so the NeoForge
     * types leave the target's descriptor and the handler's parameters, and the mixin targets the game's own method.
     */
    void neoforgeParametersOutOfMixins() throws Exception {
        for (String file : convert.projectFiles.javaFiles()) {
            String text = convert.projectFiles.read(file);
            if (!text.contains("@Mixin") || !text.contains("net/neoforged/") && !text.contains("net.neoforged.")) continue;
            String out = neoforgeParametersOutOfMixin(text);
            if (out.equals(text)) continue;
            convert.projectFiles.write(file, out);
            convert.applied.computeIfAbsent("mixin-neoforge-parameters", k -> new ArrayList<>()).add(file);
            convert.notes.add(file + ": NeoForge's own parameter types left this mixin's target descriptors and handlers (the game's method on Fabric has none).");
        }
    }

    static String neoforgeParametersOutOfMixin(String text) {
        // In a descriptor string: (...Lnet/neoforged/...;...) loses that parameter.
        Matcher strings = Pattern.compile("\"([^\"\\n]*\\([^\"\\n]*\\)[^\"\\n]*)\"").matcher(text);
        StringBuilder out = new StringBuilder();
        while (strings.find()) {
            String descriptor = strings.group(1);
            int open = descriptor.indexOf('(');
            int close = descriptor.indexOf(')');
            if (open < 0 || close < open || !descriptor.contains("Lnet/neoforged/")) {
                strings.appendReplacement(out, Matcher.quoteReplacement(strings.group()));
                continue;
            }
            String params = descriptor.substring(open + 1, close).replaceAll("Lnet/neoforged/[\\w/$]+;", "");
            strings.appendReplacement(out, Matcher.quoteReplacement("\"" + descriptor.substring(0, open + 1) + params + descriptor.substring(close) + "\""));
        }
        strings.appendTail(out);
        // A handler's parameter of a NeoForge type, written out in full.
        return out.toString().replaceAll(",\\s*(?:final\\s+)?net\\.neoforged\\.[\\w.]+\\s+\\w+(?=\\s*[,)])", "");
    }

    /**
     * A mod mixin that targets a game lambda by the compiler's name (method = "lambda$registerTypes$0", the name NeoForge's
     * development game keeps) targets it by Loom's name for it, the intermediary one (LambdaTargets): Mojang's mappings
     * leave the game's lambdas out of Loom's names. Only for a game still shipped obfuscated (before 26.1).
     */
    void lambdaMixinTargets(String minecraft) throws Exception {
        if (Versions.compare(minecraft, Route.FIRST_UNOBFUSCATED) >= 0) return;
        String[] mappings = null;
        for (String file : convert.projectFiles.javaFiles()) {
            String text = convert.projectFiles.read(file);
            if (!text.contains("@Mixin") || !text.contains("\"lambda$")) continue;
            String owner = mixinOwner(text);
            if (owner == null || !owner.startsWith("net.minecraft.")) continue;
            if (mappings == null) mappings = convert.names.gameAndIntermediaryMappings(minecraft);
            String[] names = mappings;
            Matcher lambda = Pattern.compile("\"(lambda\\$[\\w$]+?)(?=[\"(])").matcher(text);
            StringBuilder out = new StringBuilder();
            List<String> moved = new ArrayList<>();
            while (lambda.find()) {
                String name = LambdaTargets.intermediary(names[0], names[1], owner, lambda.group(1));
                if (name != null) moved.add(lambda.group(1) + " -> " + name);
                lambda.appendReplacement(out, Matcher.quoteReplacement("\"" + (name == null ? lambda.group(1) : name)));
            }
            lambda.appendTail(out);
            if (moved.isEmpty()) continue;
            convert.projectFiles.write(file, out.toString());
            convert.applied.computeIfAbsent("mixin-lambda-targets", k -> new ArrayList<>()).add(file);
            convert.notes.add(file + ": mixin targets of the game's lambdas by Loom's names (" + String.join(", ", moved) + ").");
        }
    }

    /**
     * The constants a mod adds to the game's enums (its META-INF/enumextensions.json, which NeoForge builds into the enums
     * at start) do not exist on Fabric: a lookup of one by name (RecipeBookCategories.valueOf("MOD_TAB")) becomes the
     * bridge's EnumProxy.fallback, the game's constant that stands in for it, as the mod's EnumProxy fields give.
     */
    void enumExtensionLookups() throws Exception {
        if (convert.neoForgeBridge.bridgePackage == null || !convert.projectFiles.exists(convert.neoForgeBridge.bridgeFolder() + "/EnumProxy.java")) return;
        List<String[]> entries = new ArrayList<>();
        for (String file : List.of("src/main/resources/META-INF/enumextensions.json", "src/client/resources/META-INF/enumextensions.json")) {
            if (!convert.projectFiles.exists(file)) continue;
            for (Object item : Json.array(Json.object(Json.parse(convert.projectFiles.read(file))).get("entries"))) {
                var entry = Json.object(item);
                String type = Json.text(entry, "enum", "");
                entries.add(new String[] { type.substring(type.lastIndexOf('/') + 1).replace('$', '.'), Json.text(entry, "name", "") });
            }
        }
        if (entries.isEmpty()) return;
        for (String file : convert.projectFiles.javaFiles()) {
            String text = convert.projectFiles.read(file);
            String out = enumExtensionLookups(text, entries, convert.neoForgeBridge.bridgePackage);
            if (out.equals(text)) continue;
            convert.projectFiles.write(file, out);
            convert.applied.computeIfAbsent("enum-extension-lookups", k -> new ArrayList<>()).add(file);
        }
        convert.notes.add("Enum constants the mod adds (META-INF/enumextensions.json) cannot be added on Fabric: lookups of them by name are the game's stand-in constant (EnumProxy.fallback).");
    }

    /**
     * A rule that makes a NeoForge hook call a bridge helper (block.getLightEmission(...) to
     * IBlockExtension.getLightEmission(block, ...)) has made super.hook(...) Helper.hook(super, ...), which is no Java. When
     * the class's superclass is the mod's own and it (or a mod ancestor) declares the hook, the call stays super.hook(...);
     * otherwise the superclass is the game's, whose hook is NeoForge's default, the helper's: Helper.hook(this, ...).
     */
    void superHookCalls() throws Exception {
        if (convert.neoForgeBridge.bridgePackage == null) return;
        Map<String, String> classes = new HashMap<>();
        List<String> files = convert.projectFiles.javaFiles().stream().filter(f -> !f.startsWith(convert.neoForgeBridge.bridgeFolder() + "/")).toList();
        for (String file : files) classes.put(file.substring(file.lastIndexOf('/') + 1).replaceAll("\\.java$", ""), convert.projectFiles.read(file));
        Set<String> helpers = new HashSet<>();
        for (String file : convert.projectFiles.javaFiles()) if (file.startsWith(convert.neoForgeBridge.bridgeFolder() + "/")) helpers.add(file.substring(file.lastIndexOf('/') + 1).replaceAll("\\.java$", ""));
        for (String file : files) {
            String text = classes.get(file.substring(file.lastIndexOf('/') + 1).replaceAll("\\.java$", ""));
            String out = superHookCalls(text, classes, helpers);
            if (out.equals(text)) continue;
            convert.projectFiles.write(file, out);
            convert.applied.computeIfAbsent("neoforge-hook-super-calls", k -> new ArrayList<>()).add(file);
        }
    }

    static String superHookCalls(String text, Map<String, String> modClasses, Set<String> helpers) {
        Matcher call = Pattern.compile("\\b([A-Z]\\w*)\\.(\\w+)\\(\\s*super\\s*(,\\s*|(?=\\)))").matcher(text);
        StringBuilder out = new StringBuilder();
        boolean any = false;
        while (call.find()) {
            if (!helpers.contains(call.group(1))) continue;
            any = true;
            String method = call.group(2);
            String replacement = declaresUpward(superclassOf(text), method, modClasses, new HashSet<>())
                ? "super." + method + "("
                : call.group(1) + "." + method + "(this" + call.group(3);
            call.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        if (!any) return text;
        call.appendTail(out);
        return out.toString();
    }

    /** The simple name of the first class's superclass in the text, or null. */
    static String superclassOf(String text) {
        Matcher m = Pattern.compile("\\bclass\\s+\\w+\\s*(?:<[^{]*?>)?\\s*extends\\s+([\\w.]+)").matcher(text);
        return m.find() ? m.group(1).substring(m.group(1).lastIndexOf('.') + 1) : null;
    }

    /** Whether the mod's class of that simple name, or a mod ancestor of it, declares a method of that name. */
    static boolean declaresUpward(String simple, String method, Map<String, String> modClasses, Set<String> seen) {
        if (simple == null || !seen.add(simple)) return false;
        String source = modClasses.get(simple);
        if (source == null) return false;
        if (Pattern.compile("(?m)^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:public|protected)\\s[^;=(]*\\b" + Pattern.quote(method) + "\\s*\\(").matcher(source).find()) return true;
        return declaresUpward(superclassOf(source), method, modClasses, seen);
    }

    static String enumExtensionLookups(String text, List<String[]> entries, String bridgePackage) {
        String out = text;
        for (String[] entry : entries) {
            String simple = entry[0].substring(entry[0].lastIndexOf('.') + 1);
            out = out.replaceAll(
                "(?<![\\w.])((?:[\\w]+\\.)*" + Pattern.quote(simple) + ")\\.valueOf\\(\\s*\"" + Pattern.quote(entry[1]) + "\"\\s*\\)",
                Matcher.quoteReplacement(bridgePackage) + ".EnumProxy.fallback($1.class)"
            );
        }
        return out;
    }

    /** The class a mixin source targets (@Mixin(X.class), X resolved by its imports; or @Mixin(targets = "...")), in dots. */
    static String mixinOwner(String text) {
        Matcher named = Pattern.compile("@Mixin\\(\\s*(?:value\\s*=\\s*)?\\{?\\s*([\\w.]+)\\.class").matcher(text);
        if (named.find()) {
            String simple = named.group(1);
            if (simple.contains(".")) return simple;
            Matcher imported = Pattern.compile("(?m)^import\\s+([\\w.]+\\." + Pattern.quote(simple) + ")\\s*;").matcher(text);
            return imported.find() ? imported.group(1) : null;
        }
        Matcher targets = Pattern.compile("@Mixin\\(\\s*targets\\s*=\\s*\\{?\\s*\"([\\w.$/]+)\"").matcher(text);
        return targets.find() ? targets.group(1).replace('/', '.') : null;
    }

    /** Where setAsideDatagen keeps data generation code the bridge cannot build: a folder no source set compiles. */
    static final String DATAGEN_ASIDE = "src/neoforge-datagen/java/";

    /**
     * Data generation code that needs NeoForge classes the bridge does not have for this game version (NeoForge 21.1's
     * model generators) is moved out of the build, so the rest of the mod compiles: the classes reachable from the mod's
     * GatherDataEvent listeners that nothing else in the mod uses (a recipe class kept in a datagen package but used by
     * the game stays). The mod's generated resources are kept, so the game reads the same data; the move is listed.
     */
    void setAsideDatagen() throws Exception {
        List<String> files = convert.projectFiles.javaFiles().stream().filter(f -> f.startsWith("src/main/java/") && !f.startsWith(convert.neoForgeBridge.bridgeFolder() + "/")).toList();
        Map<String, String> bySimple = new HashMap<>();
        for (String f : files) bySimple.put(f.substring(f.lastIndexOf('/') + 1).replaceAll("\\.java$", ""), f);
        // The bridge's registration of an @EventBusSubscriber class is not a use: it goes with the class.
        Pattern subscribe = Pattern.compile("(?m)^[ \\t]*Bridge\\.subscribe\\(([\\w.]+)\\.class\\);[ \\t]*\\n");
        Map<String, Set<String>> uses = new HashMap<>();
        for (String f : files) {
            Set<String> used = new HashSet<>();
            Matcher word = Pattern.compile("\\b[A-Z]\\w*\\b").matcher(subscribe.matcher(convert.projectFiles.read(f)).replaceAll(""));
            while (word.find()) {
                String other = bySimple.get(word.group());
                if (other != null && !other.equals(f)) used.add(other);
            }
            uses.put(f, used);
        }
        Set<String> datagen = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>();
        for (String f : files) if (convert.projectFiles.read(f).contains("GatherDataEvent")) todo.add(f);
        while (!todo.isEmpty()) {
            String f = todo.poll();
            if (datagen.add(f)) todo.addAll(uses.get(f));
        }
        // What the rest of the mod uses stays in the build, and so does what that uses.
        for (boolean changed = true; changed; ) {
            changed = false;
            for (String f : files) if (!datagen.contains(f)) for (String used : uses.get(f)) changed |= datagen.remove(used);
        }
        Set<String> missing = new TreeSet<>();
        for (String f : datagen) {
            Matcher neoforge = Pattern.compile("(?m)^import\\s+(?:static\\s+)?(net\\.neoforged\\.[\\w.]+)\\s*;").matcher(convert.projectFiles.read(f));
            while (neoforge.find()) missing.add(neoforge.group(1));
        }
        if (missing.isEmpty()) return;
        Set<String> moved = new TreeSet<>();
        for (String f : datagen) {
            convert.projectFiles.write(DATAGEN_ASIDE + f.substring("src/main/java/".length()), convert.projectFiles.read(f));
            convert.projectFiles.delete(f);
            moved.add(f.substring("src/main/java/".length()).replaceAll("\\.java$", "").replace('/', '.'));
        }
        for (String f : convert.projectFiles.javaFiles()) {
            String text = convert.projectFiles.read(f);
            String out = subscribe.matcher(text).replaceAll(r -> moved.contains(r.group(1)) ? "" : Matcher.quoteReplacement(r.group()));
            if (!out.equals(text)) convert.projectFiles.write(f, out);
        }
        convert.notes.add(
            "Data generation: " + moved.size() + " class(es) used only to generate data need NeoForge classes the bridge does not have for this game (" +
            String.join(", ", missing) + "), so they are kept in " + DATAGEN_ASIDE + " outside the build; the generated resources the mod ships are unchanged."
        );
        convert.applied.computeIfAbsent("datagen-set-aside", k -> new ArrayList<>()).addAll(datagen);
    }

    void dropNeoForgeTabOrder() throws Exception {
        int dropped = 0;
        for (String file : convert.projectFiles.javaFiles()) {
            String text = convert.projectFiles.read(file);
            String out = dropCalls(text, List.of("withTabsBefore", "withTabsAfter"));
            if (!out.equals(text)) {
                convert.projectFiles.write(file, out);
                dropped++;
                convert.applied.computeIfAbsent("creative-tab-order", k -> new ArrayList<>()).add(file);
            }
        }
        if (dropped > 0) convert.notes.add(
            "Creative tab order: NeoForge's withTabsBefore and withTabsAfter have no Fabric counterpart, so the converted tabs take the next free place (" +
            dropped +
            " file(s))."
        );
    }

    /** Removes chained calls .name(...) (balanced arguments, code only) from a text. */
    static String dropCalls(String text, List<String> names) {
        boolean[] code = LoaderRules.code(text);
        Matcher m = Pattern.compile("\\s*\\.\\s*(?:" + String.join("|", names) + ")\\s*\\(").matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            int open = m.end() - 1;
            if (!code[open] || open < last) continue;
            int end = LoaderRules.close(text, code, open);
            if (end < 0) continue;
            out.append(text, last, m.start());
            last = end;
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    /** A NeoForge @Mod class back as a Fabric entrypoint: the constructor convert wrote is removed; any other constructor is named. */
    String entryToFabric(String file, String text, Matcher annotation, String api, String method) {
        String simple = file.substring(file.lastIndexOf('/') + 1).replace(".java", "");
        // The no-argument constructor the way to NeoForge kept for another key's entrypoint was the implicit one.
        text = text.replaceFirst(
            "\\n[ \\t]*" + Pattern.quote(Convert.KEPT_CONSTRUCTOR) + "\\n[ \\t]*" + simple + "\\(\\) \\{\\n[ \\t]*}\\n",
            ""
        );
        Matcher ctor = Pattern.compile(
            "(?s)\\n[ \\t]*/\\*\\* Runs the Fabric (?:client )?entrypoint[^\\n]*\\n[ \\t]*public " +
            simple +
            "\\(IEventBus modBus(?:, (?:net\\.neoforged\\.fml\\.)?ModContainer container)?\\) \\{\\n(.*?)\\n[ \\t]*}\\n"
        ).matcher(text);
        if (!ctor.find() || !ctor.group(1).contains(method + "()")) {
            if (convert.neoForgeBridge.bridgePackage != null) return convert.neoForgeBridge.bridgeEntry(file, text, annotation, simple, api, method);
            convert.projectFiles.leave(
                file,
                LoaderRules.line(text, annotation.start()),
                "@Mod class " + simple,
                "its constructor is NeoForge code convert did not write; move its work into " +
                method +
                "() of a Fabric " +
                api +
                " by hand (the NeoForge bridge is written for Minecraft " +
                Route.FIRST_UNOBFUSCATED +
                " and later)"
            );
            return null;
        }
        if (
            ctor
                .group(1)
                .replaceAll(
                    "(?s)this\\(\\);|modBus\\.addListener\\(.*?RegisterEvent\\.class, event -> \\{.*?}\\);|modBus\\.addListener\\(EventPriority\\.HIGHEST, RegisterKeyMappingsEvent\\.class, event -> \\w+\\(\\)\\);",
                    ""
                )
                .strip()
                .length() >
            0
        ) {
            convert.projectFiles.leave(
                file,
                LoaderRules.line(text, ctor.start()),
                "@Mod constructor of " + simple,
                "it does more than run " + method + "(); move that work by hand"
            );
            return null;
        }
        String rest = text.substring(ctor.end());
        // A constructor convert put right after the class's opening brace leaves no blank line behind it.
        if (ctor.start() > 0 && text.charAt(ctor.start() - 1) == '{' && rest.startsWith("\n")) rest = rest.substring(1);
        String out = text.substring(0, ctor.start()) + "\n" + rest;
        out = out.replace("\n\n\n", "\n\n");
        int lineStart = out.lastIndexOf('\n', out.indexOf(annotation.group())) + 1;
        int lineEnd = out.indexOf('\n', out.indexOf(annotation.group())) + 1;
        out = out.substring(0, lineStart) + out.substring(lineEnd);
        out = Convert.implementFabric(out, simple, api, method);
        for (String name : List.of(
            "net.neoforged.fml.common.Mod",
            "net.neoforged.bus.api.IEventBus",
            "net.neoforged.neoforge.registries.RegisterEvent",
            "net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent",
            "net.minecraft.core.registries.Registries",
            "net.neoforged.api.distmarker.Dist",
            "net.neoforged.fml.ModContainer",
            "net.neoforged.bus.api.EventPriority"
        )) out = LoaderRules.dropImport(out, name);
        return out;
    }

    String fabricJson(
        Map<String, Object> toml,
        Map<String, Object> mod,
        Map<String, List<String>> entrypoints,
        String minecraft,
        boolean menu
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", 1);
        out.put("id", convert.fabricId);
        out.put("version", Json.text(mod, "version", "${version}"));
        out.put("name", Json.text(mod, "displayName", convert.modid));
        out.put("description", Json.text(mod, "description", ""));
        String authors = Json.text(mod, "authors", "");
        if (!authors.isEmpty()) out.put("authors", Arrays.stream(authors.split("\\s*,\\s*")).toList());
        Map<String, Object> contact = new LinkedHashMap<>();
        if (mod.get("displayURL") != null) contact.put("homepage", mod.get("displayURL"));
        if (toml.get("issueTrackerURL") != null) contact.put("issues", toml.get("issueTrackerURL"));
        if (!contact.isEmpty()) out.put("contact", contact);
        out.put("license", Json.text(toml, "license", "All Rights Reserved"));
        for (String key : List.of("iconFile", "logoFile")) if (
            mod.get(key) != null && !out.containsKey("icon")
        ) out.put("icon", mod.get(key));
        boolean client = entrypoints.containsKey("client"), main = entrypoints.containsKey("main"), server =
            entrypoints.containsKey("server");
        out.put("environment", main || (client && server) ? "*" : client ? "client" : server ? "server" : "*");
        // Entrypoints and custom values the way to NeoForge kept in modproperties (fabric.entrypoints.KEY, fabric.custom).
        Map<String, Object> custom = null;
        Map<String, Object> bridgedBack = new LinkedHashMap<>();
        if (toml.get("[modproperties." + convert.modid + "]") instanceof Map<?, ?> props) for (var e : Json.object(props).entrySet()) {
            String key = e.getKey().replaceAll("^\"|\"$", "");
            String value = String.valueOf(e.getValue());
            if (key.startsWith("fabric.entrypoints.") && value.startsWith("[")) {
                List<String> names = new ArrayList<>();
                for (Matcher q = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(value); q.find(); ) names.add(q.group(1));
                entrypoints.computeIfAbsent(key.substring("fabric.entrypoints.".length()), k -> new ArrayList<>()).addAll(names);
            } else if (key.equals("fabric.custom")) custom = Json.object(Json.parse(value));
            else if (key.startsWith("fabric.depends.")) bridgedBack.put(key.substring("fabric.depends.".length()), Json.parse(value));
        }
        out.put("entrypoints", entrypoints);
        List<Object> mixins = new ArrayList<>();
        for (Object m : toml.get("[[mixins]]") instanceof List<?> l ? Json.array(l) : List.of()) {
            String config = Json.text(Json.object(m), "config", "");
            // The Fabric bridge's own mixins went with the bridge (unbridgeFabric).
            if (!config.endsWith(".fabricbridge.mixins.json")) mixins.add(config);
        }
        if (convert.bridges.bridgeMixinConfig != null) mixins.add(convert.bridges.bridgeMixinConfig);
        if (!mixins.isEmpty()) out.put("mixins", mixins);
        Map<String, Object> depends = new LinkedHashMap<>(), suggests = new LinkedHashMap<>(), breaks =
            new LinkedHashMap<>(), conflicts = new LinkedHashMap<>();
        depends.put("fabricloader", ">=" + convert.fabricLoader);
        depends.put("minecraft", "~" + minecraft);
        depends.put("java", ">=" + javaVersion());
        depends.put("fabric-api", "*");
        depends.putAll(bridgedBack);
        for (Object d : toml.get("[[dependencies." + convert.modid + "]]") instanceof List<?> l ? Json.array(l) : List.of()) {
            Map<String, Object> dep = Json.object(d);
            String id = Json.text(dep, "modId", ""), type = Json.text(
                dep,
                "type",
                Json.text(dep, "mandatory", "true").equals("true") ? "required" : "optional"
            );
            if (id.equals("neoforge") || id.equals("forge")) continue;
            String range = fabricPredicate(Json.text(dep, "versionRange", "*"));
            if (id.equals("minecraft")) {
                depends.put("minecraft", range);
                continue;
            }
            switch (type) {
                case "required" -> depends.put(id, range);
                case "incompatible" -> breaks.put(id, range);
                case "discouraged" -> conflicts.put(id, range);
                default -> suggests.put(id, range);
            }
            convert.notes.add("Dependency " + id + " (" + type + ") is kept by id; it needs a Fabric build of that mod.");
        }
        if (menu) suggests.put("modmenu", "*");
        out.put("depends", depends);
        if (!suggests.isEmpty()) out.put("suggests", suggests);
        if (!breaks.isEmpty()) out.put("breaks", breaks);
        if (!conflicts.isEmpty()) out.put("conflicts", conflicts);
        if (convert.fabricWidener != null) out.put("accessWidener", convert.fabricWidener);
        if (!convert.bridges.bridgeInjected.isEmpty()) {
            // The bridge's interfaces on game classes (Loom interface injection), which its mixins add at runtime.
            custom = custom == null ? new LinkedHashMap<>() : new LinkedHashMap<>(custom);
            Map<String, Object> injected = new LinkedHashMap<>();
            if (custom.get("loom:injected_interfaces") instanceof Map<?, ?> existing) injected.putAll(Json.object(existing));
            for (var e : convert.bridges.bridgeInjected.entrySet()) {
                List<Object> names = new ArrayList<>(injected.get(e.getKey()) instanceof List<?> l ? Json.array(l) : List.of());
                for (String name : e.getValue()) if (!names.contains(name)) names.add(name);
                injected.put(e.getKey(), names);
            }
            custom.put("loom:injected_interfaces", injected);
        }
        if (custom != null) out.put("custom", custom);
        return Convert.pretty(out, "") + "\n";
    }

    String javaVersion() {
        Matcher m = Pattern.compile("JavaLanguageVersion\\.of\\((\\d+)\\)|release\\s*=\\s*(\\d+)").matcher(
            convert.projectFiles.readQuiet("build.gradle")
        );
        if (m.find()) return m.group(1) != null ? m.group(1) : m.group(2);
        return "21";
    }
}

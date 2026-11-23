import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Access wideners and access transformers turned into each other, with the game's field types they need. */
final class ConvertAccess {

    final Convert convert;

    ConvertAccess(Convert convert) {
        this.convert = convert;
    }

    /** Access transformer lines the rewritten code needs on NeoForge. */
    final Set<String> accessTransformers = new LinkedHashSet<>();
    static final String AT_FILE = "src/main/resources/META-INF/accesstransformer.cfg";

    /**
     * A Fabric access widener (or class tweaker) as NeoForge access transformer lines, by the two loaders' own formats:
     * accessible is public; extendable is public and not final for a class, protected and not final for a method;
     * mutable is not final. Entries for one target merge. inject-interface has no transformer counterpart and goes to
     * unconverted, as does every entry when the file is not in Mojang's names.
     */
    static List<String> widenerToTransformer(String text, boolean mojangNames, List<String> unconverted) {
        Map<String, Set<String>> targets = new LinkedHashMap<>();
        boolean header = false;
        for (String raw : text.lines().toList()) {
            String line = raw.replaceAll("#.*$", "").strip();
            if (line.isEmpty()) continue;
            String[] t = line.split("\\s+");
            if (!header) {
                header = true;
                if (t[0].equals("accessWidener") || t[0].equals("classTweaker")) continue;
            }
            if (!mojangNames) {
                unconverted.add(line + " (not in Mojang's names)");
                continue;
            }
            String access = t[0].replaceFirst("^transitive-", "");
            if (t.length < 3 || !Set.of("accessible", "extendable", "mutable").contains(access)) {
                unconverted.add(line);
                continue;
            }
            String kind = t[1], owner = t[2].replace('/', '.');
            String key = switch (kind) {
                case "class" -> owner;
                case "method" -> t.length >= 5 ? owner + " " + t[3] + t[4] : null;
                case "field" -> t.length >= 4 ? owner + " " + t[3] : null;
                default -> null;
            };
            if (key == null) {
                unconverted.add(line);
                continue;
            }
            targets.computeIfAbsent(kind + "|" + key, k -> new LinkedHashSet<>()).add(access);
        }
        List<String> out = new ArrayList<>();
        for (var entry : targets.entrySet()) {
            String kind = entry.getKey().substring(0, entry.getKey().indexOf('|'));
            String target = entry.getKey().substring(kind.length() + 1);
            Set<String> a = entry.getValue();
            String modifier = switch (kind) {
                case "class" -> a.contains("extendable") ? "public-f" : "public";
                case "method" -> (a.contains("accessible") ? "public" : "protected") + (a.contains("extendable") ? "-f" : "");
                default -> "public" + (a.contains("mutable") ? "-f" : "");
            };
            out.add(modifier + " " + target);
        }
        return out;
    }

    /**
     * Fabric makes the owner of an accessible member accessible too (a method of a private nested class can then be
     * called); a transformer line opens only what it names, so each public member line's owner gets its own class line.
     * An anonymous class (Owner$1) has no declaration a transformer can open, so it gets none.
     */
    static List<String> withOwners(List<String> lines) {
        List<String> out = new ArrayList<>(lines);
        Set<String> classes = new HashSet<>();
        for (String line : lines) {
            String[] t = line.split("\\s+");
            if (t.length == 2) classes.add(t[1]);
        }
        for (String line : lines) {
            String[] t = line.split("\\s+");
            if (t.length < 3 || !t[0].startsWith("public")) continue;
            String owner = t[1];
            if (owner.substring(owner.lastIndexOf('$') + 1).matches("\\d+") || !classes.add(owner)) continue;
            out.add("public " + owner);
        }
        return out;
    }

    /**
     * NeoForge access transformer lines as a Fabric access widener (v2), the reverse of widenerToTransformer. A field
     * line names no field type, which an access widener needs: fieldTypes gives it from the game (owner/name to
     * descriptor), and a field it does not know goes to unconverted with wildcards and +f. Asked for a method (owner,
     * name+descriptor), it gives "access N", the method's access flags, when it knows: a private method is also extendable.
     * Asked for "*", it lists the class's members ("field NAME DESC", "method NAME DESC ACCESS", one per line) for a
     * wildcard line (public X * or public X *()); "" names a class the game does not have.
     */
    static String transformerToWidener(String text, boolean official, List<String> unconverted) {
        return transformerToWidener(text, official, unconverted, (owner, field) -> null);
    }

    static String transformerToWidener(
        String text,
        boolean official,
        List<String> unconverted,
        java.util.function.BiFunction<String, String, String> fieldTypes
    ) {
        StringBuilder out = new StringBuilder("accessWidener v2 " + (official ? "official" : "named") + "\n");
        for (String raw : text.lines().toList()) {
            String line = raw.replaceAll("#.*$", "").strip();
            if (line.isEmpty()) continue;
            String[] t = line.split("\\s+");
            Matcher m = Pattern.compile("^(public|protected|default|private)(-f|\\+f)?$").matcher(t[0]);
            boolean wildcard = t.length == 3 && (t[2].equals("*") || t[2].equals("*()"));
            if (!m.find() || "+f".equals(m.group(2)) || t.length < 2 || line.contains("*") && !wildcard) {
                unconverted.add(line);
                continue;
            }
            boolean definal = "-f".equals(m.group(2));
            String owner = t[1].replace('.', '/');
            if (wildcard) {
                // Every field (*) or method (*()) of the class: an access widener names each, so the game lists them.
                String listing = fieldTypes.apply(owner, "*");
                if (listing == null) {
                    unconverted.add(line);
                    continue;
                }
                if (listing.isEmpty()) {
                    unconverted.add(line + " (the game has no such class, so the line opens nothing on either loader; dropped)");
                    continue;
                }
                boolean methods = t[2].equals("*()");
                for (String member : listing.lines().toList()) {
                    String[] p = member.split(" ");
                    if (methods != p[0].equals("method") || p[1].equals("<clinit>")) continue;
                    String kind = methods ? "method " : "field ";
                    out.append("accessible ").append(kind).append(owner).append(' ').append(p[1]).append(' ').append(p[2]).append('\n');
                    boolean wasPrivate = methods && p.length > 3 && (Integer.parseInt(p[3]) & 0x0002) != 0;
                    if (definal || wasPrivate) out.append(methods ? "extendable " : "mutable ").append(kind).append(owner).append(' ').append(p[1]).append(' ').append(p[2]).append('\n');
                }
                continue;
            }
            if (t.length == 2) {
                out.append(definal ? "extendable" : "accessible").append(" class ").append(owner).append('\n');
                continue;
            }
            int paren = t[2].indexOf('(');
            if (paren < 0) {
                String type = fieldTypes.apply(owner, t[2]);
                if (type != null && type.isEmpty()) {
                    // A line for a field the game no longer has opens nothing on NeoForge either.
                    unconverted.add(line + " (the game has no such field, so the line opens nothing on either loader; dropped)");
                    continue;
                }
                if (type == null) {
                    unconverted.add(line + " (an access widener names the field's type, which the transformer line does not)");
                    continue;
                }
                out.append("accessible field ").append(owner).append(' ').append(t[2]).append(' ').append(type).append('\n');
                if (definal) out.append("mutable field ").append(owner).append(' ').append(t[2]).append(' ').append(type).append('\n');
                continue;
            }
            String name = t[2].substring(0, paren), desc = t[2].substring(paren);
            out.append("accessible method ").append(owner).append(' ').append(name).append(' ').append(desc).append('\n');
            // A widener makes a private method it opens final; a transformer leaves it overridable, and mods override it.
            String access = fieldTypes.apply(owner, name + desc);
            boolean wasPrivate = access != null && access.startsWith("access ") && (Integer.parseInt(access.substring(7)) & 0x0002) != 0;
            if (definal || wasPrivate) out.append("extendable method ").append(owner).append(' ').append(name).append(' ').append(desc).append('\n');
        }
        return out.toString();
    }

    /**
     * The game's field types (owner in slash form, field name, to its descriptor), in Mojang's names, read only when a
     * field line asks: before 26.1 from Mojang's client mappings, which give every field's type; from 26.1 from the class
     * files of Mojang's client jar, which is no longer obfuscated. Null for a field the game does not have, or offline.
     */
    java.util.function.BiFunction<String, String, String> gameFieldTypes(String minecraft) {
        Map<String, String> known = new HashMap<>();
        Map<String, String> listings = new HashMap<>();
        Map<String, java.util.zip.ZipFile> jar = new HashMap<>();
        boolean[] loaded = { false };
        return (owner, field) -> {
            try {
                if (!loaded[0]) {
                    loaded[0] = true;
                    var version = Json.object(Json.parse(Target.fetch(convert.build, gameVersionUrl(minecraft))));
                    var downloads = Json.object(version.get("downloads"));
                    if (downloads.get("client_mappings") instanceof Map<?, ?> mappings) {
                        String text = Target.fetch(convert.build, Json.text(Json.object(mappings), "url", ""));
                        known.putAll(mappingFieldTypes(text));
                        listings.putAll(mappingMembers(text));
                    }
                    else {
                        var client = Json.object(downloads.get("client"));
                        Path file = Path.of(System.getProperty("user.home"), ".mmt", "minecraft", minecraft, "client.jar");
                        if (!Files.isRegularFile(file)) {
                            Files.createDirectories(file.getParent());
                            PackCheck.download(Json.text(client, "url", ""), file, Json.text(client, "sha1", ""));
                        }
                        jar.put("client", new java.util.zip.ZipFile(file.toFile()));
                    }
                }
                String key = owner + " " + field;
                if (known.containsKey(key)) return known.get(key);
                var zip = jar.get("client");
                // A class the game does not have: the line opens nothing on NeoForge either.
                if (zip == null) return listings.isEmpty() ? null : field.equals("*") ? listings.getOrDefault(owner, "") : field.contains("(") ? null : "";
                if (field.equals("*") && listings.containsKey(owner)) return listings.get(owner);
                var entry = zip.getEntry(owner + ".class");
                if (entry == null) return "";
                StringBuilder listing = new StringBuilder();
                try (var in = zip.getInputStream(entry)) {
                    for (var member : Explain.Index.parse(in.readAllBytes()).members()) {
                        if (member.method()) known.put(owner + " " + member.name() + member.descriptor(), "access " + member.access());
                        else known.put(owner + " " + member.name(), member.descriptor());
                        listing.append(member.method() ? "method " : "field ").append(member.name()).append(' ').append(member.descriptor());
                        listing.append(member.method() ? " " + member.access() : "").append('\n');
                    }
                }
                listings.put(owner, listing.toString());
                if (field.equals("*")) return listing.toString();
                // The class is read and has no such member: "" says the game lacks it (null is "not known").
                return known.getOrDefault(key, "");
            } catch (Exception offline) {
                return null;
            }
        };
    }

    String gameVersionUrl(String minecraft) throws Exception {
        for (Object v : Json.array(Json.object(Json.parse(Target.fetch(convert.build, "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))).get("versions"))) {
            if (minecraft.equals(Json.object(v).get("id"))) return Json.text(Json.object(v), "url", "");
        }
        // Gradle builds may write 26.2.0 for Mojang's 26.2.
        if (minecraft.endsWith(".0")) return gameVersionUrl(minecraft.substring(0, minecraft.length() - 2));
        throw new IOException("Mojang lists no Minecraft " + minecraft);
    }

    /** Every field of Mojang's ProGuard mappings: "owner/Class name" to its descriptor. */
    static Map<String, String> mappingFieldTypes(String mappings) {
        Map<String, String> out = new HashMap<>();
        String owner = null;
        for (String line : mappings.split("\n")) {
            if (line.startsWith("#") || line.isBlank()) continue;
            if (!line.startsWith(" ")) {
                owner = line.substring(0, line.indexOf(" -> ")).replace('.', '/');
                continue;
            }
            Matcher field = Pattern.compile("^\\s+([\\w.$\\[\\]]+) ([\\w$]+) -> [\\w$]+\\s*$").matcher(line);
            if (owner != null && field.find()) out.put(owner + " " + field.group(2), descriptor(field.group(1)));
        }
        return out;
    }

    /**
     * Every class of Mojang's ProGuard mappings with its fields and methods in Mojang's names, as gameFieldTypes lists
     * them for "*" ("field NAME DESC", "method NAME DESC"; the mappings give no access flags).
     */
    static Map<String, String> mappingMembers(String mappings) {
        Map<String, StringBuilder> out = new HashMap<>();
        StringBuilder owner = null;
        Pattern field = Pattern.compile("^\\s+([\\w.$\\[\\]]+) ([\\w$]+) -> [\\w$]+\\s*$");
        Pattern method = Pattern.compile("^\\s+(?:\\d+:\\d+:)?([\\w.$\\[\\]]+) ([\\w$<>]+)\\(([^)]*)\\)(?::\\d+:\\d+)? -> [\\w$<>]+\\s*$");
        for (String line : mappings.split("\n")) {
            if (line.startsWith("#") || line.isBlank()) continue;
            if (!line.startsWith(" ")) {
                owner = out.computeIfAbsent(line.substring(0, line.indexOf(" -> ")).replace('.', '/'), k -> new StringBuilder());
                continue;
            }
            if (owner == null) continue;
            Matcher f = field.matcher(line);
            if (f.find()) {
                owner.append("field ").append(f.group(2)).append(' ').append(descriptor(f.group(1))).append('\n');
                continue;
            }
            Matcher m = method.matcher(line);
            if (!m.find()) continue;
            StringBuilder desc = new StringBuilder("(");
            for (String arg : m.group(3).split(",")) if (!arg.isBlank()) desc.append(descriptor(arg.strip()));
            owner.append("method ").append(m.group(2)).append(' ').append(desc).append(')').append(descriptor(m.group(1))).append('\n');
        }
        Map<String, String> listed = new HashMap<>();
        for (var e : out.entrySet()) listed.put(e.getKey(), e.getValue().toString());
        return listed;
    }

    /** A Java type as written in Mojang's mappings (int, java.util.List, a.B[]) as a JVM descriptor. */
    static String descriptor(String type) {
        if (type.endsWith("[]")) return "[" + descriptor(type.substring(0, type.length() - 2));
        return switch (type) {
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            case "void" -> "V";
            default -> "L" + type.replace('.', '/') + ";";
        };
    }

    /** The access wideners fabric.mod.json names, under src/main/resources. */
    List<String> accessWideners() throws IOException {
        List<String> out = new ArrayList<>();
        String meta = convert.projectFiles.read("src/main/resources/fabric.mod.json");
        if (meta.isEmpty()) return out;
        if (Json.object(Json.parse(meta)).get("accessWidener") instanceof String widener && convert.projectFiles.exists("src/main/resources/" + widener)) out.add("src/main/resources/" + widener);
        return out;
    }

    /**
     * An access widener in yarn's names written in Mojang's (classes, members and descriptors), from the yarn -> Mojang
     * table (classes, methods and fields by owner, name and descriptor). A name the table leaves out is the same in both.
     */
    static String widenerToMojang(String text, Map<String, Object> table) {
        Map<String, String> classes = new HashMap<>(), members = new HashMap<>();
        for (Object o : Json.array(table.get("classes"))) {
            var c = Json.object(o);
            classes.put(Json.text(c, "from", "").replace('.', '/'), Json.text(c, "to", "").replace('.', '/'));
        }
        for (String kind : List.of("methods", "fields")) for (Object o : Json.array(table.get(kind))) {
            var m = Json.object(o);
            if (MixinNames.synthetic(Json.text(m, "to", ""))) continue;
            members.put(Json.text(m, "owner", "").replace('.', '/') + " " + Json.text(m, "from", "") + " " + Json.text(m, "desc", ""), Json.text(m, "to", ""));
        }
        java.util.function.UnaryOperator<String> type = name -> classes.getOrDefault(name, name);
        java.util.function.UnaryOperator<String> desc = d -> Pattern.compile("L([^;]+);").matcher(d).replaceAll(r -> Matcher.quoteReplacement("L" + type.apply(r.group(1)) + ";"));
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String body = line.replaceAll("#.*$", "").strip();
            String[] t = body.split("\\s+");
            if (body.isEmpty() || t[0].equals("accessWidener") || t.length < 3) {
                out.append(line).append('\n');
                continue;
            }
            if (t[1].equals("class")) {
                out.append(t[0]).append(" class ").append(type.apply(t[2])).append('\n');
                continue;
            }
            if (t.length < 5) {
                out.append(line).append('\n');
                continue;
            }
            // The table names only what yarn and Mojang name differently: a member it leaves out keeps its name.
            String name = members.getOrDefault(t[2] + " " + t[3] + " " + t[4], t[3]);
            out.append(t[0]).append(' ').append(t[1]).append(' ').append(type.apply(t[2])).append(' ').append(name).append(' ').append(desc.apply(t[4])).append('\n');
        }
        // split keeps a last empty piece, so one line end too many was written.
        return out.substring(0, out.length() - 1);
    }

    /**
     * Fabric API widens game members for every mod (its fabric-transitive-access-wideners-v1 module), so Fabric code
     * calls them as if public. On NeoForge the mod's access transformer opens the ones its sources name: an entry is kept
     * when the code names both its member and its owner's simple name. Read from the Fabric API release the build names,
     * from Minecraft 26.1 (Fabric API's wideners are in Mojang's names there).
     */
    void fabricApiWideners(String minecraft) {
        // A conversion that is not compiled (self-test, --no-compile) makes no lookups for it.
        if (!convert.compiling || Versions.compare(minecraft, Route.FIRST_UNOBFUSCATED) < 0) return;
        Map<String, String> props = BuildScript.properties(convert.projectFiles.readQuiet("gradle.properties"));
        String api = props.getOrDefault("fabric_api_version", props.getOrDefault("fabric_version", ""));
        if (api.isEmpty() || api.contains("$")) return;
        try {
            String base = "https://maven.fabricmc.net/net/fabricmc/fabric-api/";
            String pom = Target.fetch(convert.build, base + "fabric-api/" + api + "/fabric-api-" + api + ".pom");
            Matcher module = Pattern.compile("<artifactId>fabric-transitive-access-wideners-v1</artifactId>\\s*<version>([^<]+)</version>").matcher(pom);
            if (!module.find()) return;
            String v = module.group(1);
            Path jar = Target.download(convert.build, base + "fabric-transitive-access-wideners-v1/" + v + "/fabric-transitive-access-wideners-v1-" + v + ".jar");
            String widener = null;
            try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
                for (var e : java.util.Collections.list(zip.entries())) if (e.getName().matches("[^/]+\\.(classtweaker|accesswidener)")) try (var in = zip.getInputStream(e)) {
                    widener = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            if (widener == null) return;
            Set<String> names = new HashSet<>();
            for (String file : convert.projectFiles.javaFiles()) for (Matcher w = Pattern.compile("\\b[A-Za-z_]\\w*\\b").matcher(convert.projectFiles.read(file)); w.find(); ) names.add(w.group());
            String kept = transitiveEntries(widener, names);
            List<String> lines = withOwners(widenerToTransformer(kept, true, new ArrayList<>()));
            int added = 0;
            for (String line : lines) if (!accessTransformers.contains(line)) {
                accessTransformers.add(line);
                added++;
            }
            if (added > 0) {
                convert.notes.add("Access transformer: " + added + " line(s) for the game members Fabric API " + api + " widens for every mod (fabric-transitive-access-wideners-v1) that the code names.");
                convert.applied.computeIfAbsent("fabric-api-wideners", k -> new ArrayList<>()).add(AT_FILE);
            }
        } catch (Exception offline) {
            convert.notes.add("Fabric API's own access wideners could not be read (" + offline.getMessage() + "); members it widens are not opened on NeoForge.");
        }
    }

    /** The lines of an access widener whose member (or class) the code names: both the member and its owner's simple name. */
    static String transitiveEntries(String widener, Set<String> names) {
        StringBuilder out = new StringBuilder();
        for (String raw : widener.lines().toList()) {
            String line = raw.replaceAll("#.*$", "").strip();
            String[] t = line.split("\\s+");
            if (t.length >= 3 && (t[0].equals("accessWidener") || t[0].equals("classTweaker"))) {
                out.append(line).append('\n');
                continue;
            }
            if (t.length < 3) continue;
            String owner = t[2].substring(Math.max(t[2].lastIndexOf('/'), t[2].lastIndexOf('$')) + 1);
            boolean member = t[1].equals("class") || (t.length >= 4 && (names.contains(t[3]) || t[3].equals("<init>")));
            if (member && names.contains(owner)) out.append(line).append('\n');
        }
        return out.toString();
    }

    /**
     * NeoForge's own access transformer lines for this game (the NeoForge bridge folder's .cfg files, or its version
     * layer's), comments dropped: NeoForge applies them to the game for every mod.
     */
    List<String> neoforgeAccessTransformers(String minecraft) throws IOException {
        for (Object item : LoaderRules.list(convert.rules.get("bridges"))) {
            var b = Json.object(item);
            if (!"neoforge".equals(b.get("from")) || !"fabric".equals(b.get("to"))) continue;
            Map<String, Object> layer = ConvertBridges.bridgeLayer(b, minecraft);
            if (Versions.compare(minecraft, Json.text(b, "minecraft", "99")) < 0 && layer == null) continue;
            List<String> out = new ArrayList<>();
            for (Map<String, Object> from : layer == null ? List.of(b) : List.of(layer, b)) {
                Path folder = convert.build.harness.resolve(Json.text(from, "folder", ""));
                if (!Files.isDirectory(folder)) continue;
                try (var files = Files.list(folder)) {
                    for (Path cfg : files.filter(p -> p.getFileName().toString().endsWith(".cfg")).sorted().toList()) {
                        for (String line : Files.readString(cfg, StandardCharsets.UTF_8).lines().toList()) {
                            String stripped = line.replaceAll("#.*$", "").strip();
                            if (!stripped.isEmpty()) out.add(stripped);
                        }
                    }
                }
                // A version layer's own file stands in for the bridge's.
                if (!out.isEmpty()) return out;
            }
            return out;
        }
        return List.of();
    }

    /**
     * NeoForge's own access transformer lines for the members the converted code names (.member, ::member, or new
     * Owner( for a constructor): the code was written with those open, so its Fabric access widener opens them too.
     */
    List<String> usedNeoForgeAccess(String minecraft) throws IOException {
        List<String> lines = neoforgeAccessTransformers(minecraft);
        if (lines.isEmpty()) return List.of();
        StringBuilder all = new StringBuilder();
        // NeoForge's copied classes and the bridge's constructor factories use the game as the mod's code does.
        Set<String> factories = new HashSet<>();
        if (convert.bridges.bridge != null) for (Object f : Json.object(convert.bridges.bridge.getOrDefault("constructors", Map.of())).values()) factories.add(convert.neoForgeBridge.bridgeFolder() + "/" + f + ".java");
        for (String file : convert.projectFiles.javaFiles()) {
            boolean user = convert.neoForgeBridge.bridgePackage == null || !file.startsWith(convert.neoForgeBridge.bridgeFolder() + "/") || convert.neoForgeBridge.copiedFiles.contains(file) || factories.contains(file);
            if (user) all.append(convert.projectFiles.read(file)).append('\n');
        }
        String code = all.toString();
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            String[] t = line.split("\\s+");
            if (t.length == 2 && t[1].contains("$")) {
                // A nested class (Hud$HeartType) is named in code as Outer.Inner, or imported by its full name.
                String nested = t[1].substring(t[1].lastIndexOf('.') + 1).replace('$', '.');
                if (nested.matches(".*\\.\\d+.*")) continue;
                boolean named = Pattern.compile("(?<![\\w$])" + Pattern.quote(nested) + "(?![\\w$])").matcher(code).find();
                if (named || code.contains("import " + t[1].replace('$', '.') + ";")) out.add(line);
                continue;
            }
            if (t.length < 3) continue;
            int paren = t[2].indexOf('(');
            String member = paren < 0 ? t[2] : t[2].substring(0, paren);
            String owner = t[1].substring(Math.max(t[1].lastIndexOf('.'), t[1].lastIndexOf('$')) + 1);
            // An anonymous class (HolderSet$1) is never named in code: its number would match any 1 in the source.
            if (owner.matches("\\d+")) continue;
            boolean named = Pattern.compile("(?<![\\w$])" + Pattern.quote(owner) + "(?![\\w$])").matcher(code).find();
            boolean used;
            if (member.equals("<init>")) used = Pattern.compile("\\bnew\\s+(?:[\\w.]+\\.)?" + Pattern.quote(owner) + "\\s*[(<]").matcher(code).find();
            // A method is called on an object whose class the file need not name either (renderer.addLayer(...) on the
            // renderer an event gives): a long enough name is taken on its own, as a field's is.
            else if (paren >= 0) used = (named || member.length() >= 7) && Pattern.compile("(?:\\.|::)\\s*" + Pattern.quote(member) + "\\s*\\(?(?![\\w$])").matcher(code).find();
            else {
                // A field is read on an instance whose class the file need not name (zombie.goalSelector): a long enough
                // name is taken on its own, a short one (level) only where its class is named.
                // A subclass names an inherited field without a receiver (renderables.add in a Screen subclass).
                used = (named || member.length() >= 7) && Pattern.compile("(?:\\.\\s*|(?<![\\w$.]))" + Pattern.quote(member) + "(?![\\w$(])").matcher(code).find();
            }
            if (used) out.add(line);
        }
        // A class the used members' descriptors name, which NeoForge also opens (new BlockEntityType<>(Supplier::new, ...)
        // passes the private BlockEntityType$BlockEntitySupplier): the code needs it open to call them.
        String descriptors = String.join(" ", out);
        for (String line : lines) {
            String[] t = line.split("\\s+");
            if (t.length == 2 && !out.contains(line) && descriptors.contains("L" + t[1].replace('.', '/') + ";")) out.add(line);
        }
        return out;
    }
}

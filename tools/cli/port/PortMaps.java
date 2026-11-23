import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** The name maps a source port follows from the old release to the new one: composed, saved, and read back. */
final class PortMaps {

    final Port port;

    PortMaps(Port port) {
        this.port = port;
    }

    /**
     * One map from a yarn-named source to the unobfuscated game: names, the yarn -> Mojang table of the source release
     * (VersionMap, through Fabric's intermediary), joined with the game's own Mojang-named route map (GameDiff) from the
     * source release to the target. Every key stays in the source's yarn names and every value is the target's name, in
     * the usual map shape, so the typed fixer reads it as it reads any map. The route's history is kept for explaining
     * what is left.
     */
    static Map<String, Object> composeMaps(Map<String, Object> names, Map<String, Object> route) {
        Map<String, String> toMojang = new HashMap<>(), fromMojang = new HashMap<>(), moved = new HashMap<>();
        for (Object o : Json.array(names.get("classes"))) {
            var c = Json.object(o);
            toMojang.put(Json.text(c, "from", ""), Json.text(c, "to", ""));
            fromMojang.put(Json.text(c, "to", ""), Json.text(c, "from", ""));
        }
        for (Object o : Json.array(route.get("classes"))) {
            var c = Json.object(o);
            moved.put(Json.text(c, "from", ""), Json.text(c, "to", ""));
        }
        Set<String> classesGone = new HashSet<>(ProjectModel.strings(route.get("classesRemoved")));
        java.util.function.UnaryOperator<String> mojang = y -> toMojang.getOrDefault(y, y);
        java.util.function.UnaryOperator<String> forward = m -> moved.getOrDefault(m, m);
        java.util.function.UnaryOperator<String> yarn = m -> fromMojang.getOrDefault(m, m);
        List<Object> classes = new ArrayList<>(), classesRemoved = new ArrayList<>();
        Set<String> keyed = new HashSet<>();
        for (var e : toMojang.entrySet()) {
            keyed.add(e.getKey());
            if (classesGone.contains(e.getValue())) classesRemoved.add(e.getKey());
            else classes.add(Json.map("from", e.getKey(), "to", forward.apply(e.getValue())));
        }
        // Classes whose yarn and Mojang names agree are keyed by that name.
        for (var e : moved.entrySet()) if (!fromMojang.containsKey(e.getKey()) && keyed.add(e.getKey())) classes.add(
            Json.map("from", e.getKey(), "to", e.getValue())
        );
        for (String gone : classesGone) if (!fromMojang.containsKey(gone) && keyed.add(gone)) classesRemoved.add(gone);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", names.get("from"));
        out.put("to", route.get("to"));
        out.put("names", "yarn");
        out.put("linked", "yarn-bridge");
        out.put(
            "source",
            "yarn -> Mojang names of " + names.get("from") + " (Fabric's yarn and intermediary with Mojang's mappings), joined with " + route.get("source")
        );
        out.put("chain", route.get("chain"));
        out.put("classes", classes);
        out.put("classesRemoved", classesRemoved);
        for (String kind : List.of("methods", "fields")) {
            // Every route change of this kind, by Mojang owner, name and descriptor, with the list it came from.
            Map<String, Map<String, Object>> changed = new HashMap<>();
            Map<String, String> listOf = new HashMap<>();
            List<String> lists = kind.equals("methods") ? List.of("methods", "methodsMoved", "signatures") : List.of("fields");
            for (String list : lists) for (Object o : Json.array(route.get(list))) {
                var m = Json.object(o);
                String key = Json.text(m, "owner", "") + "." + Json.text(m, "from", "") + Json.text(m, "desc", "");
                changed.put(key, m);
                listOf.put(key, list);
            }
            Set<String> gone = new HashSet<>();
            for (Object o : Json.array(route.get(kind + "Removed"))) {
                var m = Json.object(o);
                gone.add(Json.text(m, "owner", "") + "." + Json.text(m, "from", "") + Json.text(m, "desc", ""));
            }
            Map<String, List<Object>> result = new LinkedHashMap<>();
            for (String list : lists) result.put(list, new ArrayList<>());
            List<Object> removed = new ArrayList<>();
            Set<String> reached = new HashSet<>();
            for (Object o : Json.array(names.get(kind))) {
                var m = Json.object(o);
                String owner = Json.text(m, "owner", ""), mojangOwner = Json.text(m, "newOwner", mojang.apply(owner));
                String key = mojangOwner + "." + Json.text(m, "to", "") + Json.text(m, "newDesc", "");
                reached.add(key);
                if (gone.contains(key)) {
                    removed.add(Json.map("owner", owner, "from", m.get("from"), "desc", m.get("desc")));
                    continue;
                }
                var next = changed.get(key);
                String list = next == null ? lists.get(0) : listOf.get(key);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("owner", owner);
                entry.put("from", m.get("from"));
                entry.put("desc", m.get("desc"));
                entry.put("to", next == null ? m.get("to") : next.get("to"));
                entry.put("newDesc", next == null ? descriptor(Json.text(m, "newDesc", ""), forward) : next.get("newDesc"));
                String finalOwner = next != null && next.get("newOwner") != null ? Json.text(next, "newOwner", "") : forward.apply(mojangOwner);
                if (!finalOwner.equals(owner)) entry.put("newOwner", finalOwner);
                result.get(list).add(entry);
            }
            // Members whose yarn and Mojang names agree are keyed by the yarn owner and descriptor.
            for (var e : changed.entrySet()) {
                if (reached.contains(e.getKey())) continue;
                var m = e.getValue();
                Map<String, Object> entry = new LinkedHashMap<>(m);
                entry.put("owner", yarn.apply(Json.text(m, "owner", "")));
                entry.put("desc", descriptor(Json.text(m, "desc", ""), yarn));
                result.get(listOf.get(e.getKey())).add(entry);
            }
            for (Object o : Json.array(route.get(kind + "Removed"))) {
                var m = Json.object(o);
                String key = Json.text(m, "owner", "") + "." + Json.text(m, "from", "") + Json.text(m, "desc", "");
                if (!reached.contains(key)) removed.add(
                    Json.map("owner", yarn.apply(Json.text(m, "owner", "")), "from", m.get("from"), "desc", descriptor(Json.text(m, "desc", ""), yarn))
                );
            }
            out.putAll(result);
            out.put(kind + "Removed", removed);
        }
        out.put("history", route.getOrDefault("history", List.of()));
        // The game's call-site rewrites, as they are (for code the map has already moved to Mojang's names) and, where the
        // removed member is one of the source release's, keyed by its yarn name too (what javac reports before then).
        Map<String, Map<String, Object>> yarnOf = new HashMap<>();
        for (Object o : Json.array(names.get("methods"))) {
            var m = Json.object(o);
            String owner = Json.text(m, "owner", ""), mojangOwner = Json.text(m, "newOwner", mojang.apply(owner));
            yarnOf.put(mojangOwner + "." + Json.text(m, "to", "") + Json.text(m, "newDesc", ""), m);
        }
        List<Object> rewrites = new ArrayList<>();
        for (Object o : Json.array(route.get("rewrites"))) {
            var r = Json.object(o);
            rewrites.add(r);
            var y = yarnOf.get(Json.text(r, "owner", "") + "." + Json.text(r, "from", "") + Json.text(r, "desc", ""));
            if (y == null) continue;
            Map<String, Object> yarnKeyed = new LinkedHashMap<>(r);
            yarnKeyed.put("owner", y.get("owner"));
            yarnKeyed.put("from", y.get("from"));
            yarnKeyed.put("desc", y.get("desc"));
            rewrites.add(yarnKeyed);
        }
        out.put("rewrites", rewrites);
        List<Object> classRewrites = new ArrayList<>();
        for (Object o : Json.array(route.getOrDefault("classRewrites", List.of()))) {
            var r = Json.object(o);
            classRewrites.add(r);
            String yarnName = fromMojang.get(Json.text(r, "from", ""));
            if (yarnName == null) continue;
            Map<String, Object> yarnKeyed = new LinkedHashMap<>(r);
            yarnKeyed.put("from", yarnName);
            classRewrites.add(yarnKeyed);
        }
        out.put("classRewrites", classRewrites);
        return out;
    }

    /** The game's class rewrites (Mojang names) in yarn: the removed class as the source names it, its replacement as the target. */
    static List<Object> yarnClassRewrites(List<Object> game, Map<String, Object> source, Map<String, Object> target) {
        Map<String, String> sourceYarn = new HashMap<>(), targetYarn = new HashMap<>();
        for (Object o : Json.array(source.get("classes"))) sourceYarn.put(Json.text(Json.object(o), "to", ""), Json.text(Json.object(o), "from", ""));
        for (Object o : Json.array(target.get("classes"))) targetYarn.put(Json.text(Json.object(o), "to", ""), Json.text(Json.object(o), "from", ""));
        List<Object> out = new ArrayList<>();
        for (Object o : game) {
            var r = Json.object(o);
            String from = sourceYarn.get(Json.text(r, "from", "")), to = targetYarn.get(Json.text(r, "to", ""));
            if (from == null || to == null) continue;
            Map<String, Object> yarn = new LinkedHashMap<>(r);
            yarn.put("from", from);
            yarn.put("to", to);
            out.add(yarn);
        }
        return out;
    }

    /**
     * A yarn route (Fabric's tables) knows names, not what the game's own code does at a call site: the game's call-site
     * rewrites come from GameDiff over the same releases, in Mojang's names, and are added to the route map in yarn names,
     * the removed member by the source release's table and its replacement by the target's. A rewrite either side cannot
     * name in yarn is left out; without GameDiff or a table, the route map stays as it was.
     */
    static void yarnRewrites(JavaTools tools, List<String> chain, String from, String to, Path map) {
        try {
            Path game = map.resolveSibling(map.getFileName().toString().replace(".json", ".game.json"));
            var diff = tools.run("GameDiff", List.of("--chain", String.join(",", chain), "--out", game.toString()), "mapping-rewrites");
            if (diff.exit() != 0) return;
            Map<String, Map<String, Object>> tables = new HashMap<>();
            for (String version : new LinkedHashSet<>(List.of(from, to))) {
                Path table = map.resolveSibling("names-yarn-" + version + ".json");
                if (!Files.isRegularFile(table)) {
                    var made = tools.run(
                        "VersionMap",
                        List.of(version, version, "--from-names", "yarn", "--to-names", "mojmap", "--names", "yarn", "--out", table.toString()),
                        "mapping-yarn-" + version
                    );
                    if (made.exit() != 0) return;
                }
                tables.put(version, Json.read(table));
            }
            var gameMap = Json.read(game);
            Map<String, Object> route = new LinkedHashMap<>(Json.read(map));
            // What Fabric's tables call removed but the game's own map links (1.20.2: PacketByteBuf.getVarIntLength, Mojang's
            // FriendlyByteBuf.getVarIntSize, moved to VarInt.getByteSize): those links are rewrites too, static-ness read
            // from the call.
            Set<String> removed = new HashSet<>();
            for (Object o : Json.array(route.getOrDefault("methodsRemoved", List.of()))) {
                var m = Json.object(o);
                removed.add(Json.text(m, "owner", "") + "." + Json.text(m, "from", "") + Json.text(m, "desc", ""));
            }
            List<Object> linked = new ArrayList<>();
            for (String list : List.of("methods", "methodsMoved", "signatures")) for (Object o : Json.array(gameMap.getOrDefault(list, List.of()))) {
                var m = Json.object(o);
                Map<String, Object> asRewrite = new LinkedHashMap<>();
                asRewrite.put("owner", m.get("owner"));
                asRewrite.put("from", m.get("from"));
                asRewrite.put("desc", m.get("desc"));
                asRewrite.put("toOwner", m.getOrDefault("newOwner", m.get("owner")));
                asRewrite.put("to", m.get("to"));
                asRewrite.put("newDesc", m.getOrDefault("newDesc", m.get("desc")));
                linked.add(asRewrite);
            }
            List<Object> rewrites = yarnRewrites(Json.array(gameMap.get("rewrites")), tables.get(from), tables.get(to));
            for (Object o : yarnRewrites(linked, tables.get(from), tables.get(to))) {
                var r = Json.object(o);
                if (removed.contains(Json.text(r, "owner", "") + "." + Json.text(r, "from", "") + Json.text(r, "desc", ""))) rewrites.add(r);
            }
            List<Object> classRewrites = yarnClassRewrites(Json.array(gameMap.getOrDefault("classRewrites", List.of())), tables.get(from), tables.get(to));
            route.put("rewrites", rewrites);
            route.put("classRewrites", classRewrites);
            saveMap(map, route);
        } catch (Exception unavailable) {
            System.err.println("Warning: the game's call-site rewrites are not in this yarn route: " + unavailable.getMessage());
        }
    }

    /** The game's rewrites (Mojang names) in yarn: the removed member as the source table names it, the replacement as the target's. */
    static List<Object> yarnRewrites(List<Object> game, Map<String, Object> source, Map<String, Object> target) {
        Map<String, Map<String, Object>> sourceMember = new HashMap<>(), targetMember = new HashMap<>();
        for (var side : List.of(Map.entry(source, sourceMember), Map.entry(target, targetMember))) {
            Map<String, String> mojangClass = new HashMap<>();
            for (Object o : Json.array(side.getKey().get("classes"))) {
                var c = Json.object(o);
                mojangClass.put(Json.text(c, "from", ""), Json.text(c, "to", ""));
            }
            for (Object o : Json.array(side.getKey().get("methods"))) {
                var m = Json.object(o);
                String owner = Json.text(m, "owner", "");
                String mojangOwner = Json.text(m, "newOwner", mojangClass.getOrDefault(owner, owner));
                side.getValue().put(mojangOwner + "." + Json.text(m, "to", "") + Json.text(m, "newDesc", ""), m);
            }
        }
        List<Object> out = new ArrayList<>();
        for (Object o : game) {
            var r = Json.object(o);
            var old = sourceMember.get(Json.text(r, "owner", "") + "." + Json.text(r, "from", "") + Json.text(r, "desc", ""));
            var neu = targetMember.get(Json.text(r, "toOwner", "") + "." + Json.text(r, "to", "") + Json.text(r, "newDesc", ""));
            boolean deleted = Json.text(r, "to", "").isEmpty();
            if (old == null || (neu == null && !deleted)) continue;
            Map<String, Object> yarn = new LinkedHashMap<>(r);
            yarn.put("owner", old.get("owner"));
            yarn.put("from", old.get("from"));
            yarn.put("desc", old.get("desc"));
            if (deleted) {
                out.add(yarn); // a call the game deleted has nothing to name on the target side
                continue;
            }
            yarn.put("toOwner", neu.get("owner"));
            yarn.put("to", neu.get("from"));
            yarn.put("newDesc", neu.get("desc"));
            out.add(yarn);
        }
        return out;
    }

    /** A JVM descriptor with each class name passed through names (dotted names, as the maps keep them). */
    static String descriptor(String desc, java.util.function.UnaryOperator<String> names) {
        Matcher type = Pattern.compile("L([^;]+);").matcher(desc);
        StringBuilder out = new StringBuilder();
        while (type.find()) type.appendReplacement(
            out,
            Matcher.quoteReplacement("L" + names.apply(type.group(1).replace('/', '.')).replace('.', '/') + ";")
        );
        return type.appendTail(out).toString();
    }

    /** Writes a map in the generators' own layout (one entry per line under each list), which the typed fixer reads line by line. */
    static void saveMap(Path file, Map<String, Object> map) throws IOException {
        StringBuilder out = new StringBuilder("{\n");
        List<String> keys = new ArrayList<>(map.keySet());
        for (int i = 0; i < keys.size(); i++) {
            Object value = map.get(keys.get(i));
            out.append("  ").append(Json.write(keys.get(i))).append(": ");
            if (value instanceof List<?> list) {
                out.append("[\n");
                for (int j = 0; j < list.size(); j++) {
                    Object item = list.get(j);
                    out.append("    ");
                    if (item instanceof Map<?, ?> entry) {
                        List<String> fields = new ArrayList<>();
                        for (var f : entry.entrySet()) fields.add(Json.write(String.valueOf(f.getKey())) + ": " + Json.write(f.getValue()));
                        out.append("{ ").append(String.join(", ", fields)).append(" }");
                    } else out.append(Json.write(item));
                    out.append(j + 1 < list.size() ? ",\n" : "\n");
                }
                out.append("  ]");
            } else out.append(Json.write(value));
            out.append(i + 1 < keys.size() ? ",\n" : "\n");
        }
        Files.writeString(file, out.append("}\n").toString(), StandardCharsets.UTF_8);
    }

    Set<String> mappedClasses;

    /** Old class names the route map records as removed with no replacement (dotted, nested with $). */
    Set<String> mappedClasses() throws IOException {
        if (mappedClasses != null) return mappedClasses;
        mappedClasses = new HashSet<>();
        if (mapFile == null || !Files.isRegularFile(mapFile)) return mappedClasses;
        var map = Json.read(mapFile);
        for (Object o : Json.array(map.get("classesRemoved"))) mappedClasses.add(String.valueOf(o).replace('/', '.'));
        // A verified rule for a removed class (BlockEntityType.Builder -> the 1.21.2 constructor) goes before carrying it.
        for (Object item : Json.array(Json.read(port.build.harness.resolve("knowledge/fixes.json")).get("rules"))) {
            if (Json.object(item).get("members") instanceof List<?> members) for (Object member : members) mappedClasses.remove(String.valueOf(member));
        }
        for (String list : List.of("classes", "classRewrites")) for (Object o : Json.array(map.get(list))) mappedClasses.remove(Json.text(Json.object(o), "from", ""));
        return mappedClasses;
    }

    /** The route map this port uses (for mixin target names, which javac never checks). */
    Path mapFile;
    Map<String, List<String[]>> mapMethods;

    /**
     * Drops from a route map the member renames knowledge/meaning-changes.json says change what the member returns
     * (1.21.2's getMaxBuildHeight -> getMaxY is one lower), so the typed fixer never applies them; the rules that keep
     * the old meaning run first, and what they cannot rewrite safely stays a reported error. Returns the lines dropped.
     */
    static int withoutMeaningChanges(Path map, Path knowledge) throws IOException {
        if (!Files.isRegularFile(map) || !Files.isRegularFile(knowledge)) return 0;
        List<String[]> pairs = new ArrayList<>();
        for (Object o : Json.array(Json.read(knowledge).get("changes"))) {
            var c = Json.object(o);
            pairs.add(new String[] { Json.text(c, "from", ""), Json.text(c, "to", "") });
        }
        List<String> lines = new ArrayList<>(Files.readAllLines(map, StandardCharsets.UTF_8));
        String section = "";
        int dropped = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher heading = Pattern.compile("^  \"(\\w+)\": \\[").matcher(line);
            if (heading.find()) {
                section = heading.group(1);
                continue;
            }
            if (!List.of("methods", "methodsMoved", "signatures").contains(section)) continue;
            for (String[] pair : pairs) if (
                line.contains("\"from\": \"" + pair[0] + "\"") && line.contains("\"to\": \"" + pair[1] + "\"")
            ) {
                lines.remove(i--);
                dropped++;
                break;
            }
        }
        if (dropped == 0) return 0;
        // A list whose last entry went keeps no trailing comma.
        for (int i = 1; i < lines.size(); i++) if (lines.get(i).startsWith("  ]") && lines.get(i - 1).endsWith(",")) lines.set(
            i - 1,
            lines.get(i - 1).substring(0, lines.get(i - 1).length() - 1)
        );
        Files.write(map, lines, StandardCharsets.UTF_8);
        return dropped;
    }

    String inferNames(String from) {
        // A source from a pre-1.14.4 release was written against MCP names, even when its build was
        // already retargeted (and so reads as the target release); say so before the yarn pins.
        if (Route.legacy(from)) return "mcp";
        for (String version : Port.versions(port.model)) if (Route.legacy(version)) return "mcp";
        for (Object p : Json.array(port.model.get("projects"))) {
            var pins = Json.object(Json.object(p).get("pins"));
            if (pins.containsKey("yarn_mappings") || pins.containsKey("yarn_version")) return "yarn";
        }
        return "mojmap";
    }

    /**
     * The namespace a seam-crossing port lands in: yarn when the retargeted build pins it, else
     * Mojang's names. Only the modern leg reads it; the legacy leg is always MCP.
     */
    String targetNames() {
        for (Object p : Json.array(port.model.get("projects"))) {
            var pins = Json.object(Json.object(p).get("pins"));
            if (pins.containsKey("yarn_mappings") || pins.containsKey("yarn_version")) return "yarn";
        }
        return "mojmap";
    }

    Set<String> mapClassesFrom;

    /** Whether the route map renames this class (dotted, nested with '.'): the typed fixer carries it to the target's name. */
    boolean mapRenames(String name) throws IOException {
        if (mapClassesFrom == null) {
            mapClassesFrom = new HashSet<>();
            if (mapFile != null && Files.isRegularFile(mapFile)) for (Object o : Json.array(Json.read(mapFile).get("classes"))) {
                mapClassesFrom.add(Json.text(Json.object(o), "from", "").replace('$', '.'));
            }
        }
        return mapClassesFrom.contains(name);
    }
}

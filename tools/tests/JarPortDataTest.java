import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * A mod's data carried to the release a port goes to (DataIds): ids the game's data fixers rename, read from the fixer
 * classes and borne out by both releases' registry reports, renamed both ways; files of a kind the game skips when they
 * name an id the release lacks left out; tag entries made optional; anything else named as a break, with the data that a
 * registry the release has no such registry for told apart; data in a folder the release reads under another name (1.21's
 * loot_table for loot_tables) moved there. Checked on a ported jar and its nested jar, and against a
 * fixer class whose ids are not built into a map, a rename an id with a shared meaning makes ambiguous, and a port with no
 * reports, which changes nothing.
 */
final class JarPortDataTest {

    public static void main(String[] args) throws Exception {
        // The fixers: a rename built with Map.of, a description string before it, a prefix, and two ids handed to a call
        // that builds no map (a schema registering two entities), which are no rename.
        DataIds.Fixers fixers = DataIds.read(List.of(fixerClass()));
        JarPortTest.expect(fixers.pairs().contains(List.of("minecraft:scute", "minecraft:turtle_scute"))
            && fixers.pairs().contains(List.of("minecraft:sweeping", "minecraft:sweeping_edge")), "ids built into a map are renames: " + fixers.pairs());
        JarPortTest.expect(!fixers.pairs().contains(List.of("minecraft:bogged", "minecraft:breeze")), "ids handed to a call that builds no map are no rename: " + fixers.pairs());
        JarPortTest.expect(fixers.prefixes().contains("generic."), "a prefix a fixer strips is read: " + fixers.prefixes());

        Map<String, Set<String>> old = report(
            "minecraft:item", "minecraft:scute minecraft:stone minecraft:potion",
            "minecraft:enchantment", "minecraft:sweeping",
            "minecraft:attribute", "minecraft:generic.movement_speed",
            "minecraft:entity_type", "minecraft:potion minecraft:bogged",
            "minecraft:recipe_serializer", "minecraft:crafting_shaped minecraft:crafting_special_armordye",
            "minecraft:worldgen/feature", "minecraft:random_patch minecraft:tree",
            "minecraft:float_provider_type", "minecraft:uniform",
            "minecraft:loot_function_type", "minecraft:set_count");
        Map<String, Set<String>> now = report(
            "minecraft:item", "minecraft:turtle_scute minecraft:stone minecraft:potion",
            "minecraft:enchantment", "minecraft:sweeping_edge",
            "minecraft:attribute", "minecraft:movement_speed",
            "minecraft:entity_type", "minecraft:splash_potion minecraft:bogged",
            "minecraft:recipe_serializer", "minecraft:crafting_shaped",
            "minecraft:worldgen/feature", "minecraft:tree",
            "minecraft:loot_function_type", "minecraft:set_count");
        DataIds.Fixers withSplit = new DataIds.Fixers(new HashSet<>(fixers.pairs()), fixers.prefixes());
        withSplit.pairs().add(List.of("minecraft:potion", "minecraft:splash_potion"));
        Map<String, String> forward = DataIds.renames(withSplit, old, now, true);
        JarPortTest.expect(forward.equals(Map.of("minecraft:scute", "minecraft:turtle_scute", "minecraft:sweeping", "minecraft:sweeping_edge",
            "minecraft:generic.movement_speed", "minecraft:movement_speed")), "renames forward, the prefix stripped, no id with a shared meaning: " + forward);
        Map<String, String> backward = DataIds.renames(withSplit, now, old, false);
        JarPortTest.expect(backward.equals(Map.of("minecraft:turtle_scute", "minecraft:scute", "minecraft:sweeping_edge", "minecraft:sweeping",
            "minecraft:movement_speed", "minecraft:generic.movement_speed")), "the same renames backward, the prefix put back: " + backward);

        // The folders: only a folder one release has whose name with one "s" less (or more) only the other has.
        Set<String> was = Set.of("loot_tables", "recipes", "structures", "tags/items", "tags/worldgen/biome", "worldgen", "dimension_type", "chat_type");
        Set<String> is = Set.of("loot_table", "recipe", "structure", "tags/item", "tags/worldgen/biome", "worldgen", "dimension_type", "chat_type", "trim_pattern");
        JarPortTest.expect(DataIds.folderRenames(was, is, true).equals(Map.of("loot_tables", "loot_table", "recipes", "recipe", "structures", "structure", "tags/items", "tags/item")),
            "folders renamed forward: " + DataIds.folderRenames(was, is, true));
        JarPortTest.expect(DataIds.folderRenames(is, was, false).equals(Map.of("loot_table", "loot_tables", "recipe", "recipes", "structure", "structures", "tags/item", "tags/items")),
            "and backward: " + DataIds.folderRenames(is, was, false));
        Set<String> both = new HashSet<>(is);
        both.add("recipes");
        JarPortTest.expect(!DataIds.folderRenames(was, both, true).containsKey("recipes"), "a folder the release still reads stays");

        Path scratch = Files.createDirectories(Path.of(args[0]));
        Path map = scratch.resolve("route-data.json");
        Files.writeString(map, "{}");
        JarPort port = new JarPort(new GameDiff.Snap("1.0"), new GameDiff.Snap("2.0"), map);
        port.dataStep = new DataIds.Step(Map.of("loot_tables", "loot_table", "tags/items", "tags/item"), forward, old, now, true);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("fabric.mod.json", bytes("{\"id\": \"m\", \"jars\": [{\"file\": \"META-INF/jars/inner.jar\"}]}"));
        files.put("data/m/recipe/shaped.json", bytes("{\"type\": \"minecraft:crafting_shaped\", \"result\": {\"id\": \"minecraft:scute\"}}"));
        files.put("data/m/recipe/dye.json", bytes("{\"type\": \"minecraft:crafting_special_armordye\"}"));
        files.put("data/m/loot_table/chest.json", bytes("{\"pools\": [{\"entries\": [{\"type\": \"minecraft:item\", \"name\": \"minecraft:stone\", "
            + "\"functions\": [{\"function\": \"minecraft:set_count\", \"count\": 2.5}, {\"function\": \"minecraft:set_enchantments\", "
            + "\"enchantments\": {\"minecraft:sweeping\": 1}}]}]}]}"));
        files.put("data/m/loot_table/old.json", bytes("{\"pools\": [{\"conditions\": [{\"condition\": \"minecraft:uniform\"}]}]}"));
        files.put("data/m/enchantment/fast.json", bytes("{\"effects\": {\"minecraft:attributes\": [{\"attribute\": \"minecraft:generic.movement_speed\"}]}}"));
        files.put("data/m/worldgen/configured_feature/patch.json", bytes("{\"type\": \"minecraft:random_patch\", \"config\": {\"tries\": 3}}"));
        files.put("data/m/worldgen/configured_feature/spread.json", bytes("{\"type\": \"minecraft:tree\", \"config\": {\"height\": {\"type\": \"minecraft:uniform\"}}}"));
        files.put("data/m/loot_tables/moved.json", bytes("{\"pools\": []}"));
        files.put("data/m/loot_tables/kept_apart.json", bytes("{\"pools\": [1]}"));
        files.put("data/m/loot_table/kept_apart.json", bytes("{\"pools\": [2]}"));
        files.put("data/minecraft/tags/items/old_things.json", bytes("{\"values\": [\"minecraft:gone_item\"]}"));
        files.put("data/minecraft/tags/item/things.json", bytes("{\"values\": [\"minecraft:stone\", \"minecraft:gone_item\", \"#minecraft:logs\", \"m:own\"]}"));
        String untouched = "{\"type\":   \"minecraft:tree\" ,\n \"config\": {}}";
        files.put("data/m/worldgen/configured_feature/kept.json", bytes(untouched));
        files.put("META-INF/jars/inner.jar", JarPortTest.jar(Map.of("fabric.mod.json", bytes("{\"id\": \"inner\"}"),
            "data/inner/recipe/dye.json", bytes("{\"type\": \"minecraft:crafting_special_armordye\"}"))));
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, byte[]> out = JarPortTest.entries(port.port(JarPortTest.jar(files), "data.jar", results));

        JarPortTest.expect(json(out, "data/m/recipe/shaped.json").equals(Json.parse("{\"type\": \"minecraft:crafting_shaped\", \"result\": {\"id\": \"minecraft:turtle_scute\"}}")),
            "an item id the fixers rename is renamed: " + text(out, "data/m/recipe/shaped.json"));
        JarPortTest.expect(!out.containsKey("data/m/recipe/dye.json"), "a recipe naming a serializer the release lacks is left out, as the game skips it");
        JarPortTest.expect(json(out, "data/m/loot_table/chest.json").toString().contains("minecraft:sweeping_edge=1")
            && json(out, "data/m/loot_table/chest.json").toString().contains("count=2.5"), "an id that is an object's key is renamed, the rest kept: " + text(out, "data/m/loot_table/chest.json"));
        JarPortTest.expect(out.containsKey("data/m/loot_table/old.json"), "a loot table naming an id of a registry the release does not report is kept: the game skips it at worst");
        JarPortTest.expect(text(out, "data/m/enchantment/fast.json").contains("\"minecraft:movement_speed\""), "an attribute the fixers strip a prefix from is renamed");
        JarPortTest.expect(json(out, "data/minecraft/tags/item/things.json").equals(Json.parse(
            "{\"values\": [\"minecraft:stone\", {\"id\": \"minecraft:gone_item\", \"required\": false}, \"#minecraft:logs\", \"m:own\"]}")),
            "a tag entry the release lacks is optional, the others as they were: " + text(out, "data/minecraft/tags/item/things.json"));
        JarPortTest.expect(text(out, "data/m/worldgen/configured_feature/kept.json").equals(untouched), "a file with nothing to change is left byte for byte");

        JarPortTest.expect(out.containsKey("data/m/loot_table/moved.json") && !out.containsKey("data/m/loot_tables/moved.json"),
            "a loot table in the old folder is moved to the one the release reads");
        JarPortTest.expect(text(out, "data/m/loot_table/kept_apart.json").contains("2") && text(out, "data/m/loot_tables/kept_apart.json").contains("1"),
            "a file the jar already has in the new folder is not overwritten");
        JarPortTest.expect(text(out, "data/minecraft/tags/item/old_things.json").contains("\"required\":false"), "a moved tag is judged in its new folder");

        Map<String, Object> outer = results.get(results.size() - 1);
        List<Object> left = Json.array(outer.get("data"));
        JarPortTest.expect(left.contains("data id gone: minecraft:worldgen/feature minecraft:random_patch (in data/m/worldgen/configured_feature/patch.json)"),
            "a world registry entry naming an id the release lacks is a break: " + left);
        JarPortTest.expect(left.contains("data registry gone: minecraft:float_provider_type minecraft:uniform (in data/m/worldgen/configured_feature/spread.json)"),
            "one naming a registry the release has none of is a break of its own: " + left);
        JarPortTest.expect(left.size() == 2, "nothing else is left: " + left);
        JarPortTest.expect(Json.array(outer.get("silent")).stream().anyMatch(s -> s.toString().contains("crafting_special_armordye (in data/m/recipe/dye.json)")),
            "the port says which file it left out and why: " + outer.get("silent"));
        Map<String, byte[]> inner = JarPortTest.entries(out.get("META-INF/jars/inner.jar"));
        JarPortTest.expect(!inner.containsKey("data/inner/recipe/dye.json") && inner.containsKey("fabric.mod.json"), "a nested mod's data is carried too");

        // Backward: the same jar's renamed ids go back.
        JarPort back = new JarPort(new GameDiff.Snap("2.0"), new GameDiff.Snap("1.0"), map);
        back.dataStep = new DataIds.Step(Map.of(), backward, now, old, true);
        Map<String, byte[]> returned = JarPortTest.entries(back.port(JarPortTest.jar(Map.of("data/m/recipe/shaped.json", out.get("data/m/recipe/shaped.json"))), "back.jar", new ArrayList<>()));
        JarPortTest.expect(text(returned, "data/m/recipe/shaped.json").contains("\"minecraft:scute\""), "a backward port renames the id back");

        // No reports (a porter with no releases to read them from): nothing changes and nothing is judged.
        JarPort plain = new JarPort(new GameDiff.Snap("1.0"), new GameDiff.Snap("2.0"), map);
        List<Map<String, Object>> none = new ArrayList<>();
        Map<String, byte[]> same = JarPortTest.entries(plain.port(JarPortTest.jar(files), "plain.jar", none));
        JarPortTest.expect(same.containsKey("data/m/recipe/dye.json") && text(same, "data/m/recipe/shaped.json").contains("\"minecraft:scute\"")
            && none.stream().noneMatch(r -> r.containsKey("data")), "without reports the data is as it was and not judged");

        // A jar built from source for 2.0, checked as a port from 2.0 to itself: its data was written for 1.0 (--data-from),
        // so an id 1.0 registers and 2.0 lacks is a break; judged against 2.0 alone it would pass.
        DataIds.REPORTS.put("1.0", Optional.of(old));
        DataIds.REPORTS.put("2.0", Optional.of(now));
        DataIds.FOLDERS.put("1.0", was);
        DataIds.FOLDERS.put("2.0", is);
        DataIds.FIXERS.put("2.0", new DataIds.Fixers(Set.of(), Set.of()));
        byte[] built = JarPortTest.jar(Map.of("data/m/worldgen/configured_feature/patch.json", bytes("{\"type\": \"minecraft:random_patch\", \"config\": {}}")));
        for (String written : new String[] { "1.0", null }) {
            JarPort self = new JarPort(new GameDiff.Snap("2.0"), new GameDiff.Snap("2.0"), map);
            self.origin = "2.0";
            self.dataOrigin = written;
            List<Map<String, Object>> judged = new ArrayList<>();
            self.port(built, "built.jar", judged);
            List<Object> gone = Json.array(judged.get(judged.size() - 1).get("data"));
            JarPortTest.expect(written == null ? gone.isEmpty() : gone.equals(List.of(
                    "data id gone: minecraft:worldgen/feature minecraft:random_patch (in data/m/worldgen/configured_feature/patch.json)")),
                "data ids judged against " + (written == null ? "the release itself: nothing gone" : "the release the data was written for") + ": " + gone);
        }
        JarPortTest.finish("JarPortDataTest");
    }

    /** A class named as a data fixer, whose method builds a rename map, gives a description and a prefix, and registers two ids. */
    static byte[] fixerClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/minecraft/util/datafix/DataFixers", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "addFixers", "()V", null, null);
        mv.visitCode();
        mv.visitLdcInsn("minecraft:scute");
        mv.visitLdcInsn("minecraft:turtle_scute");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Map", "of", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitLdcInsn("Rename sweeping enchant to sweeping_edge");
        mv.visitLdcInsn("minecraft:sweeping");
        mv.visitLdcInsn("minecraft:sweeping_edge");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Map", "of", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.POP);
        mv.visitLdcInsn("generic.");
        mv.visitInsn(Opcodes.POP);
        mv.visitLdcInsn("minecraft:bogged");
        mv.visitLdcInsn("minecraft:breeze");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/util/datafix/schemas/V3800", "registerSimple", "(Ljava/lang/String;Ljava/lang/String;)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    static Map<String, Set<String>> report(String... pairs) {
        Map<String, Set<String>> out = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(pairs[i], new HashSet<>(List.of(pairs[i + 1].split(" "))));
        }
        return out;
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String text(Map<String, byte[]> out, String name) {
        return new String(out.get(name), StandardCharsets.UTF_8);
    }

    static Object json(Map<String, byte[]> out, String name) {
        return Json.parse(text(out, name));
    }
}

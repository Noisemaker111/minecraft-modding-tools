import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * A mod's pack.mcmeta ported to a release whose reader takes min_format/max_format (1.21.9 on) declares that release's own
 * resource and data formats as its range. 26.1's reader refuses a pack_format above 64 with no range for resources (a
 * 1.21.11 mod's "pack_format": 75), and a range reaching below 82 with no supported_formats for data ("min_format": 75);
 * a mod's jar is read as both kinds. The description and any other key stay; a release whose reader reads only
 * pack_format, or an unreadable file, is left as it was; a nested mod's pack is ported too.
 */
final class JarPortPackTest {

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        Path map = scratch.resolve("route-pack.json");
        Files.writeString(map, "{}");
        JarPort port = new JarPort(new GameDiff.Snap("1.0"), new GameDiff.Snap("2.0"), map);
        port.packFormats = new int[] { 84, 101 };
        String old = "{\"pack\": {\"description\": \"Mod resources\", \"pack_format\": 75, \"supported_formats\": [75, 94], \"x:extra\": 1}}";
        byte[] inner = JarPortTest.jar(Map.of("pack.mcmeta", "{\"pack\": {\"min_format\": 75, \"max_format\": 99}}".getBytes(StandardCharsets.UTF_8),
            "fabric.mod.json", "{\"id\": \"inner\"}".getBytes(StandardCharsets.UTF_8)));
        List<Map<String, Object>> runs = new ArrayList<>();
        Map<String, byte[]> out = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("pack.mcmeta", old.getBytes(StandardCharsets.UTF_8),
            "fabric.mod.json", "{\"id\": \"m\", \"jars\": [{\"file\": \"META-INF/jars/inner.jar\"}]}".getBytes(StandardCharsets.UTF_8),
            "META-INF/jars/inner.jar", inner)), "pack.jar", runs));
        Map<String, Object> pack = Json.object(Json.object(Json.parse(new String(out.get("pack.mcmeta"), StandardCharsets.UTF_8))).get("pack"));
        JarPortTest.expect(String.valueOf(pack.get("min_format")).equals("84") && String.valueOf(pack.get("max_format")).equals("101")
            && !pack.containsKey("pack_format") && !pack.containsKey("supported_formats"), "the release's own two formats are the range: " + pack);
        JarPortTest.expect("Mod resources".equals(pack.get("description")) && pack.containsKey("x:extra"), "the description and other keys stay: " + pack);
        Map<String, Object> nested = Json.object(Json.object(Json.parse(new String(JarPortTest.entries(out.get("META-INF/jars/inner.jar")).get("pack.mcmeta"),
            StandardCharsets.UTF_8))).get("pack"));
        JarPortTest.expect(String.valueOf(nested.get("min_format")).equals("84") && String.valueOf(nested.get("max_format")).equals("101"), "a nested mod's pack is ported: " + nested);

        port.packFormats = new int[0];
        byte[] kept = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("pack.mcmeta", old.getBytes(StandardCharsets.UTF_8))), "old.jar", new ArrayList<>())).get("pack.mcmeta");
        JarPortTest.expect(new String(kept, StandardCharsets.UTF_8).equals(old), "a release reading only pack_format keeps the file as it was");
        port.packFormats = new int[] { 84, 101 };
        byte[] broken = "{\"pack\": {\"pack_format\": 75,}} // a comment".getBytes(StandardCharsets.UTF_8);
        byte[] left = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("pack.mcmeta", broken)), "broken.jar", new ArrayList<>())).get("pack.mcmeta");
        JarPortTest.expect(Arrays.equals(left, broken), "an unreadable file is left as it was");
        JarPortTest.finish("JarPortPackTest");
    }
}

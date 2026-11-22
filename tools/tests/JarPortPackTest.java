import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * A mod's pack.mcmeta ported to a release whose reader takes min_format/max_format (1.21.9 on) is read as both a resource
 * pack and a data pack, and each release's reader must take it as both and find the release's own format inside the
 * range. 1.21.9 and 1.21.10 (resources 69, data 88) and 1.21.11 (75, 94) need a range that starts at or below 64 with
 * supported_formats and pack_format, as the data reader requires those below 82 and the resource reader refuses
 * supported_formats above 64; 26.1 (84, 101) takes the two formats alone. The description and any other key stay; a
 * release whose reader reads only pack_format, or an unreadable file, is left as it was; a nested mod's pack is ported too.
 */
final class JarPortPackTest {

    /** Each release's resource and data formats, from its version.json's pack_version. */
    static final Map<String, int[]> RELEASES = new LinkedHashMap<>();

    static {
        RELEASES.put("1.21.9", new int[] { 69, 88 });
        RELEASES.put("1.21.10", new int[] { 69, 88 });
        RELEASES.put("1.21.11", new int[] { 75, 94 });
        RELEASES.put("26.1", new int[] { 84, 101 });
    }

    /**
     * Why the game refuses a root pack section for one kind, or null when it takes it: PackFormat.IntermediaryFormat.validate
     * as PackFormat.packCodec calls it (pack_format allowed, supported_formats not required), with the kind's last format
     * without a minor part (PackFormat.lastPreMinorVersion: 64 for resources, 81 for data). Read from 26.1's client and
     * matching what 1.21.9, 1.21.10 and 1.21.11's own readers return for the shapes here.
     */
    static String refusal(Map<String, Object> pack, int last) {
        Integer min = number(pack.get("min_format"));
        Integer max = number(pack.get("max_format"));
        Integer format = number(pack.get("pack_format"));
        List<?> supported = pack.get("supported_formats") instanceof List<?> l ? l : null;
        if ((min == null) != (max == null)) {
            return "min_format and max_format go together";
        }
        if (min != null) {
            if (min > max) {
                return "min_format above max_format";
            }
            if (min > last) {
                if (supported != null) {
                    return "supported_formats is refused when min_format is above " + last;
                }
            } else {
                if (supported == null) {
                    return "min_format " + min + " needs supported_formats";
                }
                if (number(supported.get(0)) != min.intValue() || number(supported.get(supported.size() - 1)) != max.intValue()
                    && number(supported.get(supported.size() - 1)) != last) {
                    return "supported_formats must run from min_format to max_format";
                }
                if (format == null) {
                    return "min_format " + min + " needs pack_format";
                }
            }
            if (format != null && (format < min || format > max || format < 15)) {
                return "pack_format outside the range";
            }
            return null;
        }
        if (supported != null) {
            int low = number(supported.get(0));
            int high = number(supported.get(supported.size() - 1));
            if (high > last) {
                return "supported_formats above " + last + " needs min_format";
            }
            return format == null ? "supported_formats needs pack_format" : format < low || format > high || format < 15 ? "pack_format outside supported_formats" : null;
        }
        return format != null && format <= last ? null : "pack_format above " + last + " needs min_format";
    }

    static Integer number(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        Path map = scratch.resolve("route-pack.json");
        Files.writeString(map, "{}");
        JarPort port = new JarPort(new GameDiff.Snap("1.0"), new GameDiff.Snap("2.0"), map);
        String old = "{\"pack\": {\"description\": \"Mod resources\", \"pack_format\": 75, \"supported_formats\": [75, 94], \"x:extra\": 1}}";
        JarPortTest.expect(refusal(pack(old), 81) != null, "the reader as written refuses a shape the game refuses");
        JarPortTest.expect(refusal(pack("{\"pack\": {\"min_format\": 69, \"max_format\": 88}}"), 81) != null
            && refusal(pack("{\"pack\": {\"min_format\": 69, \"max_format\": 88, \"supported_formats\": [69, 88], \"pack_format\": 69}}"), 64) != null,
            "no 1.21.9 range starting at its own resource format is taken by both kinds");
        byte[] inner = JarPortTest.jar(Map.of("pack.mcmeta", "{\"pack\": {\"min_format\": 75, \"max_format\": 99}}".getBytes(StandardCharsets.UTF_8),
            "fabric.mod.json", "{\"id\": \"inner\"}".getBytes(StandardCharsets.UTF_8)));
        for (Map.Entry<String, int[]> release : RELEASES.entrySet()) {
            port.packFormats = release.getValue();
            Map<String, byte[]> out = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("pack.mcmeta", old.getBytes(StandardCharsets.UTF_8),
                "fabric.mod.json", "{\"id\": \"m\", \"jars\": [{\"file\": \"META-INF/jars/inner.jar\"}]}".getBytes(StandardCharsets.UTF_8),
                "META-INF/jars/inner.jar", inner)), "pack.jar", new ArrayList<>()));
            Map<String, Object> written = pack(new String(out.get("pack.mcmeta"), StandardCharsets.UTF_8));
            Map<String, Object> nested = pack(new String(JarPortTest.entries(out.get("META-INF/jars/inner.jar")).get("pack.mcmeta"), StandardCharsets.UTF_8));
            String[] kinds = { "resources", "data" };
            for (int kind = 0; kind < 2; kind++) {
                int last = kind == 0 ? 64 : 81;
                int current = release.getValue()[kind];
                for (Map<String, Object> pack : List.of(written, nested)) {
                    String why = refusal(pack, last);
                    JarPortTest.expect(why == null, release.getKey() + "'s " + kinds[kind] + " reader takes the ported pack (" + why + "): " + pack);
                    JarPortTest.expect(number(pack.get("min_format")) <= current && current <= number(pack.get("max_format")),
                        release.getKey() + "'s own " + kinds[kind] + " format " + current + " is in the range: " + pack);
                }
            }
            JarPortTest.expect("Mod resources".equals(written.get("description")) && written.containsKey("x:extra"), "the description and other keys stay: " + written);
        }
        port.packFormats = RELEASES.get("26.1");
        Map<String, Object> plain = pack(new String(JarPortTest.entries(port.port(JarPortTest.jar(Map.of("pack.mcmeta", old.getBytes(StandardCharsets.UTF_8))),
            "plain.jar", new ArrayList<>())).get("pack.mcmeta"), StandardCharsets.UTF_8));
        JarPortTest.expect(String.valueOf(plain.get("min_format")).equals("84") && String.valueOf(plain.get("max_format")).equals("101")
            && !plain.containsKey("pack_format") && !plain.containsKey("supported_formats"), "on 26.1 the release's own two formats are the range: " + plain);

        port.packFormats = new int[0];
        byte[] kept = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("pack.mcmeta", old.getBytes(StandardCharsets.UTF_8))), "old.jar", new ArrayList<>())).get("pack.mcmeta");
        JarPortTest.expect(new String(kept, StandardCharsets.UTF_8).equals(old), "a release reading only pack_format keeps the file as it was");
        port.packFormats = RELEASES.get("26.1");
        byte[] broken = "{\"pack\": {\"pack_format\": 75,}} // a comment".getBytes(StandardCharsets.UTF_8);
        byte[] left = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("pack.mcmeta", broken)), "broken.jar", new ArrayList<>())).get("pack.mcmeta");
        JarPortTest.expect(Arrays.equals(left, broken), "an unreadable file is left as it was");
        JarPortTest.finish("JarPortPackTest");
    }

    static Map<String, Object> pack(String json) {
        return Json.object(Json.object(Json.parse(json)).get("pack"));
    }
}

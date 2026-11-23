import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * A jar ported to a release gets the metadata that release's loader reads, its nested jars too:
 * - NeoForge before 20.5 reads META-INF/mods.toml, from 20.5 only META-INF/neoforge.mods.toml;
 * - NeoForge 20.2 and 20.3 (FML 1) say a dependency is needed only by mandatory, FML 4 on only by type;
 * - a key only newer NeoForge reads (enumExtensions) is a break, a dependency type FML 1 cannot say is reported.
 * The Java a mixin config and fabric.mod.json ask for follows the classes' own lowering (JarPortJavaTest).
 */
final class JarPortMetadataTest {

    static int failures;

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        neoforgeBack(scratch);
        neoforgeForward(scratch);
        if (failures > 0) {
            System.out.println(failures + " metadata checks failed");
            System.exit(1);
        }
        System.out.println("metadata checks passed");
    }

    static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAILED: " + what);
        }
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String text(Map<String, byte[]> files, String name) {
        byte[] data = files.get(name);
        return data == null ? null : new String(data, StandardCharsets.UTF_8);
    }

    static JarPort port(Path scratch, String to, String loader, String language) throws Exception {
        Path map = scratch.resolve("route-metadata.json");
        Files.writeString(map, "{}");
        JarPort port = new JarPort(new GameDiff.Snap("1.0"), new GameDiff.Snap(to), map);
        port.loader = loader;
        port.modMetadata.languageRange = language;
        return port;
    }

    static List<String> all(List<Map<String, Object>> results, String key) {
        return results.stream().flatMap(r -> Json.array(r.get(key)).stream()).map(String::valueOf).toList();
    }

    static Map<String, byte[]> neoforgeJar(String id, Map<String, byte[]> extra) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("META-INF/neoforge.mods.toml", utf8("""
            modLoader="javafml"
            loaderVersion="[4,)"
            license="MIT"
            [[mods]]
            modId="%s"
            version="1"
            %s
            [[dependencies.%s]]
                modId="neoforge"
                type="required"
                versionRange="[21.1,)"
            [[dependencies.%s]]
                modId="jei"
                type="optional"
                versionRange="[19,)"
            [[dependencies.%s]]
                modId="clashing"
                type="incompatible"
                reason="it breaks"
            [[mixins]]
                config="%s.mixins.json"
            """.formatted(id, id.equals("outer") ? "enumExtensions=\"META-INF/enumextensions.json\"" : "", id, id, id, id)));
        files.put(id + ".mixins.json", utf8("{\n  \"required\": true,\n  \"package\": \"" + id + ".mixin\",\n  \"compatibilityLevel\": \"JAVA_21\",\n  \"mixins\": []\n}\n"));
        files.putAll(extra);
        return files;
    }

    /** NeoForge 1.21.1 to 1.20.3 (FML 1, Java 17): every part of the metadata, in the outer jar and the one nested in it. */
    static void neoforgeBack(Path scratch) throws Exception {
        byte[] inner = JarPortTest.jar(neoforgeJar("inner", Map.of()));
        Map<String, byte[]> files = neoforgeJar("outer", Map.of(
            "META-INF/jarjar/metadata.json", utf8("{\"jars\": [{\"identifier\": {\"group\": \"g\", \"artifact\": \"inner\"}, \"version\": {\"range\": \"[1,)\", \"artifactVersion\": \"1\"}, \"path\": \"META-INF/jarjar/inner.jar\"}]}"),
            "META-INF/jarjar/inner.jar", inner));
        List<Map<String, Object>> results = new ArrayList<>();
        JarPort port = port(scratch, "1.20.3", "neoforge", "[1,)");
        Map<String, byte[]> out = JarPortTest.entries(port.port(JarPortTest.jar(files), "outer.jar", results));
        Map<String, byte[]> nested = JarPortTest.entries(out.get("META-INF/jarjar/inner.jar"));
        for (var jar : List.of(Map.entry("outer", out), Map.entry("inner", nested))) {
            String toml = text(jar.getValue(), "META-INF/mods.toml");
            expect(toml != null && !jar.getValue().containsKey("META-INF/neoforge.mods.toml"),
                jar.getKey() + ": NeoForge 20.3 reads META-INF/mods.toml, so the metadata is there: " + jar.getValue().keySet());
            if (toml == null) {
                continue;
            }
            expect(toml.contains("mandatory=true") && toml.contains("mandatory=false") && !toml.contains("type="),
                jar.getKey() + ": FML 1 reads only mandatory, from the required and optional types: " + toml);
            expect(!toml.contains("clashing") && toml.contains("modId=\"jei\""),
                jar.getKey() + ": the incompatible block FML 1 cannot say is left out, the others kept: " + toml);
            expect(toml.contains("loaderVersion=\"[1,)\"") && toml.contains("config=\"" + jar.getKey() + ".mixins.json\""),
                jar.getKey() + ": the release's language version, and the mixin config list FML 1 reads too: " + toml);
        }
        List<String> unlinked = all(results, "unlinked"), silent = all(results, "silent");
        expect(unlinked.stream().anyMatch(u -> u.contains("enumExtensions")) && results.get(results.size() - 1).get("jar").equals("outer.jar")
                && Json.array(results.get(results.size() - 1).get("unlinked")).stream().anyMatch(u -> String.valueOf(u).contains("enumExtensions")),
            "enum extensions, which NeoForge before 21.0 never makes, are a break of the jar declaring them: " + unlinked);
        expect(unlinked.stream().noneMatch(u -> u.contains("inner.jar") && u.contains("enumExtensions")), "the nested jar declares none: " + unlinked);
        expect(silent.stream().filter(s -> s.contains("incompatible") && s.contains("clashing")).count() == 2,
            "the left-out incompatible dependency is reported for each jar: " + silent);
    }

    /** NeoForge 20.3 to 1.21.1 (FML 4) reads only type, and 20.4 (FML 2) keeps it; mandatory=false is optional, not required. */
    static void neoforgeForward(Path scratch) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("META-INF/mods.toml", utf8("""
            modLoader="javafml"
            loaderVersion="[1,)"
            license="MIT"
            [[mods]]
            modId="old"
            [[dependencies.old]]
                modId="jei"
                mandatory=false
                versionRange="[15,)"
            """));
        JarPort port = port(scratch, "1.21.1", "neoforge", "[4,)");
        Map<String, byte[]> out = JarPortTest.entries(port.port(JarPortTest.jar(files), "old.jar", new ArrayList<>()));
        String toml = text(out, "META-INF/neoforge.mods.toml");
        expect(toml != null && !out.containsKey("META-INF/mods.toml"), "NeoForge 21.1 reads only neoforge.mods.toml: " + out.keySet());
        expect(toml != null && toml.contains("type=\"optional\"") && !toml.contains("mandatory"),
            "FML 4 reads only type: mandatory=false becomes optional, not required: " + toml);
        Map<String, byte[]> typed = new LinkedHashMap<>(neoforgeJar("same", Map.of()));
        Map<String, byte[]> at204 = JarPortTest.entries(port(scratch, "1.20.4", "neoforge", "[2,)").port(JarPortTest.jar(typed), "same.jar", new ArrayList<>()));
        String toml204 = text(at204, "META-INF/mods.toml");
        expect(toml204 != null && toml204.contains("type=\"incompatible\"") && !toml204.contains("mandatory"),
            "NeoForge 20.4 (FML 2) reads type, so every type stays: " + toml204);
    }
}

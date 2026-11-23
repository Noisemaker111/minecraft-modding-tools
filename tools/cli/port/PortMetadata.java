import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** The mod's metadata files checked against the new release and loader, and old formats (mcmod.info) moved to the current one. */
final class PortMetadata {

    final Port port;

    PortMetadata(Port port) {
        this.port = port;
    }

    /**
     * A loader-derived game version is the one that matters. NeoForge resolves Minecraft from its
     * own artifact, so a changed minecraft_version alone can still compile against the old game.
     */
    static void verifyLoaderVersion(Map<String, Object> model, String to) throws IOException {
        boolean neoforge = false;
        String resolved = "";
        for (Object item : Json.array(model.get("projects"))) {
            for (Object d : Json.array(Json.object(item).get("dependencies"))) {
                var dep = Json.object(d);
                if (!"net.neoforged".equals(dep.get("group")) || !"neoforge".equals(dep.get("name"))) continue;
                neoforge = true;
                resolved = dep.get("version") == null ? "" : dep.get("version").toString();
            }
        }
        if (!neoforge) return;
        String prefix = TargetNeoForge.neoForgePrefix(to);
        if (!resolved.startsWith(prefix)) throw new IOException(
            "The evaluated NeoForge dependency is " +
            resolved +
            ", which does not target " +
            to +
            " (expected " +
            prefix +
            "x). Update the NeoForge version owner and rerun; a passing build would use the old game."
        );
    }

    /**
     * A repo can build several Minecraft variants from one source. Only the ported target is
     * verified here; nested builds for other versions would fail on the ported sources and are skipped.
     */
    /**
     * A passable build is not a loadable mod: NeoForge refuses a jar whose declared Minecraft
     * range still names the old game. Check the packaged metadata, not just the compiler.
     */
    static Map<String, Object> verifyModMetadata(Path projectDir, Map<String, Object> model, String to)
        throws IOException {
        boolean neoforge = false, fabric = false;
        for (Object item : Json.array(model.get("projects"))) {
            Set<String> loaders = Support.loaders(Json.object(item));
            if (loaders.contains("neoforge")) neoforge = true;
            if (loaders.contains("fabric")) fabric = true;
        }
        if (!neoforge && fabric) return verifyFabricMetadata(projectDir, to);
        if (!neoforge) return null;
        Path libs = projectDir.resolve("build/libs");
        if (!Files.isDirectory(libs)) return Json.map(
            "ok",
            true,
            "checked",
            false,
            "details",
            "No packaged jar to inspect."
        );
        Pattern block = Pattern.compile(
            "(?s)\\[\\[dependencies\\.([^\\]]+)\\]\\]([^\\[]*?modId\\s*=\\s*\"minecraft\"[^\\[]*?versionRange\\s*=\\s*\")([^\"]+)\""
        );
        List<String> jars = new ArrayList<>(), ranges = new ArrayList<>();
        String decidedBy = null;
        List<Path> candidates = new ArrayList<>();
        try (var stream = Files.list(libs)) {
            for (Path jar : stream.filter(p -> p.toString().endsWith(".jar")).toList()) {
                if (jar.getFileName().toString().matches(".*-(sources|api|javadoc)\\.jar$")) continue;
                candidates.add(jar);
            }
        }
        // Only the freshest jar counts: a stale artifact from an earlier build must not decide the check.
        candidates.sort(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed());
        for (Path jar : candidates) {
            try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
                boolean found = false;
                for (String name : List.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
                    var entry = zip.getEntry(name);
                    if (entry == null) continue;
                    String text = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                    Matcher match = block.matcher(text);
                    while (match.find()) {
                        String range = match.group(3);
                        jars.add(jar.getFileName().toString());
                        ranges.add(range);
                        found = true;
                        // Read as FML reads it: a Maven version range, not text containing the version.
                        MavenRange.Answer answer = MavenRange.contains(range, to, Port.classpath(model));
                        decidedBy = answer.decidedBy();
                        if (answer.error() != null) return Json.map(
                            "ok",
                            false,
                            "checked",
                            true,
                            "jar",
                            jar.toString(),
                            "declared",
                            range,
                            "decidedBy",
                            decidedBy,
                            "details",
                            "The packaged mod's Minecraft versionRange \"" +
                            range +
                            "\" is not a valid Maven range (" +
                            answer.error() +
                            "); NeoForge refuses the mod. Fix the range and rerun."
                        );
                        if (!answer.contains()) return Json.map(
                            "ok",
                            false,
                            "checked",
                            true,
                            "jar",
                            jar.toString(),
                            "declared",
                            range,
                            "decidedBy",
                            decidedBy,
                            "details",
                            "The packaged mod declares Minecraft " +
                            range +
                            " but was ported to " +
                            to +
                            "; NeoForge refuses it at runtime and the client would list 0 mods. Update the property behind the metadata range and rerun."
                        );
                    }
                    if (found) break;
                }
                if (found) break;
            }
        }
        return Json.map(
            "ok",
            true,
            "checked",
            !ranges.isEmpty(),
            "jars",
            jars,
            "ranges",
            ranges,
            "decidedBy",
            decidedBy,
            "details",
            ranges.isEmpty()
                ? "No Minecraft dependency range in the packaged metadata."
                : "Packaged metadata accepts " + to + " (range read by " + decidedBy + ")."
        );
    }

    /**
     * Fabric refuses a jar whose packaged fabric.mod.json does not accept the running game, even when
     * the source manifest used a placeholder the build expanded. Read the freshest jar, not the source.
     */
    static Map<String, Object> verifyFabricMetadata(Path projectDir, String to) throws IOException {
        Path libs = projectDir.resolve("build/libs");
        if (!Files.isDirectory(libs)) return Json.map(
            "ok",
            true,
            "checked",
            false,
            "details",
            "No packaged jar to inspect."
        );
        List<Path> candidates;
        try (var stream = Files.list(libs)) {
            candidates = new ArrayList<>(
                stream
                    .filter(
                        p ->
                            p.toString().endsWith(".jar") &&
                            !p.getFileName().toString().matches(".*-(sources|api|javadoc|dev)\\.jar$")
                    )
                    .toList()
            );
        }
        candidates.sort(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed());
        for (Path jar : candidates) {
            try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
                var entry = zip.getEntry("fabric.mod.json");
                if (entry == null) continue;
                Map<String, Object> metadata;
                try {
                    metadata = Json.object(
                        Json.parse(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8))
                    );
                } catch (IllegalArgumentException invalid) {
                    return Json.map(
                        "ok",
                        false,
                        "checked",
                        true,
                        "jar",
                        jar.toString(),
                        "details",
                        "The packaged fabric.mod.json is not valid JSON: " + invalid.getMessage()
                    );
                }
                Object depends = metadata.get("depends") instanceof Map<?, ?> map ? map.get("minecraft") : null;
                if (depends == null) return Json.map(
                    "ok",
                    true,
                    "checked",
                    true,
                    "jar",
                    jar.toString(),
                    "details",
                    "The packaged mod declares no Minecraft dependency."
                );
                List<String> ranges = depends instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList()
                    : List.of(String.valueOf(depends));
                // A list is "any of": Fabric loads the mod when one entry accepts the game.
                boolean accepted = ranges.stream().anyMatch(range -> Versions.matches(range, to));
                if (!accepted) return Json.map(
                    "ok",
                    false,
                    "checked",
                    true,
                    "jar",
                    jar.toString(),
                    "declared",
                    ranges,
                    "details",
                    "The packaged mod declares Minecraft " +
                    String.join(" or ", ranges) +
                    " but was ported to " +
                    to +
                    "; Fabric refuses to load it. Update the property or manifest behind that range and rerun."
                );
                return Json.map(
                    "ok",
                    true,
                    "checked",
                    true,
                    "jar",
                    jar.toString(),
                    "declared",
                    ranges,
                    "details",
                    "Packaged metadata accepts " + to + "."
                );
            }
        }
        return Json.map("ok", true, "checked", false, "details", "No packaged fabric.mod.json to inspect.");
    }

    /**
     * Forge before 1.13 reads the mod list from mcmod.info, not a TOML: a ported tree that keeps
     * only the modern metadata loads but stays nameless. Translate the source metadata once the
     * tree compiles, before the jar build; the game check reads the built mcmod.info back.
     */
    void migrateLegacyMetadata(String to) throws Exception {
        Map<String, Object> info = legacyModInfo(port.build.project.root, to);
        if (info == null) return;
        Path file = port.build.project.root.resolve("src/main/resources/mcmod.info");
        String text = Json.write(List.of(info));
        if (Files.isRegularFile(file) && Files.readString(file).equals(text)) return;
        port.journal.track(file);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    /**
     * The mcmod.info entry the target's Forge reads, or null when the target needs none (1.13+)
     * or the source metadata names no mod.
     */
    static Map<String, Object> legacyModInfo(Path root, String to) throws IOException {
        if (Versions.compare(to, "1.13") >= 0) return null;
        Map<String, String> raw = null;
        for (String name : List.of(
            "src/main/templates/META-INF/neoforge.mods.toml",
            "src/main/resources/META-INF/neoforge.mods.toml",
            "src/main/resources/META-INF/mods.toml"
        )) {
            Path toml = root.resolve(name);
            if (Files.isRegularFile(toml)) raw = tomlModTable(Files.readString(toml), gradleProperties(root));
            if (raw != null) break;
        }
        if (raw == null) {
            Path fabric = root.resolve("src/main/resources/fabric.mod.json");
            if (Files.isRegularFile(fabric)) raw = fabricModTable(Files.readString(fabric));
        }
        if (raw == null || raw.getOrDefault("modid", "").isEmpty()) return null;
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("modid", raw.get("modid"));
        if (!raw.getOrDefault("name", "").isEmpty()) info.put("name", raw.get("name"));
        if (!raw.getOrDefault("description", "").isEmpty()) info.put("description", raw.get("description"));
        if (!raw.getOrDefault("version", "").isEmpty()) info.put("version", raw.get("version"));
        info.put("mcversion", to);
        if (!raw.getOrDefault("url", "").isEmpty()) info.put("url", raw.get("url"));
        List<String> authors = new ArrayList<>();
        for (String author : raw.getOrDefault("authors", "").split(",")) {
            if (!author.strip().isEmpty()) authors.add(author.strip());
        }
        if (!authors.isEmpty()) info.put("authorList", authors);
        if (!raw.getOrDefault("credits", "").isEmpty()) info.put("credits", raw.get("credits"));
        if (!raw.getOrDefault("logoFile", "").isEmpty()) info.put("logoFile", raw.get("logoFile"));
        return info;
    }

    /** key=value properties without profile handling: the mod_* values a metadata template needs. */
    static Map<String, String> gradleProperties(Path root) throws IOException {
        Map<String, String> props = new LinkedHashMap<>();
        Path file = root.resolve("gradle.properties");
        if (!Files.isRegularFile(file)) return props;
        for (String line : Files.readString(file).split("\\R")) {
            String strip = line.strip();
            if (strip.isEmpty() || strip.startsWith("#") || !strip.contains("=")) continue;
            props.put(strip.substring(0, strip.indexOf('=')).strip(), strip.substring(strip.indexOf('=') + 1).strip());
        }
        return props;
    }

    /**
     * The first [[mods]] table of a source TOML under canonical keys (modid, version, name,
     * description, authors, credits, url, logoFile), with ${placeholders} resolved, or null.
     */
    static Map<String, String> tomlModTable(String text, Map<String, String> props) {
        Map<String, String> table = null;
        boolean inMods = false;
        List<String> lines = new ArrayList<>(List.of(text.split("\\R")));
        for (int i = 0; i < lines.size(); i++) {
            String strip = lines.get(i).strip();
            if (strip.startsWith("[[")) {
                inMods = strip.matches("\\[\\[\\s*mods\\s*\\]\\].*");
                if (inMods && table == null) table = new LinkedHashMap<>();
                continue;
            }
            if (!inMods || table == null || strip.startsWith("#") || !strip.contains("=")) continue;
            String key = strip.substring(0, strip.indexOf('=')).strip();
            String value = quotedValue(strip.substring(strip.indexOf('=') + 1).strip(), lines, i);
            if (value == null) continue;
            table.put(canonicalModKey(key), substitute(value, props));
        }
        return table;
    }

    /** modId/displayName/displayURL read as the mcmod.info keys they become. */
    static String canonicalModKey(String key) {
        if (key.equals("modId")) return "modid";
        if (key.equals("displayName")) return "name";
        if (key.equals("displayURL")) return "url";
        return key;
    }

    /** The first quoted string of a TOML value, spanning lines for triple-quoted text, or null. */
    static String quotedValue(String value, List<String> lines, int index) {
        if (value.startsWith("'''")) {
            StringBuilder multi = new StringBuilder(value.substring(3));
            int i = index;
            while (!multi.toString().endsWith("'''") && i + 1 < lines.size()) {
                i++;
                multi.append("\n").append(lines.get(i));
            }
            String joined = multi.toString();
            if (!joined.endsWith("'''")) return null;
            return joined.substring(0, joined.length() - 3);
        }
        if (value.startsWith("\"")) return quotedUntil(value.substring(1), '"');
        if (value.startsWith("'")) return quotedUntil(value.substring(1), '\'');
        return null;
    }

    /** Up to the next quote, honoring backslash escapes in double-quoted text. */
    static String quotedUntil(String rest, char quote) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (quote == '"' && c == '\\' && i + 1 < rest.length()) {
                i++;
                out.append(rest.charAt(i));
                continue;
            }
            if (c == quote) return out.toString();
            out.append(c);
        }
        return null;
    }

    /** ${name} placeholders resolve from the project's properties; unknown ones stay visible. */
    static String substitute(String value, Map<String, String> props) {
        String resolved = value;
        for (var entry : props.entrySet()) {
            resolved = resolved.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return resolved;
    }

    /** A fabric.mod.json under the same canonical keys the TOML table uses. */
    static Map<String, String> fabricModTable(String text) {
        Map<String, Object> meta = Json.object(Json.parse(text));
        Map<String, String> table = new LinkedHashMap<>();
        table.put("modid", Json.text(meta, "id", ""));
        table.put("version", Json.text(meta, "version", ""));
        table.put("name", Json.text(meta, "name", ""));
        table.put("description", Json.text(meta, "description", ""));
        table.put("license", Json.text(meta, "license", ""));
        List<String> authors = new ArrayList<>();
        for (Object author : Json.array(meta.get("authors"))) {
            if (author instanceof Map) authors.add(Json.text((Map<String, Object>) author, "name", ""));
            if (author instanceof String) authors.add(author.toString());
        }
        table.put("authors", String.join(", ", authors));
        Map<String, Object> contact = Json.object(meta.get("contact"));
        table.put("url", Json.text(contact, "homepage", ""));
        return table;
    }
}

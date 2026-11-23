import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** A mod's metadata moved to the new release: mods.toml, fabric.mod.json, pack.mcmeta and the loader modules it names. */
final class ModMetadata {

    final JarPort port;

    ModMetadata(JarPort port) {
        this.port = port;
    }

    int[] packFormats; // the release ported to: its resource and data pack formats when its reader takes min_format/max_format, else {}

    /**
     * The pack formats of the release ported to, as its own version.json gives them ("pack_version"), when that release reads
     * pack.mcmeta's min_format and max_format (1.21.9 on, where the formats have a minor part); empty before.
     */
    int[] packFormats() {
        if (packFormats == null) {
            try {
                Path client = GameDiff.verified(GameDiff.download(GameDiff.versionJson(port.to), "client"), "client-" + port.to + ".jar");
                Map<String, Object> v = Json.object(Json.parse(VersionMap.zipEntry(client, "version.json")));
                Map<String, Object> pv = v.get("pack_version") instanceof Map<?, ?> m ? Json.object(m) : Map.of();
                packFormats = pv.containsKey("resource_minor") && pv.get("resource_major") instanceof Number r && pv.get("data_major") instanceof Number d
                    ? new int[] { r.intValue(), d.intValue() } : new int[0];
            } catch (Exception unreadable) {
                packFormats = new int[0];
            }
        }
        return packFormats;
    }

    /** The last pack formats without a minor part, resources then data (the game's PackFormat.lastPreMinorVersion). */
    static final int[] LAST_FORMATS_WITHOUT_MINOR = { 64, 81 };

    /**
     * A mod's pack.mcmeta declares the formats of the release it was built for, and the reader from 1.21.9 on
     * (PackFormat.IntermediaryFormat.validate) refuses some shapes the releases before took. Against each kind's last
     * format without a minor part, a min_format above it must have no supported_formats, and one at or below it must
     * have supported_formats from min_format to max_format and a pack_format inside the range. A mod's jar is one pack
     * read as both kinds, so a range starting between the two (1.21.9's 69 to 88) is refused by one kind whichever way
     * it is written. Ported, the range is the release's own two formats when it starts above both; otherwise it starts
     * at or below both, with the fields both kinds then ask for. Anything else in the section is kept, and an unreadable
     * file is left as it is.
     */
    byte[] packMetadata(JarPort.Mod mod, byte[] data) {
        int[] formats = packFormats();
        if (formats.length < 2) {
            return data;
        }
        int low = Math.min(formats[0], formats[1]);
        int high = Math.max(formats[0], formats[1]);
        boolean legacy = low <= Math.max(LAST_FORMATS_WITHOUT_MINOR[0], LAST_FORMATS_WITHOUT_MINOR[1]);
        int min = legacy ? Math.min(low, Math.min(LAST_FORMATS_WITHOUT_MINOR[0], LAST_FORMATS_WITHOUT_MINOR[1])) : low;
        Map<String, Object> meta;
        try {
            meta = Json.object(Json.parse(new String(data, StandardCharsets.UTF_8).replaceFirst("^\uFEFF", "")));
        } catch (RuntimeException unreadable) {
            return data;
        }
        if (!(meta.get("pack") instanceof Map<?, ?> section)) {
            return data;
        }
        Map<String, Object> pack = Json.object(section);
        List<Object> was = new ArrayList<>(List.of(String.valueOf(pack.get("pack_format")), String.valueOf(pack.get("min_format")), String.valueOf(pack.get("max_format")),
            String.valueOf(pack.get("supported_formats"))));
        pack.remove("pack_format");
        pack.remove("supported_formats");
        if (legacy) {
            pack.put("pack_format", low);
            pack.put("supported_formats", List.of(min, high));
        }
        pack.put("min_format", min);
        pack.put("max_format", high);
        List<Object> now = List.of(String.valueOf(pack.get("pack_format")), String.valueOf(min), String.valueOf(high), String.valueOf(pack.get("supported_formats")));
        if (was.equals(now)) {
            return data;
        }
        mod.change("pack formats declared for " + port.to);
        return Json.write(meta).getBytes(StandardCharsets.UTF_8);
    }

    Set<String> platformIds = Set.of(); // mod ids the platform the port is for provides (Fabric API's modules), --platform
    Map<String, String> platformAliases = Map.of(); // an old platform's other name for a module -> the module's id, --platform-aliases

    /**
     * What the old and new releases' platforms (Fabric API) answer to: every id of the new one's modules, and the names the
     * old one answered to only as another ("provides") that the new one dropped, each to the module that gave it.
     */
    void platform(Path was, Path now) throws IOException {
        if (now == null) {
            return;
        }
        Map<String, String> has = Linking.provided(Files.readAllBytes(now)), had = was == null ? Map.of() : Linking.provided(Files.readAllBytes(was));
        platformIds = Set.copyOf(has.keySet());
        Map<String, String> aliases = new HashMap<>();
        had.forEach((alias, owner) -> {
            if (!alias.equals(owner) && !has.containsKey(alias) && has.containsKey(owner)) {
                aliases.put(alias, owner);
            }
        });
        // A module the new platform no longer has by its id was renamed or merged (26.1's fabric-key-binding-api-v1 became
        // fabric-key-mapping-api-v1): it is the module that now holds most of its classes, in the new release's names. One
        // whose classes no module holds was removed, and is asked of no one: what the mod used of it is reported where used.
        if (was != null) {
            Map<String, Set<String>> before = modules(Files.readAllBytes(was)), after = modules(Files.readAllBytes(now));
            Map<String, String> holder = new HashMap<>();
            after.forEach((id, classes) -> classes.forEach(c -> holder.putIfAbsent(c, id)));
            before.forEach((id, classes) -> {
                if (has.containsKey(id) || aliases.containsKey(id)) {
                    return;
                }
                Map<String, Integer> votes = new TreeMap<>();
                classes.forEach(c -> {
                    String to = holder.get(port.cls(c));
                    if (to != null) {
                        votes.merge(to, 1, Integer::sum);
                    }
                });
                aliases.put(id, votes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(""));
            });
        }
        platformAliases = aliases;
    }

    /** The classes each module of a platform jar declares (its own and its nested jars'), by mod id; mixins left out. */
    static Map<String, Set<String>> modules(byte[] jar) throws IOException {
        Map<String, Set<String>> out = new TreeMap<>();
        String id = null;
        Set<String> classes = new TreeSet<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName();
                if (n.equals("fabric.mod.json")) {
                    try {
                        id = Json.object(Json.parse(new String(z.readAllBytes(), StandardCharsets.UTF_8))).get("id") instanceof String s ? s : null;
                    } catch (RuntimeException unreadable) {
                        id = null;
                    }
                } else if (n.endsWith(".class") && !n.contains("/mixin/") && !n.startsWith("META-INF/")) {
                    classes.add(n.substring(0, n.length() - 6));
                } else if (n.startsWith("META-INF/jars/") && n.endsWith(".jar")) {
                    modules(z.readAllBytes()).forEach((k, v) -> out.computeIfAbsent(k, x -> new TreeSet<>()).addAll(v));
                }
            }
        }
        if (id != null) {
            out.computeIfAbsent(id, x -> new TreeSet<>()).addAll(classes);
        }
        return out;
    }

    /** The mod id of a nested jar when the platform provides it, else null. */
    String platformModule(byte[] jar) {
        if (platformIds.isEmpty()) {
            return null;
        }
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (e.getName().equals("fabric.mod.json")) {
                    Object id = Json.object(Json.parse(new String(z.readAllBytes(), StandardCharsets.UTF_8))).get("id");
                    return id instanceof String s && platformIds.contains(s) ? s : null;
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        return null;
    }

    // ---- Metadata.

    static final Pattern BLOCK = Pattern.compile("(?ms)^\\s*\\[\\[dependencies\\.[^\\]]+\\]\\].*?(?=^\\s*\\[|\\z)");

    String languageRange; // the loaderVersion the new release's javafml answers, "[N,)"; "" when unknown

    /**
     * NeoForge's javafml and lowcodefml answer to the version of the FancyModLoader that release is built on (1 for
     * 20.2-20.3, 2 for 20.4, 11 for 26.1), which its own pom names; a Forge jar asks for Forge's ([47,)), which no
     * NeoForge release answers to. "" when the pom names none.
     */
    static String languageRange(String pom) {
        for (String[] d : Exposure.dependencies(pom)) {
            if (d[0].equals("net.neoforged.fancymodloader") && d[1].equals("loader")) {
                return "[" + d[2].replaceAll("\\..*", "") + ",)";
            }
        }
        return "";
    }

    String languageRange() {
        if (languageRange == null) {
            try {
                languageRange = port.loader.equals("neoforge") && port.loaderTo != null ? languageRange(Exposure.neoforgePom(port.loaderTo)) : "";
            } catch (Exception unreadable) {
                languageRange = "";
            }
        }
        return languageRange;
    }

    static final String OLD_TOML = "META-INF/mods.toml", NEW_TOML = "META-INF/neoforge.mods.toml";

    /** FancyModLoader versions from Forge's line (Forge 1.20.1 is FML 47) count as older than NeoForge's own FML 1. */
    static final int FORGE_FML = 47;

    /**
     * The major version of the FancyModLoader the release's NeoForge runs, which decides the metadata it reads: from the
     * loader's own pom (languageRange), else the FML each release's newest NeoForge ships (read from their poms: 1.20.2
     * and 1.20.3 run FML 1, 1.20.4 FML 2, 1.20.5 and 1.20.6 FML 3, 1.21 and 1.21.1 FML 4; before 1.20.2 Forge's FML 47).
     */
    int fml() {
        Matcher range = Pattern.compile("\\[(\\d+),").matcher(languageRange());
        if (range.lookingAt()) {
            int major = Integer.parseInt(range.group(1));
            return major >= FORGE_FML ? 0 : major;
        }
        return JarPort.compareVersions(port.to, "1.20.2") < 0 ? 0 : JarPort.compareVersions(port.to, "1.20.4") < 0 ? 1 : JarPort.compareVersions(port.to, "1.20.5") < 0 ? 2
            : JarPort.compareVersions(port.to, "1.21") < 0 ? 3 : 4;
    }

    /**
     * The metadata file the release's NeoForge reads. FML 3 (NeoForge 20.5) renamed META-INF/mods.toml to
     * META-INF/neoforge.mods.toml (NeoForge's 20.5 release notes, "mods.toml renamed"), and FML 1 and 2 read only the old
     * name (their ModFileParser names "mods.toml", not "META-INF/neoforge.mods.toml"). A jar keeping the other name is
     * skipped without a word, so the port writes the one the release reads.
     */
    String metadataFile() {
        return fml() >= 3 ? NEW_TOML : OLD_TOML;
    }

    /**
     * Keys of a mod's [[mods]] entry that only newer NeoForge reads, each with the first FML that does. Read on an older
     * one, the key is ignored and what it declares never happens, and nothing older says it another way: data-driven enum
     * extensions came with FML 4 (NeoForge 21.0: net.neoforged.fml.common.asm.enumextension, which FML 3 lacks; it
     * replaced IExtensibleEnum's create methods), and feature flags a mod declares with NeoForge 21.1's FeatureFlagLoader.
     */
    static final Map<String, String> NEWER_KEYS = Map.of("enumExtensions", "1.21", "featureFlags", "1.21.1");

    /** Dependency types FML 1's mandatory flag can say; incompatible and discouraged (FML 2) it cannot. */
    static final Map<String, String> MANDATORY = Map.of("required", "true", "optional", "false");

    static final Pattern DEPENDENCY_TYPE = Pattern.compile("(?m)^(\\s*)type\\s*=\\s*\"([A-Za-z]+)\"[ \\t]*\\r?\\n?");
    static final Pattern DEPENDENCY_MANDATORY = Pattern.compile("(?m)^(\\s*)mandatory\\s*=\\s*\"?(true|false)\"?[ \\t]*\\r?\\n?");

    String metadata(String toml) {
        return metadata(new JarPort.Mod(), toml);
    }

    /**
     * The loader metadata asks for the new release: the game's and the loader's version ranges of each dependency block,
     * and the version of the loader's own language provider. Each dependency says whether it is needed the way the
     * release's FML reads it: FML 1 (NeoForge 20.2, 20.3) only by mandatory=true|false and fails a block without it
     * ("Missing required field mandatory in dependency"); FML 2 and 3 read type="required|optional|incompatible|discouraged"
     * and still the deprecated mandatory; FML 4 on reads only type, so a mandatory=false block is required there. A key
     * only newer releases read is a break on an older one.
     */
    String metadata(JarPort.Mod mod, String toml) {
        String language = languageRange();
        if (!language.isEmpty() && Pattern.compile("(?m)^\\s*modLoader\\s*=\\s*\"(javafml|lowcodefml)\"").matcher(toml).find()) {
            toml = toml.replaceFirst("(?m)^(\\s*loaderVersion\\s*=\\s*)\"[^\"]*\"", "$1" + Matcher.quoteReplacement("\"" + language + "\""));
        }
        if (port.loader.equals("neoforge")) {
            for (var key : NEWER_KEYS.entrySet()) {
                if (JarPort.compareVersions(port.to, key.getValue()) < 0 && Pattern.compile("(?m)^\\s*" + key.getKey() + "\\s*=").matcher(toml).find()) {
                    mod.unlinked.add("metadata " + key.getKey() + " has no form before " + key.getValue() + ": NeoForge for " + port.to + " does not read it");
                }
            }
        }
        int fml = port.loader.equals("neoforge") ? fml() : -1;
        Matcher m = BLOCK.matcher(toml);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String block = m.group();
            if (fml >= 0) {
                block = dependencyType(mod, block, fml);
            }
            Matcher id = Pattern.compile("modId\\s*=\\s*\"([^\"]+)\"").matcher(block);
            if (block.isEmpty()) {
                // dropped: FML 1 cannot say it
            } else if (id.find()) {
                String range = switch (id.group(1)) {
                    case "minecraft" -> "[" + port.to + "]";
                    case "neoforge", "forge" -> port.loaderTo == null ? null : "[" + port.loaderTo.replaceAll("^(\\d+\\.\\d+).*", "$1") + ",)";
                    default -> null;
                };
                if (range != null) {
                    block = block.replaceFirst("versionRange\\s*=\\s*\"[^\"]*\"", Matcher.quoteReplacement("versionRange=\"" + range + "\""));
                }
                // NeoForge's own releases (from 1.20.2) provide the mod "neoforge" and none called "forge", so a Forge jar's
                // dependency on the loader fails before anything loads.
                if (id.group(1).equals("forge") && port.loader.equals("neoforge") && JarPort.compareVersions(port.to, "1.20.2") >= 0) {
                    block = block.replaceFirst("modId\\s*=\\s*\"forge\"", "modId=\"neoforge\"");
                }
                // And the reverse: NeoForge for 1.20.1 (47.1) is Forge's line, which provides "forge".
                if (id.group(1).equals("neoforge") && port.loader.equals("neoforge") && JarPort.compareVersions(port.to, "1.20.2") < 0) {
                    block = block.replaceFirst("modId\\s*=\\s*\"neoforge\"", "modId=\"forge\"");
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(block));
        }
        return m.appendTail(sb).toString();
    }

    /**
     * A dependency block saying whether it is needed as the release's FML reads it (metadata). A type FML 1 cannot say
     * (incompatible, discouraged) drops the block, reported: the loader no longer refuses the pair, though the mod runs.
     */
    static String dependencyType(JarPort.Mod mod, String block, int fml) {
        Matcher type = DEPENDENCY_TYPE.matcher(block), mandatory = DEPENDENCY_MANDATORY.matcher(block);
        boolean typed = type.find(), flagged = mandatory.find();
        if (fml <= 1 && typed && !flagged) {
            String kind = type.group(2).toLowerCase(Locale.ROOT);
            String flag = MANDATORY.get(kind);
            if (flag == null) {
                Matcher id = Pattern.compile("modId\\s*=\\s*\"([^\"]+)\"").matcher(block);
                mod.silent.add("dependency type " + kind + " has no form before NeoForge 20.4, left out: " + (id.find() ? id.group(1) : "?"));
                mod.change("dependency type the release cannot read left out");
                return "";
            }
            mod.change("dependency type written as mandatory");
            return block.substring(0, type.start()) + type.group(1) + "mandatory=" + flag + "\n" + block.substring(type.end());
        }
        if (fml >= 2 && flagged && !typed) {
            mod.change("dependency mandatory written as type");
            return block.substring(0, mandatory.start()) + mandatory.group(1) + "type=\"" + (mandatory.group(2).equals("true") ? "required" : "optional") + "\"\n"
                + block.substring(mandatory.end());
        }
        return block;
    }

    /** The access widener a fabric.mod.json names, or null. */
    static String widenerFile(byte[] fmj) {
        try {
            return Json.object(Json.parse(new String(fmj, StandardCharsets.UTF_8))).get("accessWidener") instanceof String w ? w : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * fabric.mod.json asks for the new release: its "minecraft" dependency becomes the release ported to, and it names the
     * access widener the port wrote when the mod had none. Anything unreadable is left as it is.
     */
    byte[] fabricMetadata(byte[] data, String widener, Set<String> platformNested) {
        Map<String, Object> meta;
        try {
            meta = Json.object(Json.parse(new String(data, StandardCharsets.UTF_8)));
        } catch (RuntimeException unreadable) {
            return data;
        }
        if (meta.get("depends") instanceof Map<?, ?> depends && depends.containsKey("minecraft")) {
            Json.object(depends).put("minecraft", port.to);
        }
        // A name the old platform answered to and the new one does not is asked of the module that gave it.
        if (meta.get("depends") instanceof Map<?, ?> depends) {
            Map<String, Object> asks = Json.object(depends);
            // A version of the platform (or one of its modules) the mod asks for is numbered as the old release's platform
            // was (Konkrete for 1.20.2 asks Fabric API 0.91.6, while 1.20.3's is 0.91.1): the new release's platform is its own.
            for (String id : List.copyOf(asks.keySet())) {
                if (platformNested.contains(id) || platformIds.contains(id)) {
                    asks.put(id, "*");
                }
            }
            for (var alias : platformAliases.entrySet()) {
                if (asks.remove(alias.getKey()) != null && !alias.getValue().isEmpty()) {
                    asks.put(alias.getValue(), "*");
                }
            }
        }
        if (widener != null && !meta.containsKey("accessWidener")) {
            meta.put("accessWidener", widener);
        }
        return Json.write(meta).getBytes(StandardCharsets.UTF_8);
    }
}

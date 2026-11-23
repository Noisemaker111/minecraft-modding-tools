import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * VersionMap - what Minecraft renamed, moved or changed between two versions, generated from
 * published mappings instead of written by hand.
 *
 * Fabric's intermediary names are stable across versions: the same intermediary name is the same
 * class, method or field in every release. Joining each version's readable names (Mojang's
 * official mappings, or yarn) through intermediary gives every rename and every signature change.
 *
 *   java tools/port/names/VersionMap.java <from> <to> [--names mojmap|yarn|mcp[:channel]] [--out map.json] [--cache dir]
 *   java tools/port/names/VersionMap.java --chain <v1,v2,...> [--names mojmap|yarn|mcp[:channel]] [--to-names ...] --out map.json
 *     (one map across every update, with history; a chain crossing 1.14.4 reads MCP names up to the
 *     seam through SRG ids, translates keys through the obfuscated names both lanes share, and reads
 *     the target namespace past it -- Mojang's by default for an MCP source, or --to-names)
 *
 * Releases before 1.14.4 have no intermediary and no Mojang mappings; Forge's SRG ids are their
 * stable identity instead, with MCP's published names as the readable side (see Srg).
 *   java tools/port/names/VersionMap.java --map map.json --explain <build log>
 *   java tools/port/names/VersionMap.java --map map.json --fix <build log> --repo <dir> [--rules learned.json] [--dry-run]
 *   java tools/port/names/VersionMap.java --map map.json --learn <a.diff> [--learn <b.diff>...] --out learned.json
 *
 * Mappings download once into the cache (default ~/.mmt/mappings). Versions from 26.1 ship
 * unobfuscated and have no intermediary yet; they are reported, not guessed.
 * Exit: 0 ok, 2 usage or I/O error.
 */
public final class VersionMap {

    static Path cache = Path.of(System.getProperty("user.home"), ".mmt", "mappings");
    static final String FORGE_MAVEN = "https://maven.minecraftforge.net/";
    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    /** The published name sets a table can be built from; mcp may pin a channel (mcp:stable_39). */
    static boolean knownNames(String names) {
        return names != null && (names.equals("mojmap") || names.equals("yarn") || names.equals("mcp") || names.startsWith("mcp:"));
    }

    public static void main(String[] args) throws Exception {
        List<String> pos = new ArrayList<>();
        String names = "mojmap", out = null, map = null, explain = null, fix = null, repo = ".", rules =
            null, fromNames = null, toNames = null, chain = null;
        List<String> learn = new ArrayList<>();
        boolean dryRun = false;
        boolean preflight = false;
        boolean hierarchy = false;
        String intermediary = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--intermediary" -> intermediary = args[++i];
                case "--names" -> names = args[++i];
                case "--from-names" -> fromNames = args[++i];
                case "--to-names" -> toNames = args[++i];
                case "--out" -> out = args[++i];
                case "--cache" -> cache = Path.of(args[++i]);
                case "--map" -> map = args[++i];
                case "--explain" -> explain = args[++i];
                case "--fix" -> fix = args[++i];
                case "--repo" -> repo = args[++i];
                case "--dry-run" -> dryRun = true;
                case "--preflight" -> preflight = true;
                case "--hierarchy" -> hierarchy = true;
                case "--rules" -> rules = args[++i];
                case "--learn" -> learn.add(args[++i]);
                case "--chain" -> chain = args[++i];
                case "--supers" -> {
                    // "Type Super1 Super2 ..." per line, simple names: a member renamed on a supertype is the receiver's too.
                    for (String line : Files.readAllLines(Path.of(args[++i]), StandardCharsets.UTF_8)) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length > 1) SUPERS.put(parts[0], new HashSet<>(Arrays.asList(parts).subList(1, parts.length)));
                    }
                }
                default -> pos.add(args[i]);
            }
        }
        if (intermediary != null) {
            // --intermediary VERSION OUT: every intermediary class and member of one release in Mojang's names, the
            // names a mixin refmap resolves to (mmt convert reads a yarn mod's refmap through it).
            Path target = Path.of(intermediary);
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.writeString(target, intermediaryJson(Table.load(pos.get(0), "mojmap")), StandardCharsets.UTF_8);
            System.out.println("        " + target);
            return;
        }
        if (chain != null) {
            if (!knownNames(names) || out == null) {
                System.err.println(
                    "usage: java tools/port/names/VersionMap.java --chain <v1,v2,...> [--names mojmap|yarn|mcp[:channel]] --out map.json"
                );
                System.exit(2);
            }
            long t0 = System.nanoTime();
            List<String> versions = List.of(chain.split(","));
            String n = names, t = toNames;
            boolean srgSeam = versions.stream().anyMatch(v -> releaseCompare(v, SEAM) < 0);
            MapChain c = MapChain.of(versions, v -> laneTable(v, n, t, srgSeam));
            Path target = Path.of(out);
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.writeString(target, c.json(names), StandardCharsets.UTF_8);
            Diff d = c.diff;
            System.out.printf(
                "chain %s (%s, %d updates): classes %d renamed or moved, %d removed; methods %d renamed, %d moved, " +
                "%d signature changes, %d removed; fields %d renamed, %d removed; %d history events (%.1fs)%n",
                String.join(" -> ", versions),
                names,
                versions.size() - 1,
                d.classRenamed.size(),
                d.classRemoved.size(),
                d.methodRenamed.size(),
                d.methodMoved.size(),
                d.methodSignature.size(),
                d.methodRemoved.size(),
                d.fieldRenamed.size(),
                d.fieldRemoved.size(),
                c.history.size(),
                (System.nanoTime() - t0) / 1e9
            );
            System.out.println("        " + target);
            return;
        }
        if (preflight && map != null && fix == null) {
            MapPreflight.run(Path.of(map), Path.of(repo).toAbsolutePath().normalize(), dryRun);
            return;
        }
        if (map != null && fix != null) {
            MapFix.run(
                Path.of(map),
                Path.of(fix),
                Path.of(repo).toAbsolutePath().normalize(),
                dryRun,
                rules == null ? null : Path.of(rules)
            );
            return;
        }
        if (map != null && !learn.isEmpty()) {
            MapLearn.run(Path.of(map), learn, Path.of(out != null ? out : "learned.json"));
            return;
        }
        if (map != null && explain != null) {
            MapExplain.run(Path.of(map), Path.of(explain));
            return;
        }
        if (
            pos.size() != 2 ||
            !knownNames(names) ||
            (fromNames != null && !knownNames(fromNames)) ||
            (toNames != null && !knownNames(toNames))
        ) {
            System.err.println(
                "usage: java tools/port/names/VersionMap.java <from> <to> [--names mojmap|yarn|mcp[:channel]] [--from-names] [--to-names] [--out map.json]"
            );
            System.exit(2);
        }
        long t0 = System.nanoTime();
        // Same version with two namespaces converts a source tree (yarn to official, for example);
        // different versions with one namespace is the usual rename/move map.
        String aNames = fromNames != null ? fromNames : names, bNames = toNames != null ? toNames : names;
        Table a = Table.load(pos.get(0), aNames);
        Table b = Table.load(pos.get(1), bNames);
        Diff d = Diff.of(a, b);
        Path target = out != null
            ? Path.of(out)
            : cache.resolve("map-" + names + "-" + pos.get(0) + "-to-" + pos.get(1) + ".json");
        Files.createDirectories(target.toAbsolutePath().getParent());
        String json = d.json(pos.get(0), pos.get(1), names);
        // --hierarchy: each game class's supertypes in the target's names (Mojang's), read from the client jar
        // and its official mappings, so a member a class inherits or overrides is found on the supertype the mappings declare it on.
        if (hierarchy && bNames.equals("mojmap")) {
            StringBuilder supers = new StringBuilder(",\n  \"supers\": {");
            boolean first = true;
            for (var c : hierarchy(pos.get(1)).entrySet()) {
                List<String> up = c.getValue();
                if (up.isEmpty()) continue;
                supers.append(first ? "\n    " : ",\n    ").append(Diff.q(c.getKey())).append(": [");
                for (int i = 0; i < up.size(); i++) supers.append(i == 0 ? "" : ", ").append(Diff.q(up.get(i)));
                supers.append("]");
                first = false;
            }
            supers.append("\n  }");
            json = json.substring(0, json.lastIndexOf("\n}")) + supers + "\n}\n";
        }
        Files.writeString(target, json, StandardCharsets.UTF_8);
        System.out.printf(
            "map %s -> %s (%s): classes %d renamed or moved, %d removed; methods %d renamed, %d moved, " +
            "%d signature changes, %d removed; fields %d renamed, %d removed (%.1fs)%n",
            pos.get(0),
            pos.get(1),
            names,
            d.classRenamed.size(),
            d.classRemoved.size(),
            d.methodRenamed.size(),
            d.methodMoved.size(),
            d.methodSignature.size(),
            d.methodRemoved.size(),
            d.fieldRenamed.size(),
            d.fieldRemoved.size(),
            (System.nanoTime() - t0) / 1e9
        );
        System.out.println("        " + target);
    }

    /**
     * One release's intermediary names in Mojang's: classes (intermediary -> Mojang, slash form) and members by their
     * intermediary name alone (method_N and field_N name one member in the whole game) as [owner, name, desc].
     */
    static String intermediaryJson(Table t) {
        StringBuilder sb = new StringBuilder("{\n  \"classes\": {");
        boolean first = true;
        for (var e : new TreeMap<>(t.classes).entrySet()) {
            sb.append(first ? "\n    " : ",\n    ").append(Diff.q(e.getKey())).append(": ").append(Diff.q(e.getValue()));
            first = false;
        }
        sb.append("\n  },\n  \"members\": {");
        first = true;
        // An overriding method shares its intermediary (and Mojang) name with the one it overrides: written once.
        Set<String> written = new HashSet<>();
        for (Map<String, Member> kind : List.of(t.methods, t.fields)) {
            for (var e : new TreeMap<>(kind).entrySet()) {
                String key = e.getKey();
                String name = key.substring(key.lastIndexOf('.') + 1);
                if (!name.startsWith("method_") && !name.startsWith("field_")) continue;
                if (!written.add(name)) continue;
                Member m = e.getValue();
                sb.append(first ? "\n    " : ",\n    ").append(Diff.q(name)).append(": [");
                sb.append(Diff.q(m.owner())).append(", ").append(Diff.q(m.name())).append(", ").append(Diff.q(m.desc())).append("]");
                first = false;
            }
        }
        return sb.append("\n  }\n}\n").toString();
    }

    /**
     * Each game class's superclass and interfaces in Mojang's names (slash form), read from the release's client jar
     * with its official mappings (Table.load has cached the version json and the mappings by now). java/lang/Object
     * is left out.
     */
    static Map<String, List<String>> hierarchy(String version) throws Exception {
        String versionJson = Files.readString(cache.resolve("version-" + version + ".json"), StandardCharsets.UTF_8);
        int downloads = versionJson.indexOf("\"downloads\"");
        String jarUrl = jsonString(versionJson, "url", versionJson.indexOf("\"client\"", downloads));
        Path jar = fetch(jarUrl, "client-" + version + ".jar");
        int dl = versionJson.indexOf("\"client_mappings\"");
        String mappings = fetchText(jsonString(versionJson, "url", dl), "client-" + version + ".txt");
        Map<String, String> names = Proguard.parse(mappings).classes;
        Map<String, List<String>> out = new TreeMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (var entries = zip.entries(); entries.hasMoreElements();) {
                ZipEntry e = entries.nextElement();
                if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF/")) continue;
                List<String> types = supertypes(zip.getInputStream(e).readAllBytes());
                if (types.isEmpty()) continue;
                List<String> up = new ArrayList<>();
                for (String t : types.subList(1, types.size())) if (!t.equals("java/lang/Object")) up.add(names.getOrDefault(t, t));
                out.put(names.getOrDefault(types.get(0), types.get(0)), up);
            }
        }
        return out;
    }

    /** A class file's own name, then its superclass and interfaces (slash form); empty when it is no class file. */
    static List<String> supertypes(byte[] b) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
        if (in.readInt() != 0xCAFEBABE) return List.of();
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        String[] utf = new String[count];
        int[] classRef = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf[i] = in.readUTF();
                case 7 -> classRef[i] = in.readUnsignedShort();
                case 8, 16, 19, 20 -> in.readUnsignedShort();
                case 15 -> in.skipBytes(3);
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                case 5, 6 -> {
                    in.skipBytes(8);
                    i++;
                }
                default -> throw new IOException("unknown constant pool tag " + tag);
            }
        }
        in.readUnsignedShort();
        List<String> out = new ArrayList<>();
        out.add(utf[classRef[in.readUnsignedShort()]]);
        int sup = in.readUnsignedShort();
        out.add(sup == 0 ? "java/lang/Object" : utf[classRef[sup]]);
        int interfaces = in.readUnsignedShort();
        for (int i = 0; i < interfaces; i++) out.add(utf[classRef[in.readUnsignedShort()]]);
        return out;
    }

    // ------------------------------------------------------------------ downloads

    static Path fetch(String url, String name) throws IOException, InterruptedException {
        Path p = cache.resolve(name);
        if (Files.isRegularFile(p)) {
            if (Files.size(p) > 0) {
                return p;
            }
            // An empty file left by a failed write. Only then: a run beside this one may have just moved its download
            // in, and deleting that broke its reads (a NeoForge map lost lwjgl-opengl to it in a cloud sweep).
            Files.deleteIfExists(p);
        }
        Files.createDirectories(cache);
        HttpResponse<byte[]> r;
        try {
            r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException failed) {
            // The client's own messages name no address (a refused connection's is empty).
            throw new IOException("download of " + url + " failed: " + failed, failed);
        }
        if (r.statusCode() != 200) {
            throw new IOException("HTTP " + r.statusCode() + " for " + url);
        }
        Path tmp = Files.createTempFile(cache, "dl", ".part");
        Files.write(tmp, r.body());
        try {
            Files.move(tmp, p);
        } catch (FileAlreadyExistsException another) {
            Files.delete(tmp); // a run beside this one fetched the same file first; replacing it could break its reads
        }
        return p;
    }

    static String fetchText(String url, String name) throws IOException, InterruptedException {
        return Files.readString(fetch(url, name), StandardCharsets.UTF_8);
    }

    static String zipEntry(Path jar, String entry) throws IOException {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry(entry);
            if (e == null) {
                throw new IOException(entry + " not in " + jar);
            }
            return new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Tiny JSON value lookup, enough for Mojang's and Fabric's metadata. */
    static String jsonString(String json, String key, int from) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find(from) ? m.group(1) : null;
    }

    // ------------------------------------------------------------------ one version's names

    record Member(String owner, String name, String desc, String interDesc) {}

    static final class Table {

        final String version;
        String lane = "inter"; // the key space: srg (Forge SRG ids) or inter (Fabric intermediary ids)
        String mappings; // the published artifacts these names came from (the mcp lane's provenance)
        final Map<String, String> classes = new HashMap<>(); // intermediary -> named
        final Map<String, Member> methods = new HashMap<>(); // key -> named
        final Map<String, Member> fields = new HashMap<>();
        final Map<String, List<String>> methodsByInterName = new HashMap<>();

        Table(String version) {
            this.version = version;
        }

        /** Ids assigned once and never reused: intermediary's method_/field_ and SRG's func_/field_. */
        static boolean stableId(String interName) {
            return interName.startsWith("method_") || interName.startsWith("field_") || interName.startsWith("func_");
        }

        static String key(String interOwner, String interName, String interDesc) {
            return stableId(interName) ? interOwner + "." + interName : interOwner + "." + interName + interDesc;
        }

        /** Stable method ids by bare name, so a member moved to another owner is found when its key misses. */
        void indexMoved() {
            for (String k : methods.keySet()) {
                String name = k.substring(k.indexOf('.') + 1);
                if (name.startsWith("method_") || name.startsWith("func_")) {
                    methodsByInterName.computeIfAbsent(name, x -> new ArrayList<>()).add(k);
                }
            }
        }

        static Table load(String version, String names) throws Exception {
            if (names.equals("mcp") || names.startsWith("mcp:")) {
                return loadMcp(version, names.length() > 4 ? names.substring(4) : null);
            }
            String manifest = fetchText(
                "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
                "version_manifest_v2.json"
            );
            int at = manifest.indexOf("\"id\": \"" + version + "\"");
            if (at < 0) {
                at = manifest.indexOf("\"id\":\"" + version + "\"");
            }
            if (at < 0) {
                throw new IOException("Minecraft " + version + " is not in Mojang's version list");
            }
            String versionJson = fetchText(jsonString(manifest, "url", at), "version-" + version + ".json");
            Path inter;
            try {
                inter = fetch(
                    "https://maven.fabricmc.net/net/fabricmc/intermediary/" +
                    version +
                    "/intermediary-" +
                    version +
                    "-v2.jar",
                    "intermediary-" + version + "-v2.jar"
                );
            } catch (IOException e) {
                throw new IOException(
                    "Minecraft " +
                    version +
                    " has no published intermediary (versions from 26.1 ship " +
                    "unobfuscated); this map covers obfuscated versions only for now (" + e.getMessage() + ")",
                    e
                );
            }
            Tiny it = Tiny.parse(zipEntry(inter, "mappings/mappings.tiny")); // official, intermediary
            Table t = new Table(version);
            Map<String, String> obfToInter = it.classes;
            if (names.equals("yarn")) {
                String meta = fetchText(
                    "https://meta.fabricmc.net/v2/versions/yarn/" + version,
                    "yarn-meta-" + version + ".json"
                );
                String yv = jsonString(meta, "version", 0);
                if (yv == null) {
                    throw new IOException("no yarn for " + version);
                }
                Path y = fetch(
                    "https://maven.fabricmc.net/net/fabricmc/yarn/" + yv + "/yarn-" + yv + "-v2.jar",
                    "yarn-" + yv + "-v2.jar"
                );
                Tiny yt = Tiny.parse(zipEntry(y, "mappings/mappings.tiny")); // intermediary, named
                Map<String, String> interToNamed = yt.classes;
                for (String obf : obfToInter.keySet()) {
                    String in = obfToInter.get(obf);
                    t.classes.put(in, interToNamed.getOrDefault(in, in));
                }
                fill(
                    t,
                    it,
                    obfToInter,
                    (owner, name, interDesc, isMethod) -> {
                        String named =
                            (isMethod ? yt.methods : yt.fields).get(owner + "." + name + (isMethod ? interDesc : ""));
                        if (named == null && !isMethod) {
                            named = yt.fieldsNoDesc.get(owner + "." + name);
                        }
                        return named != null ? named : name;
                    },
                    interToNamed
                );
            } else {
                int dl = versionJson.indexOf("\"client_mappings\"");
                if (dl < 0) {
                    throw new IOException("Minecraft " + version + " publishes no official mappings");
                }
                String proguard = fetchText(jsonString(versionJson, "url", dl), "client-" + version + ".txt");
                int sdl = versionJson.indexOf("\"server_mappings\"");
                Proguard pg = Proguard.parse(proguard);
                if (sdl >= 0) {
                    pg.merge(
                        Proguard.parse(fetchText(jsonString(versionJson, "url", sdl), "server-" + version + ".txt"))
                    );
                }
                Map<String, String> interToNamed = new HashMap<>();
                for (var e : obfToInter.entrySet()) {
                    interToNamed.put(e.getValue(), pg.classes.getOrDefault(e.getKey(), e.getKey()));
                }
                t.classes.putAll(interToNamed);
                // Official names keyed by obf owner + obf name + obf descriptor, joined to intermediary.
                Map<String, String> interToObf = new HashMap<>();
                obfToInter.forEach((o, i) -> interToObf.put(i, o));
                fill(t, it, obfToInter, (owner, name, interDesc, isMethod) -> null, interToNamed);
                // Second pass: the readable name for each member comes from the obf side.
                for (var entry : it.memberRows) {
                    String named = entry.method
                        ? pg.methods.get(entry.obfOwner + "." + entry.obfName + entry.obfDesc)
                        : pg.fields.get(entry.obfOwner + "." + entry.obfName);
                    if (named == null) {
                        continue;
                    }
                    String interOwner = obfToInter.getOrDefault(entry.obfOwner, entry.obfOwner);
                    String interDesc = Tiny.remapDesc(entry.obfDesc, obfToInter);
                    Map<String, Member> table = entry.method ? t.methods : t.fields;
                    String k = key(interOwner, entry.interName, interDesc);
                    Member m = table.get(k);
                    if (m != null) {
                        table.put(k, new Member(m.owner(), named, m.desc(), m.interDesc()));
                    }
                }
            }
            t.indexMoved();
            return t;
        }

        interface Namer {
            String name(String interOwner, String interName, String interDesc, boolean method);
        }

        static void fill(
            Table t,
            Tiny it,
            Map<String, String> obfToInter,
            Namer namer,
            Map<String, String> interToNamed
        ) {
            for (var row : it.memberRows) {
                String interOwner = obfToInter.getOrDefault(row.obfOwner, row.obfOwner);
                String interDesc = Tiny.remapDesc(row.obfDesc, obfToInter);
                String named = namer.name(interOwner, row.interName, interDesc, row.method);
                if (named == null) {
                    named = stableId(row.interName) ? row.obfName : row.interName;
                }
                String namedDesc = Tiny.remapDesc(interDesc, interToNamed);
                Member m = new Member(interToNamed.getOrDefault(interOwner, interOwner), named, namedDesc, interDesc);
                (row.method ? t.methods : t.fields).put(key(interOwner, row.interName, interDesc), m);
            }
        }

        /**
         * One version's names the pre-1.14.4 way: Forge's SRG table for identity (joined.srg in
         * de.oceanlabs.mcp:mcp's srg zips, published to 1.12.2; joined.tsrg in mcp_config from 1.12.2)
         * and MCP's CSVs for the readable side. The channel names the published name set the source
         * was written against (stable_39, snapshot_20171003); the default is the last stable set
         * published for the release.
         */
        static Table loadMcp(String version, String channel) throws Exception {
            McpNames mn = mcpNames(version, channel);
            Table t = fromSrg(version, mn.keys(), mn.csv());
            t.mappings = mn.source();
            return t;
        }

        /**
         * One release's SRG data in the shapes its readers use: keys (obfuscated to SRG id), names
         * (obfuscated to MCP name, in Proguard's layout), the name CSVs, and the artifacts it all
         * came from.
         */
        record McpNames(Tiny keys, Proguard names, Map<String, String> csv, String source) {}

        static McpNames mcpNames(String version, String channel) throws Exception {
            String srgMeta = fetchText(FORGE_MAVEN + "de/oceanlabs/mcp/mcp/maven-metadata.xml", "mcp-meta.xml");
            Tiny rows;
            String srgFrom;
            if (srgMeta.contains("<version>" + version + "</version>")) {
                Path z = fetch(
                    FORGE_MAVEN + "de/oceanlabs/mcp/mcp/" + version + "/mcp-" + version + "-srg.zip",
                    "mcp-" + version + "-srg.zip"
                );
                rows = Srg.srg(zipEntry(z, "joined.srg"));
                srgFrom = "mcp " + version + " joined.srg";
            } else {
                Path z = fetch(
                    FORGE_MAVEN + "de/oceanlabs/mcp/mcp_config/" + version + "/mcp_config-" + version + ".zip",
                    "mcp_config-" + version + ".zip"
                );
                rows = Srg.tsrg(zipEntry(z, "config/joined.tsrg"));
                srgFrom = "mcp_config " + version + " joined.tsrg";
            }
            String group = channel != null && channel.startsWith("snapshot_") ? "mcp_snapshot" : "mcp_stable";
            String namesMeta = fetchText(FORGE_MAVEN + "de/oceanlabs/mcp/" + group + "/maven-metadata.xml", group + "-meta.xml");
            String artifact = mcpArtifact(namesMeta, version, channel);
            if (artifact == null) {
                throw new IOException(
                    "no MCP " +
                    group +
                    " names published for " +
                    version +
                    " (a legacy source can only be read in the names it was written against)"
                );
            }
            Path nz = fetch(
                FORGE_MAVEN + "de/oceanlabs/mcp/" + group + "/" + artifact + "/" + group + "-" + artifact + ".zip",
                group + "-" + artifact + ".zip"
            );
            Map<String, String> csv = Srg.csv(zipEntry(nz, "fields.csv"), zipEntry(nz, "methods.csv"));
            return new McpNames(rows, Srg.proguardOf(rows, csv), csv, srgFrom + ", " + group + " " + artifact);
        }

        /** The published MCP name artifact for a release: an explicit channel wins, else the last stable set. */
        static String mcpArtifact(String metadata, String version, String channel) {
            List<String> all = new ArrayList<>();
            Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(metadata);
            while (m.find()) {
                all.add(m.group(1));
            }
            if (channel != null) {
                String prefix = channel.substring(channel.indexOf('_') + 1) + "-";
                for (String a : all) {
                    if (a.startsWith(prefix)) {
                        return a;
                    }
                }
                return null;
            }
            int secondDot = version.indexOf('.', version.indexOf('.') + 1);
            String minor = secondDot < 0 ? version : version.substring(0, secondDot); // 39-1.12 serves 1.12.2
            String best = null, bestForMinor = null;
            long bestN = -1, bestMinorN = -1;
            for (String a : all) {
                int dash = a.indexOf('-');
                if (dash < 0) {
                    continue;
                }
                long n;
                try {
                    n = Long.parseLong(a.substring(0, dash));
                } catch (NumberFormatException e) {
                    continue;
                }
                String suffix = a.substring(dash + 1);
                if (suffix.equals(version) && n > bestN) {
                    best = a;
                    bestN = n;
                } else if (suffix.equals(minor) && n > bestMinorN) {
                    bestForMinor = a;
                    bestMinorN = n;
                }
            }
            return best != null ? best : bestForMinor;
        }

        /**
         * A table from SRG rows and MCP names. SRG class names are already deobfuscated and MCP never
         * renames a class, so a class's named side is its SRG name; a member MCP never named keeps its
         * SRG id, which is what legacy sources call it by.
         */
        static Table fromSrg(String version, Tiny rows, Map<String, String> names) {
            Table t = new Table(version);
            t.lane = "srg";
            Map<String, String> identity = new HashMap<>();
            for (String srg : rows.classes.values()) {
                identity.put(srg, srg);
            }
            t.classes.putAll(identity);
            fill(t, rows, rows.classes, (owner, name, desc, method) -> names.getOrDefault(name, name), identity);
            t.indexMoved();
            return t;
        }
    }

    // ------------------------------------------------------------------ tiny v2

    static final class Tiny {

        record Row(boolean method, String obfOwner, String obfName, String obfDesc, String interName) {}

        final Map<String, String> classes = new HashMap<>(); // ns0 class -> ns1 class
        final Map<String, String> methods = new HashMap<>(); // ns0 owner.name+desc -> ns1 name
        final Map<String, String> fields = new HashMap<>(); // ns0 owner.name -> ns1 name
        final Map<String, String> fieldsNoDesc = fields;
        final List<Row> memberRows = new ArrayList<>();

        /** One member in both shapes its readers use: the lookup maps and the row list. */
        void add(boolean method, String obfOwner, String obfName, String obfDesc, String interName) {
            if (method) {
                methods.put(obfOwner + "." + obfName + obfDesc, interName);
            } else {
                fields.put(obfOwner + "." + obfName, interName);
            }
            memberRows.add(new Row(method, obfOwner, obfName, obfDesc, interName));
        }

        static Tiny parse(String text) {
            Tiny t = new Tiny();
            String owner0 = null;
            for (String line : text.split("\r?\n")) {
                if (line.startsWith("c\t")) {
                    String[] p = line.split("\t", -1);
                    owner0 = p[1];
                    t.classes.put(p[1], p[2].isEmpty() ? p[1] : p[2]);
                } else if (owner0 != null && (line.startsWith("\tm\t") || line.startsWith("\tf\t"))) {
                    String[] p = line.split("\t", -1);
                    boolean method = p[1].equals("m");
                    String desc = p[2], n0 = p[3], n1 = p.length > 4 && !p[4].isEmpty() ? p[4] : n0;
                    t.add(method, owner0, n0, desc, n1);
                }
            }
            return t;
        }

        static final Pattern CLASS_IN_DESC = Pattern.compile("L([^;]+);");

        static String remapDesc(String desc, Map<String, String> classes) {
            Matcher m = CLASS_IN_DESC.matcher(desc);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(
                    sb,
                    Matcher.quoteReplacement("L" + classes.getOrDefault(m.group(1), m.group(1)) + ";")
                );
            }
            m.appendTail(sb);
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------ Mojang ProGuard

    static final class Proguard {

        final Map<String, String> classes = new HashMap<>(); // obf -> named (slashes)
        final Map<String, String> methods = new HashMap<>(); // obfOwner.obfName+obfDesc -> named
        final Map<String, String> fields = new HashMap<>(); // obfOwner.obfName -> named

        void merge(Proguard o) {
            o.classes.forEach(classes::putIfAbsent);
            o.methods.forEach(methods::putIfAbsent);
            o.fields.forEach(fields::putIfAbsent);
        }

        static Proguard parse(String text) {
            // Pass 1: classes, so member descriptors can be written in obf names.
            Map<String, String> namedToObf = new HashMap<>();
            Proguard pg = new Proguard();
            for (String line : text.split("\r?\n")) {
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(" ")) {
                    continue;
                }
                int arrow = line.indexOf(" -> ");
                String named = line.substring(0, arrow).replace('.', '/');
                String obf = line.substring(arrow + 4, line.length() - 1).replace('.', '/');
                pg.classes.put(obf, named);
                namedToObf.put(named, obf);
            }
            String owner = null;
            for (String line : text.split("\r?\n")) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (!line.startsWith(" ")) {
                    owner = line.substring(line.indexOf(" -> ") + 4, line.length() - 1).replace('.', '/');
                    continue;
                }
                String s = line.trim();
                if (s.startsWith("#")) {
                    continue;
                }
                int arrow = s.indexOf(" -> ");
                String left = s.substring(0, arrow), obfName = s.substring(arrow + 4);
                int paren = left.indexOf('(');
                if (paren < 0) {
                    String[] tp = left.split(" ");
                    pg.fields.put(owner + "." + obfName, tp[tp.length - 1]);
                    continue;
                }
                String head = left.replaceFirst("^\\d+:\\d+:", "");
                paren = head.indexOf('(');
                int close = head.indexOf(')');
                String[] rn = head.substring(0, paren).split(" ");
                String ret = rn[0], name = rn[1];
                String params = head.substring(paren + 1, close);
                StringBuilder desc = new StringBuilder("(");
                if (!params.isEmpty()) {
                    for (String p : params.split(",")) {
                        desc.append(type(p, namedToObf));
                    }
                }
                desc.append(')').append(type(ret, namedToObf));
                pg.methods.put(owner + "." + obfName + desc, name);
            }
            return pg;
        }

        static String type(String t, Map<String, String> namedToObf) {
            int dims = 0;
            while (t.endsWith("[]")) {
                dims++;
                t = t.substring(0, t.length() - 2);
            }
            String base =
                switch (t) {
                    case "void" -> "V";
                    case "boolean" -> "Z";
                    case "byte" -> "B";
                    case "char" -> "C";
                    case "short" -> "S";
                    case "int" -> "I";
                    case "long" -> "J";
                    case "float" -> "F";
                    case "double" -> "D";
                    default -> {
                        String slash = t.replace('.', '/');
                        yield "L" + namedToObf.getOrDefault(slash, slash) + ";";
                    }
                };
            return "[".repeat(dims) + base;
        }
    }

    // ------------------------------------------------------------------ SRG (MCP)

    /**
     * Forge's SRG mappings: the stable identity of the releases intermediary never covered. Both
     * spellings (the joined.srg text MCP shipped and mcp_config's joined.tsrg) parse into the row
     * shape the other lanes use, with the SRG id standing where the intermediary id stands. SRG field
     * rows carry no type, so a field's descriptor stays empty in this lane.
     */
    static final class Srg {

        static Tiny srg(String text) {
            Tiny t = new Tiny();
            for (String line : text.split("\r?\n")) {
                String[] p = line.split(" ");
                switch (p[0]) {
                    case "CL:" -> {
                        if (p.length == 3) {
                            t.classes.put(p[1], p[2]);
                        }
                    }
                    case "FD:" -> {
                        if (p.length == 3) {
                            int s = p[1].lastIndexOf('/');
                            t.add(
                                false,
                                p[1].substring(0, s),
                                p[1].substring(s + 1),
                                "",
                                p[2].substring(p[2].lastIndexOf('/') + 1)
                            );
                        }
                    }
                    case "MD:" -> {
                        if (p.length == 5) {
                            int s = p[1].lastIndexOf('/');
                            t.add(true, p[1].substring(0, s), p[1].substring(s + 1), p[2], p[3].substring(p[3].lastIndexOf('/') + 1));
                        }
                    }
                    default -> {} // PK: package rows name nothing a class row does not
                }
            }
            return t;
        }

        static Tiny tsrg(String text) {
            Tiny t = new Tiny();
            String owner = null;
            for (String line : text.split("\r?\n")) {
                if (line.isEmpty() || line.startsWith("tsrg2")) {
                    continue;
                }
                if (line.startsWith("\t")) {
                    if (owner == null) {
                        continue;
                    }
                    String[] p = line.trim().split(" ");
                    if (p.length == 3 && p[1].startsWith("(")) {
                        t.add(true, owner, p[0], p[1], p[2]);
                    } else if (p.length >= 2) {
                        t.add(false, owner, p[0], p.length == 3 ? p[1] : "", p[p.length - 1]);
                    }
                } else {
                    String[] p = line.trim().split(" ");
                    if (p.length >= 2) {
                        owner = p[0];
                        t.classes.put(p[0], p[1]);
                    }
                }
            }
            return t;
        }

        /** The same data in Proguard's layout (obfuscated to readable), for readers that walk a jar. */
        static Proguard proguardOf(Tiny rows, Map<String, String> csv) {
            Proguard pg = new Proguard();
            pg.classes.putAll(rows.classes);
            for (Tiny.Row r : rows.memberRows) {
                String named = csv.getOrDefault(r.interName(), r.interName());
                if (r.method()) {
                    pg.methods.put(r.obfOwner() + "." + r.obfName() + r.obfDesc(), named);
                } else {
                    pg.fields.put(r.obfOwner() + "." + r.obfName(), named);
                }
            }
            return pg;
        }

        /** Searge id to MCP name from the first two columns only; the quoted javadoc column is never read. */
        static Map<String, String> csv(String... texts) {
            Map<String, String> names = new HashMap<>();
            for (String text : texts) {
                for (String line : text.split("\r?\n")) {
                    int a = line.indexOf(','), b = a < 0 ? -1 : line.indexOf(',', a + 1);
                    if (b > a) {
                        String id = line.substring(0, a);
                        if (id.startsWith("func_") || id.startsWith("field_")) {
                            names.put(id, line.substring(a + 1, b));
                        }
                    }
                }
            }
            return names;
        }
    }

    // ------------------------------------------------------------------ the seam

    /**
     * The release both name lanes share: the last Forge SRG release and the first with Mojang
     * mappings. Kept equal to Route.FIRST_MAPPED. Chains load this release in SRG names and
     * translate keys past it; the index measures the translation as its own step.
     */
    static final String SEAM = "1.14.4";

    /** The pseudo-step a seam-crossing chain records the MCP to Mojang key translation under. */
    static final String BRIDGE_STEP = SEAM + " -> " + SEAM;

    /** Numeric dotted release order; chain versions are plain releases, so no pre-release handling. */
    static int releaseCompare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int l = i < x.length ? leadingNumber(x[i]) : 0, r = i < y.length ? leadingNumber(y[i]) : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    static int leadingNumber(String part) {
        int i = 0;
        while (i < part.length() && Character.isDigit(part.charAt(i))) {
            i++;
        }
        return i == 0 ? 0 : Integer.parseInt(part.substring(0, i));
    }

    /**
     * Mojang's mappings for a version, client and server separately: a member can live on either
     * side, and the two obfuscations do not always agree. A release without published mappings
     * yields empty tables.
     */
    static Proguard[] proguardsFor(String version) throws Exception {
        String manifest = fetchText(
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
            "version_manifest_v2.json"
        );
        int atManifest = manifest.indexOf("\"id\": \"" + version + "\"");
        if (atManifest < 0) {
            atManifest = manifest.indexOf("\"id\":\"" + version + "\"");
        }
        String versionJson = fetchText(jsonString(manifest, "url", atManifest), "version-" + version + ".json");
        int cdl = versionJson.indexOf("\"client_mappings\"");
        int sdl = versionJson.indexOf("\"server_mappings\"");
        Proguard client = cdl < 0
            ? new Proguard()
            : Proguard.parse(fetchText(jsonString(versionJson, "url", cdl), "client-" + version + ".txt"));
        Proguard server = sdl < 0
            ? new Proguard()
            : Proguard.parse(fetchText(jsonString(versionJson, "url", sdl), "server-" + version + ".txt"));
        return new Proguard[] {client, server};
    }

    /** Official member triples (owner, name, descriptor) a version's own mappings know. */
    static Set<String> officialTriples(Proguard[] pgs) {
        Set<String> triples = new HashSet<>();
        for (Proguard pg : pgs) {
            for (var e : pg.methods.entrySet()) {
                String key = e.getKey();
                int dot = key.indexOf('.'), paren = key.indexOf('(');
                String obfOwner = key.substring(0, dot);
                String owner = pg.classes.getOrDefault(obfOwner, obfOwner);
                triples.add(owner + "\0" + e.getValue() + "\0" + Tiny.remapDesc(key.substring(paren), pg.classes));
            }
            for (var e : pg.fields.entrySet()) {
                String key = e.getKey();
                String obfOwner = key.substring(0, key.indexOf('.'));
                String owner = pg.classes.getOrDefault(obfOwner, obfOwner);
                triples.add(owner + "\0" + e.getValue() + "\0");
            }
        }
        return triples;
    }

    /** Official class names a version's own mappings know. */
    static Set<String> officialClasses(Proguard[] pgs) {
        Set<String> names = new HashSet<>();
        for (Proguard pg : pgs) {
            names.addAll(pg.classes.values());
        }
        return names;
    }

    /**
     * The seam translation: SRG keys to intermediary keys at 1.14.4, joined through the obfuscated
     * names both lanes publish (mcp_config's joined.tsrg and intermediary's tiny). Members join on
     * owner, name and descriptor; fields carry no descriptor in SRG, so they join on owner and name.
     * Classes join on the obfuscated class. Forward reads members and classes; a backport reads the
     * Back maps. Built from cached downloads; two small file parses, no network when cached.
     */
    static final class Bridge {
        final Map<String, String> classes = new LinkedHashMap<>(), classesBack = new LinkedHashMap<>();
        final Map<String, String> members = new LinkedHashMap<>(), membersBack = new LinkedHashMap<>();
        /**
         * Fallback identities for members and classes the exact join misses: the retroactive
         * intermediary drops some overloads, so a notch name can be absent there while present in
         * both jars. SRG key to official owner, name and descriptor at the seam, guarded: a notch
         * name resolving differently on the client and server sides yields no fallback. The step
         * file pairs by these identities; chains use them to rescue what the exact join misses.
         * The reverse maps serve backports.
         */
        final Map<String, String> membersOfficial = new LinkedHashMap<>(), membersBackOfficial = new LinkedHashMap<>();
        final Map<String, String> classesOfficial = new LinkedHashMap<>(), classesBackOfficial = new LinkedHashMap<>();

        static Bridge at() throws Exception {
            Tiny srg = Table.mcpNames(SEAM, null).keys();
            Path inter = fetch(
                "https://maven.fabricmc.net/net/fabricmc/intermediary/" + SEAM + "/intermediary-" + SEAM + "-v2.jar",
                "intermediary-" + SEAM + "-v2.jar"
            );
            Tiny it = Tiny.parse(zipEntry(inter, "mappings/mappings.tiny"));
            Proguard[] pgs = proguardsFor(SEAM);
            return join(srg, it, pgs[0], pgs[1]);
        }

        /**
         * The pure join both lanes share: unit-testable without downloads. Callers pass the
         * 1.14.4 artifacts (mcp_config joined.tsrg rows, intermediary tiny, Mojang client and
         * server mappings); every map out is keyed through the shared obfuscated names.
         */
        static Bridge join(Tiny srg, Tiny it, Proguard client, Proguard server) {
            Map<String, String[]> byObf = new HashMap<>();
            for (Tiny.Row r : it.memberRows) {
                byObf.put(r.obfOwner() + "\0" + r.obfName() + "\0" + r.obfDesc(), new String[] {
                    it.classes.getOrDefault(r.obfOwner(), r.obfOwner()), r.interName()
                });
            }
            Map<String, String[]> fieldsByObf = new HashMap<>();
            for (Tiny.Row r : it.memberRows) {
                if (!r.method()) {
                    fieldsByObf.putIfAbsent(r.obfOwner() + "\0" + r.obfName(), new String[] {
                        it.classes.getOrDefault(r.obfOwner(), r.obfOwner()), r.interName()
                    });
                }
            }
            Bridge b = new Bridge();
            for (var e : srg.classes.entrySet()) {
                String named = it.classes.get(e.getKey());
                if (named == null) {
                    continue;
                }
                b.classes.put(e.getValue(), named);
                b.classesBack.put(named, e.getValue());
            }
            for (Tiny.Row r : srg.memberRows) {
                String seargeOwner = srg.classes.getOrDefault(r.obfOwner(), r.obfOwner());
                String srgKey = Table.key(seargeOwner, r.interName(), Tiny.remapDesc(r.obfDesc(), srg.classes));
                String[] found = r.method()
                    ? byObf.get(r.obfOwner() + "\0" + r.obfName() + "\0" + r.obfDesc())
                    : fieldsByObf.get(r.obfOwner() + "\0" + r.obfName());
                if (found == null) {
                    continue;
                }
                String interKey = Table.key(found[0], found[1], Tiny.remapDesc(r.obfDesc(), it.classes));
                b.members.put(srgKey, interKey);
                b.membersBack.put(interKey, srgKey);
            }
            // Fallback identities for everything the exact join missed, resolved through Mojang's
            // own mappings for both sides (the notch names coincide here; where they do not, the
            // guards below yield no fallback and the member reads as removed, honestly).
            Map<String, String> obfToOfficial = new HashMap<>();
            client.classes.forEach((k, v) -> obfToOfficial.putIfAbsent(k, v));
            server.classes.forEach((k, v) -> obfToOfficial.putIfAbsent(k, v));
            for (Tiny.Row r : srg.memberRows) {
                String seargeOwner = srg.classes.getOrDefault(r.obfOwner(), r.obfOwner());
                String srgKey = Table.key(seargeOwner, r.interName(), Tiny.remapDesc(r.obfDesc(), srg.classes));
                String owner = officialOwner(r.obfOwner(), client, server);
                String name = officialName(r, client, server);
                if (owner != null && name != null) {
                    String okey = owner + "\0" + name + "\0" + Tiny.remapDesc(r.obfDesc(), obfToOfficial);
                    b.membersOfficial.put(srgKey, okey);
                    b.membersBackOfficial.putIfAbsent(okey, srgKey);
                }
                if (b.members.containsKey(srgKey)) {
                    continue;
                }
                String[] found = r.method()
                    ? byObf.get(r.obfOwner() + "\0" + r.obfName() + "\0" + r.obfDesc())
                    : fieldsByObf.get(r.obfOwner() + "\0" + r.obfName());
                if (found == null) {
                    continue;
                }
                String interKey = Table.key(found[0], found[1], Tiny.remapDesc(r.obfDesc(), it.classes));
                b.members.put(srgKey, interKey);
                b.membersBack.put(interKey, srgKey);
            }
            for (var e : srg.classes.entrySet()) {
                String official = officialOwner(e.getKey(), client, server);
                if (official != null) {
                    b.classesOfficial.put(e.getValue(), official);
                    b.classesBackOfficial.putIfAbsent(official, e.getValue());
                }
            }
            return b;
        }

        /** A notch owner's official name, or null when the client and server disagree. */
        static String officialOwner(String obf, Proguard client, Proguard server) {
            String c = client.classes.get(obf), s = server.classes.get(obf);
            if (c != null && s != null && !c.equals(s)) {
                return null;
            }
            return c != null ? c : s;
        }

        /** A notch member's official name, or null when the client and server disagree. */
        static String officialName(Tiny.Row r, Proguard client, Proguard server) {
            String key = r.method() ? r.obfOwner() + "." + r.obfName() + r.obfDesc() : r.obfOwner() + "." + r.obfName();
            String c = r.method() ? client.methods.get(key) : client.fields.get(key);
            String s = r.method() ? server.methods.get(key) : server.fields.get(key);
            if (c != null && s != null && !c.equals(s)) {
                return null;
            }
            return c != null ? c : s;
        }

        /**
         * Official identity for a constructor: neither SRG spelling rows them, so synthesize from
         * the class link. Null unless the key names a constructor whose owner links. A partially
         * remapped descriptor simply misses its target later, honestly.
         */
        String initForward(String srgKey, String seargeDesc) {
            int dot = srgKey.indexOf('.');
            if (dot < 0 || !srgKey.substring(dot + 1).startsWith("<init>")) {
                return null;
            }
            String official = classesOfficial.get(srgKey.substring(0, dot));
            if (official == null) {
                return null;
            }
            return official + "\0<init>\0" + Tiny.remapDesc(seargeDesc, classesOfficial);
        }

        /**
         * The SRG key for a constructor reached from the intermediary side: the intermediary
         * spelling rows them, so translate the owner through the class link and the descriptor
         * back into SRG names. Null unless the key names a constructor whose owner links back.
         */
        String initBack(String interKey, String interDesc) {
            int dot = interKey.indexOf('.');
            if (dot < 0 || !interKey.substring(dot + 1).startsWith("<init>")) {
                return null;
            }
            String searge = classesBack.get(interKey.substring(0, dot));
            if (searge == null) {
                return null;
            }
            return searge + ".<init>" + Tiny.remapDesc(interDesc, classesBack);
        }
    }

    /**
     * Lane-aware table loading for a chain: releases before the seam have no official names and
     * always read MCP names through SRG ids; 1.14.4 reads the requested lane (MCP for an MCP
     * source, official otherwise), except a chain reaching back past the seam always reads it in
     * SRG names so the bridge, not the retroactive intermediary, carries the crossing; later
     * releases read the target namespace (Mojang's when the source is MCP, since the bridge lands
     * in Mojang's names). The bridge between lanes is joined by key, not guessed.
     */
    static Table laneTable(String version, String names, String toNames, boolean srgSeam) throws Exception {
        boolean srg = releaseCompare(version, SEAM) < 0
            || (version.equals(SEAM) && (names.startsWith("mcp") || srgSeam));
        if (srg) {
            return Table.loadMcp(version, names.startsWith("mcp:") ? names.substring(4) : null);
        }
        String modern = names.startsWith("mcp") ? (toNames != null ? toNames : "mojmap") : names;
        return Table.load(version, modern);
    }

    // ------------------------------------------------------------------ the diff

    record Change(String owner, String from, String to, String desc, String newDesc, String newOwner) {}

    static final class Diff {

        final List<String[]> classRenamed = new ArrayList<>();
        final List<String> classRemoved = new ArrayList<>();
        final List<Change> methodRenamed = new ArrayList<>(), methodMoved = new ArrayList<>(), methodSignature =
            new ArrayList<>(), methodRemoved = new ArrayList<>(), fieldRenamed = new ArrayList<>(), fieldRemoved =
            new ArrayList<>();

        static boolean noise(String name) {
            return name.startsWith("lambda$") || name.contains("$") || name.equals("<clinit>");
        }

        static Diff of(Table a, Table b) {
            Diff d = new Diff();
            for (var e : a.classes.entrySet()) {
                String nb = b.classes.get(e.getKey());
                if (nb == null) {
                    d.classRemoved.add(e.getValue());
                } else if (!nb.equals(e.getValue())) {
                    d.classRenamed.add(new String[] { e.getValue(), nb });
                }
            }
            for (var e : a.methods.entrySet()) {
                Member ma = e.getValue();
                if (noise(ma.name())) {
                    continue;
                }
                Member mb = b.methods.get(e.getKey());
                if (mb == null) {
                    String interName = e.getKey().substring(e.getKey().indexOf('.') + 1);
                    List<String> elsewhere = b.methodsByInterName.getOrDefault(interName, List.of());
                    if (!elsewhere.isEmpty()) {
                        Member mv = b.methods.get(elsewhere.get(0));
                        d.methodMoved.add(
                            new Change(ma.owner(), ma.name(), mv.name(), ma.desc(), mv.desc(), mv.owner())
                        );
                    } else {
                        d.methodRemoved.add(new Change(ma.owner(), ma.name(), null, ma.desc(), null, null));
                    }
                    continue;
                }
                boolean renamed = !mb.name().equals(ma.name());
                boolean sig = !mb.interDesc().equals(ma.interDesc());
                if (renamed) {
                    d.methodRenamed.add(new Change(ma.owner(), ma.name(), mb.name(), ma.desc(), mb.desc(), mb.owner()));
                }
                if (sig) {
                    d.methodSignature.add(
                        new Change(ma.owner(), ma.name(), mb.name(), ma.desc(), mb.desc(), mb.owner())
                    );
                }
            }
            for (var e : a.fields.entrySet()) {
                Member fa = e.getValue();
                if (noise(fa.name())) {
                    continue;
                }
                Member fb = b.fields.get(e.getKey());
                if (fb == null) {
                    d.fieldRemoved.add(new Change(fa.owner(), fa.name(), null, fa.desc(), null, null));
                } else if (!fb.name().equals(fa.name())) {
                    d.fieldRenamed.add(new Change(fa.owner(), fa.name(), fb.name(), fa.desc(), fb.desc(), fb.owner()));
                }
            }
            Comparator<Change> byOwner = Comparator.comparing(Change::owner).thenComparing(Change::from);
            for (var l : List.of(
                d.methodRenamed,
                d.methodMoved,
                d.methodSignature,
                d.methodRemoved,
                d.fieldRenamed,
                d.fieldRemoved
            )) {
                l.sort(byOwner);
            }
            d.classRenamed.sort(Comparator.comparing(x -> x[0]));
            Collections.sort(d.classRemoved);
            return d;
        }

        static String q(String s) {
            if (s == null) {
                return "null";
            }
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }

        static String dots(String s) {
            return s == null ? null : s.replace('/', '.');
        }

        String json(String from, String to, String names) {
            StringBuilder sb = new StringBuilder("{\n");
            sb
                .append("  \"from\": ")
                .append(q(from))
                .append(", \"to\": ")
                .append(q(to))
                .append(", \"names\": ")
                .append(q(names))
                .append(",\n");
            sb.append("  \"classes\": [");
            for (int i = 0; i < classRenamed.size(); i++) {
                sb
                    .append(i == 0 ? "\n" : ",\n")
                    .append("    { \"from\": ")
                    .append(q(dots(classRenamed.get(i)[0])))
                    .append(", \"to\": ")
                    .append(q(dots(classRenamed.get(i)[1])))
                    .append(" }");
            }
            sb.append("\n  ],\n  \"classesRemoved\": [");
            for (int i = 0; i < classRemoved.size(); i++) {
                sb.append(i == 0 ? "\n    " : ",\n    ").append(q(dots(classRemoved.get(i))));
            }
            sb.append("\n  ],\n");
            list(sb, "methods", methodRenamed, true);
            list(sb, "methodsMoved", methodMoved, true);
            list(sb, "signatures", methodSignature, true);
            list(sb, "methodsRemoved", methodRemoved, false);
            list(sb, "fields", fieldRenamed, true);
            list(sb, "fieldsRemoved", fieldRemoved, false);
            sb.setLength(sb.length() - 2);
            return sb.append("\n}\n").toString();
        }

        static void list(StringBuilder sb, String name, List<Change> l, boolean full) {
            sb.append("  \"").append(name).append("\": [");
            for (int i = 0; i < l.size(); i++) {
                Change c = l.get(i);
                sb
                    .append(i == 0 ? "\n" : ",\n")
                    .append("    { \"owner\": ")
                    .append(q(dots(c.owner())))
                    .append(", \"from\": ")
                    .append(q(c.from()))
                    .append(", \"desc\": ")
                    .append(q(c.desc()));
                if (full) {
                    sb.append(", \"to\": ").append(q(c.to())).append(", \"newDesc\": ").append(q(c.newDesc()));
                    if (!c.newOwner().equals(c.owner())) {
                        sb.append(", \"newOwner\": ").append(q(dots(c.newOwner())));
                    }
                }
                sb.append(" }");
            }
            sb.append("\n  ],\n");
        }
    }

    // ------------------------------------------------------------------ explain a build log

    /** One javac error with the lines javac prints under it. */
    record Err(String file, int line, String message, String source, int caret, String symbol, String location) {}

    static List<Err> readErrors(Path log) throws IOException {
        // javac on Windows writes in the console's code page: bytes that are not UTF-8 never stop the repair (owo-lib).
        List<String> lines = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).lines().toList();
        // Gradle indents javac's lines in multi-project builds.
        // A path starts like one (Port.FILE): Gradle's summary repeats errors with the previous line glued on, and those are skipped.
        Pattern head = Pattern.compile(
            "^\\s*((?:[A-Za-z]:[\\\\/]|/)[^:\\r\\n]*?\\.java|[\\w.-]+(?:[\\\\/][\\w .-]+)*\\.java):(\\d+): error: (.*)$"
        );
        Map<String, Err> seen = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = head.matcher(lines.get(i));
            if (!m.find()) {
                continue;
            }
            String source = i + 1 < lines.size() ? lines.get(i + 1) : "";
            int caret = i + 2 < lines.size() ? lines.get(i + 2).indexOf('^') : -1;
            int indent = m.start(1);
            if (indent > 0 && source.startsWith(" ".repeat(indent))) {
                source = source.substring(indent);
            }
            if (caret >= indent) {
                caret -= indent;
            }
            String symbol = null, location = null, required = null, found = null;
            for (int j = i + 1; j < Math.min(lines.size(), i + 8); j++) {
                String s = lines.get(j).trim();
                if (head.matcher(lines.get(j)).find()) {
                    break;
                }
                if (s.startsWith("symbol:")) {
                    symbol = s.substring(7).trim();
                }
                if (s.startsWith("location:")) {
                    location = s.substring(9).trim();
                }
                if (s.startsWith("required:") && required == null) {
                    required = s.substring(9).trim();
                }
                if (s.startsWith("found:") && found == null) {
                    found = s.substring(6).trim();
                }
                if (s.startsWith("lower bounds:")) {
                    FOUND_TYPE.putIfAbsent(m.group(1) + ":" + m.group(2), s.substring(13).trim());
                }
            }
            if (required != null && found != null) {
                REQUIRED_FOUND.put(m.group(1) + ":" + m.group(2), new String[] { required, found });
            }
            // "no suitable method found for NAME(found)": each candidate javac lists, as its parameter list.
            List<String> candidates = new ArrayList<>();
            for (int j = i + 1; j < lines.size() && !head.matcher(lines.get(j)).find(); j++) {
                Matcher c = CANDIDATE.matcher(lines.get(j));
                if (c.find()) {
                    candidates.add(c.group(1));
                }
            }
            if (!candidates.isEmpty()) {
                CANDIDATES.put(m.group(1) + ":" + m.group(2), candidates);
            }
            // The one candidate whose only mismatch is a value the porter converts: its argument's place and types.
            Matcher noSuitable = Pattern.compile("^no suitable method found for (\\w+)\\((.*)\\)$").matcher(m.group(3));
            if (noSuitable.find()) {
                List<String> foundTypes = MapFix.topLevelArguments(noSuitable.group(2).replace('<', '(').replace('>', ')'));
                List<String[]> fits = new ArrayList<>();
                for (int j = i + 1; j + 1 < lines.size() && !head.matcher(lines.get(j)).find(); j++) {
                    Matcher c = CANDIDATE.matcher(lines.get(j));
                    Matcher why = Pattern.compile("\\(argument mismatch; (.+) cannot be converted to (.+)\\)").matcher(lines.get(j + 1));
                    if (!c.find() || !why.find()) {
                        continue;
                    }
                    List<String> params = MapFix.topLevelArguments(c.group(1).replace('<', '(').replace('>', ')'));
                    String to = why.group(2).strip();
                    // A recipe builder's save takes its recipe's key; javac prints it raw in a candidate list.
                    if (to.equals("ResourceKey") && noSuitable.group(1).equals("save")) {
                        to = "ResourceKey<Recipe<?>>";
                    }
                    int index = -1;
                    for (int k = 0; k < Math.min(foundTypes.size(), params.size()) && params.size() == foundTypes.size(); k++) {
                        if (!foundTypes.get(k).strip().equals(params.get(k).strip())) {
                            index = index == -1 && foundTypes.get(k).strip().equals(why.group(1).strip()) ? k : -2;
                        }
                    }
                    if (index >= 0 && MapFix.convert("x", why.group(1).strip(), to, true) != null) {
                        fits.add(new String[] { why.group(1).strip(), to, noSuitable.group(1), String.valueOf(index) });
                    }
                }
                if (fits.size() == 1) {
                    CONVERSION_MISMATCH.put(m.group(1) + ":" + m.group(2), fits.get(0));
                }
            }
            Err e = new Err(m.group(1), Integer.parseInt(m.group(2)), m.group(3), source, caret, symbol, location);
            seen.putIfAbsent(e.file() + ":" + e.line() + ":" + e.caret() + ":" + e.message() + ":" + symbol, e);
        }
        return new ArrayList<>(seen.values());
    }

    /** javac's "required:" and "found:" argument types for a call it reports at file:line (one candidate only). */
    static final Map<String, String[]> REQUIRED_FOUND = new HashMap<>();
    /** The type javac found where it wanted another, by file:line ("Optional<Reference<T#2>>" from "lower bounds:"). */
    static final Map<String, String> FOUND_TYPE = new HashMap<>();

    /** The parameter lists of the candidates javac names for a call it reports at file:line ("method X.of(A,B) is not applicable"). */
    static final Map<String, List<String>> CANDIDATES = new HashMap<>();

    /** For a "no suitable method" call at file:line: from, to, the method's name and the argument's index to convert. */
    static final Map<String, String[]> CONVERSION_MISMATCH = new HashMap<>();

    static final Pattern CANDIDATE = Pattern.compile("^\\s+(?:method|constructor) [\\w.$<>]+\\((.*)\\) is not applicable\\s*$");

    /**
     * Registries the game builds in, by element type: in 1.21.2 a Registry is a HolderGetter, so BuiltInRegistries.ITEM is
     * the HolderGetter<Item> that datagen builders now ask for (ShapedRecipeBuilder.shaped(items, category, result)).
     * Data-driven registries (enchantments, biomes) are not here: their contents come from the world, not the game.
     */
    static final Map<String, String> BUILT_IN_REGISTRY = Map.of(
        "Item", "ITEM",
        "Block", "BLOCK",
        "EntityType<?>", "ENTITY_TYPE",
        "Fluid", "FLUID",
        "MobEffect", "MOB_EFFECT",
        "Potion", "POTION",
        "BlockEntityType<?>", "BLOCK_ENTITY_TYPE"
    );

    /** Supertypes of the receiver types a compiler log names (simple names), from --supers. */
    static final Map<String, Set<String>> SUPERS = new HashMap<>();

    static String simple(String dotted) {
        String s = dotted.substring(dotted.lastIndexOf('.') + 1);
        return s.substring(s.lastIndexOf('$') + 1);
    }

    static String field(String obj, String key) {
        Matcher m = Pattern.compile("\"" + key + "\": \"([^\"]*)\"").matcher(obj);
        return m.find() ? m.group(1) : null;
    }

    /** A call the game itself rewrote between two releases: owners dotted ($ for nested), descriptors as in the class files. */
    record Rewrite(String owner, String name, String desc, boolean isStatic, String toOwner, String to, String newDesc, boolean newStatic, boolean knownStatic) {}

    static final Pattern CALL = Pattern.compile("\\.(\\w+)\\(");

    /** First method the source line calls whose signature the map says changed. */
    static String lineCalls(String source, Set<String> names) {
        Matcher m = CALL.matcher(source);
        while (m.find()) {
            if (names.contains(m.group(1))) {
                return m.group(1);
            }
        }
        return null;
    }

    static String unq(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}

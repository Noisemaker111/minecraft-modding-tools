import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;
import org.objectweb.asm.*;

/**
 * The game ids a mod's data files name (recipes, loot tables, tags, advancements, worldgen, enchantments, item
 * components), carried to the release a port goes to, from two of the game's own sources:
 *
 * - Each release's registry report (its data generator run with --reports): every id it registers, by registry.
 * - The game's data fixers (net.minecraft.util.datafix): the renames the game applies to saved worlds, as pairs of ids
 *   built into a map (ItemRenameFix, RenameEnchantmentsFix, BlockRenameFix ...) and as prefixes a fix strips
 *   (AttributeIdPrefixFix's "generic.", 1.21.2). A pair is used only where both reports agree: the old id is in a registry
 *   of the release ported from and not the one ported to, and the new id the other way round. Backward ports use the same
 *   pairs reversed.
 *
 * An id the release ported to still lacks after that is answered the way the game itself tolerates it, by the folder the
 * file is in:
 *
 * - Recipes, advancements, loot tables, predicates and item modifiers are read by reload listeners that log a file they
 *   cannot parse and go on without it (RecipeManager's "Parsing error loading recipe", ServerAdvancementManager's
 *   "Parsing error loading custom advancement", SimpleJsonResourceReloadListener's "Couldn't parse data file"). The file
 *   is left out of the ported jar, which is what the game does with it, and the port says so (silent).
 * - A tag entry naming an id the release lacks fails its whole tag; written {"id": ..., "required": false} it is skipped
 *   alone. The game reads that form from 1.16.2 (its Tag.Builder reads "required"; 1.16.1's does not).
 * - Anything else is an entry of a registry loaded with the world (worldgen, enchantments, damage types ...), and
 *   RegistryDataLoader stops the world on one ("Failed to load registries due to errors"): a break, "data id gone", or
 *   "data registry gone" when the release has no such registry at all (worldgen before 1.18, enchantment effects before
 *   1.21).
 *
 * Only ids in the minecraft namespace are judged, and only where the game decodes an id from a registry: the dispatch
 * keys "type", "function" and "condition", and a data component's key.
 *
 * Before any of that, the folders: 1.21 reads each kind of data from a folder named for its registry (loot_table,
 * recipe, structure, tags/item) where 1.20.6 read loot_tables, recipes, structures, tags/items, and reads nothing from
 * the old ones. Each release's own data (data/minecraft/ in its client jar) shows which folders it uses; a folder only the
 * release ported from has, whose name without its last "s" only the release ported to has, is moved there (and back, for
 * a backward port).
 */
final class DataIds {

    static final Path CACHE = Exposure.HOME.resolve("registries");
    /** Each release's registry report, read or made once per process; an empty Optional when its generator does not run. */
    static final Map<String, Optional<Map<String, Set<String>>>> REPORTS = new java.util.concurrent.ConcurrentHashMap<>();
    /** Each release's data fixer renames, read once per process. */
    static final Map<String, Fixers> FIXERS = new java.util.concurrent.ConcurrentHashMap<>();
    /** Each release's own data folders (loot_tables, tags/items ...), read once per process. */
    static final Map<String, Set<String>> FOLDERS = new java.util.concurrent.ConcurrentHashMap<>();

    static final Pattern GAME_ID = Pattern.compile("minecraft:[a-z0-9_./-]+");
    static final Pattern ID_TEXT = Pattern.compile("(minecraft:)?[a-z0-9_./-]+");
    static final Pattern PREFIX = Pattern.compile("[a-z_]+\\.");
    static final Set<String> DECODED_BY = Set.of("type", "function", "condition");
    /** The folders of data files a reload listener skips when it cannot parse one (singular from 1.21, plural before). */
    static final Set<String> SKIPPED = Set.of("recipe", "recipes", "advancement", "advancements", "loot_table", "loot_tables", "predicate", "predicates",
        "item_modifier", "item_modifiers");

    /** What the game's data fixers record: id pairs (old, new) and the prefixes a fix strips from ids. */
    record Fixers(Set<List<String>> pairs, Set<String> prefixes) {}

    /** What one jar's data needed: changes by kind, files left out and why (silent), and ids still missing (breaks). */
    final Map<String, Integer> changed = new TreeMap<>();
    final List<String> silent = new ArrayList<>();
    final List<String> left = new ArrayList<>();

    /**
     * One step of a port, from release a to b: the data folders and the data fixers' ids renamed between them, and on the
     * last step the reports the ids are judged by: the release the whole port started from (origin) and the one it goes
     * to (target); null on other steps, or when a report cannot be made.
     */
    record Step(Map<String, String> folders, Map<String, String> renames, Map<String, Set<String>> origin, Map<String, Set<String>> target,
                boolean optionalTags) {

        static Step of(String origin, String a, String b, boolean last) {
            boolean forward = JarPort.compareVersions(a, b) < 0;
            Set<String> fa = DataIds.folders(a), fb = DataIds.folders(b);
            Map<String, String> folders = fa == null || fb == null ? Map.of() : DataIds.folderRenames(fa, fb, forward);
            Map<String, Set<String>> ra = report(a), rb = report(b);
            if (ra == null || rb == null) {
                return new Step(folders, Map.of(), null, null, false);
            }
            Map<String, String> renames = DataIds.renames(fixers(forward ? b : a), ra, rb, forward);
            Map<String, Set<String>> first = last ? report(origin) : null;
            return new Step(folders, renames, first, first == null ? null : rb, first != null && JarPort.compareVersions(b, "1.16.2") >= 0);
        }

        boolean idle() {
            return folders.isEmpty() && renames.isEmpty() && target == null;
        }
    }

    final Map<String, String> folders, renames;
    final Map<String, Set<String>> origin, target;
    final boolean optionalTags;

    DataIds(Step step) {
        folders = step.folders();
        renames = step.renames();
        origin = step.origin();
        target = step.target();
        optionalTags = step.optionalTags();
    }

    // ------------------------------------------------------------------ registry reports

    /** A release's registry report by registry, from ~/.mmt/exposure/registries/VERSION.json or made there; null when it cannot be made. */
    static Map<String, Set<String>> report(String version) {
        Optional<Map<String, Set<String>>> known = REPORTS.get(version);
        if (known == null) {
            synchronized (REPORTS) {
                known = REPORTS.get(version);
                if (known == null) {
                    Map<String, Set<String>> read;
                    try {
                        read = readOrMake(version);
                    } catch (Exception failed) {
                        read = null;
                    }
                    known = Optional.ofNullable(read);
                    REPORTS.put(version, known);
                }
            }
        }
        return known.orElse(null);
    }

    static Map<String, Set<String>> readOrMake(String version) throws Exception {
        Path cache = CACHE.resolve(version + ".json");
        if (!Files.isRegularFile(cache)) {
            make(version, cache);
        }
        if (!Files.isRegularFile(cache)) {
            return null;
        }
        Map<String, Set<String>> out = new HashMap<>();
        for (var e : Json.object(Json.parse(Files.readString(cache, StandardCharsets.UTF_8))).entrySet()) {
            Set<String> ids = new HashSet<>();
            for (Object id : Json.array(e.getValue())) {
                ids.add(id.toString());
            }
            out.put(e.getKey(), ids);
        }
        return out;
    }

    /** Runs the release's own data generator (net.minecraft.data.Main --reports) on its server jar and keeps the registries report. */
    static void make(String version, Path cache) throws Exception {
        String json = GameDiff.versionJson(version);
        String[] server = GameDiff.download(json, "server");
        if (server == null) {
            return;
        }
        Path jar = GameDiff.verified(server, "server-" + version + ".jar");
        Path work = Files.createTempDirectory("mmt-registries-" + version + "-");
        try {
            boolean bundled;
            try (ZipFile z = new ZipFile(jar.toFile())) {
                bundled = z.getEntry("META-INF/versions.list") != null;
            }
            String java = ProcessHandle.current().info().command().orElse("java");
            // From 1.18 the server jar is a bundler that names the class to run; before, the generator is on its class path.
            List<String> command = new ArrayList<>(bundled ? List.of(java, "-DbundlerMainClass=net.minecraft.data.Main", "-jar", jar.toString())
                : List.of(java, "-cp", jar.toString(), "net.minecraft.data.Main"));
            command.addAll(List.of("--reports", "--output", work.resolve("generated").toString()));
            Process p = new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true).redirectOutput(work.resolve("log.txt").toFile()).start();
            Path report = work.resolve("generated/reports/registries.json");
            if (p.waitFor() != 0 || !Files.isRegularFile(report)) {
                return;
            }
            Map<String, Object> ids = new TreeMap<>();
            for (var e : Json.object(Json.parse(Files.readString(report, StandardCharsets.UTF_8))).entrySet()) {
                ids.put(e.getKey(), new TreeSet<>(Json.object(Json.object(e.getValue()).get("entries")).keySet()));
            }
            Files.createDirectories(cache.getParent());
            Path part = cache.resolveSibling(cache.getFileName() + "." + ProcessHandle.current().pid() + ".part");
            Files.writeString(part, Json.write(ids), StandardCharsets.UTF_8);
            Files.move(part, cache, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            try (var walk = Files.walk(work)) {
                walk.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        }
    }

    /** Makes each release's report ahead of a port (the scoreboard keys its ports by them): prints the ones that cannot be made. */
    public static void main(String[] versions) {
        for (String v : versions) {
            if (report(v) == null) {
                System.out.println("no registry report for " + v);
            }
        }
    }

    // ------------------------------------------------------------------ the game's own data folders

    /** The folders the release's own data is in (data/minecraft/X, and tags/Y under it), from its client jar; null when unreadable. */
    static Set<String> folders(String version) {
        Set<String> known = FOLDERS.computeIfAbsent(version, v -> {
            Set<String> out = new TreeSet<>();
            try (ZipFile z = new ZipFile(clientJar(v).toFile())) {
                for (var e = z.entries(); e.hasMoreElements(); ) {
                    String[] parts = e.nextElement().getName().split("/");
                    if (parts.length > 3 && parts[0].equals("data") && parts[1].equals("minecraft")) {
                        out.add(parts[2].equals("tags") && parts.length > 4 ? "tags/" + parts[3] : parts[2]);
                    }
                }
            } catch (Exception unreadable) {
                out.add("");
            }
            return out;
        });
        return known.contains("") ? null : known;
    }

    static Path clientJar(String version) throws Exception {
        return GameDiff.verified(GameDiff.download(GameDiff.versionJson(version), "client"), "client-" + version + ".jar");
    }

    /** Folders only src uses whose name without (forward) or with (backward) a last "s" only dst uses. */
    static Map<String, String> folderRenames(Set<String> src, Set<String> dst, boolean forward) {
        Map<String, String> out = new TreeMap<>();
        for (String f : src) {
            String to = forward ? (f.endsWith("s") ? f.substring(0, f.length() - 1) : null) : f + "s";
            if (!dst.contains(f) && to != null && dst.contains(to) && !src.contains(to)) {
                out.put(f, to);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the game's data fixers

    static Fixers fixers(String version) {
        return FIXERS.computeIfAbsent(version, v -> {
            try {
                String json = GameDiff.versionJson(v);
                Path jar = clientJar(v);
                String[] mappings = GameDiff.download(json, "client_mappings");
                // Obfuscated releases: the data fixer classes by their names in the jar, from Mojang's mappings.
                Set<String> named = null;
                if (mappings != null) {
                    named = new HashSet<>();
                    for (String line : Files.readAllLines(GameDiff.verified(mappings, "client-" + v + ".txt"), StandardCharsets.UTF_8)) {
                        if (line.startsWith("net.minecraft.util.datafix.") && line.endsWith(":")) {
                            named.add(line.substring(line.indexOf(" -> ") + 4, line.length() - 1).replace('.', '/') + ".class");
                        }
                    }
                }
                List<byte[]> classes = new ArrayList<>();
                try (ZipFile z = new ZipFile(jar.toFile())) {
                    for (var e = z.entries(); e.hasMoreElements(); ) {
                        ZipEntry ze = e.nextElement();
                        if (named == null ? ze.getName().startsWith("net/minecraft/util/datafix/") && ze.getName().endsWith(".class") : named.contains(ze.getName())) {
                            try (InputStream in = z.getInputStream(ze)) {
                                classes.add(in.readAllBytes());
                            }
                        }
                    }
                }
                return read(classes);
            } catch (Exception unreadable) {
                return new Fixers(Set.of(), Set.of());
            }
        });
    }

    /**
     * The renames in data fixer classes: ids loaded one after another and handed to a call that builds a map or a
     * renamer from them (Map.of, ImmutableMap.of, a builder's put, Map.entry, createRenamer), taken two by two; and
     * every constant that is a lowercase word ending in a dot, a prefix a fix strips.
     */
    static Fixers read(List<byte[]> classes) {
        Set<List<String>> pairs = new HashSet<>();
        Set<String> prefixes = new HashSet<>();
        for (byte[] c : classes) {
            new ClassReader(c).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        final List<String> run = new ArrayList<>();

                        @Override
                        public void visitLdcInsn(Object value) {
                            if (value instanceof String s && PREFIX.matcher(s).matches()) {
                                prefixes.add(s);
                            }
                            if (value instanceof String s && ID_TEXT.matcher(s).matches()) {
                                run.add(s.startsWith("minecraft:") ? s : "minecraft:" + s);
                            } else {
                                run.clear();
                            }
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String n, String d, boolean itf) {
                            if (run.size() >= 2 && run.size() % 2 == 0 && Set.of("of", "put", "entry", "createRenamer").contains(n)) {
                                for (int i = 0; i + 1 < run.size(); i += 2) {
                                    pairs.add(List.of(run.get(i), run.get(i + 1)));
                                }
                            }
                            run.clear();
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            run.clear();
                        }

                        @Override
                        public void visitVarInsn(int opcode, int var) {
                            run.clear();
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String n, String d) {
                            run.clear();
                        }

                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            run.clear();
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
                            run.clear();
                        }

                        @Override
                        public void visitJumpInsn(int opcode, Label label) {
                            run.clear();
                        }

                        @Override
                        public void visitLabel(Label label) {
                            run.clear();
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return new Fixers(pairs, prefixes);
    }

    /**
     * The fixers' renames that both releases' reports bear out, from the release ported from (src) to the one ported to
     * (dst): forward, a pair's old id is only in src and its new id only in dst, in the same registry; backward the same
     * reversed. A data file's string does not say which registry it names, so neither id may keep a meaning both releases
     * share (a registry that has it in both): 1.21.5's "potion" entity became "splash_potion", but "potion" is still an
     * item, and 1.21.2's "boat" entity became one per wood, "oak_boat" already an item. An id two renames claim is left
     * alone too.
     */
    static Map<String, String> renames(Fixers fixers, Map<String, Set<String>> src, Map<String, Set<String>> dst, boolean forward) {
        Set<String> kept = new HashSet<>(); // ids with a meaning both releases share
        src.forEach((registry, ids) -> {
            Set<String> other = dst.get(registry);
            if (other != null) {
                ids.stream().filter(other::contains).forEach(kept::add);
            }
        });
        Map<String, Set<String>> found = new TreeMap<>();
        for (var e : src.entrySet()) {
            Set<String> other = dst.get(e.getKey());
            if (other == null) {
                continue;
            }
            Set<String> gone = new HashSet<>(e.getValue());
            gone.removeAll(other);
            Set<String> added = new HashSet<>(other);
            added.removeAll(e.getValue());
            if (gone.isEmpty() || added.isEmpty()) {
                continue;
            }
            for (List<String> p : fixers.pairs()) {
                String from = forward ? p.get(0) : p.get(1), to = forward ? p.get(1) : p.get(0);
                if (gone.contains(from) && added.contains(to)) {
                    found.computeIfAbsent(from, k -> new TreeSet<>()).add(to);
                }
            }
            for (String prefix : fixers.prefixes()) {
                for (String id : gone) {
                    String path = id.substring(id.indexOf(':') + 1), ns = id.substring(0, id.indexOf(':') + 1);
                    String to = forward ? (path.startsWith(prefix) ? ns + path.substring(prefix.length()) : null) : ns + prefix + path;
                    if (to != null && added.contains(to)) {
                        found.computeIfAbsent(id, k -> new TreeSet<>()).add(to);
                    }
                }
            }
        }
        Map<String, String> out = new TreeMap<>();
        found.forEach((from, to) -> {
            if (to.size() == 1 && !kept.contains(from) && !kept.contains(to.iterator().next())) {
                out.put(from, to.iterator().next());
            }
        });
        return out;
    }

    // ------------------------------------------------------------------ a jar's data

    /** Carries the data files among a jar's entries (changed in place) and notes what it did and what is left. */
    void apply(Map<String, byte[]> entries) {
        move(entries);
        Map<String, String> registryOf = new HashMap<>();
        Set<String> present = new HashSet<>();
        if (target != null) {
            origin.forEach((registry, ids) -> ids.forEach(id -> registryOf.putIfAbsent(id, registry)));
            target.values().forEach(present::addAll);
        }
        for (String file : new ArrayList<>(entries.keySet())) {
            if (!file.startsWith("data/") || !file.endsWith(".json")) {
                continue;
            }
            Object json;
            try {
                String text = new String(entries.get(file), StandardCharsets.UTF_8);
                json = Json.parse(text.startsWith("\uFEFF") ? text.substring(1) : text);
            } catch (RuntimeException unreadable) {
                continue;
            }
            int[] renamed = { 0 };
            json = rename(json, renamed);
            String[] parts = file.split("/");
            boolean tag = parts.length > 3 && parts[2].equals("tags");
            int optional = tag && optionalTags ? optional(json, parts) : 0;
            List<String> missing = new ArrayList<>();
            boolean judged = true;
            if (target != null && !tag) {
                walk(null, json, registryOf, present, missing);
            }
            if (!missing.isEmpty() && parts.length > 3 && SKIPPED.contains(parts[2])) {
                List<String> gone = missing.stream().filter(m -> target.containsKey(m.substring(0, m.indexOf(' ')))).toList();
                if (!gone.isEmpty()) {
                    entries.remove(file);
                    change("data files left out that the release would skip (an id it lacks)");
                    silent.add("data file the release would skip, left out: " + gone.get(0) + " (in " + file + ")");
                    continue;
                }
                judged = false; // a registry the release does not report: the reload listener skips the file at worst
            }
            if (judged) {
                for (String m : missing) {
                    String registry = m.substring(0, m.indexOf(' '));
                    left.add((target.containsKey(registry) ? "data id gone: " : "data registry gone: ") + m + " (in " + file + ")");
                }
            }
            if (renamed[0] + optional > 0) {
                entries.put(file, Json.write(json).getBytes(StandardCharsets.UTF_8));
                if (renamed[0] > 0) {
                    change("data ids renamed as the game's data fixers rename them");
                }
                if (optional > 0) {
                    change("tag entries the release lacks made optional");
                }
            }
        }
    }

    /** Each data file in a folder the release reads under another name, moved there (unless the jar has that file already). */
    void move(Map<String, byte[]> entries) {
        if (folders.isEmpty()) {
            return;
        }
        Map<String, byte[]> moved = new LinkedHashMap<>();
        for (var e : entries.entrySet()) {
            String name = e.getKey(), to = name;
            String[] parts = name.split("/", 3);
            if (parts.length == 3 && parts[0].equals("data")) {
                for (var f : folders.entrySet()) {
                    if (parts[2].startsWith(f.getKey() + "/")) {
                        to = "data/" + parts[1] + "/" + f.getValue() + parts[2].substring(f.getKey().length());
                        break;
                    }
                }
            }
            if (!to.equals(name) && !entries.containsKey(to)) {
                change("data files moved to the folder the release reads");
                moved.put(to, e.getValue());
            } else {
                moved.put(name, e.getValue());
            }
        }
        entries.clear();
        entries.putAll(moved);
    }

    void change(String kind) {
        changed.merge(kind, 1, Integer::sum);
    }

    /** The value with every string, and every object key (a component's, with or without "!"), that names a renamed id renamed. */
    @SuppressWarnings("unchecked")
    Object rename(Object value, int[] count) {
        if (value instanceof String s) {
            String to = renames.get(s);
            if (to != null) {
                count[0]++;
                return to;
            }
            return s;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object item : l) {
                out.add(rename(item, count));
            }
            return out;
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (var e : ((Map<String, Object>) m).entrySet()) {
                String key = e.getKey();
                boolean removed = key.startsWith("!");
                String to = renames.get(removed ? key.substring(1) : key);
                if (to != null) {
                    count[0]++;
                    key = (removed ? "!" : "") + to;
                }
                out.put(key, rename(e.getValue(), count));
            }
            return out;
        }
        return value;
    }

    /** Ids in the minecraft namespace a tag lists that the release's registry for it lacks, made optional entries; how many. */
    @SuppressWarnings("unchecked")
    int optional(Object json, String[] parts) {
        if (!(json instanceof Map<?, ?> tag) || !(tag.get("values") instanceof List<?> values) || target == null) {
            return 0;
        }
        String folder = parts[3];
        Set<String> ids = target.get("minecraft:" + folder);
        if (ids == null && folder.endsWith("s")) {
            ids = target.get("minecraft:" + folder.substring(0, folder.length() - 1)); // tags/items before 1.21's tags/item
        }
        if (ids == null) {
            return 0;
        }
        int n = 0;
        List<Object> list = (List<Object>) values;
        for (int i = 0; i < list.size(); i++) {
            Object entry = list.get(i);
            String id = entry instanceof String s ? s : entry instanceof Map<?, ?> m && m.get("id") instanceof String s ? s : null;
            if (id == null || !GAME_ID.matcher(id).matches() || ids.contains(id) || entry instanceof Map<?, ?> m && Boolean.FALSE.equals(m.get("required"))) {
                continue;
            }
            Map<String, Object> written = new LinkedHashMap<>();
            written.put("id", id);
            written.put("required", false);
            list.set(i, written);
            n++;
        }
        return n;
    }

    /** "REGISTRY ID" for each id the release ported from registered and the one ported to does not, where the game decodes one. */
    static void walk(String key, Object value, Map<String, String> registryOf, Set<String> present, List<String> missing) {
        if (value instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                String id = k.startsWith("!") ? k.substring(1) : k;
                if ("components".equals(key) && registryOf.containsKey(id) && !present.contains(id)) {
                    add(missing, registryOf.get(id) + " " + id);
                }
                walk(k, e.getValue(), registryOf, present, missing);
            }
        } else if (value instanceof List<?> l) {
            for (Object item : l) {
                walk(key, item, registryOf, present, missing);
            }
        } else if (value instanceof String s && DECODED_BY.contains(key) && GAME_ID.matcher(s).matches() && registryOf.containsKey(s) && !present.contains(s)) {
            add(missing, registryOf.get(s) + " " + s);
        }
    }

    static void add(List<String> list, String item) {
        if (!list.contains(item)) {
            list.add(item);
        }
    }
}

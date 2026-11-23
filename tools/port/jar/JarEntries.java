import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/** What a mod's jar holds as a file: its entries, the jars nested in it, its signatures, and which of them use the game. */
final class JarEntries {

    /** Methods (class.name+desc) a class declares twice, which the JVM refuses to load it for (ClassFormatError). */
    static List<String> declaredTwice(byte[] bytes) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ClassReader r = new ClassReader(bytes);
        r.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (!seen.add(name + desc)) {
                    out.add(Exposure.dotted(r.getClassName()) + "." + name + desc);
                }
                return null;
            }
        }, ClassReader.SKIP_CODE);
        return out;
    }

    /** Where mark first is in data, or -1 (Boyer-Moore-Horspool: every class of a jar is searched at every step of a long port). */
    static int indexOf(byte[] data, byte[] mark) {
        return indexOf(data, mark, 0);
    }

    static int indexOf(byte[] data, byte[] mark, int from) {
        int m = mark.length;
        if (m == 0) {
            return from;
        }
        int[] skip = new int[256];
        Arrays.fill(skip, m);
        for (int j = 0; j < m - 1; j++) {
            skip[mark[j] & 0xFF] = m - 1 - j;
        }
        for (int i = from; i + m <= data.length; i += skip[data[i + m - 1] & 0xFF]) {
            int j = m - 1;
            while (j >= 0 && data[i + j] == mark[j]) {
                j--;
            }
            if (j < 0) {
                return i;
            }
        }
        return -1;
    }

    /** A jar's files by name (directories left out); times gets each file's time. An entry pointing outside the jar refuses it. */
    static Map<String, byte[]> entries(byte[] jar, String name, Map<String, Long> times) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (unsafe(e.getName())) {
                    throw new IOException(name + " has an entry that points outside the jar: " + e.getName());
                }
                if (!e.isDirectory()) {
                    entries.put(e.getName(), zip.readAllBytes());
                    times.put(e.getName(), e.getTime());
                }
            }
        }
        return entries;
    }

    /**
     * Jar-in-jar: nested jars are where META-INF/jarjar/metadata.json says ("path", often META-INF/jars/), in
     * META-INF/jarjar/, where fabric.mod.json lists them ("jars": [{"file": ...}]), and every other jar inside whose classes
     * use the game (selfLoaded). Every reader of a mod's code (ModNames, the hand-back, the port) reads this one set.
     */
    static Set<String> nested(Map<String, byte[]> entries) {
        Set<String> nested = listed(entries);
        nested.addAll(selfLoaded(entries, nested));
        return nested;
    }

    /**
     * The jars inside a jar that no loader metadata lists and whose classes use the game: code the mod's own classes put on
     * the class path at start (a mod locator, a language adapter, a loader of its own that unpacks it), which runs in the
     * game as surely as a listed jar. A jar whose classes only name the game in text (a crash-log reader's patterns) is no
     * game code.
     */
    static Set<String> selfLoaded(Map<String, byte[]> entries, Set<String> listed) {
        Set<String> out = new TreeSet<>();
        for (var e : entries.entrySet()) {
            if (e.getKey().endsWith(".jar") && !listed.contains(e.getKey()) && usesGame(e.getValue())) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    static final byte[] GAME_PACKAGE = "net/minecraft/".getBytes(StandardCharsets.ISO_8859_1), BLAZE3D_PACKAGE = "com/mojang/blaze3d/".getBytes(StandardCharsets.ISO_8859_1);
    static final Map<byte[], Boolean> USES_GAME = Collections.synchronizedMap(new WeakHashMap<>());

    /** Whether a jar's classes, or those of the jars inside it, refer to a class of the game (its class constants). */
    static boolean usesGame(byte[] jar) {
        Boolean known = USES_GAME.get(jar);
        if (known != null) {
            return known;
        }
        boolean uses = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; !uses && (e = zip.getNextEntry()) != null; ) {
                if (e.getName().endsWith(".class")) {
                    uses = !gameClasses(zip.readAllBytes()).isEmpty();
                } else if (e.getName().endsWith(".jar")) {
                    uses = usesGame(zip.readAllBytes());
                }
            }
        } catch (IOException notAJar) {
            uses = false;
        }
        USES_GAME.put(jar, uses);
        return uses;
    }

    /**
     * The game classes (net/minecraft/, com/mojang/blaze3d/) a class refers to as types: in its references, descriptors,
     * signatures and annotations, never in its text.
     */
    static Set<String> gameClasses(byte[] bytes) {
        Set<String> out = new TreeSet<>();
        if (indexOf(bytes, GAME_PACKAGE) < 0 && indexOf(bytes, BLAZE3D_PACKAGE) < 0) {
            return out; // most library classes: no parse
        }
        try {
            new ClassReader(bytes).accept(new ClassRemapper(new ClassVisitor(Opcodes.ASM9) { }, new Remapper(Opcodes.ASM9) {
                @Override
                public String map(String internalName) {
                    if (internalName.startsWith("net/minecraft/") || internalName.startsWith("com/mojang/blaze3d/")) {
                        out.add(internalName);
                    }
                    return internalName;
                }
            }), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException unparsed) {
            // a class the JVM refuses too
        }
        return out;
    }

    /**
     * The first game class a jar's classes name that a release lacks, or null. A jar the mod's own code loads that names
     * game classes its release lacks is not written in that release's names: its own code renames it at start, through a
     * table of its own (one file for every release), so the port leaves it, and the hand-back too, as it came.
     */
    static String notIn(byte[] jar, java.util.function.Predicate<String> release) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.getName().endsWith(".class") && !e.getName().startsWith("META-INF/versions/")) {
                    for (String c : gameClasses(zip.readAllBytes())) {
                        if (!release.test(c)) {
                            return c;
                        }
                    }
                }
            }
        } catch (IOException notAJar) {
            return null;
        }
        return null;
    }

    static final Pattern DIGEST = Pattern.compile("(?i)\\b[0-9a-f]{32}(?:[0-9a-f]{8})?(?:[0-9a-f]{24})?\\b");

    /**
     * A jar that records the digest (MD5, SHA-1 or SHA-256, as hex) of a jar it carries in a text file beside it (a loader
     * of its own checking the jar it unpacks) records the new jar's: the digest of what was ported is no longer that of the
     * jar carried. files: the jar's files, rewritten in place; was and after: its nested jars by name, as they came and as
     * they are written now.
     */
    static void redigest(Map<String, byte[]> files, Map<String, byte[]> was, Map<String, byte[]> after) {
        Map<String, String> moved = new HashMap<>();
        for (var e : after.entrySet()) {
            byte[] before = was.get(e.getKey());
            if (before == null || Arrays.equals(before, e.getValue())) {
                continue;
            }
            for (String algorithm : List.of("MD5", "SHA-1", "SHA-256")) {
                try {
                    var digest = java.security.MessageDigest.getInstance(algorithm);
                    moved.put(HexFormat.of().formatHex(digest.digest(before)), HexFormat.of().formatHex(digest.digest(e.getValue())));
                } catch (java.security.NoSuchAlgorithmException missing) {
                    throw new IllegalStateException(missing);
                }
            }
        }
        if (moved.isEmpty()) {
            return;
        }
        for (var e : files.entrySet()) {
            String n = e.getKey();
            if (n.endsWith(".class") || n.endsWith(".jar") || e.getValue().length > 1 << 20) {
                continue;
            }
            String text = new String(e.getValue(), StandardCharsets.ISO_8859_1);
            Matcher m = DIGEST.matcher(text);
            StringBuilder out = new StringBuilder();
            boolean changed = false;
            while (m.find()) {
                String now = moved.get(m.group().toLowerCase(Locale.ROOT));
                changed |= now != null;
                String written = now == null ? m.group() : m.group().equals(m.group().toUpperCase(Locale.ROOT)) ? now.toUpperCase(Locale.ROOT) : now;
                m.appendReplacement(out, written);
            }
            if (changed) {
                m.appendTail(out);
                e.setValue(out.toString().getBytes(StandardCharsets.ISO_8859_1));
            }
        }
    }

    /** The nested jars a loader reads from its metadata, and META-INF/jarjar/, where NeoForge's loader looks. */
    static Set<String> listed(Map<String, byte[]> entries) {
        Set<String> nested = new HashSet<>();
        byte[] list = entries.get("META-INF/jarjar/metadata.json");
        if (list != null) {
            for (Object j : Json.array(Json.object(Json.parse(new String(list, StandardCharsets.UTF_8))).getOrDefault("jars", List.of()))) {
                nested.add(Json.text(Json.object(j), "path", ""));
            }
        }
        byte[] fmj = entries.get("fabric.mod.json");
        if (fmj != null) {
            try {
                for (Object j : Json.object(Json.parse(new String(fmj, StandardCharsets.UTF_8))).get("jars") instanceof List<?> l ? l : List.of()) {
                    if (j instanceof Map<?, ?> m && m.get("file") instanceof String file) {
                        nested.add(file);
                    }
                }
            } catch (RuntimeException unreadable) {
                // the loader rejects it too
            }
        }
        for (String n : entries.keySet()) {
            if (n.startsWith("META-INF/jarjar/") && n.endsWith(".jar")) {
                nested.add(n);
            }
        }
        nested.retainAll(entries.keySet());
        return nested;
    }

    /** An entry name that an unzip would write outside its folder: absolute, a drive, a backslash or a ".." part. */
    static boolean unsafe(String entry) {
        return entry.startsWith("/") || entry.contains("\\") || entry.matches("^[A-Za-z]:.*") || Arrays.asList(entry.split("/")).contains("..");
    }

    static byte[] unsigned(byte[] manifest) throws IOException {
        java.util.jar.Manifest m = new java.util.jar.Manifest(new ByteArrayInputStream(manifest));
        // Per-entry digests belong to the signature that was removed; everything else stays.
        for (var entry : m.getEntries().values()) {
            entry.keySet().removeIf(k -> k.toString().endsWith("-Digest"));
        }
        m.getEntries().values().removeIf(java.util.jar.Attributes::isEmpty);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        m.write(out);
        return out.toByteArray();
    }
}

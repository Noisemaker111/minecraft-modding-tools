import java.io.*;
import java.lang.classfile.*;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.*;
import java.lang.constant.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * GameDiff - what Minecraft renamed, moved or changed between releases, derived from the game itself.
 *
 * No Fabric intermediary, yarn or other third-party mapping is read. Each release is the vanilla
 * client jar from Mojang's version manifest, named with Mojang's own client_mappings (ProGuard
 * txt) while the game was obfuscated, and by the jar's own names from 26.1 on. Every class, method
 * and field of the first release gets an id of our own (class_g1, method_g1, field_g1); each next
 * release is matched to the one before it from bytecode evidence and inherits those ids, and
 * anything unmatched gets a fresh id. VersionMap.Chain then turns the consistently keyed tables
 * into the usual map (classes, methods, methodsMoved, signatures, ..., chain, history).
 *
 * Evidence, strongest first; a link needs a mutual best match above a threshold, and an ambiguous
 * candidate is left unlinked (a missing rename is reported as removed, never guessed):
 *   classes  same full name; a nested class of a linked outer class with the same nested name;
 *            then member names (weighted by rarity), identical method bodies, superclass, simple
 *            name (a class whose content moved into a new parent keeps its namesake: Slime -> Slime).
 *   methods  same name and descriptor (descriptors read through the class links), also into a new
 *            parent class; same descriptor and a similar body (opcodes plus every referenced class,
 *            method, field and constant, read through the links found so far; disjoint string
 *            constants veto); the only unlinked method of that name on each side (signature change);
 *            an override of a linked method; linked callers that call it at the same place on each
 *            side (same descriptor); an identical non-trivial body found exactly once on each side
 *            of the jar (a move).
 *   fields   same name and type; same compile-time constant; the same linked methods reading or
 *            writing it; linked callers; finally same name with a new type.
 * The rounds repeat until nothing new links, so each link sharpens the next comparison.
 *
 * --loader neoforge adds NeoForge's own classes (net/neoforged/**) from its published universal jar
 * (maven.neoforged.net, Mojang names already) and links them the same way. A release with no
 * NeoForge build carries the previous build. Fabric API is not covered yet: its jars use
 * intermediary names before 26.1, and this tool makes no claim it cannot back from Mojang names.
 *
 *   java GameDiff --chain v1,v2,... --out map.json [--loader neoforge] [--loader-versions a,b,...] [--cache dir]
 *   java GameDiff --compare A B [--all] [--report file]   (validation only: an obfuscated pair, our
 *        linking scored against VersionMap's intermediary diff; --all includes private members)
 *
 * Compile with tools/port/names/VersionMap.java, JDK 25 (java.lang.classfile). Downloads are cached in
 * VersionMap.cache (~/.mmt/mappings). Exit: 0 ok, 2 usage or I/O error.
 */
public final class GameDiff {

    static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    static final String NEOFORGE = "https://maven.neoforged.net/releases/net/neoforged/neoforge/";
    static final int ACC_PUBLIC = 0x0001;
    static final int ACC_PRIVATE = 0x0002;
    static final int ACC_STATIC = 0x0008;
    static final int ACC_BRIDGE = 0x0040;
    static final int ACC_INTERFACE = 0x0200;
    static final int ACC_ANNOTATION = 0x2000;
    static final int ACC_ENUM = 0x4000;
    static final int ACC_SYNTHETIC = 0x1000;
    static boolean verbose = false;
    static final String WHY = System.getProperty("gamediff.why", ""); // -Dgamediff.why=<internal class name>: print its class candidates

    public static void main(String[] args) throws Exception {
        // Several --chain/--out pairs build several maps in one process (a port's steps, bench/exposure.py routes): each
        // is its own chain, as if built alone, and the process starts and warms up once.
        List<String> chains = new ArrayList<>();
        List<String> outs = new ArrayList<>();
        String loader = null;
        String loaderVersions = null;
        List<String> compare = new ArrayList<>();
        boolean all = false;
        String report = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--chain" -> chains.add(args[++i]);
                case "--out" -> outs.add(args[++i]);
                case "--loader" -> loader = args[++i];
                case "--loader-versions" -> loaderVersions = args[++i];
                case "--cache" -> VersionMap.cache = Path.of(args[++i]).toAbsolutePath();
                case "--compare" -> {
                    compare.add(args[++i]);
                    compare.add(args[++i]);
                }
                case "--all" -> all = true;
                case "--report" -> report = args[++i];
                case "--verbose" -> verbose = true;
                default -> usage();
            }
        }
        if (loader != null && !loader.equals("neoforge") && !loader.equals("fabric")) {
            System.err.println("--loader is neoforge or fabric");
            System.exit(2);
        }
        if (!compare.isEmpty()) {
            GameCompare.run(compare.get(0), compare.get(1), !all, report == null ? null : Path.of(report));
            return;
        }
        if (chains.isEmpty() || chains.size() != outs.size()) {
            usage();
        }
        // Each map is its own chain, one core's work, so the maps are built side by side, each worker taking a run of
        // consecutive chains: a chain starts at the release the one before it ended at, read once. As many workers as
        // the heap holds: 20 workers on a 24-processor PC ran out of 10 GB.
        int workers = workers(chains.size(), MAP_WORKER, 0);
        List<Thread> running = new ArrayList<>();
        List<Throwable> failed = Collections.synchronizedList(new ArrayList<>());
        String l = loader, lv = loaderVersions;
        for (int w = 0; w < workers; w++) {
            int from = chains.size() * w / workers, to = chains.size() * (w + 1) / workers;
            running.add(Thread.ofPlatform().start(() -> {
                try {
                    for (int i = from; i < to; i++) {
                        map(chains.get(i), outs.get(i), l, lv);
                        Set<String> later = new HashSet<>();
                        for (String c : chains.subList(i + 1, to)) {
                            later.addAll(List.of(c.split(",")));
                        }
                        for (String v : chains.get(i).split(",")) {
                            if (!later.contains(v)) {
                                Exposure.forget(v);
                            }
                        }
                    }
                } catch (Throwable t) {
                    failed.add(t);
                }
            }));
        }
        for (Thread t : running) {
            t.join();
        }
        if (!failed.isEmpty()) {
            throw failed.get(0) instanceof Exception e ? e : new RuntimeException(failed.get(0));
        }
    }

    /**
     * The heap one map worker needs (Fabric 1.20.1 -> 26.3's 20 step maps): 2 workers ran out of 2 GB and finished in 3,
     * 4 in 4 GB with 33 full collections and as fast as with 10 GB in 5, 8 ran out of 6 GB and finished in 8.
     */
    static final long MAP_WORKER = 1280L << 20;

    /**
     * Workers for jobs side by side: one per processor, as many as the heap left after base holds at perWorker each, and
     * at least one.
     */
    static int workers(int jobs, long perWorker, long base) {
        long room = Runtime.getRuntime().maxMemory() - base;
        long byMemory = Math.max(1, room / perWorker);
        return (int) Math.max(1, Math.min(jobs, Math.min(Runtime.getRuntime().availableProcessors(), byMemory)));
    }

    static void map(String chain, String out, String loader, String loaderVersions) throws Exception {
        long t0 = System.nanoTime();
        List<String> versions = List.of(chain.split(","));
        GameDriver driver = new GameDriver(versions, loader, loaderVersions == null ? null : List.of(loaderVersions.split(",")));
        MapChain c = MapChain.of(versions, driver);
        Path target = Path.of(out);
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.writeString(target, driver.json(c), StandardCharsets.UTF_8);
        VersionMap.Diff d = c.diff;
        System.out.printf(
            "chain %s (game-bytecode%s, %d updates): classes %d renamed or moved, %d removed; methods %d renamed, " +
            "%d moved, %d signature changes, %d removed; fields %d renamed, %d removed; %d history events (%.1fs)%n",
            String.join(" -> ", versions),
            loader == null ? "" : " + " + loader,
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
    }

    static void usage() {
        System.err.println(
            "usage: java GameDiff --chain <v1,v2,...> --out map.json [--chain ... --out ...] [--loader neoforge|fabric] [--loader-versions a,b,...] [--cache dir]\n" +
            "       java GameDiff --compare <A> <B> [--all] [--report disagreements.txt]"
        );
        System.exit(2);
    }

    // ------------------------------------------------------------------ model

    /** A reference from a method body: c class, m method, f field, k constant (value in owner). */
    record Ref(char kind, String owner, String name, String desc) {}

    static final class Meth {

        final Cls owner;
        final String name;
        final String desc;
        int flags; // widened by the loader's access transformer (Exposure.loaderAccess)
        String ops = ""; // null, as refs is, until its class's code is read in a release read without it (references)
        Ref[] refs = new Ref[0];
        boolean hasCode;
        String id;
        int[] bigrams;
        long[] tokens;
        long hash;

        Meth(Cls owner, String name, String desc, int flags) {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
            this.flags = flags;
        }

        boolean special() {
            return name.startsWith("<");
        }

        /**
         * Its references, its class's code read first where the release was read without it (GameDiff.read): a port
         * names the game's members and reads the code of a few methods, and reading every body was half of reading a
         * release. What is read then is what a whole read gives each method of the class.
         */
        Ref[] references() {
            synchronized (owner) {
                if (refs == null) {
                    Origin o = owner.origin;
                    code(owner, parse(o.bytes(), true), o.raw(), o.fallback(), o.names(), o.resolved());
                }
                return refs;
            }
        }

        boolean isStatic() {
            return (flags & ACC_STATIC) != 0;
        }

        boolean trivial() {
            return !hasCode || (refs.length == 0 && ops.length() < 6);
        }

        /** Strong enough to link alone, anywhere in the jar. */
        boolean distinctive() {
            return hasCode && ops.length() >= 8 && refs.length >= 2;
        }

        int[] bigrams() {
            if (bigrams == null) {
                int n = Math.max(0, ops.length() - 1);
                int[] g = new int[n];
                for (int i = 0; i < n; i++) {
                    g[i] = (ops.charAt(i) << 16) | ops.charAt(i + 1);
                }
                Arrays.sort(g);
                bigrams = g;
            }
            return bigrams;
        }

        /**
         * From its names, so maps and sets of methods go round in the same order every run (an identity hash differs
         * between runs, and a link two old methods tie for went to whichever came first). Equality stays identity.
         */
        @Override
        public int hashCode() {
            return (owner.name.hashCode() * 31 + name.hashCode()) * 31 + desc.hashCode();
        }

        String sig() {
            return owner.name + "." + name + desc;
        }
    }

    static final class Fld {

        final Cls owner;
        final String name;
        final String desc;
        int flags; // widened by the loader's access transformer (Exposure.loaderAccess)
        final List<Meth> accessors = new ArrayList<>();
        String constant; // a compile-time constant's value: javac inlines these, so nothing reads the field
        String id; // our stable id (field_g<n>)
        String regId; // the id it is registered under: the first string <clinit> loads for it ("doMobLoot"), no "minecraft:"

        /** From its names, as Meth's. */
        @Override
        public int hashCode() {
            return (owner.name.hashCode() * 31 + name.hashCode()) * 31 + desc.hashCode();
        }

        Fld(Cls owner, String name, String desc, int flags) {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
            this.flags = flags;
        }
    }

    static final class Cls {

        final String name;
        int flags; // widened by the loader's access transformer (Exposure.loaderAccess)
        final String sup;
        final List<String> ifaces;
        final boolean loader;
        /** From its name, as Meth's. */
        @Override
        public int hashCode() {
            return name.hashCode();
        }

        final Map<String, Meth> methods = new LinkedHashMap<>(); // name + desc
        final Map<String, Fld> fields = new LinkedHashMap<>(); // name
        final List<Cls> children = new ArrayList<>();
        String id;
        boolean library; // read from one of the release's library jars (addLibraries)
        Origin origin; // the file it was read from, the game's or the loader's; null for a library's class or a stand-in
        String artifact; // that library's Maven group and name, its version left out (com/mojang/datafixerupper)

        Cls(String name, int flags, String sup, List<String> ifaces, boolean loader) {
            this.name = name;
            this.flags = flags;
            this.sup = sup;
            this.ifaces = ifaces;
            this.loader = loader;
        }

        int kind() {
            int k = flags & (ACC_INTERFACE | ACC_ANNOTATION | ACC_ENUM);
            return "java/lang/Record".equals(sup) ? k | 0x10000 : k;
        }

        String simple() {
            return name.substring(name.lastIndexOf('/') + 1);
        }
    }

    /** Every class of one release (and of its loader build) worth linking, in Mojang names. */
    static final class Snap {

        final String version;
        String loaderVersion;
        int java; // the Java the release runs on (its version.json's javaVersion), 0 where not read from Mojang
        final Classes classes = new Classes();
        boolean code = true; // false: read without the game's code (read), each class's read when it is asked for (Meth.references)
        final Set<String> fixPairs = new HashSet<>(); // "old\0new" ids from the game's own datafixes
        final Map<String, Origin> origins = new HashMap<>(); // class -> its bytes, so Learner can read a body again
        final Map<String, Cls> libraries = new HashMap<>(); // the release's library classes, declarations only: what the learner may write calls to

        /** A library member as the JVM resolves it from owner: declared there or in a supertype. */
        Meth libraryMethod(String owner, String name, String desc) {
            for (String c = owner; c != null;) {
                Cls k = libraries.get(c);
                if (k == null) {
                    return null;
                }
                Meth m = k.methods.get(name + desc);
                if (m != null) {
                    return m;
                }
                c = k.sup;
            }
            return null;
        }

        Fld libraryField(String owner, String name) {
            Cls k = libraries.get(owner);
            return k == null ? null : k.fields.get(name);
        }

        Snap(String version) {
            this.version = version;
        }

        /**
         * The release's own classes (Classes.own), each with its code read, on every processor as a whole read reads it:
         * for a search over all of a release's code. A library's classes are declarations, and no method of theirs has
         * a reference.
         */
        List<Cls> coded() {
            List<Cls> own = classes.own();
            synchronized (this) {
                if (!coded) {
                    own.parallelStream().filter(c -> c.origin != null).forEach(c -> c.methods.values().stream().findFirst().ifPresent(Meth::references));
                    coded = true;
                }
            }
            return own;
        }

        private boolean coded;

        Meth method(String owner, String name, String desc) {
            Cls c = classes.get(owner);
            return c == null ? null : c.methods.get(name + desc);
        }

        Fld field(String owner, String name) {
            Cls c = classes.get(owner);
            return c == null ? null : c.fields.get(name);
        }

        int methodCount() {
            int n = 0;
            for (Cls c : classes.values()) {
                n += c.methods.size();
            }
            return n;
        }

        int fieldCount() {
            int n = 0;
            for (Cls c : classes.values()) {
                n += c.fields.size();
            }
            return n;
        }
    }

    /**
     * A release's classes by name, in the order they were read. A library's class is read from its jar when it is first
     * asked for: a release lists about 27,000 of them (fastutil alone has 11,000), a mod names a few dozen, and reading
     * them all was a third of reading a release. One not read yet has its name and its place in the order, so the map
     * answers and lists as one read whole does; listing the classes themselves (values) reads them.
     */
    static final class Classes extends AbstractMap<String, Cls> {

        private final LinkedHashMap<String, Object> slots = new LinkedHashMap<>(); // a Cls, or the Unread it is read from

        private Cls read(Object slot) {
            if (!(slot instanceof Unread u)) {
                return (Cls) slot;
            }
            boolean first = !u.isRead();
            Cls c = u.read();
            if (first) {
                // What own() rests on: a library is built without the game and its loader, so no class of one extends theirs.
                for (String sup : supertypes(c)) {
                    if (slots.get(sup) instanceof Cls game && game.origin != null) {
                        throw new IllegalStateException("the library class " + c.name + " (" + u.jar.library.jar() + ") extends " + sup + ", a class of the release itself");
                    }
                }
            }
            return c;
        }

        /** Whether a class is the release's own (the game's, the loader's, a stand-in's) and not a library jar's. */
        boolean own(String name) {
            return slots.get(name) instanceof Cls;
        }

        /**
         * The release's own classes, in the order of classes, no library's read: every class that can extend one of
         * them, since a library is built without the game and its loader.
         */
        List<Cls> own() {
            List<Cls> out = new ArrayList<>();
            for (Object slot : slots.values()) {
                if (slot instanceof Cls c) {
                    out.add(c);
                }
            }
            return out;
        }

        /** Whether a library jar has a class whose name starts so; none is read. */
        boolean libraryIn(String packages) {
            for (Map.Entry<String, Object> e : slots.entrySet()) {
                if (e.getValue() instanceof Unread && e.getKey().startsWith(packages)) {
                    return true;
                }
            }
            return false;
        }

        /** The classes that can extend a class: the release's own under one of its own, every class under a library's or the JDK's. */
        Collection<Cls> under(String name) {
            return own(name) ? own() : values();
        }

        @Override
        public Cls get(Object name) {
            return read(slots.get(name));
        }

        @Override
        public boolean containsKey(Object name) {
            return slots.containsKey(name);
        }

        @Override
        public Cls put(String name, Cls c) {
            return read(slots.put(name, c));
        }

        @Override
        public Cls remove(Object name) {
            return read(slots.remove(name));
        }

        @Override
        public int size() {
            return slots.size();
        }

        @Override
        public void clear() {
            slots.clear();
        }

        @Override
        public Set<String> keySet() {
            return slots.keySet();
        }

        @Override
        public Collection<Cls> values() {
            return new AbstractCollection<>() {
                @Override
                public Iterator<Cls> iterator() {
                    Iterator<Object> each = slots.values().iterator();
                    return new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            return each.hasNext();
                        }

                        @Override
                        public Cls next() {
                            return read(each.next());
                        }

                        @Override
                        public void remove() {
                            each.remove();
                        }
                    };
                }

                @Override
                public int size() {
                    return slots.size();
                }
            };
        }

        @Override
        public Set<Map.Entry<String, Cls>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public Iterator<Map.Entry<String, Cls>> iterator() {
                    Iterator<Map.Entry<String, Object>> each = slots.entrySet().iterator();
                    return new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            return each.hasNext();
                        }

                        @Override
                        public Map.Entry<String, Cls> next() {
                            Map.Entry<String, Object> e = each.next();
                            return new SimpleImmutableEntry<>(e.getKey(), read(e.getValue()));
                        }

                        @Override
                        public void remove() {
                            each.remove();
                        }
                    };
                }

                @Override
                public int size() {
                    return slots.size();
                }
            };
        }

        /**
         * The classes of the libraries a release runs with, as the JVM resolves a mod's references against them:
         * declarations only, no code, marked as not the game's (as the loader's are). A class the release already has
         * (the game's, the loader's, an earlier jar's) is kept.
         */
        void libraries(List<Library> jars) throws IOException {
            for (Library lib : jars) {
                LibraryJar jar = new LibraryJar(lib);
                for (String entry : jar.classes()) {
                    slots.putIfAbsent(entry.substring(0, entry.length() - 6), new Unread(jar, entry));
                }
            }
        }

        /** Each library class with the library it is of (null for a stand-in written in a library's place), none of them read. */
        void libraries(java.util.function.BiConsumer<String, String> each) {
            for (Map.Entry<String, Object> e : slots.entrySet()) {
                if (e.getValue() instanceof Unread u) {
                    each.accept(e.getKey(), u.jar.library.artifact());
                } else if (((Cls) e.getValue()).library) {
                    each.accept(e.getKey(), ((Cls) e.getValue()).artifact);
                }
            }
        }

        /** Adds another release's class as that release has it: the one class in both, read once, when either asks. */
        void carry(String name, Classes from) {
            slots.put(name, from.slots.get(name));
        }
    }

    /** A library class not read yet. */
    static final class Unread {

        final LibraryJar jar;
        final String entry;
        private volatile Cls read;

        Unread(LibraryJar jar, String entry) {
            this.jar = jar;
            this.entry = entry;
        }

        boolean isRead() {
            return read != null;
        }

        Cls read() {
            Cls c = read;
            if (c == null) {
                synchronized (this) {
                    c = read;
                    if (c == null) {
                        c = jar.read(entry);
                        read = c;
                    }
                }
            }
            return c;
        }
    }

    /** One library's jar, kept open while a release that lists it is: its classes are read one at a time, as they are asked for. */
    static final class LibraryJar {

        final Library library;
        private ZipFile zip;

        LibraryJar(Library library) {
            this.library = library;
        }

        private synchronized ZipFile zip() throws IOException {
            if (zip == null) {
                zip = new ZipFile(library.jar().toFile());
            }
            return zip;
        }

        /** Its class files' entries, in the jar's order. */
        List<String> classes() throws IOException {
            List<String> out = new ArrayList<>();
            for (Enumeration<? extends ZipEntry> e = zip().entries(); e.hasMoreElements();) {
                String n = e.nextElement().getName();
                if (classFile(n, null)) {
                    out.add(n);
                }
            }
            return out;
        }

        Cls read(String entry) {
            try {
                ZipFile z = zip();
                Cls c = libraryClass(z.getInputStream(z.getEntry(entry)).readAllBytes(), library);
                if (!entry.equals(c.name + ".class")) {
                    // The table lists a class by its entry, before it is read; a whole read names it from its own file.
                    throw new IllegalStateException(library.jar() + " holds the class " + c.name + " as " + entry);
                }
                return c;
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }
    }

    // ------------------------------------------------------------------ reading class files

    record RawRef(char kind, String owner, String name, String desc) {}

    static final class RawMeth {

        String name;
        String desc;
        int flags;
        boolean hasCode;
        final StringBuilder ops = new StringBuilder();
        final List<RawRef> refs = new ArrayList<>();
        final List<String> lambdas = new ArrayList<>(); // name + desc in the same class
    }

    /** Where a class of a snapshot came from: its bytes and how its references are named and resolved. */
    record Origin(byte[] bytes, Map<String, RawCls> raw, Map<String, RawCls> fallback, Names names, Map<String, Ref> resolved) {}

    static final class RawCls {

        byte[] bytes;
        String name;
        int flags;
        String sup;
        List<String> ifaces;
        final Map<String, RawMeth> methods = new LinkedHashMap<>();
        final Map<String, String[]> fields = new LinkedHashMap<>(); // name -> desc, flags, constant value or null
        final Map<String, List<String>> fieldIds = new HashMap<>(); // static field -> strings loaded before its putstatic
        final List<String[]> stringPairs = new ArrayList<>(); // consecutive string constants
        Set<String> methodKeys;

        Set<String> methodKeys() {
            return methodKeys != null ? methodKeys : methods.keySet();
        }
    }

    static String internal(ClassDesc d) {
        String s = d.descriptorString();
        return s.startsWith("L") ? s.substring(1, s.length() - 1) : s;
    }

    static char op(int code) {
        return (char) (0x100 + code);
    }

    static RawCls parse(byte[] bytes) {
        return parse(bytes, true);
    }

    /** One class file; code false: its declarations alone, each method with whether it has code and none of it. */
    static RawCls parse(byte[] bytes, boolean code) {
        ClassModel cm = ClassFile.of().parse(bytes);
        RawCls rc = new RawCls();
        rc.bytes = bytes;
        rc.name = cm.thisClass().asInternalName();
        rc.flags = cm.flags().flagsMask();
        rc.sup = cm.superclass().map(ClassEntry::asInternalName).orElse(null);
        List<String> ifs = new ArrayList<>();
        for (ClassEntry e : cm.interfaces()) {
            ifs.add(e.asInternalName());
        }
        rc.ifaces = ifs;
        for (FieldModel fm : cm.fields()) {
            rc.fields.put(
                fm.fieldName().stringValue(),
                new String[] {
                    fm.fieldType().stringValue(),
                    Integer.toString(fm.flags().flagsMask()),
                    fm.findAttribute(Attributes.constantValue()).map(a -> String.valueOf(a.constant().constantValue())).orElse(null),
                }
            );
        }
        for (MethodModel mm : cm.methods()) {
            RawMeth m = new RawMeth();
            m.name = mm.methodName().stringValue();
            m.desc = mm.methodType().stringValue();
            m.flags = mm.flags().flagsMask();
            Optional<CodeModel> body = code ? mm.code() : Optional.empty();
            m.hasCode = code ? body.isPresent() : mm.findAttribute(Attributes.code()).isPresent();
            if (body.isPresent()) {
                boolean clinit = m.name.equals("<clinit>");
                List<String> pending = new ArrayList<>();
                String previous = null;
                for (CodeElement el : body.get()) {
                    if (el instanceof Instruction ins) {
                        instruction(rc.name, m, ins);
                        String text = ins instanceof ConstantInstruction ci && ci.constantValue() instanceof String str ? str : null;
                        // Two string constants in a row: a datafix's (old id, new id) when the class is a fix.
                        if (text != null && previous != null) {
                            rc.stringPairs.add(new String[] { previous, text });
                        }
                        previous = text;
                        if (!clinit) {
                            continue;
                        }
                        // The ids a static field is registered under: strings loaded since the previous putstatic.
                        if (text != null) {
                            pending.add(text);
                        } else if (ins instanceof FieldInstruction fi && ins.opcode() == Opcode.PUTSTATIC) {
                            if (fi.owner().asInternalName().equals(rc.name) && !pending.isEmpty()) {
                                rc.fieldIds.putIfAbsent(fi.name().stringValue(), List.copyOf(pending));
                            }
                            pending.clear();
                        }
                    }
                }
            }
            rc.methods.put(m.name + m.desc, m);
        }
        return rc;
    }

    static void instruction(String self, RawMeth m, Instruction ins) {
        switch (ins) {
            case LoadInstruction li -> m.ops.append(op(300 + li.typeKind().ordinal()));
            case StoreInstruction si -> m.ops.append(op(320 + si.typeKind().ordinal()));
            case ConstantInstruction ci -> {
                m.ops.append(op(340 + ci.typeKind().ordinal()));
                ConstantDesc v = ci.constantValue();
                if (v instanceof ClassDesc cd) {
                    m.refs.add(new RawRef('c', internal(cd), null, null));
                } else if (v instanceof String s) {
                    m.refs.add(new RawRef('k', "s:" + s, null, null));
                } else if (v instanceof Number n) {
                    m.refs.add(new RawRef('k', "n:" + n, null, null));
                }
            }
            case InvokeInstruction ii -> {
                m.ops.append(op(ins.opcode().ordinal()));
                m.refs.add(
                    new RawRef('m', ii.owner().asInternalName(), ii.name().stringValue(), ii.type().stringValue())
                );
            }
            case FieldInstruction fi -> {
                m.ops.append(op(ins.opcode().ordinal()));
                m.refs.add(
                    new RawRef('f', fi.owner().asInternalName(), fi.name().stringValue(), fi.type().stringValue())
                );
            }
            case NewObjectInstruction ni -> {
                m.ops.append(op(ins.opcode().ordinal()));
                m.refs.add(new RawRef('c', ni.className().asInternalName(), null, null));
            }
            case TypeCheckInstruction ti -> {
                m.ops.append(op(ins.opcode().ordinal()));
                m.refs.add(new RawRef('c', ti.type().asInternalName(), null, null));
            }
            case NewReferenceArrayInstruction ai -> {
                m.ops.append(op(ins.opcode().ordinal()));
                m.refs.add(new RawRef('c', ai.componentType().asInternalName(), null, null));
            }
            case NewMultiArrayInstruction ai -> {
                m.ops.append(op(ins.opcode().ordinal()));
                m.refs.add(new RawRef('c', ai.arrayType().asInternalName(), null, null));
            }
            case InvokeDynamicInstruction di -> {
                m.ops.append(op(ins.opcode().ordinal()));
                m.refs.add(new RawRef('k', "indy:" + di.name().stringValue(), null, null));
                for (ConstantDesc arg : di.bootstrapArgs()) {
                    if (arg instanceof DirectMethodHandleDesc h) {
                        String owner = internal(h.owner());
                        if (owner.equals(self) && h.methodName().startsWith("lambda$")) {
                            m.lambdas.add(h.methodName() + h.lookupDescriptor());
                        } else {
                            m.refs.add(new RawRef('m', owner, h.methodName(), h.lookupDescriptor()));
                        }
                    } else if (arg instanceof String s) {
                        m.refs.add(new RawRef('k', "s:" + s, null, null));
                    }
                }
            }
            case BranchInstruction bi -> {
                Opcode o = bi.opcode();
                m.ops.append(op((o == Opcode.GOTO_W ? Opcode.GOTO : o).ordinal()));
            }
            default -> m.ops.append(op(ins.opcode().ordinal()));
        }
    }

    /** Names of one jar: obfuscated (ProGuard) or already Mojang's (identity). */
    static final class Names {

        final VersionMap.Proguard pg;
        final Map<String, String> descCache = new ConcurrentHashMap<>();

        Names(VersionMap.Proguard pg) {
            this.pg = pg;
        }

        String cls(String raw) {
            if (pg == null) {
                return raw;
            }
            if (raw.startsWith("[")) {
                return desc(raw);
            }
            return pg.classes.getOrDefault(raw, raw);
        }

        String desc(String raw) {
            if (pg == null) {
                return raw;
            }
            return descCache.computeIfAbsent(raw, d -> VersionMap.Tiny.remapDesc(d, pg.classes));
        }

        String method(String declarer, String name, String desc) {
            return pg == null ? name : pg.methods.getOrDefault(declarer + "." + name + desc, name);
        }

        String field(String declarer, String name) {
            return pg == null ? name : pg.fields.getOrDefault(declarer + "." + name, name);
        }
    }

    /** The class that declares a member a reference names: the owner itself or one of its supertypes. */
    static String declarer(Map<String, RawCls> g, Map<String, RawCls> fallback, String owner, String name, String desc, boolean field) {
        ArrayDeque<String> todo = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        todo.add(owner);
        while (!todo.isEmpty()) {
            String c = todo.poll();
            if (!seen.add(c)) {
                continue;
            }
            RawCls rc = g.get(c);
            if (rc == null && fallback != null) {
                rc = fallback.get(c);
            }
            if (rc == null) {
                continue;
            }
            if (field ? rc.fields.containsKey(name) : rc.methodKeys().contains(name + desc)) {
                return c;
            }
            if (rc.sup != null) {
                todo.add(rc.sup);
            }
            todo.addAll(rc.ifaces);
        }
        return null;
    }

    static boolean gameClass(String named) {
        return named.startsWith("net/minecraft/") || named.startsWith("com/mojang/");
    }

    /** Foo$1 and Foo$1Local are compiler-made classes no mod can name. */
    static boolean anonymous(String internal) {
        int d = internal.lastIndexOf('$');
        return d >= 0 && d + 1 < internal.length() && Character.isDigit(internal.charAt(d + 1));
    }

    /** Read class files into a snapshot: game classes (names through pg if obfuscated) and loader classes (Mojang names). */
    static Snap read(String version, List<byte[]> game, VersionMap.Proguard pg, List<byte[]> loader) {
        return read(version, game, pg, loader, true);
    }

    /**
     * code false: the classes' declarations alone, for a port. A method's code is read when it is asked for (Meth.references);
     * what only the whole game's code tells (a field's accessors and registry id, the datafixes' id pairs) is not read,
     * and the learner, which reads it, refuses such a release (GameHop).
     */
    static Snap read(String version, List<byte[]> game, VersionMap.Proguard pg, List<byte[]> loader, boolean code) {
        Snap s = new Snap(version);
        s.code = code;
        Names names = new Names(pg);
        Map<String, RawCls> raw = parseAll(game, code);
        // The game's classes in Mojang names, for resolving loader references through game supertypes.
        Map<String, RawCls> named = new HashMap<>();
        for (RawCls rc : raw.values()) {
            RawCls n = new RawCls();
            n.name = names.cls(rc.name);
            n.sup = rc.sup == null ? null : names.cls(rc.sup);
            n.ifaces = rc.ifaces.stream().map(names::cls).toList();
            Set<String> keys = new HashSet<>();
            for (RawMeth m : rc.methods.values()) {
                keys.add(names.method(rc.name, m.name, m.desc) + names.desc(m.desc));
            }
            n.methodKeys = keys;
            for (var f : rc.fields.entrySet()) {
                n.fields.put(names.field(rc.name, f.getKey()), f.getValue());
            }
            named.put(n.name, n);
        }
        convert(s, raw, null, names, false);
        if (loader != null && !loader.isEmpty()) {
            Map<String, RawCls> lraw = parseAll(loader, code);
            convert(s, lraw, named, new Names(null), true);
        }
        for (Cls c : s.classes.values()) {
            for (Meth m : code ? c.methods.values() : List.<Meth>of()) {
                for (Ref r : m.refs) {
                    if (r.kind() == 'f') {
                        Fld f = s.field(r.owner(), r.name());
                        if (f != null && !f.accessors.contains(m)) {
                            f.accessors.add(m);
                        }
                    }
                }
            }
            for (String sup : supertypes(c)) {
                Cls p = s.classes.get(sup);
                if (p != null) {
                    p.children.add(c);
                }
            }
        }
        return s;
    }

    /** A registry id without the default namespace: "minecraft:mob_drops" and "mob_drops" are one id. */
    static String id(String s) {
        return s.startsWith("minecraft:") ? s.substring(10) : s;
    }

    static List<String> supertypes(Cls c) {
        List<String> l = new ArrayList<>();
        if (c.sup != null) {
            l.add(c.sup);
        }
        l.addAll(c.ifaces);
        return l;
    }

    static Map<String, RawCls> parseAll(List<byte[]> classes) {
        return parseAll(classes, true);
    }

    static Map<String, RawCls> parseAll(List<byte[]> classes, boolean code) {
        Map<String, RawCls> raw = new ConcurrentHashMap<>();
        classes.parallelStream().forEach(b -> {
            RawCls rc = parse(b, code);
            raw.put(rc.name, rc);
        });
        return raw;
    }

    static void convert(Snap s, Map<String, RawCls> raw, Map<String, RawCls> fallback, Names names, boolean loader) {
        Map<String, Ref> resolved = new ConcurrentHashMap<>();
        List<RawCls> keep = new ArrayList<>();
        for (RawCls rc : raw.values()) {
            String n = names.cls(rc.name);
            if (!loader && n.startsWith("net/minecraft/util/datafix/fixes/")) {
                for (String[] p : rc.stringPairs) {
                    s.fixPairs.add(id(p[0]) + "\0" + id(p[1]));
                }
            }
            boolean inside = loader ? !gameClass(n) : gameClass(n); // the loader's classes are the ones its caller chose (Exposure.snap)
            if (!inside || anonymous(n) || (rc.flags & ACC_SYNTHETIC) != 0) {
                continue;
            }
            keep.add(rc);
        }
        keep.sort(Comparator.comparing(rc -> names.cls(rc.name)));
        List<Cls> made = keep
            .parallelStream()
            .map(rc -> {
                Cls c = new Cls(
                    names.cls(rc.name),
                    rc.flags,
                    rc.sup == null ? null : names.cls(rc.sup),
                    rc.ifaces.stream().map(names::cls).toList(),
                    loader
                );
                for (RawMeth rm : rc.methods.values()) {
                    if (!declared(rm)) {
                        continue;
                    }
                    Meth m = new Meth(c, names.method(rc.name, rm.name, rm.desc), names.desc(rm.desc), rm.flags);
                    m.hasCode = rm.hasCode;
                    m.ops = null;
                    m.refs = null;
                    c.methods.put(m.name + m.desc, m);
                }
                if (s.code) {
                    code(c, rc, raw, fallback, names, resolved);
                }
                for (var f : rc.fields.entrySet()) {
                    int ff = Integer.parseInt(f.getValue()[1]);
                    if ((ff & ACC_SYNTHETIC) != 0 || f.getKey().contains("$")) {
                        continue;
                    }
                    Fld fd = new Fld(c, names.field(rc.name, f.getKey()), names.desc(f.getValue()[0]), ff);
                    fd.constant = f.getValue()[2];
                    List<String> loaded = rc.fieldIds.get(f.getKey());
                    fd.regId = loaded == null ? null : id(loaded.get(0));
                    c.fields.put(fd.name, fd);
                }
                return c;
            })
            .toList();
        for (int i = 0; i < made.size(); i++) {
            made.get(i).origin = new Origin(keep.get(i).bytes, raw, fallback, names, resolved);
            s.classes.put(made.get(i).name, made.get(i));
            s.origins.put(made.get(i).name, made.get(i).origin);
        }
    }

    /** Whether a method of a class file is one of the class's own: not one the compiler added (a bridge, a lambda's body). */
    static boolean declared(RawMeth rm) {
        return (rm.flags & (ACC_SYNTHETIC | ACC_BRIDGE)) == 0 && !rm.name.contains("$");
    }

    /** Gives each method of c its opcodes and references from rc, the class's file read with its code. */
    static void code(Cls c, RawCls rc, Map<String, RawCls> raw, Map<String, RawCls> fallback, Names names, Map<String, Ref> resolved) {
        for (RawMeth rm : rc.methods.values()) {
            if (!declared(rm)) {
                continue;
            }
            // Two of a class file's methods one name in Mojang's names: the class has the one read last, with its own code.
            Meth m = c.methods.get(names.method(rc.name, rm.name, rm.desc) + names.desc(rm.desc));
            StringBuilder ops = new StringBuilder();
            List<Ref> refs = new ArrayList<>();
            body(rc, rm, ops, refs, raw, fallback, names, resolved, new HashSet<>(), 0);
            m.ops = ops.toString();
            m.refs = refs.toArray(new Ref[0]);
        }
    }

    /** One method's opcodes and references, with the bodies of its lambdas inlined. */
    static void body(
        RawCls rc,
        RawMeth rm,
        StringBuilder ops,
        List<Ref> refs,
        Map<String, RawCls> raw,
        Map<String, RawCls> fallback,
        Names names,
        Map<String, Ref> resolved,
        Set<String> seen,
        int depth
    ) {
        if (!seen.add(rm.name + rm.desc)) {
            return;
        }
        ops.append(rm.ops);
        for (RawRef r : rm.refs) {
            refs.add(resolve(r, raw, fallback, names, resolved));
        }
        if (depth > 4) {
            return;
        }
        for (String l : rm.lambdas) {
            RawMeth lm = rc.methods.get(l);
            if (lm != null) {
                body(rc, lm, ops, refs, raw, fallback, names, resolved, seen, depth + 1);
            }
        }
    }

    static Ref resolve(RawRef r, Map<String, RawCls> raw, Map<String, RawCls> fallback, Names names, Map<String, Ref> cache) {
        switch (r.kind()) {
            case 'k' -> {
                return new Ref('k', r.owner(), null, null);
            }
            case 'c' -> {
                return new Ref('c', names.cls(r.owner()), null, null);
            }
            default -> {
                String key = r.kind() + r.owner() + "." + r.name() + r.desc();
                Ref hit = cache.get(key);
                if (hit != null) {
                    return hit;
                }
                boolean field = r.kind() == 'f';
                String d = r.owner().startsWith("[") ? null : declarer(raw, fallback, r.owner(), r.name(), r.desc(), field);
                Ref out;
                if (d == null) {
                    out = new Ref(r.kind(), names.cls(r.owner()), r.name(), names.desc(r.desc()));
                } else if (field) {
                    out = new Ref('f', names.cls(d), names.field(d, r.name()), names.desc(r.desc()));
                } else {
                    out = new Ref('m', names.cls(d), names.method(d, r.name(), r.desc()), names.desc(r.desc()));
                }
                cache.put(key, out);
                return out;
            }
        }
    }

    // ------------------------------------------------------------------ downloads

    static String manifest;

    static String versionJson(String version) throws Exception {
        if (manifest == null) {
            Path m = VersionMap.cache.resolve("version_manifest_v2.json");
            String text = Files.isRegularFile(m) ? Files.readString(m) : "";
            if (!text.contains("\"" + version + "\"")) {
                Files.deleteIfExists(m);
            }
            manifest = VersionMap.fetchText(MANIFEST, "version_manifest_v2.json");
        }
        Matcher at = Pattern.compile("\"id\"\\s*:\\s*\"" + Pattern.quote(version) + "\"").matcher(manifest);
        if (!at.find()) {
            throw new IOException("Minecraft " + version + " is not in Mojang's version list");
        }
        return VersionMap.fetchText(VersionMap.jsonString(manifest, "url", at.start()), "version-" + version + ".json");
    }

    /** The url and sha1 of one entry under "downloads" in a version json. */
    static String[] download(String versionJson, String key) {
        int d = versionJson.indexOf("\"downloads\"");
        int k = d < 0 ? -1 : versionJson.indexOf("\"" + key + "\"", d);
        if (k < 0) {
            return null;
        }
        int end = versionJson.indexOf('}', k);
        String obj = versionJson.substring(k, end);
        return new String[] { VersionMap.jsonString(obj, "url", 0), VersionMap.jsonString(obj, "sha1", 0) };
    }

    /** One library jar a release lists, with its Maven group and name (com/mojang/datafixerupper): the same library in another version shares it. */
    record Library(String artifact, Path jar) {}

    /**
     * The jars of the libraries a release runs with (JOML, DataFixerUpper, Brigadier, fastutil, LWJGL...), as its
     * version json lists them, each fetched once and checked by its sha1. Natives are left out: they hold no classes.
     */
    static List<Library> libraries(String versionJson) throws Exception {
        List<Library> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"artifact\"\\s*:\\s*\\{([^}]*)}").matcher(versionJson);
        Set<String> seen = new HashSet<>();
        while (m.find()) {
            String obj = m.group(1);
            String path = VersionMap.jsonString(obj, "path", 0);
            if (path == null || !path.endsWith(".jar") || path.contains("natives") || !seen.add(path)) {
                continue;
            }
            String version = path.substring(0, path.lastIndexOf('/'));
            out.add(new Library(version.substring(0, version.lastIndexOf('/')), verified(new String[] { VersionMap.jsonString(obj, "url", 0), VersionMap.jsonString(obj, "sha1", 0) },
                "lib-" + path.substring(path.lastIndexOf('/') + 1))));
        }
        return out;
    }

    /**
     * Library classes as the JVM resolves a mod's references against them: declarations only, no code, marked as not the
     * game's (as the loader's are). A class the snapshot already has (the game's, the loader's, an earlier jar's) is kept.
     */
    static void addLibraries(Map<String, Cls> into, List<Library> jars) throws IOException {
        for (Library lib : jars) {
            for (byte[] bytes : classes(lib.jar(), null)) {
                Cls c = libraryClass(bytes, lib);
                into.putIfAbsent(c.name, c);
            }
        }
    }

    /** One class of a library: its declarations. */
    static Cls libraryClass(byte[] bytes, Library lib) {
        ClassModel cm = ClassFile.of().parse(bytes);
        List<String> ifs = new ArrayList<>();
        for (ClassEntry e : cm.interfaces()) {
            ifs.add(e.asInternalName());
        }
        Cls c = new Cls(cm.thisClass().asInternalName(), cm.flags().flagsMask(), cm.superclass().map(ClassEntry::asInternalName).orElse(null), ifs, true);
        c.library = true;
        c.artifact = lib.artifact();
        for (FieldModel fm : cm.fields()) {
            c.fields.put(fm.fieldName().stringValue(), new Fld(c, fm.fieldName().stringValue(), fm.fieldType().stringValue(), fm.flags().flagsMask()));
        }
        for (MethodModel mm : cm.methods()) {
            Meth m = new Meth(c, mm.methodName().stringValue(), mm.methodType().stringValue(), mm.flags().flagsMask());
            c.methods.put(m.name + m.desc, m);
        }
        return c;
    }

    static Path verified(String[] dl, String name) throws Exception {
        Path p = VersionMap.fetch(dl[0], name);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(p)));
        if (!sha.equalsIgnoreCase(dl[1])) {
            Files.delete(p);
            throw new IOException(name + " failed its sha1 check");
        }
        return p;
    }

    /** Whether a jar's entry is a class to read: under prefix when one is given, and not a module's or a package's own file. */
    static boolean classFile(String entry, String prefix) {
        if (!entry.endsWith(".class") || entry.startsWith("META-INF/") || entry.endsWith("module-info.class")) {
            return false;
        }
        return !entry.endsWith("package-info.class") && (prefix == null || entry.startsWith(prefix));
    }

    static List<byte[]> classes(Path jar, String prefix) throws IOException {
        List<byte[]> l = new ArrayList<>();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
                ZipEntry ze = e.nextElement();
                if (!classFile(ze.getName(), prefix)) {
                    continue;
                }
                l.add(z.getInputStream(ze).readAllBytes());
            }
        }
        return l;
    }

    /** A release's client jar as Mojang ships it: its class files, and its mappings (null from 26.1, which ships in Mojang's names). */
    record Client(List<byte[]> classes, VersionMap.Proguard names) {}

    /**
     * A release's client jar, read once per process and only read after: a release's snapshot and the port's reading of
     * the game's code (GameCode) both start from it, and each unzipped the jar and parsed its mappings for itself, every
     * worker of a port at once.
     */
    static Client client(String version) throws Exception {
        try {
            return CLIENTS.computeIfAbsent(version, v -> {
                try {
                    String vj = versionJson(v);
                    String[] m = download(vj, "client_mappings");
                    VersionMap.Proguard names = m == null ? null : VersionMap.Proguard.read(verified(m, "client-" + v + ".txt"));
                    return new Client(classes(verified(download(vj, "client"), "client-" + v + ".jar"), null), names);
                } catch (Exception failed) {
                    throw new CompletionException(failed);
                }
            });
        } catch (CompletionException failed) {
            throw failed.getCause() instanceof Exception e ? e : failed;
        }
    }

    static final Map<String, Client> CLIENTS = new ConcurrentHashMap<>();

    static final Pattern JAVA_VERSION = Pattern.compile("\"javaVersion\"\\s*:\\s*\\{[^}]*\"majorVersion\"\\s*:\\s*(\\d+)");

    /** The Java a release runs on, as its version.json names it; Java 8 for the releases from before it named one. */
    static int javaOf(String versionJson) {
        Matcher m = JAVA_VERSION.matcher(versionJson);
        return m.find() ? Integer.parseInt(m.group(1)) : 8;
    }

    /** One release from Mojang, plus a loader jar when given. */
    static Snap load(String version, Path loaderJar, String loaderVersion) throws Exception {
        return load(version, loaderJar == null ? List.of() : classes(loaderJar, "net/neoforged/"), loaderVersion);
    }

    /** One release from Mojang, plus the loader's classes (in Mojang names) when given. */
    static Snap load(String version, List<byte[]> loader, String loaderVersion) throws Exception {
        long t0 = System.nanoTime();
        String vj = versionJson(version);
        Path jar = verified(download(vj, "client"), "client-" + version + ".jar");
        String[] m = download(vj, "client_mappings");
        VersionMap.Proguard pg = null;
        if (m != null) {
            pg = VersionMap.Proguard.parse(Files.readString(verified(m, "client-" + version + ".txt"), StandardCharsets.UTF_8));
        }
       Snap s = read(version, classes(jar, null), pg, loader);
        addLibraries(s.libraries, libraries(vj));
        s.loaderVersion = loaderVersion;
        s.java = javaOf(vj);
        System.out.printf(
            "  read %s%s: %,d classes, %,d methods, %,d fields (%s names, %.1fs)%n",
            version,
            loaderVersion == null ? "" : " + neoforge " + loaderVersion,
            s.classes.size(),
            s.methodCount(),
            s.fieldCount(),
            pg == null ? "jar" : "Mojang mapped",
            (System.nanoTime() - t0) / 1e9
        );
        return s;
    }

    /** NeoForge's own versions: 1.21.4 -> 21.4.x, 1.21 -> 21.0.x, 26.1.2 -> 26.1.2.x, 26.2 -> 26.2.0.x. */
    static String neoforgeFor(String mc, String metadata) {
        String[] p = mc.split("\\.");
        String prefix;
        if (p[0].equals("1")) {
            if (p.length < 2) {
                return null;
            }
            prefix = p[1] + "." + (p.length > 2 ? p[2] : "0") + ".";
        } else {
            prefix = p.length == 2 ? mc + ".0." : mc + ".";
        }
        Matcher vm = Pattern.compile("<version>([^<]+)</version>").matcher(metadata);
        String best = null;
        long bestNum = -1;
        boolean bestStable = false;
        while (vm.find()) {
            String v = vm.group(1);
            if (!v.startsWith(prefix)) {
                continue;
            }
            String rest = v.substring(prefix.length());
            Matcher rm = Pattern.compile("(\\d+)(-beta)?").matcher(rest);
            if (!rm.matches()) {
                continue;
            }
            long n = Long.parseLong(rm.group(1));
            boolean stable = rm.group(2) == null;
            if ((stable && !bestStable) || (stable == bestStable && n > bestNum)) {
                best = v;
                bestNum = n;
                bestStable = stable;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ hashing

    static long fnv(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** Multiset Jaccard of two sorted arrays. */
    static double jaccard(long[] a, long[] b) {
        if (a.length == 0 && b.length == 0) {
            return 1;
        }
        int i = 0;
        int j = 0;
        int common = 0;
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                common++;
                i++;
                j++;
            } else if (a[i] < b[j]) {
                i++;
            } else {
                j++;
            }
        }
        return (double) common / (a.length + b.length - common);
    }

    static double jaccard(int[] a, int[] b) {
        if (a.length == 0 && b.length == 0) {
            return 1;
        }
        int i = 0;
        int j = 0;
        int common = 0;
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                common++;
                i++;
                j++;
            } else if (a[i] < b[j]) {
                i++;
            } else {
                j++;
            }
        }
        return (double) common / (a.length + b.length - common);
    }

    // ------------------------------------------------------------------ linking two releases

    /** Links release b to release a (the one before it). */
    /**
     * Renames a loader published for its own API with a release, read from its own files. Fabric API renamed its classes
     * and members to Mojang's names for 26.1 and listed every one: an IntelliJ migration map of the classes and its porting
     * page, which gives each class's renamed members. A renamed package and class (keybinding.v1.KeyBindingHelper ->
     * keymapping.v1.KeyMappingHelper) shares no name and little code with the old one, so linking alone misses most.
     * The files are pinned to one commit of the docs, so the map is the same run to run.
     */
    record Published(Map<String, String> classes, Map<String, Map<String, String>> members) {

        static final Published NONE = new Published(Map.of(), Map.of());

        /** loader, the release the renames came with, the class map (IntelliJ migration map) and the page listing members. */
        static final String[][] SOURCES = {
            { "fabric", "26.1",
                "https://raw.githubusercontent.com/FabricMC/fabric-docs/e75b101b101d64ebbd025a73b8693ef79b0ca69f/public/assets/develop/porting/fabric-api-26-1-migration-map.xml",
                "https://raw.githubusercontent.com/FabricMC/fabric-docs/e75b101b101d64ebbd025a73b8693ef79b0ca69f/versions/26.1.2/develop/porting/fabric-api.md" },
        };

        static final Pattern ENTRY = Pattern.compile("<entry\\s+oldName=\"([^\"]+)\"\\s+newName=\"([^\"]+)\"\\s+type=\"class\"");
        static final Pattern OWNER = Pattern.compile("^- `([^`]+)`(?:\\s*\u2192\\s*`([^`]+)`)?\\s*$");
        static final Pattern MEMBER = Pattern.compile("^\\s+- `([^`]+)`\\s*\u2192\\s*`([^`]+)`\\s*$");

        /** What the loader published as renamed between two releases (from, to]: none for another loader or no list. */
        static Published read(String loader, String from, String to) throws Exception {
            Map<String, String> classes = new LinkedHashMap<>();
            Map<String, Map<String, String>> members = new LinkedHashMap<>();
            for (String[] src : SOURCES) {
                if (!src[0].equals(loader) || Exposure.compare(from, src[1]) >= 0 || Exposure.compare(to, src[1]) < 0) {
                    continue;
                }
                String tag = src[0] + "-" + src[1] + "-" + src[2].split("/")[5].substring(0, 12);
                parse(VersionMap.fetchText(src[2], tag + "-renamed-classes.xml"), VersionMap.fetchText(src[3], tag + "-renamed-members.md"), classes, members);
            }
            return classes.isEmpty() && members.isEmpty() ? NONE : new Published(classes, members);
        }

        static void parse(String classMap, String page, Map<String, String> classes, Map<String, Map<String, String>> members) {
            Matcher e = ENTRY.matcher(classMap);
            while (e.find()) {
                classes.put(internal(e.group(1)), internal(e.group(2)));
            }
            String owner = null;
            for (String line : page.split("\\R")) {
                Matcher o = OWNER.matcher(line), m = MEMBER.matcher(line);
                if (o.matches() && o.group(1).contains("/")) {
                    owner = o.group(1);
                    if (o.group(2) != null) {
                        String to = o.group(2).contains("/") ? o.group(2) : owner.substring(0, owner.lastIndexOf('/') + 1) + o.group(2);
                        classes.putIfAbsent(owner, to);
                    }
                } else if (m.matches() && owner != null) {
                    members.computeIfAbsent(owner, k -> new LinkedHashMap<>()).put(m.group(1), m.group(2));
                } else if (!line.isBlank() && !line.startsWith(" ")) {
                    owner = null;
                }
            }
        }

        /** a.b.C$D -> a/b/C$D */
        static String internal(String dotted) {
            int dollar = dotted.indexOf('$');
            return dollar < 0 ? dotted.replace('.', '/') : dotted.substring(0, dollar).replace('.', '/') + dotted.substring(dollar);
        }
    }

    // ------------------------------------------------------------------ ids and tables

    /** Walks the chain one release at a time, carrying ids forward, and hands VersionMap.Chain one table per release. */
    // ------------------------------------------------------------------ replacements learned from the game's own code

    /**
     * One value or statement of a method body, rebuilt from its bytecode. op: p a parameter (value: its slot), l a local
     * nothing was stored to, k a constant, n null, g a static field, f an instance field, s a static call, v an instance
     * call, w new (ref: the constructor), u an object not constructed yet, t a checkcast, x a primitive conversion, y a
     * lambda or method reference (value: the interface method), o any other operation (value: its name), S a statement
     * (value: its kind). type is the value's descriptor, in the snapshot's names.
     */
    static final class Node {

        final char op;
        final Ref ref;
        final Object value;
        final String type;
        final Node[] kids;
        String body; // a lambda of the method's own class: its raw name + desc
        int at = -1; // the instruction (by its number in the method) that made this call, field read or statement
        int slot = -1; // a store statement's local

        Node(char op, Ref ref, Object value, String type, Node... kids) {
            this.op = op;
            this.ref = ref;
            this.value = value;
            this.type = type;
            this.kids = kids;
        }
    }

    /** A method body as statements, a local's value written into where it is read; and its own lambda bodies, in order. */
    static final class Body {

        final List<Node> statements = new ArrayList<>();
        final List<String> lambdas = new ArrayList<>(); // raw name + desc in the same class
        boolean whole; // every instruction was read
    }
}

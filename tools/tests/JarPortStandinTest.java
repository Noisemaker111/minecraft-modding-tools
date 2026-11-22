import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * Stand-ins (knowledge/standins/FROM-TO/), run on this JVM against real classes of the new release. The update dropped
 * Gauge.read() (the new Gauge has doubled()), the class OldBook (an item with id 7 now) and Vault.peek() (the new Vault
 * keeps the value behind a private secret()), and Gauge.zone(Zone) takes the class Zone the update also dropped. Each stand-in is a Java source the port compiles against the new release;
 * the ported jar carries the ones it uses under a package of its own, calls them in place of the old members, answers
 * instanceof OldBook through one, and opens the closed member a stand-in reaches through the mod's access transformer.
 * A stand-in that answers no old member is refused, and a jar that needs none carries none.
 */
final class JarPortStandinTest {

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        check(scratch);
        JarPortTest.finish("JarPortStandinTest");
    }

    static void check(Path scratch) throws Exception {
        GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
        b.loaderVersion = "2.0.1";
        JarPortTest.snap(a, "s9/Gauge", 0, null, "<init>()V", "read()I", "zone(Ls9/Zone;)I");
        JarPortTest.snap(a, "s9/Zone", 0, null, "<init>()V");
        JarPortTest.snap(b, "s9/Gauge", 0, null, "<init>()V", "doubled()I");
        JarPortTest.snap(a, "s9/OldBook", 0, "s9/Item", "<init>()V");
        for (GameDiff.Snap s : List.of(a, b)) {
            GameDiff.Cls item = JarPortTest.snap(s, "s9/Item", 0, null, "<init>(I)V");
            item.fields.put("id", new GameDiff.Fld(item, "id", "I", Opcodes.ACC_PUBLIC));
        }
        JarPortTest.snap(a, "s9/Vault", 0, null, "<init>()V", "peek()I");
        GameDiff.Cls vault = JarPortTest.snap(b, "s9/Vault", 0, null, "<init>()V");
        vault.methods.put("secret()I", new GameDiff.Meth(vault, "secret", "()I", Opcodes.ACC_PRIVATE));

        Path book = Files.createDirectories(scratch.resolve("standin-kb")).resolve("fixes.json");
        Files.writeString(book, "{\"rules\": []}");
        Path dir = scratch.resolve("standin-kb/standins/1.0-2.0/s9");
        write(dir.resolve("Gauge.java"), """
            package mmt.standin.s9;
            public final class Gauge {
                public static int read(s9.Gauge self) {
                    return self.doubled() / 2;
                }
                public static int zone(s9.Gauge self, mmt.standin.s9.Zone zone) {
                    return zone.size() + read(self);
                }
            }
            """);
        // A gone class's stand-in, named after Gauge: Gauge.zone above matches it whatever order the two are read in.
        write(dir.resolve("Zone.java"), """
            package mmt.standin.s9;
            public final class Zone {
                public int size() {
                    return 3;
                }
            }
            """);
        write(dir.resolve("OldBook.java"), """
            package mmt.standin.s9;
            public final class OldBook {
                public static boolean isInstance(Object value) {
                    return value instanceof s9.Item item && item.id == 7;
                }
            }
            """);
        write(dir.resolve("Vault.java"), """
            package mmt.standin.s9;
            public final class Vault {
                public static int peek(s9.Vault self) {
                    return self.secret();
                }
            }
            """);
        // Another loader's stand-ins (FROM-TO.LOADER) use that loader's classes and are not read for this one.
        write(scratch.resolve("standin-kb/standins/1.0-2.0.fabric/s9/Gauge.java"), """
            package mmt.standin.s9;
            public final class Gauge {
                public static int read(s9.Gauge self) {
                    return net.fabricmc.Only.value();
                }
            }
            """);
        Path map = scratch.resolve("route-standin.json");
        Files.writeString(map, "{}");
        JarPort port = new JarPort(a, b, map);
        port.fixes(book);

        Map<String, byte[]> modFiles = new HashMap<>();
        modFiles.put("n9/Mod.class", mod());
        modFiles.put("META-INF/neoforge.mods.toml", "modLoader=\"javafml\"\n[[mods]]\nmodId=\"n9\"\n".getBytes(StandardCharsets.UTF_8));
        List<Map<String, Object>> runs = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(modFiles), "standin.jar", runs));
        List<String> unlinked = Json.array(runs.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(unlinked.isEmpty(), "every reference a stand-in answers links: " + unlinked);
        List<String> carried = ported.keySet().stream().filter(n -> n.startsWith("mmt/standin/")).sorted().toList();
        JarPortTest.expect(carried.size() == 2 && carried.stream().allMatch(n -> n.matches("mmt/standin/m[0-9a-f]+/s9/(Gauge|Vault)\\.class")),
            "the jar carries each stand-in it uses under a package of its own: " + carried);
        Map<String, byte[]> types = types(ported, false);
        JarPortTest.expect(types.keySet().stream().filter(n -> n.endsWith(".class")).toList().size() == 2 && types.keySet().stream().filter(n -> n.matches("mmt/standin/u[0-9a-f]+/s9/(OldBook|Zone)\\.class")).count() == 2,
            "the stand-in types (Zone and OldBook, classes the update dropped) are carried in the update's jar of types, nested as a library: " + types.keySet());
        String at = new String(ported.getOrDefault("META-INF/accesstransformer.cfg", new byte[0]), StandardCharsets.UTF_8);
        JarPortTest.expect(at.contains("public s9.Vault secret()I"), "the closed member a stand-in reaches is opened through the mod's access transformer: " + at);

        Map<String, byte[]> classes = new HashMap<>(game());
        Map<String, byte[]> all = new HashMap<>(ported);
        all.putAll(types);
        for (var e : all.entrySet()) {
            if (e.getKey().endsWith(".class")) {
                classes.put(e.getKey().substring(0, e.getKey().length() - 6), e.getValue());
            }
        }
        ClassLoader loader = new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) {
                    return Class.forName(name, false, ClassLoader.getPlatformClassLoader());
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        String trace;
        try {
            Class<?> mod = loader.loadClass("n9.Mod"), gauge = loader.loadClass("s9.Gauge"), item = loader.loadClass("s9.Item");
            Object read = mod.getMethod("read", gauge).invoke(null, gauge.getConstructor().newInstance());
            Object book7 = mod.getMethod("isBook", Object.class).invoke(null, item.getConstructor(int.class).newInstance(7));
            Object book3 = mod.getMethod("isBook", Object.class).invoke(null, item.getConstructor(int.class).newInstance(3));
            Object zone = mod.getMethod("zone", gauge).invoke(null, gauge.getConstructor().newInstance());
            trace = read + ";" + book7 + ";" + book3 + ";" + zone;
        } catch (ReflectiveOperationException | LinkageError failed) {
            trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
        JarPortTest.expect(trace.equals("42;true;false;45"), "the ported mod runs the stand-ins against the new release: " + trace);

        nested(port);

        List<Map<String, Object>> plainRuns = new ArrayList<>();
        Map<String, byte[]> plain = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("n9/Plain.class", plain())), "plain.jar", plainRuns));
        JarPortTest.expect(plain.keySet().stream().noneMatch(n -> n.startsWith("mmt/")), "a jar that needs no stand-in carries none: " + plain.keySet());

        write(dir.resolve("Gauge.java"), """
            package mmt.standin.s9;
            public final class Gauge {
                public static int reed(s9.Gauge self) {
                    return 0;
                }
            }
            """);
        String refused;
        try {
            new JarPort(a, b, map).fixes(book);
            refused = "accepted";
        } catch (IllegalArgumentException wrong) {
            refused = wrong.getMessage();
        }
        JarPortTest.expect(refused.contains("answers no member of s9.Gauge"), "a stand-in that answers no old member is refused, not ignored: " + refused);

        String outer = JarPort.standinHome("mod.jar", "1.0", "2.0"), inner = JarPort.standinHome("mod.jar!library.jar", "1.0", "2.0");
        JarPortTest.expect(!outer.equals(inner) && outer.matches("mmt/standin/m[0-9a-f]+/") && inner.matches("mmt/standin/m[0-9a-f]+/"),
            "a mod and a jar nested in it carry their stand-ins in packages of their own (one module each on NeoForge): " + outer + " " + inner);

        // A stand-in runs on the new release's Java: 1.20.3 runs on Java 17, which cannot load Java 21's class files.
        Path old = scratch.resolve("standin-java/standins/1.20.2-1.20.3/s9");
        write(old.resolve("Gauge.java"), """
            package mmt.standin.s9;
            public final class Gauge {
                public static int read() {
                    return 1;
                }
            }
            """);
        Map<String, Integer> majors = new TreeMap<>();
        for (String release : List.of("1.16.5", "1.17.1", "1.20.3", "1.21.1", "26.1")) {
            byte[] gauge = JarPort.compileStandins(List.of(old.getParent()), new GameDiff.Snap(release)).get("mmt/standin/s9/Gauge");
            majors.put(release, ((gauge[6] & 0xff) << 8) | (gauge[7] & 0xff));
        }
        JarPortTest.expect(majors.equals(Map.of("1.16.5", 52, "1.17.1", 60, "1.20.3", 61, "1.21.1", 65, "26.1", 65)),
            "each release's stand-ins are compiled for the Java it runs on: " + majors);
    }

    /**
     * A library nested in a mod (jar-in-jar: META-INF/jarjar on NeoForge, fabric.mod.json's "jars" on Fabric) whose Java 8
     * interface calls an old member, as fabric-permissions-api's Permissions (nested in spark, veinminer and others) calls
     * Entity.createCommandSourceStack(): ported with the same stand-ins as its outer mod, written in a method the interface
     * may hold (public: Java 8 has no private interface methods), and carried by the library itself, which the loader loads
     * as a module, or a mod, of its own.
     */
    static void nested(JarPort port) throws Exception {
        for (boolean fabric : List.of(false, true)) {
            Map<String, byte[]> inner = new HashMap<>();
            inner.put("n9/lib/Lib.class", java8Interface());
            inner.put("n9/lib/Maker.class", maker());
            inner.put(fabric ? "fabric.mod.json" : "META-INF/neoforge.mods.toml", (fabric ? "{\"schemaVersion\": 1, \"id\": \"n9lib\", \"version\": \"1\"}"
                : "modLoader=\"javafml\"\n[[mods]]\nmodId=\"n9lib\"\n").getBytes(StandardCharsets.UTF_8));
            String innerPath = fabric ? "META-INF/jars/n9lib.jar" : "META-INF/jarjar/n9lib.jar";
            Map<String, byte[]> outer = new HashMap<>();
            outer.put("n9/Plain.class", plain());
            outer.put("n9/Use.class", use());
            outer.put(innerPath, JarPortTest.jar(inner));
            if (fabric) {
                outer.put("fabric.mod.json", "{\"schemaVersion\": 1, \"id\": \"n9\", \"version\": \"1\", \"jars\": [{\"file\": \"META-INF/jars/n9lib.jar\"}]}".getBytes(StandardCharsets.UTF_8));
            } else {
                outer.put("META-INF/neoforge.mods.toml", "modLoader=\"javafml\"\n[[mods]]\nmodId=\"n9\"\n".getBytes(StandardCharsets.UTF_8));
                outer.put("META-INF/jarjar/metadata.json", ("{\"jars\": [{\"identifier\": {\"group\": \"n9\", \"artifact\": \"n9lib\"}, \"path\": \"" + innerPath + "\"}]}").getBytes(StandardCharsets.UTF_8));
            }
            String saved = port.loader;
            port.loader = fabric ? "fabric" : "neoforge";
            List<Map<String, Object>> runs = new ArrayList<>();
            Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(outer), "outer.jar", runs, null));
            port.loader = saved;
            List<String> unlinked = runs.stream().flatMap(r -> Json.array(r.get("unlinked")).stream()).map(String::valueOf).toList();
            JarPortTest.expect(unlinked.isEmpty(), (fabric ? "Fabric" : "NeoForge") + ": a nested library's references a stand-in answers link: " + unlinked);
            Map<String, byte[]> lib2 = JarPortTest.entries(ported.get(innerPath));
            List<String> carried = lib2.keySet().stream().filter(n -> n.startsWith("mmt/standin/")).sorted().toList();
            JarPortTest.expect(carried.equals(List.of(JarPort.standinHome("outer.jar!n9lib.jar", "1.0", "2.0") + "s9/Gauge.class")),
                (fabric ? "Fabric" : "NeoForge") + ": the nested library carries the stand-ins it uses in its own package: " + carried);
            JarPortTest.expect(ported.keySet().stream().noneMatch(n -> n.startsWith("mmt/")),
                (fabric ? "Fabric" : "NeoForge") + ": the outer jar, which calls no stand-in, carries none of its own: " + ported.keySet());
            Map<String, byte[]> classes = new HashMap<>(game());
            Map<String, byte[]> all = new HashMap<>(lib2);
            all.putAll(types(ported, fabric));
            all.putAll(ported);
            for (var e : all.entrySet()) {
                if (e.getKey().endsWith(".class")) {
                    classes.put(e.getKey().substring(0, e.getKey().length() - 6), e.getValue());
                }
            }
            String trace;
            try {
                ClassLoader loader = loader(classes);
                Class<?> l = loader.loadClass("n9.lib.Lib"), gauge = loader.loadClass("s9.Gauge");
                trace = String.valueOf(l.getMethod("read", gauge).invoke(null, gauge.getConstructor().newInstance()));
            } catch (ReflectiveOperationException | LinkageError failed) {
                trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
            }
            JarPortTest.expect(trace.equals("42"), (fabric ? "Fabric" : "NeoForge") + ": the ported library runs its stand-ins: " + trace);
            // The mod calls the library's Zone make(): both name the one stand-in type, so the call finds the declaration.
            try {
                Object zone = loader(classes).loadClass("n9.Use").getMethod("made").invoke(null);
                trace = zone.getClass().getName() + " " + zone.getClass().getMethod("size").invoke(zone);
            } catch (ReflectiveOperationException | LinkageError failed) {
                trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
            }
            JarPortTest.expect(trace.matches("mmt\\.standin\\.u[0-9a-f]+\\.s9\\.Zone 3"),
                (fabric ? "Fabric" : "NeoForge") + ": a mod's call of its nested library's method that returns a stand-in type links: " + trace);
        }
    }

    /** The update's jar of stand-in types a ported jar nests, as its metadata lists it, read into its files. */
    static Map<String, byte[]> types(Map<String, byte[]> ported, boolean fabric) throws Exception {
        String list = new String(ported.getOrDefault(fabric ? "fabric.mod.json" : "META-INF/jarjar/metadata.json", new byte[0]), StandardCharsets.UTF_8);
        for (String n : ported.keySet()) {
            if (n.matches("META-INF/(jarjar|jars)/mmt-standin-types-[0-9a-f]+\\.jar")) {
                JarPortTest.expect(list.contains(n), "the jar of types is listed where the loader reads nested jars: " + list);
                Map<String, byte[]> files = JarPortTest.entries(ported.get(n));
                String manifest = new String(files.getOrDefault("META-INF/MANIFEST.MF", new byte[0]), StandardCharsets.UTF_8);
                JarPortTest.expect(manifest.contains("FMLModType: GAMELIBRARY") && files.containsKey("fabric.mod.json"),
                    "the jar of types loads with the game on NeoForge and as a mod on Fabric: " + files.keySet());
                return files;
            }
        }
        return Map.of();
    }

    /** class n9.lib.Maker: static Zone make() { return new Zone(); } (Zone, a class the update dropped). */
    static byte[] maker() {
        ClassWriter w = start("n9/lib/Maker");
        MethodVisitor make = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "()Ls9/Zone;", null, null);
        make.visitCode();
        make.visitTypeInsn(Opcodes.NEW, "s9/Zone");
        make.visitInsn(Opcodes.DUP);
        make.visitMethodInsn(Opcodes.INVOKESPECIAL, "s9/Zone", "<init>", "()V", false);
        make.visitInsn(Opcodes.ARETURN);
        make.visitMaxs(0, 0);
        return end(w);
    }

    /** class n9.Use, in the outer mod: static Object made() { return Maker.make(); } */
    static byte[] use() {
        ClassWriter w = start("n9/Use");
        MethodVisitor made = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "made", "()Ljava/lang/Object;", null, null);
        made.visitCode();
        made.visitMethodInsn(Opcodes.INVOKESTATIC, "n9/lib/Maker", "make", "()Ls9/Zone;", false);
        made.visitInsn(Opcodes.ARETURN);
        made.visitMaxs(0, 0);
        return end(w);
    }

    /** interface n9.lib.Lib, Java 8: static int read(Gauge g) { return g.read(); } */
    static byte[] java8Interface() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        w.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, "n9/lib/Lib", null, "java/lang/Object", null);
        MethodVisitor read = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "(Ls9/Gauge;)I", null, null);
        read.visitCode();
        read.visitVarInsn(Opcodes.ALOAD, 0);
        read.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "s9/Gauge", "read", "()I", false);
        read.visitInsn(Opcodes.IRETURN);
        read.visitMaxs(0, 0);
        return end(w);
    }

    static ClassLoader loader(Map<String, byte[]> classes) {
        return new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) {
                    return Class.forName(name, false, ClassLoader.getPlatformClassLoader());
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    /** The new release's classes, as bytecode. */
    static Map<String, byte[]> game() {
        Map<String, byte[]> out = new HashMap<>();
        ClassWriter g = start("s9/Gauge");
        MethodVisitor doubled = g.visitMethod(Opcodes.ACC_PUBLIC, "doubled", "()I", null, null);
        doubled.visitCode();
        doubled.visitIntInsn(Opcodes.BIPUSH, 84);
        doubled.visitInsn(Opcodes.IRETURN);
        doubled.visitMaxs(0, 0);
        out.put("s9/Gauge", end(g));
        ClassWriter v = start("s9/Vault");
        MethodVisitor secret = v.visitMethod(Opcodes.ACC_PRIVATE, "secret", "()I", null, null);
        secret.visitCode();
        secret.visitInsn(Opcodes.ICONST_5);
        secret.visitInsn(Opcodes.IRETURN);
        secret.visitMaxs(0, 0);
        out.put("s9/Vault", end(v));
        ClassWriter item = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        item.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "s9/Item", null, "java/lang/Object", null);
        item.visitField(Opcodes.ACC_PUBLIC, "id", "I", null, null).visitEnd();
        MethodVisitor init = item.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ILOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, "s9/Item", "id", "I");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        item.visitEnd();
        out.put("s9/Item", item.toByteArray());
        return out;
    }

    static ClassWriter start(String name) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor init = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        return w;
    }

    static byte[] end(ClassWriter w) {
        w.visitEnd();
        return w.toByteArray();
    }

    /** The mod, written for the old release: Gauge.read(), instanceof OldBook, Vault.peek() and Gauge.zone(new Zone()). */
    static byte[] mod() {
        ClassWriter w = start("n9/Mod");
        MethodVisitor read = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "(Ls9/Gauge;)I", null, null);
        read.visitCode();
        read.visitVarInsn(Opcodes.ALOAD, 0);
        read.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "s9/Gauge", "read", "()I", false);
        read.visitInsn(Opcodes.IRETURN);
        read.visitMaxs(0, 0);
        MethodVisitor book = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "isBook", "(Ljava/lang/Object;)Z", null, null);
        book.visitCode();
        book.visitVarInsn(Opcodes.ALOAD, 0);
        book.visitTypeInsn(Opcodes.INSTANCEOF, "s9/OldBook");
        book.visitInsn(Opcodes.IRETURN);
        book.visitMaxs(0, 0);
        MethodVisitor peek = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "peek", "(Ls9/Vault;)I", null, null);
        peek.visitCode();
        peek.visitVarInsn(Opcodes.ALOAD, 0);
        peek.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "s9/Vault", "peek", "()I", false);
        peek.visitInsn(Opcodes.IRETURN);
        peek.visitMaxs(0, 0);
        MethodVisitor zone = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "zone", "(Ls9/Gauge;)I", null, null);
        zone.visitCode();
        zone.visitVarInsn(Opcodes.ALOAD, 0);
        zone.visitTypeInsn(Opcodes.NEW, "s9/Zone");
        zone.visitInsn(Opcodes.DUP);
        zone.visitMethodInsn(Opcodes.INVOKESPECIAL, "s9/Zone", "<init>", "()V", false);
        zone.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "s9/Gauge", "zone", "(Ls9/Zone;)I", false);
        zone.visitInsn(Opcodes.IRETURN);
        zone.visitMaxs(0, 0);
        return end(w);
    }

    static byte[] plain() {
        return end(start("n9/Plain"));
    }
}

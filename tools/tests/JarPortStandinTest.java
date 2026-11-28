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
        modFiles.put("n9/Sub.class", sub());
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
            // The subclass overrides the old read() with 99 of its own; its super.read() ran the game's old read(), which is
            // the stand-in now, not the subclass's own method.
            Object viaSuper = loader.loadClass("n9.Sub").getMethod("viaSuper").invoke(loader.loadClass("n9.Sub").getConstructor().newInstance());
            trace = read + ";" + book7 + ";" + book3 + ";" + zone + ";" + viaSuper;
        } catch (ReflectiveOperationException | LinkageError failed) {
            trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
        JarPortTest.expect(trace.equals("42;true;false;45;42"), "the ported mod runs the stand-ins against the new release, a super call to a gone method included: " + trace);

        nested(port);
        became(scratch);
        reshaped(scratch);

        // A jar whose fabric.mod.json gives a key twice (Physics Mod's "environment"), which Fabric Loader reads with the last
        // value winning: the types are carried and listed, the key given once with that value.
        String saved = port.loader;
        port.loader = "fabric";
        List<Map<String, Object>> twiceRuns = new ArrayList<>();
        Map<String, byte[]> twiceFiles = new HashMap<>(Map.of("n9/Mod.class", mod(),
            "fabric.mod.json", "{\"schemaVersion\": 1, \"id\": \"n9\", \"version\": \"1\", \"environment\": \"server\", \"environment\": \"*\"}".getBytes(StandardCharsets.UTF_8)));
        Map<String, byte[]> twicePorted = JarPortTest.entries(port.port(JarPortTest.jar(twiceFiles), "twice.jar", twiceRuns, null));
        port.loader = saved;
        List<String> twice = twiceRuns.stream().flatMap(r -> Json.array(r.get("unlinked")).stream()).map(String::valueOf).toList();
        Map<String, Object> twiceMeta = Json.object(Json.parse(new String(twicePorted.get("fabric.mod.json"), StandardCharsets.UTF_8)));
        JarPortTest.expect(twice.stream().noneMatch(u -> u.startsWith("stand-in types not carried")) && "*".equals(twiceMeta.get("environment"))
                && String.valueOf(twiceMeta.get("jars")).contains("mmt-standin-types"),
            "a jar whose metadata gives a key twice carries its types, read as Fabric Loader reads it: " + twice + " " + twiceMeta);

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

        String outer = StandinClasses.standinHome("mod.jar", "1.0", "2.0"), inner = StandinClasses.standinHome("mod.jar!library.jar", "1.0", "2.0");
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
            byte[] gauge = StandinClasses.compileStandins(List.of(old.getParent()), new GameDiff.Snap(release)).get("mmt/standin/s9/Gauge");
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
            JarPortTest.expect(carried.equals(List.of(StandinClasses.standinHome("outer.jar!n9lib.jar", "1.0", "2.0") + "s9/Gauge.class")),
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

    /**
     * A gone interface whose stand-in is written as static methods on the type the game's own classes moved to, as
     * 1.21.11's PermissionSource on PermissionSetSupplier: s9.Source (has(int)), which s9.Stack implemented, is gone, and
     * Stack is an s9.Supplier (level()) now. The class goes to Supplier, so a mod's cast to Source and call of has(2)
     * link and run on a Stack; where Stack did not move to the stand-in's type, the stand-in is the class, as before.
     */
    static void became(Path scratch) throws Exception {
        for (boolean moved : List.of(true, false)) {
            GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
            b.loaderVersion = "2.0.1";
            JarPortTest.snap(a, "s9/Source", Opcodes.ACC_INTERFACE, null, "has(I)Z");
            JarPortTest.snap(a, "s9/Stack", 0, null, "<init>()V", "has(I)Z").ifaces.add("s9/Source");
            JarPortTest.snap(b, "s9/Supplier", Opcodes.ACC_INTERFACE, null, "level()I");
            GameDiff.Cls stack = JarPortTest.snap(b, "s9/Stack", 0, null, "<init>()V", "level()I");
            if (moved) {
                stack.ifaces.add("s9/Supplier");
            }
            Path kb = scratch.resolve("became-kb-" + moved);
            Path book = Files.createDirectories(kb).resolve("fixes.json");
            Files.writeString(book, "{\"rules\": []}");
            write(kb.resolve("standins/1.0-2.0/s9/Source.java"), """
                package mmt.standin.s9;
                public final class Source {
                    private Source() {}
                    public static boolean has(s9.Supplier self, int level) {
                        return self.level() >= level;
                    }
                }
                """);
            Path map = scratch.resolve("route-became.json");
            Files.writeString(map, "{}");
            JarPort port = new JarPort(a, b, map);
            port.fixes(book);
            List<Map<String, Object>> runs = new ArrayList<>();
            Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("n9/Asks.class", asks())), "became.jar", runs));
            List<String> unlinked = Json.array(runs.get(0).get("unlinked")).stream().map(String::valueOf).toList();
            if (!moved) {
                JarPortTest.expect(!unlinked.isEmpty(), "where the game's classes did not move to the stand-in's type, the stand-in is the class and has(2) stays unlinked: " + unlinked);
                continue;
            }
            JarPortTest.expect(unlinked.isEmpty(), "a gone class whose stand-in is written on the type the game moved to links: " + unlinked);
            Map<String, byte[]> classes = new HashMap<>();
            ClassWriter supplier = new ClassWriter(0);
            supplier.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, "s9/Supplier", null, "java/lang/Object", null);
            supplier.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "level", "()I", null, null).visitEnd();
            classes.put("s9/Supplier", end(supplier));
            ClassWriter game = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            game.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "s9/Stack", null, "java/lang/Object", new String[] {"s9/Supplier"});
            MethodVisitor init = game.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            MethodVisitor level = game.visitMethod(Opcodes.ACC_PUBLIC, "level", "()I", null, null);
            level.visitCode();
            level.visitInsn(Opcodes.ICONST_3);
            level.visitInsn(Opcodes.IRETURN);
            level.visitMaxs(0, 0);
            classes.put("s9/Stack", end(game));
            for (var e : ported.entrySet()) {
                if (e.getKey().endsWith(".class")) {
                    classes.put(e.getKey().substring(0, e.getKey().length() - 6), e.getValue());
                }
            }
            String trace;
            try {
                ClassLoader loader = loader(classes);
                Class<?> asks = loader.loadClass("n9.Asks");
                Object onStack = loader.loadClass("s9.Stack").getConstructor().newInstance();
                trace = asks.getMethod("ask", Object.class, int.class).invoke(null, onStack, 2) + ";" + asks.getMethod("ask", Object.class, int.class).invoke(null, onStack, 4);
            } catch (ReflectiveOperationException | LinkageError failed) {
                trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
            }
            JarPortTest.expect(trace.equals("true;false"), "the ported mod asks the stand-in on the game's object: " + trace);
        }
    }

    /**
     * Classes an update reshaped rather than dropped, as 1.20.2 did to 1.20.1's: s9.Sheet is still there but its constructor
     * now takes a long (ImageButton, which takes sprites now), so its stand-in is the old class written on the new one and a
     * mod's subclass extends the stand-in, keeping its old super(...) and the members the new class kept (value()); s9.Packet
     * is gone for s9.NewPacket, which no game class was before, and its holder's init makes one (ClientboundCustomPayloadPacket);
     * s9.Source is gone and s9.Behaviour.run takes s9.Src where it took Source (core.BlockSource, now core.dispenser's record);
     * and the stand-in for Holder.peek() reaches a closed nested class of the new game, which compiles and is opened.
     */
    static void reshaped(Path scratch) throws Exception {
        GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
        b.loaderVersion = "2.0.1";
        JarPortTest.snap(a, "s9/Sheet", 0, null, "<init>(I)V", "value()I");
        JarPortTest.snap(b, "s9/Sheet", 0, null, "<init>(J)V", "value()I");
        JarPortTest.snap(a, "s9/Packet", Opcodes.ACC_FINAL, null, "<init>(I)V", "size()I");
        JarPortTest.snap(b, "s9/NewPacket", Opcodes.ACC_FINAL, null, "<init>(J)V", "size()I");
        JarPortTest.snap(a, "s9/Source", Opcodes.ACC_INTERFACE, null, "at()I");
        JarPortTest.snap(b, "s9/Src", Opcodes.ACC_FINAL, null, "<init>(I)V", "pos()I");
        JarPortTest.snap(a, "s9/Behaviour", 0, null, "<init>()V", "run(Ls9/Source;)I");
        JarPortTest.snap(b, "s9/Behaviour", 0, null, "<init>()V", "run(Ls9/Src;)I");
        JarPortTest.snap(a, "s9/Holder", 0, null, "<init>()V", "peek()I");
        GameDiff.Cls holder = JarPortTest.snap(b, "s9/Holder", 0, null, "<init>()V");
        holder.fields.put("cache", new GameDiff.Fld(holder, "cache", "Ls9/Holder$Cache;", Opcodes.ACC_PRIVATE));
        JarPortTest.snap(b, "s9/Holder$Cache", 0, null, "<init>()V", "get()I").flags &= ~Opcodes.ACC_PUBLIC;
        Path kb = scratch.resolve("reshaped-kb");
        Path book = Files.createDirectories(kb).resolve("fixes.json");
        Files.writeString(book, "{\"rules\": []}");
        Path dir = kb.resolve("standins/1.0-2.0/s9");
        write(dir.resolve("Sheet.java"), """
            package mmt.standin.s9;
            public class Sheet extends s9.Sheet {
                public Sheet(int half) {
                    super((long) half * 2);
                }
                public static s9.Sheet init(int half) {
                    return new Sheet(half);
                }
            }
            """);
        write(dir.resolve("Packet.java"), """
            package mmt.standin.s9;
            public final class Packet {
                private Packet() {}
                public static s9.NewPacket init(int size) {
                    return new s9.NewPacket((long) size + 100);
                }
            }
            """);
        write(dir.resolve("Source.java"), """
            package mmt.standin.s9;
            public final class Source {
                private Source() {}
                public static int at(s9.Src self) {
                    return self.pos();
                }
            }
            """);
        write(dir.resolve("Holder.java"), """
            package mmt.standin.s9;
            public final class Holder {
                private Holder() {}
                public static int peek(s9.Holder self) {
                    return self.cache.get();
                }
            }
            """);
        Path map = scratch.resolve("route-reshaped.json");
        Files.writeString(map, "{}");
        JarPort port = new JarPort(a, b, map);
        port.fixes(book);
        Map<String, byte[]> modFiles = new HashMap<>(Map.of("n9/MySheet.class", mySheet(), "n9/Uses.class", uses(), "n9/Peeks.class", peeks()));
        modFiles.put("META-INF/neoforge.mods.toml", "modLoader=\"javafml\"\n[[mods]]\nmodId=\"n9\"\n".getBytes(StandardCharsets.UTF_8));
        List<Map<String, Object>> runs = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(modFiles), "reshaped.jar", runs));
        List<String> unlinked = Json.array(runs.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(unlinked.isEmpty(), "reshaped classes' stand-ins answer every reference: " + unlinked);
        String at = new String(ported.getOrDefault("META-INF/accesstransformer.cfg", new byte[0]), StandardCharsets.UTF_8);
        JarPortTest.expect(at.contains("public s9.Holder$Cache") && at.contains("public s9.Holder cache"),
            "the closed nested class and field a stand-in reaches are opened: " + at);
        Map<String, byte[]> classes = new HashMap<>(reshapedGame());
        Map<String, byte[]> all = new HashMap<>(ported);
        all.putAll(types(ported, false));
        for (var e : all.entrySet()) {
            if (e.getKey().endsWith(".class")) {
                classes.put(e.getKey().substring(0, e.getKey().length() - 6), e.getValue());
            }
        }
        String trace;
        try {
            ClassLoader loader = loader(classes);
            Class<?> uses = loader.loadClass("n9.Uses");
            Object sub = loader.loadClass("n9.MySheet").getConstructor(int.class).newInstance(3);
            Object made = uses.getMethod("sheet", int.class).invoke(null, 4);
            Object packet = uses.getMethod("packet", int.class).invoke(null, 5);
            Object src = loader.loadClass("s9.Src").getConstructor(int.class).newInstance(9);
            trace = sub.getClass().getMethod("twice").invoke(sub) + ";" + made.getClass().getMethod("value").invoke(made) + ";"
                + packet.getClass().getName() + " " + packet.getClass().getMethod("size").invoke(packet) + ";"
                + uses.getMethod("at", Object.class).invoke(null, src) + ";" + uses.getMethod("run", Object.class).invoke(null, src);
        } catch (ReflectiveOperationException | LinkageError failed) {
            trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
        JarPortTest.expect(trace.equals("12;8;s9.NewPacket 105;9;10"),
            "the subclass, the made sheet, the packet and the source run on the new release through their stand-ins: " + trace);
    }

    /** class n9.MySheet extends Sheet: MySheet(int half) { super(half); } int twice() { return super.value() * 2; } */
    static byte[] mySheet() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "n9/MySheet", null, "s9/Sheet", null);
        MethodVisitor init = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ILOAD, 1);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "s9/Sheet", "<init>", "(I)V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        MethodVisitor twice = w.visitMethod(Opcodes.ACC_PUBLIC, "twice", "()I", null, null);
        twice.visitCode();
        twice.visitVarInsn(Opcodes.ALOAD, 0);
        twice.visitMethodInsn(Opcodes.INVOKESPECIAL, "s9/Sheet", "value", "()I", false);
        twice.visitInsn(Opcodes.ICONST_2);
        twice.visitInsn(Opcodes.IMUL);
        twice.visitInsn(Opcodes.IRETURN);
        twice.visitMaxs(0, 0);
        return end(w);
    }

    /**
     * class n9.Uses, written for the old release: static Sheet sheet(int h) { return new Sheet(h); }, static Packet make(int n)
     * { return new Packet(n); }, static Object packet(int n) { return make(n); }, static int at(Object o) { return ((Source)
     * o).at(); }, static int run(Object o) { return new Behaviour().run((Source) o); }
     */
    static byte[] uses() {
        ClassWriter w = start("n9/Uses");
        MethodVisitor sheet = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "sheet", "(I)Ls9/Sheet;", null, null);
        sheet.visitCode();
        sheet.visitTypeInsn(Opcodes.NEW, "s9/Sheet");
        sheet.visitInsn(Opcodes.DUP);
        sheet.visitVarInsn(Opcodes.ILOAD, 0);
        sheet.visitMethodInsn(Opcodes.INVOKESPECIAL, "s9/Sheet", "<init>", "(I)V", false);
        sheet.visitInsn(Opcodes.ARETURN);
        sheet.visitMaxs(0, 0);
        MethodVisitor make = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "(I)Ls9/Packet;", null, null);
        make.visitCode();
        make.visitTypeInsn(Opcodes.NEW, "s9/Packet");
        make.visitInsn(Opcodes.DUP);
        make.visitVarInsn(Opcodes.ILOAD, 0);
        make.visitMethodInsn(Opcodes.INVOKESPECIAL, "s9/Packet", "<init>", "(I)V", false);
        make.visitInsn(Opcodes.ARETURN);
        make.visitMaxs(0, 0);
        MethodVisitor packet = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "packet", "(I)Ljava/lang/Object;", null, null);
        packet.visitCode();
        packet.visitVarInsn(Opcodes.ILOAD, 0);
        packet.visitMethodInsn(Opcodes.INVOKESTATIC, "n9/Uses", "make", "(I)Ls9/Packet;", false);
        packet.visitInsn(Opcodes.ARETURN);
        packet.visitMaxs(0, 0);
        MethodVisitor at = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "at", "(Ljava/lang/Object;)I", null, null);
        at.visitCode();
        at.visitVarInsn(Opcodes.ALOAD, 0);
        at.visitTypeInsn(Opcodes.CHECKCAST, "s9/Source");
        at.visitMethodInsn(Opcodes.INVOKEINTERFACE, "s9/Source", "at", "()I", true);
        at.visitInsn(Opcodes.IRETURN);
        at.visitMaxs(0, 0);
        MethodVisitor run = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(Ljava/lang/Object;)I", null, null);
        run.visitCode();
        run.visitTypeInsn(Opcodes.NEW, "s9/Behaviour");
        run.visitInsn(Opcodes.DUP);
        run.visitMethodInsn(Opcodes.INVOKESPECIAL, "s9/Behaviour", "<init>", "()V", false);
        run.visitVarInsn(Opcodes.ALOAD, 0);
        run.visitTypeInsn(Opcodes.CHECKCAST, "s9/Source");
        run.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "s9/Behaviour", "run", "(Ls9/Source;)I", false);
        run.visitInsn(Opcodes.IRETURN);
        run.visitMaxs(0, 0);
        return end(w);
    }

    /** class n9.Peeks: static int peek(Holder h) { return h.peek(); } (linked and opened, not run: the test's loader applies no access transformer). */
    static byte[] peeks() {
        ClassWriter w = start("n9/Peeks");
        MethodVisitor peek = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "peek", "(Ls9/Holder;)I", null, null);
        peek.visitCode();
        peek.visitVarInsn(Opcodes.ALOAD, 0);
        peek.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "s9/Holder", "peek", "()I", false);
        peek.visitInsn(Opcodes.IRETURN);
        peek.visitMaxs(0, 0);
        return end(w);
    }

    /** The new release of reshaped(): Sheet(long) and value(), NewPacket(long) and size(), Src(int) and pos(), Behaviour.run(Src) = pos + 1. */
    static Map<String, byte[]> reshapedGame() {
        Map<String, byte[]> out = new HashMap<>();
        out.put("s9/Sheet", holding("s9/Sheet", 0, "J", "value"));
        out.put("s9/NewPacket", holding("s9/NewPacket", Opcodes.ACC_FINAL, "J", "size"));
        out.put("s9/Src", holding("s9/Src", Opcodes.ACC_FINAL, "I", "pos"));
        ClassWriter behaviour = start("s9/Behaviour");
        MethodVisitor run = behaviour.visitMethod(Opcodes.ACC_PUBLIC, "run", "(Ls9/Src;)I", null, null);
        run.visitCode();
        run.visitVarInsn(Opcodes.ALOAD, 1);
        run.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "s9/Src", "pos", "()I", false);
        run.visitInsn(Opcodes.ICONST_1);
        run.visitInsn(Opcodes.IADD);
        run.visitInsn(Opcodes.IRETURN);
        run.visitMaxs(0, 0);
        out.put("s9/Behaviour", end(behaviour));
        return out;
    }

    /** A class holding one value of type desc (I or J), given to its constructor and read back as an int by getter. */
    static byte[] holding(String name, int flags, String desc, String getter) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | flags, name, null, "java/lang/Object", null);
        w.visitField(Opcodes.ACC_PRIVATE, "v", desc, null, null).visitEnd();
        MethodVisitor init = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + desc + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(desc.equals("J") ? Opcodes.LLOAD : Opcodes.ILOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, name, "v", desc);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        MethodVisitor get = w.visitMethod(Opcodes.ACC_PUBLIC, getter, "()I", null, null);
        get.visitCode();
        get.visitVarInsn(Opcodes.ALOAD, 0);
        get.visitFieldInsn(Opcodes.GETFIELD, name, "v", desc);
        if (desc.equals("J")) {
            get.visitInsn(Opcodes.L2I);
        }
        get.visitInsn(Opcodes.IRETURN);
        get.visitMaxs(0, 0);
        return end(w);
    }

    /** class n9.Asks, written for the old release: static boolean ask(Object o, int level) { return ((Source) o).has(level); } */
    static byte[] asks() {
        ClassWriter w = start("n9/Asks");
        MethodVisitor ask = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "ask", "(Ljava/lang/Object;I)Z", null, null);
        ask.visitCode();
        ask.visitVarInsn(Opcodes.ALOAD, 0);
        ask.visitTypeInsn(Opcodes.CHECKCAST, "s9/Source");
        ask.visitVarInsn(Opcodes.ILOAD, 1);
        ask.visitMethodInsn(Opcodes.INVOKEINTERFACE, "s9/Source", "has", "(I)Z", true);
        ask.visitInsn(Opcodes.IRETURN);
        ask.visitMaxs(0, 0);
        return end(w);
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

    /** A mod class extending Gauge that overrides the old read() and calls super.read(), as a screen calls super.handleComponentClicked(style). */
    static byte[] sub() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "n9/Sub", null, "s9/Gauge", null);
        MethodVisitor init = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "s9/Gauge", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        MethodVisitor own = w.visitMethod(Opcodes.ACC_PUBLIC, "read", "()I", null, null);
        own.visitCode();
        own.visitIntInsn(Opcodes.BIPUSH, 99);
        own.visitInsn(Opcodes.IRETURN);
        own.visitMaxs(0, 0);
        MethodVisitor viaSuper = w.visitMethod(Opcodes.ACC_PUBLIC, "viaSuper", "()I", null, null);
        viaSuper.visitCode();
        viaSuper.visitVarInsn(Opcodes.ALOAD, 0);
        viaSuper.visitMethodInsn(Opcodes.INVOKESPECIAL, "s9/Gauge", "read", "()I", false);
        viaSuper.visitInsn(Opcodes.IRETURN);
        viaSuper.visitMaxs(0, 0);
        return end(w);
    }

    static byte[] plain() {
        return end(start("n9/Plain"));
    }
}

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * Stand-ins (knowledge/standins/FROM-TO/), run on this JVM against real classes of the new release. The update dropped
 * Gauge.read() (the new Gauge has doubled()), the class OldBook (an item with id 7 now) and Vault.peek() (the new Vault
 * keeps the value behind a private secret()). Each stand-in is a Java source the port compiles against the new release;
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
        JarPortTest.snap(a, "s9/Gauge", 0, null, "<init>()V", "read()I");
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
        JarPortTest.expect(carried.size() == 3 && carried.stream().allMatch(n -> n.matches("mmt/standin/m[0-9a-f]+/s9/(Gauge|OldBook|Vault)\\.class")),
            "the jar carries each stand-in it uses under a package of its own: " + carried);
        String at = new String(ported.getOrDefault("META-INF/accesstransformer.cfg", new byte[0]), StandardCharsets.UTF_8);
        JarPortTest.expect(at.contains("public s9.Vault secret()I"), "the closed member a stand-in reaches is opened through the mod's access transformer: " + at);

        Map<String, byte[]> classes = new HashMap<>(game());
        for (var e : ported.entrySet()) {
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
            trace = read + ";" + book7 + ";" + book3;
        } catch (ReflectiveOperationException | LinkageError failed) {
            trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
        JarPortTest.expect(trace.equals("42;true;false"), "the ported mod runs the stand-ins against the new release: " + trace);

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

    /** The mod, written for the old release: Gauge.read(), instanceof OldBook and Vault.peek(). */
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
        return end(w);
    }

    static byte[] plain() {
        return end(start("n9/Plain"));
    }
}

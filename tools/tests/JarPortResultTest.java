import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * Game calls the update gave another result type, run on this JVM against real classes of the new release:
 * r9.Level.getRandom() was a java.util.Random and is an r9.Rand (as 1.19's RandomSource); r9.Window.getGuiScale() was a
 * double and is an int (as 1.21.6's); r9.Sounds.click() was a Sound and is a Holder of one, the game's own code reading
 * holder.value(). A mod's nextInt on the result, its double scale and its played sound run. r9.Store.name(), a String
 * that is now an Optional, stays reported: an empty one would throw where the old call answered.
 */
final class JarPortResultTest {

    static final String RANDOM = "Ljava/util/Random;", RAND = "Lr9/Rand;", SOUND = "Lr9/Sound;", HOLDER = "Lr9/Holder;";

    public static void main(String[] args) throws Exception {
        check(Path.of(args.length > 0 ? args[0] : ".runs/result-test"));
        if (JarPortTest.failures > 0) {
            System.exit(1);
        }
        System.out.println("jar-port: call results checked");
    }

    static void check(Path scratch) throws Exception {
        Files.createDirectories(scratch);
        GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
        JarPortTest.snap(a, "r9/Level", 0, null, "getRandom()" + RANDOM);
        JarPortTest.snap(b, "r9/Level", 0, null, "getRandom()" + RAND);
        JarPortTest.snap(b, "r9/Rand", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null, "nextInt(I)I");
        JarPortTest.snap(a, "r9/Window", 0, null, "getGuiScale()D");
        JarPortTest.snap(b, "r9/Window", 0, null, "getGuiScale()I");
        for (GameDiff.Snap s : List.of(a, b)) {
            JarPortTest.snap(s, "r9/Sound", 0, null);
            GameDiff.Cls player = JarPortTest.snap(s, "r9/Player", 0, null);
            player.methods.put("play(" + SOUND + ")V", new GameDiff.Meth(player, "play", "(" + SOUND + ")V", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
        }
        method(JarPortTest.snap(a, "r9/Sounds", 0, null), "click", "()" + SOUND);
        method(JarPortTest.snap(b, "r9/Sounds", 0, null), "click", "()" + HOLDER);
        JarPortTest.snap(b, "r9/Holder", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null, "value()Ljava/lang/Object;");
        JarPortTest.snap(a, "r9/Store", 0, null, "name()Ljava/lang/String;");
        JarPortTest.snap(b, "r9/Store", 0, null, "name()Ljava/util/Optional;");
        Path map = scratch.resolve("route-result.json");
        Files.writeString(map, """
            {"signatures": [{"owner": "r9.Level", "from": "getRandom", "desc": "()Ljava/util/Random;", "to": "getRandom", "newDesc": "()Lr9/Rand;"},
                            {"owner": "r9.Window", "from": "getGuiScale", "desc": "()D", "to": "getGuiScale", "newDesc": "()I"},
                            {"owner": "r9.Sounds", "from": "click", "desc": "()Lr9/Sound;", "to": "click", "newDesc": "()Lr9/Holder;"},
                            {"owner": "r9.Store", "from": "name", "desc": "()Ljava/lang/String;", "to": "name", "newDesc": "()Ljava/util/Optional;"}]}
            """);
        JarPort port = new JarPort(a, b, map);
        Map<String, byte[]> code = new HashMap<>(Map.of("r9/Ui", ui()));
        port.newCode = new JarPort.GameCode(code::get, null, code);
        port.oldCode = new JarPort.GameCode(Map.<String, byte[]>of()::get, null, Map.of());

        List<Map<String, Object>> runs = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("n12/Mod.class", mod())), "result.jar", runs));
        List<String> unlinked = Json.array(runs.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(unlinked.isEmpty(), "calls whose result type changed link: used as the new type, widened, or read out of the holder: " + unlinked);

        List<Map<String, Object>> maybe = new ArrayList<>();
        port.port(JarPortTest.jar(Map.of("n12/Named.class", named())), "named.jar", maybe);
        List<String> left = Json.array(maybe.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(left.equals(List.of("call: r9.Store.name()Ljava/lang/String;")), "a result that may hold nothing (an Optional) stays reported: " + left);

        Map<String, byte[]> classes = new HashMap<>(JarPortTest.newGame());
        classes.putAll(game());
        classes.put("n12/Mod", ported.get("n12/Mod.class"));
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
            Class<?> mod = loader.loadClass("n12.Mod");
            Object roll = mod.getMethod("roll", loader.loadClass("r9.Level")).invoke(null, loader.loadClass("r9.Level").getConstructor().newInstance());
            Object scale = mod.getMethod("scale", loader.loadClass("r9.Window")).invoke(null, loader.loadClass("r9.Window").getConstructor().newInstance());
            mod.getMethod("click").invoke(null);
            trace = roll + ";" + scale + ";" + loader.loadClass("g2.Log").getField("out").get(null);
        } catch (ReflectiveOperationException | LinkageError failed) {
            trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
        JarPortTest.expect(trace.equals("42;3.0;next;value;play;"), "the converted results run against the new release: " + trace);
    }

    static void method(GameDiff.Cls c, String name, String desc) {
        c.methods.put(name + desc, new GameDiff.Meth(c, name, desc, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
    }

    /** r9.Ui.a(holder) and b(holder): play((Sound) holder.value()); c(optional) and d(optional): (String) optional.get(). */
    static byte[] ui() {
        ClassWriter w = JarPortRetypeTest.writer("r9/Ui", "java/lang/Object");
        for (String name : List.of("c", "d")) {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "(Ljava/util/Optional;)Ljava/lang/String;", null, null);
            m.visitCode();
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "get", "()Ljava/lang/Object;", false);
            m.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
            m.visitInsn(Opcodes.ARETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
        for (String name : List.of("a", "b")) {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "(" + HOLDER + ")V", null, null);
            m.visitCode();
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKEINTERFACE, "r9/Holder", "value", "()Ljava/lang/Object;", true);
            m.visitTypeInsn(Opcodes.CHECKCAST, "r9/Sound");
            m.visitMethodInsn(Opcodes.INVOKESTATIC, "r9/Player", "play", "(" + SOUND + ")V", false);
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
        w.visitEnd();
        return w.toByteArray();
    }

    /** n12.Mod: roll(level) = level.getRandom().nextInt(5); scale(window) = window.getGuiScale(); click() = play(Sounds.click()). */
    static byte[] mod() {
        ClassWriter w = JarPortRetypeTest.writer("n12/Mod", "java/lang/Object");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "roll", "(Lr9/Level;)I", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "r9/Level", "getRandom", "()" + RANDOM, false);
        m.visitInsn(Opcodes.ICONST_5);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Random", "nextInt", "(I)I", false);
        m.visitInsn(Opcodes.IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "scale", "(Lr9/Window;)D", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "r9/Window", "getGuiScale", "()D", false);
        m.visitInsn(Opcodes.DRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "click", "()V", null, null);
        m.visitCode();
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "r9/Sounds", "click", "()" + SOUND, false);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "r9/Player", "play", "(" + SOUND + ")V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** n12.Named: label(store) = store.name(). */
    static byte[] named() {
        ClassWriter w = JarPortRetypeTest.writer("n12/Named", "java/lang/Object");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "label", "(Lr9/Store;)Ljava/lang/String;", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "r9/Store", "name", "()Ljava/lang/String;", false);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** The new release as real classes; each call logs what it is asked. */
    static Map<String, byte[]> game() {
        Map<String, byte[]> out = new HashMap<>();
        out.put("r9/Rand", JarPortTest.real("r9/Rand", "java/lang/Object", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, new String[0], w ->
            w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "nextInt", "(I)I", null, null).visitEnd()));
        out.put("r9/Dice", JarPortTest.real("r9/Dice", "java/lang/Object", 0, new String[] { "r9/Rand" }, w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "nextInt", "(I)I", null, null);
            m.visitCode();
            JarPortTest.append(m, "next;");
            m.visitIntInsn(Opcodes.BIPUSH, 42);
            m.visitInsn(Opcodes.IRETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        out.put("r9/Level", JarPortTest.real("r9/Level", "java/lang/Object", 0, new String[0], w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "getRandom", "()" + RAND, null, null);
            m.visitCode();
            m.visitTypeInsn(Opcodes.NEW, "r9/Dice");
            m.visitInsn(Opcodes.DUP);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "r9/Dice", "<init>", "()V", false);
            m.visitInsn(Opcodes.ARETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        out.put("r9/Window", JarPortTest.real("r9/Window", "java/lang/Object", 0, new String[0], w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "getGuiScale", "()I", null, null);
            m.visitCode();
            m.visitInsn(Opcodes.ICONST_3);
            m.visitInsn(Opcodes.IRETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        out.put("r9/Sound", JarPortTest.real("r9/Sound", "java/lang/Object", 0, new String[0], w -> { }));
        out.put("r9/Player", JarPortTest.real("r9/Player", "java/lang/Object", 0, new String[0], w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "play", "(" + SOUND + ")V", null, null);
            m.visitCode();
            JarPortTest.append(m, "play;");
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        out.put("r9/Holder", JarPortTest.real("r9/Holder", "java/lang/Object", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, new String[0], w ->
            w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "value", "()Ljava/lang/Object;", null, null).visitEnd()));
        out.put("r9/Ref", JarPortTest.real("r9/Ref", "java/lang/Object", 0, new String[] { "r9/Holder" }, w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/Object;", null, null);
            m.visitCode();
            JarPortTest.append(m, "value;");
            m.visitTypeInsn(Opcodes.NEW, "r9/Sound");
            m.visitInsn(Opcodes.DUP);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "r9/Sound", "<init>", "()V", false);
            m.visitInsn(Opcodes.ARETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        out.put("r9/Sounds", JarPortTest.real("r9/Sounds", "java/lang/Object", 0, new String[0], w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "click", "()" + HOLDER, null, null);
            m.visitCode();
            m.visitTypeInsn(Opcodes.NEW, "r9/Ref");
            m.visitInsn(Opcodes.DUP);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "r9/Ref", "<init>", "()V", false);
            m.visitInsn(Opcodes.ARETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        return out;
    }
}

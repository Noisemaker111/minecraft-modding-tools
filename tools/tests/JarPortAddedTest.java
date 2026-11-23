import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * Abstract methods the new release adds that the old release had nothing of, run on this JVM against real classes of
 * the new release:
 * - t10.Widget.narrate(int): two game widgets write it as this.defaultNarration(x), a third with its own field (1.17's
 *   widgets and AbstractWidget.defaultButtonNarrationText). A mod widget gets the two widgets' body.
 * - t10.Entry.refresh(): one game entry refreshes nothing, the other its own field (1.19.4's KeyBindsList entries). A
 *   mod entry gets an empty one: the old release never asked it, and the game shows doing nothing is an answer.
 *   Entry.sync(), which every game entry writes with its own code, stays reported: an empty one might drop what the game
 *   hands the class now. Entry.label(), which gives a String, one game entry answering with a constant, stays reported:
 *   one subclass is no pattern, and a guessed value is worse than none.
 * - t10.Getter.height(): two game getters return 0, but Getter.maxHeight(), a default the old release had, now asks
 *   height() (1.17's LevelHeightAccessor). The mod's getter answered maxHeight before through the default; it stays
 *   reported.
 * - t10.Shader.attach(): t10.Manager.link(shader) called shader.vertex() in the old release and calls attach() now
 *   (1.17's ProgramManager and Shader.attachToProgram): attach() does work the game did, and stays reported.
 * - A mod widget that writes old(), which t10.Widget no longer has, keeps narrate() reported: its code may belong there.
 * And what the change map says of each kind: added, moved (game panels stopped writing paint() for draw()), retyped
 * (a panel's size() gave an int, now a long).
 */
final class JarPortAddedTest {

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        check(scratch);
        JarPortTest.finish("JarPortAddedTest");
    }

    static void check(Path scratch) throws Exception {
        GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
        for (GameDiff.Snap s : List.of(a, b)) {
            JarPortTest.snap(s, "t10/Draw", 0, null);
            GameDiff.Cls widget = JarPortTest.snap(s, "t10/Widget", Opcodes.ACC_ABSTRACT, null, "defaultNarration(I)V");
            JarPortTest.snap(s, "t10/Entry", Opcodes.ACC_ABSTRACT, null);
            JarPortTest.snap(s, "t10/Getter", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null);
            JarPortTest.snap(s, "t10/Shader", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null, "vertex()V");
            JarPortTest.snap(s, "t10/Panel", Opcodes.ACC_ABSTRACT, null);
            for (String w : List.of("Wa", "Wb", "Wc")) {
                JarPortTest.snap(s, "t10/" + w, 0, "t10/Widget", s == b ? new String[] { "narrate(I)V" } : new String[0]);
            }
            for (String e : List.of("Ea", "Eb")) {
                JarPortTest.snap(s, "t10/" + e, 0, "t10/Entry", s == b ? new String[] { "refresh()V", "sync()V", "label()Ljava/lang/String;" } : new String[0]);
            }
            for (String g : List.of("Ga", "Gb")) {
                JarPortTest.snap(s, "t10/" + g, 0, null, s == b ? new String[] { "height()I" } : new String[0]).ifaces.add("t10/Getter");
            }
            JarPortTest.snap(s, "t10/Sa", 0, null, "vertex()V", s == b ? "attach()V" : "vertex()V").ifaces.add("t10/Shader");
            for (String p : List.of("Pa", "Pb")) {
                JarPortTest.snap(s, "t10/" + p, 0, "t10/Panel", s == b ? "draw()V" : "paint()V");
            }
            GameDiff.Meth link = new GameDiff.Meth(JarPortTest.snap(s, "t10/Manager", 0, null), "link", "(Lt10/Shader;)V", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
            link.refs = new GameDiff.Ref[] { new GameDiff.Ref('m', "t10/Shader", s == b ? "attach" : "vertex", "()V") };
            link.owner.methods.put("link(Lt10/Shader;)V", link);
            if (s == a) {
                widget.methods.put("old()V", new GameDiff.Meth(widget, "old", "()V", Opcodes.ACC_PUBLIC));
                s.classes.get("t10/Getter").methods.put("maxHeight()I", new GameDiff.Meth(s.classes.get("t10/Getter"), "maxHeight", "()I", Opcodes.ACC_PUBLIC));
                GameDiff.Cls panel = s.classes.get("t10/Panel");
                panel.methods.put("paint()V", new GameDiff.Meth(panel, "paint", "()V", Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT));
                panel.methods.put("size()I", new GameDiff.Meth(panel, "size", "()I", Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT));
            }
        }
        abstractMethod(b.classes.get("t10/Widget"), "narrate", "(I)V");
        abstractMethod(b.classes.get("t10/Entry"), "refresh", "()V");
        abstractMethod(b.classes.get("t10/Entry"), "label", "()Ljava/lang/String;");
        abstractMethod(b.classes.get("t10/Entry"), "sync", "()V");
        abstractMethod(b.classes.get("t10/Getter"), "height", "()I");
        b.classes.get("t10/Getter").methods.put("maxHeight()I", new GameDiff.Meth(b.classes.get("t10/Getter"), "maxHeight", "()I", Opcodes.ACC_PUBLIC));
        abstractMethod(b.classes.get("t10/Shader"), "attach", "()V");
        abstractMethod(b.classes.get("t10/Panel"), "draw", "()V");
        abstractMethod(b.classes.get("t10/Panel"), "size", "()J");

        Path map = scratch.resolve("route-added.json");
        Files.writeString(map, "{}");
        JarPort port = new JarPort(a, b, map);
        Map<String, byte[]> game = game();
        port.oldCode = new GameCode(x -> null, null);
        port.newCode = new GameCode(game::get, null);

        List<Map<String, Object>> runs = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(Map.of(
            "n11/MyWidget.class", JarPortRetypeTest.writer("n11/MyWidget", "t10/Widget").toByteArray(),
            "n11/MyEntry.class", entry("n11/MyEntry", "label", "()Ljava/lang/String;"),
            "n11/LabelEntry.class", entry("n11/LabelEntry", "refresh", "()V"),
            "n11/MyGetter.class", implementing("n11/MyGetter", "t10/Getter", null),
            "n11/MyShader.class", implementing("n11/MyShader", "t10/Shader", "vertex"),
            "n11/OldWidget.class", oldWidget())), "added.jar", runs));
        List<String> left = Json.array(runs.get(0).get("unlinked")).stream().map(String::valueOf).sorted().toList();
        JarPortTest.expect(left.equals(List.of(
            "abstract method not written: t10.Entry.label()Ljava/lang/String; (in n11.LabelEntry)",
            "abstract method not written: t10.Entry.sync()V (in n11.LabelEntry)",
            "abstract method not written: t10.Entry.sync()V (in n11.MyEntry)",
            "abstract method not written: t10.Getter.height()I (in n11.MyGetter)",
            "abstract method not written: t10.Shader.attach()V (in n11.MyShader)",
            "abstract method not written: t10.Widget.narrate(I)V (in n11.OldWidget)")),
            "a value one subclass answers, a method no game subclass leaves empty, a method an old default or an old game method hands its work to, and a class with a method the game dropped stay reported: " + left);

        Map<String, byte[]> classes = new HashMap<>(JarPortTest.newGame());
        classes.putAll(game);
        classes.put("n11/MyWidget", ported.get("n11/MyWidget.class"));
        classes.put("n11/MyEntry", ported.get("n11/MyEntry.class"));
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
            loader.loadClass("t10.Widget").getMethod("narrate", int.class).invoke(loader.loadClass("n11.MyWidget").getConstructor().newInstance(), 3);
            loader.loadClass("t10.Entry").getMethod("refresh").invoke(loader.loadClass("n11.MyEntry").getConstructor().newInstance());
            trace = String.valueOf(loader.loadClass("g2.Log").getField("out").get(null));
        } catch (ReflectiveOperationException | LinkageError failed) {
            trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
        JarPortTest.expect(trace.equals("default3;"), "the mod widget narrates as the game's widgets do, and the mod entry refreshes nothing: " + trace);

        String[] narrate = Exposure.added(a, b, "abstract method not written: t10.Widget.narrate(I)V");
        String[] draw = Exposure.added(a, b, "abstract method not written: t10.Panel.draw()V");
        String[] size = Exposure.added(a, b, "abstract method not written: t10.Panel.size()J");
        JarPortTest.expect(narrate != null && narrate[0].equals("added") && draw != null && draw[0].equals("moved") && draw[1].startsWith("paint()V")
            && size != null && size[0].equals("retyped"), "the change map reads an added, a moved and a retyped abstract method: "
            + Arrays.toString(narrate) + " " + Arrays.toString(draw) + " " + Arrays.toString(size));
    }

    static void abstractMethod(GameDiff.Cls c, String name, String desc) {
        c.methods.put(name + desc, new GameDiff.Meth(c, name, desc, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT));
    }

    /** The new release's game classes, as the port reads their code. */
    static Map<String, byte[]> game() {
        Map<String, byte[]> out = new HashMap<>();
        out.put("t10/Draw", JarPortTest.real("t10/Draw", "java/lang/Object", 0, new String[0], w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "say", "(Ljava/lang/String;I)V", null, null);
            m.visitCode();
            m.visitFieldInsn(Opcodes.GETSTATIC, "g2/Log", "out", "Ljava/lang/StringBuilder;");
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            m.visitVarInsn(Opcodes.ILOAD, 1);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false);
            m.visitLdcInsn(";");
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            m.visitInsn(Opcodes.POP);
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        out.put("t10/Widget", JarPortTest.real("t10/Widget", "java/lang/Object", Opcodes.ACC_ABSTRACT, new String[0], w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PROTECTED, "defaultNarration", "(I)V", null, null);
            m.visitCode();
            m.visitLdcInsn("default");
            m.visitVarInsn(Opcodes.ILOAD, 1);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, "t10/Draw", "say", "(Ljava/lang/String;I)V", false);
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
            w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "narrate", "(I)V", null, null).visitEnd();
        }));
        for (String name : List.of("Wa", "Wb", "Wc")) {
            out.put("t10/" + name, JarPortTest.real("t10/" + name, "t10/Widget", 0, new String[0], w -> {
                w.visitField(Opcodes.ACC_PRIVATE, "label", "Ljava/lang/String;", null, null).visitEnd();
                MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "narrate", "(I)V", null, null);
                m.visitCode();
                m.visitVarInsn(Opcodes.ALOAD, 0);
                if (name.equals("Wc")) {
                    m.visitFieldInsn(Opcodes.GETFIELD, "t10/Wc", "label", "Ljava/lang/String;");
                    m.visitVarInsn(Opcodes.ILOAD, 1);
                    m.visitMethodInsn(Opcodes.INVOKESTATIC, "t10/Draw", "say", "(Ljava/lang/String;I)V", false);
                } else {
                    m.visitVarInsn(Opcodes.ILOAD, 1);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "t10/" + name, "defaultNarration", "(I)V", false);
                }
                m.visitInsn(Opcodes.RETURN);
                m.visitMaxs(0, 0);
                m.visitEnd();
            }));
        }
        out.put("t10/Entry", JarPortTest.real("t10/Entry", "java/lang/Object", Opcodes.ACC_ABSTRACT, new String[0], w -> {
            w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "refresh", "()V", null, null).visitEnd();
            w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "label", "()Ljava/lang/String;", null, null).visitEnd();
            w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "sync", "()V", null, null).visitEnd();
        }));
        for (String name : List.of("Ea", "Eb")) {
            out.put("t10/" + name, JarPortTest.real("t10/" + name, "t10/Entry", 0, new String[0], w -> {
                w.visitField(Opcodes.ACC_PRIVATE, "count", "I", null, null).visitEnd();
                for (String n : List.of("refresh", "sync")) {
                    MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, n, "()V", null, null);
                    m.visitCode();
                    if (n.equals("sync") || name.equals("Ea")) {
                        m.visitLdcInsn(name);
                        m.visitVarInsn(Opcodes.ALOAD, 0);
                        m.visitFieldInsn(Opcodes.GETFIELD, "t10/" + name, "count", "I");
                        m.visitMethodInsn(Opcodes.INVOKESTATIC, "t10/Draw", "say", "(Ljava/lang/String;I)V", false);
                    }
                    m.visitInsn(Opcodes.RETURN);
                    m.visitMaxs(0, 0);
                    m.visitEnd();
                }
                MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "label", "()Ljava/lang/String;", null, null);
                m.visitCode();
                if (name.equals("Ea")) {
                    m.visitLdcInsn("entry");
                } else {
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitFieldInsn(Opcodes.GETFIELD, "t10/Eb", "count", "I");
                    m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(I)Ljava/lang/String;", false);
                }
                m.visitInsn(Opcodes.ARETURN);
                m.visitMaxs(0, 0);
                m.visitEnd();
            }));
        }
        out.put("t10/Getter", JarPortTest.real("t10/Getter", "java/lang/Object", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, new String[0], w -> {
            w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "height", "()I", null, null).visitEnd();
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "maxHeight", "()I", null, null);
            m.visitCode();
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKEINTERFACE, "t10/Getter", "height", "()I", true);
            m.visitInsn(Opcodes.IRETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        for (String name : List.of("Ga", "Gb")) {
            out.put("t10/" + name, JarPortTest.real("t10/" + name, "java/lang/Object", 0, new String[] { "t10/Getter" }, w -> {
                MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "height", "()I", null, null);
                m.visitCode();
                m.visitInsn(Opcodes.ICONST_0);
                m.visitInsn(Opcodes.IRETURN);
                m.visitMaxs(0, 0);
                m.visitEnd();
            }));
        }
        out.put("t10/Shader", JarPortTest.real("t10/Shader", "java/lang/Object", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, new String[0], w -> {
            w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "vertex", "()V", null, null).visitEnd();
            w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "attach", "()V", null, null).visitEnd();
        }));
        out.put("t10/Sa", JarPortTest.real("t10/Sa", "java/lang/Object", 0, new String[] { "t10/Shader" }, w -> {
            for (String n : List.of("vertex", "attach")) {
                MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, n, "()V", null, null);
                m.visitCode();
                m.visitInsn(Opcodes.RETURN);
                m.visitMaxs(0, 0);
                m.visitEnd();
            }
        }));
        return out;
    }

    /** name extends t10.Entry and writes only method (empty or a fixed label). */
    static byte[] entry(String name, String method, String desc) {
        ClassWriter w = JarPortRetypeTest.writer(name, "t10/Entry");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, method, desc, null, null);
        m.visitCode();
        if (desc.endsWith("V")) {
            m.visitInsn(Opcodes.RETURN);
        } else {
            m.visitLdcInsn("mine");
            m.visitInsn(Opcodes.ARETURN);
        }
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** name implements iface, writing method()V empty when not null. */
    static byte[] implementing(String name, String iface, String method) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", new String[] { iface });
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        if (method != null) {
            m = w.visitMethod(Opcodes.ACC_PUBLIC, method, "()V", null, null);
            m.visitCode();
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
        w.visitEnd();
        return w.toByteArray();
    }

    /** n11.OldWidget extends t10.Widget and writes old(), which the new Widget no longer has. */
    static byte[] oldWidget() {
        ClassWriter w = JarPortRetypeTest.writer("n11/OldWidget", "t10/Widget");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "old", "()V", null, null);
        m.visitCode();
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }
}

import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * Abstract methods the new release adds whose body the game's own subclasses moved from an old method, run on this JVM
 * against real classes of the new release:
 * - t9.Button.renderWidget(int) becomes final and calls the new abstract renderContents(int), and every game button
 *   moved its renderWidget body there (1.21.11's AbstractButton). A mod button's renderWidget moves the same way. One
 *   calling super.renderWidget stays reported: the final one calls renderContents back.
 * - t9.Entry.render(top, mouseX) becomes renderContent(mouseX), the game's entries reading the top from this.getTop()
 *   (1.21.9's AbstractSelectionList.Entry). A mod entry's render moves with its top read the same way.
 * - t9.Widget drops old() as it adds fresh(), the game's widgets writing unrelated code there: nothing moved, so a mod's
 *   old() stays where it is and fresh() stays reported.
 */
final class JarPortTakeoverTest {

    static void check(Path scratch) throws Exception {
        GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
        JarPortTest.snap(a, "t9/Draw", 0, null);
        JarPortTest.snap(b, "t9/Draw", 0, null);
        JarPortTest.snap(a, "t9/Button", 0, null, "renderWidget(I)V");
        GameDiff.Cls button = JarPortTest.snap(b, "t9/Button", 0, null);
        method(button, "renderWidget", "(I)V", Opcodes.ACC_FINAL);
        method(button, "renderContents", "(I)V", Opcodes.ACC_ABSTRACT);
        JarPortTest.snap(a, "t9/Entry", 0, null, "render(II)V");
        GameDiff.Cls entry = JarPortTest.snap(b, "t9/Entry", 0, null, "getTop()I");
        method(entry, "renderContent", "(I)V", Opcodes.ACC_ABSTRACT);
        JarPortTest.snap(a, "t9/Widget", 0, null, "old()V");
        method(JarPortTest.snap(b, "t9/Widget", 0, null), "fresh", "()V", Opcodes.ACC_ABSTRACT);
        for (String sub : List.of("Plain", "Fancy")) {
            JarPortTest.snap(a, "t9/" + sub, 0, "t9/Button", "renderWidget(I)V");
            JarPortTest.snap(b, "t9/" + sub, 0, "t9/Button", "renderContents(I)V");
        }
        for (String sub : List.of("Row", "Line")) {
            JarPortTest.snap(a, "t9/" + sub, 0, "t9/Entry", "render(II)V");
            JarPortTest.snap(b, "t9/" + sub, 0, "t9/Entry", "renderContent(I)V");
        }
        for (String sub : List.of("Wa", "Wb")) {
            JarPortTest.snap(a, "t9/" + sub, 0, "t9/Widget", "old()V");
            JarPortTest.snap(b, "t9/" + sub, 0, "t9/Widget", "fresh()V");
        }
        Path map = scratch.resolve("route-takeover.json");
        Files.writeString(map, """
            {"signatures": [{"owner": "t9.Entry", "from": "render", "desc": "(II)V", "to": "renderContent", "newDesc": "(I)V"}]}
            """);
        JarPort port = new JarPort(a, b, map);
        Map<String, byte[]> before = new HashMap<>(), after = new HashMap<>();
        for (String sub : List.of("Plain", "Fancy")) {
            before.put("t9/" + sub, sub(sub, "t9/Button", "renderWidget", "(I)V", m -> draw(m, sub.equals("Plain") ? "box" : "frame")));
            after.put("t9/" + sub, sub(sub, "t9/Button", "renderContents", "(I)V", m -> draw(m, sub.equals("Plain") ? "box" : "frame")));
        }
        for (String sub : List.of("Row", "Line")) {
            before.put("t9/" + sub, sub(sub, "t9/Entry", "render", "(II)V", m -> {
                m.visitVarInsn(Opcodes.ILOAD, 1);
                m.visitVarInsn(Opcodes.ILOAD, 2);
                m.visitMethodInsn(Opcodes.INVOKESTATIC, "t9/Draw", sub.equals("Row") ? "at" : "line", "(II)V", false);
            }));
            after.put("t9/" + sub, sub(sub, "t9/Entry", "renderContent", "(I)V", m -> {
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "t9/" + sub, "getTop", "()I", false);
                m.visitVarInsn(Opcodes.ILOAD, 1);
                m.visitMethodInsn(Opcodes.INVOKESTATIC, "t9/Draw", sub.equals("Row") ? "at" : "line", "(II)V", false);
            }));
        }
        for (String sub : List.of("Wa", "Wb")) {
            before.put("t9/" + sub, sub(sub, "t9/Widget", "old", "()V", m -> {
                m.visitInsn(Opcodes.ICONST_1);
                m.visitMethodInsn(Opcodes.INVOKESTATIC, "t9/Draw", "box", "(I)V", false);
            }));
            after.put("t9/" + sub, sub(sub, "t9/Widget", "fresh", "()V", m -> {
                m.visitLdcInsn("x");
                m.visitMethodInsn(Opcodes.INVOKESTATIC, "t9/Draw", "text", "(Ljava/lang/String;)V", false);
                m.visitLdcInsn("y");
                m.visitMethodInsn(Opcodes.INVOKESTATIC, "t9/Draw", "text", "(Ljava/lang/String;)V", false);
            }));
        }
        port.oldCode = new JarPort.GameCode(before::get, null);
        port.newCode = new JarPort.GameCode(after::get, null);

        List<Map<String, Object>> runs = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("n10/MyButton.class", myButton(false), "n10/MyRow.class", myRow())), "takeover.jar", runs));
        List<String> unlinked = Json.array(runs.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(unlinked.isEmpty(), "a mod's override moves where the game's own subclasses moved theirs: " + unlinked);

        List<Map<String, Object>> refused = new ArrayList<>();
        port.port(JarPortTest.jar(Map.of("n10/SuperButton.class", myButton(true), "n10/MyWidget.class", myWidget())), "refused.jar", refused);
        List<String> left = Json.array(refused.get(0).get("unlinked")).stream().map(String::valueOf).sorted().toList();
        JarPortTest.expect(left.equals(List.of("abstract method not written: t9.Button.renderContents(I)V (in n10.SuperButton)",
            "abstract method not written: t9.Widget.fresh()V (in n10.MyWidget)")),
            "an override calling the final method back, and one whose game method went without its body moving anywhere, stay reported: " + left);

        Map<String, byte[]> classes = new HashMap<>(JarPortTest.newGame());
        classes.putAll(game());
        classes.put("n10/MyButton", ported.get("n10/MyButton.class"));
        classes.put("n10/MyRow", ported.get("n10/MyRow.class"));
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
            Class<?> buttonClass = loader.loadClass("t9.Button"), entryClass = loader.loadClass("t9.Entry");
            buttonClass.getMethod("renderWidget", int.class).invoke(loader.loadClass("n10.MyButton").getConstructor().newInstance(), 5);
            entryClass.getMethod("run", entryClass).invoke(null, loader.loadClass("n10.MyRow").getConstructor().newInstance());
            trace = String.valueOf(loader.loadClass("g2.Log").getField("out").get(null));
        } catch (ReflectiveOperationException | LinkageError failed) {
            trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
        JarPortTest.expect(trace.equals("mine5;at7,3;"), "the moved overrides run against the new release, the entry's top read from it: " + trace);
    }

    static void method(GameDiff.Cls c, String name, String desc, int flags) {
        c.methods.put(name + desc, new GameDiff.Meth(c, name, desc, Opcodes.ACC_PUBLIC | flags));
    }

    /** A game subclass of sup whose method name+desc runs body. */
    static byte[] sub(String name, String sup, String method, String desc, java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter w = JarPortRetypeTest.writer("t9/" + name, sup);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, method, desc, null, null);
        m.visitCode();
        body.accept(m);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    static void draw(MethodVisitor m, String what) {
        m.visitVarInsn(Opcodes.ILOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "t9/Draw", what, "(I)V", false);
    }

    /** n10.MyButton extends t9.Button: renderWidget(x) logs "mine" x, after super.renderWidget(x) when calls. */
    static byte[] myButton(boolean calls) {
        String name = calls ? "n10/SuperButton" : "n10/MyButton";
        ClassWriter w = JarPortRetypeTest.writer(name, "t9/Button");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "renderWidget", "(I)V", null, null);
        m.visitCode();
        if (calls) {
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitVarInsn(Opcodes.ILOAD, 1);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "t9/Button", "renderWidget", "(I)V", false);
        }
        m.visitVarInsn(Opcodes.ILOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "t9/Draw", "mine", "(I)V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** n10.MyRow extends t9.Entry: render(top, mouseX) = Draw.at(top, mouseX). */
    static byte[] myRow() {
        ClassWriter w = JarPortRetypeTest.writer("n10/MyRow", "t9/Entry");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "render", "(II)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ILOAD, 1);
        m.visitVarInsn(Opcodes.ILOAD, 2);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "t9/Draw", "at", "(II)V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** n10.MyWidget extends t9.Widget: old() = Draw.box(2). */
    static byte[] myWidget() {
        ClassWriter w = JarPortRetypeTest.writer("n10/MyWidget", "t9/Widget");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "old", "()V", null, null);
        m.visitCode();
        m.visitInsn(Opcodes.ICONST_2);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "t9/Draw", "box", "(I)V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** The new release as real classes: Draw logs what it is asked, Button's final renderWidget calls renderContents, Entry.run renders at mouse 3. */
    static Map<String, byte[]> game() {
        Map<String, byte[]> out = new HashMap<>();
        out.put("t9/Draw", JarPortTest.real("t9/Draw", "java/lang/Object", 0, new String[0], w -> {
            for (String[] d : new String[][] { { "mine", "(I)V" }, { "box", "(I)V" }, { "at", "(II)V" } }) {
                MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, d[0], d[1], null, null);
                m.visitCode();
                m.visitFieldInsn(Opcodes.GETSTATIC, "g2/Log", "out", "Ljava/lang/StringBuilder;");
                m.visitLdcInsn(d[0]);
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                m.visitVarInsn(Opcodes.ILOAD, 0);
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false);
                if (d[1].equals("(II)V")) {
                    m.visitLdcInsn(",");
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                    m.visitVarInsn(Opcodes.ILOAD, 1);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false);
                }
                m.visitLdcInsn(";");
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                m.visitInsn(Opcodes.POP);
                m.visitInsn(Opcodes.RETURN);
                m.visitMaxs(0, 0);
                m.visitEnd();
            }
        }));
        out.put("t9/Button", JarPortTest.real("t9/Button", "java/lang/Object", Opcodes.ACC_ABSTRACT, new String[0], w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "renderWidget", "(I)V", null, null);
            m.visitCode();
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitVarInsn(Opcodes.ILOAD, 1);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "t9/Button", "renderContents", "(I)V", false);
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
            w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "renderContents", "(I)V", null, null).visitEnd();
        }));
        out.put("t9/Entry", JarPortTest.real("t9/Entry", "java/lang/Object", Opcodes.ACC_ABSTRACT, new String[0], w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "getTop", "()I", null, null);
            m.visitCode();
            m.visitIntInsn(Opcodes.BIPUSH, 7);
            m.visitInsn(Opcodes.IRETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
            w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "renderContent", "(I)V", null, null).visitEnd();
            m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(Lt9/Entry;)V", null, null);
            m.visitCode();
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitInsn(Opcodes.ICONST_3);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "t9/Entry", "renderContent", "(I)V", false);
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        return out;
    }
}

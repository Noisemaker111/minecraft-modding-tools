import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * Mod overrides of game methods the new release made final, run on this JVM against real classes of the new release:
 * - u9.Widget.render(x) becomes final and calls renderWidget(x) (1.20.3's AbstractWidget). A mod button's render, which
 *   drew around super.render, moves to renderWidget with its super call to the button's renderWidget; a mod widget that
 *   writes renderWidget too keeps that under another name, and its moved render's super call goes there. Code calling
 *   render on the button still goes through the final method. One calling render on itself again stays reported.
 * - u9.Btn.renderWidget(x) becomes final and calls the new renderContents(x), the old body split into sprite(x) and
 *   label(x), which the new u9.Btn$Plain calls (1.21.11's AbstractButton and Button.Plain). A mod button's super
 *   renderWidget runs those parts.
 */
final class JarPortFinalTest {

    static void check(Path scratch) throws Exception {
        GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
        for (GameDiff.Snap s : List.of(a, b)) {
            JarPortTest.snap(s, "u9/Draw", 0, null);
        }
        GameDiff.Cls widgetOld = JarPortTest.snap(a, "u9/Widget", Opcodes.ACC_ABSTRACT, null, "render(I)V");
        method(widgetOld, "renderWidget", "(I)V", Opcodes.ACC_ABSTRACT);
        GameDiff.Cls widget = JarPortTest.snap(b, "u9/Widget", Opcodes.ACC_ABSTRACT, null);
        method(widget, "render", "(I)V", Opcodes.ACC_FINAL);
        method(widget, "renderWidget", "(I)V", Opcodes.ACC_ABSTRACT);
        JarPortTest.snap(a, "u9/Button", 0, "u9/Widget", "renderWidget(I)V");
        JarPortTest.snap(b, "u9/Button", 0, "u9/Widget", "renderWidget(I)V");
        JarPortTest.snap(a, "u9/Btn", 0, "u9/Widget", "renderWidget(I)V");
        GameDiff.Cls btn = JarPortTest.snap(b, "u9/Btn", Opcodes.ACC_ABSTRACT, "u9/Widget");
        method(btn, "renderWidget", "(I)V", Opcodes.ACC_FINAL);
        method(btn, "renderContents", "(I)V", Opcodes.ACC_ABSTRACT);
        btn.methods.put("sprite(I)V", new GameDiff.Meth(btn, "sprite", "(I)V", Opcodes.ACC_PROTECTED));
        btn.methods.put("label(I)V", new GameDiff.Meth(btn, "label", "(I)V", Opcodes.ACC_PROTECTED));
        JarPortTest.snap(b, "u9/Btn$Plain", 0, "u9/Btn", "renderContents(I)V");
        Path map = scratch.resolve("route-final.json");
        Files.writeString(map, "{}");
        JarPort port = new JarPort(a, b, map);
        Map<String, byte[]> game = game();
        port.newCode = new JarPort.GameCode(game::get, null);
        port.oldCode = new JarPort.GameCode(Map.<String, byte[]>of()::get, null);

        List<Map<String, Object>> runs = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("n11/MyButton.class", around("n11/MyButton", "u9/Button", false),
            "n11/OwnHook.class", around("n11/OwnHook", "u9/Widget", true), "n11/Fancy.class", fancy(), "n11/Caller.class", caller())), "final.jar", runs));
        List<String> unlinked = Json.array(runs.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(unlinked.isEmpty(), "overrides of methods the update made final move to the hooks those call: " + unlinked);

        List<Map<String, Object>> refused = new ArrayList<>();
        port.port(JarPortTest.jar(Map.of("n11/Again.class", again())), "again.jar", refused);
        List<String> left = Json.array(refused.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(left.equals(List.of("final method overridden: u9.Widget.render(I)V (in n11.Again)")),
            "an override calling the final method on itself again stays reported: " + left);

        Map<String, byte[]> classes = new HashMap<>(JarPortTest.newGame());
        classes.putAll(game);
        for (String c : List.of("n11/MyButton", "n11/OwnHook", "n11/Fancy", "n11/Caller")) {
            classes.put(c, ported.get(c + ".class"));
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
            Class<?> w = loader.loadClass("u9.Widget"), caller = loader.loadClass("n11.Caller");
            caller.getMethod("draw", loader.loadClass("n11.MyButton")).invoke(null, loader.loadClass("n11.MyButton").getConstructor().newInstance());
            w.getMethod("render", int.class).invoke(loader.loadClass("n11.OwnHook").getConstructor().newInstance(), 6);
            w.getMethod("render", int.class).invoke(loader.loadClass("n11.Fancy").getConstructor().newInstance(), 7);
            trace = String.valueOf(loader.loadClass("g2.Log").getField("out").get(null));
        } catch (ReflectiveOperationException | LinkageError failed) {
            trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
        JarPortTest.expect(trace.equals("frame5;pre5;button5;frame6;pre6;hook6;frame7;sprite7;label7;extra7;"),
            "the moved overrides run inside the final methods, their super calls reaching the hook, the class's own hook and the split parts: " + trace);
    }

    static void method(GameDiff.Cls c, String name, String desc, int flags) {
        c.methods.put(name + desc, new GameDiff.Meth(c, name, desc, Opcodes.ACC_PUBLIC | flags));
    }

    static void draw(MethodVisitor m, String what) {
        m.visitVarInsn(Opcodes.ILOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "u9/Draw", what, "(I)V", false);
    }

    static void superCall(MethodVisitor m, String owner, String name) {
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ILOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, name, "(I)V", false);
    }

    static void method(ClassWriter w, String name, java.util.function.Consumer<MethodVisitor> body) {
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, name, "(I)V", null, null);
        m.visitCode();
        body.accept(m);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** name extends sup: render(x) = pre(x); super.render(x); with ownHook, renderWidget(x) = hook(x) too. */
    static byte[] around(String name, String sup, boolean ownHook) {
        ClassWriter w = JarPortRetypeTest.writer(name, sup);
        method(w, "render", m -> {
            draw(m, "pre");
            superCall(m, sup, "render");
        });
        if (ownHook) {
            method(w, "renderWidget", m -> draw(m, "hook"));
        }
        w.visitEnd();
        return w.toByteArray();
    }

    /** n11.Fancy extends u9.Btn: renderWidget(x) = super.renderWidget(x); extra(x). */
    static byte[] fancy() {
        ClassWriter w = JarPortRetypeTest.writer("n11/Fancy", "u9/Btn");
        method(w, "renderWidget", m -> {
            superCall(m, "u9/Btn", "renderWidget");
            draw(m, "extra");
        });
        w.visitEnd();
        return w.toByteArray();
    }

    /** n11.Again extends u9.Button: render(x) = if (x > 0) this.render(x - 1). */
    static byte[] again() {
        ClassWriter w = JarPortRetypeTest.writer("n11/Again", "u9/Button");
        method(w, "render", m -> {
            Label done = new Label();
            m.visitVarInsn(Opcodes.ILOAD, 1);
            m.visitJumpInsn(Opcodes.IFLE, done);
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitVarInsn(Opcodes.ILOAD, 1);
            m.visitInsn(Opcodes.ICONST_1);
            m.visitInsn(Opcodes.ISUB);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "n11/Again", "render", "(I)V", false);
            m.visitLabel(done);
        });
        w.visitEnd();
        return w.toByteArray();
    }

    /** n11.Caller.draw(button) = button.render(5). */
    static byte[] caller() {
        ClassWriter w = JarPortRetypeTest.writer("n11/Caller", "java/lang/Object");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "draw", "(Ln11/MyButton;)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitInsn(Opcodes.ICONST_5);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "n11/MyButton", "render", "(I)V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** The new release as real classes; Draw logs what it is asked. */
    static Map<String, byte[]> game() {
        Map<String, byte[]> out = new HashMap<>();
        out.put("u9/Draw", JarPortTest.real("u9/Draw", "java/lang/Object", 0, new String[0], w -> {
            for (String d : List.of("frame", "pre", "button", "hook", "sprite", "label", "extra")) {
                MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, d, "(I)V", null, null);
                m.visitCode();
                m.visitFieldInsn(Opcodes.GETSTATIC, "g2/Log", "out", "Ljava/lang/StringBuilder;");
                m.visitLdcInsn(d);
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                m.visitVarInsn(Opcodes.ILOAD, 0);
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false);
                m.visitLdcInsn(";");
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                m.visitInsn(Opcodes.POP);
                m.visitInsn(Opcodes.RETURN);
                m.visitMaxs(0, 0);
                m.visitEnd();
            }
        }));
        out.put("u9/Widget", JarPortTest.real("u9/Widget", "java/lang/Object", Opcodes.ACC_ABSTRACT, new String[0], w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "render", "(I)V", null, null);
            m.visitCode();
            draw(m, "frame");
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitVarInsn(Opcodes.ILOAD, 1);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "u9/Widget", "renderWidget", "(I)V", false);
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
            w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "renderWidget", "(I)V", null, null).visitEnd();
        }));
        out.put("u9/Button", JarPortTest.real("u9/Button", "u9/Widget", 0, new String[0], w -> method(w, "renderWidget", m -> draw(m, "button"))));
        out.put("u9/Btn", JarPortTest.real("u9/Btn", "u9/Widget", Opcodes.ACC_ABSTRACT, new String[0], w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "renderWidget", "(I)V", null, null);
            m.visitCode();
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitVarInsn(Opcodes.ILOAD, 1);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "u9/Btn", "renderContents", "(I)V", false);
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
            w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "renderContents", "(I)V", null, null).visitEnd();
            for (String part : List.of("sprite", "label")) {
                m = w.visitMethod(Opcodes.ACC_PROTECTED, part, "(I)V", null, null);
                m.visitCode();
                draw(m, part);
                m.visitInsn(Opcodes.RETURN);
                m.visitMaxs(0, 0);
                m.visitEnd();
            }
        }));
        out.put("u9/Btn$Plain", JarPortTest.real("u9/Btn$Plain", "u9/Btn", 0, new String[0], w -> method(w, "renderContents", m -> {
            for (String part : List.of("sprite", "label")) {
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitVarInsn(Opcodes.ILOAD, 1);
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "u9/Btn$Plain", part, "(I)V", false);
            }
        })));
        return out;
    }
}

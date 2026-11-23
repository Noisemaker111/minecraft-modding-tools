import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * Mixins into game code the update moved, followed to where the game's own code went:
 * - f8.Hud.tick(int) is gone and the new update(int, float) has its code: an @Inject into tick follows to update, its
 *   handler re-declared for the new parameters, though the new other() shares half its code. f8.Bar.go() is gone with two
 *   new methods that read like it equally, so nothing
 *   says which one took its code and it stays reported.
 * - f8.Hud.render() no longer calls air() itself: it calls the new private renderAir(), which does (1.21.2's
 *   Gui.renderPlayerHealth and renderAirBubbles). An @Inject at that call follows into renderAir. f8.Bar.draw() moved its
 *   call into part(), which another method calls too, so the injector would run more often there and stays reported.
 * - f8.Hud.layers() hands its drawing to the new drawLayer(), which setup() names (1.20.5 split Gui.render into layer
 *   methods registered by reference): drawLayer's code is the old layers' code, so an @Inject at its air() call follows.
 * - f8.Screen.init(Mc, int, int) becomes init(int, int); the old one stored the Mc in this.mc, which only the new
 *   constructor sets now (1.21.11's Screen.init). A handler that took the Mc reads this.mc. f8.Pane's new init sets its mc
 *   itself, so at the new method's start the field may not hold it yet, and the handler stays reported. f8.Lid's new
 *   class has a second constructor that never sets mc, so a Lid made that way has none: stays reported too.
 * - What is not followed: a cancellable @Inject at render's air() call (its cancel would end only renderAir, and render
 *   would run on); f8.Box.show(), whose air() call went away while its private helper foot() always had one (the hook
 *   would land on other code); f8.Wrap.wrap(String, String), gone, whose short body reads like the new wrap2 only if
 *   the ';' inside descriptors were taken for instruction boundaries (wrap2 calls another method).
 */
final class JarPortFollowTest {

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        check(scratch);
        JarPortTest.finish("JarPortFollowTest");
    }

    static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    static final String MC = "Lf8/Mc;";

    static void check(Path scratch) throws Exception {
        GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
        for (GameDiff.Snap s : List.of(a, b)) {
            JarPortTest.snap(s, "f8/Mc", 0, null);
            JarPortTest.snap(s, "f8/Sink", 0, null, "a(I)V", "b(I)V", "c(I)V", "d(I)V");
        }
        JarPortTest.snap(a, "f8/Hud", 0, null, "tick(I)V", "render()V", "air()I", "layers()V", "setup()V");
        GameDiff.Cls hud = JarPortTest.snap(b, "f8/Hud", 0, null, "update(IF)V", "other()V", "render()V", "air()I", "layers()V", "setup()V", "drawLayer()V");
        hud.methods.put("renderAir()V", new GameDiff.Meth(hud, "renderAir", "()V", Opcodes.ACC_PRIVATE));
        JarPortTest.snap(a, "f8/Bar", 0, null, "go()V", "draw()V", "air()I", "more()V");
        GameDiff.Cls bar = JarPortTest.snap(b, "f8/Bar", 0, null, "go1()V", "go2()V", "draw()V", "air()I", "more()V");
        bar.methods.put("part()V", new GameDiff.Meth(bar, "part", "()V", Opcodes.ACC_PRIVATE));
        String wd = "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
        for (GameDiff.Snap s : List.of(a, b)) {
            GameDiff.Cls text = JarPortTest.snap(s, "f8/Text", 0, null);
            for (String t : List.of("sanitize", "log")) {
                text.methods.put(t + wd, new GameDiff.Meth(text, t, wd, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
            }
        }
        JarPortTest.snap(a, "f8/Box", 0, null, "show()V", "air()I");
        JarPortTest.snap(b, "f8/Box", 0, null, "show()V", "air()I");
        for (GameDiff.Snap s : List.of(a, b)) {
            GameDiff.Cls box = s.classes.get("f8/Box");
            box.methods.put("foot()V", new GameDiff.Meth(box, "foot", "()V", Opcodes.ACC_PRIVATE));
        }
        GameDiff.Cls oldWrap = JarPortTest.snap(a, "f8/Wrap", 0, null);
        oldWrap.methods.put("wrap" + wd, new GameDiff.Meth(oldWrap, "wrap", wd, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
        GameDiff.Cls newWrap = JarPortTest.snap(b, "f8/Wrap", 0, null);
        newWrap.methods.put("wrap2" + wd, new GameDiff.Meth(newWrap, "wrap2", wd, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
        for (String pane : List.of("f8/Screen", "f8/Pane", "f8/Lid")) {
            JarPortTest.snap(a, pane, 0, null, "init(" + MC + "II)V");
            JarPortTest.snap(b, pane, 0, null, "init(II)V");
            for (GameDiff.Snap s : List.of(a, b)) {
                GameDiff.Cls c = s.classes.get(pane);
                c.fields.put("mc", new GameDiff.Fld(c, "mc", MC, Opcodes.ACC_PROTECTED));
            }
        }
        Path map = scratch.resolve("route-follow.json");
        Files.writeString(map, "{}");
        JarPort port = new JarPort(a, b, map);
        port.oldCode = new GameCode(Map.of("f8/Hud", hud(true), "f8/Bar", bar(true), "f8/Screen", pane("f8/Screen", true, false, false), "f8/Pane", pane("f8/Pane", true, false, false),
            "f8/Lid", pane("f8/Lid", true, false, false), "f8/Box", box(true), "f8/Wrap", wrap(true))::get, null);
        port.newCode = new GameCode(Map.of("f8/Hud", hud(false), "f8/Bar", bar(false), "f8/Screen", pane("f8/Screen", false, false, false), "f8/Pane", pane("f8/Pane", false, true, false),
            "f8/Lid", pane("f8/Lid", false, false, true), "f8/Box", box(false), "f8/Wrap", wrap(false))::get, null);

        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(Map.of(
            "m8/HudMixin.class", mixin("m8/HudMixin", "f8/Hud", "onTick", "(I" + CI + ")V", "tick(I)V", "HEAD", null, "onAir", "render()V", "Lf8/Hud;air()I"),
            "m8/LayerMixin.class", mixin("m8/LayerMixin", "f8/Hud", "onSetup", "(" + CI + ")V", "setup()V", "HEAD", null, "onLayer", "layers()V", "Lf8/Hud;air()I"),
            "m8/BarMixin.class", mixin("m8/BarMixin", "f8/Bar", "onGo", "(" + CI + ")V", "go()V", "HEAD", null, "onAir", "draw()V", "Lf8/Bar;air()I"),
            "m8/ScreenMixin.class", initMixin("m8/ScreenMixin", "f8/Screen"),
            "m8/PaneMixin.class", initMixin("m8/PaneMixin", "f8/Pane"),
            "m8/LidMixin.class", initMixin("m8/LidMixin", "f8/Lid"),
            "m8/CancelMixin.class", cancelMixin(),
            "m8/BoxMixin.class", mixin("m8/BoxMixin", "f8/Box", "onShow", "(" + CI + ")V", "show()V", "HEAD", null, "onAir", "show()V", "Lf8/Box;air()I"),
            "m8/WrapMixin.class", mixin("m8/WrapMixin", "f8/Wrap", "onWrap", "(" + CI + ")V", "wrap" + wd, "HEAD", null, "onWrapAt", "wrap" + wd, "Lf8/Text;log" + wd),
            "m8.mixins.json", "{\"package\": \"m8\", \"mixins\": [\"HudMixin\", \"LayerMixin\", \"BarMixin\", \"ScreenMixin\", \"PaneMixin\", \"LidMixin\", \"CancelMixin\", \"BoxMixin\", \"WrapMixin\"], \"injectors\": {\"defaultRequire\": 1}}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8))), "follow.jar", results));
        List<String> unlinked = Json.array(results.get(0).get("unlinked")).stream().map(String::valueOf).toList();

        Map<String, String> hudStrings = JarPortFixesTest.strings(ported.get("m8/HudMixin.class"));
        JarPortTest.expect(hudStrings.getOrDefault("onTick(IF" + CI + ")V", "").startsWith("update(IF)V HEAD") && !hudStrings.containsKey("onTick(I" + CI + ")V"),
            "an @Inject into a method the update dropped follows the new method that has its code, re-declared for its parameters: " + hudStrings);
        JarPortTest.expect(unlinked.stream().noneMatch(u -> u.contains("f8.Hud.tick")),
            "the followed injector is no break: " + unlinked);
        JarPortTest.expect(unlinked.contains("mixin target gone: f8.Bar.go()V"),
            "a dropped method two new methods read like is not followed: " + unlinked);

        JarPortTest.expect(hudStrings.getOrDefault("onAir(" + CI + ")V", "").startsWith("renderAir()V INVOKE Lf8/Hud;air()I"),
            "an @Inject at a call the method moved into a helper of its own follows into the helper: " + hudStrings);
        JarPortTest.expect(unlinked.stream().noneMatch(u -> u.contains("in f8.Hud.render") && !u.contains("CancelMixin")),
            "the injection point that moved into the helper is no break: " + unlinked);
        JarPortTest.expect(JarPortFixesTest.strings(ported.get("m8/LayerMixin.class")).getOrDefault("onLayer(" + CI + ")V", "").startsWith("drawLayer()V INVOKE Lf8/Hud;air()I")
                && unlinked.stream().noneMatch(u -> u.contains("in f8.Hud.layers")),
            "an @Inject at a call the method handed to a new method made of its own code follows there: " + JarPortFixesTest.strings(ported.get("m8/LayerMixin.class")) + " " + unlinked);
        JarPortTest.expect(unlinked.stream().anyMatch(u -> u.startsWith("mixin injection point gone: f8.Bar.air()I in f8.Bar.draw()V")),
            "a helper something else calls too is not injected into: " + unlinked);

        String init = body(ported.get("m8/ScreenMixin.class"), "onInit", "(II" + CI + ")V");
        JarPortTest.expect(init != null && init.contains("GETFIELD f8/Screen.mc"),
            "a handler that took the argument the game now keeps on the object reads it there: " + init + " " + JarPortTest.members(ported.get("m8/ScreenMixin.class")).keySet());
        JarPortTest.expect(unlinked.stream().noneMatch(u -> u.contains("f8.Screen.init")) && unlinked.stream().anyMatch(u -> u.contains("f8.Pane.init")),
            "the field is read only where the new release sets it before any method runs: " + unlinked);
        JarPortTest.expect(unlinked.stream().anyMatch(u -> u.contains("f8.Lid.init")),
            "nor where one of its constructors never sets it: " + unlinked);

        JarPortTest.expect(JarPortFixesTest.strings(ported.get("m8/CancelMixin.class")).getOrDefault("onCancel(" + CI + ")V", "").startsWith("render()V INVOKE Lf8/Hud;air()I")
                && unlinked.stream().anyMatch(u -> u.startsWith("mixin injection point gone: f8.Hud.air()I in f8.Hud.render()V (in m8.CancelMixin)")),
            "a cancellable @Inject is not moved into a helper, where its cancel would not stop the method: " + JarPortFixesTest.strings(ported.get("m8/CancelMixin.class")) + " " + unlinked);
        JarPortTest.expect(unlinked.stream().anyMatch(u -> u.startsWith("mixin injection point gone: f8.Box.air()I in f8.Box.show()V")),
            "a helper that always had the point is not where it moved: " + unlinked);
        JarPortTest.expect(unlinked.contains("mixin target gone: f8.Wrap.wrap" + wd),
            "a short body is not matched through the ';' of its descriptors: " + unlinked);
    }

    /** m8.CancelMixin: a cancellable @Inject into f8.Hud.render() at its air() call. */
    static byte[] cancelMixin() {
        ClassWriter w = JarPortRetypeTest.writer("m8/CancelMixin", "java/lang/Object");
        AnnotationVisitor t = w.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor value = t.visitArray("value");
        value.visit(null, Type.getObjectType("f8/Hud"));
        value.visitEnd();
        t.visitEnd();
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PRIVATE, "onCancel", "(" + CI + ")V", null, null);
        AnnotationVisitor av = m.visitAnnotation(JarPortFixesTest.INJECT, false);
        AnnotationVisitor methods = av.visitArray("method");
        methods.visit(null, "render()V");
        methods.visitEnd();
        AnnotationVisitor ats = av.visitArray("at");
        AnnotationVisitor point = ats.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
        point.visit("value", "INVOKE");
        point.visit("target", "Lf8/Hud;air()I");
        point.visitEnd();
        ats.visitEnd();
        av.visit("cancellable", true);
        av.visitEnd();
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/spongepowered/asm/mixin/injection/callback/CallbackInfo", "cancel", "()V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** f8.Box's code: show() calls air() itself (old) or only the private foot() (new); foot() calls air() in both. */
    static byte[] box(boolean old) {
        ClassWriter w = JarPortRetypeTest.writer("f8/Box", "java/lang/Object");
        method(w, "air", "()I", Opcodes.ACC_PUBLIC, null);
        method(w, "show", "()V", Opcodes.ACC_PUBLIC, m -> call(m, "f8/Box", old ? "air" : "foot", old ? "()I" : "()V"));
        method(w, "foot", "()V", Opcodes.ACC_PRIVATE, m -> call(m, "f8/Box", "air", "()I"));
        w.visitEnd();
        return w.toByteArray();
    }

    /** f8.Wrap's code: the old static wrap(a, b) returns Text.sanitize(a, b); the new wrap2(a, b) returns Text.log(a, b). */
    static byte[] wrap(boolean old) {
        String wd = "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
        ClassWriter w = JarPortRetypeTest.writer("f8/Wrap", "java/lang/Object");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, old ? "wrap" : "wrap2", wd, null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "f8/Text", old ? "sanitize" : "log", wd, false);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** A mixin into target with two @Injects: name1 into method1 at at1 (no target), and name2 into method2 at a call of call2. */
    static byte[] mixin(String name, String target, String name1, String desc1, String method1, String at1, String unused, String name2, String method2, String call2) {
        ClassWriter w = JarPortRetypeTest.writer(name, "java/lang/Object");
        AnnotationVisitor t = w.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor value = t.visitArray("value");
        value.visit(null, Type.getObjectType(target));
        value.visitEnd();
        t.visitEnd();
        JarPortFixesTest.handler(w, JarPortFixesTest.INJECT, name1, desc1, method1, at1, null, m -> { });
        JarPortFixesTest.handler(w, JarPortFixesTest.INJECT, name2, "(" + CI + ")V", method2, "INVOKE", call2, m -> { });
        w.visitEnd();
        return w.toByteArray();
    }

    /** A mixin into target's init(Mc, int, int) at HEAD whose handler reads the Mc it is given. */
    static byte[] initMixin(String name, String target) {
        ClassWriter w = JarPortRetypeTest.writer(name, "java/lang/Object");
        AnnotationVisitor t = w.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor value = t.visitArray("value");
        value.visit(null, Type.getObjectType(target));
        value.visitEnd();
        t.visitEnd();
        JarPortFixesTest.handler(w, JarPortFixesTest.INJECT, "onInit", "(" + MC + "II" + CI + ")V", "init", "HEAD", null, m -> {
            m.visitVarInsn(Opcodes.ALOAD, 1);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNull", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            m.visitInsn(Opcodes.POP);
        });
        w.visitEnd();
        return w.toByteArray();
    }

    /** f8.Hud's code: the old tick(int) and the new update(int, float) feed the same sinks; render calls air() itself (old) or through renderAir() (new). */
    static byte[] hud(boolean old) {
        ClassWriter w = JarPortRetypeTest.writer("f8/Hud", "java/lang/Object");
        method(w, old ? "tick" : "update", old ? "(I)V" : "(IF)V", Opcodes.ACC_PUBLIC, m -> sinks(m, "a", "b", "c", "d"));
        if (!old) {
            // Shares half of tick's code: alike, but update(int, float) is the clear one.
            method(w, "other", "()V", Opcodes.ACC_PUBLIC, m -> sinks(m, "a", "b"));
        }
        method(w, "air", "()I", Opcodes.ACC_PUBLIC, null);
        method(w, "render", "()V", Opcodes.ACC_PUBLIC, m -> call(m, "f8/Hud", old ? "air" : "renderAir", old ? "()I" : "()V"));
        if (!old) {
            method(w, "renderAir", "()V", Opcodes.ACC_PRIVATE, m -> call(m, "f8/Hud", "air", "()I"));
            method(w, "drawLayer", "()V", Opcodes.ACC_PUBLIC, m -> layer(m));
        }
        method(w, "layers", "()V", Opcodes.ACC_PUBLIC, m -> {
            if (old) {
                layer(m);
            }
        });
        method(w, "setup", "()V", Opcodes.ACC_PUBLIC, m -> {
            if (!old) {
                call(m, "f8/Hud", "drawLayer", "()V");
            }
        });
        w.visitEnd();
        return w.toByteArray();
    }

    /** f8.Bar's code: go() becomes two alike methods; draw() calls air() itself (old) or through part(), which more() calls too (new). */
    static byte[] bar(boolean old) {
        ClassWriter w = JarPortRetypeTest.writer("f8/Bar", "java/lang/Object");
        for (String go : old ? List.of("go") : List.of("go1", "go2")) {
            method(w, go, "()V", Opcodes.ACC_PUBLIC, m -> sinks(m, "a", "b", "c", "d"));
        }
        method(w, "air", "()I", Opcodes.ACC_PUBLIC, null);
        method(w, "draw", "()V", Opcodes.ACC_PUBLIC, m -> call(m, "f8/Bar", old ? "air" : "part", old ? "()I" : "()V"));
        method(w, "more", "()V", Opcodes.ACC_PUBLIC, m -> {
            if (!old) {
                call(m, "f8/Bar", "part", "()V");
            }
        });
        if (!old) {
            method(w, "part", "()V", Opcodes.ACC_PRIVATE, m -> call(m, "f8/Bar", "air", "()I"));
        }
        w.visitEnd();
        return w.toByteArray();
    }

    /** A pane's code: the old init(Mc, int, int) stores its Mc in this.mc; the new constructor takes the Mc, the new init(int, int) stores it too where again, and bare adds a constructor that never sets it. */
    static byte[] pane(String name, boolean old, boolean again, boolean bare) {
        // No default constructor: the panes' constructors are the cases.
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        w.visitField(Opcodes.ACC_PROTECTED, "mc", MC, null, null).visitEnd();
        method(w, "<init>", old ? "()V" : "(" + MC + ")V", Opcodes.ACC_PUBLIC, m -> {
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            if (!old) {
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitVarInsn(Opcodes.ALOAD, 1);
                m.visitFieldInsn(Opcodes.PUTFIELD, name, "mc", MC);
            }
        });
        if (bare) {
            method(w, "<init>", "()V", Opcodes.ACC_PUBLIC, m -> {
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            });
        }
        method(w, "init", old ? "(" + MC + "II)V" : "(II)V", Opcodes.ACC_PUBLIC, m -> {
            if (old || again) {
                m.visitVarInsn(Opcodes.ALOAD, 0);
                if (old) {
                    m.visitVarInsn(Opcodes.ALOAD, 1);
                } else {
                    m.visitTypeInsn(Opcodes.NEW, "f8/Mc");
                    m.visitInsn(Opcodes.DUP);
                    m.visitMethodInsn(Opcodes.INVOKESPECIAL, "f8/Mc", "<init>", "()V", false);
                }
                m.visitFieldInsn(Opcodes.PUTFIELD, name, "mc", MC);
            }
        });
        w.visitEnd();
        return w.toByteArray();
    }

    /** What f8.Hud draws as a layer: a constant to two sinks, then air(). */
    static void layer(MethodVisitor m) {
        for (String n : List.of("c", "d")) {
            m.visitIntInsn(Opcodes.BIPUSH, 9);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, "f8/Sink", n, "(I)V", false);
        }
        call(m, "f8/Hud", "air", "()I");
    }

    static void sinks(MethodVisitor m, String... names) {
        for (String n : names) {
            m.visitVarInsn(Opcodes.ILOAD, 1);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, "f8/Sink", n, "(I)V", false);
        }
    }

    static void call(MethodVisitor m, String owner, String name, String desc) {
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, name, desc, false);
        if (Type.getReturnType(desc).getSort() != Type.VOID) {
            m.visitInsn(Opcodes.POP);
        }
    }

    static void method(ClassWriter w, String name, String desc, int access, java.util.function.Consumer<MethodVisitor> body) {
        MethodVisitor m = w.visitMethod(access, name, desc, null, null);
        m.visitCode();
        if (body != null) {
            body.accept(m);
        }
        Type ret = Type.getReturnType(desc);
        if (ret.getSort() == Type.VOID) {
            m.visitInsn(Opcodes.RETURN);
        } else {
            m.visitInsn(Opcodes.ICONST_0);
            m.visitInsn(Opcodes.IRETURN);
        }
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** A method's field and method instructions as text ("GETFIELD owner.name"), or null when the class has no such method. */
    static String body(byte[] bytes, String name, String desc) {
        StringBuilder out = new StringBuilder();
        boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                if (!n.equals(name) || !d.equals(desc)) {
                    return null;
                }
                found[0] = true;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String field, String fd) {
                        out.append(opcode == Opcodes.GETFIELD ? "GETFIELD " : "FIELD ").append(owner).append('.').append(field).append(';');
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String md, boolean itf) {
                        out.append("CALL ").append(owner).append('.').append(method).append(md).append(';');
                    }
                };
            }
        }, 0);
        return found[0] ? out.toString() : null;
    }
}

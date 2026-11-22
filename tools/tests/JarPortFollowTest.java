import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * Mixins into game code the update moved, followed to where the game's own code went:
 * - f8.Hud.tick(int) is gone and the new update(int, float) has its code: an @Inject into tick follows to update, its
 *   handler re-declared for the new parameters. f8.Bar.go() is gone with two new methods that read like it, so nothing
 *   says which one took its code and it stays reported.
 * - f8.Hud.render() no longer calls air() itself: it calls the new private renderAir(), which does (1.21.2's
 *   Gui.renderPlayerHealth and renderAirBubbles). An @Inject at that call follows into renderAir. f8.Bar.draw() moved its
 *   call into part(), which another method calls too, so the injector would run more often there and stays reported.
 * - f8.Screen.init(Mc, int, int) becomes init(int, int); the old one stored the Mc in this.mc, which only the new
 *   constructor sets now (1.21.11's Screen.init). A handler that took the Mc reads this.mc. f8.Pane's new init sets its mc
 *   itself, so at the new method's start the field may not hold it yet, and the handler stays reported.
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
        JarPortTest.snap(a, "f8/Hud", 0, null, "tick(I)V", "render()V", "air()I");
        GameDiff.Cls hud = JarPortTest.snap(b, "f8/Hud", 0, null, "update(IF)V", "other()V", "render()V", "air()I");
        hud.methods.put("renderAir()V", new GameDiff.Meth(hud, "renderAir", "()V", Opcodes.ACC_PRIVATE));
        JarPortTest.snap(a, "f8/Bar", 0, null, "go()V", "draw()V", "air()I", "more()V");
        GameDiff.Cls bar = JarPortTest.snap(b, "f8/Bar", 0, null, "go1()V", "go2()V", "draw()V", "air()I", "more()V");
        bar.methods.put("part()V", new GameDiff.Meth(bar, "part", "()V", Opcodes.ACC_PRIVATE));
        for (String pane : List.of("f8/Screen", "f8/Pane")) {
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
        port.oldCode = new JarPort.GameCode(Map.of("f8/Hud", hud(true), "f8/Bar", bar(true), "f8/Screen", pane("f8/Screen", true, false), "f8/Pane", pane("f8/Pane", true, false))::get, null);
        port.newCode = new JarPort.GameCode(Map.of("f8/Hud", hud(false), "f8/Bar", bar(false), "f8/Screen", pane("f8/Screen", false, false), "f8/Pane", pane("f8/Pane", false, true))::get, null);

        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(Map.of(
            "m8/HudMixin.class", mixin("m8/HudMixin", "f8/Hud", "onTick", "(I" + CI + ")V", "tick(I)V", "HEAD", null, "onAir", "render()V", "Lf8/Hud;air()I"),
            "m8/BarMixin.class", mixin("m8/BarMixin", "f8/Bar", "onGo", "(" + CI + ")V", "go()V", "HEAD", null, "onAir", "draw()V", "Lf8/Bar;air()I"),
            "m8/ScreenMixin.class", initMixin("m8/ScreenMixin", "f8/Screen"),
            "m8/PaneMixin.class", initMixin("m8/PaneMixin", "f8/Pane"),
            "m8.mixins.json", "{\"package\": \"m8\", \"mixins\": [\"HudMixin\", \"BarMixin\", \"ScreenMixin\", \"PaneMixin\"], \"injectors\": {\"defaultRequire\": 1}}"
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
        JarPortTest.expect(unlinked.stream().noneMatch(u -> u.contains("in f8.Hud.render")),
            "the injection point that moved into the helper is no break: " + unlinked);
        JarPortTest.expect(unlinked.stream().anyMatch(u -> u.startsWith("mixin injection point gone: f8.Bar.air()I in f8.Bar.draw()V")),
            "a helper something else calls too is not injected into: " + unlinked);

        String init = body(ported.get("m8/ScreenMixin.class"), "onInit", "(II" + CI + ")V");
        JarPortTest.expect(init != null && init.contains("GETFIELD f8/Screen.mc"),
            "a handler that took the argument the game now keeps on the object reads it there: " + init + " " + JarPortTest.members(ported.get("m8/ScreenMixin.class")).keySet());
        JarPortTest.expect(unlinked.stream().noneMatch(u -> u.contains("f8.Screen.init")) && unlinked.stream().anyMatch(u -> u.contains("f8.Pane.init")),
            "the field is read only where the new release sets it before any method runs: " + unlinked);
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
            method(w, "other", "()V", Opcodes.ACC_PUBLIC, m -> call(m, "f8/Hud", "render", "()V"));
        }
        method(w, "air", "()I", Opcodes.ACC_PUBLIC, null);
        method(w, "render", "()V", Opcodes.ACC_PUBLIC, m -> call(m, "f8/Hud", old ? "air" : "renderAir", old ? "()I" : "()V"));
        if (!old) {
            method(w, "renderAir", "()V", Opcodes.ACC_PRIVATE, m -> call(m, "f8/Hud", "air", "()I"));
        }
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

    /** A pane's code: the old init(Mc, int, int) stores its Mc in this.mc; the new constructor takes the Mc, and the new init(int, int) stores it too where again. */
    static byte[] pane(String name, boolean old, boolean again) {
        ClassWriter w = JarPortRetypeTest.writer(name, "java/lang/Object");
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

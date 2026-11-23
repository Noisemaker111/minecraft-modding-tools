import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * Injection points the method still exists for, while the call or field an @At names is no longer in its code. The @At
 * moves only to what the game's new code has in the same place:
 * - p9.Ui.key() wrote inv.selected; the new code calls inv.setSelected(int), which writes it (1.21.5's Inventory). An
 *   @Inject and a @Redirect at the field both move to the call.
 * - p9.Ui.text() called draw(String, int, int, boolean); the new code calls draw(String, int, int), which hands over to
 *   it (1.21.4's GuiGraphics.drawString), and label() calls it too. An @Inject moves; a @Redirect, whose handler takes the
 *   call's arguments, stays reported.
 * - p9.Ui.tick() called air() three times; the new code drops the first. An @Inject at ordinal 2 is now at ordinal 1;
 *   one at ordinal 1 still finds a place, but the second call is the old third: it moves to ordinal 0. One at ordinal 0
 *   lines up with nothing and stays reported (it still finds a place: no break, but no move either).
 * - p9.Ui.quit(Scr) hands over to the new quit(Scr, boolean), which every old caller of quit(Scr) now calls (1.20.5's
 *   Minecraft.disconnect): a cancellable @Inject at its isDown() call moves there, the Scr it took passed on.
 *   p9.Ui.close() calls the new closeAll(), which the new fresh() calls too: the injector would run where it never ran,
 *   so it stays reported.
 * - p9.Ui.blend() stopped calling Gl.blend() with nothing in its place (1.21.2's LoadingOverlay): reported.
 */
final class JarPortPointTest {

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        check(scratch);
        JarPortTest.finish("JarPortPointTest");
    }

    static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    static final String DRAW = "(Ljava/lang/String;IIZ)V", DRAW3 = "(Ljava/lang/String;II)V", SCR = "Lp9/Scr;";

    static void check(Path scratch) throws Exception {
        GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
        for (GameDiff.Snap s : List.of(a, b)) {
            JarPortTest.snap(s, "p9/Scr", 0, null);
            JarPortTest.snap(s, "p9/Sink", 0, null, "a()V", "b()V", "c()V");
            JarPortTest.snap(s, "p9/Gl", 0, null, "blend()V");
        }
        GameDiff.Cls oldUi = JarPortTest.snap(a, "p9/Ui", 0, null, "key()V", "text()V", "draw" + DRAW, "tick()V", "air()V", "quit(" + SCR + ")V",
            "close()V", "isDown()Z", "blend()V", "label()V");
        GameDiff.Cls newUi = JarPortTest.snap(b, "p9/Ui", 0, null, "key()V", "text()V", "draw" + DRAW, "draw" + DRAW3, "tick()V", "air()V",
            "quit(" + SCR + ")V", "quit(" + SCR + "Z)V", "close()V", "closeAll()V", "fresh()V", "isDown()Z", "blend()V", "label()V");
        JarPortTest.snap(a, "p9/Inv", 0, null);
        JarPortTest.snap(b, "p9/Inv", 0, null, "setSelected(I)V");
        for (GameDiff.Snap x : List.of(a, b)) {
            GameDiff.Cls inv = x.classes.get("p9/Inv");
            inv.fields.put("selected", new GameDiff.Fld(inv, "selected", "I", Opcodes.ACC_PUBLIC));
            GameDiff.Cls ui = x.classes.get("p9/Ui");
            ui.fields.put("inv", new GameDiff.Fld(ui, "inv", "Lp9/Inv;", Opcodes.ACC_PUBLIC));
        }
        for (GameDiff.Snap x : List.of(a, b)) {
            JarPortTest.snap(x, "p9/Caller", 0, null, "leave()V");
        }
        Path map = scratch.resolve("route-point.json");
        Files.writeString(map, "{}");
        JarPort port = new JarPort(a, b, map);
        Map<String, byte[]> before = Map.of("p9/Ui", ui(true), "p9/Caller", caller(true), "p9/Inv", inv(true)),
            after = Map.of("p9/Ui", ui(false), "p9/Caller", caller(false), "p9/Inv", inv(false));
        port.oldCode = new JarPort.GameCode(before::get, null, before);
        port.newCode = new JarPort.GameCode(after::get, null, after);

        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(Map.of(
            "m9/UiMixin.class", mixin(),
            "m9.mixins.json", "{\"package\": \"m9\", \"mixins\": [\"UiMixin\"], \"injectors\": {\"defaultRequire\": 1}}".getBytes(java.nio.charset.StandardCharsets.UTF_8))),
            "point.jar", results));
        List<String> unlinked = Json.array(results.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        Map<String, String> s = JarPortFixesTest.strings(ported.get("m9/UiMixin.class"));
        Map<String, Integer> ordinals = ordinals(ported.get("m9/UiMixin.class"));

        JarPortTest.expect(s.getOrDefault("onKey(" + CI + ")V", "").equals("key()V INVOKE Lp9/Inv;setSelected(I)V")
                && s.getOrDefault("redirectKey(Lp9/Inv;I)V", "").equals("key()V INVOKE Lp9/Inv;setSelected(I)V"),
            "an @At at a field the new code writes through its setter moves to the setter, for any injector: " + s);
        JarPortTest.expect(s.getOrDefault("onText(" + CI + ")V", "").equals("text()V INVOKE Lp9/Ui;draw" + DRAW3),
            "an @Inject at a call the new code makes through another signature of it moves there: " + s);
        JarPortTest.expect(unlinked.stream().anyMatch(u -> u.startsWith("mixin injection point gone: p9.Ui.draw" + DRAW + " in p9.Ui.text()V")),
            "a @Redirect, which takes the call's arguments, does not follow another signature: " + unlinked);
        JarPortTest.expect(ordinals.get("onThirdAir(" + CI + ")V") == 1 && ordinals.get("onSecondAir(" + CI + ")V") == 0
                && unlinked.stream().noneMatch(u -> u.contains("in p9.Ui.tick")),
            "an ordinal moves to the call the old one lines up with, whether or not it still found a place: " + ordinals + " " + unlinked);
        JarPortTest.expect(ordinals.get("onFirstAir(" + CI + ")V") == 0,
            "an ordinal whose call is gone, while another one still takes its number, is left as it was: " + ordinals);
        JarPortTest.expect(s.getOrDefault("onQuit(" + SCR + "Z" + CI + ")V", "").equals("quit(" + SCR + "Z)V INVOKE Lp9/Ui;isDown()Z")
                && unlinked.stream().noneMatch(u -> u.contains("in p9.Ui.quit")),
            "a cancellable @Inject follows into the method its own now only hands over to, which every old caller now calls: " + s + " " + unlinked);
        JarPortTest.expect(unlinked.stream().anyMatch(u -> u.startsWith("mixin injection point gone: p9.Ui.isDown()Z in p9.Ui.close()V")),
            "not into a method a new way also reaches: " + unlinked);
        JarPortTest.expect(unlinked.stream().anyMatch(u -> u.startsWith("mixin injection point gone: p9.Gl.blend()V in p9.Ui.blend()V")),
            "a call the method dropped with nothing in its place stays reported: " + unlinked);

        // What the scoreboard says the game did, from the same reading.
        JarPort.GameCode then = port.oldCode, now = port.newCode;
        String[] ui = { "p9/Ui" };
        JarPortTest.expect(JarPort.GameCode.slot(then, now, x -> x, new String[] { ui[0], "blend", "()V" }, new String[] { ui[0], "blend", "()V" }, 'm', "p9/Gl", "blend", "()V", -1).game().equals("dropped")
                && JarPort.GameCode.slot(then, now, x -> x, new String[] { ui[0], "close", "()V" }, new String[] { ui[0], "close", "()V" }, 'm', "p9/Ui", "isDown", "()Z", -1).game().equals("helper")
                && JarPort.GameCode.slot(then, now, x -> x, new String[] { ui[0], "text", "()V" }, new String[] { ui[0], "text", "()V" }, 'm', "p9/Ui", "draw", DRAW, -1).game().equals("overload"),
            "the reading names what the game did: dropped, moved into a method, another signature");
    }

    /** Each handler's @At ordinal, where it gives one. */
    static Map<String, Integer> ordinals(byte[] bytes) {
        Map<String, Integer> out = new TreeMap<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                        return find(name + desc);
                    }
                };
            }

            AnnotationVisitor find(String key) {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String n, Object v) {
                        if ("ordinal".equals(n) && v instanceof Integer i) {
                            out.put(key, i);
                        }
                    }

                    @Override
                    public AnnotationVisitor visitArray(String n) {
                        return find(key);
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String n, String d) {
                        return find(key);
                    }
                };
            }
        }, 0);
        return out;
    }

    /** m9.UiMixin: every case's handlers into p9.Ui. */
    static byte[] mixin() {
        ClassWriter w = JarPortRetypeTest.writer("m9/UiMixin", "java/lang/Object");
        AnnotationVisitor t = w.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor value = t.visitArray("value");
        value.visit(null, Type.getObjectType("p9/Ui"));
        value.visitEnd();
        t.visitEnd();
        String redirect = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
        handler(w, JarPortFixesTest.INJECT, "onKey", "(" + CI + ")V", "key()V", "FIELD", "Lp9/Inv;selected:I", -1, false);
        handler(w, redirect, "redirectKey", "(Lp9/Inv;I)V", "key()V", "FIELD", "Lp9/Inv;selected:I", -1, false);
        handler(w, JarPortFixesTest.INJECT, "onText", "(" + CI + ")V", "text()V", "INVOKE", "Lp9/Ui;draw" + DRAW, -1, false);
        handler(w, redirect, "redirectText", "(Lp9/Ui;Ljava/lang/String;IIZ)V", "text()V", "INVOKE", "Lp9/Ui;draw" + DRAW, -1, false);
        handler(w, JarPortFixesTest.INJECT, "onFirstAir", "(" + CI + ")V", "tick()V", "INVOKE", "Lp9/Ui;air()V", 0, false);
        handler(w, JarPortFixesTest.INJECT, "onSecondAir", "(" + CI + ")V", "tick()V", "INVOKE", "Lp9/Ui;air()V", 1, false);
        handler(w, JarPortFixesTest.INJECT, "onThirdAir", "(" + CI + ")V", "tick()V", "INVOKE", "Lp9/Ui;air()V", 2, false);
        handler(w, JarPortFixesTest.INJECT, "onQuit", "(" + SCR + CI + ")V", "quit(" + SCR + ")V", "INVOKE", "Lp9/Ui;isDown()Z", -1, true);
        handler(w, JarPortFixesTest.INJECT, "onClose", "(" + CI + ")V", "close()V", "INVOKE", "Lp9/Ui;isDown()Z", -1, false);
        handler(w, JarPortFixesTest.INJECT, "onBlend", "(" + CI + ")V", "blend()V", "INVOKE", "Lp9/Gl;blend()V", -1, false);
        w.visitEnd();
        return w.toByteArray();
    }

    static void handler(ClassWriter w, String kind, String name, String desc, String method, String at, String target, int ordinal, boolean cancellable) {
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PRIVATE, name, desc, null, null);
        AnnotationVisitor av = m.visitAnnotation(kind, false);
        AnnotationVisitor methods = av.visitArray("method");
        methods.visit(null, method);
        methods.visitEnd();
        AnnotationVisitor ats = av.visitArray("at");
        AnnotationVisitor point = ats.visitAnnotation(null, JarPort.AT);
        point.visit("value", at);
        point.visit("target", target);
        if (ordinal >= 0) {
            point.visit("ordinal", ordinal);
        }
        point.visitEnd();
        ats.visitEnd();
        if (cancellable) {
            av.visit("cancellable", true);
        }
        av.visitEnd();
        m.visitCode();
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** p9.Ui's code in one release (see the class comment). */
    static byte[] ui(boolean old) {
        ClassWriter w = JarPortRetypeTest.writer("p9/Ui", "java/lang/Object");
        w.visitField(Opcodes.ACC_PUBLIC, "inv", "Lp9/Inv;", null, null).visitEnd();
        JarPortFollowTest.method(w, "key", "()V", Opcodes.ACC_PUBLIC, m -> {
            sink(m, "a");
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitFieldInsn(Opcodes.GETFIELD, "p9/Ui", "inv", "Lp9/Inv;");
            m.visitInsn(Opcodes.ICONST_3);
            if (old) {
                m.visitFieldInsn(Opcodes.PUTFIELD, "p9/Inv", "selected", "I");
            } else {
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "p9/Inv", "setSelected", "(I)V", false);
            }
            sink(m, "b");
        });
        JarPortFollowTest.method(w, "text", "()V", Opcodes.ACC_PUBLIC, m -> {
            sink(m, "a");
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitLdcInsn("hi");
            m.visitInsn(Opcodes.ICONST_1);
            m.visitInsn(Opcodes.ICONST_2);
            if (old) {
                m.visitInsn(Opcodes.ICONST_0);
            }
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "p9/Ui", "draw", old ? DRAW : DRAW3, false);
            sink(m, "b");
        });
        JarPortFollowTest.method(w, "draw", DRAW, Opcodes.ACC_PUBLIC, null);
        JarPortFollowTest.method(w, "label", "()V", Opcodes.ACC_PUBLIC, m -> {
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitLdcInsn("label");
            m.visitInsn(Opcodes.ICONST_5);
            m.visitInsn(Opcodes.ICONST_5);
            if (old) {
                m.visitInsn(Opcodes.ICONST_0);
            }
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "p9/Ui", "draw", old ? DRAW : DRAW3, false);
        });
        if (!old) {
            JarPortFollowTest.method(w, "draw", DRAW3, Opcodes.ACC_PUBLIC, m -> {
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitVarInsn(Opcodes.ALOAD, 1);
                m.visitVarInsn(Opcodes.ILOAD, 2);
                m.visitVarInsn(Opcodes.ILOAD, 3);
                m.visitInsn(Opcodes.ICONST_1);
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "p9/Ui", "draw", DRAW, false);
            });
        }
        JarPortFollowTest.method(w, "tick", "()V", Opcodes.ACC_PUBLIC, m -> {
            sink(m, "a");
            if (old) {
                JarPortFollowTest.call(m, "p9/Ui", "air", "()V");
            }
            sink(m, "b");
            JarPortFollowTest.call(m, "p9/Ui", "air", "()V");
            sink(m, "c");
            JarPortFollowTest.call(m, "p9/Ui", "air", "()V");
        });
        JarPortFollowTest.method(w, "air", "()V", Opcodes.ACC_PUBLIC, null);
        JarPortFollowTest.method(w, "isDown", "()Z", Opcodes.ACC_PUBLIC, null);
        // quit(Scr): the old one does the work; the new one hands over to quit(Scr, boolean), which does.
        JarPortFollowTest.method(w, "quit", "(" + SCR + ")V", Opcodes.ACC_PUBLIC, m -> {
            if (old) {
                down(m);
            } else {
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitVarInsn(Opcodes.ALOAD, 1);
                m.visitInsn(Opcodes.ICONST_0);
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "p9/Ui", "quit", "(" + SCR + "Z)V", false);
            }
        });
        JarPortFollowTest.method(w, "close", "()V", Opcodes.ACC_PUBLIC, m -> {
            if (old) {
                closing(m);
            } else {
                JarPortFollowTest.call(m, "p9/Ui", "closeAll", "()V");
            }
        });
        if (!old) {
            JarPortFollowTest.method(w, "quit", "(" + SCR + "Z)V", Opcodes.ACC_PUBLIC, JarPortPointTest::down);
            JarPortFollowTest.method(w, "closeAll", "()V", Opcodes.ACC_PUBLIC, JarPortPointTest::closing);
            JarPortFollowTest.method(w, "fresh", "()V", Opcodes.ACC_PUBLIC, m -> JarPortFollowTest.call(m, "p9/Ui", "closeAll", "()V"));
        }
        JarPortFollowTest.method(w, "blend", "()V", Opcodes.ACC_PUBLIC, m -> {
            sink(m, "a");
            if (old) {
                m.visitMethodInsn(Opcodes.INVOKESTATIC, "p9/Gl", "blend", "()V", false);
            }
            sink(m, "b");
        });
        w.visitEnd();
        return w.toByteArray();
    }

    /** p9.Caller: leave() quits a screen, through quit(Scr) in the old release and quit(Scr, true) in the new one. */
    static byte[] caller(boolean old) {
        ClassWriter w = JarPortRetypeTest.writer("p9/Caller", "java/lang/Object");
        JarPortFollowTest.method(w, "leave", "()V", Opcodes.ACC_PUBLIC, m -> {
            m.visitTypeInsn(Opcodes.NEW, "p9/Ui");
            m.visitInsn(Opcodes.DUP);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "p9/Ui", "<init>", "()V", false);
            m.visitInsn(Opcodes.ACONST_NULL);
            if (!old) {
                m.visitInsn(Opcodes.ICONST_1);
            }
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "p9/Ui", "quit", old ? "(" + SCR + ")V" : "(" + SCR + "Z)V", false);
        });
        w.visitEnd();
        return w.toByteArray();
    }

    /** p9.Inv: a field selected, written through setSelected(int) in the new release. */
    static byte[] inv(boolean old) {
        ClassWriter w = JarPortRetypeTest.writer("p9/Inv", "java/lang/Object");
        w.visitField(Opcodes.ACC_PUBLIC, "selected", "I", null, null).visitEnd();
        if (!old) {
            JarPortFollowTest.method(w, "setSelected", "(I)V", Opcodes.ACC_PUBLIC, m -> {
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitVarInsn(Opcodes.ILOAD, 1);
                m.visitFieldInsn(Opcodes.PUTFIELD, "p9/Inv", "selected", "I");
            });
        }
        w.visitEnd();
        return w.toByteArray();
    }

    /** Sink.c, isDown(), Sink.c: closing code, unlike quitting's. */
    static void closing(MethodVisitor m) {
        sink(m, "c");
        JarPortFollowTest.call(m, "p9/Ui", "isDown", "()Z");
        sink(m, "c");
    }

    /** Sink.a, isDown(), Sink.b. */
    static void down(MethodVisitor m) {
        sink(m, "a");
        JarPortFollowTest.call(m, "p9/Ui", "isDown", "()Z");
        sink(m, "b");
    }

    static void sink(MethodVisitor m, String n) {
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "p9/Sink", n, "()V", false);
    }
}

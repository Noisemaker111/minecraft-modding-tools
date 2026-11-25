import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * Mixin handlers whose target's float became a tracker that can be read two ways, as 1.21's partial tick became a
 * DeltaTracker read as getGameTimeDeltaPartialTick(true) in some places and (false) in others. Release 2.0 of the game
 * g9 reads Tick.partial(false) where Hud.bar used its float, and Main.frame reads partial(true) where it used its own;
 * World.render never used its float as it was, but Main.frame passed it its own. So a handler of Hud.render (which
 * passes its float on to bar) gets partial(false), one of World.render gets partial(true), and one of Both.render, which
 * drew its float (now read as false) and lerped by it (now read as true), stays reported, though its lerp alone would say
 * true. A @Redirect of the bar call gets partial(false) too. Each ported handler is
 * run on this JVM with a real 2.0 Tick (partial(true) = 0.25, partial(false) = 0.75) and must see the right one.
 * Hud.render also draws its float, which 2.0 reads as tick.paused(): the game's own paused() is partial(false), so
 * the two places agree.
 * Level.draw(float, long, boolean) became draw(Tick, boolean): a @ModifyVariable of its boolean, found by type, follows;
 * one of its float, which the update took from the parameters, stays reported. Glyph.draw(r, g, b, a) became draw(color),
 * and Font, which worked the four out of its colour, now passes the colour: a handler of draw gets them out of it the same
 * way.
 */
final class JarPortTickTest {

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        check(scratch);
        roomForConstants();
        JarPortTest.finish("JarPortTickTest");
    }

    /**
     * A conversion that calls a static helper with the value and five constants (a long among them) needs that much
     * operand stack above what an adapter already holds; the JVM's verifier rejects the handler when the room is short.
     */
    static void roomForConstants() throws Exception {
        String[] via = { "k:I:1", "k:J:2", "k:I:3", "k:I:4", "k:F:5" };
        for (int room : new int[] { Conversions.room(new String[][] { via }), 3 }) {
            ClassWriter w = new ClassWriter(0);
            w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "m9/Room" + room, null, "java/lang/Object", null);
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(I)I", null, null);
            m.visitCode();
            m.visitVarInsn(Opcodes.ILOAD, 0);
            m.visitLdcInsn(1);
            m.visitLdcInsn(2L);
            m.visitLdcInsn(3);
            m.visitLdcInsn(4);
            m.visitLdcInsn(5f);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, "m9/Room" + room, "helper", "(IIJIIF)I", false);
            m.visitInsn(Opcodes.IRETURN);
            m.visitMaxs(1 + room, 1);
            m.visitEnd();
            MethodVisitor h = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "helper", "(IIJIIF)I", null, null);
            h.visitCode();
            h.visitVarInsn(Opcodes.ILOAD, 0);
            h.visitInsn(Opcodes.IRETURN);
            h.visitMaxs(1, 7);
            h.visitEnd();
            w.visitEnd();
            byte[] bytes = w.toByteArray();
            String name = "m9.Room" + room;
            ClassLoader loader = new ClassLoader(null) {
                @Override
                protected Class<?> findClass(String n) throws ClassNotFoundException {
                    return n.equals(name) ? defineClass(n, bytes, 0, bytes.length) : Class.forName(n, false, ClassLoader.getPlatformClassLoader());
                }
            };
            boolean verified;
            try {
                verified = (int) loader.loadClass(name).getMethod("run", int.class).invoke(null, 7) == 7;
            } catch (VerifyError | ReflectiveOperationException rejected) {
                verified = false;
            }
            JarPortTest.expect(verified == (room != 3), room == 3
                ? "the old fixed room of 3 is too little for five constants, so the verifier rejects it (the check can fail)"
                : "Conversions.room leaves the operand stack its constants need, and the verifier accepts the handler: " + room);
        }
    }

    static final String GFX = "Lg9/Gfx;", TICK = "Lg9/Tick;", HUD = "Lg9/Hud;";
    static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;", REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";

    static void check(Path scratch) throws Exception {
        GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
        b.loaderVersion = "2.0.1";
        for (GameDiff.Snap s : List.of(a, b)) {
            boolean old = s == a;
            String f = old ? "F" : TICK;
            JarPortTest.snap(s, "g9/Gfx", 0, null, "draw(F)V");
            JarPortTest.snap(s, "g9/Hud", 0, null, "render(" + GFX + f + ")V", "bar(" + GFX + f + ")V");
            JarPortTest.snap(s, "g9/World", 0, null, "render(" + f + ")V");
            JarPortTest.snap(s, "g9/Main", 0, null, "frame(" + f + ")V", "lerp(F)V");
            JarPortTest.snap(s, "g9/Both", 0, null, "render(" + GFX + f + ")V");
            JarPortTest.snap(s, "g9/Panel", 0, null, "paint()V");
            JarPortTest.snap(s, "g9/Level", 0, null, "draw(" + (old ? "FJZ" : TICK + "Z") + ")V", "fill(" + (old ? "FJZ" : TICK + "Z") + ")V");
            JarPortTest.snap(s, "g9/Glyph", 0, null, "draw(" + (old ? "FFFF" : "I") + ")V");
            JarPortTest.snap(s, "g9/Font", 0, null, "text(I)V");
            GameDiff.Cls argb = JarPortTest.snap(s, "g9/Argb", 0, null);
            argb.methods.put("red(I)I", new GameDiff.Meth(argb, "red", "(I)I", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
        }
        JarPortTest.snap(b, "g9/Tick", 0, null, "partial(Z)F", "paused()F");
        Path map = scratch.resolve("route-follow.json");
        Files.writeString(map, """
            {"signatures": [{"owner": "g9.Hud", "from": "render", "desc": "(Lg9/Gfx;F)V", "to": "render", "newDesc": "(Lg9/Gfx;Lg9/Tick;)V"},
                            {"owner": "g9.Hud", "from": "bar", "desc": "(Lg9/Gfx;F)V", "to": "bar", "newDesc": "(Lg9/Gfx;Lg9/Tick;)V"},
                            {"owner": "g9.World", "from": "render", "desc": "(F)V", "to": "render", "newDesc": "(Lg9/Tick;)V"},
                            {"owner": "g9.Main", "from": "frame", "desc": "(F)V", "to": "frame", "newDesc": "(Lg9/Tick;)V"},
                            {"owner": "g9.Both", "from": "render", "desc": "(Lg9/Gfx;F)V", "to": "render", "newDesc": "(Lg9/Gfx;Lg9/Tick;)V"},
                            {"owner": "g9.Level", "from": "draw", "desc": "(FJZ)V", "to": "draw", "newDesc": "(Lg9/Tick;Z)V"},
                            {"owner": "g9.Level", "from": "fill", "desc": "(FJZ)V", "to": "fill", "newDesc": "(Lg9/Tick;Z)V"},
                            {"owner": "g9.Glyph", "from": "draw", "desc": "(FFFF)V", "to": "draw", "newDesc": "(I)V"}]}
            """);
        JarPort port = new JarPort(a, b, map);
        Map<String, byte[]> before = game(true), after = game(false);
        port.oldCode = new GameCode(before::get, null, before);
        port.newCode = new GameCode(after::get, null, after);

        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(Map.ofEntries(Map.entry("m9/Seen.class", seen()),
            Map.entry("m9/HudMixin.class", injector("m9/HudMixin", "g9/Hud", "render(" + GFX + "F)V", "(" + GFX + "F" + CI + ")V", 2)),
            Map.entry("m9/WorldMixin.class", injector("m9/WorldMixin", "g9/World", "render(F)V", "(F" + CI + ")V", 1)),
            Map.entry("m9/BothMixin.class", injector("m9/BothMixin", "g9/Both", "render(" + GFX + "F)V", "(" + GFX + "F" + CI + ")V", 2)),
            Map.entry("m9/PanelMixin.class", redirect()),
            Map.entry("m9/GlyphMixin.class", glyphs()),
            Map.entry("m9/FlagMixin.class", variable("m9/FlagMixin", "(Z)Z", Opcodes.ILOAD)), Map.entry("m9/JitterMixin.class", variable("m9/JitterMixin", "(F)F", Opcodes.FLOAD)),
            Map.entry("m9/LocalFlagMixin.class", variable("m9/LocalFlagMixin", "(Z)Z", Opcodes.ILOAD, "RETURN", false, "draw")),
            Map.entry("m9/LocalFillMixin.class", variable("m9/LocalFillMixin", "(Z)Z", Opcodes.ILOAD, "RETURN", false, "fill")),
            Map.entry("m9.mixins.json", "{\"package\": \"m9\", \"mixins\": [\"HudMixin\", \"WorldMixin\", \"BothMixin\", \"PanelMixin\", \"FlagMixin\", \"LocalFlagMixin\", \"LocalFillMixin\", \"JitterMixin\", \"GlyphMixin\"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8)))),
            "follow.jar", results));
        List<String> unlinked = Json.array(results.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(unlinked.equals(List.of("mixin target changed signature: g9.Both.render(Lg9/Gfx;F)V", "mixin target changed signature: g9.Level.draw(FJZ)V")),
            "handlers of Hud.render and World.render, and the redirect of bar, follow; Both.render's float reached two readings and stays reported: " + unlinked);
        // Level.draw(float, long, boolean) became draw(Tick, boolean): its boolean is at another slot but still the one boolean.
        String flag = JarPortFixesTest.strings(ported.get("m9/FlagMixin.class")).getOrDefault("flag(Z)Z", "");
        String jitter = JarPortFixesTest.strings(ported.get("m9/JitterMixin.class")).getOrDefault("flag(F)F", "");
        JarPortTest.expect(flag.contains("draw(Lg9/Tick;Z)V") && jitter.contains("draw(FJZ)V"),
            "a @ModifyVariable of the boolean, found by its type, follows the new signature; one of the float the update took away stays: " + flag + " | " + jitter);
        // Without argsOnly, at RETURN, Mixin also counts the live locals of its type: 2.0's draw holds one more boolean
        // there, so the one boolean is not the same variable; fill holds none, so it is (Cardinal Components' BlockEntity
        // in ChunkHolder.broadcastBlockEntity, which 1.20 gave a first parameter).
        String local = JarPortFixesTest.strings(ported.get("m9/LocalFlagMixin.class")).getOrDefault("flag(Z)Z", "");
        JarPortTest.expect(local.contains("draw(FJZ)V") && !local.contains("draw(Lg9/Tick;Z)V"),
            "a @ModifyVariable by type that the new body's own local of that type would also match stays reported when the parameters' slots move: " + local);
        String fill = JarPortFixesTest.strings(ported.get("m9/LocalFillMixin.class")).getOrDefault("flag(Z)Z", "");
        JarPortTest.expect(fill.contains("fill(Lg9/Tick;Z)V"),
            "a @ModifyVariable by type whose variable is still the one of its type follows the new signature though the slots moved: " + fill);

        Map<String, byte[]> classes = new HashMap<>(after);
        classes.put("g9/Tick", tick());
        classes.put("org/spongepowered/asm/mixin/injection/callback/CallbackInfo", JarPortTest.real("org/spongepowered/asm/mixin/injection/callback/CallbackInfo", "java/lang/Object", 0, new String[0], w -> { }));
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
        String seen;
        try {
            Class<?> gfx = loader.loadClass("g9.Gfx"), tick = loader.loadClass("g9.Tick"), ci = loader.loadClass("org.spongepowered.asm.mixin.injection.callback.CallbackInfo");
            Object g = gfx.getConstructor().newInstance(), t = tick.getConstructor().newInstance(), info = ci.getConstructor().newInstance();
            call(loader, "m9.HudMixin", "on", new Class<?>[] { gfx, tick, ci }, g, t, info);
            call(loader, "m9.WorldMixin", "on", new Class<?>[] { tick, ci }, t, info);
            call(loader, "m9.PanelMixin", "onBar", new Class<?>[] { loader.loadClass("g9.Hud"), gfx, tick }, loader.loadClass("g9.Hud").getConstructor().newInstance(), g, t);
            call(loader, "m9.GlyphMixin", "on", new Class<?>[] { int.class, ci }, 0x80FF4000, info);
            seen = String.valueOf(loader.loadClass("m9.Seen").getField("out").get(null));
        } catch (ReflectiveOperationException | LinkageError failed) {
            seen = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
        JarPortTest.expect(seen.equals("0.75 0.25 0.75 1.0 0.2509804 0.0 0.5019608 "),
            "the HUD's handler and the redirect get partial(false), the world's handler partial(true), as the game read each float; "
                + "the glyph's handler gets red, green, blue and alpha out of the colour as Font worked them out: " + seen);
    }

    static void call(ClassLoader loader, String owner, String name, Class<?>[] params, Object... args) throws ReflectiveOperationException {
        Class<?> c = loader.loadClass(owner);
        var m = c.getDeclaredMethod(name, params);
        m.setAccessible(true);
        m.invoke(c.getConstructor().newInstance(), args);
    }

    /** g9's code in one release (old: 1.0, with floats; new: 2.0, with a Tick). */
    static Map<String, byte[]> game(boolean old) {
        String f = old ? "F" : TICK;
        Map<String, byte[]> out = new HashMap<>();
        out.put("g9/Level", JarPortTest.real("g9/Level", "java/lang/Object", 0, new String[0], w -> {
            // 2.0's draw keeps a boolean of its own until it returns, in its local variable table as javac writes one.
            if (old) {
                body(w, "draw", "(FJZ)V", m -> { });
            } else {
                MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "draw", "(" + TICK + "Z)V", null, null);
                Label start = new Label();
                Label end = new Label();
                m.visitCode();
                m.visitInsn(Opcodes.ICONST_0);
                m.visitVarInsn(Opcodes.ISTORE, 3);
                m.visitLabel(start);
                m.visitInsn(Opcodes.RETURN);
                m.visitLabel(end);
                m.visitLocalVariable("extra", "Z", null, start, end, 3);
                m.visitMaxs(0, 0);
                m.visitEnd();
            }
            body(w, "fill", "(" + (old ? "FJZ" : TICK + "Z") + ")V", m -> { });
        }));
        if (!old) {
            out.put("g9/Tick", tick());
        }
        out.put("g9/Gfx", JarPortTest.real("g9/Gfx", "java/lang/Object", 0, new String[0], w -> body(w, "draw", "(F)V", m -> { })));
        out.put("g9/Hud", JarPortTest.real("g9/Hud", "java/lang/Object", 0, new String[0], w -> {
            // render passes its value on to bar as it is, and draws it: 2.0 draws tick.paused(), which is partial(false).
            body(w, "render", "(" + GFX + f + ")V", m -> {
                m.visitVarInsn(Opcodes.ALOAD, 1);
                if (old) {
                    m.visitVarInsn(Opcodes.FLOAD, 2);
                } else {
                    m.visitVarInsn(Opcodes.ALOAD, 2);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g9/Tick", "paused", "()F", false);
                }
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g9/Gfx", "draw", "(F)V", false);
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitVarInsn(Opcodes.ALOAD, 1);
                m.visitVarInsn(old ? Opcodes.FLOAD : Opcodes.ALOAD, 2);
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g9/Hud", "bar", "(" + GFX + f + ")V", false);
            });
            // bar draws it: 2.0 draws tick.partial(false) there.
            body(w, "bar", "(" + GFX + f + ")V", m -> {
                m.visitVarInsn(Opcodes.ALOAD, 1);
                partial(m, old, 2, false);
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g9/Gfx", "draw", "(F)V", false);
            });
        }));
        // World.render never uses its value as it is.
        out.put("g9/World", JarPortTest.real("g9/World", "java/lang/Object", 0, new String[0], w -> body(w, "render", "(" + f + ")V", m -> { })));
        out.put("g9/Main", JarPortTest.real("g9/Main", "java/lang/Object", 0, new String[0], w -> {
            // frame passes its value to World.render and lerps by it: 2.0 lerps by tick.partial(true).
            body(w, "frame", "(" + f + ")V", m -> {
                m.visitTypeInsn(Opcodes.NEW, "g9/World");
                m.visitInsn(Opcodes.DUP);
                m.visitMethodInsn(Opcodes.INVOKESPECIAL, "g9/World", "<init>", "()V", false);
                m.visitVarInsn(old ? Opcodes.FLOAD : Opcodes.ALOAD, 1);
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g9/World", "render", "(" + f + ")V", false);
                m.visitVarInsn(Opcodes.ALOAD, 0);
                partial(m, old, 1, true);
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g9/Main", "lerp", "(F)V", false);
            });
            body(w, "lerp", "(F)V", m -> { });
        }));
        // Both.render draws its value (read as false in 2.0) and lerps by it (read as true).
        out.put("g9/Both", JarPortTest.real("g9/Both", "java/lang/Object", 0, new String[0], w -> body(w, "render", "(" + GFX + f + ")V", m -> {
            m.visitVarInsn(Opcodes.ALOAD, 1);
            partial(m, old, 2, false);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g9/Gfx", "draw", "(F)V", false);
            m.visitTypeInsn(Opcodes.NEW, "g9/Main");
            m.visitInsn(Opcodes.DUP);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "g9/Main", "<init>", "()V", false);
            partial(m, old, 2, true);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g9/Main", "lerp", "(F)V", false);
        })));
        // Panel.paint, the same in both, calls bar with a value it makes: 0.5 in 1.0, its own tick in 2.0.
        out.put("g9/Panel", JarPortTest.real("g9/Panel", "java/lang/Object", 0, new String[0], w -> body(w, "paint", "()V", m -> {
            m.visitTypeInsn(Opcodes.NEW, "g9/Hud");
            m.visitInsn(Opcodes.DUP);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "g9/Hud", "<init>", "()V", false);
            m.visitInsn(Opcodes.ACONST_NULL);
            if (old) {
                m.visitLdcInsn(0.5f);
            } else {
                m.visitInsn(Opcodes.ACONST_NULL);
            }
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g9/Hud", "bar", "(" + GFX + f + ")V", false);
        })));
        glyphGame(out, old);
        return out;
    }

    /**
     * Glyph.draw(r, g, b, a) became draw(color); Font.text(color) worked the four floats out of its colour (red through
     * Argb.red, the rest by shifts and masks, each over 255) and now passes the colour as it is.
     */
    static void glyphGame(Map<String, byte[]> out, boolean old) {
        out.put("g9/Argb", JarPortTest.real("g9/Argb", "java/lang/Object", 0, new String[0], w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "red", "(I)I", null, null);
            m.visitCode();
            m.visitVarInsn(Opcodes.ILOAD, 0);
            m.visitIntInsn(Opcodes.BIPUSH, 16);
            m.visitInsn(Opcodes.ISHR);
            m.visitIntInsn(Opcodes.SIPUSH, 255);
            m.visitInsn(Opcodes.IAND);
            m.visitInsn(Opcodes.IRETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        out.put("g9/Glyph", JarPortTest.real("g9/Glyph", "java/lang/Object", 0, new String[0], w -> body(w, "draw", old ? "(FFFF)V" : "(I)V", m -> { })));
        out.put("g9/Font", JarPortTest.real("g9/Font", "java/lang/Object", 0, new String[0], w -> body(w, "text", "(I)V", m -> {
            m.visitTypeInsn(Opcodes.NEW, "g9/Glyph");
            m.visitInsn(Opcodes.DUP);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "g9/Glyph", "<init>", "()V", false);
            m.visitVarInsn(Opcodes.ILOAD, 1);
            if (old) {
                m.visitMethodInsn(Opcodes.INVOKESTATIC, "g9/Argb", "red", "(I)I", false);
                m.visitInsn(Opcodes.I2F);
                m.visitLdcInsn(255.0f);
                m.visitInsn(Opcodes.FDIV);
                channel(m, 8, false);
                channel(m, 0, false);
                channel(m, 24, true);
            }
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g9/Glyph", "draw", old ? "(FFFF)V" : "(I)V", false);
        })));
    }

    /** One channel of the colour in slot 1 as a float over 255: shifted (unsigned for alpha) and masked. */
    static void channel(MethodVisitor m, int shift, boolean alpha) {
        m.visitVarInsn(Opcodes.ILOAD, 1);
        if (shift > 0) {
            m.visitIntInsn(Opcodes.BIPUSH, shift);
            m.visitInsn(alpha ? Opcodes.IUSHR : Opcodes.ISHR);
        }
        if (!alpha) {
            m.visitIntInsn(Opcodes.SIPUSH, 255);
            m.visitInsn(Opcodes.IAND);
        }
        m.visitInsn(Opcodes.I2F);
        m.visitLdcInsn(255.0f);
        m.visitInsn(Opcodes.FDIV);
    }

    /** m9.GlyphMixin: an @Inject at the head of Glyph.draw(r, g, b, a) that hands each of the four to Seen.saw. */
    static byte[] glyphs() {
        ClassWriter w = JarPortRetypeTest.writer("m9/GlyphMixin", "java/lang/Object");
        mixinOf(w, "g9/Glyph");
        JarPortFixesTest.handler(w, INJECT, "on", "(FFFF" + CI + ")V", "draw(FFFF)V", "HEAD", null, m -> {
            for (int slot = 1; slot <= 4; slot++) {
                m.visitVarInsn(Opcodes.FLOAD, slot);
                m.visitMethodInsn(Opcodes.INVOKESTATIC, "m9/Seen", "saw", "(F)V", false);
            }
        });
        w.visitEnd();
        return w.toByteArray();
    }

    /** The value a method uses: its float parameter in 1.0, its tick's partial(runsNormally) in 2.0. */
    static void partial(MethodVisitor m, boolean old, int slot, boolean runsNormally) {
        if (old) {
            m.visitVarInsn(Opcodes.FLOAD, slot);
            return;
        }
        m.visitVarInsn(Opcodes.ALOAD, slot);
        m.visitInsn(runsNormally ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g9/Tick", "partial", "(Z)F", false);
    }

    static void body(ClassWriter w, String name, String desc, java.util.function.Consumer<MethodVisitor> code) {
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
        m.visitCode();
        code.accept(m);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** 2.0's Tick: partial(true) is 0.25, partial(false) 0.75, and paused() hands back partial(false). */
    static byte[] tick() {
        return JarPortTest.real("g9/Tick", "java/lang/Object", 0, new String[0], w -> {
            MethodVisitor p = w.visitMethod(Opcodes.ACC_PUBLIC, "paused", "()F", null, null);
            p.visitCode();
            p.visitVarInsn(Opcodes.ALOAD, 0);
            p.visitInsn(Opcodes.ICONST_0);
            p.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g9/Tick", "partial", "(Z)F", false);
            p.visitInsn(Opcodes.FRETURN);
            p.visitMaxs(0, 0);
            p.visitEnd();
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "partial", "(Z)F", null, null);
            m.visitCode();
            Label no = new Label();
            m.visitVarInsn(Opcodes.ILOAD, 1);
            m.visitJumpInsn(Opcodes.IFEQ, no);
            m.visitLdcInsn(0.25f);
            m.visitInsn(Opcodes.FRETURN);
            m.visitLabel(no);
            m.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
            m.visitLdcInsn(0.75f);
            m.visitInsn(Opcodes.FRETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        });
    }

    /** m9.Seen.saw(f) appends f and a space to Seen.out. */
    static byte[] seen() {
        return JarPortTest.real("m9/Seen", "java/lang/Object", 0, new String[0], w -> {
            w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "out", "Ljava/lang/StringBuilder;", null, null).visitEnd();
            MethodVisitor init = w.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            init.visitCode();
            init.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
            init.visitInsn(Opcodes.DUP);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
            init.visitFieldInsn(Opcodes.PUTSTATIC, "m9/Seen", "out", "Ljava/lang/StringBuilder;");
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "saw", "(F)V", null, null);
            m.visitCode();
            m.visitFieldInsn(Opcodes.GETSTATIC, "m9/Seen", "out", "Ljava/lang/StringBuilder;");
            m.visitVarInsn(Opcodes.FLOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(F)Ljava/lang/StringBuilder;", false);
            m.visitLdcInsn(" ");
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            m.visitInsn(Opcodes.POP);
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        });
    }

    /** A mixin with an @Inject at HEAD of method, whose handler on(... float ..., CallbackInfo) hands the float at slot to Seen.saw. */
    static byte[] injector(String name, String target, String method, String desc, int slot) {
        ClassWriter w = JarPortRetypeTest.writer(name, "java/lang/Object");
        mixinOf(w, target);
        JarPortFixesTest.handler(w, INJECT, "on", desc, method, "HEAD", null, m -> {
            m.visitVarInsn(Opcodes.FLOAD, slot);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, "m9/Seen", "saw", "(F)V", false);
        });
        w.visitEnd();
        return w.toByteArray();
    }

    /** m9.PanelMixin: a @Redirect of Panel.paint's call of Hud.bar(gfx, f) that hands f to Seen.saw. */
    static byte[] redirect() {
        ClassWriter w = JarPortRetypeTest.writer("m9/PanelMixin", "java/lang/Object");
        mixinOf(w, "g9/Panel");
        JarPortFixesTest.handler(w, REDIRECT, "onBar", "(" + HUD + GFX + "F)V", "paint()V", "INVOKE", "Lg9/Hud;bar(" + GFX + "F)V", m -> {
            m.visitVarInsn(Opcodes.FLOAD, 3);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, "m9/Seen", "saw", "(F)V", false);
        });
        w.visitEnd();
        return w.toByteArray();
    }

    /** A mixin into Level.draw with a @ModifyVariable at HEAD, by type, that returns the variable as it was. */
    static byte[] variable(String name, String desc, int load) {
        return variable(name, desc, load, "HEAD", true, "draw");
    }

    /** The same @ModifyVariable at point; argsOnly false lets Mixin pick among the live locals of its type too. */
    static byte[] variable(String name, String desc, int load, String at, boolean argsOnly, String target) {
        ClassWriter w = JarPortRetypeTest.writer(name, "java/lang/Object");
        mixinOf(w, "g9/Level");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PRIVATE, "flag", desc, null, null);
        AnnotationVisitor av = m.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/ModifyVariable;", false);
        AnnotationVisitor methods = av.visitArray("method");
        methods.visit(null, target + "(FJZ)V");
        methods.visitEnd();
        AnnotationVisitor point = av.visitAnnotation("at", "Lorg/spongepowered/asm/mixin/injection/At;");
        point.visit("value", at);
        point.visitEnd();
        if (argsOnly) {
            av.visit("argsOnly", true);
        }
        av.visitEnd();
        m.visitCode();
        m.visitVarInsn(load, 1);
        m.visitInsn(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN));
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    static void mixinOf(ClassWriter w, String target) {
        AnnotationVisitor at = w.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor value = at.visitArray("value");
        value.visit(null, Type.getObjectType(target));
        value.visitEnd();
        at.visitEnd();
    }
}

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * A port back across an update that replaced static drawing helpers with an object passed to every screen, as 1.20's
 * GuiGraphics replaced 1.19.4's GuiComponent statics and the PoseStack they took. Release 2.0 has Gfx (around a Pose)
 * and Widget.render(Gfx, int), Widget.label(Gfx, int); release 1.0 has Comp.fill(Pose, int) and Widget.render(Pose, int),
 * Widget.label(Pose, int). A stand-in (knowledge/standins/2.0-1.0/) writes Gfx on 1.0, with from(Pose) for what 1.0's
 * game passes. Ported back, a mod's widget runs on 1.0's real classes: its render override is the one 1.0 calls, handed
 * a Gfx made from the Pose; the stand-in draws through 1.0's Comp; and its calls passing the Gfx where 1.0 takes the
 * Pose (label, in the override and in a helper of its own handed the Gfx, and super.render) pass the Pose the stand-in
 * holds. The change map routes Gfx to Comp, as 1.20's map
 * routes GuiGraphics to GuiComponent; the stand-in leaves fewer uses unlinked, so the port keeps it for this mod.
 */
final class JarPortBackStandinTest {

    static final String POSE = """
        package net.minecraft.g5;
        public class Pose {
            public final StringBuilder log = new StringBuilder();
        }
        """;

    static final String NEWER_GFX = """
        package net.minecraft.g5;
        public class Gfx {
            final Pose pose;
            public Gfx(Pose pose) {
                this.pose = pose;
            }
            public Pose pose() {
                return pose;
            }
            public void fill(int color) {
                pose.log.append("fill" + color + ";");
            }
        }
        """;

    static final String NEWER_WIDGET = """
        package net.minecraft.g5;
        public class Widget {
            public void render(Gfx g, int x) {
                g.fill(x);
            }
            protected void label(Gfx g, int c) {
                g.fill(c * 10);
            }
        }
        """;

    static final String OLDER_COMP = """
        package net.minecraft.g5;
        public class Comp {
            public static void fill(Pose pose, int color) {
                pose.log.append("fill" + color + ";");
            }
        }
        """;

    static final String OLDER_WIDGET = """
        package net.minecraft.g5;
        public class Widget extends Comp {
            public void render(Pose pose, int x) {
                fill(pose, x);
            }
            protected void label(Pose pose, int c) {
                fill(pose, c * 10);
            }
        }
        """;

    static final String MOD = """
        package n6;
        import net.minecraft.g5.Gfx;
        import net.minecraft.g5.Widget;
        public class Mine extends Widget {
            @Override
            public void render(Gfx g, int x) {
                g.fill(x + 1);
                label(g, 2);
                deco(g);
                super.render(g, x);
            }
            void deco(Gfx g) {
                label(g, 3);
            }
        }
        """;

    static final String STANDIN = """
        package mmt.standin.net.minecraft.g5;
        import net.minecraft.g5.Comp;
        import net.minecraft.g5.Pose;
        public class Gfx {
            final Pose pose;
            public Gfx(Pose pose) {
                this.pose = pose;
            }
            public static Gfx from(Pose pose) {
                return new Gfx(pose);
            }
            public Pose pose() {
                return pose;
            }
            public void fill(int color) {
                Comp.fill(pose, color);
            }
        }
        """;

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]).resolve("back-standin"));
        Map<String, byte[]> newer = JarPortBackTest.compile(scratch.resolve("newer"),
            Map.of("net/minecraft/g5/Pose.java", POSE, "net/minecraft/g5/Gfx.java", NEWER_GFX, "net/minecraft/g5/Widget.java", NEWER_WIDGET), null);
        Map<String, byte[]> older = JarPortBackTest.compile(scratch.resolve("older"),
            Map.of("net/minecraft/g5/Pose.java", POSE, "net/minecraft/g5/Comp.java", OLDER_COMP, "net/minecraft/g5/Widget.java", OLDER_WIDGET), null);
        Map<String, byte[]> mod = JarPortBackTest.compile(scratch.resolve("mod"), Map.of("n6/Mine.java", MOD), scratch.resolve("newer/classes"));

        GameDiff.Snap a = GameDiff.read("2.0", new ArrayList<>(newer.values()), null, null);
        GameDiff.Snap b = GameDiff.read("1.0", new ArrayList<>(older.values()), null, null);
        b.loaderVersion = "1.0.1";
        Path map = scratch.resolve("route.json");
        // As the game's change map reads the update back: the class Gfx became Comp (the game's own methods switched from
        // one to the other), and Widget's methods take the Pose again.
        Files.writeString(map, """
            {"classes": [{"from": "net.minecraft.g5.Gfx", "to": "net.minecraft.g5.Comp"}],
             "signatures": [{"owner": "net.minecraft.g5.Widget", "from": "render", "desc": "(Lnet/minecraft/g5/Gfx;I)V", "to": "render", "newDesc": "(Lnet/minecraft/g5/Pose;I)V"},
                            {"owner": "net.minecraft.g5.Widget", "from": "label", "desc": "(Lnet/minecraft/g5/Gfx;I)V", "to": "label", "newDesc": "(Lnet/minecraft/g5/Pose;I)V"}]}
            """);
        JarPort port = new JarPort(a, b, map);
        port.oldCode = new GameCode(newer::get, null, newer);
        port.newCode = new GameCode(older::get, null, older);
        Path standin = scratch.resolve("standins/2.0-1.0/net/minecraft/g5/Gfx.java");
        Files.createDirectories(standin.getParent());
        Files.writeString(standin, STANDIN);
        port.standIn.standins(List.of(scratch.resolve("standins/2.0-1.0")));

        Map<String, byte[]> files = new HashMap<>();
        mod.forEach((name, bytes) -> files.put(name + ".class", bytes));
        files.put("META-INF/neoforge.mods.toml", "modLoader=\"javafml\"\n[[mods]]\nmodId=\"n6\"\n".getBytes(StandardCharsets.UTF_8));
        List<Map<String, Object>> runs = new ArrayList<>();
        byte[] portedJar = port.port(JarPortTest.jar(files), "back-standin.jar", runs);
        Map<String, byte[]> ported = JarPortTest.entries(portedJar);
        List<String> unlinked = Json.array(runs.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(unlinked.isEmpty(), "the widget links against 1.0 with the stand-in: " + unlinked);
        // A NeoForge port is written by the porter alone (no hand-back to other names): it lists its folders, so a mod
        // finding its classes by package (ClassLoader.getResources) finds them.
        Path written = scratch.resolve("back-standin-ported.jar");
        Files.write(written, portedJar);
        try (java.net.URLClassLoader folders = new java.net.URLClassLoader(new java.net.URL[] { written.toUri().toURL() }, null)) {
            JarPortTest.expect(folders.getResource("n6/") != null, "the ported jar lists the mod's package as a folder");
        }

        Map<String, byte[]> classes = new HashMap<>(older);
        for (var e : ported.entrySet()) {
            if (e.getKey().endsWith(".class")) {
                classes.put(e.getKey().substring(0, e.getKey().length() - 6), e.getValue());
            }
            if (e.getKey().endsWith(".jar")) {
                for (var inner : JarPortTest.entries(e.getValue()).entrySet()) {
                    if (inner.getKey().endsWith(".class")) {
                        classes.put(inner.getKey().substring(0, inner.getKey().length() - 6), inner.getValue());
                    }
                }
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
            Class<?> mine = loader.loadClass("n6.Mine"), widget = loader.loadClass("net.minecraft.g5.Widget"), pose = loader.loadClass("net.minecraft.g5.Pose");
            Object p = pose.getConstructor().newInstance();
            // As 1.0's game calls it: Widget.render(Pose, int), on the mod's widget.
            widget.getMethod("render", pose, int.class).invoke(mine.getConstructor().newInstance(), p, 5);
            trace = String.valueOf(pose.getField("log").get(p));
        } catch (ReflectiveOperationException | LinkageError failed) {
            trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
        JarPortTest.expect(trace.equals("fill6;fill20;fill30;fill5;"),
            "1.0's game runs the mod's render with a Gfx made from its Pose; label, from the override and from a helper handed the Gfx, and super.render get the Pose it holds: " + trace);

        // Every port of the update carries the same jar of stand-in types, whatever its own route kept: the loader loads one
        // jar of that id. A port that has not chosen the stand-in route (Gfx still routed to Comp) lists Gfx all the same.
        JarPort fresh = new JarPort(a, b, map);
        fresh.standIn.standins(List.of(scratch.resolve("standins/2.0-1.0")));
        JarPortTest.expect(fresh.standIn.standinTypes().contains("mmt/standin/net/minecraft/g5/Gfx"),
            "the stand-in for a class the older release lacks is one of the update's types for every port: " + fresh.standIn.standinTypes());

        // A mixin into the class only the stand-in has changes nothing the older game runs (its code never calls the copy),
        // so it does not link.
        org.objectweb.asm.ClassWriter w = JarPortRetypeTest.writer("n6/GfxMixin", "java/lang/Object");
        org.objectweb.asm.AnnotationVisitor target = w.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        org.objectweb.asm.AnnotationVisitor value = target.visitArray("value");
        value.visit(null, org.objectweb.asm.Type.getObjectType("net/minecraft/g5/Gfx"));
        value.visitEnd();
        target.visitEnd();
        org.objectweb.asm.MethodVisitor m = w.visitMethod(org.objectweb.asm.Opcodes.ACC_PRIVATE, "onFill", "(I" + JarPortPointTest.CI + ")V", null, null);
        org.objectweb.asm.AnnotationVisitor inject = m.visitAnnotation(JarPortFixesTest.INJECT, false);
        org.objectweb.asm.AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, "fill(I)V");
        methods.visitEnd();
        org.objectweb.asm.AnnotationVisitor ats = inject.visitArray("at");
        org.objectweb.asm.AnnotationVisitor head = ats.visitAnnotation(null, MixinRetargets.AT);
        head.visit("value", "HEAD");
        head.visitEnd();
        ats.visitEnd();
        inject.visitEnd();
        m.visitCode();
        m.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        // Beside the widget, whose port takes the stand-in route, as Patchouli's screens and its mixin into GuiGraphics.
        Map<String, byte[]> withMixin = new HashMap<>(files);
        withMixin.put("n6/GfxMixin.class", w.toByteArray());
        withMixin.put("n6.mixins.json", "{\"package\": \"n6\", \"mixins\": [\"GfxMixin\"]}".getBytes(StandardCharsets.UTF_8));
        List<Map<String, Object>> mixinRuns = new ArrayList<>();
        Map<String, byte[]> mixinPorted = JarPortTest.entries(port.port(JarPortTest.jar(withMixin), "gfx-mixin.jar", mixinRuns));
        List<String> mixinUnlinked = Json.array(mixinRuns.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        boolean intoStandin = new String(mixinPorted.get("n6/GfxMixin.class"), StandardCharsets.ISO_8859_1).contains("mmt/standin/");
        JarPortTest.expect(!(intoStandin && mixinUnlinked.isEmpty()),
            "a mixin into a class only a stand-in has does not link (into the stand-in the older game never calls): " + mixinUnlinked);
        // An accessor reads the stand-in's own field, which Mixin applies to the one copy every port shares: it links.
        org.objectweb.asm.ClassWriter acc = new org.objectweb.asm.ClassWriter(0);
        acc.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_INTERFACE | org.objectweb.asm.Opcodes.ACC_ABSTRACT,
            "n6/GfxAccessor", null, "java/lang/Object", null);
        org.objectweb.asm.AnnotationVisitor accTarget = acc.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        org.objectweb.asm.AnnotationVisitor accValue = accTarget.visitArray("value");
        accValue.visit(null, org.objectweb.asm.Type.getObjectType("net/minecraft/g5/Gfx"));
        accValue.visitEnd();
        accTarget.visitEnd();
        org.objectweb.asm.MethodVisitor getter = acc.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_ABSTRACT, "getPose", "()Lnet/minecraft/g5/Pose;", null, null);
        org.objectweb.asm.AnnotationVisitor accessor = getter.visitAnnotation("Lorg/spongepowered/asm/mixin/gen/Accessor;", false);
        accessor.visit("value", "pose");
        accessor.visitEnd();
        getter.visitEnd();
        acc.visitEnd();
        Map<String, byte[]> withAccessor = new HashMap<>(files);
        withAccessor.put("n6/GfxAccessor.class", acc.toByteArray());
        withAccessor.put("n6.mixins.json", "{\"package\": \"n6\", \"mixins\": [\"GfxAccessor\"]}".getBytes(StandardCharsets.UTF_8));
        List<Map<String, Object>> accessorRuns = new ArrayList<>();
        port.port(JarPortTest.jar(withAccessor), "gfx-accessor.jar", accessorRuns);
        List<String> accessorUnlinked = Json.array(accessorRuns.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(accessorUnlinked.isEmpty(), "an accessor into a class only a stand-in has reads the stand-in's own field, and links: " + accessorUnlinked);
        valueClass(scratch);
        reference(scratch);
        movedMixin(scratch);
        storedLocal(scratch);
        JarPortTest.finish("JarPortBackStandinTest");
    }

    /**
     * A mixin into the class only a stand-in has, on a method whose work the older game does in a method of its own (1.20's
     * GuiGraphics.renderTooltip(Font, ItemStack, ...), which 1.19.4 draws in Screen.renderTooltip(PoseStack, ItemStack, ...)
     * with the screen's font): the stand-in's answer hands the work to that method, and the mixin moves there. Its handler
     * takes that method's parameters; the font is the screen's own, and where the handler used its object as the stood-in
     * class it gets one made from the Pose. A handler that uses its object otherwise (a shadow of the stood-in class) stays
     * reported, and so does a mixin on a method whose answer does its work in a static helper (Comp.fill).
     */
    static void movedMixin(Path scratch) throws Exception {
        Path dir = Files.createDirectories(scratch.resolve("moved-mixin"));
        String font = "package net.minecraft.g5;\npublic class Font {\n}\n";
        Map<String, byte[]> newer = JarPortBackTest.compile(dir.resolve("newer"), Map.of("net/minecraft/g5/Pose.java", POSE, "net/minecraft/g5/Font.java", font,
            "net/minecraft/g5/Gfx.java", NEWER_GFX.replace("public void fill(int color) {", "public void tip(Font f, int x) {\n        pose.log.append(\"tip\" + x + \";\");\n    }\n    public void fill(int color) {"),
            "net/minecraft/g5/Screen.java", "package net.minecraft.g5;\npublic class Screen {\n    public Font font;\n}\n"), null);
        Map<String, byte[]> older = JarPortBackTest.compile(dir.resolve("older"), Map.of("net/minecraft/g5/Pose.java", POSE, "net/minecraft/g5/Font.java", font,
            "net/minecraft/g5/Comp.java", OLDER_COMP,
            "net/minecraft/g5/Screen.java", "package net.minecraft.g5;\npublic class Screen {\n    public Font font;\n    public void tip(Pose pose, int x) {\n        pose.log.append(\"tip\" + x + \";\");\n    }\n}\n"), null);
        Map<String, byte[]> mod = JarPortBackTest.compile(dir.resolve("mod"), Map.of("n9/Hook.java", """
            package n9;
            import net.minecraft.g5.Font;
            import net.minecraft.g5.Gfx;
            public class Hook {
                public static void seen(Gfx g, Font f, int x) {
                    g.fill(x);
                }
            }
            """), dir.resolve("newer/classes"));
        GameDiff.Snap a = GameDiff.read("2.0", new ArrayList<>(newer.values()), null, null);
        GameDiff.Snap b = GameDiff.read("1.0", new ArrayList<>(older.values()), null, null);
        b.loaderVersion = "1.0.1";
        Path map = dir.resolve("route.json");
        Files.writeString(map, "{\"classes\": [{\"from\": \"net.minecraft.g5.Gfx\", \"to\": \"net.minecraft.g5.Comp\"}]}");
        JarPort port = new JarPort(a, b, map);
        port.oldCode = new GameCode(newer::get, null, newer);
        port.newCode = new GameCode(older::get, null, older);
        Path standin = dir.resolve("standins/2.0-1.0/net/minecraft/g5/Gfx.java");
        Files.createDirectories(standin.getParent());
        Files.writeString(standin, STANDIN.replace("import net.minecraft.g5.Pose;", "import net.minecraft.g5.Pose;\nimport net.minecraft.g5.Font;\nimport net.minecraft.g5.Screen;").replace("public void fill(int color) {", """
            public void tip(Font font, int x) {
                    Tips tips = new Tips();
                    tips.font = font;
                    tips.tip(pose, x);
                }
                static final class Tips extends Screen {
                }
                public void fill(int color) {"""));
        port.standIn.standins(List.of(dir.resolve("standins/2.0-1.0")));
        Map<String, byte[]> files = new HashMap<>();
        mod.forEach((name, bytes) -> files.put(name + ".class", bytes));
        files.put("META-INF/neoforge.mods.toml", "modLoader=\"javafml\"\n[[mods]]\nmodId=\"n9\"\n".getBytes(StandardCharsets.UTF_8));
        files.put("n9/TipMixin.class", tipMixin("n9/TipMixin", "tip(Lnet/minecraft/g5/Font;I)V", "(Lnet/minecraft/g5/Font;I" + JarPortPointTest.CI + ")V", false));
        files.put("n9/ShadowMixin.class", tipMixin("n9/ShadowMixin", "tip(Lnet/minecraft/g5/Font;I)V", "(Lnet/minecraft/g5/Font;I" + JarPortPointTest.CI + ")V", true));
        files.put("n9/FillMixin.class", tipMixin("n9/FillMixin", "fill(I)V", "(I" + JarPortPointTest.CI + ")V", false));
        files.put("n9.mixins.json", "{\"package\": \"n9\", \"mixins\": [\"TipMixin\", \"ShadowMixin\", \"FillMixin\"]}".getBytes(StandardCharsets.UTF_8));
        List<Map<String, Object>> runs = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(files), "moved-mixin.jar", runs));
        List<String> unlinked = Json.array(runs.get(0).get("unlinked")).stream().map(String::valueOf).sorted().toList();
        JarPortTest.expect(unlinked.equals(List.of("mixin target class gone: net.minecraft.g5.Gfx, only a stand-in has it (in n9.FillMixin)",
                "mixin target class gone: net.minecraft.g5.Gfx, only a stand-in has it (in n9.ShadowMixin)")),
            "the mixin whose handler uses its object only as the stood-in class moves; one with a shadow, and one on a method a static helper does, stay reported: " + unlinked);
        Map<String, String> strings = JarPortFixesTest.strings(ported.get("n9/TipMixin.class")), code = code(ported.get("n9/TipMixin.class"));
        String handler = code.getOrDefault("on(Lnet/minecraft/g5/Pose;I" + JarPortPointTest.CI + ")V", "");
        boolean body = code.keySet().stream().anyMatch(k -> k.matches("on\\$mmt\\(Lmmt/standin/[\\w/]*net/minecraft/g5/Gfx;Lnet/minecraft/g5/Font;I.*") && code.get(k).startsWith("static"));
        JarPortTest.expect(strings.getOrDefault("on(Lnet/minecraft/g5/Pose;I" + JarPortPointTest.CI + ")V", "").contains("Lnet/minecraft/g5/Screen;tip(Lnet/minecraft/g5/Pose;I)V")
                && code.get("@Mixin").equals("net/minecraft/g5/Screen") && handler.matches(".*Gfx\\.from .*net/minecraft/g5/Screen\\.font .*") && body,
            "it targets Screen.tip(Pose, int): the handler takes the Pose, hands its body a Gfx made from it and the screen's font: " + code);
    }

    /**
     * A @ModifyVariable at a local's STORE in a method whose drawing object the update changed (1.20's Gui.renderPlayerHealth
     * (GuiGraphics), 1.19.4's (PoseStack)): it follows where the older method stores the same locals in the same order,
     * whatever its drawing calls now are (paint), and stays reported where it stores another local first (shade).
     */
    static void storedLocal(Path scratch) throws Exception {
        Path dir = Files.createDirectories(scratch.resolve("stored-local"));
        Map<String, byte[]> newer = JarPortBackTest.compile(dir.resolve("newer"), Map.of("net/minecraft/g5/Pose.java", POSE, "net/minecraft/g5/Gfx.java", NEWER_GFX,
            "net/minecraft/g5/Panel.java", """
                package net.minecraft.g5;
                public class Panel {
                    public void paint(Gfx g, int x) {
                        int w = x * 2;
                        int h = w + 1;
                        g.fill(h);
                    }
                    public void shade(Gfx g, int x) {
                        int w = x * 2;
                        int h = w + 1;
                        g.fill(h);
                    }
                }
                """), null);
        Map<String, byte[]> older = JarPortBackTest.compile(dir.resolve("older"), Map.of("net/minecraft/g5/Pose.java", POSE, "net/minecraft/g5/Comp.java", OLDER_COMP,
            "net/minecraft/g5/Panel.java", """
                package net.minecraft.g5;
                public class Panel {
                    public void paint(Pose p, int x) {
                        int w = x * 2;
                        int h = w + 1;
                        Comp.fill(p, h);
                    }
                    public void shade(Pose p, int x) {
                        int extra = 1;
                        int w = x * 2;
                        int h = w + extra;
                        Comp.fill(p, h);
                    }
                }
                """), null);
        GameDiff.Snap a = GameDiff.read("2.0", new ArrayList<>(newer.values()), null, null);
        GameDiff.Snap b = GameDiff.read("1.0", new ArrayList<>(older.values()), null, null);
        b.loaderVersion = "1.0.1";
        Path map = dir.resolve("route.json");
        Files.writeString(map, """
            {"classes": [{"from": "net.minecraft.g5.Gfx", "to": "net.minecraft.g5.Comp"}],
             "signatures": [{"owner": "net.minecraft.g5.Panel", "from": "paint", "desc": "(Lnet/minecraft/g5/Gfx;I)V", "to": "paint", "newDesc": "(Lnet/minecraft/g5/Pose;I)V"},
                            {"owner": "net.minecraft.g5.Panel", "from": "shade", "desc": "(Lnet/minecraft/g5/Gfx;I)V", "to": "shade", "newDesc": "(Lnet/minecraft/g5/Pose;I)V"}]}
            """);
        JarPort port = new JarPort(a, b, map);
        port.oldCode = new GameCode(newer::get, null, newer);
        port.newCode = new GameCode(older::get, null, older);
        Map<String, byte[]> files = new HashMap<>();
        files.put("META-INF/neoforge.mods.toml", "modLoader=\"javafml\"\n[[mods]]\nmodId=\"n10\"\n".getBytes(StandardCharsets.UTF_8));
        for (String method : List.of("paint", "shade")) {
            String name = "n10/" + Character.toUpperCase(method.charAt(0)) + method.substring(1) + "Mixin";
            org.objectweb.asm.ClassWriter w = JarPortRetypeTest.writer(name, "java/lang/Object");
            org.objectweb.asm.AnnotationVisitor target = w.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
            org.objectweb.asm.AnnotationVisitor value = target.visitArray("value");
            value.visit(null, org.objectweb.asm.Type.getObjectType("net/minecraft/g5/Panel"));
            value.visitEnd();
            target.visitEnd();
            org.objectweb.asm.MethodVisitor m = w.visitMethod(org.objectweb.asm.Opcodes.ACC_PRIVATE, "taller", "(I)I", null, null);
            org.objectweb.asm.AnnotationVisitor variable = m.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/ModifyVariable;", false);
            org.objectweb.asm.AnnotationVisitor methods = variable.visitArray("method");
            methods.visit(null, method + "(Lnet/minecraft/g5/Gfx;I)V");
            methods.visitEnd();
            org.objectweb.asm.AnnotationVisitor at = variable.visitAnnotation("at", MixinRetargets.AT);
            at.visit("value", "STORE");
            at.visitEnd();
            variable.visit("ordinal", 1);
            variable.visitEnd();
            m.visitCode();
            m.visitVarInsn(org.objectweb.asm.Opcodes.ILOAD, 1);
            m.visitInsn(org.objectweb.asm.Opcodes.ICONST_1);
            m.visitInsn(org.objectweb.asm.Opcodes.IADD);
            m.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
            m.visitMaxs(2, 2);
            m.visitEnd();
            w.visitEnd();
            files.put(name + ".class", w.toByteArray());
        }
        files.put("n10.mixins.json", "{\"package\": \"n10\", \"mixins\": [\"PaintMixin\", \"ShadeMixin\"]}".getBytes(StandardCharsets.UTF_8));
        List<Map<String, Object>> runs = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(files), "stored-local.jar", runs));
        List<String> unlinked = Json.array(runs.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        String paint = JarPortFixesTest.strings(ported.get("n10/PaintMixin.class")).getOrDefault("taller(I)I", "");
        JarPortTest.expect(unlinked.equals(List.of("mixin target changed signature: net.minecraft.g5.Panel.shade(Lnet/minecraft/g5/Gfx;I)V"))
                && paint.contains("paint(Lnet/minecraft/g5/Pose;I)V"),
            "a STORE @ModifyVariable follows where the older method stores its locals alike, and stays reported where it stores another first: " + unlinked + " " + paint);
    }

    /** Each method's code as the members it names in order ("static" first for a static one), and "@Mixin" the target class. */
    static Map<String, String> code(byte[] bytes) {
        Map<String, String> out = new TreeMap<>();
        out.put("@Mixin", "");
        new org.objectweb.asm.ClassReader(bytes).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.AnnotationVisitor visitAnnotation(String d, boolean visible) {
                return !d.equals("Lorg/spongepowered/asm/mixin/Mixin;") ? null : new org.objectweb.asm.AnnotationVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    @Override
                    public org.objectweb.asm.AnnotationVisitor visitArray(String n) {
                        return new org.objectweb.asm.AnnotationVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            @Override
                            public void visit(String x, Object v) {
                                out.put("@Mixin", v instanceof org.objectweb.asm.Type t ? t.getInternalName() : String.valueOf(v));
                            }
                        };
                    }
                };
            }

            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                StringBuilder sb = new StringBuilder((access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0 ? "static " : "");
                return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        sb.append(owner).append('.').append(n).append(' ');
                    }

                    @Override
                    public void visitFieldInsn(int op, String owner, String n, String d) {
                        sb.append(owner).append('.').append(n).append(' ');
                    }

                    @Override
                    public void visitEnd() {
                        out.put(name + desc, sb.toString());
                    }
                };
            }
        }, 0);
        return out;
    }

    /**
     * A mixin into Gfx with an @Inject at HEAD of method, whose handler hands its object as a Gfx, the font and the int to
     * n9.Hook.seen; with shadow, it first calls a method of its own object (as a @Shadow of the stood-in class would).
     */
    static byte[] tipMixin(String name, String method, String desc, boolean shadow) {
        org.objectweb.asm.ClassWriter w = JarPortRetypeTest.writer(name, "java/lang/Object");
        org.objectweb.asm.AnnotationVisitor target = w.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        org.objectweb.asm.AnnotationVisitor value = target.visitArray("value");
        value.visit(null, org.objectweb.asm.Type.getObjectType("net/minecraft/g5/Gfx"));
        value.visitEnd();
        target.visitEnd();
        org.objectweb.asm.MethodVisitor m = w.visitMethod(org.objectweb.asm.Opcodes.ACC_PRIVATE, "on", desc, null, null);
        org.objectweb.asm.AnnotationVisitor inject = m.visitAnnotation(JarPortFixesTest.INJECT, false);
        org.objectweb.asm.AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, method);
        methods.visitEnd();
        org.objectweb.asm.AnnotationVisitor ats = inject.visitArray("at");
        org.objectweb.asm.AnnotationVisitor head = ats.visitAnnotation(null, MixinRetargets.AT);
        head.visit("value", "HEAD");
        head.visitEnd();
        ats.visitEnd();
        inject.visitEnd();
        m.visitCode();
        if (shadow) {
            m.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
            m.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, name, "pose", "()Lnet/minecraft/g5/Pose;", false);
            m.visitInsn(org.objectweb.asm.Opcodes.POP);
        }
        boolean font = desc.startsWith("(Lnet/minecraft/g5/Font;");
        m.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        m.visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST, "net/minecraft/g5/Gfx");
        if (font) {
            m.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
        } else {
            m.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
        }
        m.visitVarInsn(org.objectweb.asm.Opcodes.ILOAD, font ? 2 : 1);
        m.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, "n9/Hook", "seen", "(Lnet/minecraft/g5/Gfx;Lnet/minecraft/g5/Font;I)V", false);
        m.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        m.visitMaxs(3, 4);
        m.visitEnd();
        if (shadow) {
            org.objectweb.asm.MethodVisitor s = w.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_ABSTRACT, "pose", "()Lnet/minecraft/g5/Pose;", null, null);
            s.visitEnd();
        }
        w.visitEnd();
        return w.toByteArray();
    }

    /**
     * A value class the older release lacks that the route reads as another (1.20's SignText, which the change map moves
     * to SignBlockEntity, whose one side holds its fields on 1.19.4): a stand-in method of an existing class gives the
     * value class's stand-in, and the port takes the stand-in route although the mod only names the value class as what
     * a call gives (Board.note()L.../Note;). A field of that type (1.20's AbstractSignEditScreen.text) is answered the same way.
     */
    static void valueClass(Path scratch) throws Exception {
        Path dir = Files.createDirectories(scratch.resolve("value-class"));
        String note = """
            package net.minecraft.g5;
            public class Note {
                final String t;
                public Note(String t) {
                    this.t = t;
                }
                public String text() {
                    return t;
                }
            }
            """;
        Map<String, byte[]> newer = JarPortBackTest.compile(dir.resolve("newer"), Map.of("net/minecraft/g5/Note.java", note, "net/minecraft/g5/Board.java", """
            package net.minecraft.g5;
            public class Board {
                public Note last = new Note("hi");
                public Note note() {
                    return new Note("hi");
                }
            }
            """), null);
        Map<String, byte[]> older = JarPortBackTest.compile(dir.resolve("older"), Map.of("net/minecraft/g5/Board.java", """
            package net.minecraft.g5;
            public class Board {
                public String text() {
                    return "hi";
                }
            }
            """), null);
        Map<String, byte[]> mod = JarPortBackTest.compile(dir.resolve("mod"), Map.of("n7/Reader.java", """
            package n7;
            import net.minecraft.g5.Board;
            public class Reader {
                public static String read(Board b) {
                    return b.note().text() + b.last.text();
                }
            }
            """), dir.resolve("newer/classes"));
        GameDiff.Snap a = GameDiff.read("2.0", new ArrayList<>(newer.values()), null, null);
        GameDiff.Snap b = GameDiff.read("1.0", new ArrayList<>(older.values()), null, null);
        b.loaderVersion = "1.0.1";
        Path map = dir.resolve("route.json");
        Files.writeString(map, "{\"classes\": [{\"from\": \"net.minecraft.g5.Note\", \"to\": \"net.minecraft.g5.Board\"}]}");
        JarPort port = new JarPort(a, b, map);
        port.oldCode = new GameCode(newer::get, null, newer);
        port.newCode = new GameCode(older::get, null, older);
        Path standins = dir.resolve("standins/2.0-1.0/net/minecraft/g5");
        Files.createDirectories(standins);
        Files.writeString(standins.resolve("Note.java"), note.replace("package net.minecraft.g5;", "package mmt.standin.net.minecraft.g5;"));
        Files.writeString(standins.resolve("Board.java"), """
            package mmt.standin.net.minecraft.g5;
            public final class Board {
                private Board() {
                }
                public static Note note(net.minecraft.g5.Board self) {
                    return new Note(self.text());
                }
                public static Note last(net.minecraft.g5.Board self) {
                    return new Note(self.text());
                }
            }
            """);
        port.standIn.standins(List.of(dir.resolve("standins/2.0-1.0")));
        Map<String, byte[]> files = new HashMap<>();
        mod.forEach((name, bytes) -> files.put(name + ".class", bytes));
        files.put("META-INF/neoforge.mods.toml", "modLoader=\"javafml\"\n[[mods]]\nmodId=\"n7\"\n".getBytes(StandardCharsets.UTF_8));
        List<Map<String, Object>> runs = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(files), "value-class.jar", runs));
        List<String> unlinked = Json.array(runs.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(unlinked.isEmpty(), "a call giving a value class the older release lacks links through the stand-ins, route left out: " + unlinked);
        Map<String, byte[]> classes = new HashMap<>(older);
        for (var e : ported.entrySet()) {
            if (e.getKey().endsWith(".class")) {
                classes.put(e.getKey().substring(0, e.getKey().length() - 6), e.getValue());
            }
            if (e.getKey().endsWith(".jar")) {
                for (var inner : JarPortTest.entries(e.getValue()).entrySet()) {
                    if (inner.getKey().endsWith(".class")) {
                        classes.put(inner.getKey().substring(0, inner.getKey().length() - 6), inner.getValue());
                    }
                }
            }
        }
        String got;
        try {
            Class<?> board = JarPortStandinTest.loader(classes).loadClass("net.minecraft.g5.Board");
            Class<?> reader = board.getClassLoader().loadClass("n7.Reader");
            got = String.valueOf(reader.getMethod("read", board).invoke(null, board.getConstructor().newInstance()));
        } catch (ReflectiveOperationException | LinkageError failed) {
            got = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
        JarPortTest.expect(got.equals("hihi"), "and reads the older release's own value, through a call and through a field of that type: " + got);
    }

    /**
     * A method reference to a method that cannot move, made for a game callback whose parameter is the class only a
     * stand-in has on the older release (MRU's overlay::render for 1.20's HudRenderCallback.onHudRender(GuiGraphics,
     * float), where 1.19.4's takes the PoseStack). The mod's own interface keeps the stand-in type; the lambda is made
     * through an adapter that makes the stand-in from what the older game passes, and the older game's call reaches it.
     */
    static void reference(Path scratch) throws Exception {
        Path dir = Files.createDirectories(scratch.resolve("reference"));
        String hud = """
            package net.minecraft.g5;
            public class Hud {
                static java.util.List<Painter> painters = new java.util.ArrayList<>();
                public static void register(Painter p) {
                    painters.add(p);
                }
                public static void run(Pose pose) {
                    for (Painter painter : painters) {
                        painter.paint(%s, 4);
                    }
                }
            }
            """;
        Map<String, byte[]> newer = JarPortBackTest.compile(dir.resolve("newer"), Map.of("net/minecraft/g5/Pose.java", POSE, "net/minecraft/g5/Gfx.java", NEWER_GFX,
            "net/minecraft/g5/Painter.java", "package net.minecraft.g5;\npublic interface Painter {\n    void paint(Gfx g, int x);\n}\n",
            "net/minecraft/g5/Hud.java", hud.formatted("new Gfx(pose)")), null);
        Map<String, byte[]> older = JarPortBackTest.compile(dir.resolve("older"), Map.of("net/minecraft/g5/Pose.java", POSE, "net/minecraft/g5/Comp.java", OLDER_COMP,
            "net/minecraft/g5/Painter.java", "package net.minecraft.g5;\npublic interface Painter {\n    void paint(Pose p, int x);\n}\n",
            "net/minecraft/g5/Hud.java", hud.formatted("pose")), null);
        Map<String, byte[]> mod = JarPortBackTest.compile(dir.resolve("mod"), Map.of("n8/Layer.java", """
            package n8;
            import net.minecraft.g5.Gfx;
            public interface Layer {
                boolean draw(Gfx g, int x);
            }
            """, "n8/Box.java", """
            package n8;
            import net.minecraft.g5.Gfx;
            public class Box implements Layer {
                @Override
                public boolean draw(Gfx g, int x) {
                    g.fill(x + 3);
                    return true;
                }
            }
            """, "n8/Wire.java", """
            package n8;
            import net.minecraft.g5.Gfx;
            import net.minecraft.g5.Hud;
            public class Wire {
                public static void wire(Layer layer) {
                    Hud.register(layer::draw);
                    Hud.register(Wire::mark);
                    Hud.register(Wire::mark);
                }
                static void mark(Gfx g, int x) {
                    g.fill(x);
                }
            }
            """), dir.resolve("newer/classes"));
        GameDiff.Snap a = GameDiff.read("2.0", new ArrayList<>(newer.values()), null, null);
        GameDiff.Snap b = GameDiff.read("1.0", new ArrayList<>(older.values()), null, null);
        b.loaderVersion = "1.0.1";
        Path map = dir.resolve("route.json");
        Files.writeString(map, """
            {"classes": [{"from": "net.minecraft.g5.Gfx", "to": "net.minecraft.g5.Comp"}],
             "signatures": [{"owner": "net.minecraft.g5.Painter", "from": "paint", "desc": "(Lnet/minecraft/g5/Gfx;I)V", "to": "paint", "newDesc": "(Lnet/minecraft/g5/Pose;I)V"}]}
            """);
        JarPort port = new JarPort(a, b, map);
        port.oldCode = new GameCode(newer::get, null, newer);
        port.newCode = new GameCode(older::get, null, older);
        Path standin = dir.resolve("standins/2.0-1.0/net/minecraft/g5/Gfx.java");
        Files.createDirectories(standin.getParent());
        Files.writeString(standin, STANDIN);
        port.standIn.standins(List.of(dir.resolve("standins/2.0-1.0")));
        Map<String, byte[]> files = new HashMap<>();
        mod.forEach((name, bytes) -> files.put(name + ".class", bytes));
        files.put("META-INF/neoforge.mods.toml", "modLoader=\"javafml\"\n[[mods]]\nmodId=\"n8\"\n".getBytes(StandardCharsets.UTF_8));
        List<Map<String, Object>> runs = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(files), "reference.jar", runs));
        List<String> unlinked = Json.array(runs.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(unlinked.isEmpty(), "a method reference to the mod's own interface method links for the callback's older parameters: " + unlinked);
        Map<String, byte[]> classes = new HashMap<>(older);
        for (var e : ported.entrySet()) {
            if (e.getKey().endsWith(".class")) {
                classes.put(e.getKey().substring(0, e.getKey().length() - 6), e.getValue());
            }
            if (e.getKey().endsWith(".jar")) {
                for (var inner : JarPortTest.entries(e.getValue()).entrySet()) {
                    if (inner.getKey().endsWith(".class")) {
                        classes.put(inner.getKey().substring(0, inner.getKey().length() - 6), inner.getValue());
                    }
                }
            }
        }
        String got;
        try {
            ClassLoader loader = JarPortStandinTest.loader(classes);
            Class<?> hudClass = loader.loadClass("net.minecraft.g5.Hud"), pose = loader.loadClass("net.minecraft.g5.Pose"), layer = loader.loadClass("n8.Layer");
            loader.loadClass("n8.Wire").getMethod("wire", layer).invoke(null, loader.loadClass("n8.Box").getConstructor().newInstance());
            Object p = pose.getConstructor().newInstance();
            hudClass.getMethod("run", pose).invoke(null, p);
            got = String.valueOf(pose.getField("log").get(p));
        } catch (ReflectiveOperationException | LinkageError failed) {
            got = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
        // The mod's method returns a value the void callback drops; a static method referenced twice moves once, and the
        // second reference's adapter calls it as it was.
        JarPortTest.expect(got.equals("fill7;fill4;fill4;"), "the older game's callback reaches the mod's methods with the stand-in made from its Pose: " + got);
    }
}

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
        JarPortTest.finish("JarPortBackStandinTest");
    }

    /**
     * A value class the older release lacks that the route reads as another (1.20's SignText, which the change map moves
     * to SignBlockEntity, whose one side holds its fields on 1.19.4): a stand-in method of an existing class gives the
     * value class's stand-in, and the port takes the stand-in route although the mod only names the value class as what
     * a call gives (Board.note()L.../Note;).
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
                    return b.note().text();
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
        JarPortTest.expect(got.equals("hi"), "and reads the older release's own value: " + got);
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
                static Painter painter;
                public static void register(Painter p) {
                    painter = p;
                }
                public static void run(Pose pose) {
                    painter.paint(%s, 4);
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
                void draw(Gfx g, int x);
            }
            """, "n8/Box.java", """
            package n8;
            import net.minecraft.g5.Gfx;
            public class Box implements Layer {
                @Override
                public void draw(Gfx g, int x) {
                    g.fill(x + 3);
                }
            }
            """, "n8/Wire.java", """
            package n8;
            import net.minecraft.g5.Hud;
            public class Wire {
                public static void wire(Layer layer) {
                    Hud.register(layer::draw);
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
        JarPortTest.expect(got.equals("fill7;"), "the older game's callback reaches the mod's method with the stand-in made from its Pose: " + got);
    }
}

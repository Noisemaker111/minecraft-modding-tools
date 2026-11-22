import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * A stand-in for an old class mods extend, as 1.19.4's abstract GuiComponent (moved onto GuiGraphics in 1.20, and
 * subclassed by Trinkets and Better Advancements to reach its helpers by their plain names): written as that class, it
 * is the ported subclass's superclass, so the subclass passes the verifier where it is given as a Panel and reaches the
 * helpers it inherits. Here Panel's members went to Canvas, as GuiComponent's went to GuiGraphics. A mod extending a gone
 * class whose stand-in is only a holder of static methods (Plate) does not link.
 */
final class JarPortExtendTest {

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
        b.loaderVersion = "2.0.1";
        JarPortTest.snap(a, "s9/Panel", Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, null, "<init>()V", "fill()I");
        a.classes.get("s9/Panel").methods.get("fill()I").flags |= Opcodes.ACC_STATIC;
        JarPortTest.snap(b, "s9/Canvas", Opcodes.ACC_PUBLIC, null, "<init>(I)V", "fill()I");
        JarPortTest.snap(a, "s9/Plate", Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, null, "<init>()V");
        // The game's own subclass extends Object now, as Gui and AbstractWidget stopped extending GuiComponent in 1.20.
        JarPortTest.snap(a, "s9/Widget", Opcodes.ACC_PUBLIC, "s9/Panel", "<init>()V");
        JarPortTest.snap(b, "s9/Widget", Opcodes.ACC_PUBLIC, "java/lang/Object", "<init>()V");

        Path book = Files.createDirectories(scratch.resolve("extend-kb")).resolve("fixes.json");
        Files.writeString(book, "{\"rules\": []}");
        JarPortStandinTest.write(scratch.resolve("extend-kb/standins/1.0-2.0/s9/Panel.java"), """
            package mmt.standin.s9;
            public abstract class Panel {
                public Panel() {}
                public static int fill() {
                    return 7;
                }
            }
            """);
        JarPortStandinTest.write(scratch.resolve("extend-kb/standins/1.0-2.0/s9/Plate.java"), """
            package mmt.standin.s9;
            public final class Plate {
                private Plate() {}
                public static boolean isInstance(Object value) {
                    return false;
                }
            }
            """);
        Path map = scratch.resolve("route-extend.json");
        Files.writeString(map, "{\"classes\": [{\"from\": \"s9.Panel\", \"to\": \"s9.Canvas\"}]}");
        JarPort port = new JarPort(a, b, map);
        port.fixes(book);

        List<Map<String, Object>> runs = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("n9/MyPanel.class", myPanel(),
            "META-INF/neoforge.mods.toml", "modLoader=\"javafml\"\n[[mods]]\nmodId=\"n9\"\n".getBytes(StandardCharsets.UTF_8))), "extend.jar", runs));
        List<String> unlinked = runs.stream().flatMap(r -> Json.array(r.get("unlinked")).stream()).map(String::valueOf).toList();
        JarPortTest.expect(unlinked.isEmpty(), "a subclass of a class with an extendable stand-in links: " + unlinked);
        Map<String, byte[]> classes = new HashMap<>(Map.of("s9/Canvas", JarPortStandinTest.end(JarPortStandinTest.start("s9/Canvas"))));
        Map<String, byte[]> all = new HashMap<>(ported);
        all.putAll(JarPortStandinTest.types(ported, false));
        for (var e : all.entrySet()) {
            if (e.getKey().endsWith(".class")) {
                classes.put(e.getKey().substring(0, e.getKey().length() - 6), e.getValue());
            }
        }
        String trace;
        try {
            Class<?> mine = JarPortStandinTest.loader(classes).loadClass("n9.MyPanel");
            trace = mine.getSuperclass().getName() + " " + mine.getMethod("run").invoke(null);
        } catch (ReflectiveOperationException | LinkageError failed) {
            trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
        JarPortTest.expect(trace.matches("mmt\\.standin\\.u[0-9a-f]+\\.s9\\.Panel 7"),
            "the subclass extends the stand-in, passes the verifier as a Panel and reaches the helper it inherits: " + trace);

        List<Map<String, Object>> plateRuns = new ArrayList<>();
        port.port(JarPortTest.jar(Map.of("n9/MyPlate.class", JarPortStandinTest.end(subclass("n9/MyPlate", "s9/Plate")))), "plate.jar", plateRuns);
        List<String> plate = plateRuns.stream().flatMap(r -> Json.array(r.get("unlinked")).stream()).map(String::valueOf).toList();
        JarPortTest.expect(plate.stream().anyMatch(u -> u.contains("s9.Plate.<init>()V")),
            "a subclass of a gone class whose stand-in is a holder of static methods does not link: " + plate);
        JarPortTest.finish("JarPortExtendTest");
    }

    static ClassWriter subclass(String name, String sup) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, sup, null);
        MethodVisitor init = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, sup, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        return w;
    }

    /**
     * class MyPanel extends Panel, written for 1.0: int draw() { return fill(); } (javac names the subclass as the
     * helper's owner), static int take(Panel p) { return ((MyPanel) p).draw(); }, static int run() { return take(new MyPanel()); }
     */
    static byte[] myPanel() {
        ClassWriter w = subclass("n9/MyPanel", "s9/Panel");
        MethodVisitor draw = w.visitMethod(Opcodes.ACC_PUBLIC, "draw", "()I", null, null);
        draw.visitCode();
        draw.visitMethodInsn(Opcodes.INVOKESTATIC, "n9/MyPanel", "fill", "()I", false);
        draw.visitInsn(Opcodes.IRETURN);
        draw.visitMaxs(0, 0);
        MethodVisitor take = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "take", "(Ls9/Panel;)I", null, null);
        take.visitCode();
        take.visitVarInsn(Opcodes.ALOAD, 0);
        take.visitTypeInsn(Opcodes.CHECKCAST, "n9/MyPanel");
        take.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "n9/MyPanel", "draw", "()I", false);
        take.visitInsn(Opcodes.IRETURN);
        take.visitMaxs(0, 0);
        MethodVisitor run = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()I", null, null);
        run.visitCode();
        run.visitTypeInsn(Opcodes.NEW, "n9/MyPanel");
        run.visitInsn(Opcodes.DUP);
        run.visitMethodInsn(Opcodes.INVOKESPECIAL, "n9/MyPanel", "<init>", "()V", false);
        run.visitMethodInsn(Opcodes.INVOKESTATIC, "n9/MyPanel", "take", "(Ls9/Panel;)I", false);
        run.visitInsn(Opcodes.IRETURN);
        run.visitMaxs(0, 0);
        return JarPortStandinTest.end(w);
    }
}

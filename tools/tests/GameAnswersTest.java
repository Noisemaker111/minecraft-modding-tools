import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * What the game's own code did about each reference a port cannot link, answered inside the port from the route map
 * (GameDriver's "evidence" list, Exposure.says, JarPort.answer) on a three-release game built here as real bytecode.
 *
 * 1.0 -> 2.0 moves q.Widget to q.ui.Widget and drops Gui.spare(), which nothing calls. 2.0 -> 3.0 drops Gui.blit()
 * and ui.Widget.press() (Screen.render calls Gui.paint(int) and ui.Widget.tap(String) instead) and the library method
 * org.lib.Codec.old() (render calls Codec.fresh()). Gui.keep(int) is in every release.
 *
 *   java -cp asm.jar:CLASSES GameAnswersTest SCRATCH   (in the jar-port check of bench/run_tests.py)
 */
final class GameAnswersTest {

    static final String Q = "net/minecraft/q/", LIB = "org/lib/Codec";

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        GameDriver driver = new GameDriver(List.of("1.0", "2.0", "3.0"), null, null);
        for (String v : List.of("1.0", "2.0", "3.0")) {
            GameDiff.Snap s = GameDiff.read(v, List.copyOf(game(v).values()), null, List.of());
            s.libraries.put(LIB, library(v));
            driver.next(s, null, null);
        }
        Path map = scratch.resolve("route-answers.json");
        Files.writeString(map, "{\"evidence\": [" + String.join(",\n", driver.evidence.values()) + "]}");
        JarPortTest.expect(driver.evidence.values().stream().allMatch(line -> line.chars().allMatch(c -> c >= 0x20)),
            "the map's lines hold no raw control character, which JSON readers refuse: " + driver.evidence.values());
        Exposure.Unlinked hop = Exposure.Unlinked.of(Json.read(map));
        String press = "net.minecraft.q.Widget.press()V";
        JarPortTest.expect(hop.evidence().containsKey(press) && !hop.evidence().containsKey("net.minecraft.q.ui.Widget.press()V"),
            "a member the second update drops is kept under the name the chain's first release gives it: " + hop.evidence().keySet());
        JarPortTest.expect(hop.evidence().get(press).sites() == 1,
            "and counted in the code of the release it was dropped from: " + hop.evidence().get(press));

        // The port's side: the first and last releases as a port reads them, the library among the classes (Exposure.snap).
        GameDiff.Snap a = GameDiff.read("1.0", List.copyOf(game("1.0").values()), null, List.of(), false);
        GameDiff.Snap b = GameDiff.read("3.0", List.copyOf(game("3.0").values()), null, List.of(), false);
        a.classes.put(LIB, library("1.0"));
        b.classes.put(LIB, library("3.0"));
        String blit = "call: net.minecraft.q.Gui.blit()V", sub = "call: net.minecraft.q.SubGui.blit()V", spare = "call: net.minecraft.q.Gui.spare()V";
        String codec = "call: org.lib.Codec.old()V", keep = "call: net.minecraft.q.Gui.keep(I)V", widget = "call: net.minecraft.q.Widget.press()V";
        String other = "call: m.Other.thing()V", own = "built for another loader: every class names the game as another loader's build does";
        Map<String, Object> result = new LinkedHashMap<>(Map.of("jar", "m.jar",
            "unlinked", List.of(blit + " (in m.Mod)", sub + " (in m.Mod)", spare, codec, keep, widget, other, own)));
        Map<String, Object> before = new LinkedHashMap<>(Map.of("jar", "m.jar", "unlinked", List.of(blit), "game", Map.of()));
        JarPort.answer(a, b, hop, List.of(before, result), new HashMap<>());
        Map<String, Object> said = Json.object(result.get("game"));
        expect(said, blit, "split", "game", "k n:7 ; m net.minecraft.q.Gui.paint(I)V at 1 of 1 rewritten uses (1 uses)",
            "a call the game's code rewrote into another call with a constant is answered from the hop that dropped it");
        JarPortTest.expect(Boolean.TRUE.equals(Json.object(said.get(blit)).get("portable")), "with whether its old body can go with the mod: " + said.get(blit));
        expect(said, sub, "split", "game", "k n:7 ; m net.minecraft.q.Gui.paint(I)V at 1 of 1 rewritten uses (1 uses)",
            "a call naming a subclass is answered where the old release declares the member");
        expect(said, spare, "unused", "game", "the game never uses it", "a member nothing in the game called is unused");
        expect(said, codec, "replaced", "library", "org.lib.Codec.fresh()V at 1 of 1 aligned uses (1 uses)",
            "a library member the game's code stopped calling is answered from the game's code too");
        expect(said, keep, "linked", "game", "the map follows it; the new signature differs", "a member the map has no evidence for is one it links");
        expect(said, widget, "split", "game", "k s:two\nlines ; m net.minecraft.q.ui.Widget.tap(Ljava.lang.String;)V at 1 of 1 rewritten uses (1 uses)",
            "a member dropped by the second update of a chain is answered under its first name");
        JarPortTest.expect(!said.containsKey(other) && !said.containsKey(own), "a line naming nothing of the old release gets no answer: " + said.keySet());
        JarPortTest.expect(Json.object(before.get("game")).isEmpty(), "a step before's answers are left as they are: " + before.get("game"));

        if (JarPortTest.failures > 0) {
            System.exit(1);
        }
        System.out.println("game-answers: checks passed");
    }

    static void expect(Map<String, Object> said, String member, String game, String part, String detail, String what) {
        Object row = said.get(member);
        JarPortTest.expect(row instanceof Map<?, ?> r && game.equals(r.get("game")) && part.equals(r.get("part")) && detail.equals(r.get("gameDetail")),
            what + ": " + member + " -> " + row);
    }

    /** org.lib.Codec as a library the release runs with: old() before 3.0, fresh() from it. */
    static GameDiff.Cls library(String version) {
        GameDiff.Cls c = new GameDiff.Cls(LIB, Opcodes.ACC_PUBLIC, "java/lang/Object", List.of(), true);
        c.library = true;
        String name = version.equals("3.0") ? "fresh" : "old";
        c.methods.put(name + "()V", new GameDiff.Meth(c, name, "()V", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
        return c;
    }

    static Map<String, byte[]> game(String version) {
        boolean moved = !version.equals("1.0"), last = version.equals("3.0");
        String widget = Q + (moved ? "ui/" : "") + "Widget";
        Map<String, byte[]> out = new LinkedHashMap<>();
        out.put(Q + "Gui", cls(Q + "Gui", "java/lang/Object", w -> {
            body(w, "keep", "(I)V", m -> { });
            body(w, "fill", "()V", m -> { });
            if (last) {
                body(w, "paint", "(I)V", m -> { });
            } else {
                body(w, "blit", "()V", m -> { });
            }
            if (!moved) {
                body(w, "spare", "()V", m -> { });
            }
        }));
        out.put(Q + "SubGui", cls(Q + "SubGui", Q + "Gui", w -> { }));
        out.put(widget, cls(widget, "java/lang/Object", w -> {
            body(w, "label", "()Ljava/lang/String;", m -> {
                m.visitLdcInsn("w");
                m.visitInsn(Opcodes.ARETURN);
            });
            if (last) {
                body(w, "tap", "(Ljava/lang/String;)V", m -> { });
            } else {
                body(w, "press", "()V", m -> { });
            }
        }));
        out.put(Q + "Screen", cls(Q + "Screen", "java/lang/Object", w -> body(w, "render", "(L" + Q + "Gui;L" + widget + ";)V", m -> {
            m.visitVarInsn(Opcodes.ALOAD, 1);
            m.visitInsn(Opcodes.ICONST_1);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Q + "Gui", "keep", "(I)V", false);
            m.visitVarInsn(Opcodes.ALOAD, 1);
            if (last) {
                m.visitIntInsn(Opcodes.BIPUSH, 7);
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Q + "Gui", "paint", "(I)V", false);
            } else {
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Q + "Gui", "blit", "()V", false);
            }
            m.visitVarInsn(Opcodes.ALOAD, 1);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Q + "Gui", "fill", "()V", false);
            m.visitVarInsn(Opcodes.ALOAD, 2);
            if (last) {
                m.visitLdcInsn("two\nlines"); // a constant with a line break, written into the map as JSON allows
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, widget, "tap", "(Ljava/lang/String;)V", false);
            } else {
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, widget, "press", "()V", false);
            }
            // Each change between calls the releases share, so each is a stretch of its own.
            m.visitVarInsn(Opcodes.ALOAD, 2);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, widget, "label", "()Ljava/lang/String;", false);
            m.visitInsn(Opcodes.POP);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, LIB, last ? "fresh" : "old", "()V", false);
            m.visitVarInsn(Opcodes.ALOAD, 1);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Q + "Gui", "fill", "()V", false);
        })));
        return out;
    }

    /** A public method; void ones end with the return the body leaves out. */
    static void body(ClassWriter w, String name, String desc, java.util.function.Consumer<MethodVisitor> code) {
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
        m.visitCode();
        code.accept(m);
        if (desc.endsWith(")V")) {
            m.visitInsn(Opcodes.RETURN);
        }
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    static byte[] cls(String name, String sup, java.util.function.Consumer<ClassWriter> body) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String x, String y) {
                return "java/lang/Object";
            }
        };
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, sup, null);
        MethodVisitor init = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, sup, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        body.accept(w);
        w.visitEnd();
        return w.toByteArray();
    }
}

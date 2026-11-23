import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * GameDiff.SameCall on a two-release game built here: methods the game kept with other parameters, each called as its
 * old self behaved, read from the two bodies. Bio.temp(y) tested y > 80 and temp(y, sea) tests y > sea + 17, so the old
 * call is temp(y, 63); Bio.cold(y) only hands sea on to temp, so it is cold(y, 63). Ent.grief() read this.level() where
 * grief(level) reads its ServerLevel-like parameter, so it is grief((SLvl) this.level()). Fin.id() ignores the context it
 * takes now, so it gets null. Base.size(i) took a context its own body ignores too, but Sub overrides it: nothing is
 * learned, since the override may read it. Lst(wide, screen, top, bottom) became Lst(wide, height, y) over a new
 * superclass Pane whose getBottom() is getY() + height: the game reads top as getY() and bottom as getBottom(), so the old
 * call is Lst($1, $4 - $3, $3), the screen height nothing reads any more dropped. Pt(a) became Pt(a, c), c a value the
 * old object never held, and Fol(r, spread) became Fol(r) where the old size() read spread: nothing is learned for
 * either, since dropping spread would change what a mod's Fol does. The learned lines are applied by JarPort to a mod that then runs on this
 * JVM, its own list subclass's super(...) call included.
 *
 *   java -cp asm.jar:CLASSES GameSameCallTest SCRATCH   (in the jar-port check of bench/run_tests.py)
 */
final class GameSameCallTest {

    static final String U = "net/minecraft/s5/", CTX = "L" + U + "Ctx;";

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        Map<String, byte[]> oldGame = game(false), newGame = game(true);
        GameDiff.Snap a = GameDiff.read("1.0", List.copyOf(oldGame.values()), null, List.of());
        GameDiff.Snap b = GameDiff.read("2.0", List.copyOf(newGame.values()), null, List.of());
        GameDiff.Hop hop = new GameDiff.Hop(a, b);
        hop.run();
        GameDiff.Learner learner = new GameDiff.Learner(hop);
        Map<String, String> learned = new TreeMap<>();
        List<String> lines = new ArrayList<>();
        // How the game reads Lst's removed fields in 2.0, as the learner gives them.
        String lst = "net.minecraft.s5.Lst";
        List<GameDiff.Learner.Learned> known = List.of(new GameDiff.Learner.Learned(lst, "top", "", true, false, "$0.getY()", 1, 1),
            new GameDiff.Learner.Learned(lst, "bottom", "", true, false, "$0.getBottom()", 1, 1),
            new GameDiff.Learner.Learned(lst, "wide", "", true, false, "$0.width", 1, 1));
        for (GameDiff.Learner.Learned l : new GameDiff.SameCall(hop, learner).learn(known)) {
            learned.put(l.owner() + "." + l.name() + l.desc(), l.expr());
            lines.add(l.json("1.0 -> 2.0"));
        }
        String s = "net.minecraft.s5.";
        check(learned, s + "Bio.temp(I)F", "$0.temp($1, 63)", "a parameter sitting where the old body had a fixed value is solved through the sum it is in");
        check(learned, s + "Bio.cold(I)Z", "$0.cold($1, 63)", "a parameter only handed on to a callee that changed the same way takes the callee's value");
        check(learned, s + "Ent.grief()Z", "$0.grief((net.minecraft.s5.SLvl) $0.level())", "a parameter where the old body read a value of its receiver takes that value, narrowed to the new type");
        check(learned, s + "Fin.id()I", "$0.id(null)", "a parameter the new body never reads, of a method nothing overrides, takes its type's zero");
        check(learned, s + "Base.size(I)I", null, "a method an override may change says nothing about what to pass");
        check(learned, s + "Lst.<init>(IIII)V", "new net.minecraft.s5.Lst($1, $4 - $3, $3)",
            "a constructor's new parameters are solved so every field the new release reads holds what the old one stored");
        check(learned, s + "Pt.<init>(I)V", null, "a parameter for a value the old object never held is not made up");
        check(learned, s + "Fol.<init>(II)V", null, "an argument is not dropped when the old game used what it set");

        Path map = scratch.resolve("route-samecall.json");
        Files.writeString(map, "{\"learned\": [" + String.join(",\n", lines) + "]}");
        String trace = run(a, b, map, newGame);
        JarPortTest.expect(trace.equals("1.00.5truetrue760"), "the old calls run on the new game as the old one behaved: " + trace);
        if (JarPortTest.failures > 0) {
            System.exit(1);
        }
        System.out.println("game-samecall: 9 checks passed");
    }

    static void check(Map<String, String> learned, String key, String want, String what) {
        JarPortTest.expect(Objects.equals(want, learned.get(key)), what + ": " + learned.get(key));
    }

    /**
     * m5.Run.go(bio, ent, fin): "" + bio.temp(100) + bio.temp(70) + bio.cold(70) + ent.grief() + fin.id() + new
     * m5.Rows(10, 100, 20, 80).rows(), Rows being the mod's own Lst whose constructor is super(wide, screen, top, bottom).
     */
    static String run(GameDiff.Snap a, GameDiff.Snap b, Path map, Map<String, byte[]> newGame) throws Exception {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m5/Run", null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "go", "(L" + U + "Bio;L" + U + "Ent;L" + U + "Fin;)Ljava/lang/String;", null, null);
        m.visitCode();
        m.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        m.visitInsn(Opcodes.DUP);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        for (int y : new int[] { 100, 70 }) {
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitIntInsn(Opcodes.BIPUSH, y);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Bio", "temp", "(I)F", false);
            append(m, "F");
        }
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitIntInsn(Opcodes.BIPUSH, 70);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Bio", "cold", "(I)Z", false);
        append(m, "Z");
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Ent", "grief", "()Z", false);
        append(m, "Z");
        m.visitVarInsn(Opcodes.ALOAD, 2);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Fin", "id", "()I", false);
        append(m, "I");
        m.visitTypeInsn(Opcodes.NEW, "m5/Rows");
        m.visitInsn(Opcodes.DUP);
        for (int v : new int[] { 10, 100, 20, 80 }) {
            m.visitIntInsn(Opcodes.BIPUSH, v);
        }
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "m5/Rows", "<init>", "(IIII)V", false);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "m5/Rows", "rows", "()I", false);
        append(m, "I");
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        List<Map<String, Object>> results = new ArrayList<>();
        ClassWriter r = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        r.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m5/Rows", null, U + "Lst", null);
        MethodVisitor k = r.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(IIII)V", null, null);
        k.visitCode();
        k.visitVarInsn(Opcodes.ALOAD, 0);
        for (int i = 1; i <= 4; i++) {
            k.visitVarInsn(Opcodes.ILOAD, i);
        }
        k.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "Lst", "<init>", "(IIII)V", false);
        k.visitInsn(Opcodes.RETURN);
        k.visitMaxs(0, 0);
        k.visitEnd();
        r.visitEnd();
        Map<String, byte[]> ported = JarPortTest.entries(new JarPort(a, b, map).port(JarPortTest.jar(Map.of("m5/Run.class", w.toByteArray(), "m5/Rows.class", r.toByteArray())),
            "samecall.jar", results));
        Map<String, byte[]> classes = new HashMap<>(newGame);
        classes.put("m5/Run", ported.get("m5/Run.class"));
        classes.put("m5/Rows", ported.get("m5/Rows.class"));
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
        try {
            Object bio = loader.loadClass("net.minecraft.s5.Bio").getConstructor().newInstance();
            Object ent = loader.loadClass("net.minecraft.s5.Ent").getConstructor().newInstance();
            Object fin = loader.loadClass("net.minecraft.s5.Fin").getConstructor().newInstance();
            return String.valueOf(loader.loadClass("m5.Run").getMethods()[0].invoke(null, bio, ent, fin));
        } catch (ReflectiveOperationException | LinkageError failed) {
            return "failed: " + (failed.getCause() == null ? failed : failed.getCause()) + " " + Json.array(results.get(0).get("unlinked"));
        }
    }

    static void append(MethodVisitor m, String desc) {
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(" + desc + ")Ljava/lang/StringBuilder;", false);
    }

    static Map<String, byte[]> game(boolean two) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        out.put(U + "Ctx", cls("Ctx", 0, null, w -> constructor(w, "java/lang/Object")));
        out.put(U + "Lvl", cls("Lvl", 0, null, w -> {
            constructor(w, "java/lang/Object");
            MethodVisitor r = w.visitMethod(Opcodes.ACC_PUBLIC, "rule", "()Z", null, null);
            r.visitCode();
            r.visitInsn(Opcodes.ICONST_1);
            r.visitInsn(Opcodes.IRETURN);
            r.visitMaxs(0, 0);
            r.visitEnd();
        }));
        out.put(U + "SLvl", cls("SLvl", 0, U + "Lvl", w -> constructor(w, U + "Lvl")));
        // Bio (final): temp(y) is y > 80 ? 1 : 0.5, and y > sea + 17 in 2.0; cold(y) is temp(y) < 0.6.
        out.put(U + "Bio", cls("Bio", Opcodes.ACC_FINAL, null, w -> {
            constructor(w, "java/lang/Object");
            MethodVisitor t = w.visitMethod(Opcodes.ACC_PUBLIC, "temp", two ? "(II)F" : "(I)F", null, null);
            t.visitCode();
            Label low = new Label();
            t.visitVarInsn(Opcodes.ILOAD, 1);
            if (two) {
                t.visitVarInsn(Opcodes.ILOAD, 2);
                t.visitIntInsn(Opcodes.BIPUSH, 17);
                t.visitInsn(Opcodes.IADD);
            } else {
                t.visitIntInsn(Opcodes.BIPUSH, 80);
            }
            t.visitJumpInsn(Opcodes.IF_ICMPLE, low);
            t.visitInsn(Opcodes.FCONST_1);
            t.visitInsn(Opcodes.FRETURN);
            t.visitLabel(low);
            t.visitLdcInsn(0.5F);
            t.visitInsn(Opcodes.FRETURN);
            t.visitMaxs(0, 0);
            t.visitEnd();
            MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "cold", two ? "(II)Z" : "(I)Z", null, null);
            c.visitCode();
            Label warm = new Label();
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitVarInsn(Opcodes.ILOAD, 1);
            if (two) {
                c.visitVarInsn(Opcodes.ILOAD, 2);
            }
            c.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Bio", "temp", two ? "(II)F" : "(I)F", false);
            c.visitLdcInsn(0.6F);
            c.visitInsn(Opcodes.FCMPG);
            c.visitJumpInsn(Opcodes.IFGE, warm);
            c.visitInsn(Opcodes.ICONST_1);
            c.visitInsn(Opcodes.IRETURN);
            c.visitLabel(warm);
            c.visitInsn(Opcodes.ICONST_0);
            c.visitInsn(Opcodes.IRETURN);
            c.visitMaxs(0, 0);
            c.visitEnd();
        }));
        // Ent: level() returns its SLvl as a Lvl; grief() is level().rule(), grief(level) is level.rule() in 2.0.
        out.put(U + "Ent", cls("Ent", 0, null, w -> {
            w.visitField(Opcodes.ACC_PRIVATE, "lvl", "L" + U + "Lvl;", null, null).visitEnd();
            MethodVisitor k = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            k.visitCode();
            k.visitVarInsn(Opcodes.ALOAD, 0);
            k.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            k.visitVarInsn(Opcodes.ALOAD, 0);
            k.visitTypeInsn(Opcodes.NEW, U + "SLvl");
            k.visitInsn(Opcodes.DUP);
            k.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "SLvl", "<init>", "()V", false);
            k.visitFieldInsn(Opcodes.PUTFIELD, U + "Ent", "lvl", "L" + U + "Lvl;");
            k.visitInsn(Opcodes.RETURN);
            k.visitMaxs(0, 0);
            k.visitEnd();
            MethodVisitor l = w.visitMethod(Opcodes.ACC_PUBLIC, "level", "()L" + U + "Lvl;", null, null);
            l.visitCode();
            l.visitVarInsn(Opcodes.ALOAD, 0);
            l.visitFieldInsn(Opcodes.GETFIELD, U + "Ent", "lvl", "L" + U + "Lvl;");
            l.visitInsn(Opcodes.ARETURN);
            l.visitMaxs(0, 0);
            l.visitEnd();
            MethodVisitor g = w.visitMethod(Opcodes.ACC_PUBLIC, "grief", two ? "(L" + U + "SLvl;)Z" : "()Z", null, null);
            g.visitCode();
            if (two) {
                g.visitVarInsn(Opcodes.ALOAD, 1);
            } else {
                g.visitVarInsn(Opcodes.ALOAD, 0);
                g.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Ent", "level", "()L" + U + "Lvl;", false);
            }
            g.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Lvl", "rule", "()Z", false);
            g.visitInsn(Opcodes.IRETURN);
            g.visitMaxs(0, 0);
            g.visitEnd();
        }));
        // Fin (final): id() is 7, id(ctx) too. Base.size(i) is 1, size(i, ctx) too, and Sub overrides it with 2.
        out.put(U + "Fin", cls("Fin", Opcodes.ACC_FINAL, null, w -> {
            constructor(w, "java/lang/Object");
            constant(w, "id", two ? "(" + CTX + ")I" : "()I", 7);
        }));
        out.put(U + "Base", cls("Base", 0, null, w -> {
            constructor(w, "java/lang/Object");
            constant(w, "size", two ? "(I" + CTX + ")I" : "(I)I", 1);
        }));
        out.put(U + "Sub", cls("Sub", 0, U + "Base", w -> {
            constructor(w, U + "Base");
            constant(w, "size", two ? "(I" + CTX + ")I" : "(I)I", 2);
        }));
        // Lst 1.0: Lst(wide, screen, top, bottom) keeps all four, rows() is bottom - top. 2.0: Pane(x, y, width, height)
        // holds them, getBottom() is getY() + height; Lst(wide, height, y) is super(0, y, wide, height), rows() getBottom() - getY().
        if (two) {
            out.put(U + "Pane", cls("Pane", 0, null, w -> {
                for (String f : new String[] { "y", "width", "height" }) {
                    w.visitField(Opcodes.ACC_PUBLIC, f, "I", null, null).visitEnd();
                }
                MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(IIII)V", null, null);
                c.visitCode();
                c.visitVarInsn(Opcodes.ALOAD, 0);
                c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                put(c, U + "Pane", "y", 2);
                put(c, U + "Pane", "width", 3);
                put(c, U + "Pane", "height", 4);
                c.visitInsn(Opcodes.RETURN);
                c.visitMaxs(0, 0);
                c.visitEnd();
                MethodVisitor g = w.visitMethod(Opcodes.ACC_PUBLIC, "getY", "()I", null, null);
                g.visitCode();
                g.visitVarInsn(Opcodes.ALOAD, 0);
                g.visitFieldInsn(Opcodes.GETFIELD, U + "Pane", "y", "I");
                g.visitInsn(Opcodes.IRETURN);
                g.visitMaxs(0, 0);
                g.visitEnd();
                MethodVisitor b = w.visitMethod(Opcodes.ACC_PUBLIC, "getBottom", "()I", null, null);
                b.visitCode();
                b.visitVarInsn(Opcodes.ALOAD, 0);
                b.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Pane", "getY", "()I", false);
                b.visitVarInsn(Opcodes.ALOAD, 0);
                b.visitFieldInsn(Opcodes.GETFIELD, U + "Pane", "height", "I");
                b.visitInsn(Opcodes.IADD);
                b.visitInsn(Opcodes.IRETURN);
                b.visitMaxs(0, 0);
                b.visitEnd();
            }));
        }
        String[] fields = { "wide", "screen", "top", "bottom" };
        out.put(U + "Lst", cls("Lst", 0, two ? U + "Pane" : null, w -> {
            if (!two) {
                for (String f : fields) {
                    w.visitField(Opcodes.ACC_PUBLIC, f, "I", null, null).visitEnd();
                }
            }
            MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", two ? "(III)V" : "(IIII)V", null, null);
            c.visitCode();
            c.visitVarInsn(Opcodes.ALOAD, 0);
            if (two) {
                c.visitInsn(Opcodes.ICONST_0);
                c.visitVarInsn(Opcodes.ILOAD, 3);
                c.visitVarInsn(Opcodes.ILOAD, 1);
                c.visitVarInsn(Opcodes.ILOAD, 2);
                c.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "Pane", "<init>", "(IIII)V", false);
            } else {
                c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                for (int i = 0; i < 4; i++) {
                    put(c, U + "Lst", fields[i], i + 1);
                }
            }
            c.visitInsn(Opcodes.RETURN);
            c.visitMaxs(0, 0);
            c.visitEnd();
            MethodVisitor rows = w.visitMethod(Opcodes.ACC_PUBLIC, "rows", "()I", null, null);
            rows.visitCode();
            rows.visitVarInsn(Opcodes.ALOAD, 0);
            if (two) {
                rows.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Lst", "getBottom", "()I", false);
                rows.visitVarInsn(Opcodes.ALOAD, 0);
                rows.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Lst", "getY", "()I", false);
            } else {
                rows.visitFieldInsn(Opcodes.GETFIELD, U + "Lst", "bottom", "I");
                rows.visitVarInsn(Opcodes.ALOAD, 0);
                rows.visitFieldInsn(Opcodes.GETFIELD, U + "Lst", "top", "I");
            }
            rows.visitInsn(Opcodes.ISUB);
            rows.visitInsn(Opcodes.IRETURN);
            rows.visitMaxs(0, 0);
            rows.visitEnd();
        }));
        // Pt 1.0: Pt(a) keeps a. 2.0: Pt(a, c) keeps both.
        out.put(U + "Pt", cls("Pt", 0, null, w -> {
            w.visitField(Opcodes.ACC_PUBLIC, "a", "I", null, null).visitEnd();
            if (two) {
                w.visitField(Opcodes.ACC_PUBLIC, "c", "I", null, null).visitEnd();
            }
            MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", two ? "(II)V" : "(I)V", null, null);
            c.visitCode();
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            put(c, U + "Pt", "a", 1);
            if (two) {
                put(c, U + "Pt", "c", 2);
            }
            c.visitInsn(Opcodes.RETURN);
            c.visitMaxs(0, 0);
            c.visitEnd();
        }));
        // Fol 1.0: Fol(r, spread) keeps both, size() is r + spread. 2.0: Fol(r) keeps r, size() is r.
        out.put(U + "Fol", cls("Fol", 0, null, w -> {
            w.visitField(Opcodes.ACC_PUBLIC, "r", "I", null, null).visitEnd();
            if (!two) {
                w.visitField(Opcodes.ACC_PUBLIC, "spread", "I", null, null).visitEnd();
            }
            MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", two ? "(I)V" : "(II)V", null, null);
            c.visitCode();
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            put(c, U + "Fol", "r", 1);
            if (!two) {
                put(c, U + "Fol", "spread", 2);
            }
            c.visitInsn(Opcodes.RETURN);
            c.visitMaxs(0, 0);
            c.visitEnd();
            MethodVisitor z = w.visitMethod(Opcodes.ACC_PUBLIC, "size", "()I", null, null);
            z.visitCode();
            z.visitVarInsn(Opcodes.ALOAD, 0);
            z.visitFieldInsn(Opcodes.GETFIELD, U + "Fol", "r", "I");
            if (!two) {
                z.visitVarInsn(Opcodes.ALOAD, 0);
                z.visitFieldInsn(Opcodes.GETFIELD, U + "Fol", "spread", "I");
                z.visitInsn(Opcodes.IADD);
            }
            z.visitInsn(Opcodes.IRETURN);
            z.visitMaxs(0, 0);
            z.visitEnd();
        }));
        return out;
    }

    /** this.field = the int parameter in slot. */
    static void put(MethodVisitor c, String owner, String field, int slot) {
        c.visitVarInsn(Opcodes.ALOAD, 0);
        c.visitVarInsn(Opcodes.ILOAD, slot);
        c.visitFieldInsn(Opcodes.PUTFIELD, owner, field, "I");
    }

    static void constant(ClassWriter w, String name, String desc, int value) {
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
        m.visitCode();
        m.visitIntInsn(Opcodes.BIPUSH, value);
        m.visitInsn(Opcodes.IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    static void constructor(ClassWriter w, String sup) {
        MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        c.visitCode();
        c.visitVarInsn(Opcodes.ALOAD, 0);
        c.visitMethodInsn(Opcodes.INVOKESPECIAL, sup, "<init>", "()V", false);
        c.visitInsn(Opcodes.RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();
    }

    static byte[] cls(String simple, int flags, String sup, java.util.function.Consumer<ClassWriter> body) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String x, String y) {
                return "java/lang/Object";
            }
        };
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | flags, U + simple, null, sup == null ? "java/lang/Object" : sup, null);
        body.accept(w);
        w.visitEnd();
        return w.toByteArray();
    }
}

import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * A member whose operand picks what it does, on a two-release game built here. 1.0: new Ev(kind, s) for Kind's constants
 * SAY, COPY, OPEN and URL, and new Tip(kind, value) with an Object value. 2.0: Ev and Tip are interfaces with one class per
 * kind (Ev.Say(String), Ev.Copy(String), Ev.Open(String), Ev.Url(URI); Tip.Say(String), Tip.Copy(String)), and each of
 * Kind's constants is built with its Ev class's CODEC. The game's own code wrote new Ev(SAY, s) as new Ev.Say(s) at 4
 * places and new Ev(COPY, s) as new Ev.Copy(s) at 1: one reading for all would be wrong for COPY although 4 of 5 agree,
 * so each constant keeps its own. OPEN and URL, which no game code passes, are read from Kind's own constants: Ev.Open's
 * constructor that takes the String as it is (not its File one), and Ev.Url's URI is URI.create(s) (new URI(s) throws a checked exception the old call did
 * not). Tip's value is cast to the String the game passed. A mod passing the constant gets its own class; a mod passing a
 * kind it computed keeps the old call, reported.
 *
 *   java -cp asm.jar:CLASSES GameConstantTest SCRATCH   (in the jar-port check of bench/run_tests.py)
 */
final class GameConstantTest {

    static final String U = "net/minecraft/c9/", STR = "Ljava/lang/String;", OBJ = "Ljava/lang/Object;", KIND = "L" + U + "Kind;";
    static final String[] KINDS = { "SAY", "COPY", "OPEN", "URL" };

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        Map<String, byte[]> oldGame = game(false), newGame = game(true);
        GameDiff.Snap a = GameDiff.read("1.0", List.copyOf(oldGame.values()), null, List.of());
        GameDiff.Snap b = GameDiff.read("2.0", List.copyOf(newGame.values()), null, List.of());
        GameHop hop = new GameHop(a, b);
        hop.run();
        Map<String, String> learned = new TreeMap<>();
        List<String> lines = new ArrayList<>();
        for (GameLearner.Learned l : new GameLearner(hop).learn()) {
            learned.put(l.name() + l.desc().substring(0, l.desc().indexOf(')') + 1) + (l.when() == null ? "" : " " + l.when()) + " " + l.owner().substring(l.owner().lastIndexOf('.') + 1), l.expr());
            lines.add(l.json("1.0 -> 2.0"));
        }
        String y = "net.minecraft.c9.", ev = "<init>(" + KIND + STR + ")", tip = "<init>(" + KIND + OBJ + ")";
        JarPortTest.expect(("new " + y + "Ev$Say($2)").equals(learned.get(ev + " 1:SAY Ev")) && ("new " + y + "Ev$Copy($2)").equals(learned.get(ev + " 1:COPY Ev")),
            "each constant keeps the reading its own uses show: " + learned);
        JarPortTest.expect(!learned.containsKey(ev + " Ev"), "4 uses of one constant do not outvote another's: " + learned.get(ev + " Ev"));
        JarPortTest.expect(("new " + y + "Ev$Open($2)").equals(learned.get(ev + " 1:OPEN Ev")),
            "a constant no use passes is read from the class the new release builds it with: " + learned.get(ev + " 1:OPEN Ev"));
        JarPortTest.expect(("new " + y + "Ev$Url(java.net.URI.create($2))").equals(learned.get(ev + " 1:URL Ev")),
            "a JDK value is made by its one maker with no checked exception: " + learned.get(ev + " 1:URL Ev"));
        JarPortTest.expect(("new " + y + "Tip$Say((java.lang.String) $2)").equals(learned.get(tip + " 1:SAY Tip")),
            "a value declared wider is cast to the type the game passed: " + learned.get(tip + " 1:SAY Tip"));
        JarPortTest.expect(!learned.containsKey(tip + " 1:OPEN Tip"), "a constant built with another type's class says nothing for this one: " + learned.get(tip + " 1:OPEN Tip"));

        Path map = scratch.resolve("route-constant.json");
        Files.writeString(map, "{\"learned\": [" + String.join(",\n", lines) + "]}");
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(new JarPort(a, b, map).port(JarPortTest.jar(Map.of("m9/Run.class", run(), "m9/Dyn.class", dyn())), "constant.jar", results));
        List<String> unlinked = Json.array(results.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        Map<String, byte[]> classes = new HashMap<>(newGame);
        classes.put("m9/Run", ported.get("m9/Run.class"));
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
            trace = String.valueOf(loader.loadClass("m9.Run").getMethod("go").invoke(null));
        } catch (ReflectiveOperationException | LinkageError failed) {
            trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause()) + " " + unlinked;
        }
        JarPortTest.expect(trace.equals("say:hi url:http://x open:f copy:t say:p"), "each of the mod's calls makes its constant's class on the new game: " + trace);
        JarPortTest.expect(unlinked.size() == 2 && unlinked.contains("new of a class the new release cannot make: net.minecraft.c9.Ev (in m9.Dyn.go)"),
            "a kind the mod computed names no constant: the call stays and is reported, with the class it cannot make: " + unlinked);
        if (JarPortTest.failures > 0) {
            System.exit(1);
        }
        System.out.println("game-constant: 8 checks passed");
    }

    /**
     * m9/Run.go(): each made object's text, space-separated: Ev(SAY, "hi"), Ev(URL, "http://x"), Ev(OPEN, "f"), Tip(COPY,
     * "t"), and Tip(SAY, true ? "p" : "q"), whose branch puts frames between new and the constructor.
     */
    static byte[] run() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String x, String y) {
                return "java/lang/Object";
            }
        };
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m9/Run", null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "go", "()" + STR, null, null);
        m.visitCode();
        m.visitTypeInsn(Opcodes.NEW, "java/util/StringJoiner");
        m.visitInsn(Opcodes.DUP);
        m.visitLdcInsn(" ");
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/StringJoiner", "<init>", "(Ljava/lang/CharSequence;)V", false);
        String[][] made = { { "Ev", "SAY", "hi" }, { "Ev", "URL", "http://x" }, { "Ev", "OPEN", "f" }, { "Tip", "COPY", "t" } };
        for (String[] one : made) {
            m.visitTypeInsn(Opcodes.NEW, U + one[0]);
            m.visitInsn(Opcodes.DUP);
            m.visitFieldInsn(Opcodes.GETSTATIC, U + "Kind", one[1], KIND);
            m.visitLdcInsn(one[2]);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, U + one[0], "<init>", "(" + KIND + (one[0].equals("Ev") ? STR : OBJ) + ")V", false);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(" + OBJ + ")" + STR, false);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/StringJoiner", "add", "(Ljava/lang/CharSequence;)Ljava/util/StringJoiner;", false);
        }
        Label q = new Label(), joined = new Label();
        m.visitTypeInsn(Opcodes.NEW, U + "Tip");
        m.visitInsn(Opcodes.DUP);
        m.visitFieldInsn(Opcodes.GETSTATIC, U + "Kind", "SAY", KIND);
        m.visitInsn(Opcodes.ICONST_1);
        m.visitJumpInsn(Opcodes.IFEQ, q);
        m.visitLdcInsn("p");
        m.visitJumpInsn(Opcodes.GOTO, joined);
        m.visitLabel(q);
        m.visitLdcInsn("q");
        m.visitLabel(joined);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "Tip", "<init>", "(" + KIND + OBJ + ")V", false);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(" + OBJ + ")" + STR, false);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/StringJoiner", "add", "(Ljava/lang/CharSequence;)Ljava/util/StringJoiner;", false);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/StringJoiner", "toString", "()" + STR, false);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** m9/Dyn.go(kind): new Ev(kind, "x"), the kind computed elsewhere. */
    static byte[] dyn() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m9/Dyn", null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "go", "(" + KIND + ")" + OBJ, null, null);
        m.visitCode();
        m.visitTypeInsn(Opcodes.NEW, U + "Ev");
        m.visitInsn(Opcodes.DUP);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitLdcInsn("x");
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "Ev", "<init>", "(" + KIND + STR + ")V", false);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /**
     * 1.0: Kind with constants SAY, COPY, OPEN, URL; Ev(Kind, String) and Tip(Kind, Object), each keeping its value; User's
     * e1..e4 make Ev(SAY, s), e5 Ev(COPY, s), t1 and t2 Tip(SAY, s), t3 Tip(COPY, s), s a String parameter. 2.0: Ev and Tip
     * are interfaces; Ev$Say, Ev$Copy, Ev$Open take a String, Ev$Url a URI, Tip$Say and Tip$Copy a String, each with a
     * static CODEC and a toString of "kind:value"; Kind's constants are built with Ev$Say.CODEC, ..., Ev$Url.CODEC; User's
     * methods make the classes directly.
     */
    static Map<String, byte[]> game(boolean two) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        out.put(U + "Kind", cls("Kind", 0, null, w -> {
            for (String k : KINDS) {
                w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, k, KIND, null, null).visitEnd();
            }
            MethodVisitor c = w.visitMethod(Opcodes.ACC_PRIVATE, "<init>", two ? "(" + OBJ + ")V" : "()V", null, null);
            c.visitCode();
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            c.visitInsn(Opcodes.RETURN);
            c.visitMaxs(0, 0);
            c.visitEnd();
            MethodVisitor s = w.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            s.visitCode();
            for (String k : KINDS) {
                s.visitTypeInsn(Opcodes.NEW, U + "Kind");
                s.visitInsn(Opcodes.DUP);
                if (two) {
                    s.visitFieldInsn(Opcodes.GETSTATIC, U + "Ev$" + title(k), "CODEC", OBJ);
                }
                s.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "Kind", "<init>", two ? "(" + OBJ + ")V" : "()V", false);
                s.visitFieldInsn(Opcodes.PUTSTATIC, U + "Kind", k, KIND);
            }
            s.visitInsn(Opcodes.RETURN);
            s.visitMaxs(0, 0);
            s.visitEnd();
        }));
        if (!two) {
            for (String holder : new String[] { "Ev", "Tip" }) {
                String value = holder.equals("Ev") ? STR : OBJ;
                out.put(U + holder, cls(holder, 0, null, w -> {
                    w.visitField(Opcodes.ACC_PUBLIC, "value", value, null, null).visitEnd();
                    MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + KIND + value + ")V", null, null);
                    c.visitCode();
                    c.visitVarInsn(Opcodes.ALOAD, 0);
                    c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                    c.visitVarInsn(Opcodes.ALOAD, 0);
                    c.visitVarInsn(Opcodes.ALOAD, 2);
                    c.visitFieldInsn(Opcodes.PUTFIELD, U + holder, "value", value);
                    c.visitInsn(Opcodes.RETURN);
                    c.visitMaxs(0, 0);
                    c.visitEnd();
                }));
            }
        } else {
            out.put(U + "Ev", cls("Ev", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null, w -> {}));
            out.put(U + "Tip", cls("Tip", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null, w -> {}));
            for (String k : KINDS) {
                out.put(U + "Ev$" + title(k), kind("Ev", title(k), k.equals("URL") ? "Ljava/net/URI;" : STR));
            }
            out.put(U + "Tip$Say", kind("Tip", "Say", STR));
            out.put(U + "Tip$Copy", kind("Tip", "Copy", STR));
        }
        out.put(U + "User", cls("User", 0, null, w -> {
            String[][] uses = { { "e1", "Ev", "SAY" }, { "e2", "Ev", "SAY" }, { "e3", "Ev", "SAY" }, { "e4", "Ev", "SAY" }, { "e5", "Ev", "COPY" },
                { "t1", "Tip", "SAY" }, { "t2", "Tip", "SAY" }, { "t3", "Tip", "COPY" } };
            for (String[] u : uses) {
                MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, u[0], "(" + STR + ")" + OBJ, null, null);
                m.visitCode();
                String made = two ? U + u[1] + "$" + title(u[2]) : U + u[1];
                m.visitTypeInsn(Opcodes.NEW, made);
                m.visitInsn(Opcodes.DUP);
                if (!two) {
                    m.visitFieldInsn(Opcodes.GETSTATIC, U + "Kind", u[2], KIND);
                }
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitMethodInsn(Opcodes.INVOKESPECIAL, made, "<init>", two ? "(" + STR + ")V" : "(" + KIND + (u[1].equals("Ev") ? STR : OBJ) + ")V", false);
                m.visitInsn(Opcodes.ARETURN);
                m.visitMaxs(0, 0);
                m.visitEnd();
            }
        }));
        return out;
    }

    /** holder$name implementing holder: a public constructor of one value, a static CODEC and a toString of "name:value". */
    static byte[] kind(String holder, String name, String value) {
        String me = U + holder + "$" + name;
        return cls(holder + "$" + name, 0, U + holder, w -> {
            w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "CODEC", OBJ, null, null).visitEnd();
            w.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "value", value, null, null).visitEnd();
            MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + value + ")V", null, null);
            c.visitCode();
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitVarInsn(Opcodes.ALOAD, 1);
            c.visitFieldInsn(Opcodes.PUTFIELD, me, "value", value);
            c.visitInsn(Opcodes.RETURN);
            c.visitMaxs(0, 0);
            c.visitEnd();
            if (name.equals("Open")) {
                // A second way in, from a File: the one taking the String as it is is the class's form for it.
                MethodVisitor f = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/io/File;)V", null, null);
                f.visitCode();
                f.visitVarInsn(Opcodes.ALOAD, 0);
                f.visitVarInsn(Opcodes.ALOAD, 1);
                f.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/File", "getPath", "()" + STR, false);
                f.visitMethodInsn(Opcodes.INVOKESPECIAL, me, "<init>", "(" + STR + ")V", false);
                f.visitInsn(Opcodes.RETURN);
                f.visitMaxs(0, 0);
                f.visitEnd();
            }
            MethodVisitor t = w.visitMethod(Opcodes.ACC_PUBLIC, "toString", "()" + STR, null, null);
            t.visitCode();
            t.visitLdcInsn(name.toLowerCase(Locale.ROOT) + ":");
            t.visitVarInsn(Opcodes.ALOAD, 0);
            t.visitFieldInsn(Opcodes.GETFIELD, me, "value", value);
            t.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(" + OBJ + ")" + STR, false);
            t.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(" + STR + ")" + STR, false);
            t.visitInsn(Opcodes.ARETURN);
            t.visitMaxs(0, 0);
            t.visitEnd();
            MethodVisitor s = w.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            s.visitCode();
            s.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
            s.visitInsn(Opcodes.DUP);
            s.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            s.visitFieldInsn(Opcodes.PUTSTATIC, me, "CODEC", OBJ);
            s.visitInsn(Opcodes.RETURN);
            s.visitMaxs(0, 0);
            s.visitEnd();
        });
    }

    static String title(String constant) {
        return constant.charAt(0) + constant.substring(1).toLowerCase(Locale.ROOT);
    }

    static byte[] cls(String simple, int flags, String iface, java.util.function.Consumer<ClassWriter> body) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String x, String y) {
                return "java/lang/Object";
            }
        };
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | flags, U + simple, null, "java/lang/Object", iface == null ? null : new String[] { iface });
        body.accept(w);
        w.visitEnd();
        return w.toByteArray();
    }
}

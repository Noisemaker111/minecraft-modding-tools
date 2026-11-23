import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * Readings the game's own uses show only once uses that say nothing about them stand aside, on a two-release game built
 * here like 1.21.5's tags. 1.0: Tag.getText(key) gives the text or "", getNum(key) the number or 0, getTag(key) and
 * getSub(key) a tag or an empty one, getList(key, type) the tag's list, or an empty one where it is absent or a nonempty
 * list of another element type; Ver.pack() an int.
 * 2.0: those give Optionals, and getTextOr(key, d), getNumOr(key, d), getTagOrEmpty(key), getSubOrEmpty(key) and
 * getListOrEmpty(key) give what the old ones did, but getListOrEmpty gives the list whatever its element type; Ver.pack()
 * gives a Fmt record, whose one factory from an int,
 * Fmt.of(n), is new Fmt(n, 0).
 * - getText: three uses read getTextOr(key, ""); one now reads a whole Pos where the old code read the text (another
 *   value, unrelated in type), which says nothing about the call.
 * - getNum: three read getNumOr(key, 0.0F); one passes a default of its own place, Defaults' private PUSH.x, which
 *   cannot be written over the old operands but is the same call with a fixed value.
 * - getTag: three read getTagOrEmpty(key); one insists, getTag(key).orElseThrow(), where the game knows the tag is there.
 * - getSub: eight read getSubOrEmpty(key), and two nest getSub(a).getSub(b): getSub is not a setter gone into a factory
 *   (it has a successor), so its own reading stays.
 * - getList: types 6, 8 and 10 all read getListOrEmpty(key), but the old body compared the type to choose what it gave:
 *   dropping it would hand a mod a list of the wrong type, so the call is left unported.
 * - Ver.pack(): no use; pack().major(), the component Fmt.of fills.
 * Uses that stand aside carry a reading only so far: getPair(key, fallback), whose old body passes the fallback on, reads
 * getPairOr(key, "") at three uses with one aside, and leaves the fallback out, so nothing is learned; getOne(key) reads
 * getOneOr(key) at two with one aside, too few to agree.
 * Each alone falls short of four in five, or is dropped: 1.21.5's getString, getFloat, getCompound and getList, and
 * 1.21.9's packVersion. A mod calling each runs on the new game as before, and its getList(key, 10) on a list of 10s, a
 * list of 8s and no list either gives what it gave on the old game or is reported unlinked.
 *
 *   java -cp asm.jar:CLASSES GameReadTest SCRATCH   (in the jar-port check of bench/run_tests.py)
 */
final class GameReadTest {

    static final String U = "net/minecraft/r5/", STR = "Ljava/lang/String;", OPT = "Ljava/util/Optional;", TAG = "L" + U + "Tag;", LST = "L" + U + "Lst;";

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
            learned.put(l.owner().substring(l.owner().lastIndexOf('.') + 1) + "." + l.name(), l.expr());
            lines.add(l.json("1.0 -> 2.0"));
        }
        JarPortTest.expect("$0.getTextOr($1, \"\")".equals(learned.get("Tag.getText")), "a use now reading another value stands aside: " + learned.get("Tag.getText"));
        JarPortTest.expect("$0.getNumOr($1, 0.0F)".equals(learned.get("Tag.getNum")), "the same call with a default of its own place stands aside: " + learned.get("Tag.getNum"));
        JarPortTest.expect("$0.getTagOrEmpty($1)".equals(learned.get("Tag.getTag")), "a use insisting on the value stands aside: " + learned.get("Tag.getTag"));
        JarPortTest.expect("$0.getSubOrEmpty($1)".equals(learned.get("Tag.getSub")), "a call with a successor nested in itself keeps its own reading: " + learned.get("Tag.getSub"));
        JarPortTest.expect(!learned.containsKey("Tag.getList"), "an operand the old body tested to choose what it gave is not dropped: " + learned.get("Tag.getList"));
        JarPortTest.expect("$0.pack().major()".equals(learned.get("Ver.pack")), "a plain result wrapped in a record is read back through the part its factory fills: " + learned.get("Ver.pack"));
        JarPortTest.expect(!learned.containsKey("Tag.getPair"), "uses standing aside do not carry a reading that leaves out an operand: " + learned.get("Tag.getPair"));
        JarPortTest.expect(!learned.containsKey("Tag.getOne"), "uses standing aside do not carry a reading two uses show: " + learned.get("Tag.getOne"));

        Path map = scratch.resolve("route-read.json");
        Files.writeString(map, "{\"learned\": [" + String.join(",\n", lines) + "]}");
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(new JarPort(a, b, map).port(JarPortTest.jar(Map.of("m5/Run.class", run())), "read.jar", results));
        Map<String, byte[]> classes = new HashMap<>(newGame);
        classes.put("m5/Run", ported.get("m5/Run.class"));
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
            trace = String.valueOf(loader.loadClass("m5.Run").getMethod("go").invoke(null));
        } catch (ReflectiveOperationException | LinkageError failed) {
            trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause()) + " " + Json.array(results.get(0).get("unlinked"));
        }
        JarPortTest.expect(trace.equals("[]0.0 tag tag 7"), "the mod's reads run on the new game as before: " + trace);

        // getList(key, 10) on the old game and, ported, on the new one: a list of 10s, a list of 8s, no list.
        List<Map<String, Object>> listed = new ArrayList<>();
        Map<String, byte[]> classesB = new HashMap<>(newGame);
        classesB.put("m5/Lists", JarPortTest.entries(new JarPort(a, b, map).port(JarPortTest.jar(Map.of("m5/Lists.class", lists())), "lists.jar", listed)).get("m5/Lists.class"));
        Map<String, byte[]> classesA = new HashMap<>(oldGame);
        classesA.put("m5/Lists", lists());
        boolean reported = Json.array(listed.get(0).get("unlinked")).stream().anyMatch(u -> String.valueOf(u).contains("Tag.getList"));
        for (int[] list : new int[][] { { 10, 2 }, { 8, 2 }, null }) {
            String was = sizeRead(classesA, list), now = sizeRead(classesB, list);
            String state = list == null ? "no list" : "a list of " + list[1] + " tags of type " + list[0];
            JarPortTest.expect(was.equals(now) || now.startsWith("failed") && reported, "getList(\"l\", 10) on " + state + " gives " + was + " on the old game, " + now + " ported");
        }
        if (JarPortTest.failures > 0) {
            System.exit(1);
        }
        System.out.println("game-read: 12 checks passed");
    }

    /** m5.Lists.size(tag) on a Tag of game classes whose list is list (type, size), or none: the size, or how it failed. */
    static String sizeRead(Map<String, byte[]> classes, int[] list) throws Exception {
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
        Class<?> tag = loader.loadClass(U.replace('/', '.') + "Tag"), lst = loader.loadClass(U.replace('/', '.') + "Lst");
        Object t = tag.getConstructor().newInstance();
        if (list != null) {
            tag.getField("list").set(t, lst.getConstructor(int.class, int.class).newInstance(list[0], list[1]));
        }
        try {
            return String.valueOf(loader.loadClass("m5.Lists").getMethod("size", tag).invoke(null, t));
        } catch (ReflectiveOperationException | LinkageError failed) {
            return "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
    }

    /** m5/Lists.size(t): t.getList("l", 10).size, a mod asking for a list of compound tags. */
    static byte[] lists() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m5/Lists", null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "size", "(" + TAG + ")I", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitLdcInsn("l");
        m.visitIntInsn(Opcodes.BIPUSH, 10);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Tag", "getList", "(" + STR + "I)" + LST, false);
        m.visitFieldInsn(Opcodes.GETFIELD, U + "Lst", "size", "I");
        m.visitInsn(Opcodes.IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** m5/Run.go(): "[" + getText("x") + "]" + getNum("y") + " " + getTag("z") + " " + getSub("s") + " " + new Ver().pack(), on an empty Tag. */
    static byte[] run() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m5/Run", null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "go", "()" + STR, null, null);
        m.visitCode();
        m.visitTypeInsn(Opcodes.NEW, U + "Tag");
        m.visitInsn(Opcodes.DUP);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "Tag", "<init>", "()V", false);
        m.visitVarInsn(Opcodes.ASTORE, 0);
        m.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        m.visitInsn(Opcodes.DUP);
        m.visitLdcInsn("[");
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(" + STR + ")V", false);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitLdcInsn("x");
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Tag", "getText", "(" + STR + ")" + STR, false);
        append(m, STR);
        m.visitLdcInsn("]");
        append(m, STR);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitLdcInsn("y");
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Tag", "getNum", "(" + STR + ")F", false);
        append(m, "F");
        for (String[] c : new String[][] { { "getTag", "z" }, { "getSub", "s" } }) {
            m.visitLdcInsn(" ");
            append(m, STR);
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitLdcInsn(c[1]);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Tag", c[0], "(" + STR + ")" + TAG, false);
            append(m, "Ljava/lang/Object;");
        }
        m.visitLdcInsn(" ");
        append(m, STR);
        m.visitTypeInsn(Opcodes.NEW, U + "Ver");
        m.visitInsn(Opcodes.DUP);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "Ver", "<init>", "()V", false);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Ver", "pack", "()I", false);
        append(m, "I");
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()" + STR, false);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    static void append(MethodVisitor m, String type) {
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(" + type + ")Ljava/lang/StringBuilder;", false);
    }

    /** The game of the class comment; User's methods are its own uses. */
    static Map<String, byte[]> game(boolean two) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        out.put(U + "Lst", cls("Lst", "java/lang/Object", w -> {
            // Lst(type, size): a list of size tags of element type type; Lst() an empty one.
            ctor(w, "java/lang/Object");
            for (String part : new String[] { "type", "size" }) {
                w.visitField(Opcodes.ACC_PUBLIC, part, "I", null, null).visitEnd();
            }
            MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(II)V", null, null);
            c.visitCode();
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitVarInsn(Opcodes.ILOAD, 1);
            c.visitFieldInsn(Opcodes.PUTFIELD, U + "Lst", "type", "I");
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitVarInsn(Opcodes.ILOAD, 2);
            c.visitFieldInsn(Opcodes.PUTFIELD, U + "Lst", "size", "I");
            c.visitInsn(Opcodes.RETURN);
            c.visitMaxs(0, 0);
            c.visitEnd();
            str(w, "lst");
        }));
        out.put(U + "Pos", cls("Pos", "java/lang/Object", w -> ctor(w, "java/lang/Object")));
        out.put(U + "Vec", cls("Vec", "java/lang/Object", w -> {
            w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "x", "F", null, null).visitEnd();
            MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(F)V", null, null);
            c.visitCode();
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitVarInsn(Opcodes.FLOAD, 1);
            c.visitFieldInsn(Opcodes.PUTFIELD, U + "Vec", "x", "F");
            c.visitInsn(Opcodes.RETURN);
            c.visitMaxs(0, 0);
            c.visitEnd();
        }));
        out.put(U + "Defaults", cls("Defaults", "java/lang/Object", w -> {
            w.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "PUSH", "L" + U + "Vec;", null, null).visitEnd();
            // Its own use of getNum, with its own private default: a reading cannot name that field.
            use(w, "num3", "F", m -> {
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitLdcInsn("n3");
                if (two) {
                    m.visitFieldInsn(Opcodes.GETSTATIC, U + "Defaults", "PUSH", "L" + U + "Vec;");
                    m.visitFieldInsn(Opcodes.GETFIELD, U + "Vec", "x", "F");
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Tag", "getNumOr", "(" + STR + "F)F", false);
                } else {
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Tag", "getNum", "(" + STR + ")F", false);
                }
            }, Opcodes.FRETURN);
            MethodVisitor s = w.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            s.visitCode();
            s.visitTypeInsn(Opcodes.NEW, U + "Vec");
            s.visitInsn(Opcodes.DUP);
            s.visitLdcInsn(0.5F);
            s.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "Vec", "<init>", "(F)V", false);
            s.visitFieldInsn(Opcodes.PUTSTATIC, U + "Defaults", "PUSH", "L" + U + "Vec;");
            s.visitInsn(Opcodes.RETURN);
            s.visitMaxs(0, 0);
            s.visitEnd();
        }));
        out.put(U + "Tag", cls("Tag", "java/lang/Object", w -> {
            ctor(w, "java/lang/Object");
            str(w, "tag");
            w.visitField(Opcodes.ACC_PUBLIC, "list", LST, null, null).visitEnd(); // the list under the one key, null if absent
            if (!two) {
                body(w, "getText", "(" + STR + ")" + STR, m -> m.visitLdcInsn(""), Opcodes.ARETURN);
                body(w, "getPair", "(" + STR + STR + ")" + STR, m -> m.visitVarInsn(Opcodes.ALOAD, 2), Opcodes.ARETURN);
                body(w, "getOne", "(" + STR + ")" + STR, m -> m.visitLdcInsn("one"), Opcodes.ARETURN);
                body(w, "getNum", "(" + STR + ")F", m -> m.visitInsn(Opcodes.FCONST_0), Opcodes.FRETURN);
                for (String sub : new String[] { "getTag", "getSub" }) {
                    body(w, sub, "(" + STR + ")" + TAG, m -> make(m, "Tag"), Opcodes.ARETURN);
                }
                // getList(key, type), as 1.21.4's: the list, or an empty one where there is none or it is nonempty and its
                // element type is not type. The type is only compared, never kept or passed on, but it chooses what is given.
                MethodVisitor g = w.visitMethod(Opcodes.ACC_PUBLIC, "getList", "(" + STR + "I)" + LST, null, null);
                g.visitCode();
                Label has = new Label(), same = new Label();
                g.visitVarInsn(Opcodes.ALOAD, 0);
                g.visitFieldInsn(Opcodes.GETFIELD, U + "Tag", "list", LST);
                g.visitJumpInsn(Opcodes.IFNONNULL, has);
                make(g, "Lst");
                g.visitInsn(Opcodes.ARETURN);
                g.visitLabel(has);
                g.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                g.visitVarInsn(Opcodes.ALOAD, 0);
                g.visitFieldInsn(Opcodes.GETFIELD, U + "Tag", "list", LST);
                g.visitFieldInsn(Opcodes.GETFIELD, U + "Lst", "size", "I");
                g.visitJumpInsn(Opcodes.IFEQ, same);
                g.visitVarInsn(Opcodes.ALOAD, 0);
                g.visitFieldInsn(Opcodes.GETFIELD, U + "Tag", "list", LST);
                g.visitFieldInsn(Opcodes.GETFIELD, U + "Lst", "type", "I");
                g.visitVarInsn(Opcodes.ILOAD, 2);
                g.visitJumpInsn(Opcodes.IF_ICMPEQ, same);
                make(g, "Lst");
                g.visitInsn(Opcodes.ARETURN);
                g.visitLabel(same);
                g.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                g.visitVarInsn(Opcodes.ALOAD, 0);
                g.visitFieldInsn(Opcodes.GETFIELD, U + "Tag", "list", LST);
                g.visitInsn(Opcodes.ARETURN);
                g.visitMaxs(0, 0);
                g.visitEnd();
            } else {
                for (String gone : new String[] { "getText", "getNum", "getTag", "getSub" }) {
                    body(w, gone, "(" + STR + ")" + OPT, GameReadTest::empty, Opcodes.ARETURN);
                }
                body(w, "getList", "(" + STR + ")" + OPT, GameReadTest::empty, Opcodes.ARETURN);
                body(w, "read", "(" + STR + ")" + OPT, GameReadTest::empty, Opcodes.ARETURN);
                body(w, "getTextOr", "(" + STR + STR + ")" + STR, m -> m.visitVarInsn(Opcodes.ALOAD, 2), Opcodes.ARETURN);
                body(w, "getPair", "(" + STR + ")" + OPT, GameReadTest::empty, Opcodes.ARETURN);
                body(w, "getPairOr", "(" + STR + STR + ")" + STR, m -> m.visitVarInsn(Opcodes.ALOAD, 2), Opcodes.ARETURN);
                body(w, "getOne", "(" + STR + ")" + OPT, GameReadTest::empty, Opcodes.ARETURN);
                body(w, "getOneOr", "(" + STR + ")" + STR, m -> m.visitVarInsn(Opcodes.ALOAD, 1), Opcodes.ARETURN);
                body(w, "getNumOr", "(" + STR + "F)F", m -> m.visitVarInsn(Opcodes.FLOAD, 2), Opcodes.FRETURN);
                for (String sub : new String[] { "getTagOrEmpty", "getSubOrEmpty" }) {
                    body(w, sub, "(" + STR + ")" + TAG, m -> make(m, "Tag"), Opcodes.ARETURN);
                }
                // getListOrEmpty(key), as 1.21.5's: the list whatever its element type, or an empty one.
                MethodVisitor g = w.visitMethod(Opcodes.ACC_PUBLIC, "getListOrEmpty", "(" + STR + ")" + LST, null, null);
                g.visitCode();
                Label has = new Label();
                g.visitVarInsn(Opcodes.ALOAD, 0);
                g.visitFieldInsn(Opcodes.GETFIELD, U + "Tag", "list", LST);
                g.visitJumpInsn(Opcodes.IFNONNULL, has);
                make(g, "Lst");
                g.visitInsn(Opcodes.ARETURN);
                g.visitLabel(has);
                g.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                g.visitVarInsn(Opcodes.ALOAD, 0);
                g.visitFieldInsn(Opcodes.GETFIELD, U + "Tag", "list", LST);
                g.visitInsn(Opcodes.ARETURN);
                g.visitMaxs(0, 0);
                g.visitEnd();
            }
        }));
        if (two) {
            out.put(U + "Fmt", record());
        }
        out.put(U + "Ver", cls("Ver", "java/lang/Object", w -> {
            ctor(w, "java/lang/Object");
            if (two) {
                body(w, "pack", "()L" + U + "Fmt;", m -> {
                    m.visitIntInsn(Opcodes.BIPUSH, 7);
                    m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Fmt", "of", "(I)L" + U + "Fmt;", false);
                }, Opcodes.ARETURN);
            } else {
                body(w, "pack", "()I", m -> m.visitIntInsn(Opcodes.BIPUSH, 7), Opcodes.IRETURN);
            }
        }));
        out.put(U + "User", cls("User", "java/lang/Object", w -> users(w, two)));
        return out;
    }

    /** Each use, a static method of User over a Tag t. */
    static void users(ClassWriter w, boolean two) {
        // getText: three plain, one now reading a whole Pos.
        for (int i = 0; i < 4; i++) {
            boolean other = i == 3;
            String key = "t" + i;
            use(w, "text" + i, two && other ? "L" + U + "Pos;" : STR, m -> {
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitLdcInsn(key);
                if (!two) {
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Tag", "getText", "(" + STR + ")" + STR, false);
                } else if (other) {
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Tag", "read", "(" + STR + ")" + OPT, false);
                    m.visitInsn(Opcodes.ACONST_NULL);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "orElse", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                    m.visitTypeInsn(Opcodes.CHECKCAST, U + "Pos");
                } else {
                    m.visitLdcInsn("");
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Tag", "getTextOr", "(" + STR + STR + ")" + STR, false);
                }
            }, two && other ? Opcodes.ARETURN : Opcodes.ARETURN);
        }
        // getPair: three read getPairOr(key, ""), one now reads a whole Pos. getOne: two read getOneOr(key), one a Pos.
        for (String[] member : new String[][] { { "getPair", "4" }, { "getOne", "3" } }) {
            boolean pair = member[0].equals("getPair");
            int n = Integer.parseInt(member[1]);
            for (int i = 0; i < n; i++) {
                boolean other = i == n - 1;
                String key = member[0] + i;
                use(w, member[0] + i, two && other ? "L" + U + "Pos;" : STR, m -> {
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitLdcInsn(key);
                    if (!two) {
                        if (pair) {
                            m.visitVarInsn(Opcodes.ALOAD, 0);
                            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString", "()" + STR, false);
                        }
                        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Tag", member[0], "(" + STR + (pair ? STR : "") + ")" + STR, false);
                    } else if (other) {
                        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Tag", "read", "(" + STR + ")" + OPT, false);
                        m.visitInsn(Opcodes.ACONST_NULL);
                        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "orElse", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                        m.visitTypeInsn(Opcodes.CHECKCAST, U + "Pos");
                    } else {
                        if (pair) {
                            m.visitLdcInsn("");
                        }
                        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Tag", member[0] + "Or", "(" + STR + (pair ? STR : "") + ")" + STR, false);
                    }
                }, Opcodes.ARETURN);
            }
        }
        // getNum: three plain here; the fourth, with its own default, is Defaults.num3.
        for (int i = 0; i < 3; i++) {
            String key = "n" + i;
            use(w, "num" + i, "F", m -> {
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitLdcInsn(key);
                if (!two) {
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Tag", "getNum", "(" + STR + ")F", false);
                    return;
                }
                m.visitInsn(Opcodes.FCONST_0);
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Tag", "getNumOr", "(" + STR + "F)F", false);
            }, Opcodes.FRETURN);
        }
        // getTag: three plain, one insisting.
        for (int i = 0; i < 4; i++) {
            boolean insist = i == 3;
            String key = "g" + i;
            use(w, "tag" + i, TAG, m -> {
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitLdcInsn(key);
                if (!two) {
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Tag", "getTag", "(" + STR + ")" + TAG, false);
                } else if (insist) {
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Tag", "getTag", "(" + STR + ")" + OPT, false);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "orElseThrow", "()Ljava/lang/Object;", false);
                    m.visitTypeInsn(Opcodes.CHECKCAST, U + "Tag");
                } else {
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Tag", "getTagOrEmpty", "(" + STR + ")" + TAG, false);
                }
            }, Opcodes.ARETURN);
        }
        // getSub: eight plain, two nested getSub(a).getSub(b).
        for (int i = 0; i < 10; i++) {
            boolean nested = i >= 8;
            String key = "s" + i;
            use(w, "sub" + i, TAG, m -> {
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitLdcInsn(key);
                String name = two ? "getSubOrEmpty" : "getSub";
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Tag", name, "(" + STR + ")" + TAG, false);
                if (nested) {
                    m.visitLdcInsn("inner");
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Tag", name, "(" + STR + ")" + TAG, false);
                }
            }, Opcodes.ARETURN);
        }
        // getList: types 6, 8 and 10.
        int[] types = { 6, 8, 10 };
        for (int i = 0; i < types.length; i++) {
            int type = types[i];
            String key = "l" + i;
            use(w, "list" + i, LST, m -> {
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitLdcInsn(key);
                if (two) {
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Tag", "getListOrEmpty", "(" + STR + ")" + LST, false);
                } else {
                    m.visitIntInsn(Opcodes.BIPUSH, type);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Tag", "getList", "(" + STR + "I)" + LST, false);
                }
            }, Opcodes.ARETURN);
        }
    }

    static void use(ClassWriter w, String name, String result, java.util.function.Consumer<MethodVisitor> code, int ret) {
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "(" + TAG + ")" + result, null, null);
        m.visitCode();
        code.accept(m);
        m.visitInsn(ret);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** Fmt(int major, int minor), a record; Fmt.of(n) is new Fmt(n, 0). */
    static byte[] record() {
        String me = U + "Fmt";
        return cls("Fmt", "java/lang/Record", w -> {
            w.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "major", "I", null, null).visitEnd();
            w.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "minor", "I", null, null).visitEnd();
            MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(II)V", null, null);
            c.visitCode();
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Record", "<init>", "()V", false);
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitVarInsn(Opcodes.ILOAD, 1);
            c.visitFieldInsn(Opcodes.PUTFIELD, me, "major", "I");
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitVarInsn(Opcodes.ILOAD, 2);
            c.visitFieldInsn(Opcodes.PUTFIELD, me, "minor", "I");
            c.visitInsn(Opcodes.RETURN);
            c.visitMaxs(0, 0);
            c.visitEnd();
            for (String part : new String[] { "major", "minor" }) {
                body(w, part, "()I", m -> {
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitFieldInsn(Opcodes.GETFIELD, me, part, "I");
                }, Opcodes.IRETURN);
            }
            MethodVisitor of = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "of", "(I)L" + me + ";", null, null);
            of.visitCode();
            of.visitTypeInsn(Opcodes.NEW, me);
            of.visitInsn(Opcodes.DUP);
            of.visitVarInsn(Opcodes.ILOAD, 0);
            of.visitInsn(Opcodes.ICONST_0);
            of.visitMethodInsn(Opcodes.INVOKESPECIAL, me, "<init>", "(II)V", false);
            of.visitInsn(Opcodes.ARETURN);
            of.visitMaxs(0, 0);
            of.visitEnd();
            body(w, "equals", "(Ljava/lang/Object;)Z", m -> m.visitInsn(Opcodes.ICONST_0), Opcodes.IRETURN);
            body(w, "hashCode", "()I", m -> m.visitInsn(Opcodes.ICONST_0), Opcodes.IRETURN);
            str(w, "fmt");
        });
    }

    static void empty(MethodVisitor m) {
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Optional", "empty", "()" + OPT, false);
    }

    static void make(MethodVisitor m, String simple) {
        m.visitTypeInsn(Opcodes.NEW, U + simple);
        m.visitInsn(Opcodes.DUP);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, U + simple, "<init>", "()V", false);
    }

    static void body(ClassWriter w, String name, String desc, java.util.function.Consumer<MethodVisitor> code, int ret) {
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
        m.visitCode();
        code.accept(m);
        m.visitInsn(ret);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    static void str(ClassWriter w, String text) {
        body(w, "toString", "()" + STR, m -> m.visitLdcInsn(text), Opcodes.ARETURN);
    }

    static void ctor(ClassWriter w, String sup) {
        MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        c.visitCode();
        c.visitVarInsn(Opcodes.ALOAD, 0);
        c.visitMethodInsn(Opcodes.INVOKESPECIAL, sup, "<init>", "()V", false);
        c.visitInsn(Opcodes.RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();
    }

    static byte[] cls(String simple, String sup, java.util.function.Consumer<ClassWriter> body) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, U + simple, null, sup, null);
        body.accept(w);
        w.visitEnd();
        return w.toByteArray();
    }
}

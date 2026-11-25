import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;

/**
 * A release read for a port answers as one read whole. A port of a few mods read every method's code and all 27,000
 * classes of the libraries the game ships with; now a release is read as declarations (GameDiff.read without code), a
 * class's code when a method's references are asked for, and a library's class when something names it. On games and
 * library jars built here, in several shapes each:
 * - every method's references and opcodes, asked of a release read without code, are those of the whole read, lambdas
 *   and references through a superclass included, and nothing is read before it is asked;
 * - the learner, which needs what only all the code tells, refuses a release read without it;
 * - the library table lists and orders its classes as reading every jar did (the release's own class, then the first
 *   jar's, kept; a module's and a package's files left out), each class the same once asked for, and a class file that
 *   cannot be read stops only the question about it;
 * - the classes that can extend a class of the release are its own, found with no library read; under a library's
 *   class, every class; and a library class that does extend the release's is refused, since that is what the first rests on;
 * - Mojang's mappings parse the same with and without ProGuard's source line numbers.
 *
 *   java -cp asm.jar:CLASSES GameDeclaredTest SCRATCH   (in the jar-port check of bench/run_tests.py)
 */
final class GameDeclaredTest {

    static final String G = "net/minecraft/d7/";
    static int checks;

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        for (int shape = 0; shape < 3; shape++) {
            code(shape);
            libraries(scratch, shape);
        }
        under(scratch);
        mappings();
        if (JarPortTest.failures > 0) {
            System.exit(1);
        }
        System.out.println("game-declared: " + checks + " checks passed");
    }

    static void expect(boolean ok, String what) {
        checks++;
        JarPortTest.expect(ok, what);
    }

    /** One method as the port reads it: its name, flags, whether it has code, and what its code names. */
    static String method(GameDiff.Meth m) {
        return m.owner.name + "." + m.name + m.desc + " " + m.flags + " " + m.hasCode + " " + Arrays.toString(m.references()) + " " + m.ops.chars().boxed().toList();
    }

    /** A class's declarations: what a port links against. */
    static String declared(GameDiff.Cls c) {
        List<String> out = new ArrayList<>(List.of(c.name, String.valueOf(c.flags), String.valueOf(c.sup), c.ifaces.toString(), c.loader + " " + c.library + " " + c.artifact));
        c.methods.forEach((k, m) -> out.add(k + " " + m.flags + " " + m.name + m.desc));
        c.fields.forEach((k, f) -> out.add(k + " " + f.flags + " " + f.desc + " " + f.constant));
        c.children.forEach(k -> out.add("child " + k.name));
        return String.join("\n", out);
    }

    static void code(int shape) throws Exception {
        List<byte[]> game = game(shape), loader = List.of(loaderClass(shape));
        GameDiff.Snap whole = GameDiff.read("1." + shape, game, null, loader);
        GameDiff.Snap port = GameDiff.read("1." + shape, game, null, loader, false);
        expect(new ArrayList<>(port.classes.keySet()).equals(new ArrayList<>(whole.classes.keySet())) && whole.classes.size() == 4,
            "the same classes in the same order: " + port.classes.keySet() + " and " + whole.classes.keySet());
        int unread = 0, methods = 0;
        for (GameDiff.Cls c : port.classes.values()) {
            for (GameDiff.Meth m : c.methods.values()) {
                unread += m.refs == null && m.ops == null ? 1 : 0;
                methods++;
            }
        }
        expect(unread == methods && methods == 8, "no code is read until it is asked for: " + unread + " of " + methods + " methods unread");
        boolean refused = false;
        try {
            new GameHop(whole, port);
        } catch (IllegalArgumentException needsCode) {
            refused = true;
        }
        expect(refused, "the learner refuses a release read without its code");
        // One method asked first, from the last class: its class alone is read.
        GameDiff.Cls sub = port.classes.get(G + "Sub");
        sub.methods.get("tick()V").references();
        expect(port.classes.get(G + "Base").methods.get("tick()V").refs == null, "asking one class's code reads no other's");
        List<String> expected = new ArrayList<>(), got = new ArrayList<>();
        for (GameDiff.Cls c : whole.classes.values()) {
            expected.add(declared(c));
            c.methods.values().forEach(m -> expected.add(method(m)));
        }
        for (GameDiff.Cls c : port.classes.values()) {
            got.add(declared(c));
            c.methods.values().forEach(m -> got.add(method(m)));
        }
        expect(got.equals(expected), "shape " + shape + ": every class and method reads as in the whole read:\n" + String.join("\n", got) + "\n-- whole:\n" + String.join("\n", expected));
        String tick = method(whole.classes.get(G + "Base").methods.get("tick()V"));
        expect(tick.contains("name=helper") && tick.contains(shape == 1 ? "owner=s:one" : "owner=s:zero") && tick.contains("kind=f, owner=" + G + "Base, name=count"),
            "the references read are the method's own, its lambda's and its fields': " + tick);
        // The whole of a release's code at once, for a search over it: the release's own classes, each read.
        GameDiff.Snap searched = GameDiff.read("1." + shape, game, null, loader, false);
        List<String> all = new ArrayList<>();
        for (GameDiff.Cls c : searched.coded()) {
            for (GameDiff.Meth m : c.methods.values()) {
                expect(m.refs != null, "a search over all the code finds every method's read: " + m.name);
                all.add(method(m));
            }
        }
        List<String> wholeMethods = new ArrayList<>();
        whole.classes.values().forEach(c -> c.methods.values().forEach(m -> wholeMethods.add(method(m))));
        expect(all.equals(wholeMethods), "and they are the whole read's, in its order");
    }

    static void libraries(Path scratch, int shape) throws Exception {
        Path first = scratch.resolve("first-" + shape + ".jar"), second = scratch.resolve("second-" + shape + ".jar"), broken = scratch.resolve("broken-" + shape + ".jar");
        Map<String, byte[]> one = new LinkedHashMap<>();
        // The order of a jar's entries is the order of its classes; each shape writes another.
        List<String> names = new ArrayList<>(List.of("lib/a/One", "lib/a/Three", G + "Base"));
        Collections.rotate(names, shape);
        for (String name : names) {
            one.put(name + ".class", library(name, "java/lang/Object", "first" + shape));
        }
        one.put("module-info.class", new byte[] { 1 });
        one.put("lib/a/package-info.class", new byte[] { 1 });
        one.put("META-INF/versions/9/lib/a/One.class", library("lib/a/One", "java/lang/Object", "newer"));
        zip(first, one);
        Map<String, byte[]> two = new LinkedHashMap<>();
        two.put("lib/a/One.class", library("lib/a/One", "java/lang/Object", "second"));
        two.put("lib/b/Two.class", library("lib/b/Two", "lib/a/One", "two" + shape));
        zip(second, two);
        zip(broken, Map.of("lib/c/Broken.class", new byte[] { 1, 2, 3 }));
        List<GameDiff.Library> jars = List.of(new GameDiff.Library("lib/first", first), new GameDiff.Library("lib/second", second));

        GameDiff.Snap eager = GameDiff.read("1." + shape, game(shape), null, List.of());
        Map<String, GameDiff.Cls> read = new LinkedHashMap<>(eager.classes);
        GameDiff.addLibraries(read, jars);
        GameDiff.Snap lazy = GameDiff.read("1." + shape, game(shape), null, List.of());
        lazy.classes.libraries(jars);
        expect(new ArrayList<>(lazy.classes.keySet()).equals(new ArrayList<>(read.keySet())) && lazy.classes.size() == read.size() && read.size() == 6,
            "shape " + shape + ": the table lists the classes reading every jar did, in that order: " + lazy.classes.keySet() + " and " + read.keySet());
        expect(lazy.classes.get(G + "Base").origin != null && !lazy.classes.get(G + "Base").library, "the release's own class is kept over a library's of its name");
        List<String> got = new ArrayList<>(), expected = new ArrayList<>();
        // Asked for in another order than they are listed in, then listed.
        List<String> asked = new ArrayList<>(read.keySet());
        Collections.reverse(asked);
        for (String name : asked) {
            expect(declared(lazy.classes.get(name)).equals(declared(read.get(name))), "each class reads the same once asked for: " + name);
        }
        lazy.classes.values().forEach(c -> got.add(declared(c)));
        read.values().forEach(c -> expected.add(declared(c)));
        expect(got.equals(expected) && got.toString().contains("first" + shape + "()V") && !got.toString().contains("second()V") && !got.toString().contains("newer()V"),
            "listed, they are those of the first jar that has each: " + got);
        expect(lazy.classes.get("lib/a/One") == lazy.classes.get("lib/a/One"), "a class is read once");

        List<String> libraryNames = new ArrayList<>();
        lazy.classes.libraries((name, artifact) -> libraryNames.add(name + " of " + artifact));
        expect(libraryNames.size() == 3 && libraryNames.contains("lib/b/Two of lib/second") && libraryNames.contains("lib/a/One of lib/first"),
            "each library class names its library: " + libraryNames);

        // A class file that cannot be read stops the question about it, and no other.
        GameDiff.Snap withBroken = GameDiff.read("1." + shape, game(shape), null, List.of());
        withBroken.classes.libraries(List.of(jars.get(0), new GameDiff.Library("lib/broken", broken), jars.get(1)));
        expect(withBroken.classes.containsKey("lib/c/Broken") && withBroken.classes.size() == 7 && withBroken.classes.get("lib/b/Two").sup.equals("lib/a/One"),
            "a class not asked for is not read: the table lists it and answers for the others");
        expect(withBroken.classes.own().size() == 3 && withBroken.classes.own(G + "Sub") && !withBroken.classes.own("lib/a/One") && !withBroken.classes.own("java/lang/Object"),
            "the release's own classes are listed with no library read: " + withBroken.classes.own().size());
        expect(new Linking(null).declaring(withBroken, "tick()V", G + "Base").stream().map(c -> c.name).toList().equals(List.of(G + "Base", G + "Sub")),
            "which of the release's classes declare a method under one of its own is answered with no library read");
        boolean stopped = false;
        try {
            withBroken.classes.get("lib/c/Broken");
        } catch (RuntimeException unreadable) {
            stopped = true;
        }
        expect(stopped, "asking for the class that cannot be read fails");

        // A class another release has and this one does not is carried as that release has it: one class, read once.
        GameDiff.Snap newer = GameDiff.read("2." + shape, game(shape), null, List.of());
        newer.classes.carry("lib/b/Two", lazy.classes);
        expect(newer.classes.containsKey("lib/b/Two") && newer.classes.get("lib/b/Two") == lazy.classes.get("lib/b/Two") && !newer.classes.own("lib/b/Two"),
            "a carried class is the one class in both releases");
    }

    static void under(Path scratch) throws Exception {
        Path jar = scratch.resolve("under.jar");
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("lib/a/One.class", library("lib/a/One", "java/lang/Object", "tick"));
        files.put("lib/b/Two.class", library("lib/b/Two", "lib/a/One", "tick"));
        zip(jar, files);
        GameDiff.Snap s = GameDiff.read("1.0", game(0), null, List.of());
        s.classes.libraries(List.of(new GameDiff.Library("lib/under", jar)));
        Linking links = new Linking(null);
        List<String> every = links.declaring(s, "tick()V").stream().map(c -> c.name).toList();
        expect(every.equals(List.of(G + "Base", G + "Sub", "lib/a/One", "lib/b/Two")), "every class that declares a method: " + every);
        for (String game : List.of(G + "Base", G + "Sub", G + "Other")) {
            List<String> under = links.declaring(s, "tick()V", game).stream().filter(c -> Linking.subtype(s, c.name, game)).map(c -> c.name).toList();
            List<String> whole = links.declaring(s, "tick()V").stream().filter(c -> Linking.subtype(s, c.name, game)).map(c -> c.name).toList();
            expect(under.equals(whole), "under a class of the release, its own classes answer as every class does: " + under + " and " + whole);
        }
        for (String other : List.of("lib/a/One", "java/lang/Object", "not/in/the/Release")) {
            expect(links.declaring(s, "tick()V", other).equals(links.declaring(s, "tick()V")), "under a library's class or one the release does not have, every class is searched: " + other);
            expect(new ArrayList<>(s.classes.under(other)).equals(new ArrayList<>(s.classes.values())), "and every class can extend it: " + other);
        }
        expect(s.classes.under(G + "Base").equals(s.classes.own()), "only the release's own can extend one of its own");
        expect(links.declaringIn(s, "tick()V", "lib/").equals(links.declaring(s, "tick()V")) && links.declaringIn(s, "tick()V", "").equals(links.declaring(s, "tick()V")),
            "a search by package reads the libraries that have classes there");
        expect(links.declaringIn(s, "tick()V", "net/minecraft/").stream().map(c -> c.name).toList().equals(List.of(G + "Base", G + "Sub")),
            "and the release's own classes where no library has one");

        // What searching the release's own classes rests on, checked at each library class read.
        Path wrong = scratch.resolve("wrong.jar");
        zip(wrong, Map.of("lib/w/Wrong.class", library("lib/w/Wrong", G + "Base", "tick")));
        GameDiff.Snap bad = GameDiff.read("1.0", game(0), null, List.of());
        bad.classes.libraries(List.of(new GameDiff.Library("lib/wrong", wrong)));
        boolean refused = false;
        try {
            bad.classes.get("lib/w/Wrong");
        } catch (IllegalStateException extendsTheGame) {
            refused = extendsTheGame.getMessage().contains("lib/w/Wrong") && extendsTheGame.getMessage().contains(G + "Base");
        }
        expect(refused, "a library class that extends a class of the release is refused, by name");
    }

    static void mappings() {
        String plain = """
            net.minecraft.d7.Base -> a:
                int count -> a
                void tick() -> b
                net.minecraft.d7.Base later(int,net.minecraft.d7.Base[]) -> c
            net.minecraft.d7.Sub -> b:
                void tick() -> b
            """;
        String numbered = plain.replace("    void tick() -> b", "    12:34:void tick() -> b").replace("    net.minecraft.d7.Base later(", "    7:7:net.minecraft.d7.Base later(")
            .replace(") -> c", "):40:41 -> c");
        VersionMap.Proguard a = VersionMap.Proguard.parse(plain), b = VersionMap.Proguard.parse(numbered);
        expect(a.methods.equals(b.methods) && a.fields.equals(b.fields) && a.classes.equals(b.classes), "source line numbers change no name: " + b.methods);
        expect("tick".equals(a.methods.get("a.b()V")) && "later".equals(a.methods.get("a.c(I[La;)La;")) && "count".equals(a.fields.get("a.a")) && a.methods.size() == 3,
            "a method is keyed by its owner, name and descriptor as the jar has them: " + a.methods);
        for (String[] line : new String[][] { { "12:34:void a()", "void a()" }, { "void a()", "void a()" }, { "12:void a()", "12:void a()" }, { "12:34void a()", "12:34void a()" },
            { ":3:4:void a()", ":3:4:void a()" }, { "1:2:", "" }, { "12:34", "12:34" }, { "", "" } }) {
            expect(VersionMap.Proguard.unnumbered(line[0]).equals(line[1]), "\"" + line[0] + "\" without its line numbers is \"" + line[1] + "\", not \"" + VersionMap.Proguard.unnumbered(line[0]) + "\"");
        }
    }

    /**
     * Base: a field count; tick() calls helper(), reads and writes count, and makes a lambda that loads a string (another
     * in shape 1); helper(). Sub extends Base: tick() calls super.tick() and, through Sub, Base's helper() (shape 2: twice).
     * Other: nothing of theirs.
     */
    static List<byte[]> game(int shape) {
        List<byte[]> out = new ArrayList<>();
        ClassWriter base = start(G + "Base", "java/lang/Object");
        base.visitField(Opcodes.ACC_PUBLIC, "count", "I", null, null).visitEnd();
        MethodVisitor tick = base.visitMethod(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
        tick.visitCode();
        tick.visitVarInsn(Opcodes.ALOAD, 0);
        tick.visitMethodInsn(Opcodes.INVOKEVIRTUAL, G + "Base", "helper", "()V", false);
        tick.visitVarInsn(Opcodes.ALOAD, 0);
        tick.visitVarInsn(Opcodes.ALOAD, 0);
        tick.visitFieldInsn(Opcodes.GETFIELD, G + "Base", "count", "I");
        tick.visitInsn(Opcodes.ICONST_1);
        tick.visitInsn(Opcodes.IADD);
        tick.visitFieldInsn(Opcodes.PUTFIELD, G + "Base", "count", "I");
        Handle meta = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
        tick.visitInvokeDynamicInsn("get", "()Ljava/util/function/Supplier;", meta, Type.getType("()Ljava/lang/Object;"),
            new Handle(Opcodes.H_INVOKESTATIC, G + "Base", "lambda$tick$0", "()Ljava/lang/String;", false), Type.getType("()Ljava/lang/String;"));
        tick.visitInsn(Opcodes.POP);
        tick.visitInsn(Opcodes.RETURN);
        tick.visitMaxs(0, 0);
        tick.visitEnd();
        MethodVisitor lambda = base.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "lambda$tick$0", "()Ljava/lang/String;", null, null);
        lambda.visitCode();
        lambda.visitLdcInsn(shape == 1 ? "one" : "zero");
        lambda.visitInsn(Opcodes.ARETURN);
        lambda.visitMaxs(0, 0);
        lambda.visitEnd();
        empty(base, "helper");
        out.add(end(base));
        ClassWriter sub = start(G + "Sub", G + "Base");
        MethodVisitor over = sub.visitMethod(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
        over.visitCode();
        over.visitVarInsn(Opcodes.ALOAD, 0);
        over.visitMethodInsn(Opcodes.INVOKESPECIAL, G + "Base", "tick", "()V", false);
        for (int i = 0; i < (shape == 2 ? 2 : 1); i++) {
            over.visitVarInsn(Opcodes.ALOAD, 0);
            over.visitMethodInsn(Opcodes.INVOKEVIRTUAL, G + "Sub", "helper", "()V", false);
        }
        over.visitInsn(Opcodes.RETURN);
        over.visitMaxs(0, 0);
        over.visitEnd();
        out.add(end(sub));
        out.add(end(start(G + "Other", "java/lang/Object")));
        // Each shape hands the files over in another order: the release's order is by name, whatever the jar's.
        Collections.rotate(out, shape);
        return out;
    }

    /** A class of the loader that calls the game's: Sub.helper(), which Base declares. */
    static byte[] loaderClass(int shape) {
        ClassWriter w = start("loader/api/Hooks", "java/lang/Object");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(L" + G + "Sub;)V", null, null);
        m.visitCode();
        for (int i = 0; i <= shape; i++) {
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, G + "Sub", "helper", "()V", false);
        }
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        return end(w);
    }

    static byte[] library(String name, String sup, String method) {
        ClassWriter w = start(name, sup);
        w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "SIZE", "I", null, null).visitEnd();
        empty(w, method);
        return end(w);
    }

    static ClassWriter start(String name, String sup) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, sup, null);
        MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        c.visitCode();
        c.visitVarInsn(Opcodes.ALOAD, 0);
        c.visitMethodInsn(Opcodes.INVOKESPECIAL, sup, "<init>", "()V", false);
        c.visitInsn(Opcodes.RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();
        return w;
    }

    static void empty(ClassWriter w, String name) {
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, name, "()V", null, null);
        m.visitCode();
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    static byte[] end(ClassWriter w) {
        w.visitEnd();
        return w.toByteArray();
    }

    static void zip(Path jar, Map<String, byte[]> files) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (var e : files.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
    }
}

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * A port back to an older release, where the mod calls game methods the older release does not have yet. Release 2.0
 * added Loc.of (through a private helper of its own), Loc.twice() and Loc.lazy() (a lambda), whose code uses only what
 * 1.0 has, and Loc.fresh(), which reads a field 1.0 does not have. Ported from 2.0 back to 1.0, the jar carries copies of
 * 2.0's own code for the first three and calls them, a super call from a mod's subclass included, and the ported mod runs
 * on 1.0's real classes; fresh() has no older form and stays a break, as does Loc.third(), which a class of the jar
 * overrides (a call on Loc may run the override). The game's classes are compiled here, in both
 * releases, from source.
 */
final class JarPortBackTest {

    static final String NEWER = """
        package net.minecraft.c5;
        public class Loc {
            public final String text;
            public final int extra;
            public Loc(String text) {
                this.text = text;
                this.extra = 7;
            }
            public int value() {
                return text.length();
            }
            public static Loc of(String a, String b) {
                return joined(a, b);
            }
            private static Loc joined(String a, String b) {
                return new Loc(a + ":" + b);
            }
            public int twice() {
                return value() * 2;
            }
            public java.util.function.IntSupplier lazy() {
                return () -> value() * 3;
            }
            public int fresh() {
                return extra;
            }
            public int third() {
                return value() * 4;
            }
        }
        """;

    static final String OLDER = """
        package net.minecraft.c5;
        public class Loc {
            public final String text;
            public Loc(String text) {
                this.text = text;
            }
            public int value() {
                return text.length();
            }
        }
        """;

    static final String MOD = """
        package n5;
        import net.minecraft.c5.Loc;
        public class Mod {
            public static String make(String a, String b) {
                return Loc.of(a, b).text;
            }
            public static int twice(Loc loc) {
                return loc.twice();
            }
            public static int lazy(Loc loc) {
                return loc.lazy().getAsInt();
            }
            public static int fresh(Loc loc) {
                return loc.fresh();
            }
            public static int third(Loc loc) {
                return loc.third();
            }
        }
        """;

    static final String SUB = """
        package n5;
        import net.minecraft.c5.Loc;
        public class Sub extends Loc {
            public Sub(String text) {
                super(text);
            }
            public int viaSuper() {
                return super.twice();
            }
        }
        """;

    static final String OWN = """
        package n5;
        import net.minecraft.c5.Loc;
        public class Own extends Loc {
            public Own(String text) {
                super(text);
            }
            @Override
            public int third() {
                return 99;
            }
        }
        """;

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]).resolve("back"));
        Map<String, byte[]> newer = compile(scratch.resolve("newer"), Map.of("net/minecraft/c5/Loc.java", NEWER), null);
        Map<String, byte[]> older = compile(scratch.resolve("older"), Map.of("net/minecraft/c5/Loc.java", OLDER), null);
        Map<String, byte[]> mod = compile(scratch.resolve("mod"), Map.of("n5/Mod.java", MOD, "n5/Sub.java", SUB, "n5/Own.java", OWN), scratch.resolve("newer/classes"));

        GameDiff.Snap a = GameDiff.read("2.0", new ArrayList<>(newer.values()), null, null);
        GameDiff.Snap b = GameDiff.read("1.0", new ArrayList<>(older.values()), null, null);
        b.loaderVersion = "1.0.1";
        Path map = scratch.resolve("route.json");
        Files.writeString(map, "{}");
        JarPort port = new JarPort(a, b, map);
        port.oldCode = new JarPort.GameCode(newer::get, null, newer);
        port.newCode = new JarPort.GameCode(older::get, null, older);

        Map<String, byte[]> files = new HashMap<>();
        mod.forEach((name, bytes) -> files.put(name + ".class", bytes));
        files.put("META-INF/neoforge.mods.toml", "modLoader=\"javafml\"\n[[mods]]\nmodId=\"n5\"\n".getBytes(StandardCharsets.UTF_8));
        List<Map<String, Object>> runs = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(files), "back.jar", runs));
        List<String> unlinked = Json.array(runs.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        // third() is copyable, but a class of the jar overrides it: a call on Loc may run that override, so it stays.
        JarPortTest.expect(unlinked.equals(List.of("call: net.minecraft.c5.Loc.fresh()I", "call: net.minecraft.c5.Loc.third()I")),
            "what 2.0 added whose code uses only 1.0 links; fresh(), which reads a field 1.0 lacks, and third(), which the jar overrides, stay breaks: " + unlinked);
        List<String> carried = ported.keySet().stream().filter(n -> n.startsWith("mmt/standin/")).sorted().toList();
        JarPortTest.expect(carried.equals(List.of(port.copiedHome("back.jar") + "net/minecraft/c5/Loc.class")),
            "the copies are carried in one class under the jar's own package: " + carried);

        Map<String, byte[]> classes = new HashMap<>(older);
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
        String trace;
        try {
            Class<?> m = loader.loadClass("n5.Mod"), loc = loader.loadClass("net.minecraft.c5.Loc"), sub = loader.loadClass("n5.Sub");
            Object five = loc.getConstructor(String.class).newInstance("abcde");
            trace = m.getMethod("make", String.class, String.class).invoke(null, "a", "b") + ";" + m.getMethod("twice", loc).invoke(null, five)
                + ";" + m.getMethod("lazy", loc).invoke(null, five) + ";" + sub.getMethod("viaSuper").invoke(sub.getConstructor(String.class).newInstance("abc"));
        } catch (ReflectiveOperationException | LinkageError failed) {
            trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
        JarPortTest.expect(trace.equals("a:b;10;15;6"),
            "the ported mod runs 2.0's code on 1.0: a private helper, an instance method, a lambda and a super call: " + trace);

        // A port forward copies nothing: a method the newer release dropped is the old API, which stand-ins answer.
        JarPort forward = new JarPort(b, a, map);
        JarPortTest.expect(forward.copyNewer(new HashMap<>(files), "forward.jar").isEmpty(), "a port forward copies nothing");
        JarPortTest.finish("JarPortBackTest");
    }

    /** Java sources compiled against cp (or the JDK alone): class files by internal name. */
    static Map<String, byte[]> compile(Path dir, Map<String, String> sources, Path cp) throws IOException {
        Path src = Files.createDirectories(dir.resolve("src")), out = Files.createDirectories(dir.resolve("classes"));
        List<String> command = new ArrayList<>(List.of("--release", "21", "-proc:none", "-nowarn", "-d", out.toString()));
        if (cp != null) {
            command.addAll(List.of("-cp", cp.toString()));
        }
        for (var e : sources.entrySet()) {
            Path f = src.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
            command.add(f.toString());
        }
        StringWriter errors = new StringWriter();
        int exit = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, new PrintStream(new OutputStream() {
            public void write(int x) {
                errors.write(x);
            }
        }, true), command.toArray(String[]::new));
        if (exit != 0) {
            throw new IOException("does not compile: " + errors);
        }
        return JarPort.readClasses(out);
    }
}

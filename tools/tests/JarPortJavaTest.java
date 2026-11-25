import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * A mod built for Java 21 ported to a release that runs Java 8 (Fabric 1.14.4 to 1.16.5 do): its classes are written in
 * Java 8's class-file version, and what Java 8 cannot run is rewritten (string concatenation, records, nestmates, a private
 * interface method, pattern switches over types, enum constants and enums) or called through the JDK stand-ins (List.of,
 * getFirst, String.isBlank, Optional.isEmpty, Stream.toList, String::isBlank as a method reference, a constructor Java 8
 * lacks, and whole classes it lacks: HexFormat, and java.net.http against a real local server), while ByteBuffer.flip()
 * is called as Java 8 declares it. The ported classes then run here and give exactly what the original classes give, and
 * no class names a JDK member Java 8 lacks. A class calling StackWalker, which Java 8 has no answer for, is reported, the
 * metadata asks for Java 8, and a port to a release whose Java reads the classes leaves them as they are.
 */
final class JarPortJavaTest {

    static final String MOD = """
        package d8;

        import java.net.URI;
        import java.net.http.HttpClient;
        import java.net.http.HttpRequest;
        import java.net.http.HttpResponse;
        import java.nio.ByteBuffer;
        import java.time.Duration;
        import java.util.*;
        import java.util.function.Predicate;

        public final class Mod {
            public record Point(int x, long y, double z, String name, int[] data) {}
            public record Empty() {}
            sealed interface Shape permits Circle, Square {}
            record Circle(double r) implements Shape {}
            record Square(double side) implements Shape {}
            sealed interface Tone permits Color {}
            enum Color implements Tone { RED, GREEN, BLUE }

            private int secret = 7;

            private int hidden() {
                return secret * 2;
            }

            final class Inner {
                int peek() {
                    return secret + hidden();
                }
            }

            interface Greeter {
                private String name() {
                    return "mmt";
                }

                default String greet() {
                    return "hi " + name();
                }
            }

            static String describe(Object o) {
                return switch (o) {
                    case null -> "null";
                    case Circle c when c.r() > 10 -> "big circle";
                    case Circle c -> "circle " + c.r();
                    case Square s -> "square " + s.side();
                    case Integer i -> "int " + i;
                    case String s -> "string " + s;
                    default -> "other";
                };
            }

            static String tone(Tone t) {
                return switch (t) {
                    case Color.RED -> "red";
                    case Color c -> "tone " + c.name().toLowerCase();
                };
            }

            static String color(Color c) {
                return switch (c) {
                    case RED -> "r";
                    case Color g when g.ordinal() == 1 -> "g";
                    default -> "other";
                };
            }

            public static String run() {
                StringBuilder out = new StringBuilder();
                Point p = new Point(1, 2L, 3.5, "a", new int[] { 4 });
                Point q = new Point(1, 2L, 3.5, "a", p.data());
                out.append(p.toString().replaceAll("@[0-9a-f]+", "@")).append(' ').append(p.equals(q)).append(' ')
                    .append(p.equals(new Point(1, 2L, 3.5, "b", p.data()))).append(' ').append(p.hashCode() == q.hashCode()).append(' ')
                    .append(new Point(0, 0L, -0.0, null, null).hashCode()).append(' ').append(new Empty()).append(' ').append(new Empty().hashCode()).append('\\n');
                Mod mod = new Mod();
                out.append(mod.new Inner().peek()).append(' ').append(new Greeter() {}.greet()).append('\\n');
                for (Object o : new Object[] { null, new Circle(11), new Circle(2), new Square(3), 5, "x", 2.5 }) {
                    out.append(describe(o)).append(';');
                }
                for (Color c : Color.values()) {
                    out.append(tone(c)).append(';').append(color(c)).append(';');
                }
                out.append('\\n');
                List<String> words = new ArrayList<>(List.of("b", " ", "a"));
                words.addFirst("z");
                Predicate<String> blank = String::isBlank;
                out.append(words.getFirst()).append(words.getLast()).append(' ').append(words.stream().filter(blank.negate()).toList())
                    .append(' ').append("  pad ".strip()).append('|').append("ab".repeat(3)).append(' ')
                    .append(Optional.empty().isEmpty()).append(' ').append(Map.of("k", 1)).append(' ').append(Set.of(9))
                    .append(' ').append(Objects.requireNonNullElse(null, "else")).append('\\n');
                ByteBuffer buffer = ByteBuffer.allocate(4);
                buffer.put((byte) 1).flip();
                out.append(buffer.remaining()).append('\\n');
                out.append(HexFormat.of().formatHex(new byte[] { 1, (byte) 0xab })).append(' ').append(new IndexOutOfBoundsException(3).getMessage()).append('\\n');
                try {
                    out.append(http());
                } catch (Exception e) {
                    out.append(e);
                }
                return out.toString();
            }

            // java.net.http, which Java 8 lacks: a redirect followed, a POST body, an error status, headers read back.
            static String http() throws Exception {
                String base = System.getProperty("d8.http");
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();
                HttpResponse<String> moved = client.send(HttpRequest.newBuilder(URI.create(base + "/moved")).header("X-A", "1").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                HttpResponse<String> posted = client.send(HttpRequest.newBuilder().uri(URI.create(base + "/echo")).header("X-A", "2")
                    .POST(HttpRequest.BodyPublishers.ofString("sent")).build(), HttpResponse.BodyHandlers.ofString());
                HttpResponse<byte[]> missing = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + "/missing")).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
                HttpResponse<String> never = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + "/moved")).build(),
                    HttpResponse.BodyHandlers.ofString());
                return moved.statusCode() + " " + moved.body() + " " + moved.uri().getPath() + " " + moved.headers().firstValue("x-b").orElse("none") + "|"
                    + posted.statusCode() + " " + posted.body() + "|" + missing.statusCode() + " " + new String(missing.body()) + "|"
                    + never.statusCode() + " " + never.headers().firstValue("Location").orElse("none");
            }
        }
        """;

    static final String WALKER = """
        package d8;

        public final class Walker {
            public static Object caller() {
                return StackWalker.getInstance().getCallerClass();
            }
        }
        """;

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]).resolve("java-lowering"));
        Map<String, byte[]> classes = compile(scratch);

        Map<String, byte[]> modFiles = new TreeMap<>();
        classes.forEach((name, bytes) -> modFiles.put(name + ".class", bytes));
        modFiles.put("fabric.mod.json", "{\"schemaVersion\": 1, \"id\": \"d8\", \"version\": \"1\", \"depends\": {\"java\": \">=21\"}, \"mixins\": [\"d8.mixins.json\"]}"
            .getBytes(StandardCharsets.UTF_8));
        modFiles.put("d8.mixins.json", "{\"required\": true, \"package\": \"d8.mixin\", \"compatibilityLevel\": \"JAVA_21\", \"mixins\": []}".getBytes(StandardCharsets.UTF_8));
        byte[] jar = JarPortTest.jar(modFiles);

        JarPort port = porter(scratch, 8);
        List<Map<String, Object>> runs = new ArrayList<>();
        byte[] portedJar = port.port(jar, "d8.jar", runs, null);
        Files.write(scratch.resolve("d8-java8.jar"), portedJar); // kept to run on a real Java 8 by hand
        Map<String, byte[]> ported = JarPortTest.entries(portedJar);
        List<String> unlinked = Json.array(runs.get(0).get("unlinked")).stream().map(String::valueOf).toList();

        Map<String, Integer> majors = new TreeMap<>();
        ported.forEach((n, b) -> {
            if (n.endsWith(".class")) {
                majors.put(n, Downgrade.major(b));
            }
        });
        JarPortTest.expect(!majors.isEmpty() && majors.values().stream().allMatch(m -> m == 52), "every class of the port is written for Java 8: " + majors);
        JarPortTest.expect(unlinked.equals(List.of("not in Java 8: class java.lang.StackWalker (in d8.Walker)")),
            "the one JDK class Java 8 has no answer for is reported, nothing else: " + unlinked);
        JarPortTest.expect(ported.keySet().stream().anyMatch(n -> n.matches("mmt/standin/m[0-9a-f]+/java/util/List\\.class")),
            "the JDK stand-ins the mod calls are carried in it, under a package of its own: " + ported.keySet());

        // Every JDK reference of the ported classes, read again, is one Java 8 declares.
        Downgrade.Api java8 = Downgrade.Api.of(8);
        List<String> missing = new ArrayList<>();
        for (var e : ported.entrySet()) {
            if (e.getKey().endsWith(".class") && !e.getKey().startsWith("d8/Walker")) {
                jdkCalls(e.getValue(), (owner, member) -> {
                    boolean has = member.contains("(") ? java8.method(owner, member.substring(0, member.indexOf('(')), member.substring(member.indexOf('(')))
                        : java8.cls(owner) != null;
                    if (!has) {
                        missing.add(e.getKey() + ": " + owner + "." + member);
                    }
                });
            }
        }
        JarPortTest.expect(missing.isEmpty(), "no ported class calls a JDK member Java 8 lacks: " + missing);

        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = exchange.getRequestBody().readAllBytes();
            String reply = exchange.getRequestMethod() + " " + exchange.getRequestHeaders().getFirst("X-A") + " " + new String(body, StandardCharsets.UTF_8);
            int status = 200;
            if (path.equals("/moved")) {
                exchange.getResponseHeaders().add("Location", "/echo");
                status = 302;
                reply = "";
            } else if (path.equals("/missing")) {
                status = 404;
                reply = "nope";
            }
            exchange.getResponseHeaders().add("X-B", "yes");
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        String before, after;
        try {
            System.setProperty("d8.http", "http://127.0.0.1:" + server.getAddress().getPort());
            before = run(classes);
            after = run(classesOf(ported));
        } finally {
            server.stop(0);
        }
        JarPortTest.expect(after.equals(before), "the ported mod gives what the original gives:\n" + before + "\n--- ported ---\n" + after);
        JarPortTest.expect(before.contains("Point[x=1, y=2, z=3.5, name=a, data=[I@] true false true") && before.contains("red;r;tone green;g;tone blue;other"),
            "the run covers records and pattern switches: " + before);
        JarPortTest.expect(before.contains("01ab Index out of range: 3") && before.contains("200 GET 1  /echo yes|200 POST 2 sent|404 nope|302 /echo"),
            "the run covers a JDK class Java 8 lacks (HexFormat, java.net.http) and a constructor it lacks: " + before);

        String fmj = new String(ported.get("fabric.mod.json"), StandardCharsets.UTF_8), mixins = new String(ported.get("d8.mixins.json"), StandardCharsets.UTF_8);
        JarPortTest.expect(fmj.contains("\">=8\"") && mixins.contains("\"JAVA_8\""), "the metadata asks for the Java the mod now runs on: " + fmj + " " + mixins);

        // A release whose Java reads the classes as they are (Java 21) leaves them so.
        List<Map<String, Object>> sameRuns = new ArrayList<>();
        Map<String, byte[]> same = JarPortTest.entries(porter(scratch, 21).port(jar, "d8.jar", sameRuns, null));
        JarPortTest.expect(Downgrade.major(same.get("d8/Mod.class")) == 65 && Json.array(sameRuns.get(0).get("unlinked")).isEmpty()
            && same.keySet().stream().noneMatch(n -> n.startsWith("mmt/")), "a mod the release's Java runs is left as it came: " + same.keySet());

        JarPortTest.finish("JarPortJavaTest");
    }

    static JarPort porter(Path scratch, int java) throws IOException {
        GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
        b.loaderVersion = "2.0.1";
        b.java = java;
        Path map = scratch.resolve("route.json");
        Files.writeString(map, "{}");
        JarPort port = new JarPort(a, b, map);
        port.olderJava.jdkStandins = Downgrade.Standins.of(Path.of("knowledge/standins/jdk"));
        return port;
    }

    static Map<String, byte[]> compile(Path scratch) throws IOException {
        Path src = scratch.resolve("src"), out = scratch.resolve("classes");
        Files.createDirectories(src.resolve("d8"));
        Files.createDirectories(out);
        Files.writeString(src.resolve("d8/Mod.java"), MOD);
        Files.writeString(src.resolve("d8/Walker.java"), WALKER);
        int exit = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-d", out.toString(),
            src.resolve("d8/Mod.java").toString(), src.resolve("d8/Walker.java").toString());
        if (exit != 0) {
            throw new IllegalStateException("the Java 21 mod does not compile");
        }
        return StandinClasses.readClasses(out);
    }

    static Map<String, byte[]> classesOf(Map<String, byte[]> entries) {
        Map<String, byte[]> out = new HashMap<>();
        entries.forEach((n, b) -> {
            if (n.endsWith(".class")) {
                out.put(n.substring(0, n.length() - 6), b);
            }
        });
        return out;
    }

    static String run(Map<String, byte[]> classes) throws Exception {
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
        Method run = loader.loadClass("d8.Mod").getMethod("run");
        return (String) run.invoke(null);
    }

    /** Each JDK class and member a class's code names: (owner, "name(desc)") for calls, (owner, "class") for types. */
    static void jdkCalls(byte[] bytes, java.util.function.BiConsumer<String, String> found) {
        new org.objectweb.asm.ClassReader(bytes).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        if (owner.startsWith("java/")) {
                            found.accept(owner, n + d);
                        }
                    }

                    @Override
                    public void visitTypeInsn(int op, String type) {
                        if (type.startsWith("java/")) {
                            found.accept(type, "class");
                        }
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String n, String d, org.objectweb.asm.Handle bsm, Object... a) {
                        found.accept(bsm.getOwner(), bsm.getName() + bsm.getDesc());
                        for (Object o : a) {
                            if (o instanceof org.objectweb.asm.Handle h && h.getOwner().startsWith("java/")) {
                                found.accept(h.getOwner(), h.getName() + h.getDesc());
                            }
                        }
                    }
                };
            }
        }, 0);
    }
}

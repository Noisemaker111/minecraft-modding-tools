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
                    out.append(http()).append('\\n').append(exchanges());
                } catch (Exception e) {
                    out.append(e);
                }
                try {
                    out.append('\\n').append(files());
                } catch (Exception e) {
                    out.append(e);
                }
                return out.toString();
            }

            // What a mod leans on java.net.http for past one plain exchange: any method, a GET with a body, a timeout
            // per request sent (a redirect's again) by the clock, a body cut short, a stream read as it arrives, a file written as it arrives, and a
            // server's credentials kept from the other origin it redirects to.
            interface Step {
                String run() throws Exception;
            }

            // Each step's answer or what it threw: one step failing hides none of the others.
            static String each(Step... steps) {
                StringBuilder out = new StringBuilder();
                for (Step step : steps) {
                    try {
                        out.append(step.run());
                    } catch (Exception e) {
                        out.append(e.getClass().getSimpleName());
                    }
                    out.append('|');
                }
                return out.toString();
            }

            static String timed(HttpClient client, String uri) throws Exception {
                try {
                    client.send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofMillis(1000)).build(), HttpResponse.BodyHandlers.ofString());
                    return "in time";
                } catch (java.net.http.HttpTimeoutException e) {
                    return "timed out";
                }
            }

            static String exchanges() throws Exception {
                String base = System.getProperty("d8.http");
                HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
                java.nio.file.Path file = java.nio.file.Paths.get(System.getProperty("d8.file"));
                return each(
                    () -> client.send(HttpRequest.newBuilder(URI.create(base + "/echo")).header("X-A", "3").method("PATCH", HttpRequest.BodyPublishers.ofString("p")).build(),
                        HttpResponse.BodyHandlers.ofString()).body(),
                    () -> client.send(HttpRequest.newBuilder(URI.create(base + "/echo")).header("X-A", "4").method("GET", HttpRequest.BodyPublishers.ofString("g")).build(),
                        HttpResponse.BodyHandlers.ofString()).body(),
                    () -> timed(client, base + "/slow-moved"),
                    () -> timed(client, System.getProperty("d8.trickle")),
                    () -> client.send(HttpRequest.newBuilder(URI.create(base + "/slow-body")).timeout(Duration.ofMillis(1000)).build(), HttpResponse.BodyHandlers.ofString()).body(),
                    () -> {
                        try {
                            return "whole " + client.send(HttpRequest.newBuilder(URI.create(base + "/short")).build(), HttpResponse.BodyHandlers.ofString()).body().length();
                        } catch (java.io.IOException e) {
                            return "cut short";
                        }
                    },
                    () -> {
                        java.nio.file.Files.write(file, "old".getBytes());
                        String saved;
                        try {
                            client.send(HttpRequest.newBuilder(URI.create(base + "/short")).build(), HttpResponse.BodyHandlers.ofFile(file));
                            saved = "saved";
                        } catch (java.io.IOException e) {
                            saved = "cut short";
                        }
                        return saved + " " + new String(java.nio.file.Files.readAllBytes(file));
                    },
                    () -> {
                        try (java.io.BufferedReader live = new java.io.BufferedReader(new java.io.InputStreamReader(
                            client.send(HttpRequest.newBuilder(URI.create(base + "/live")).build(), HttpResponse.BodyHandlers.ofInputStream()).body()))) {
                            String first = live.readLine();
                            client.send(HttpRequest.newBuilder(URI.create(base + "/release")).build(), HttpResponse.BodyHandlers.discarding());
                            return first + " " + live.readLine();
                        }
                    },
                    () -> {
                        byte[] got = java.nio.file.Files.readAllBytes(client.send(HttpRequest.newBuilder(URI.create(base + "/to-file")).build(), HttpResponse.BodyHandlers.ofFile(file)).body());
                        return got.length + " " + new String(got, got.length - 8, 8);
                    },
                    () -> client.send(HttpRequest.newBuilder(URI.create(base + "/same")).header("Authorization", "secret").header("Cookie", "c=1").build(),
                        HttpResponse.BodyHandlers.ofString()).body(),
                    () -> client.send(HttpRequest.newBuilder(URI.create(base + "/cross")).header("Authorization", "secret").header("Cookie", "c=1").build(),
                        HttpResponse.BodyHandlers.ofString()).body());
            }

            // FileWriter and FileReader of a charset (Java 11's constructors), through append and read(CharBuffer).
            static String files() throws Exception {
                java.io.File f = new java.io.File(System.getProperty("d8.file") + ".txt");
                try (java.io.FileWriter w = new java.io.FileWriter(f, java.nio.charset.StandardCharsets.UTF_16LE)) {
                    w.append("h\u00e9").append("xyz", 1, 2).append('!');
                }
                StringBuilder out = new StringBuilder(HexFormat.of().formatHex(java.nio.file.Files.readAllBytes(f.toPath()))).append(' ');
                try (java.io.FileReader r = new java.io.FileReader(f, java.nio.charset.StandardCharsets.UTF_16LE)) {
                    java.nio.CharBuffer chars = java.nio.CharBuffer.allocate(16);
                    r.read(chars);
                    chars.flip();
                    out.append(chars);
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

    // A library nested in the mod whose method returns a JDK class Java 8 lacks, and the mod calling it.
    static final String LIB = """
        package d8n;

        import java.util.HexFormat;

        public final class Lib {
            public static HexFormat formatter() {
                return HexFormat.of().withUpperCase();
            }
        }
        """;

    static final String CALLER = """
        package d8n;

        public final class Caller {
            public static String run() {
                return Lib.formatter().formatHex(new byte[] { 1, (byte) 0xab });
            }
        }
        """;

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]).resolve("java-lowering"));
        Map<String, byte[]> classes = compile(scratch, "d8", Map.of("Mod", MOD, "Walker", WALKER));

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
        com.sun.net.httpserver.HttpServer other = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.ExecutorService handlers = java.util.concurrent.Executors.newCachedThreadPool();
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch> released = new java.util.concurrent.atomic.AtomicReference<>();
        Path download = scratch.resolve("download.bin");
        com.sun.net.httpserver.HttpHandler handler = exchange -> {
            try {
                serve(exchange, other, released, download);
            } catch (IOException | InterruptedException expected) {
                // a client that gave up, or a body cut short on purpose
            } finally {
                exchange.close();
            }
        };
        server.createContext("/", handler);
        other.createContext("/", handler);
        server.setExecutor(handlers);
        other.setExecutor(handlers);
        server.start();
        other.start();
        // Headers sent a byte at a time, each well inside the request's timeout and all of them well past it.
        java.net.ServerSocket trickle = new java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"));
        Thread trickling = new Thread(() -> {
            while (!trickle.isClosed()) {
                try (java.net.Socket s = trickle.accept()) {
                    BufferedReader request = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                    while (!request.readLine().isEmpty()) {
                        continue;
                    }
                    OutputStream out = s.getOutputStream();
                    out.write("HTTP/1.1 200 OK\r\nX-Slow: ".getBytes(StandardCharsets.ISO_8859_1));
                    for (int i = 0; i < 12; i++) {
                        out.write('a');
                        out.flush();
                        Thread.sleep(200);
                    }
                    out.write("\r\nContent-Length: 2\r\n\r\nok".getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                } catch (IOException | InterruptedException | NullPointerException gone) {
                    // the client gave up, or the server is closing
                }
            }
        });
        trickling.setDaemon(true);
        trickling.start();
        String before, after;
        try {
            System.setProperty("d8.http", "http://127.0.0.1:" + server.getAddress().getPort());
            System.setProperty("d8.file", download.toString());
            System.setProperty("d8.trickle", "http://127.0.0.1:" + trickle.getLocalPort() + "/");
            before = run(classes);
            after = run(classesOf(ported));
        } finally {
            server.stop(0);
            other.stop(0);
            trickle.close();
            handlers.shutdownNow();
        }
        JarPortTest.expect(after.equals(before), "the ported mod gives what the original gives:\n" + before + "\n--- ported ---\n" + after);
        JarPortTest.expect(before.contains("PATCH 3 p|GET 4 g|in time|timed out|late body|cut short|cut short 0123456789|first second|8200 streamed|secret c=1|null null|"),
            "the run covers any method, a GET with a body, a deadline per request by the clock, a body cut short, a live stream, a file"
                + " written as it arrives and credentials kept from another origin: " + before);
        JarPortTest.expect(before.contains("6800e90079002100 h\u00e9y!"), "the run covers FileWriter and FileReader of a charset through append and read(CharBuffer): " + before);
        JarPortTest.expect(before.contains("Point[x=1, y=2, z=3.5, name=a, data=[I@] true false true") && before.contains("red;r;tone green;g;tone blue;other"),
            "the run covers records and pattern switches: " + before);
        JarPortTest.expect(before.contains("01ab Index out of range: 3") && before.contains("200 GET 1  /echo yes|200 POST 2 sent|404 nope|302 /echo"),
            "the run covers a JDK class Java 8 lacks (HexFormat, java.net.http) and a constructor it lacks: " + before);

        // Java 8's and 17's HttpURLConnection end a body the server cut short as if it were whole (Java 25's fails it, so
        // the run above cannot tell): the stand-in's body stream fails it on any Java, as java.net.http does.
        String bodyClass = ported.keySet().stream().filter(n -> n.endsWith("/java/net/http/HttpClient$Body.class")).findFirst().orElse("none");
        String cut;
        try {
            Class<?> body = load(classesOf(ported)).loadClass(bodyClass.replace('/', '.').replace(".class", ""));
            var make = body.getDeclaredConstructor(InputStream.class, java.net.HttpURLConnection.class, long.class);
            make.setAccessible(true);
            InputStream read = (InputStream) make.newInstance(new ByteArrayInputStream("0123456789".getBytes(StandardCharsets.UTF_8)), null, 100L);
            read.readAllBytes();
            cut = "read whole";
        } catch (IOException e) {
            cut = "cut short";
        } catch (ReflectiveOperationException e) {
            cut = e.toString();
        }
        JarPortTest.expect(cut.equals("cut short"), "a body ending before the length its response announced fails the read: " + cut);

        String fmj = new String(ported.get("fabric.mod.json"), StandardCharsets.UTF_8), mixins = new String(ported.get("d8.mixins.json"), StandardCharsets.UTF_8);
        JarPortTest.expect(fmj.contains("\">=8\"") && mixins.contains("\"JAVA_8\""), "the metadata asks for the Java the mod now runs on: " + fmj + " " + mixins);

        // A release whose Java reads the classes as they are (Java 21) leaves them so.
        List<Map<String, Object>> sameRuns = new ArrayList<>();
        Map<String, byte[]> same = JarPortTest.entries(porter(scratch, 21).port(jar, "d8.jar", sameRuns, null));
        JarPortTest.expect(Downgrade.major(same.get("d8/Mod.class")) == 65 && Json.array(sameRuns.get(0).get("unlinked")).isEmpty()
            && same.keySet().stream().noneMatch(n -> n.startsWith("mmt/")), "a mod the release's Java runs is left as it came: " + same.keySet());

        // A library nested in the mod and the mod's call to it name the stand-in for HexFormat alike: one copy, carried
        // by the outermost jar, which the loader (one class loader for every mod and nested jar on Fabric) finds for both.
        Map<String, byte[]> both = compile(scratch.resolve("nested"), "d8n", Map.of("Lib", LIB, "Caller", CALLER));
        Map<String, byte[]> libFiles = new TreeMap<>();
        libFiles.put("d8n/Lib.class", both.get("d8n/Lib"));
        libFiles.put("fabric.mod.json", "{\"schemaVersion\": 1, \"id\": \"d8lib\", \"version\": \"1\", \"depends\": {\"java\": \">=21\"}}".getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> outerFiles = new TreeMap<>();
        outerFiles.put("d8n/Caller.class", both.get("d8n/Caller"));
        outerFiles.put("META-INF/jars/d8lib.jar", JarPortTest.jar(libFiles));
        outerFiles.put("fabric.mod.json", "{\"schemaVersion\": 1, \"id\": \"d8n\", \"version\": \"1\", \"jars\": [{\"file\": \"META-INF/jars/d8lib.jar\"}]}"
            .getBytes(StandardCharsets.UTF_8));
        byte[] outerJar = porter(scratch, 8).port(JarPortTest.jar(outerFiles), "d8n.jar", new ArrayList<>(), null);
        Files.write(scratch.resolve("d8n-java8.jar"), outerJar); // kept to run on a real Java 8 by hand
        Map<String, byte[]> outer = JarPortTest.entries(outerJar);
        Map<String, byte[]> inner = JarPortTest.entries(outer.get("META-INF/jars/d8lib.jar"));
        Map<String, byte[]> loaded = classesOf(outer);
        loaded.putAll(classesOf(inner));
        String called = (String) load(loaded).loadClass("d8n.Caller").getMethod("run").invoke(null);
        JarPortTest.expect(called.equals("01AB") && inner.keySet().stream().noneMatch(n -> n.startsWith("mmt/"))
            && outer.keySet().stream().anyMatch(n -> n.matches("mmt/standin/m[0-9a-f]+/java/util/HexFormat\\.class")),
            "a mod's call into its nested library through a JDK class Java 8 lacks links, the stand-in carried once in the outermost jar: "
                + called + " " + outer.keySet() + " " + inner.keySet());

        JarPortTest.finish("JarPortJavaTest");
    }

    /** The local server the mod's java.net.http calls go to: each path one thing a client must get right. */
    static void serve(com.sun.net.httpserver.HttpExchange exchange, com.sun.net.httpserver.HttpServer other,
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch> released, Path download) throws IOException, InterruptedException {
        String path = exchange.getRequestURI().getPath();
        byte[] body = exchange.getRequestBody().readAllBytes();
        var headers = exchange.getRequestHeaders();
        String reply = exchange.getRequestMethod() + " " + headers.getFirst("X-A") + " " + new String(body, StandardCharsets.UTF_8);
        int status = 200;
        switch (path) {
            case "/moved" -> {
                exchange.getResponseHeaders().add("Location", "/echo");
                status = 302;
                reply = "";
            }
            case "/missing" -> {
                status = 404;
                reply = "nope";
            }
            case "/slow-moved", "/slow" -> {
                // Each hop in less than the request's timeout, both together in more: the JDK times each hop.
                Thread.sleep(700);
                if (path.equals("/slow-moved")) {
                    exchange.getResponseHeaders().add("Location", "/slow");
                    status = 302;
                    reply = "";
                }
            }
            case "/slow-body" -> {
                // Headers at once, the body after longer than the request's timeout, which ends at the headers.
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().flush();
                Thread.sleep(1500);
                exchange.getResponseBody().write("late body".getBytes(StandardCharsets.UTF_8));
                return;
            }
            case "/short" -> {
                exchange.sendResponseHeaders(200, 100);
                exchange.getResponseBody().write("0123456789".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                return;
            }
            case "/live" -> {
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                released.set(latch);
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("first\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                // Released by the client's next request, which it sends only once it has read the first line.
                exchange.getResponseBody().write((latch.await(5, java.util.concurrent.TimeUnit.SECONDS) ? "second\n" : "late\n").getBytes(StandardCharsets.UTF_8));
                return;
            }
            case "/release" -> {
                released.get().countDown();
                status = 204;
                reply = "";
            }
            case "/to-file" -> {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(new byte[8192]);
                exchange.getResponseBody().flush();
                // A client writing the body as it arrives has the first part on disk before the rest is sent.
                long until = System.nanoTime() + 5_000_000_000L;
                while (Files.size(download) != 8192 && System.nanoTime() < until) {
                    Thread.sleep(5);
                }
                exchange.getResponseBody().write((Files.size(download) == 8192 ? "streamed" : "buffered").getBytes(StandardCharsets.UTF_8));
                return;
            }
            case "/same" -> {
                exchange.getResponseHeaders().add("Location", "/auth");
                status = 302;
                reply = "";
            }
            case "/cross" -> {
                exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + other.getAddress().getPort() + "/auth");
                status = 302;
                reply = "";
            }
            case "/auth" -> reply = headers.getFirst("Authorization") + " " + headers.getFirst("Cookie");
            default -> {
            }
        }
        exchange.getResponseHeaders().add("X-B", "yes");
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        byte[] out = reply.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
        exchange.getResponseBody().write(out);
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

    static Map<String, byte[]> compile(Path scratch, String pkg, Map<String, String> sources) throws IOException {
        Path src = scratch.resolve("src"), out = scratch.resolve("classes");
        Files.createDirectories(src.resolve(pkg));
        Files.createDirectories(out);
        List<String> args = new ArrayList<>(List.of("--release", "21", "-d", out.toString()));
        for (var e : sources.entrySet()) {
            Path file = src.resolve(pkg).resolve(e.getKey() + ".java");
            Files.writeString(file, e.getValue());
            args.add(file.toString());
        }
        int exit = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new));
        if (exit != 0) {
            throw new IllegalStateException("the Java 21 classes do not compile");
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
        Method run = load(classes).loadClass("d8.Mod").getMethod("run");
        return (String) run.invoke(null);
    }

    static ClassLoader load(Map<String, byte[]> classes) {
        return new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) {
                    return Class.forName(name, false, ClassLoader.getPlatformClassLoader());
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
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

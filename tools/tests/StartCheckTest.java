import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;

/**
 * start-check without a game: its agent run for real on a stand-in game (a main that starts a "Server thread", a client
 * that asks GLFW for a window), and how a run's events and log are turned into the jars at fault.
 * - The server thread's work never happens and the window is never asked for, whatever the start does first.
 * - A start that fails by itself leaves no "stop": start-check reads that as a failed start, not as a pass.
 * - A game class that does not load with its mixins is the fault of the mod its error names, not of the first mod that
 *   mixes into the class; a loader's "recommends" warning is nobody's fault; a data pack that fails names its jar.
 * Usage: StartCheckTest <harness root>
 */
public final class StartCheckTest {

    static int count;

    public static void main(String[] args) throws Exception {
        Path harness = Path.of(args[0]), scratch = Files.createTempDirectory("mmt-startcheck");
        try {
            Path agent = agent(harness, scratch);
            server(agent, scratch);
            client(agent, scratch);
            blame(harness);
            System.out.println(count + " start-check checks passed: the agent ends a start before its server thread works and before its window, "
                + "a failed start is not a pass, and each failure is traced to the jar it names");
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }

    static void expect(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        count++;
    }

    static void compile(Path out, List<Path> classpath, Map<String, String> sources, Path scratch) throws Exception {
        Path folder = Files.createTempDirectory(scratch, "src");
        List<String> arguments = new ArrayList<>(List.of("--release", "8", "-Xlint:-options", "-nowarn", "-encoding", "UTF-8", "-d", out.toString()));
        if (!classpath.isEmpty()) {
            arguments.add("-cp");
            arguments.add(String.join(java.io.File.pathSeparator, classpath.stream().map(Path::toString).toList()));
        }
        for (var source : sources.entrySet()) {
            Path file = folder.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            arguments.add(file.toString());
        }
        expect(ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)) == 0, "the stand-in compiles: " + sources.keySet());
    }

    /** The agent's jar, from the sources mmt ships, for Java 8 and with the manifest mmt gives it. */
    static Path agent(Path harness, Path scratch) throws Exception {
        Path stubs = Files.createDirectories(scratch.resolve("stubs")), classes = Files.createDirectories(scratch.resolve("agent"));
        List<String> first = new ArrayList<>(List.of("--release", "8", "-Xlint:-options", "-nowarn", "-encoding", "UTF-8", "-d", stubs.toString()));
        for (String name : MixinCheck.STUBS) first.add(harness.resolve(name).toString());
        expect(ToolProvider.getSystemJavaCompiler().run(null, null, null, first.toArray(String[]::new)) == 0, "Mixin's API stubs compile");
        List<String> second = new ArrayList<>(List.of("--release", "8", "-Xlint:-options", "-nowarn", "-encoding", "UTF-8", "-cp", stubs.toString(), "-d", classes.toString()));
        for (String name : StartCheck.AGENT_SOURCES) second.add(harness.resolve(name).toString());
        expect(ToolProvider.getSystemJavaCompiler().run(null, null, null, second.toArray(String[]::new)) == 0, "the agent compiles for Java 8");
        Path jar = scratch.resolve("mmt-nogame.jar");
        StartCheck.agentJar(classes, jar);
        return jar;
    }

    /** One stand-in start under the agent: {exit, the events' text, the output}. */
    static String[] start(Path agent, String side, Path classes, String main, Path folder, String... arguments) throws Exception {
        Path events = folder.resolve("events.jsonl");
        Files.deleteIfExists(events);
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-javaagent:" + agent + "=" + side,
            "-Dmmt.mixincheck.out=" + events, "-cp", classes.toString(), main, folder.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        expect(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "the stand-in start ends: " + out);
        return new String[] { String.valueOf(process.exitValue()), Files.isRegularFile(events) ? Files.readString(events) : "", out };
    }

    // ------------------------------------------------------------------ the server
    static void server(Path agent, Path scratch) throws Exception {
        Path classes = Files.createDirectories(scratch.resolve("server"));
        // The server thread's first act differs from release to release: a line on the console, or a class it has not met.
        compile(classes, List.of(), Map.of(
            "net/minecraft/server/Main.java",
            "package net.minecraft.server;\n" +
            "import java.nio.file.*;\n" +
            "public class Main {\n" +
            "  public static void main(String[] a) throws Exception {\n" +
            "    Files.write(Paths.get(a[0], \"data-read\"), new byte[0]);\n" +
            "    if (a[1].equals(\"crash\")) throw new IllegalStateException(\"a mod's start code failed\");\n" +
            "    final String first = a[1];\n" +
            "    final String folder = a[0];\n" +
            "    Thread server = new Thread(new Runnable() { public void run() {\n" +
            "      try {\n" +
            "        if (first.equals(\"prints\")) System.out.println(\"Starting minecraft server\");\n" +
            "        if (first.equals(\"loads\")) new World();\n" +
            "        Files.write(Paths.get(folder, \"world-opened\"), new byte[0]);\n" +
            "      } catch (Exception e) { throw new RuntimeException(e); }\n" +
            "    } }, \"Server thread\");\n" +
            "    server.start();\n" +
            "    server.join();\n" +
            "  }\n" +
            "}\n" +
            "class World { }\n"
        ), scratch);
        for (String first : List.of("prints", "loads")) {
            Path folder = Files.createTempDirectory(scratch, "run");
            String[] run = start(agent, "server", classes, "net.minecraft.server.Main", folder, first);
            expect(run[0].equals("0") && run[1].contains("\"event\":\"stop\"") && run[1].contains("the game started its server thread"),
                "a start whose server thread first " + first + " ends at that thread, and says so: " + Arrays.toString(run));
            expect(Files.exists(folder.resolve("data-read")) && !Files.exists(folder.resolve("world-opened")),
                "the game's main ran and its server thread did no work (" + first + ")");
            expect(run[1].contains("has no Mixin"), "a launch with no Mixin says no mixins were read: " + run[1]);
        }
        // Asked only whether the mixins apply, a start ends once they are read: nothing of the game's own start-up runs.
        Path asked = Files.createTempDirectory(scratch, "run");
        String[] mixins = start(agent, "server,mixins", classes, "net.minecraft.server.Main", asked, "prints");
        expect(mixins[0].equals("0") && mixins[1].contains("the mixins were read") && !Files.exists(asked.resolve("data-read")),
            "a start asked only for its mixins ends before the game's main runs: " + Arrays.toString(mixins));
        Path folder = Files.createTempDirectory(scratch, "run");
        String[] crashed = start(agent, "server", classes, "net.minecraft.server.Main", folder, "crash");
        expect(!crashed[0].equals("0") && !crashed[1].contains("\"event\":\"stop\"") && crashed[2].contains("a mod's start code failed"),
            "a start that fails by itself ends with no stop and its own words: " + Arrays.toString(crashed));
    }

    // ------------------------------------------------------------------ the client
    static void client(Path agent, Path scratch) throws Exception {
        // GLFW's class differs between LWJGL builds: two-slot constants before the method, and the method first or last.
        List<String> shapes = List.of(
            "public static final long BIG = 1234567890123L; public static final double RATIO = 2.5;\n" +
            "  public static String glfwGetVersionString() { return \"3.3 \" + BIG; }\n  public static boolean glfwInit() { opened = true; return true; }\n",
            "public static boolean glfwInit() { try { opened = true; return true; } finally { tries++; } }\n  static int tries;\n" +
            "  public static String glfwGetVersionString() { return \"3.4\"; }\n"
        );
        // early: something the mixins are read through loads GLFW before the game's own code does.
        for (String shape : List.of(shapes.get(0), shapes.get(1), "early")) {
            Path classes = Files.createTempDirectory(scratch, "client");
            boolean early = shape.equals("early");
            if (early) {
                compile(classes, List.of(), Map.of("org/spongepowered/asm/mixin/Mixins.java",
                    "package org.spongepowered.asm.mixin;\npublic class Mixins {\n  static { try { Class.forName(\"org.lwjgl.glfw.GLFW\"); } catch (Exception e) { throw new Error(e); } }\n}\n"), scratch);
                shape = shapes.get(0);
            }
            compile(classes, List.of(classes), Map.of(
                "org/lwjgl/glfw/GLFW.java", "package org.lwjgl.glfw;\npublic class GLFW {\n  public static boolean opened;\n  " + shape + "}\n",
                "net/minecraft/client/main/Main.java",
                "package net.minecraft.client.main;\n" +
                "import java.nio.file.*;\n" +
                "import org.lwjgl.glfw.GLFW;\n" +
                "public class Main {\n" +
                "  public static void main(String[] a) throws Exception {\n" +
                "    Files.write(Paths.get(a[0], \"version\"), GLFW.glfwGetVersionString().getBytes());\n" +
                "    boolean made = GLFW.glfwInit();\n" +
                "    Files.write(Paths.get(a[0], \"window-\" + made + \"-\" + GLFW.opened), new byte[0]);\n" +
                "  }\n" +
                "}\n"
            ), scratch);
            Path folder = Files.createTempDirectory(scratch, "run");
            String[] run = start(agent, "client", classes, "net.minecraft.client.main.Main", folder);
            expect(run[0].equals("0") && run[1].contains("the game asked for its window"), "a client ends where it asks for its window: " + Arrays.toString(run));
            expect(Files.readString(folder.resolve("version")).startsWith("3."), "GLFW's other methods run as they were");
            try (var left = Files.list(folder)) {
                expect(left.noneMatch(p -> p.getFileName().toString().startsWith("window-")), "the window call never returned, and its own code never ran");
            }
            // Without the agent the same stand-in opens its window: the check above cannot pass by the stand-in's own fault.
            Process plain = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classes.toString(), "net.minecraft.client.main.Main",
                folder.toString()).redirectErrorStream(true).start();
            plain.getInputStream().readAllBytes();
            plain.waitFor();
            expect(Files.exists(folder.resolve("window-true-true")), "the stand-in asks for a window when nothing ends it");
        }
    }

    // ------------------------------------------------------------------ whose fault
    static StartCheck.Launch launch(List<Map<String, Object>> events) {
        return new StartCheck.Launch("", events, Path.of("run.log"), 1.0, 0, false);
    }

    static void blame(Path harness) throws Exception {
        PackCheck owner = new PackCheck(harness, null, null);
        owner.scan.mixins.put("lib.mixins.json", "lib.jar");
        owner.scan.mixins.put("bad.mixins.json", "bad.jar");
        owner.scan.mixins.put("inner.mixins.json", "outer.jar!META-INF/jars/inner.jar");
        owner.scan.providers.computeIfAbsent("named", k -> new ArrayList<>()).add(new PackMods.Provider("named.jar", "1.0", false, "fabric.mod.json"));
        Set<String> present = new LinkedHashSet<>(List.of("lib.jar", "bad.jar", "outer.jar", "named.jar"));
        String refused = "Mixin transformation of net.minecraft.class_3218 failed\n  caused by InjectionError: Critical injection failure: Callback method x in bad.mixins.json:ServerLevelMixin from mod bad failed injection check";
        // Several mods mix into one class; the class is listed under the first config that targets it.
        var blamed = StartCheck.refusedByMixin(owner, launch(List.of(
            Json.map("event", "target", "target", "net.minecraft.class_3218", "config", "lib.mixins.json", "loaded", false, "exception", "java.lang.RuntimeException", "message", refused))), present);
        expect(blamed.keySet().equals(Set.of("bad.jar")) && blamed.get("bad.jar").contains("failed injection check"), "the mod the error names is at fault, not the first to target the class: " + blamed);
        blamed = StartCheck.refusedByMixin(owner, launch(List.of(
            Json.map("event", "target", "target", "a.B", "config", "lib.mixins.json", "loaded", false, "exception", "java.lang.VerifyError", "message", "Bad type on operand stack from mod named"))), present);
        expect(blamed.keySet().equals(Set.of("named.jar")), "an error that names only a mod id blames the jar that provides it: " + blamed);
        blamed = StartCheck.refusedByMixin(owner, launch(List.of(
            Json.map("event", "error", "phase", "apply", "config", "inner.mixins.json", "mixin", "a.M", "target", "a.B", "action", "ERROR", "message", "@Shadow field x was not located"))), present);
        expect(blamed.keySet().equals(Set.of("outer.jar")) && blamed.get("outer.jar").contains("@Shadow field x was not located"), "a nested jar's mixin fails the jar that ships it: " + blamed);
        blamed = StartCheck.refusedByMixin(owner, launch(List.of(
            Json.map("event", "error", "phase", "apply", "config", "bad.mixins.json", "mixin", "a.M", "action", "WARN", "message", "an optional mixin did not apply"),
            Json.map("event", "target", "target", "net.minecraft.client.X", "config", "bad.mixins.json", "loaded", false, "exception", "java.lang.ClassNotFoundException", "message", "net.minecraft.client.X"))), present);
        expect(blamed.isEmpty(), "a mixin Mixin only warns about, and a class this side does not have, fail nobody: " + blamed);
        blamed = StartCheck.refusedByMixin(owner, launch(List.of(
            Json.map("event", "target", "target", "a.B", "config", "bad.mixins.json", "loaded", false, "exception", "java.lang.RuntimeException", "message", refused))), Set.of("lib.jar"));
        expect(blamed.isEmpty(), "a jar that is not in the run is not blamed for it: " + blamed);

        // A jar the game names is at fault and one only near the failure is not; with nobody named, the jars near it are, unsure.
        Map<String, Object> namedOne = Json.map("jar", "bad.jar", "why", List.of("the loader names it: Failed to parse bad:worldgen/x.json from pack bad"));
        Map<String, Object> nearOne = Json.map("jar", "lib.jar", "why", List.of("a mixin handler of this mod is in the stack"));
        Map<String, Object> both = Json.map("jar", "named.jar", "why", List.of("its code (a.B) is in the stack: at a.B.c", "the loader names it: Mod named requires x"));
        Set<String> unsure = new LinkedHashSet<>();
        var fault = StartCheck.atFault(List.of(nearOne, namedOne, both), present, unsure);
        expect(fault.keySet().equals(Set.of("bad.jar", "named.jar")) && unsure.isEmpty(), "with a jar named, one only near the failure is a bystander: " + fault + " " + unsure);
        fault = StartCheck.atFault(List.of(nearOne), present, unsure);
        expect(fault.keySet().equals(Set.of("lib.jar")) && unsure.equals(Set.of("lib.jar")), "with nobody named, the jar near the failure is failed and marked to be started again: " + fault + " " + unsure);
        fault = StartCheck.atFault(List.of(namedOne), Set.of("lib.jar"), unsure);
        expect(fault.isEmpty(), "a jar that is not in the run is not at fault for it: " + fault);

        // What the loader says of a start that failed: a recommendation is no fault, a data pack that fails is its jar's.
        PackLoaders.Loader fabric = PackLoaders.find(harness, "fabric");
        PackMods.Scan scan = new PackMods.Scan();
        for (String id : List.of("polite", "broken", "datamod")) scan.providers.computeIfAbsent(id, k -> new ArrayList<>()).add(new PackMods.Provider(id + ".jar", "1.0", false, "fabric.mod.json"));
        String log = String.join("\n",
            "[main/WARN]: Warnings were found!",
            "\t - Mod 'Polite' (polite) 1.0 recommends any version of modmenu, which is missing!",
            "[main/ERROR]: Incompatible mods found!",
            "net.fabricmc.loader.impl.FormattedException: Some of your mods are incompatible with the game or each other!",
            "\t - Mod 'Broken' (broken) 2.0 requires version 1.7 or later of mod 'Lib' (lib), but only the wrong version is present: 1.3!");
        List<String> named = PackChecks.suspects(log, List.of(), scan, fabric).stream().map(s -> Json.text(s, "jar", "")).toList();
        expect(named.equals(List.of("broken.jar")), "the mod the loader refuses is named, the one it only advises is not: " + named);
        String data = String.join("\n",
            "[main/ERROR]: Registry loading errors:",
            "java.lang.IllegalStateException: Failed to parse datamod:worldgen/biome/placeholder.json from pack datamod",
            "[main/WARN]: Failed to load datapacks, can't proceed with server load. You can either fix your datapacks or reset to vanilla with --safeMode");
        named = PackChecks.suspects(data, List.of(), scan, fabric).stream().map(s -> Json.text(s, "jar", "")).toList();
        expect(named.equals(List.of("datamod.jar")), "a data pack the release refuses names the jar it is in: " + named);
        // NeoForge names a jar whose mod class it did not find by its file, in a folder of the run.
        for (String folder : List.of("C:\\packs\\server-1\\mods\\", "/home/packs/server-1/mods/")) {
            String unbuilt = "[main/FATAL] [ne.ne.fm.ModLoader/LOADING]: File " + folder + "broken.jar constructed 0 mods: [], but had 1 mods specified: [broken]\n"
                + "[main/FATAL] [ne.ne.fm.ModLoader/CORE]: Failed to initialize mod containers";
            named = PackChecks.suspects(unbuilt, List.of(), scan, PackLoaders.find(harness, "neoforge")).stream().map(s -> Json.text(s, "jar", "")).toList();
            expect(named.equals(List.of("broken.jar")), "a jar whose mod class the loader did not find is named by its file: " + named);
        }
    }
}

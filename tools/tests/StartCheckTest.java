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
            otherSide(agent, scratch, harness);
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
    /** A stand-in client: a window library (its class's source) and a main that asks it for a window (call, a boolean) and notes what came back. */
    static Path clientWith(Path scratch, String library, String source, String call, boolean early) throws Exception {
        Path classes = Files.createTempDirectory(scratch, "client");
        String type = library.substring(library.lastIndexOf('.') + 1);
        if (early) {
            // Something the mixins are read through loads the window library before the game's own code does.
            compile(classes, List.of(), Map.of("org/spongepowered/asm/mixin/Mixins.java",
                "package org.spongepowered.asm.mixin;\npublic class Mixins {\n  static { try { Class.forName(\"" + library + "\"); } catch (Exception e) { throw new Error(e); } }\n}\n"), scratch);
        }
        compile(classes, List.of(classes), Map.of(
            library.replace('.', '/') + ".java", "package " + library.substring(0, library.lastIndexOf('.')) + ";\npublic class " + type + " {\n  public static boolean opened;\n  " + source + "}\n",
            "net/minecraft/client/main/Main.java",
            "package net.minecraft.client.main;\n" +
            "import java.nio.file.*;\n" +
            "public class Main {\n" +
            "  public static void main(String[] a) throws Exception {\n" +
            "    Files.write(Paths.get(a[0], \"version\"), " + library + ".version().getBytes());\n" +
            "    boolean made = " + library + "." + call + ";\n" +
            "    Files.write(Paths.get(a[0], \"window-\" + made + \"-\" + " + library + ".opened), new byte[0]);\n" +
            "  }\n" +
            "}\n"
        ), scratch);
        return classes;
    }

    static void client(Path agent, Path scratch) throws Exception {
        // A window library's class differs between builds: two-slot constants before the method, the method first or last,
        // and the library itself between releases (GLFW; SDL since the releases that make their window with it).
        String glfw = "org.lwjgl.glfw.GLFW", sdl = "org.lwjgl.sdl.SDLInit";
        String first = "public static final long BIG = 1234567890123L; public static final double RATIO = 2.5;\n" +
            "  public static String version() { return \"3.3 \" + BIG; }\n  public static boolean glfwInit() { opened = true; return true; }\n";
        String last = "public static boolean glfwInit() { try { opened = true; return true; } finally { tries++; } }\n  static int tries;\n" +
            "  public static String version() { return \"3.4\"; }\n";
        String flags = "public static String version() { return \"3.\"; }\n  public static boolean SDL_Init(int flags) { opened = flags != 0; return true; }\n" +
            "  public static boolean SDL_InitSubSystem(int flags) { opened = true; return true; }\n";
        // SDL starts itself when a window is asked for, so a game need not start it first: making the window ends the check too.
        String makes = "public static String version() { return \"3.\"; }\n" +
            "  public static long SDL_CreateWindow(CharSequence title, int w, int h, long flags) { opened = true; return 7L; }\n" +
            "  public static long SDL_CreateWindowWithProperties(int properties) { opened = true; return 7L; }\n";
        List<Path> clients = List.of(clientWith(scratch, glfw, first, "glfwInit()", false), clientWith(scratch, glfw, last, "glfwInit()", false),
            clientWith(scratch, glfw, first, "glfwInit()", true), clientWith(scratch, sdl, flags, "SDL_Init(32)", false), clientWith(scratch, sdl, flags, "SDL_InitSubSystem(32)", false),
            clientWith(scratch, "org.lwjgl.sdl.SDLVideo", makes, "SDL_CreateWindow(\"Minecraft\", 854, 480, 2L) != 0", false),
            clientWith(scratch, "org.lwjgl.sdl.SDLVideo", makes, "SDL_CreateWindowWithProperties(3) != 0", false));
        for (Path classes : clients) {
            Path folder = Files.createTempDirectory(scratch, "run");
            String[] run = start(agent, "client", classes, "net.minecraft.client.main.Main", folder);
            expect(run[0].equals("0") && run[1].contains("the game asked for its window"), "a client ends where it asks for its window: " + Arrays.toString(run));
            expect(Files.readString(folder.resolve("version")).startsWith("3."), "the window library's other methods run as they were");
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
        // A window library the agent does not know starts unseen: where the game binds its graphics library is where the
        // check ends then. Loading that library is no sign of a window (26.1 on, the game's main loads Vulkan's first).
        for (String binds : List.of("org.lwjgl.opengl.GL.createCapabilities(true) != null", "org.lwjgl.vulkan.VK10.vkCreateDevice(1L, 2L) == 0")) {
            Path unknown = Files.createTempDirectory(scratch, "client");
            compile(unknown, List.of(), Map.of("org/lwjgl/opengl/GL.java", "package org.lwjgl.opengl;\npublic class GL { public static Object createCapabilities(boolean forward) { return \"\"; } }\n",
                "org/lwjgl/vulkan/VK.java", "package org.lwjgl.vulkan;\npublic class VK { public static String version() { return \"3.\"; } }\n",
                "org/lwjgl/vulkan/VK10.java", "package org.lwjgl.vulkan;\npublic class VK10 { public static int vkCreateDevice(long physical, long info) { return 0; } }\n"), scratch);
            compile(unknown, List.of(unknown), Map.of("org/lwjgl/other/Windows.java",
                "package org.lwjgl.other;\npublic class Windows {\n  public static boolean opened;\n  public static String version() { return org.lwjgl.vulkan.VK.version(); }\n" +
                "  public static boolean open() { opened = true; return " + binds + "; }\n}\n",
                "net/minecraft/client/main/Main.java",
                "package net.minecraft.client.main;\nimport java.nio.file.*;\npublic class Main {\n  public static void main(String[] a) throws Exception {\n" +
                "    Files.write(Paths.get(a[0], \"version\"), org.lwjgl.other.Windows.version().getBytes());\n" +
                "    boolean made = org.lwjgl.other.Windows.open();\n    Files.write(Paths.get(a[0], \"window-\" + made), new byte[0]);\n  }\n}\n"), scratch);
            Path folder = Files.createTempDirectory(scratch, "run");
            String[] run = start(agent, "client", unknown, "net.minecraft.client.main.Main", folder);
            expect(run[0].equals("0") && run[1].contains("the game asked for its graphics library") && !run[1].contains("asked for its window"),
                "a client with a window library nobody knows ends where it binds its graphics library, and says so: " + Arrays.toString(run));
            expect(Files.exists(folder.resolve("version")), "loading a graphics library alone ends nothing");
            try (var left = Files.list(folder)) {
                expect(left.noneMatch(p -> p.getFileName().toString().startsWith("window-")), "nothing after the graphics library was bound ran");
            }
        }
    }

    // ------------------------------------------------------------------ the other side's code
    /** A class of a jar that names a game class this side lacks is no start's to load: what Mixin says of it refuses nobody. */
    static void otherSide(Path agent, Path scratch, Path harness) throws Exception {
        Path client = Files.createDirectories(scratch.resolve("side-client")), server = Files.createDirectories(scratch.resolve("side-server"));
        compile(client, List.of(), Map.of("net/minecraft/client/Screen.java", "package net.minecraft.client;\npublic class Screen { }\n"), scratch);
        compile(server, List.of(client), Map.of(
            "net/minecraft/Both.java", "package net.minecraft;\npublic class Both { }\n",
            "mod/Widget.java", "package mod;\npublic class Widget extends net.minecraft.client.Screen { }\n",
            "mod/Opens.java", "package mod;\npublic class Opens { Object open() { return new net.minecraft.client.Screen(); } }\n",
            "mod/Common.java", "package mod;\npublic class Common { net.minecraft.Both both; String path = \"textures/gui.png\"; }\n"), scratch);
        try (var agentClasses = new java.net.URLClassLoader(new java.net.URL[] {agent.toUri().toURL()}, null);
            var game = new java.net.URLClassLoader(new java.net.URL[] {server.toUri().toURL()}, null)) {
            var asked = agentClasses.loadClass("MmtMixinCheck").getDeclaredMethod("otherSide", String.class, ClassLoader.class, Set.class);
            asked.setAccessible(true);
            expect("net.minecraft.client.Screen".equals(asked.invoke(null, "mod.Widget", game, Set.of())), "a class that extends a game class this side lacks is the other side's code");
            expect("net.minecraft.client.Screen".equals(asked.invoke(null, "mod.Opens", game, Set.of())), "so is one that only makes one in a method");
            expect(asked.invoke(null, "mod.Common", game, Set.of()) == null, "a class that names only game classes this side has is this side's: " + asked.invoke(null, "mod.Common", game, Set.of()));
            expect(asked.invoke(null, "mod.Widget", game, Set.of("net.minecraft.client.Screen")) == null, "a game class that is a mixin target itself is judged on its own, not here");
            expect(asked.invoke(null, "net.minecraft.Both", game, Set.of()) == null && asked.invoke(null, "mod.Gone", game, Set.of()) == null, "the game's own classes and a class that is not there are not asked");
        }
        PackCheck owner = new PackCheck(harness, null, null);
        owner.scan.mixins.put("gui.mixins.json", "gui.jar");
        String refused = "An unexpected critical error was encountered\n  caused by MixinPreProcessorException: Attach error for gui.mixins.json:WidgetMixin from mod gui during activity";
        for (String other : Arrays.asList("net.minecraft.client.Screen", null)) {
            var events = List.of(
                Json.map("event", "error", "phase", "apply", "target", "mod.Widget", "mixin", "gui.WidgetMixin", "config", "gui.mixins.json", "action", "ERROR", "message", refused),
                Json.map("event", "target", "target", "mod.Widget", "config", "gui.mixins.json", "loaded", false, "exception", "org.spongepowered.asm.mixin.transformer.throwables.MixinTransformerError", "message", refused, "otherSide", other));
            var blamed = StartCheck.refusedByMixin(owner, launch(events), Set.of("gui.jar"));
            expect(blamed.isEmpty() == (other != null), "Mixin's refusal in " + (other == null ? "this side's code refuses the jar" : "the other side's code refuses nobody") + ": " + blamed);
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
        var fault = StartCheck.atFault(List.of(nearOne, namedOne), present, unsure);
        expect(fault.keySet().equals(Set.of("bad.jar")) && unsure.isEmpty(), "with a jar named, one only near the failure is a bystander, and the one named is not started again: " + fault + " " + unsure);
        fault = StartCheck.atFault(List.of(nearOne, namedOne, both), present, unsure);
        expect(fault.keySet().equals(Set.of("bad.jar", "named.jar")) && unsure.equals(Set.of("bad.jar", "named.jar")), "two jars one start names are both failed, and each is started again: one may be where the other's fault surfaced: " + fault + " " + unsure);
        unsure.clear();
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
        // NeoForge names a data pack by its mod id, whichever way the jar's metadata writes that id down.
        String inline = "license = 'x'\nauthors = ['a, b', 'c']\nmods = [\n\t{ modId = 'wrapped_pack', version = '1-v4', displayName = 'Wrapped, a [pack]' }, # one\n]\nmixins = [{ config = 'wrapped.mixins.json' }]\n";
        String block = "license = 'x'\n[[mods]]\nmodId = 'wrapped_pack'\nversion = '1-v4'\n[[mixins]]\nconfig = 'wrapped.mixins.json'\n";
        for (String metadata : List.of(inline, block)) {
            PackMods.Scan wrapped = new PackMods.Scan();
            PackMods.toml(metadata, "wrapped.jar", false, "neoforge.mods.toml", "1", wrapped);
            String refusedData = "[main/ERROR] [minecraft/RegistryDataLoader]: Registry loading errors:\n"
                + "java.lang.IllegalStateException: Failed to parse wrapped:enchantment/a.json from pack mod/wrapped_pack";
            named = PackChecks.suspects(refusedData, List.of(), wrapped, PackLoaders.find(harness, "neoforge")).stream().map(s -> Json.text(s, "jar", "")).toList();
            expect(named.equals(List.of("wrapped.jar")), "a data pack NeoForge refuses names its jar, its metadata written " + (metadata == inline ? "as one array" : "in blocks") + ": " + named);
            expect("1-v4".equals(wrapped.providers.get("wrapped_pack").get(0).version()), "its version is read too: " + wrapped.providers);
            expect("wrapped.jar".equals(wrapped.mixins.get("wrapped.mixins.json")), "and its mixin config: " + wrapped.mixins);
        }
        // NeoForge's client logs what its resolver refuses and still goes on to its window: that run did not start.
        String sorted = "[main/ERROR] [loading.ModSorter/LOADING]: Missing or unsupported mandatory dependencies:\n"
            + "\tMod ID: 'lib', Requested by: 'needy', Expected range: '[1.7.7,)', Actual version: '[MISSING]'\n"
            + "[main/ERROR] [loading.ModSorter/LOADING]: Incompatibilities between mods:\n"
            + "\tMod 'picky' is incompatible with 'other', versions: '[0,)'; Version found: '1.0.10'\n";
        PackMods.Scan three = new PackMods.Scan();
        for (String id : List.of("needy", "picky", "other")) three.providers.computeIfAbsent(id, k -> new ArrayList<>()).add(new PackMods.Provider(id + ".jar", "1.0", false, "neoforge.mods.toml"));
        named = PackChecks.suspects(sorted, List.of(), three, PackLoaders.find(harness, "neoforge")).stream().map(s -> Json.text(s, "jar", "")).toList();
        expect(named.equals(List.of("needy.jar", "picky.jar")), "the mod that asks for a missing one and the mod that refuses another are named, the refused one is not: " + named);
        List<Map<String, Object>> atWindow = List.of(Json.map("event", "stop", "reason", StartCheck.CLIENT_STOP, "seconds", 1.0));
        StartCheck.Side clientSide = new StartCheck.Side("client");
        owner.loader = PackLoaders.find(harness, "neoforge");
        StartCheck.Launch refusedRun = new StartCheck.Launch("[main/INFO]: loading\n" + sorted, atWindow, Path.of("run.log"), 1.0, 0, false);
        expect(!StartCheck.started(owner, clientSide, refusedRun), "a client that got to its window after its loader refused jars did not start");
        expect(StartCheck.dependencies(owner, refusedRun).startsWith("not checked"), "and its dependencies are not reported as accepted: " + StartCheck.dependencies(owner, refusedRun));
        for (String half : sorted.split("(?m)^(?=\\[main/ERROR)")) {
            StartCheck.Launch halfRun = new StartCheck.Launch(half, atWindow, Path.of("run.log"), 1.0, 0, false);
            expect(!StartCheck.started(owner, clientSide, halfRun), "either of the resolver's two complaints is a refusal: " + half.lines().findFirst().orElse(""));
        }
        StartCheck.Launch cleanRun = new StartCheck.Launch("[main/INFO]: loading\n", atWindow, Path.of("run.log"), 1.0, 0, false);
        expect(StartCheck.started(owner, clientSide, cleanRun), "a client that got to its window with nothing refused started");
        expect(!StartCheck.started(owner, clientSide, new StartCheck.Launch("[main/INFO]: loading\n", List.of(), Path.of("run.log"), 1.0, 0, false)), "a run that never got to the window did not start");
        owner.loader = null;

        String unended = "mods = [\n\t{ modId = 'open'\n";
        expect(PackMods.blocks(unended).equals(unended), "an array that never ends is left as it was written");
        String plain = "authors = ['a', 'b']\n[[mods]]\nmodId = 'plain'\nlist = [ { inner = 1 } ]\n";
        expect(PackMods.blocks(plain).equals(plain), "an array inside a block is not moved out of it");
    }
}

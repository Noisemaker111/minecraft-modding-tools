import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * A mod whose metadata accepts a release its code was not built for (a build for 1.19 and later that says "minecraft
 * >=1.16.5") names, where the loader starts it, classes neither release has. The link check left such a reference as
 * written (in a mod built for the old release it sits behind a check of the version), so it called the mod working, and
 * the game stopped at start with NoClassDefFoundError. Here the start code is read as the JVM runs it: a class neither
 * release has, used before the start's first branch, in a method it calls there or a class it first uses there, holds
 * the mod back; behind a branch or a try block, in code no start runs, or of another mod, it does not; nor does a class
 * the new release has by Fabric's intermediary name, which the hand-back gives it as it is.
 */
final class JarPortEveryStartTest {

    static final String GONE_API = "net/fabricmc/fabric/api/command/v2/Callback", NEWER_GAME = "net/minecraft/class_7157", NEW_IN_B = "net/minecraft/class_6862";

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args.length > 0 ? args[0] : ".runs/every-start-test"));
        GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
        for (GameDiff.Snap s : List.of(a, b)) {
            JarPortTest.snap(s, "net/fabricmc/fabric/api/command/v1/Commands", 0, null, "register()V");
            JarPortTest.snap(s, "net/minecraft/Util", 0, null, "run()V");
        }
        Path map = scratch.resolve("route-every-start.json");
        Files.writeString(map, "{}");

        Map<String, byte[]> fabric = new LinkedHashMap<>();
        fabric.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"m\",\"entrypoints\":{\"client\":[\"m.Client\"],"
            + "\"main\":[{\"adapter\":\"default\",\"value\":\"m.Main::INSTANCE\"}],\"modmenu\":[\"m.Menu\"]}}").getBytes(StandardCharsets.UTF_8));
        // Starts by reading the callback of an API neither release has: the build for a later release.
        fabric.put("m/Client.class", entry("m/Client", "onInitializeClient", false, false, GONE_API, null));
        // Starts behind a branch (a check of the version) and in a try block: either may never run.
        fabric.put("m/Main.class", entry("m/Main", "onInitialize", true, true, GONE_API, "m/Setup"));
        // Called on every start from Main: its own start names a game class neither release has.
        fabric.put("m/Setup.class", user("m/Setup", "init", "net/minecraft/Future"));
        // An entry point of another mod's stage (a config screen) and code no start runs.
        fabric.put("m/Menu.class", entry("m/Menu", "onInitialize", false, false, GONE_API, null));
        fabric.put("m/Later.class", user("m/Later", "init", GONE_API));
        List<String> unlinked = ported(a, b, map, "fabric", fabric, Map.of());
        JarPortTest.expect(unlinked.stream().anyMatch(u -> u.contains("net.fabricmc.fabric.api.command.v2.Callback (in m.Client.onInitializeClient)")),
            "a platform class neither release has, read where the loader starts the mod, holds it back: " + unlinked);
        JarPortTest.expect(unlinked.stream().anyMatch(u -> u.contains("net.minecraft.Future (in m.Setup.init)")),
            "so does one in a method the start calls on every start: " + unlinked);
        JarPortTest.expect(unlinked.stream().noneMatch(u -> u.contains("m.Main") || u.contains("m.Menu") || u.contains("m.Later")),
            "behind a branch or a try block, in another mod's entry point, or in code no start runs, it does not: " + unlinked);

        // A class the new release has by its intermediary name (a build for the new release read in the old one's names).
        Map<String, byte[]> newer = new LinkedHashMap<>();
        newer.put("n/Main.class", entry("n/Main", "onInitialize", false, false, NEW_IN_B, null));
        newer.put("n/Other.class", entry("n/Other", "onInitialize", false, false, NEWER_GAME, null));
        newer.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"n\",\"entrypoints\":{\"main\":[\"n.Main\",\"n.Other\"]}}".getBytes(StandardCharsets.UTF_8));
        List<String> names = ported(a, b, map, "fabric", newer, Map.of("net/minecraft/tags/TagKey", NEW_IN_B));
        JarPortTest.expect(names.stream().noneMatch(u -> u.contains("class_6862")) && names.stream().anyMatch(u -> u.contains("class_7157")),
            "an intermediary name the new release has links; one it lacks does not: " + names);

        // A jar built against a newer release (its manifest says which) whose start logs through a library that release
        // ships and neither of these does (SLF4J, from 1.18): the class is missing at start. Another mod's class in an
        // area no release's library holds is that mod's, and the same jar with no build release named is not judged.
        EveryStart.LIBRARY_AREAS.put("3.0 org/slf4j/", Set.of("org/slf4j"));
        EveryStart.LIBRARY_AREAS.put("3.0 com/other/", Set.of());
        Map<String, byte[]> built = new LinkedHashMap<>();
        built.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"s\",\"entrypoints\":{\"main\":[\"s.Main\",\"s.Other\"]}}".getBytes(StandardCharsets.UTF_8));
        built.put("s/Main.class", annotated("s/Main", "Ls/Marker;", "org/slf4j/LoggerFactory"));
        built.put("s/Other.class", annotated("s/Other", "Ls/Marker;", "com/other/Api"));
        List<String> unbuilt = ported(a, b, map, "fabric", built, Map.of());
        built.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nFabric-Minecraft-Version: 3.0\r\n".getBytes(StandardCharsets.UTF_8));
        List<String> libs = ported(a, b, map, "fabric", built, Map.of());
        JarPortTest.expect(libs.stream().anyMatch(u -> u.contains("org.slf4j.LoggerFactory (in s.Main.<init>)")) && libs.stream().noneMatch(u -> u.contains("com.other")),
            "a class of a library the jar's build release ships and neither release does holds it back; another mod's does not: " + libs);
        JarPortTest.expect(unbuilt.stream().noneMatch(u -> u.contains("org.slf4j")),
            "with no build release named, a class outside every release's packages is left as written: " + unbuilt);

        // NeoForge starts a mod by constructing its @Mod class.
        Map<String, byte[]> neoforge = new LinkedHashMap<>();
        neoforge.put("META-INF/neoforge.mods.toml", "[[mods]]\nmodId=\"q\"\n".getBytes(StandardCharsets.UTF_8));
        neoforge.put("q/Mod.class", annotated("q/Mod", "Lnet/neoforged/fml/common/Mod;", "net/minecraft/Future"));
        List<String> neo = ported(a, b, map, "neoforge", neoforge, Map.of());
        JarPortTest.expect(neo.stream().anyMatch(u -> u.contains("net.minecraft.Future (in q.Mod.<init>)")),
            "a game class neither release has, in a @Mod class's constructor, holds the mod back: " + neo);

        if (JarPortTest.failures > 0) {
            System.exit(1);
        }
        System.out.println("jar-port: classes a mod needs on every start checked");
    }

    static List<String> ported(GameDiff.Snap a, GameDiff.Snap b, Path map, String loader, Map<String, byte[]> files, Map<String, String> newIntermediary) throws Exception {
        JarPort port = new JarPort(a, b, map);
        port.loader = loader;
        if (!newIntermediary.isEmpty()) {
            // The jar as the port is given it, read in the old release's names already: what intermediary names are left
            // are of classes the old release lacks.
            port.loaderNames.in = new ModNames(new Exposure.Intermediary(Map.of(), Map.of()));
            port.loaderNames.out = new ModNames.Back(newIntermediary, Map.of(), Map.of(), b);
        }
        List<Map<String, Object>> runs = new ArrayList<>();
        port.port(JarPortTest.jar(files), "m.jar", runs);
        List<String> unlinked = new ArrayList<>();
        for (Map<String, Object> run : runs) {
            Json.array(run.get("unlinked")).forEach(u -> unlinked.add(String.valueOf(u)));
        }
        return unlinked;
    }

    /**
     * A class with a start method that reads type.EVENT: first thing, or after a branch and inside a try block (guarded),
     * then calls helper.init() if given.
     */
    static byte[] entry(String name, String method, boolean guarded, boolean helperFirst, String type, String helper) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        constructor(w, null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, method, "()V", null, null);
        m.visitCode();
        if (helperFirst && helper != null) {
            m.visitMethodInsn(Opcodes.INVOKESTATIC, helper, "init", "()V", false);
        }
        Label end = new Label();
        if (guarded) {
            Label start = new Label(), stop = new Label(), handler = new Label();
            m.visitTryCatchBlock(start, stop, handler, "java/lang/Throwable");
            m.visitLabel(start);
            m.visitFieldInsn(Opcodes.GETSTATIC, type, "EVENT", "Ljava/lang/Object;");
            m.visitInsn(Opcodes.POP);
            m.visitLabel(stop);
            m.visitJumpInsn(Opcodes.GOTO, end);
            m.visitLabel(handler);
            m.visitInsn(Opcodes.POP);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "getBoolean", "()Z", false);
            m.visitJumpInsn(Opcodes.IFEQ, end);
        }
        m.visitFieldInsn(Opcodes.GETSTATIC, type, "EVENT", "Ljava/lang/Object;");
        m.visitInsn(Opcodes.POP);
        m.visitLabel(end);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** A class with a static method that calls type.run() first thing. */
    static byte[] user(String name, String method, String type) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, method, "()V", null, null);
        m.visitCode();
        m.visitMethodInsn(Opcodes.INVOKESTATIC, type, "run", "()V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** A class carrying an annotation, whose constructor calls type.run() first thing. */
    static byte[] annotated(String name, String annotation, String type) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        AnnotationVisitor av = w.visitAnnotation(annotation, true);
        av.visit("value", "q");
        av.visitEnd();
        constructor(w, type);
        w.visitEnd();
        return w.toByteArray();
    }

    static void constructor(ClassWriter w, String calls) {
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        if (calls != null) {
            m.visitMethodInsn(Opcodes.INVOKESTATIC, calls, "run", "()V", false);
        }
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }
}

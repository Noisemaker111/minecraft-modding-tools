import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * A mod's references into the libraries a release ships, linked as the JVM will on the new release. 1.20.5's
 * DataFixerUpper dropped DataResult.PartialResult while the release still listed DataFixerUpper; Resourceful Config named
 * it, was counted as linking and crashed on both loaders. Here l9.Result$Partial is gone from a library both releases list
 * (com/l9/dfu): the mod that names it must not link, and the break is labelled the library's. A library the new release
 * no longer lists at all (com/l9/old) may still be there at run time, brought by the loader, so its classes still link.
 */
final class JarPortLibraryTest {

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args.length > 0 ? args[0] : ".runs/library-test"));
        GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
        for (GameDiff.Snap s : List.of(a, b)) {
            library(s, "l9/Result", "com/l9/dfu", "get()Ljava/lang/Object;");
        }
        library(a, "l9/Result$Partial", "com/l9/dfu", "message()Ljava/lang/String;");
        library(a, "l9/old/Walker", "com/l9/old", "walk()V");
        Path map = scratch.resolve("route-library.json");
        Files.writeString(map, "{}");
        JarPort port = new JarPort(a, b, map);

        List<Map<String, Object>> runs = new ArrayList<>();
        port.port(JarPortTest.jar(Map.of("n13/Mod.class", mod())), "library.jar", runs);
        List<String> unlinked = Json.array(runs.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(unlinked.stream().anyMatch(u -> u.contains("l9.Result$Partial")),
            "a class gone from a library the new release still lists does not link: " + unlinked);
        JarPortTest.expect(unlinked.stream().noneMatch(u -> u.contains("l9.Result.get") || u.contains("l9.old.Walker")),
            "a library member both releases have, and a library the new release no longer lists, still link: " + unlinked);
        JarPortTest.expect(Exposure.part(a, "l9.Result$Partial.message()Ljava/lang/String;").equals("library"),
            "the scoreboard labels a library member as the library's, not the game's or the loader's");
        if (JarPortTest.failures > 0) {
            System.exit(1);
        }
        System.out.println("jar-port: library references checked");
    }

    /** A class of one release's library jar, as GameDiff.addLibraries reads it. */
    static void library(GameDiff.Snap s, String name, String artifact, String... methods) {
        GameDiff.Cls c = JarPortTest.snap(s, name, 0, null, methods);
        c.library = true;
        c.artifact = artifact;
    }

    /** A mod class that calls into all three: the gone class, the kept one and the unlisted library's. */
    static byte[] mod() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "n13/Mod", null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "use", "(Ll9/Result;Ll9/Result$Partial;Ll9/old/Walker;)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "l9/Result", "get", "()Ljava/lang/Object;", false);
        m.visitInsn(Opcodes.POP);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "l9/Result$Partial", "message", "()Ljava/lang/String;", false);
        m.visitInsn(Opcodes.POP);
        m.visitVarInsn(Opcodes.ALOAD, 2);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "l9/old/Walker", "walk", "()V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }
}

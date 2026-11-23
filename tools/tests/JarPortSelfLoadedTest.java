import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;

/**
 * Jars inside a mod that no loader metadata lists, and that the mod's own code puts on the class path at start. Essential
 * and CustomSkinLoader keep their game code so, and the scoreboard counted them as unreadable on every release. Here:
 * payload.jar's code uses a game class the new release dropped, so the mod must not link, and the digest a properties file
 * records of payload.jar must be that of the ported payload (a loader of its own checks it before it unpacks it).
 * app.jar only names the game in a string (a crash-log reader's pattern): no game code, so it is no part of the port.
 * own.jar names a game class the old release lacks, only in a method's parameters: it is written in other names, which its own code renames at start,
 * so it is left as it came.
 */
final class JarPortSelfLoadedTest {

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args.length > 0 ? args[0] : ".runs/self-loaded-test"));
        GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
        JarPortTest.snap(a, "net/minecraft/Gone", 0, null, "run()V");
        JarPortTest.snap(a, "net/minecraft/Kept", 0, null, "run()V");
        JarPortTest.snap(b, "net/minecraft/Kept", 0, null, "run()V");
        Path map = scratch.resolve("route-self-loaded.json");
        Files.writeString(map, "{}");

        byte[] payload = JarPortTest.jar(Map.of("p/Payload.class", user("p/Payload", "net/minecraft/Gone")));
        byte[] app = JarPortTest.jar(Map.of("p/App.class", mentions("p/App", "Error loading class: net/minecraft/Gone")));
        byte[] own = JarPortTest.jar(Map.of("p/Own.class", takes("p/Own", "net/minecraft/Future"), "p/Old.class", user("p/Old", "net/minecraft/Gone")));
        String md5 = hex("MD5", payload);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"m\"}".getBytes(StandardCharsets.UTF_8));
        files.put("loader.properties", ("pinnedFile=/payload.jar\npinnedFileMd5=" + md5 + "\n").getBytes(StandardCharsets.UTF_8));
        files.put("payload.jar", payload);
        files.put("META-INF/lib/app.jar", app);
        files.put("own.jar", own);

        JarPort port = new JarPort(a, b, map);
        port.loader = "neoforge";
        List<Map<String, Object>> runs = new ArrayList<>();
        byte[] out = port.port(JarPortTest.jar(files), "self.jar", runs);
        List<String> unlinked = new ArrayList<>();
        for (Map<String, Object> run : runs) {
            Json.array(run.get("unlinked")).forEach(u -> unlinked.add(run.get("jar") + ": " + u));
        }
        JarPortTest.expect(unlinked.stream().anyMatch(u -> u.startsWith("self.jar!payload.jar") && u.contains("net.minecraft.Gone")),
            "game code in a jar the mod loads itself is read, and a class the new release dropped holds it back: " + unlinked);
        JarPortTest.expect(unlinked.stream().noneMatch(u -> u.contains("app.jar") || u.contains("own.jar")),
            "a jar naming the game only in text, and one written in other names, are no part of the port: " + unlinked);

        Map<String, byte[]> written = JarEntries.entries(out, "self.jar", new HashMap<>());
        String recorded = new String(written.get("loader.properties"), StandardCharsets.UTF_8);
        JarPortTest.expect(recorded.contains("pinnedFileMd5=" + hex("MD5", written.get("payload.jar"))) && !recorded.contains(md5),
            "the digest the mod records of a jar it carries is the ported jar's: " + recorded);
        JarPortTest.expect(Arrays.equals(written.get("own.jar"), own) && Arrays.equals(written.get("META-INF/lib/app.jar"), app),
            "jars the port does not read are written as they came");
        if (JarPortTest.failures > 0) {
            System.exit(1);
        }
        System.out.println("jar-port: jars a mod loads itself checked");
    }

    static String hex(String algorithm, byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
    }

    /** A class whose method calls game.run(). */
    static byte[] user(String name, String game) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "use", "(L" + game + ";)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, game, "run", "()V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** A class with a method that takes a game type and only passes it on: the type is in its descriptor alone. */
    static byte[] takes(String name, String game) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "keep", "(L" + game + ";)Ljava/lang/Object;", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** A class that only holds text naming the game. */
    static byte[] mentions(String name, String text) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pattern", "()Ljava/lang/String;", null, null);
        m.visitCode();
        m.visitLdcInsn(text);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }
}

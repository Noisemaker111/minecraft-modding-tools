import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * The port's link check includes the JVM's verifier. The update removed v9/Old, a v9/Base, and the route map moves it to
 * v9/New, which is no Base (as 1.19 moved TranslatableComponent, a component, to TranslatableContents, which is none). A
 * mod that only calls Old's methods keeps the route and links. A mod that passes a new Old where a Base is wanted links
 * by every reference but throws VerifyError in game: the port leaves the route out for that mod and reports the class
 * gone, and where the update has a stand-in for the class, uses the stand-in, and the mod links. A mod method renamed
 * onto one the mod already declares (GeckoLib on 1.19) is a class the JVM refuses too.
 */
final class JarPortVerifyTest {

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
        for (GameDiff.Snap s : List.of(a, b)) {
            JarPortTest.snap(s, "v9/Base", 0, null, "<init>()V");
            JarPortTest.snap(s, "v9/Sink", 0, null, "take(Lv9/Base;)V").methods.values().forEach(m -> m.flags |= Opcodes.ACC_STATIC);
        }
        JarPortTest.snap(a, "v9/Old", 0, "v9/Base", "<init>()V", "key()Ljava/lang/String;");
        JarPortTest.snap(b, "v9/New", 0, null, "<init>()V", "key()Ljava/lang/String;");
        JarPortTest.snap(a, "v9/Hook", 0, null, "<init>()V", "foo()V");
        JarPortTest.snap(b, "v9/Hook", 0, null, "<init>()V", "bar()V");
        Path map = scratch.resolve("route-verify.json");
        Files.writeString(map, """
            {"classes": [{"from": "v9.Old", "to": "v9.New"}],
             "methods": [{"owner": "v9.Hook", "from": "foo", "desc": "()V", "to": "bar", "newDesc": "()V"}]}
            """);

        JarPort port = new JarPort(a, b, map);
        List<String> reader = unlinked(port, "n9/Reader", false);
        JarPortTest.expect(reader.isEmpty(), "a mod that only calls the old class's methods keeps the route and links: " + reader);
        List<String> passer = unlinked(port, "n9/Passer", true);
        JarPortTest.expect(passer.contains("class gone: v9.Old (in n9.Passer.run)") && passer.stream().noneMatch(u -> u.startsWith("does not verify")),
            "a mod passing the routed class where its old supertype is wanted does not link: the route is left out and the class reported gone: " + passer);

        Path book = Files.createDirectories(scratch.resolve("verify-kb")).resolve("fixes.json");
        Files.writeString(book, "{\"rules\": []}");
        Path standin = scratch.resolve("verify-kb/standins/1.0-2.0/v9/Old.java");
        Files.createDirectories(standin.getParent());
        Files.writeString(standin, """
            package mmt.standin.v9;
            public class Old extends v9.Base {
                public Old() {
                }
                public String key() {
                    return "old";
                }
            }
            """, StandardCharsets.UTF_8);
        JarPort withStandin = new JarPort(a, b, map);
        withStandin.fixes(book);
        List<Map<String, Object>> runs = new ArrayList<>();
        byte[] ported = withStandin.port(JarPortTest.jar(Map.of("n9/Passer.class", user("n9/Passer", true))), "passer.jar", runs);
        List<String> carried = Json.array(runs.get(runs.size() - 1).get("unlinked")).stream().map(String::valueOf).toList();
        String passerNow = new String(JarPortTest.entries(ported).get("n9/Passer.class"), StandardCharsets.ISO_8859_1);
        JarPortTest.expect(carried.isEmpty() && passerNow.contains("mmt/standin/") && !passerNow.contains("v9/New"),
            "with a stand-in for the old class, the mod the route fails uses the stand-in and links: " + carried);
        List<String> readerWith = unlinked(withStandin, "n9/Reader", false);
        JarPortTest.expect(readerWith.isEmpty(), "a mod the route suits keeps it beside a stand-in: " + readerWith);

        List<Map<String, Object>> dupRuns = new ArrayList<>();
        port.port(JarPortTest.jar(Map.of("n9/Dup.class", duplicate())), "dup.jar", dupRuns);
        List<String> dup = Json.array(dupRuns.get(dupRuns.size() - 1).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(dup.stream().anyMatch(u -> u.startsWith("does not verify: Duplicate method name bar")),
            "an override renamed onto a method the mod already declares is a class the JVM refuses: " + dup);
        JarPortTest.finish("JarPortVerifyTest");
    }

    static List<String> unlinked(JarPort port, String name, boolean pass) throws Exception {
        List<Map<String, Object>> runs = new ArrayList<>();
        port.port(JarPortTest.jar(Map.of(name + ".class", user(name, pass))), name.replace('/', '-') + ".jar", runs);
        return Json.array(runs.get(runs.size() - 1).get("unlinked")).stream().map(String::valueOf).toList();
    }

    /** run(): new v9.Old(), then passed to Sink.take(Base) (pass) or asked its key() (not pass). */
    static byte[] user(String name, boolean pass) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        m.visitCode();
        m.visitTypeInsn(Opcodes.NEW, "v9/Old");
        m.visitInsn(Opcodes.DUP);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "v9/Old", "<init>", "()V", false);
        if (pass) {
            m.visitMethodInsn(Opcodes.INVOKESTATIC, "v9/Sink", "take", "(Lv9/Base;)V", false);
        } else {
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "v9/Old", "key", "()Ljava/lang/String;", false);
            m.visitInsn(Opcodes.POP);
        }
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** A Hook subclass overriding foo() (renamed bar() by the update) beside a bar() of its own. */
    static byte[] duplicate() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "n9/Dup", null, "v9/Hook", null);
        for (String name : List.of("foo", "bar")) {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, name, "()V", null, null);
            m.visitCode();
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
        w.visitEnd();
        return w.toByteArray();
    }
}

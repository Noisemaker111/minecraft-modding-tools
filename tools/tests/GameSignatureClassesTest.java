import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * A class the update split into an interface and a base class follows the interface, on a two-release game built here,
 * as 26.3 split GpuTexture into renderpearl.api.textures.GpuTexture and backend.common.BaseGpuTexture. 1.0: Tex is an
 * abstract class (width(), area(), close()); Device.make() and latest() give a Tex, use() takes one and Device.last holds
 * one. 2.0: api/Tex is an interface, BaseTex the abstract class with Tex's bodies, and every Device signature names
 * api/Tex. By body likeness Tex is BaseTex; by what the game's own signatures name in its place it is api/Tex, and a mod
 * calling d.make().area() runs on the new game only that way.
 *
 * Slime and Magma went in 2.0, and Arena's methods that took either take their shared parent Cube: a type two old classes
 * now go through is not where either went, so neither is linked to it.
 *
 *   java -cp asm.jar:CLASSES GameSignatureClassesTest SCRATCH   (in the jar-port check of bench/run_tests.py)
 */
final class GameSignatureClassesTest {

    static final String U = GameTypesTest.U; // cls() names its classes there

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of(args[0]));
        Map<String, byte[]> oldGame = game(false), newGame = game(true);
        GameDiff.Snap a = GameDiff.read("1.0", List.copyOf(oldGame.values()), null, List.of());
        GameDiff.Snap b = GameDiff.read("2.0", List.copyOf(newGame.values()), null, List.of());
        GameDiff.Hop hop = new GameDiff.Hop(a, b);
        hop.run();
        Map<String, String> links = new TreeMap<>();
        hop.cAB.forEach((x, y) -> links.put(x.name, y.name));
        JarPortTest.expect((U + "api/Tex").equals(links.get(U + "Tex")),
            "a class the game's signatures now name as an interface goes to that interface, not the base class holding its bodies: " + links);
        JarPortTest.expect(!links.containsKey(U + "Slime") && !links.containsKey(U + "Magma"),
            "two old classes whose uses now take one shared parent are not linked to it: " + links);

        StringBuilder classes = new StringBuilder();
        links.forEach((x, y) -> {
            if (!x.equals(y)) {
                classes.append(classes.isEmpty() ? "" : ", ").append("{\"from\": \"").append(x.replace('/', '.')).append("\", \"to\": \"").append(y.replace('/', '.')).append("\"}");
            }
        });
        Path map = Path.of(args[0]).resolve("route-signature-classes.json");
        Files.writeString(map, "{\"classes\": [" + classes + "]}");
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m9/Run", null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "go", "(L" + U + "Device;)I", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Device", "make", "()L" + U + "Tex;", false);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Tex", "area", "()I", false);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Device", "make", "()L" + U + "Tex;", false);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Tex", "width", "()I", false);
        m.visitInsn(Opcodes.IADD);
        m.visitInsn(Opcodes.IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(new JarPort(a, b, map).port(JarPortTest.jar(Map.of("m9/Run.class", w.toByteArray())), "signatures.jar", results));
        Map<String, byte[]> loaded = new HashMap<>(newGame);
        loaded.put("m9/Run", ported.get("m9/Run.class"));
        ClassLoader loader = new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = loaded.get(name.replace('.', '/'));
                if (bytes == null) {
                    return Class.forName(name, false, ClassLoader.getPlatformClassLoader());
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        String got;
        try {
            Class<?> device = loader.loadClass((U + "Device").replace('/', '.'));
            got = String.valueOf(loader.loadClass("m9.Run").getMethod("go", device).invoke(null, device.getConstructor().newInstance()));
        } catch (ReflectiveOperationException | LinkageError failed) {
            got = "failed: " + (failed.getCause() == null ? failed : failed.getCause()) + " " + results;
        }
        JarPortTest.expect(got.equals("56"), "the mod's calls on the texture run on the new game through the interface: " + got);
        if (JarPortTest.failures > 0) {
            System.exit(1);
        }
        System.out.println("game-signature-classes: 3 checks passed");
    }

    static Map<String, byte[]> game(boolean two) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        String tex = two ? U + "api/Tex" : U + "Tex";
        String base = two ? U + "BaseTex" : U + "Tex";
        if (two) {
            out.put(tex, GameTypesTest.cls("api/Tex", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null, null, w -> {
                for (String name : new String[] { "width", "area" }) {
                    w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, "()I", null, null).visitEnd();
                }
                w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "close", "()V", null, null).visitEnd();
            }));
        }
        out.put(base, GameTypesTest.cls(two ? "BaseTex" : "Tex", Opcodes.ACC_ABSTRACT, null, two ? tex : null, w -> {
            w.visitField(Opcodes.ACC_PROTECTED, "w", "I", null, null).visitEnd();
            MethodVisitor c = w.visitMethod(Opcodes.ACC_PROTECTED, "<init>", "(I)V", null, null);
            c.visitCode();
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitVarInsn(Opcodes.ILOAD, 1);
            c.visitFieldInsn(Opcodes.PUTFIELD, base, "w", "I");
            c.visitInsn(Opcodes.RETURN);
            c.visitMaxs(0, 0);
            c.visitEnd();
            MethodVisitor width = w.visitMethod(Opcodes.ACC_PUBLIC, "width", "()I", null, null);
            width.visitCode();
            width.visitVarInsn(Opcodes.ALOAD, 0);
            width.visitFieldInsn(Opcodes.GETFIELD, base, "w", "I");
            width.visitInsn(Opcodes.IRETURN);
            width.visitMaxs(0, 0);
            width.visitEnd();
            MethodVisitor area = w.visitMethod(Opcodes.ACC_PUBLIC, "area", "()I", null, null);
            area.visitCode();
            area.visitVarInsn(Opcodes.ALOAD, 0);
            area.visitMethodInsn(Opcodes.INVOKEVIRTUAL, base, "width", "()I", false);
            area.visitVarInsn(Opcodes.ALOAD, 0);
            area.visitFieldInsn(Opcodes.GETFIELD, base, "w", "I");
            area.visitInsn(Opcodes.IMUL);
            area.visitInsn(Opcodes.IRETURN);
            area.visitMaxs(0, 0);
            area.visitEnd();
            w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "close", "()V", null, null).visitEnd();
        }));
        out.put(U + "GlTex", GameTypesTest.cls("GlTex", 0, base, null, w -> {
            MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            c.visitCode();
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitIntInsn(Opcodes.BIPUSH, 7);
            c.visitMethodInsn(Opcodes.INVOKESPECIAL, base, "<init>", "(I)V", false);
            c.visitInsn(Opcodes.RETURN);
            c.visitMaxs(0, 0);
            c.visitEnd();
            MethodVisitor close = w.visitMethod(Opcodes.ACC_PUBLIC, "close", "()V", null, null);
            close.visitCode();
            close.visitInsn(Opcodes.RETURN);
            close.visitMaxs(0, 0);
            close.visitEnd();
        }));
        String t = "L" + tex + ";";
        boolean itf = two;
        out.put(U + "Device", GameTypesTest.cls("Device", 0, null, null, w -> {
            GameTypesTest.constructor(w, "java/lang/Object");
            w.visitField(Opcodes.ACC_PUBLIC, "last", t, null, null).visitEnd();
            MethodVisitor make = w.visitMethod(Opcodes.ACC_PUBLIC, "make", "()" + t, null, null);
            make.visitCode();
            make.visitTypeInsn(Opcodes.NEW, U + "GlTex");
            make.visitInsn(Opcodes.DUP);
            make.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "GlTex", "<init>", "()V", false);
            make.visitInsn(Opcodes.ARETURN);
            make.visitMaxs(0, 0);
            make.visitEnd();
            MethodVisitor latest = w.visitMethod(Opcodes.ACC_PUBLIC, "latest", "()" + t, null, null);
            latest.visitCode();
            latest.visitVarInsn(Opcodes.ALOAD, 0);
            latest.visitFieldInsn(Opcodes.GETFIELD, U + "Device", "last", t);
            latest.visitInsn(Opcodes.ARETURN);
            latest.visitMaxs(0, 0);
            latest.visitEnd();
            MethodVisitor use = w.visitMethod(Opcodes.ACC_PUBLIC, "use", "(" + t + ")I", null, null);
            use.visitCode();
            use.visitVarInsn(Opcodes.ALOAD, 1);
            use.visitMethodInsn(itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, tex, "width", "()I", itf);
            use.visitInsn(Opcodes.IRETURN);
            use.visitMaxs(0, 0);
            use.visitEnd();
        }));
        String[] mobs = two ? new String[] { "Cube" } : new String[] { "Slime", "Magma" };
        for (String mob : mobs) {
            out.put(U + mob, GameTypesTest.cls(mob, 0, null, null, w -> {
                GameTypesTest.constructor(w, "java/lang/Object");
                MethodVisitor jump = w.visitMethod(Opcodes.ACC_PUBLIC, "jump", "()I", null, null);
                jump.visitCode();
                jump.visitInsn(Opcodes.ICONST_2);
                jump.visitInsn(Opcodes.IRETURN);
                jump.visitMaxs(0, 0);
                jump.visitEnd();
            }));
        }
        out.put(U + "Arena", GameTypesTest.cls("Arena", 0, null, null, w -> {
            GameTypesTest.constructor(w, "java/lang/Object");
            for (String mob : new String[] { "Slime", "Magma" }) {
                for (int i = 1; i <= 3; i++) {
                    String type = "L" + U + (two ? "Cube" : mob) + ";";
                    MethodVisitor hit = w.visitMethod(Opcodes.ACC_PUBLIC, "hit" + mob + i, "(" + type + ")I", null, null);
                    hit.visitCode();
                    hit.visitVarInsn(Opcodes.ALOAD, 1);
                    hit.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + (two ? "Cube" : mob), "jump", "()I", false);
                    hit.visitIntInsn(Opcodes.BIPUSH, i);
                    hit.visitInsn(Opcodes.IADD);
                    hit.visitInsn(Opcodes.IRETURN);
                    hit.visitMaxs(0, 0);
                    hit.visitEnd();
                }
            }
        }));
        return out;
    }
}

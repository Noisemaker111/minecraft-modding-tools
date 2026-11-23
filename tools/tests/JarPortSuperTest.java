import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * Mod constructors whose super(...) goes to a game constructor the update reshaped, run on this JVM against real classes
 * of the new release. The game's Rend(Disp) became Rend(Ctx), whose body stores ctx.getDisp() where the old one stored
 * the dispatcher, and its own ArrowR and BoxR now take a Ctx too; Factory.make(Disp) became make(Ctx). A mod renderer
 * MyR(Disp) { super(d); label = d.name(); } moves to MyR(Ctx), the dispatcher read as ctx.getDisp(), and the mod's
 * factory lambda d -> new MyR(d) moves with Factory.make and hands it the context. A call with no context at hand keeps
 * the old constructor and stays reported, and so does a class whose game subclasses show no such move (Lone). A mixin's
 * super(...), which Mixin never runs, goes to the new constructor with zeros, its field set after it kept; a mixin of a
 * class that became an interface (Tile) extends Object, implements it, and calls Object's constructor.
 */
final class JarPortSuperTest {

    static final String DISP = "Ls7/Disp;", CTX = "Ls7/Ctx;", REND = "Ls7/Rend;";

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        check(scratch);
        JarPortTest.finish("JarPortSuperTest");
    }

    static void check(Path scratch) throws Exception {
        GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
        for (GameDiff.Snap s : List.of(a, b)) {
            boolean old = s == a;
            String took = old ? DISP : CTX;
            JarPortTest.snap(s, "s7/Disp", 0, null, "<init>(Ljava/lang/String;)V", "name()Ljava/lang/String;");
            GameDiff.Cls rend = JarPortTest.snap(s, "s7/Rend", 0, null, "<init>(" + took + ")V", "getDisp()" + DISP);
            rend.fields.put("disp", new GameDiff.Fld(rend, "disp", DISP, Opcodes.ACC_PROTECTED));
            JarPortTest.snap(s, "s7/ArrowR", 0, "s7/Rend", "<init>(" + took + ")V");
            JarPortTest.snap(s, "s7/BoxR", 0, "s7/Rend", "<init>(" + took + ")V");
            JarPortTest.snap(s, "s7/Lone", 0, null, "<init>(" + took + ")V");
            JarPortTest.snap(s, "s7/Factory", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null, "make(" + took + ")" + REND);
            JarPortTest.snap(s, "s7/Reg", 0, null, "<init>()V", "make(" + took + ")" + REND).ifaces.add("s7/Factory");
            if (old) {
                JarPortTest.snap(s, "s7/Tile", 0, null, "<init>(" + DISP + ")V");
                JarPortTest.snap(s, "s7/BeaconR", 0, "s7/Tile", "<init>(" + DISP + ")V");
                JarPortTest.snap(s, "s7/BellR", 0, "s7/Tile", "<init>(" + DISP + ")V");
            } else {
                JarPortTest.snap(s, "s7/Ctx", 0, null, "<init>(" + DISP + ")V", "getDisp()" + DISP);
                JarPortTest.snap(s, "s7/Tile", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null);
                JarPortTest.snap(s, "s7/BeaconR", 0, null, "<init>(" + CTX + ")V").ifaces.add("s7/Tile");
                JarPortTest.snap(s, "s7/BellR", 0, null, "<init>(" + CTX + ")V").ifaces.add("s7/Tile");
            }
        }
        Path map = scratch.resolve("route-super.json");
        Files.writeString(map, """
            {"signatures": [{"owner": "s7.Factory", "from": "make", "desc": "(Ls7/Disp;)Ls7/Rend;", "to": "make", "newDesc": "(Ls7/Ctx;)Ls7/Rend;"},
                            {"owner": "s7.Reg", "from": "make", "desc": "(Ls7/Disp;)Ls7/Rend;", "to": "make", "newDesc": "(Ls7/Ctx;)Ls7/Rend;"}]}
            """);
        JarPort port = new JarPort(a, b, map);
        port.oldCode = new JarPort.GameCode(Map.of("s7/Rend", rend(true), "s7/Reg", reg(true), "s7/Lone", lone(true))::get, null);
        port.newCode = new JarPort.GameCode(Map.of("s7/Rend", rend(false), "s7/Reg", reg(false), "s7/Lone", lone(false))::get, null);

        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("m7/MyR.class", myR(), "m7/Init.class", init())), "super.jar", results));
        List<String> unlinked = unlinked(results);
        Map<String, String> members = JarPortTest.members(ported.get("m7/MyR.class"));
        JarPortTest.expect(unlinked.isEmpty() && members.containsKey("method <init>(" + CTX + ")V") && !members.containsKey("method <init>(" + DISP + ")V"),
            "a renderer whose superclass's constructor takes a context now moves to take it, and its factory lambda with it: " + unlinked + " " + members.keySet());
        String ran = run(ported);
        JarPortTest.expect(ran.equals("x true"), "the moved constructor stores the context's dispatcher and reads its name from it: " + ran);

        List<Map<String, Object>> keptRuns = new ArrayList<>();
        Map<String, byte[]> keptJar = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("m7/MyR.class", myR(), "m7/Init.class", init(), "m7/Direct.class", direct())),
            "kept.jar", keptRuns));
        Map<String, String> kept = JarPortTest.members(keptJar.get("m7/MyR.class"));
        JarPortTest.expect(unlinked(keptRuns).contains("call: s7.Rend.<init>(Ls7/Disp;)V") && kept.containsKey("method <init>(" + DISP + ")V") && kept.containsKey("method <init>(" + CTX + ")V"),
            "a call with no context at hand keeps the old constructor, whose super call stays reported: " + unlinked(keptRuns) + " " + kept.keySet());

        List<Map<String, Object>> loneRuns = new ArrayList<>();
        port.port(JarPortTest.jar(Map.of("m7/MyLone.class", myLone())), "lone.jar", loneRuns);
        JarPortTest.expect(unlinked(loneRuns).contains("call: s7.Lone.<init>(Ls7/Disp;)V"),
            "a constructor no game subclass shows moving is not moved: " + unlinked(loneRuns));

        List<Map<String, Object>> mixinRuns = new ArrayList<>();
        Map<String, byte[]> mixins = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("m7/RendMixin.class", mixin("m7/RendMixin", "s7/Rend", "s7/ArrowR"),
            "m7/TileMixin.class", mixin("m7/TileMixin", "s7/Tile", "s7/BeaconR"),
            "m7.mixins.json", "{\"package\": \"m7\", \"mixins\": [\"RendMixin\", \"TileMixin\"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8))), "mixin.jar", mixinRuns));
        String rendInit = JarPortTest.members(mixins.get("m7/RendMixin.class")).get("method <init>(" + DISP + ")V");
        JarPortTest.expect(unlinked(mixinRuns).isEmpty() && rendInit != null && rendInit.contains("s7/Rend.<init>(" + CTX + ")V") && rendInit.contains("m7/RendMixin.count"),
            "a mixin's super call goes to the new constructor and what follows it stays: " + unlinked(mixinRuns) + " " + rendInit);
        ClassReader tile = new ClassReader(mixins.get("m7/TileMixin.class"));
        String tileInit = JarPortTest.members(mixins.get("m7/TileMixin.class")).get("method <init>(" + DISP + ")V");
        JarPortTest.expect(tile.getSuperName().equals("java/lang/Object") && Arrays.asList(tile.getInterfaces()).contains("s7/Tile") && tileInit != null
            && tileInit.contains("java/lang/Object.<init>()V"),
            "a mixin of a class that became an interface extends what the target does now and implements it: " + tile.getSuperName() + " "
                + Arrays.toString(tile.getInterfaces()) + " " + tileInit);
    }

    static List<String> unlinked(List<Map<String, Object>> results) {
        return Json.array(results.get(0).get("unlinked")).stream().map(String::valueOf).toList();
    }

    /** Runs the ported mod on the new release: Init.factory().make(new Ctx(new Disp("x"))), its label and whether it holds the dispatcher. */
    static String run(Map<String, byte[]> ported) throws Exception {
        Map<String, byte[]> classes = new HashMap<>(newGame());
        classes.put("m7/MyR", ported.get("m7/MyR.class"));
        classes.put("m7/Init", ported.get("m7/Init.class"));
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
        try {
            Class<?> disp = loader.loadClass("s7.Disp"), ctx = loader.loadClass("s7.Ctx");
            Object d = disp.getConstructor(String.class).newInstance("x");
            Object c = ctx.getConstructor(disp).newInstance(d);
            Object factory = loader.loadClass("m7.Init").getMethod("factory").invoke(null);
            Object r = loader.loadClass("s7.Factory").getMethod("make", ctx).invoke(factory, c);
            Object label = r.getClass().getField("label").get(r);
            return label + " " + (loader.loadClass("s7.Rend").getMethod("getDisp").invoke(r) == d);
        } catch (ReflectiveOperationException | LinkageError failed) {
            return "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
    }

    /** The new release's s7 classes as real bytecode. */
    static Map<String, byte[]> newGame() {
        Map<String, byte[]> out = new HashMap<>();
        ClassWriter disp = JarPortRetypeTest.writer("s7/Disp", "java/lang/Object");
        disp.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "name", "Ljava/lang/String;", null, null).visitEnd();
        MethodVisitor m = disp.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitFieldInsn(Opcodes.PUTFIELD, "s7/Disp", "name", "Ljava/lang/String;");
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        getter(disp, "s7/Disp", "name", "name", "Ljava/lang/String;");
        disp.visitEnd();
        out.put("s7/Disp", disp.toByteArray());
        ClassWriter ctx = JarPortRetypeTest.writer("s7/Ctx", "java/lang/Object");
        ctx.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "disp", DISP, null, null).visitEnd();
        m = ctx.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + DISP + ")V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitFieldInsn(Opcodes.PUTFIELD, "s7/Ctx", "disp", DISP);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        getter(ctx, "s7/Ctx", "getDisp", "disp", DISP);
        ctx.visitEnd();
        out.put("s7/Ctx", ctx.toByteArray());
        out.put("s7/Rend", rend(false));
        ClassWriter factory = new ClassWriter(0);
        factory.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, "s7/Factory", null, "java/lang/Object", null);
        factory.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "make", "(" + CTX + ")" + REND, null, null).visitEnd();
        factory.visitEnd();
        out.put("s7/Factory", factory.toByteArray());
        return out;
    }

    static void getter(ClassWriter w, String owner, String name, String field, String desc) {
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, name, "()" + desc, null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitFieldInsn(Opcodes.GETFIELD, owner, field, desc);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** s7.Rend: stores the dispatcher it is given (1.0), or its context's (2.0). */
    static byte[] rend(boolean old) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "s7/Rend", null, "java/lang/Object", null);
        w.visitField(Opcodes.ACC_PROTECTED, "disp", DISP, null, null).visitEnd();
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + (old ? DISP : CTX) + ")V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        if (!old) {
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "s7/Ctx", "getDisp", "()" + DISP, false);
        }
        m.visitFieldInsn(Opcodes.PUTFIELD, "s7/Rend", "disp", DISP);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        getter(w, "s7/Rend", "getDisp", "disp", DISP);
        w.visitEnd();
        return w.toByteArray();
    }

    /** s7.Lone: like Rend, with no game subclass. */
    static byte[] lone(boolean old) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "s7/Lone", null, "java/lang/Object", null);
        w.visitField(Opcodes.ACC_PROTECTED, "disp", DISP, null, null).visitEnd();
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + (old ? DISP : CTX) + ")V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        if (!old) {
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "s7/Ctx", "getDisp", "()" + DISP, false);
        }
        m.visitFieldInsn(Opcodes.PUTFIELD, "s7/Lone", "disp", DISP);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** s7.Reg, the game's own factory: names its dispatcher, then makes an ArrowR (1.0 from the dispatcher, 2.0 the context). */
    static byte[] reg(boolean old) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "s7/Reg", null, "java/lang/Object", new String[] { "s7/Factory" });
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "make", "(" + (old ? DISP : CTX) + ")" + REND, null, null);
        m.visitCode();
        for (int i = 0; i < 2; i++) {
            m.visitVarInsn(Opcodes.ALOAD, 1);
            if (!old) {
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "s7/Ctx", "getDisp", "()" + DISP, false);
            }
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "s7/Disp", "name", "()Ljava/lang/String;", false);
            m.visitInsn(Opcodes.POP);
        }
        m.visitTypeInsn(Opcodes.NEW, "s7/ArrowR");
        m.visitInsn(Opcodes.DUP);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "s7/ArrowR", "<init>", "(" + (old ? DISP : CTX) + ")V", false);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** m7.MyR extends s7.Rend: MyR(Disp d) { super(d); this.label = d.name(); }. */
    static byte[] myR() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m7/MyR", null, "s7/Rend", null);
        w.visitField(Opcodes.ACC_PUBLIC, "label", "Ljava/lang/String;", null, null).visitEnd();
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + DISP + ")V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "s7/Rend", "<init>", "(" + DISP + ")V", false);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "s7/Disp", "name", "()Ljava/lang/String;", false);
        m.visitFieldInsn(Opcodes.PUTFIELD, "m7/MyR", "label", "Ljava/lang/String;");
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** m7.MyLone extends s7.Lone, made by a factory lambda. */
    static byte[] myLone() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m7/MyLone", null, "s7/Lone", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + DISP + ")V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "s7/Lone", "<init>", "(" + DISP + ")V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** m7.Init.factory(): d -> new MyR(d), a Factory. */
    static byte[] init() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m7/Init", null, "java/lang/Object", null);
        Handle meta = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
        MethodVisitor f = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "factory", "()Ls7/Factory;", null, null);
        f.visitCode();
        f.visitInvokeDynamicInsn("make", "()Ls7/Factory;", meta, Type.getType("(" + DISP + ")" + REND),
            new Handle(Opcodes.H_INVOKESTATIC, "m7/Init", "lambda$factory$0", "(" + DISP + ")" + REND, false), Type.getType("(" + DISP + ")" + REND));
        f.visitInsn(Opcodes.ARETURN);
        f.visitMaxs(0, 0);
        f.visitEnd();
        MethodVisitor l = w.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "lambda$factory$0", "(" + DISP + ")" + REND, null, null);
        l.visitCode();
        l.visitTypeInsn(Opcodes.NEW, "m7/MyR");
        l.visitInsn(Opcodes.DUP);
        l.visitVarInsn(Opcodes.ALOAD, 0);
        l.visitMethodInsn(Opcodes.INVOKESPECIAL, "m7/MyR", "<init>", "(" + DISP + ")V", false);
        l.visitInsn(Opcodes.ARETURN);
        l.visitMaxs(0, 0);
        l.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** m7.Direct.make(Disp d): new MyR(d), with no context at hand. */
    static byte[] direct() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m7/Direct", null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "(" + DISP + ")" + REND, null, null);
        m.visitCode();
        m.visitTypeInsn(Opcodes.NEW, "m7/MyR");
        m.visitInsn(Opcodes.DUP);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "m7/MyR", "<init>", "(" + DISP + ")V", false);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** A mixin into target extending sup: Mixin(Disp d) { super(d); this.count = 5; }. */
    static byte[] mixin(String name, String sup, String target) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, null, sup, null);
        AnnotationVisitor mixin = w.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor value = mixin.visitArray("value");
        value.visit(null, Type.getObjectType(target));
        value.visitEnd();
        mixin.visitEnd();
        w.visitField(Opcodes.ACC_PRIVATE, "count", "I", null, null).visitEnd();
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + DISP + ")V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, sup, "<init>", "(" + DISP + ")V", false);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitInsn(Opcodes.ICONST_5);
        m.visitFieldInsn(Opcodes.PUTFIELD, name, "count", "I");
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }
}

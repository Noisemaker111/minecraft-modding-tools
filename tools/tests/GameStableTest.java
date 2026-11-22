import java.util.*;
import org.objectweb.asm.*;

/**
 * GameDiff gives the same map every run, on a two-release game built here with a tie. 1.0: Boat and Cart each have
 * damage(), alike. 2.0: both extend a new Vehicle, where damage() moved up. Only one old method can link to Vehicle's,
 * and which one used to depend on the order a map of classes went round in, which an identity hash sets anew each run
 * (1.20.3's Boat and AbstractMinecart into VehicleEntity). The update is linked and learned 30 times here, each with new
 * objects, and every run must give the same links and the same learned lines.
 *
 *   java -cp asm.jar:CLASSES GameStableTest   (in the jar-port check of bench/run_tests.py)
 */
final class GameStableTest {

    static final String U = "net/minecraft/s4/";

    public static void main(String[] args) throws Exception {
        Set<String> seen = new TreeSet<>();
        for (int run = 0; run < 30; run++) {
            GameDiff.Snap a = GameDiff.read("1.0", game(false), null, List.of());
            GameDiff.Snap b = GameDiff.read("2.0", game(true), null, List.of());
            GameDiff.Hop hop = new GameDiff.Hop(a, b);
            hop.run();
            List<String> out = new ArrayList<>();
            hop.mAB.forEach((x, y) -> out.add(x.owner.name + "." + x.name + " -> " + y.owner.name + "." + y.name));
            for (GameDiff.Learner.Learned l : new GameDiff.Learner(hop).learn()) {
                out.add(l.json("1.0 -> 2.0"));
            }
            Collections.sort(out);
            seen.add(String.join("\n", out));
        }
        JarPortTest.expect(seen.size() == 1, "30 runs of one update give one map, not " + seen.size());
        JarPortTest.expect(seen.iterator().next().contains(" -> " + U + "Vehicle.damage"), "the moved method is linked from one of the two: " + seen.iterator().next());
        if (JarPortTest.failures > 0) {
            System.exit(1);
        }
        System.out.println("game-stable: 2 checks passed");
    }

    /** 1.0: Boat and Cart with damage(). 2.0: Vehicle with damage(); Boat and Cart extend it. */
    static List<byte[]> game(boolean two) {
        List<byte[]> out = new ArrayList<>();
        if (two) {
            out.add(cls("Vehicle", "java/lang/Object", true));
        }
        for (String name : new String[] { "Boat", "Cart" }) {
            out.add(cls(name, two ? U + "Vehicle" : "java/lang/Object", !two));
        }
        return out;
    }

    static byte[] cls(String simple, String sup, boolean damage) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, U + simple, null, sup, null);
        MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        c.visitCode();
        c.visitVarInsn(Opcodes.ALOAD, 0);
        c.visitMethodInsn(Opcodes.INVOKESPECIAL, sup, "<init>", "()V", false);
        c.visitInsn(Opcodes.RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();
        if (damage) {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "damage", "()F", null, null);
            m.visitCode();
            m.visitLdcInsn("damage");
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
            m.visitInsn(Opcodes.I2F);
            m.visitInsn(Opcodes.FRETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
        w.visitEnd();
        return w.toByteArray();
    }
}

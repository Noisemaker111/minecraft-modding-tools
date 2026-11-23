import java.util.*;
import org.objectweb.asm.*;

/** The steps that turn a value of an old type into the type the new release takes. */
final class Conversions {

    final JarPort port;

    Conversions(JarPort port) {
        this.port = port;
    }

    /** The instruction that turns a value of one primitive type into another, by the two descriptors. */
    static final Map<String, Integer> CONVERT = Map.ofEntries(Map.entry("IC", Opcodes.I2C), Map.entry("IB", Opcodes.I2B),
        Map.entry("IS", Opcodes.I2S), Map.entry("IJ", Opcodes.I2L), Map.entry("IF", Opcodes.I2F), Map.entry("ID", Opcodes.I2D),
        Map.entry("JI", Opcodes.L2I), Map.entry("FD", Opcodes.F2D), Map.entry("DF", Opcodes.D2F), Map.entry("FI", Opcodes.F2I),
        Map.entry("DI", Opcodes.D2I));

    /** Each parameter's local slot in an instance method. */
    static int[] slots(Type[] params) {
        return slots(params, 1);
    }

    /** Each parameter's local slot, the first at first (0 in a static method). */
    static int[] slots(Type[] params, int first) {
        int[] out = new int[params.length];
        int next = first;
        for (int i = 0; i < params.length; i++) {
            out[i] = next;
            next += params[i].getSize();
        }
        return out;
    }

    /** The steps a conversion reads (Retype.step) on the value on top of the stack, written in the old release's names. */
    void steps(MethodVisitor mv, String[] steps) {
        for (String text : steps == null ? new String[0] : steps) {
            if (text.startsWith("x:")) {
                mv.visitInsn(CONVERT.get(text.substring(2)));
                continue;
            }
            if (text.startsWith("k:")) {
                String type = text.substring(2, 3), value = text.substring(4);
                mv.visitLdcInsn(switch (type) {
                    case "J" -> (Object) Long.valueOf(value);
                    case "F" -> Float.valueOf(value);
                    case "D" -> Double.valueOf(value);
                    case "Z" -> value.equals("true") || value.equals("1") ? 1 : 0;
                    default -> Integer.valueOf(value);
                });
                continue;
            }
            if (text.startsWith("o:")) {
                // An operation with a constant: o:OPCODE:type:value (the value on the stack, then the constant).
                String[] o = text.split(":", 4);
                mv.visitLdcInsn(switch (o[2]) {
                    case "J" -> (Object) Long.valueOf(o[3]);
                    case "F" -> Float.valueOf(o[3]);
                    case "D" -> Double.valueOf(o[3]);
                    default -> Integer.valueOf(o[3]);
                });
                mv.visitInsn(Integer.parseInt(o[1]));
                continue;
            }
            String[] s = Retype.step(text);
            String owner = port.inverse.getOrDefault(s[1], s[1]);
            if (s[0].equals("s")) {
                GameDiff.Cls holder = port.b.classes.get(s[1]);
                boolean itf = holder != null && (holder.flags & Opcodes.ACC_INTERFACE) != 0;
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, s[2], port.oldDesc(s[3]), itf);
            } else if (s[0].equals("m")) {
                GameDiff.Cls holder = port.b.classes.get(s[1]);
                boolean itf = holder != null && (holder.flags & Opcodes.ACC_INTERFACE) != 0;
                mv.visitMethodInsn(itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, owner, s[2], port.oldDesc(s[3]), itf);
            } else {
                mv.visitFieldInsn(Opcodes.GETFIELD, owner, s[2], port.oldDesc(s[3]));
            }
        }
    }

    /** The value Java gives a variable of type t that nothing set: 0, false or null. */
    static void pushDefault(MethodVisitor mv, Type t) {
        mv.visitInsn(switch (t.getSort()) {
            case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> Opcodes.ICONST_0;
            case Type.LONG -> Opcodes.LCONST_0;
            case Type.FLOAT -> Opcodes.FCONST_0;
            case Type.DOUBLE -> Opcodes.DCONST_0;
            default -> Opcodes.ACONST_NULL;
        });
    }
}

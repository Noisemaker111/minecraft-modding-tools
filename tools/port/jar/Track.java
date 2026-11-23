import java.util.*;
import org.objectweb.asm.*;

/**
 * The stack as the JVM's verifier sees it (asm-commons' AnalyzerAdapter), where a moved method's old parameters keep
 * who they are: each load of one pushes a mark, so a call can tell which of its arguments is which old parameter.
 */
final class Track extends Marked {
    final Map<Integer, Object> marks = new HashMap<>(); // slot -> mark
    final Map<Object, Integer> params = new IdentityHashMap<>(); // mark -> old parameter

    Track(String owner, int access, String name, String desc, MethodVisitor mv) {
        super(owner, access, name, desc, mv);
    }

    void mark(int slot, int param, Type t) {
        Object mark = t.getSort() == Type.OBJECT ? new String(t.getInternalName()) : t.getSort() == Type.ARRAY ? new String(t.getDescriptor()) : new Object();
        marks.put(slot, mark);
        params.put(mark, param);
    }

    @Override
    boolean marked(Object value) {
        return params.containsKey(value);
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        super.visitVarInsn(opcode, var);
        Object mark = marks.get(var);
        // A long or double is two entries, its value first.
        int at = stack == null ? -1 : stack.size() - (opcode == Opcodes.LLOAD || opcode == Opcodes.DLOAD ? 2 : 1);
        if (mark != null && opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD && at >= 0) {
            stack.set(at, mark);
        }
    }

    /** For each argument of a call about to be made (on top of the stack), the old parameter it is, or null. */
    Integer[] sources(Type[] args) {
        if (stack == null) {
            return null;
        }
        Integer[] out = new Integer[args.length];
        int at = stack.size();
        for (int k = args.length - 1; k >= 0; k--) {
            at -= args[k].getSize();
            if (at < 0) {
                return null;
            }
            out[k] = params.get(stack.get(at));
        }
        return out;
    }
}

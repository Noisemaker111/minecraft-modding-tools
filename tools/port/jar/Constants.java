import java.util.*;
import org.objectweb.asm.*;

/**
 * Which of a type's own constants each call of a method passes: a static field of its class's type read (an enum's
 * constant, or HoverEvent.Action.SHOW_TEXT) and passed on, as the verifier tracks the stack, across jumps where every
 * way in passes the same one.
 */
final class Constants extends Marked {
    final Map<Integer, Map<Integer, String>> at = new HashMap<>();
    final Map<Object, String> marks = new IdentityHashMap<>();
    int calls = -1;

    Constants(String owner, int access, String name, String desc) {
        super(owner, access, name, desc, null);
    }

    @Override
    boolean marked(Object value) {
        return marks.containsKey(value);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
        super.visitFieldInsn(opcode, owner, name, desc);
        if (opcode == Opcodes.GETSTATIC && desc.equals("L" + owner + ";") && stack != null && !stack.isEmpty()) {
            Object mark = new String(owner);
            stack.set(stack.size() - 1, mark);
            marks.put(mark, name);
        }
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
        calls++;
        if (stack != null) {
            Type[] args = Type.getArgumentTypes(desc);
            int pos = stack.size();
            for (int i = args.length - 1; i >= 0 && pos > 0; i--) {
                pos -= args[i].getSize();
                String c = pos >= 0 ? marks.get(stack.get(pos)) : null;
                if (c != null) {
                    at.computeIfAbsent(calls, z -> new HashMap<>()).put(i, c);
                }
            }
        }
        super.visitMethodInsn(opcode, owner, name, desc, itf);
    }

    static Map<Integer, Map<Integer, String>> of(byte[] bytes, String name, String desc) {
        if (bytes == null) {
            return Map.of();
        }
        ClassReader reader = new ClassReader(bytes);
        Constants[] found = new Constants[1];
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                if (!n.equals(name) || !d.equals(desc) || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    return null;
                }
                return found[0] = new Constants(reader.getClassName(), access, n, d);
            }
        }, ClassReader.EXPAND_FRAMES);
        return found[0] == null ? Map.of() : found[0].at;
    }
}

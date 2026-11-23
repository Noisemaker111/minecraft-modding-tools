import java.util.*;
import org.objectweb.asm.*;

final class ReadUses extends org.objectweb.asm.commons.AnalyzerAdapter {
    static final class Read {
        final List<String[]> uses = new ArrayList<>();
        boolean bad;
    }

    final List<Read> reads = new ArrayList<>(); // by field instruction number; null for a write or a primitive
    final Map<Integer, Read> results = new HashMap<>(); // by call number: where a call's object result goes
    final Map<Object, Read> marks = new IdentityHashMap<>();
    int calls = -1;

    ReadUses(String owner, int access, String name, String desc) {
        super(Opcodes.ASM9, owner, access, name, desc, null);
    }

    Map<Object, Integer> counted() {
        Map<Object, Integer> out = new IdentityHashMap<>();
        for (Object v : stack == null ? List.of() : stack) {
            if (marks.containsKey(v)) {
                out.merge(v, 1, Integer::sum);
            }
        }
        return out;
    }

    /** The marked value at a depth below the top of the stack (0 the top), or null. */
    Object at(int depth) {
        if (stack == null || depth >= stack.size()) {
            return null;
        }
        Object v = stack.get(stack.size() - 1 - depth);
        return marks.containsKey(v) ? v : null;
    }

    /** Runs one instruction; a marked value it takes away is used as said says, or else makes its read bad. */
    void step(Map<Object, String[]> said, Runnable instruction) {
        Map<Object, Integer> before = counted();
        instruction.run();
        Map<Object, Integer> after = counted();
        before.forEach((v, n) -> {
            if (after.getOrDefault(v, 0) < n) {
                String[] use = said.get(v);
                if (use == null) {
                    marks.get(v).bad = true;
                } else {
                    marks.get(v).uses.add(use);
                }
            }
        });
    }

    /** A value still on the stack where a jump leaves goes to its target too, whose frame names its old type. */
    void carried() {
        for (Object v : stack == null ? List.of() : stack) {
            Read r = marks.get(v);
            if (r != null) {
                r.bad = true;
            }
        }
    }

    static Map<Object, String[]> said(Object v, String... use) {
        Map<Object, String[]> out = new IdentityHashMap<>();
        if (v != null) {
            out.put(v, use);
        }
        return out;
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
        step(Map.of(), () -> super.visitFieldInsn(opcode, owner, name, desc));
        boolean read = opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC;
        if (read && stack != null && (desc.startsWith("L") || desc.startsWith("["))) {
            Object mark = new String(desc.startsWith("L") ? desc.substring(1, desc.length() - 1) : desc);
            stack.set(stack.size() - 1, mark);
            Read r = new Read();
            marks.put(mark, r);
            reads.add(r);
        } else {
            reads.add(null);
        }
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
        calls++;
        Map<Object, String[]> said = new IdentityHashMap<>();
        Type[] args = Type.getArgumentTypes(desc);
        int depth = 0;
        for (int i = args.length - 1; i >= 0; i--) {
            depth += args[i].getSize();
            Object v = at(depth - 1);
            if (v != null) {
                said.putIfAbsent(v, new String[] { "A", String.valueOf(calls), args[i].getDescriptor(), String.valueOf(i), owner, name, desc, String.valueOf(opcode) });
            }
        }
        Object self = opcode == Opcodes.INVOKESTATIC ? null : at(depth);
        if (self != null && !said.containsKey(self) && opcode != Opcodes.INVOKESPECIAL) {
            said.put(self, new String[] { "R", String.valueOf(calls), owner, name, desc });
        }
        step(said, () -> super.visitMethodInsn(opcode, owner, name, desc, itf));
        Type result = Type.getReturnType(desc);
        if (stack != null && (result.getSort() == Type.OBJECT || result.getSort() == Type.ARRAY) && !name.equals("<init>")) {
            Object mark = new String(result.getSort() == Type.OBJECT ? result.getInternalName() : result.getDescriptor());
            stack.set(stack.size() - 1, mark);
            Read r = new Read();
            marks.put(mark, r);
            results.put(calls, r);
        }
    }

    @Override
    public void visitInsn(int opcode) {
        boolean test = opcode == Opcodes.POP || opcode == Opcodes.POP2 || opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT;
        Map<Object, String[]> said = test ? said(at(0), "N") : Map.of();
        if (opcode == Opcodes.POP2 && at(1) != null) {
            said = new IdentityHashMap<>(said);
            said.put(at(1), new String[] { "N" });
        }
        Map<Object, String[]> s = said;
        step(s, () -> super.visitInsn(opcode));
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        Map<Object, String[]> said = opcode == Opcodes.CHECKCAST ? said(at(0), "C", type) : Map.of();
        step(said, () -> super.visitTypeInsn(opcode, type));
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        Map<Object, String[]> said = opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL ? said(at(0), "N") : Map.of();
        step(said, () -> super.visitJumpInsn(opcode, label));
        carried();
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        step(Map.of(), () -> super.visitVarInsn(opcode, var));
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        step(Map.of(), () -> super.visitIntInsn(opcode, operand));
    }

    @Override
    public void visitLdcInsn(Object value) {
        step(Map.of(), () -> super.visitLdcInsn(value));
    }

    @Override
    public void visitIincInsn(int var, int increment) {
        step(Map.of(), () -> super.visitIincInsn(var, increment));
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) {
        step(Map.of(), () -> super.visitInvokeDynamicInsn(name, desc, bsm, args));
    }

    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
        step(Map.of(), () -> super.visitTableSwitchInsn(min, max, dflt, labels));
        carried();
    }

    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
        step(Map.of(), () -> super.visitLookupSwitchInsn(dflt, keys, labels));
        carried();
    }

    @Override
    public void visitMultiANewArrayInsn(String desc, int dims) {
        step(Map.of(), () -> super.visitMultiANewArrayInsn(desc, dims));
    }

    @Override
    public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
        step(Map.of(), () -> super.visitFrame(type, nLocal, local, nStack, stack));
    }

    /** What a method of a class's bytes does with each value it reads from a field, or null when it is not there. */
    static ReadUses of(byte[] bytes, String name, String desc) {
        if (bytes == null) {
            return null;
        }
        ClassReader reader = new ClassReader(bytes);
        ReadUses[] found = new ReadUses[1];
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                if (!n.equals(name) || !d.equals(desc) || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    return null;
                }
                return found[0] = new ReadUses(reader.getClassName(), access, n, d);
            }
        }, ClassReader.EXPAND_FRAMES);
        return found[0];
    }
}

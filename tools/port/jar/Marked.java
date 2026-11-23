import java.util.*;
import org.objectweb.asm.*;

/**
 * The stack as the JVM's verifier sees it (asm-commons' AnalyzerAdapter), where values a subclass marks keep who they
 * are across jumps: a frame gives the stack as types, so the marks a jump carries ("a ? b : c" as an argument) are kept
 * per label, where every way in agrees, and put back when the label's frame comes.
 */
abstract class Marked extends org.objectweb.asm.commons.AnalyzerAdapter {

    Marked(String owner, int access, String name, String desc, MethodVisitor mv) {
        super(Opcodes.ASM9, owner, access, name, desc, mv);
    }

    abstract boolean marked(Object value);

    final Map<Label, Object[]> carried = new HashMap<>();
    final List<Label> arrived = new ArrayList<>();

    void carry(Label label) {
        if (stack == null) {
            return;
        }
        Object[] now = stack.stream().map(v -> marked(v) ? v : null).toArray();
        carried.merge(label, now, (had, add) -> {
            if (had.length != add.length) {
                return new Object[0];
            }
            Object[] both = new Object[had.length];
            for (int i = 0; i < had.length; i++) {
                both[i] = had[i] == add[i] ? had[i] : null;
            }
            return both;
        });
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        if (opcode == Opcodes.GOTO) {
            carry(label);
            super.visitJumpInsn(opcode, label);
        } else {
            super.visitJumpInsn(opcode, label);
            carry(label);
        }
        arrived.clear();
    }

    @Override
    public void visitLabel(Label label) {
        carry(label); // the way in from the instruction before, when it falls through
        arrived.add(label);
        super.visitLabel(label);
    }

    @Override
    public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
        super.visitFrame(type, nLocal, local, nStack, stack);
        for (Label l : arrived) {
            Object[] marks = carried.get(l);
            if (marks != null && this.stack != null && marks.length == this.stack.size()) {
                for (int i = 0; i < marks.length; i++) {
                    if (marks[i] != null) {
                        this.stack.set(i, marks[i]);
                    }
                }
            }
        }
    }

    @Override
    public void visitInsn(int opcode) {
        super.visitInsn(opcode);
        arrived.clear();
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        arrived.clear();
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        arrived.clear();
        super.visitFieldInsn(opcode, owner, name, descriptor);
    }

    @Override
    public void visitLdcInsn(Object value) {
        arrived.clear();
        super.visitLdcInsn(value);
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        arrived.clear();
        super.visitIntInsn(opcode, operand);
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        arrived.clear();
        super.visitTypeInsn(opcode, type);
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        arrived.clear();
        super.visitVarInsn(opcode, var);
    }
}

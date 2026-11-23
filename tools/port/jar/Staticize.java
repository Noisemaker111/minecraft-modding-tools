import java.util.*;
import org.objectweb.asm.*;

/** A mixin handler in two: its annotations go to the handler with the new parameters, its body to name$mmt, which the handler calls. */
/**
 * An instance method's code written as a static method's, for a handler that never reads its own object: each local
 * moves down one slot, and full frames lose the object. Compressed frames are relative to the start, which the
 * writer reads from the static access.
 */
final class Staticize extends MethodVisitor {
    Staticize(MethodVisitor mv) {
        super(Opcodes.ASM9, mv);
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        super.visitVarInsn(opcode, var - 1);
    }

    @Override
    public void visitIincInsn(int var, int increment) {
        super.visitIincInsn(var - 1, increment);
    }

    @Override
    public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
        if ((type == Opcodes.F_FULL || type == Opcodes.F_NEW) && numLocal > 0) {
            super.visitFrame(type, numLocal - 1, Arrays.copyOfRange(local, 1, numLocal), numStack, stack);
            return;
        }
        super.visitFrame(type, numLocal, local, numStack, stack);
    }

    @Override
    public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
        if (index > 0) {
            super.visitLocalVariable(name, desc, signature, start, end, index - 1);
        }
    }

    @Override
    public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath, Label[] start, Label[] end, int[] index, String descriptor, boolean visible) {
        return null;
    }
}

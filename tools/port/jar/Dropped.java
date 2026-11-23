import org.objectweb.asm.*;

/** Call sites, field access, method references and the types named inside one method body. */
/**
 * A call to a method that now returns nothing (1.21.6's GuiGraphics.drawString returned where the text ended and
 * returns void), written as the new call only where the mod threw the value away: the instruction after the call pops
 * it, and the pop goes with it. Anywhere else the value is used, so the old call stays and is reported unlinked. Sites
 * offers the new call just before it writes the old one; this decides on the next instruction.
 */
final class Dropped extends MethodVisitor {

    final JarPort.Mod mod;
    String[] offer, held; // {opcode, owner, name, desc, itf} of the new call, and of the old call held back
    String heldUnlinked, offerUnlinked;
    int offerSize, heldSize;

    Dropped(JarPort.Mod mod, MethodVisitor mv) {
        super(Opcodes.ASM9, mv);
        this.mod = mod;
    }

    void offer(int opcode, String owner, String name, String desc, boolean itf, int size, String unlinked) {
        offer = new String[] { Integer.toString(opcode), owner, name, desc, Boolean.toString(itf) };
        offerSize = size;
        offerUnlinked = unlinked;
    }

    String[] alternative;

    void flush() {
        if (held != null) {
            String[] h = held;
            held = null;
            mod.unlinked.add(heldUnlinked);
            super.visitMethodInsn(Integer.parseInt(h[0]), h[1], h[2], h[3], Boolean.parseBoolean(h[4]));
        }
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
        flush();
        if (offer != null) {
            alternative = offer;
            offer = null;
            held = new String[] { Integer.toString(opcode), owner, name, desc, Boolean.toString(itf) };
            heldSize = offerSize;
            heldUnlinked = offerUnlinked;
            return;
        }
        super.visitMethodInsn(opcode, owner, name, desc, itf);
    }

    @Override
    public void visitInsn(int opcode) {
        if (held != null && (opcode == Opcodes.POP && heldSize == 1 || opcode == Opcodes.POP2 && heldSize == 2)) {
            held = null;
            String[] w = alternative;
            super.visitMethodInsn(Integer.parseInt(w[0]), w[1], w[2], w[3], Boolean.parseBoolean(w[4]));
            mod.change("call whose value the mod dropped, now returning nothing");
            return;
        }
        flush();
        super.visitInsn(opcode);
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        flush();
        super.visitIntInsn(opcode, operand);
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        flush();
        super.visitVarInsn(opcode, var);
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        flush();
        super.visitTypeInsn(opcode, type);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
        flush();
        super.visitFieldInsn(opcode, owner, name, desc);
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) {
        flush();
        super.visitInvokeDynamicInsn(name, desc, bsm, args);
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        flush();
        super.visitJumpInsn(opcode, label);
    }

    @Override
    public void visitLabel(Label label) {
        flush();
        super.visitLabel(label);
    }

    @Override
    public void visitLdcInsn(Object value) {
        flush();
        super.visitLdcInsn(value);
    }

    @Override
    public void visitIincInsn(int var, int increment) {
        flush();
        super.visitIincInsn(var, increment);
    }

    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
        flush();
        super.visitTableSwitchInsn(min, max, dflt, labels);
    }

    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
        flush();
        super.visitLookupSwitchInsn(dflt, keys, labels);
    }

    @Override
    public void visitMultiANewArrayInsn(String desc, int dims) {
        flush();
        super.visitMultiANewArrayInsn(desc, dims);
    }

    @Override
    public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath, String desc, boolean visible) {
        flush();
        return super.visitInsnAnnotation(typeRef, typePath, desc, visible);
    }

    @Override
    public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
        flush();
        super.visitFrame(type, numLocal, local, numStack, stack);
    }

    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
        flush();
        super.visitTryCatchBlock(start, end, handler, type);
    }

    @Override
    public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
        flush();
        super.visitLocalVariable(name, desc, signature, start, end, index);
    }

    @Override
    public void visitLineNumber(int line, Label start) {
        flush();
        super.visitLineNumber(line, start);
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        flush();
        super.visitMaxs(maxStack, maxLocals);
    }

    @Override
    public void visitEnd() {
        flush();
        super.visitEnd();
    }
}

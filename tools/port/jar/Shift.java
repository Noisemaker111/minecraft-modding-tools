import java.util.*;
import org.objectweb.asm.*;

/**
 * A moved body (Bridge.via): the old parameters worked out from the new ones and stored past them, and every local
 * of the old body moved by as much. loadNew pushes a new parameter itself.
 */
final class Shift extends MethodVisitor {
    final JarPort port;

    final Track track;
    final Overrides.Bridge br;
    final Type[] now, was;
    final int[] nowSlots, wasSlots;
    final int base, first; // first: the first parameter's slot, 0 in a static method
    final Set<Integer> stored;

    Shift(JarPort port, Track track, Overrides.Bridge br, String oldDesc, Set<Integer> stored, boolean isStatic) {
        super(Opcodes.ASM9, track);
        this.port = port;
        this.track = track;
        this.br = br;
        this.stored = stored;
        first = isStatic ? 0 : 1;
        now = Type.getArgumentTypes(port.oldDesc(br.desc()));
        was = Type.getArgumentTypes(oldDesc);
        nowSlots = Conversions.slots(now, first);
        wasSlots = Conversions.slots(was, first);
        base = first + Arrays.stream(now).mapToInt(Type::getSize).sum();
    }

    int at(int var) {
        return var < first ? var : var + base - first;
    }

    void loadNew(int j) {
        mv.visitVarInsn(now[j].getOpcode(Opcodes.ILOAD), nowSlots[j]);
    }

    /** Whether a call's arguments include an old parameter the body has only a default for. */
    boolean defaulted(Type[] args) {
        Integer[] src = track.sources(args);
        return src != null && Arrays.stream(src).anyMatch(s -> s != null && br.from()[s] == -1);
    }

    @Override
    public void visitCode() {
        super.visitCode();
        for (int i = 0; i < was.length; i++) {
            int f = br.from()[i];
            if (f == MixinRetargets.THIS) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                port.conversions.steps(mv, br.via()[i]);
            } else if (f < 0) {
                Conversions.pushDefault(mv, was[i]);
            } else {
                loadNew(f);
                port.conversions.steps(mv, br.via()[i]);
            }
            mv.visitVarInsn(was[i].getOpcode(Opcodes.ISTORE), at(wasSlots[i]));
            if (!stored.contains(wasSlots[i])) {
                track.mark(at(wasSlots[i]), i, was[i]);
            }
        }
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        super.visitVarInsn(opcode, at(var));
    }

    @Override
    public void visitIincInsn(int var, int increment) {
        super.visitIincInsn(at(var), increment);
    }

    @Override
    public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
        super.visitLocalVariable(name, desc, signature, start, end, at(index));
    }

    @Override
    public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
        if (type != Opcodes.F_NEW || nLocal < first) {
            super.visitFrame(type, nLocal, local, nStack, stack);
            return;
        }
        Object[] out = new Object[nLocal + now.length];
        System.arraycopy(local, 0, out, 0, first);
        for (int j = 0; j < now.length; j++) {
            out[first + j] = switch (now[j].getSort()) {
                case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> Opcodes.INTEGER;
                case Type.FLOAT -> Opcodes.FLOAT;
                case Type.LONG -> Opcodes.LONG;
                case Type.DOUBLE -> Opcodes.DOUBLE;
                case Type.ARRAY -> now[j].getDescriptor();
                default -> now[j].getInternalName();
            };
        }
        System.arraycopy(local, first, out, first + now.length, nLocal - first);
        super.visitFrame(type, out.length, out, nStack, stack);
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        super.visitMaxs(maxStack + 4, maxLocals + base - first); // a conversion's receiver and its constant arguments
    }

    @Override
    public void visitParameter(String name, int access) {
    }

    @Override
    public void visitAnnotableParameterCount(int parameterCount, boolean visible) {
    }

    @Override
    public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
        // A parameter that passes through as it is keeps its annotations (MixinExtras' @Local, @Share...) at its new place.
        int f = parameter < br.from().length ? br.from()[parameter] : -1;
        return f >= 0 && (br.via() == null || parameter >= br.via().length || br.via()[parameter] == null) && now[f].equals(was[parameter])
            ? super.visitParameterAnnotation(f, descriptor, visible) : null;
    }

    @Override
    public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath, Label[] start, Label[] end, int[] index, String descriptor, boolean visible) {
        return null;
    }
}

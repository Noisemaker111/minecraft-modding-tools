import org.objectweb.asm.*;

final class Split extends MethodVisitor {
    final JarPort port;

    final MethodVisitor handler;
    final String owner, name, desc;
    final int access;
    final MixinRetargets.Adapter adapter;
    final boolean itf;

    Split(JarPort port, MethodVisitor handler, MethodVisitor body, String owner, String name, String desc, int access, MixinRetargets.Adapter adapter, boolean itf) {
        super(Opcodes.ASM9, body);
        this.port = port;
        this.itf = itf;
        this.handler = handler;
        this.owner = owner;
        this.name = name;
        this.desc = desc;
        this.access = access;
        this.adapter = adapter;
    }

    @Override
    public AnnotationVisitor visitAnnotation(String d, boolean visible) {
        return handler.visitAnnotation(d, visible);
    }

    @Override
    public void visitAnnotableParameterCount(int parameterCount, boolean visible) {
    }

    @Override
    public void visitParameter(String n, int access) {
        if (adapter.selfFrom() < 0) {
            super.visitParameter(n, access); // a body handed its object takes one parameter more than these name
        }
    }

    @Override
    public void visitLocalVariable(String n, String d, String signature, Label start, Label end, int index) {
        boolean handed = adapter.selfFrom() >= 0 && index == 0;
        super.visitLocalVariable(handed ? "self" : n, handed ? adapter.selfType() : d, handed ? null : signature, start, end, index);
    }

    @Override
    public AnnotationVisitor visitParameterAnnotation(int parameter, String d, boolean visible) {
        // What the handler takes after the arguments it re-declares (the CallbackInfo, sugar such as @Local) keeps its
        // annotations on the handler, where Mixin reads them; the body behind it is a plain method.
        int rest = Type.getArgumentTypes(desc).length - adapter.captured();
        int now = Type.getArgumentTypes(port.oldDesc(adapter.desc())).length;
        return parameter >= adapter.captured() ? handler.visitParameterAnnotation(now - rest + parameter - adapter.captured(), d, visible) : null;
    }

    @Override
    public void visitEnd() {
        super.visitEnd();
        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
        Type[] now = Type.getArgumentTypes(port.oldDesc(adapter.desc()));
        Type[] was = Type.getArgumentTypes(desc);
        int[] slots = new int[now.length];
        int next = isStatic ? 0 : 1;
        for (int i = 0; i < now.length; i++) {
            slots[i] = next;
            next += now[i].getSize();
        }
        handler.visitCode();
        int stack = isStatic ? 0 : 1;
        boolean self = adapter.selfFrom() >= 0;
        if (self) {
            // The body takes the object its code used as its own (StoodInMixins): made from a new parameter.
            handler.visitVarInsn(Opcodes.ALOAD, slots[adapter.selfFrom()]);
            port.conversions.steps(handler, adapter.selfVia());
        } else if (!isStatic) {
            handler.visitVarInsn(Opcodes.ALOAD, 0);
        }
        for (int j = 0; j < adapter.captured(); j++) {
            int f = adapter.from()[j];
            if (f == MixinRetargets.THIS) {
                handler.visitVarInsn(Opcodes.ALOAD, 0);
                port.conversions.steps(handler, adapter.via()[j]);
            } else if (f < 0) {
                Conversions.pushDefault(handler, was[j]);
            } else {
                handler.visitVarInsn(now[f].getOpcode(Opcodes.ILOAD), slots[f]);
                port.conversions.steps(handler, adapter.via()[j]);
            }
            stack += was[j].getSize();
        }
        // The CallbackInfo and what the handler takes after it (sugar, captured locals) pass through as they are.
        for (int j = now.length - (was.length - adapter.captured()); j < now.length; j++) {
            handler.visitVarInsn(now[j].getOpcode(Opcodes.ILOAD), slots[j]);
            stack += now[j].getSize();
        }
        handler.visitMethodInsn(isStatic || self ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL, owner, name + "$mmt", self ? bodyDesc(adapter, desc) : desc, itf);
        handler.visitInsn(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN));
        handler.visitMaxs(stack + Conversions.room(adapter.via()) + (self ? Conversions.room(new String[][] { adapter.selfVia() }) : 0), next);
        handler.visitEnd();
    }

    /** The descriptor of a body that takes the object it used as its own first, as a static method. */
    static String bodyDesc(MixinRetargets.Adapter adapter, String desc) {
        return "(" + adapter.selfType() + desc.substring(1);
    }
}

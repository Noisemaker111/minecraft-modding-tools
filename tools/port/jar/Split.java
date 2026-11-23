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
        if (!isStatic) {
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
        handler.visitMethodInsn(isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL, owner, name + "$mmt", desc, itf);
        handler.visitInsn(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN));
        handler.visitMaxs(stack + 3, next);
        handler.visitEnd();
    }
}

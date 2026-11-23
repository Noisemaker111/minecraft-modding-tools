import org.objectweb.asm.*;

/** A call whose parameters the update retyped: each argument converted as the game's own callers convert it. */
final class ConvertedCalls {

    final Sites sites;

    ConvertedCalls(Sites sites) {
        this.sites = sites;
    }

    /**
     * A call whose method now takes a parameter of a type the new release makes from the old one with a constructor
     * (1.21.9's PlayerList.isOp(GameProfile) takes a NameAndId, and NameAndId(GameProfile) is public): each such
     * argument is passed through that constructor, the rest as they were (they must still fit), and the result must
     * serve where the old one did.
     */
    boolean converted(int opcode, String owner, String declOwner, String name, String desc) {
        if (sites.moved != null || opcode == Opcodes.INVOKESPECIAL) {
            return false;
        }
        boolean isStatic = opcode == Opcodes.INVOKESTATIC;
        String[] t = sites.port.route.method(declOwner, name, desc);
        Type[] before = Type.getArgumentTypes(sites.port.desc(desc)), after = Type.getArgumentTypes(t[2]);
        Type was = Type.getReturnType(sites.port.desc(desc)), now = Type.getReturnType(t[2]);
        if (before.length != after.length || !(was.equals(now) || was.getSort() != Type.VOID && sites.port.links.usable(now, was)) || !isStatic && !sites.isA(sites.port.cls(owner), t[0])) {
            return false;
        }
        String[] make = new String[before.length];
        boolean any = false;
        for (int i = 0; i < before.length; i++) {
            if (sites.port.links.usable(before[i], after[i])) {
                continue;
            }
            GameDiff.Cls into = after[i].getSort() == Type.OBJECT ? sites.port.b.classes.get(after[i].getInternalName()) : null;
            String ctor = "(" + before[i].getDescriptor() + ")V";
            GameDiff.Meth c = into == null || (into.flags & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0 ? null : into.methods.get("<init>" + ctor);
            if (c == null || (c.flags & Opcodes.ACC_PUBLIC) == 0) {
                return false;
            }
            make[i] = into.name;
            any = true;
        }
        GameDiff.Cls holder = Exposure.declaring(sites.port.b, t[0], 'm', t[1], t[2]);
        GameDiff.Meth m = holder == null ? null : holder.methods.get(t[1] + t[2]);
        if (!any || m == null || m.isStatic() != isStatic || !sites.port.links.linksNew(sites.mod, sites.callers, t[0], 'm', t[1], t[2])) {
            return false;
        }
        Type[] old = Type.getArgumentTypes(desc);
        int[] slots = sites.spill(old, 0);
        for (int i = 0; i < old.length; i++) {
            if (make[i] != null) {
                String made = sites.port.inverse.getOrDefault(make[i], make[i]);
                sites.emitTypeInsn(Opcodes.NEW, made);
                sites.emitInsn(Opcodes.DUP);
                sites.emitVarInsn(old[i].getOpcode(Opcodes.ILOAD), slots[i]);
                sites.emitMethodInsn(Opcodes.INVOKESPECIAL, made, "<init>", "(" + old[i].getDescriptor() + ")V", false);
            } else {
                sites.emitVarInsn(old[i].getOpcode(Opcodes.ILOAD), slots[i]);
            }
        }
        sites.extraStack = Math.max(sites.extraStack, 3);
        GameDiff.Cls named = sites.port.b.classes.getOrDefault(t[0], holder);
        boolean itf = (named.flags & Opcodes.ACC_INTERFACE) != 0;
        sites.emitMethodInsn(isStatic ? Opcodes.INVOKESTATIC : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, sites.port.inverse.getOrDefault(t[0], t[0]), t[1], sites.port.oldDesc(t[2]), itf);
        sites.mod.change("argument made into the type the game takes now, with its constructor");
        return true;
    }
}

import java.util.*;
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
        if (sites.moved != null || opcode == Opcodes.INVOKESPECIAL && name.equals("<init>")) {
            return false;
        }
        boolean isStatic = opcode == Opcodes.INVOKESTATIC;
        String[] t = sites.port.route.method(declOwner, name, desc);
        Type[] before = Type.getArgumentTypes(sites.port.desc(desc));
        if (Exposure.declaring(sites.port.b, t[0], 'm', t[1], t[2]) == null) {
            t = alike(t, before);
            if (t == null) {
                return false;
            }
        }
        Type[] after = Type.getArgumentTypes(t[2]);
        Type was = Type.getReturnType(sites.port.desc(desc)), now = Type.getReturnType(t[2]);
        if (before.length != after.length || !(was.equals(now) || was.getSort() != Type.VOID && sites.port.links.usable(now, was)) || !isStatic && !sites.isA(sites.port.cls(owner), t[0])) {
            return false;
        }
        String[] make = new String[before.length], read = new String[before.length];
        boolean any = false;
        for (int i = 0; i < before.length; i++) {
            if (sites.port.links.usable(before[i], after[i])) {
                continue;
            }
            read[i] = held(before[i], after[i]);
            if (read[i] != null) {
                any = true;
                continue;
            }
            if (opcode == Opcodes.INVOKESPECIAL) {
                return false;
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
        // A member closed to the caller (a copy of the newer game's code calling a protected method) is opened through the
        // mod's access transformer or widener where the loader reads one.
        if (!any || m == null || m.isStatic() != isStatic || !sites.port.links.linksNew(sites.mod, sites.callers, t[0], 'm', t[1], t[2])
            && !sites.port.links.opened(sites.mod, sites.callers, t[0], 'm', t[1], t[2])) {
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
                if (read[i] != null) {
                    sites.emitMethodInsn(Opcodes.INVOKEVIRTUAL, old[i].getInternalName(), read[i], "()" + sites.port.oldDesc(after[i].getDescriptor()), false);
                }
            }
        }
        sites.extraStack = Math.max(sites.extraStack, 3);
        GameDiff.Cls named = sites.port.b.classes.getOrDefault(t[0], holder);
        boolean itf = (named.flags & Opcodes.ACC_INTERFACE) != 0;
        sites.emitMethodInsn(opcode == Opcodes.INVOKESPECIAL ? Opcodes.INVOKESPECIAL : isStatic ? Opcodes.INVOKESTATIC : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL,
            opcode == Opcodes.INVOKESPECIAL ? owner : sites.port.inverse.getOrDefault(t[0], t[0]), t[1], sites.port.oldDesc(t[2]), itf);
        sites.mod.change(Arrays.stream(read).anyMatch(Objects::nonNull) ? "argument read from the stand-in that holds what the game takes now"
            : "argument made into the type the game takes now, with its constructor");
        return true;
    }

    /**
     * Where the route names a signature the game does not have (a class route rewrites every descriptor: 1.20's
     * renderScrollingString(GuiGraphics, ...) as one taking GuiComponent), the one method of that name on the same class
     * with as many parameters, each taking the argument as it is or what a stand-in passed there holds (1.19.4's
     * renderScrollingString(PoseStack, ...)); null when there is not exactly one.
     */
    String[] alike(String[] t, Type[] before) {
        String[] found = null;
        for (GameDiff.Meth m : MixinMembers.named(sites.port.b, t[0], t[1])) {
            Type[] p = Type.getArgumentTypes(m.desc);
            boolean fits = p.length == before.length;
            for (int i = 0; fits && i < p.length; i++) {
                fits = sites.port.links.usable(before[i], p[i]) || held(before[i], p[i]) != null;
            }
            if (fits) {
                if (found != null) {
                    return null;
                }
                found = new String[] { t[0], t[1], m.desc };
            }
        }
        return found;
    }

    /**
     * The one public no-argument method of a stand-in that is a class itself giving a value of the type the game takes
     * now (1.20's GuiGraphics.pose() for the PoseStack 1.19.4's Screen.render takes), or null.
     */
    String held(Type was, Type now) {
        if (was.getSort() != Type.OBJECT || now.getSort() != Type.OBJECT || !was.getInternalName().startsWith(StandinClasses.STANDIN)) {
            return null;
        }
        GameDiff.Cls standin = sites.port.b.classes.get(was.getInternalName());
        String found = null;
        for (GameDiff.Meth m : standin == null ? List.<GameDiff.Meth>of() : standin.methods.values()) {
            if ((m.flags & Opcodes.ACC_PUBLIC) != 0 && !m.isStatic() && m.desc.equals("()" + now.getDescriptor())) {
                if (found != null) {
                    return null;
                }
                found = m.name;
            }
        }
        return found;
    }
}

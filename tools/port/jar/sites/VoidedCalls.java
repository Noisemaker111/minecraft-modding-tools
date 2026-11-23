import java.util.*;
import org.objectweb.asm.*;

/** A call to a method the update deleted with nothing in its place, where the mod can go on without it. */
final class VoidedCalls {

    final Sites sites;

    VoidedCalls(Sites sites) {
        this.sites = sites;
    }

    /**
     * Offers Dropped the call that took this one's place and returns nothing (the route's counterpart, or the call the
     * game's code switched to), where the old one returned a value and the arguments still fit: written if the mod
     * pops the value. A moved body's stack is read as it is written (Track), so it is not held back there.
     */
    boolean voided(int opcode, String owner, String declOwner, String key, String name, String desc, String unlinked) {
        Type ret = Type.getReturnType(desc);
        if (sites.moved != null || opcode == Opcodes.INVOKESPECIAL || ret.getSort() == Type.VOID) {
            return false;
        }
        boolean isStatic = opcode == Opcodes.INVOKESTATIC;
        String wanted = Type.getMethodDescriptor(Type.VOID_TYPE, Type.getArgumentTypes(sites.port.desc(desc)));
        List<String[]> now = new ArrayList<>();
        now.add(sites.port.route.method(declOwner, name, desc));
        for (JarPort.Call c : sites.port.rewrites.getOrDefault(key, List.of())) {
            now.add(new String[] { c.owner(), c.name(), c.desc() });
        }
        for (String[] c : now) {
            if (Type.getReturnType(c[2]).getSort() != Type.VOID || !sites.port.links.widens(wanted, c[2]) || !isStatic && !sites.isA(sites.port.cls(owner), c[0])) {
                continue;
            }
            GameDiff.Cls holder = Exposure.declaring(sites.port.b, c[0], 'm', c[1], c[2]);
            GameDiff.Meth m = holder == null ? null : holder.methods.get(c[1] + c[2]);
            if (m == null || m.isStatic() != isStatic || !sites.port.links.linksNew(sites.mod, sites.callers, c[0], 'm', c[1], c[2])) {
                continue;
            }
            GameDiff.Cls named = sites.port.b.classes.getOrDefault(c[0], holder);
            boolean itf = (named.flags & Opcodes.ACC_INTERFACE) != 0;
            int op = isStatic ? Opcodes.INVOKESTATIC : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
            sites.dropped.offer(op, sites.port.inverse.getOrDefault(c[0], c[0]), c[1], sites.port.oldDesc(c[2]), itf, ret.getSize(), unlinked);
            return true;
        }
        return false;
    }
}

import java.util.*;
import org.objectweb.asm.*;

/** A member the update put behind another call (a field that is now reached through a getter chain). */
final class ChainedCalls {

    final Sites sites;

    ChainedCalls(Sites sites) {
        this.sites = sites;
    }

    /**
     * A member read from the same receiver, step by step (1.21.2: Input.jumping -> keyPresses.jump()). The receiver is
     * on the stack under the old call's arguments (desc; "" for a field read): they are set aside, the steps read
     * fields and call no-argument methods, and the last step takes them back. The result must serve as the old one.
     */
    boolean chain(String key, Type result, boolean isStatic, String desc) {
        JarPort.Chain c = sites.port.chains.get(key);
        if (c == null || isStatic) {
            return false;
        }
        String[] steps = c.steps().split(",");
        Type[] args = desc.isEmpty() ? new Type[0] : Type.getArgumentTypes(desc);
        Type[] newArgs = desc.isEmpty() ? new Type[0] : Type.getArgumentTypes(sites.port.desc(desc));
        // Plan every step before writing any: each one must link against the new release.
        List<String[]> plan = new ArrayList<>();
        String type = c.newOwner();
        for (int i = 0; i < steps.length; i++) {
            String[] s = steps[i].split(":", 2);
            boolean last = i == steps.length - 1;
            if (s.length != 2 || type == null) {
                return false;
            }
            if (s[0].equals("f")) {
                Linking.FieldHit f = sites.port.links.fieldNew(sites.mod, sites.callers, type, s[1]);
                GameDiff.Cls holder = Exposure.declaring(sites.port.b, type, 'f', s[1], null);
                boolean instance = holder == null || (holder.fields.get(s[1]).flags & Opcodes.ACC_STATIC) == 0;
                if (f == null || !f.open() || !instance || (last && !sites.port.links.usable(Type.getType(f.desc()), Type.getType(sites.port.desc(result.getDescriptor()))))) {
                    return false;
                }
                plan.add(new String[] { "f", type, s[1], f.desc() });
                type = Type.getType(f.desc()).getSort() == Type.OBJECT ? Type.getType(f.desc()).getInternalName() : null;
                continue;
            }
            GameDiff.Meth found = null;
            for (GameDiff.Cls k = sites.port.b.classes.get(type); k != null && found == null; k = k.sup == null ? null : sites.port.b.classes.get(k.sup)) {
                for (GameDiff.Meth m : k.methods.values()) {
                    Type[] params = Type.getArgumentTypes(m.desc);
                    boolean argsFit = params.length == (last ? newArgs.length : 0);
                    for (int p = 0; argsFit && p < params.length; p++) {
                        argsFit = sites.port.links.usable(newArgs[p], params[p]);
                    }
                    boolean resultFits = !last || sites.port.links.usable(Type.getReturnType(m.desc), Type.getType(sites.port.desc(result.getDescriptor())));
                    if (m.name.equals(s[1]) && (m.flags & Opcodes.ACC_STATIC) == 0 && argsFit && resultFits) {
                        found = m;
                        break;
                    }
                }
            }
            if (found == null || !sites.port.links.linksNew(sites.mod, sites.callers, type, 'm', found.name, found.desc)) {
                return false;
            }
            plan.add(new String[] { "m", type, found.name, found.desc });
            Type ret = Type.getReturnType(found.desc);
            type = ret.getSort() == Type.OBJECT ? ret.getInternalName() : null;
        }
        if (!sites.isA(sites.port.cls(key.substring(0, key.indexOf('.'))), c.newOwner())) {
            return false;
        }
        int[] slots = sites.spill(args, 0);
        for (int i = 0; i < plan.size(); i++) {
            String[] p = plan.get(i);
            if (i == plan.size() - 1) {
                for (int a = 0; a < args.length; a++) {
                    sites.emitVarInsn(args[a].getOpcode(Opcodes.ILOAD), slots[a]);
                }
            }
            if (p[0].equals("f")) {
                sites.emitFieldInsn(Opcodes.GETFIELD, sites.port.inverse.getOrDefault(p[1], p[1]), p[2], sites.port.oldDesc(p[3]));
            } else {
                sites.invoke(false, new JarPort.Call(p[1], p[2], p[3], false));
            }
        }
        return true;
    }
}

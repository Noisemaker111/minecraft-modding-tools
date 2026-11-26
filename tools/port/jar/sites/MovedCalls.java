import java.util.*;
import org.objectweb.asm.*;

/** A call to a method the game or the loader moved: to the call the game's own code switched to, or to where the method went. */
final class MovedCalls {

    final Sites sites;

    MovedCalls(Sites sites) {
        this.sites = sites;
    }

    /**
     * A call the game's own code switched to another (Registry.getHolder -> HolderGetter.get): taken when the receiver
     * is one, the arguments and the result still fit, and the mod may call it.
     */
    boolean rewritten(String key, String owner, String desc, int opcode) {
        boolean isStatic = opcode == Opcodes.INVOKESTATIC;
        for (JarPort.Call c : sites.port.rewrites.getOrDefault(key, List.of())) {
            if (c.isStatic() != isStatic || !sites.port.links.widens(sites.port.desc(desc), c.desc()) || !sites.port.links.linksNew(sites.mod, sites.callers, c.owner(), 'm', c.name(), c.desc())) {
                continue;
            }
            if (!isStatic && !sites.isA(sites.port.cls(owner), c.owner())) {
                continue;
            }
            sites.invoke(isStatic, c);
            sites.mod.change("call rewritten from the game's code");
            return true;
        }
        return false;
    }

    /**
     * A call to a method the new release dropped, written to where the game's or loader's own files say it went, when
     * nothing in them called it to line up: the old method's whole body forwarded to one method the new release still
     * has (a deprecated alias), or, for a static method, exactly one class of the same project gained a public static
     * method of its name and signature (NeoForge 1.21.7 moved PacketDistributor.sendToServer to ClientPacketDistributor).
     * Arguments and result are the same, so the call is only retargeted. False when neither says where.
     */
    boolean relocated(String declOwner, String name, String desc) {
        GameDiff.Cls oldOwner = sites.port.a.classes.get(declOwner);
        GameDiff.Meth old = oldOwner == null ? null : oldOwner.methods.get(name + desc);
        if (old == null || old.special()) {
            return false;
        }
        String newDesc = sites.port.desc(desc);
        String[] to = forwardedTo(old, newDesc);
        if (to == null && old.isStatic()) {
            to = movedStatic(declOwner, name, newDesc);
        }
        if (to == null || !sites.port.links.linksNew(sites.mod, sites.callers, to[0], 'm', to[1], newDesc)) {
            return false;
        }
        GameDiff.Cls holder = sites.port.b.classes.get(to[0]);
        boolean itf = holder != null && (holder.flags & Opcodes.ACC_INTERFACE) != 0;
        int op = old.isStatic() ? Opcodes.INVOKESTATIC : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
        sites.emitMethodInsn(op, sites.port.inverse.getOrDefault(to[0], to[0]), to[1], sites.port.oldDesc(newDesc), itf);
        return true;
    }

    /** {owner, name} (new names) of the one method old's body called and nothing else, when it takes and gives the same and the new release has it. */
    String[] forwardedTo(GameDiff.Meth old, String newDesc) {
        GameDiff.Ref[] made = old.references();
        if (made.length != 1 || made[0].kind() != 'm') {
            return null;
        }
        GameDiff.Ref r = made[0];
        if (r.owner().equals(old.owner.name) && r.name().equals(old.name) || !newDesc.equals(sites.port.desc(r.desc()))) {
            return null;
        }
        GameDiff.Cls target = Exposure.declaring(sites.port.b, sites.port.cls(r.owner()), 'm', r.name(), newDesc);
        GameDiff.Meth m = target == null ? null : target.methods.get(r.name() + newDesc);
        if (m == null || m.isStatic() != old.isStatic()) {
            return null;
        }
        // An instance alias forwards on its own receiver: the new method must be callable on the old owner's instances.
        if (!old.isStatic() && !Exposure.assignable(sites.port.b, Exposure.dotted(sites.port.cls(old.owner.name)), Exposure.dotted(target.name))) {
            return null;
        }
        return new String[] { target.name, r.name() };
    }

    /** {owner, name} of the one class in the same project (its first two package names) that gained a public static name+newDesc. */
    String[] movedStatic(String declOwner, String name, String newDesc) {
        return sites.port.overrides.movedStatics.computeIfAbsent(declOwner + "." + name + newDesc, key -> {
            String[] parts = sites.port.cls(declOwner).split("/");
            String project = parts.length > 2 ? parts[0] + "/" + parts[1] + "/" : "";
            String found = null;
            for (GameDiff.Cls c : sites.port.links.declaringIn(sites.port.b, name + newDesc, project)) {
                GameDiff.Meth m = c.methods.get(name + newDesc);
                if (!m.isStatic() || (m.flags & Opcodes.ACC_PUBLIC) == 0 || !c.name.startsWith(project)) {
                    continue;
                }
                GameDiff.Cls before = sites.port.a.classes.get(sites.port.inverse.getOrDefault(c.name, c.name));
                if (before != null && before.methods.containsKey(name + sites.port.oldDesc(newDesc))) {
                    continue;
                }
                if (found != null) {
                    return Optional.empty();
                }
                found = c.name;
            }
            return found == null ? Optional.<String[]>empty() : Optional.of(new String[] { found, name });
        }).orElse(null);
    }
}

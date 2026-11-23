import org.objectweb.asm.*;

/** A mod's super call into a game method or constructor the update moved or removed. */
final class SuperCalls {

    final Sites sites;

    SuperCalls(Sites sites) {
        this.sites = sites;
    }

    /**
     * A mixin constructor's super(...) to a constructor the update reshaped or removed. Mixin never runs or resolves it:
     * it finds the call by owner and name alone (Bytecode.findDelegateInit) and merges only what follows into the
     * target's constructors. So the call goes to the constructor the route map gives, else the superclass's one with
     * the fewest parameters, each passed its type's zero, and what follows stays as it was.
     */
    boolean mixinSuper(String owner, String desc) {
        GameDiff.RawCls self = sites.mod.classes.get(sites.mod.self);
        if (!sites.mod.mixinTargets.containsKey(sites.mod.self) || self == null || !owner.equals(self.sup) || !sites.key.contains(".<init>(")) {
            return false;
        }
        String[] moved = sites.port.a.classes.containsKey(owner) ? sites.port.route.method(owner, "<init>", desc) : null;
        return sites.zeroSuper(owner, sites.port.cls(owner), desc, moved);
    }

    /** super(...) of a class whose superclass the game removed: the same constructor of the class it extends now. */
    void superCall(String[] replaced, String desc, boolean itf) {
        GameDiff.Cls now = sites.port.b.classes.get(replaced[1]);
        boolean has = replaced[1].equals("java/lang/Object") ? desc.equals("()V") : now != null && now.methods.containsKey("<init>" + sites.port.desc(desc));
        if (!has && sites.mod.mixinTargets.containsKey(sites.mod.self)) {
            // Mixin never runs a mixin's own super(...) (see mixinSuper).
            if (!sites.zeroSuper(sites.port.inverse.getOrDefault(replaced[1], replaced[1]), replaced[1], desc, null)) {
                sites.emitMethodInsn(Opcodes.INVOKESPECIAL, sites.port.inverse.getOrDefault(replaced[1], replaced[1]), "<init>", desc, itf);
            }
            return;
        }
        if (!has) {
            sites.mod.unlinked.add("constructor of a removed superclass: " + Exposure.dotted(replaced[0]) + ".<init>" + desc + " (in " + Exposure.dotted(sites.mod.self) + ")");
        }
        sites.emitMethodInsn(Opcodes.INVOKESPECIAL, has ? sites.port.inverse.getOrDefault(replaced[1], replaced[1]) : replaced[0], "<init>", desc, itf);
    }

    /**
     * An instance member a class inherited from a superclass the game removed, called on that class: the class it extends
     * now must have it, or the call cannot be carried. True when the call is settled (reported, or left on the class
     * because the stand-in it extends now declares it).
     */
    boolean inheritedGone(int opcode, String owner, String name, String desc, JarPort.Decl decl) {
        if (opcode == Opcodes.INVOKESTATIC || decl == null || !decl.game()) {
            return false;
        }
        for (GameDiff.RawCls own = sites.mod.classes.get(owner); own != null && own.sup != null; own = sites.mod.classes.get(own.sup)) {
            String now = sites.port.links.successor(own.sup);
            if (now != null) {
                if (decl.owner().equals(own.sup) && now.startsWith(StandinClasses.STANDIN) && sites.port.b.method(now, name, sites.port.desc(desc)) != null) {
                    return true; // the old class's stand-in, which the class extends now, has it: the call stays on the class
                }
                if (!decl.owner().equals(own.sup) || sites.port.b.method(now, name, sites.port.desc(desc)) != null
                    || opcode == Opcodes.INVOKEVIRTUAL && sites.port.links.becameInterface(own.sup) && sites.port.b.method(sites.port.cls(own.sup), name, sites.port.desc(desc)) != null) {
                    return false; // still there, or reached through the interface the class implements now
                }
                sites.mod.unlinked.add("inherited from a removed superclass: " + Exposure.dotted(own.sup) + "." + name + desc + " (in " + Exposure.dotted(sites.mod.self) + ")");
                return true;
            }
        }
        return false;
    }
}

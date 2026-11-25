import java.util.*;
import org.objectweb.asm.*;

/** Lambdas and method references that implement or name a game method the update changed. */
final class LambdaSites {

    final Sites sites;

    LambdaSites(Sites sites) {
        this.sites = sites;
    }

    /**
     * A lambda made in a moved body that captures an old parameter worked out from a new one (b -> b.render(pose, ...)):
     * its body, a method of the class, moves with that parameter as the new type as a helper does (Mod.twins), and once it
     * has, the lambda captures the new parameter and is made from the moved body.
     */
    /** A lambda for an interface method whose parameters changed type, made for its moved body (JarPort.lambdas). */
    boolean callback(String name, String desc, Handle bsm, Object[] args) {
        if (!bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") || args.length < 3 || !(args[0] instanceof Type sam)
            || !(args[1] instanceof Handle impl) || !sites.mod.classes.containsKey(impl.getOwner())) {
            return false;
        }
        String iface = Type.getReturnType(desc).getInternalName();
        if (!sites.port.a.classes.containsKey(iface) || sites.port.a.method(iface, name, sam.getDescriptor()) == null
            || Exposure.declaring(sites.port.b, sites.port.cls(iface), 'm', name, sites.port.desc(sam.getDescriptor())) != null) {
            return false;
        }
        String[] now = sites.port.route.method(iface, name, sam.getDescriptor());
        Overrides.Reference ref = sites.mod.references.getOrDefault(sites.mod.self, List.of()).stream()
            .filter(r -> r.impl().equals(impl) && r.iface().equals(iface) && r.sam().equals(sam.getDescriptor())).findFirst().orElse(null);
        if (ref != null && now[1].equals(name)) {
            Object[] made = args.clone();
            made[0] = Type.getMethodType(sites.port.oldDesc(now[2]));
            made[1] = new Handle(Opcodes.H_INVOKESTATIC, sites.mod.self, ref.name(), sites.port.oldDesc(ref.desc()), sites.mod.selfInterface);
            made[2] = made[0];
            sites.emitInvokeDynamicInsn(name, desc, bsm, made);
            sites.mod.change("method reference made through an adapter for its interface method's new signature");
            return true;
        }
        Overrides.Bridge body = sites.mod.bridges.getOrDefault(impl.getOwner(), List.of()).stream()
            .filter(br -> br.target().equals(impl.getName()) && br.targetDesc().equals(impl.getDesc()) && !br.desc().equals(sites.port.desc(impl.getDesc())))
            .findFirst().orElse(null);
        if (body == null || !now[1].equals(name)) {
            return false;
        }
        Object[] made = args.clone();
        made[0] = Type.getMethodType(sites.port.oldDesc(now[2]));
        made[1] = new Handle(impl.getTag(), impl.getOwner(), body.name(), sites.port.oldDesc(body.desc()), impl.isInterface());
        made[2] = made[0];
        sites.emitInvokeDynamicInsn(name, desc, bsm, made);
        sites.mod.change("lambda made for its interface method's new signature");
        return true;
    }

    boolean capturing(String name, String desc, Handle bsm, Object[] args) {
        if (!bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") || args.length < 3 || !(args[1] instanceof Handle impl)
            || !impl.getOwner().equals(sites.mod.self) || impl.getTag() != Opcodes.H_INVOKESTATIC && impl.getTag() != Opcodes.H_INVOKESPECIAL
            && impl.getTag() != Opcodes.H_INVOKEVIRTUAL) {
            return false;
        }
        GameDiff.RawCls rc = sites.mod.classes.get(sites.mod.self);
        GameDiff.RawMeth body = rc == null ? null : rc.methods.get(impl.getName() + impl.getDesc());
        if (body == null || (body.flags & Opcodes.ACC_PRIVATE) == 0) {
            return false;
        }
        Type[] captured = Type.getArgumentTypes(desc);
        Integer[] src = sites.moved.track.sources(captured);
        int receiver = impl.getTag() == Opcodes.H_INVOKESTATIC ? 0 : 1;
        for (int k = receiver; src != null && k < captured.length; k++) {
            if (src[k] == null || sites.moved.br.via()[src[k]] == null || sites.moved.br.from()[src[k]] < 0) {
                continue;
            }
            int r = sites.moved.br.from()[src[k]];
            Type q = Type.getArgumentTypes(sites.moved.br.desc())[r];
            Overrides.Bridge twin = sites.mod.bridges.getOrDefault(sites.mod.self, List.of()).stream()
                .filter(br -> br.via() != null && br.target().equals(impl.getName()) && br.targetDesc().equals(impl.getDesc())).findFirst().orElse(null);
            if (twin == null) {
                sites.mod.twins.putIfAbsent(sites.mod.self + "." + impl.getName() + impl.getDesc(), new Object[] { k - receiver, q.getDescriptor(), sites.moved.br.via()[src[k]] });
                return false;
            }
            if (!Type.getArgumentTypes(twin.desc())[k - receiver].equals(q)) {
                return false;
            }
            int[] slots = sites.spill(captured, 0);
            for (int i = 0; i < captured.length; i++) {
                if (i == k) {
                    sites.moved.loadNew(r);
                } else {
                    sites.emitVarInsn(captured[i].getOpcode(Opcodes.ILOAD), slots[i]);
                }
            }
            sites.extraStack = Math.max(sites.extraStack, 2 * captured.length);
            Type[] now = captured.clone();
            now[k] = Type.getType(sites.port.oldDesc(q.getDescriptor()));
            Object[] made = args.clone();
            made[1] = new Handle(impl.getTag(), impl.getOwner(), twin.name(), sites.port.oldDesc(twin.desc()), impl.isInterface());
            sites.emitInvokeDynamicInsn(name, Type.getMethodDescriptor(Type.getReturnType(desc), now), bsm, made);
            sites.mod.change("lambda moved with what the game passes now");
            return true;
        }
        return false;
    }

    /** A method reference keeps its call site's types, so only a rename with the same descriptor (after class renames) links. */
    Handle handle(Handle h) {
        if (h.getTag() > Opcodes.H_PUTSTATIC) {
            String[] own = sites.mod.renamedDecl.get(h.getOwner() + "." + h.getName() + h.getDesc());
            // A reference to an override moved under a final game method still goes through the final one.
            if (own != null && (h.getTag() == Opcodes.H_INVOKESPECIAL || !sites.mod.superHook.containsKey(h.getOwner() + "." + h.getName() + h.getDesc()))) {
                return new Handle(h.getTag(), h.getOwner(), own[0], sites.port.oldDesc(own[1]), h.isInterface());
            }
            JarPort.Decl decl = h.getOwner().startsWith("[") ? null : sites.port.links.declaringOld(sites.mod, h.getOwner(), 'm', h.getName(), h.getDesc());
            if (decl == null || !decl.game()) {
                return h;
            }
            String[] t = sites.port.links.target(sites.mod, sites.callers, h.getOwner(), 'm', h.getName(), h.getDesc(), decl);
            if (t == null || !t[2].equals(sites.port.desc(h.getDesc()))) {
                int tag = h.getTag();
                if (tag == Opcodes.H_INVOKESTATIC || tag == Opcodes.H_INVOKEVIRTUAL || tag == Opcodes.H_INVOKEINTERFACE || tag == Opcodes.H_NEWINVOKESPECIAL) {
                    // X::new takes the arguments and gives an X, as the factory replacing X's constructor does.
                    String opDesc = tag == Opcodes.H_INVOKESTATIC ? h.getDesc()
                        : tag == Opcodes.H_NEWINVOKESPECIAL ? h.getDesc().substring(0, h.getDesc().indexOf(')') + 1) + "L" + h.getOwner() + ";"
                        : "(L" + h.getOwner() + ";" + h.getDesc().substring(1);
                    String[] added = sites.ruleAnswers.fix(List.of(h.getOwner(), decl.owner()), h.getName(), h.getDesc(), opDesc, tag != Opcodes.H_INVOKESTATIC && tag != Opcodes.H_NEWINVOKESPECIAL);
                    if (added != null) {
                        sites.mod.change("method reference replaced by a port rule");
                        return new Handle(Opcodes.H_INVOKESTATIC, sites.mod.self, added[0], added[1], sites.mod.selfInterface);
                    }
                }
                sites.mod.unlinked.add("method reference: " + Exposure.dotted(decl.owner()) + "." + h.getName() + h.getDesc());
                return h;
            }
            if (!t[1].equals(h.getName()) || !t[0].equals(sites.port.cls(h.getOwner()))) {
                sites.mod.change("method reference renamed");
            }
            GameDiff.Cls holder = sites.port.b.classes.get(t[0]);
            boolean itf = holder == null ? h.isInterface() : (holder.flags & Opcodes.ACC_INTERFACE) != 0;
            int tag = h.getTag() == Opcodes.H_INVOKEVIRTUAL && itf ? Opcodes.H_INVOKEINTERFACE
                : h.getTag() == Opcodes.H_INVOKEINTERFACE && !itf ? Opcodes.H_INVOKEVIRTUAL : h.getTag();
            return new Handle(tag, sites.port.inverse.getOrDefault(t[0], t[0]), t[1], sites.port.oldDesc(t[2]), itf);
        }
        return h;
    }
}

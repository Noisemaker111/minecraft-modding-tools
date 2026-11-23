import java.util.*;
import java.util.regex.*;
import org.objectweb.asm.*;

/** Calls inside a method body the port moved to a game method's new parameters: passed the new values where they are at hand. */
final class MovedBodies {

    final Sites sites;

    MovedBodies(Sites sites) {
        this.sites = sites;
    }

    /**
     * A call that does not link, made in a body moved to a game method's new parameter types (moved), to a method that
     * now takes what the game passes: each candidate {owner, name, desc, "static" | "" | "mod"} (new names) is tried in
     * order. Each new parameter is an argument of its type (paired as convert pairs them), or the moved method's own new
     * parameter that the old arguments of the call were worked out from (renderBackground(poseStack) inside render(
     * GuiGraphics...) becomes renderBackground(graphics); keyPressed(key, scan, mods) becomes keyPressed(event)), or the
     * moved method's new parameter that no old one came from, the one of its type. Every old argument must be passed
     * or be worked out from a new parameter that is passed. A static call may become a call on that parameter
     * (GuiComponent.fill(poseStack, ...) -> graphics.fill(...)).
     */
    boolean given(int opcode, String owner, String desc, List<String[]> candidates) {
        Type[] args = Type.getArgumentTypes(desc);
        Integer[] src = sites.moved.track.sources(args);
        if (src == null || Arrays.stream(src).allMatch(Objects::isNull)) {
            return false;
        }
        Type[] mine = Type.getArgumentTypes(sites.moved.br.desc());
        int[] from = sites.moved.br.from();
        Type[] argsNew = Arrays.stream(args).map(t -> Type.getType(sites.port.desc(t.getDescriptor()))).toArray(Type[]::new);
        boolean isStatic = opcode == Opcodes.INVOKESTATIC;
        for (String[] c : candidates) {
            // The mod's own moved method is static exactly where the call is (a helper moved with its parameter).
            boolean own = c[3].equals("mod"), cStatic = c[3].equals("static") || own && isStatic;
            if (!isStatic && cStatic || !own && !sites.port.links.linksNew(sites.mod, sites.callers, c[0], 'm', c[1], c[2])) {
                continue;
            }
            List<Type> params = new ArrayList<>();
            boolean receiver = isStatic && !cStatic;
            if (receiver) {
                params.add(Type.getObjectType(c[0]));
            }
            params.addAll(Arrays.asList(Type.getArgumentTypes(c[2])));
            Type[] want = params.toArray(Type[]::new);
            int[] argOf = Overrides.pair(argsNew, want), newOf = new int[want.length];
            boolean[] used = new boolean[args.length];
            Set<Integer> passed = new HashSet<>();
            boolean ok = true;
            for (int j = 0; j < want.length && ok; j++) {
                newOf[j] = -1;
                // An old parameter the moved body has no value for (a default) is never passed on as a value.
                if (argOf[j] >= 0 && !used[argOf[j]] && (src[argOf[j]] == null || from[src[argOf[j]]] >= 0)) {
                    used[argOf[j]] = true;
                    continue;
                }
                argOf[j] = -1;
                for (int r = 0; r < mine.length && newOf[j] < 0; r++) {
                    if (!mine[r].equals(want[j]) || passed.contains(r)) {
                        continue;
                    }
                    for (int k = 0; k < args.length; k++) {
                        if (!used[k] && src[k] != null && from[src[k]] == r) {
                            newOf[j] = r;
                        }
                    }
                }
                if (newOf[j] < 0) {
                    List<Integer> free = new ArrayList<>();
                    for (int r = 0; r < mine.length; r++) {
                        int rr = r;
                        if (mine[r].equals(want[j]) && Arrays.stream(from).noneMatch(f -> f == rr)) {
                            free.add(r);
                        }
                    }
                    newOf[j] = free.size() == 1 ? free.get(0) : -1;
                }
                ok = newOf[j] >= 0;
                if (ok) {
                    passed.add(newOf[j]);
                }
            }
            for (int k = 0; k < args.length && ok; k++) {
                ok = used[k] || src[k] != null && (from[src[k]] < 0 || passed.contains(from[src[k]]));
            }
            Type was = Type.getType(sites.port.desc(Type.getReturnType(desc).getDescriptor())), now = Type.getReturnType(c[2]);
            if (!ok || !(was.equals(now) || was.getSort() == Type.VOID || sites.port.links.usable(now, was))) {
                continue;
            }
            int[] slots = sites.spill(args, 0);
            for (int j = 0; j < want.length; j++) {
                if (argOf[j] >= 0) {
                    sites.emitVarInsn(args[argOf[j]].getOpcode(Opcodes.ILOAD), slots[argOf[j]]);
                } else {
                    sites.moved.loadNew(newOf[j]);
                }
            }
            sites.extraStack = Math.max(sites.extraStack, 2 * want.length);
            GameDiff.Cls holder = sites.port.b.classes.get(c[0]);
            boolean itf = own ? sites.mod.classes.get(c[0]) != null && (sites.mod.classes.get(c[0]).flags & Opcodes.ACC_INTERFACE) != 0
                : holder != null && (holder.flags & Opcodes.ACC_INTERFACE) != 0;
            int op = opcode == Opcodes.INVOKESPECIAL ? opcode : cStatic ? Opcodes.INVOKESTATIC : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
            String site = opcode == Opcodes.INVOKESPECIAL ? owner : own ? c[0] : sites.port.inverse.getOrDefault(c[0], c[0]);
            sites.emitMethodInsn(op, site, c[1], sites.port.oldDesc(c[2]), itf);
            if (was.getSort() == Type.VOID && now.getSort() != Type.VOID) {
                sites.emitInsn(now.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
            }
            sites.mod.change("call given what the game passes now");
            return true;
        }
        return false;
    }

    /**
     * A call from a moved body to one of the mod's own helpers that cannot be overridden (static, private or final),
     * passing an old parameter worked out from a new one (renderLabel(pose, ...) inside render(GuiGraphics, ...)): the
     * helper is wanted with that parameter as the new type too, so the calls it makes on it get what the game passes
     * now. port adds it and rewrites the mod again (Mod.twins).
     */
    void twin(String owner, String name, String desc) {
        GameDiff.RawCls rc = sites.mod.classes.get(owner);
        GameDiff.RawMeth m = rc == null ? null : rc.methods.get(name + desc);
        if (m == null || name.startsWith("<") || (m.flags & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
            || (m.flags & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) == 0 && (rc.flags & Opcodes.ACC_FINAL) == 0 && !alone(rc, m)) {
            return;
        }
        Type[] args = Type.getArgumentTypes(desc);
        Integer[] src = sites.moved.track.sources(args);
        for (int k = 0; src != null && k < args.length; k++) {
            if (src[k] == null || sites.moved.br.via()[src[k]] == null || sites.moved.br.from()[src[k]] < 0) {
                continue;
            }
            Type q = Type.getArgumentTypes(sites.moved.br.desc())[sites.moved.br.from()[src[k]]];
            sites.mod.twins.putIfAbsent(owner + "." + name + desc, new Object[] { k, q.getDescriptor(), sites.moved.br.via()[src[k]] });
            return;
        }
    }

    /**
     * Whether an instance method of the mod can move like a final one: no other class of the mod declares that name
     * and descriptor (nothing of the mod overrides it, or is overridden by it), and it overrides nothing of the game
     * or the loader, so every call reaches this one body.
     */
    boolean alone(GameDiff.RawCls rc, GameDiff.RawMeth m) {
        for (GameDiff.RawCls other : sites.mod.classes.values()) {
            if (other != rc && other.methods.containsKey(m.name + m.desc)) {
                return false;
            }
        }
        List<String> parents = new ArrayList<>(rc.ifaces);
        if (rc.sup != null) {
            parents.add(0, rc.sup);
        }
        return sites.port.overrides.gameRoot(sites.mod, parents, m.name, m.desc) == null;
    }

    /**
     * A call that does not link, in a moved body, one of whose arguments is an old parameter worked out from a new one
     * (pose = graphics.pose()): written as the game's own code wrote it with the new value there (GameDiff.Retyped's
     * reading, font.draw(pose, ...) -> graphics.drawString(font, ...)), the new parameter passed in the old one's place.
     */
    boolean onOrigin(int opcode, String owner, String declOwner, String name, String desc) {
        boolean isStatic = opcode == Opcodes.INVOKESTATIC;
        Type[] args = Type.getArgumentTypes(desc);
        Integer[] src = sites.moved.track.sources(args);
        if (Sites.DEBUG && name.equals("draw")) {
            System.err.println("onOrigin " + declOwner + "." + name + desc + " src=" + Arrays.toString(src) + " calls=" + sites.port.retypedCalls.getOrDefault(declOwner, List.of()).stream().map(JarPort.RetypedCall::desc).toList());
        }
        for (JarPort.RetypedCall c : sites.port.retypedCalls.getOrDefault(declOwner, List.of())) {
            int k = c.at() - 1; // $0 is the receiver, $1 the first argument (static calls have no $0)
            if (!c.name().equals(name) || !c.desc().equals(desc) || src == null || k < 0 || k >= args.length || src[k] == null) {
                continue;
            }
            int r = sites.moved.br.from()[src[k]];
            if (r < 0 || sites.moved.br.via()[src[k]] == null || !Type.getArgumentTypes(sites.moved.br.desc())[r].getDescriptor().equals(c.as())) {
                continue;
            }
            List<Type> ops = new ArrayList<>();
            if (!isStatic) {
                ops.add(Type.getObjectType(owner));
            }
            ops.addAll(Arrays.asList(args));
            ops.set(isStatic ? k : c.at(), Type.getType(sites.port.oldDesc(c.as())));
            String opDesc = Type.getMethodDescriptor(Type.getReturnType(desc), ops.toArray(Type[]::new));
            JarPort.Fix fix = new JarPort.Fix("game " + name, Pattern.compile(Pattern.quote(name)), null, c.expr());
            String[] added = sites.ruleAnswers.fix(Map.of(declOwner, List.of(fix)), List.of(declOwner), name, desc, opDesc, !isStatic);
            if (Sites.DEBUG) {
                System.err.println("  fix " + c.expr() + " " + opDesc + " -> " + Arrays.toString(added));
            }
            if (added == null) {
                continue;
            }
            int[] slots = sites.spill(args, 0);
            for (int i = 0; i < args.length; i++) {
                if (i == k) {
                    sites.moved.loadNew(r);
                } else {
                    sites.emitVarInsn(args[i].getOpcode(Opcodes.ILOAD), slots[i]);
                }
            }
            sites.invokeAdded(added);
            sites.mod.change("call written on what the game passes now, as the game's own code writes it");
            return true;
        }
        return false;
    }
}

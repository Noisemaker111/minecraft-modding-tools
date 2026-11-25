import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.util.*;
import org.objectweb.asm.*;

/** A mod's override of a game method the update renamed, retyped or replaced: moved to the new method, with a bridge where callers still use the old one. */
final class Overrides {

    final JarPort port;

    Overrides(JarPort port) {
        this.port = port;
    }

    /**
     * A method added to a mod class with a game method's new name and signature (new names), calling the mod's own
     * (target). Each old argument i is the new parameter from[i], or a default when from[i] is -1. When via is not null
     * the game method's parameters changed type, and the mod's method is replaced: its body moves to the new signature,
     * with each old parameter worked out first from new parameter from[i] through the steps via[i] (null: as it is), so
     * calls in the body that need what the game passes now get it (see Retype).
     */
    record Bridge(String name, String desc, String target, String targetDesc, int[] from, int access, String[][] via) {}

    /**
     * The game method a mod method overrides, found through the mod's own parents up to the game (ModChild.foo over
     * ModBase.foo over GameBase.foo is GameBase.foo), so every member of an override family follows a rename.
     */
    JarPort.Decl gameRoot(JarPort.Mod mod, List<String> parents, String name, String desc) {
        Deque<String> queue = new ArrayDeque<>(parents);
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String c = queue.poll();
            if (!seen.add(c)) {
                continue;
            }
            GameDiff.RawCls own = mod.classes.get(c);
            if (own != null) {
                if (own.sup != null) {
                    queue.add(own.sup);
                }
                queue.addAll(own.ifaces);
                continue;
            }
            GameDiff.Cls game = Exposure.declaring(port.a, c, 'm', name, desc);
            if (game != null) {
                return new JarPort.Decl(game.name, true);
            }
        }
        return null;
    }

    /** The game classes (new names) a mod class extends or implements, directly or through the mod's own classes. */
    List<String> gameParents(JarPort.Mod mod, GameDiff.RawCls rc) {
        List<String> out = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        if (rc.sup != null) {
            queue.add(rc.sup);
        }
        queue.addAll(rc.ifaces);
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String c = queue.poll();
            if (!seen.add(c)) {
                continue;
            }
            GameDiff.RawCls own = mod.classes.get(c);
            if (own == null) {
                out.add(port.cls(c));
                continue;
            }
            if (own.sup != null) {
                queue.add(own.sup);
            }
            queue.addAll(own.ifaces);
        }
        return out;
    }

    void override(JarPort.Mod mod, GameDiff.RawCls rc, GameDiff.RawMeth m) {
        List<String> parents = new ArrayList<>(rc.ifaces);
        if (rc.sup != null) {
            parents.add(0, rc.sup);
        }
        JarPort.Decl decl = gameRoot(mod, parents, m.name, m.desc);
        if (decl == null) {
            return;
        }
        GameDiff.Meth over = port.a.method(decl.owner(), m.name, m.desc);
        if (over == null || (over.flags & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) != 0) {
            return;
        }
        String[] moved = port.route.method(decl.owner(), m.name, m.desc);
        String mapped = port.desc(m.desc);
        // Looked up from the game classes the mod actually extends: a game subclass may keep the old name while its
        // ancestor renames it, and then the mod's method still overrides as written.
        List<String> supers = gameParents(mod, rc);
        String[] took = moved[1].equals(m.name) && moved[2].equals(mapped) ? tookOver(mod, rc, m, supers) : null;
        if (took != null) {
            moved = took;
        } else if (port.finalOverrides.finalHook(mod, rc, m, supers) instanceof String[] hook && port.finalOverrides.moveToHook(mod, rc, m, hook, supers)) {
            return;
        } else if (supers.stream().anyMatch(s -> Exposure.declaring(port.b, s, 'm', m.name, mapped) != null)) {
            return; // still overrides as written
        }
        String[] to = moved;
        if (moved[2].equals(mapped) && supers.stream().anyMatch(s -> Exposure.declaring(port.b, s, 'm', to[1], to[2]) != null)) {
            mod.renamedDecl.put(rc.name + "." + m.name + m.desc, new String[] { moved[1], moved[2] });
            mod.change(took != null ? "override moved where the game's own overrides moved" : "override renamed");
            return;
        }
        if (bridge(mod, rc, m, decl.owner(), moved, mapped, supers)) {
            boolean body = mod.bridges.get(rc.name).get(mod.bridges.get(rc.name).size() - 1).via() != null;
            mod.change(body ? "override moved to the game method's new parameter types" : "override bridged to the game method's new signature");
            return;
        }
        // The game no longer calls it: the class still loads, the hook is silent.
        mod.silent.add("override no longer called: " + Exposure.dotted(decl.owner()) + "." + m.name + m.desc + " (in " + Exposure.dotted(rc.name) + ")");
    }

    /**
     * Lambdas and method references of a class that implement a game or loader interface method whose parameters changed
     * type (a Fabric API callback: HudRenderCallback.onHudRender(PoseStack, float) -> (GuiGraphics, float)). The body, a
     * method of the class nothing else can call or override, moves to the new types as an override's does, each old
     * parameter worked out from a new one as the game's own code does (graphics.pose()), and the lambda is made for the
     * new method (Sites.capturing writes it). One that cannot move is reported: the interface's method would go unwritten.
     */
    void lambdas(JarPort.Mod mod, GameDiff.RawCls rc) {
        byte[] bytes = mod.bytes.get(rc.name);
        if (bytes == null || (new ClassReader(bytes).readShort(6) & 0xFFFF) < Opcodes.V1_8) {
            return;
        }
        List<Object[]> made = new ArrayList<>(); // {interface, method, sam descriptor, impl handle, captured descriptor}
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
                        if (bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") && args.length >= 3 && args[0] instanceof Type sam
                            && args[1] instanceof Handle impl && args[2] instanceof Type made2) {
                            made.add(new Object[] { Type.getReturnType(d).getInternalName(), n, sam.getDescriptor(), impl, d, made2.getDescriptor() });
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        for (Object[] l : made) {
            String iface = (String) l[0], name = (String) l[1], sam = (String) l[2], captured = (String) l[4];
            Handle impl = (Handle) l[3];
            if (!port.a.classes.containsKey(iface) || port.a.method(iface, name, sam) == null || Exposure.declaring(port.b, port.cls(iface), 'm', name, port.desc(sam)) != null) {
                continue;
            }
            String[] moved = port.route.method(iface, name, sam);
            GameDiff.Cls holder = Exposure.declaring(port.b, port.cls(iface), 'm', moved[1], moved[2]);
            String where = Exposure.dotted(iface) + "." + name + sam + " (in " + Exposure.dotted(rc.name) + ")";
            Bridge body = holder == null || !moved[1].equals(name) || !sam.equals(l[5]) ? null : lambdaBody(mod, rc, impl, captured, iface, name, sam, moved);
            if (body == null) {
                mod.unlinked.add("lambda for a changed method: " + where);
                continue;
            }
            mod.bridges.computeIfAbsent(impl.getOwner(), k -> new ArrayList<>()).add(body);
            mod.change("lambda moved to its interface method's new parameter types");
        }
    }

    /**
     * The moved body of a lambda for iface.name(sam), now moved[2], or null when its method cannot move: the lambda's own
     * private method, or a static method of any of the mod's classes (Hud::render), which no class can override.
     */
    Bridge lambdaBody(JarPort.Mod mod, GameDiff.RawCls rc, Handle impl, String captured, String iface, String name, String sam, String[] moved) {
        GameDiff.RawCls holder = mod.classes.get(impl.getOwner());
        GameDiff.RawMeth m = holder == null ? null : holder.methods.get(impl.getName() + impl.getDesc());
        if (m == null || (m.flags & Opcodes.ACC_STATIC) == 0 && ((m.flags & Opcodes.ACC_PRIVATE) == 0 || holder != rc) || (m.flags & Opcodes.ACC_ABSTRACT) != 0
            || mod.bytes.get(holder.name) == null || (new ClassReader(mod.bytes.get(holder.name)).readShort(6) & 0xFFFF) < Opcodes.V1_6
            || mod.bridges.getOrDefault(holder.name, List.of()).stream().anyMatch(br -> br.target().equals(m.name) && br.targetDesc().equals(m.desc))) {
            return null;
        }
        Type[] caps = Type.getArgumentTypes(captured), was = Type.getArgumentTypes(port.desc(sam)), now = Type.getArgumentTypes(moved[2]);
        int skip = (m.flags & Opcodes.ACC_STATIC) != 0 ? 0 : 1; // an instance body captures its this first
        Type[] own = Type.getArgumentTypes(m.desc);
        if (own.length != caps.length - skip + was.length || !Type.getReturnType(port.desc(sam)).equals(Type.getReturnType(moved[2]))) {
            return null;
        }
        int[] paired = pair(now, was);
        String[][] via = Retype.withoutThis(port.retype.conversions(iface, name, sam, moved, paired));
        boolean[] unread = unread(mod, holder.name, m.name, m.desc, (m.flags & Opcodes.ACC_STATIC) != 0, own.length);
        int k = caps.length - skip;
        Type[] params = new Type[k + now.length];
        int[] from = new int[own.length];
        String[][] steps = new String[own.length][];
        for (int i = 0; i < k; i++) {
            params[i] = Type.getType(port.desc(own[i].getDescriptor()));
            from[i] = i;
        }
        for (int i = 0; i < now.length; i++) {
            params[k + i] = now[i];
        }
        for (int i = 0; i < was.length; i++) {
            if (paired[i] >= 0) {
                from[k + i] = k + paired[i];
            } else if (via != null && via[i] != null) {
                from[k + i] = k + Integer.parseInt(via[i][0]);
                steps[k + i] = Arrays.copyOfRange(via[i], 1, via[i].length);
            } else if (unread != null && unread[k + i]) {
                from[k + i] = -1; // the body never reads it ((mc, parent) -> new Screen(parent)): a default goes there
            } else {
                return null;
            }
        }
        String newDesc = Type.getMethodDescriptor(Type.getType(port.desc(Type.getReturnType(m.desc).getDescriptor())), params);
        if (Arrays.stream(steps).allMatch(Objects::isNull)) {
            // Nothing to work out: an added method with the new parameters calls the body with the ones it had.
            return new Bridge(m.name, newDesc, m.name, m.desc, from, m.flags | Opcodes.ACC_SYNTHETIC, null);
        }
        return new Bridge(m.name, newDesc, m.name, m.desc, from, m.flags, steps);
    }

    /**
     * {owner, name, desc} (new names) of an abstract method the new release adds to a mod class's game supertypes that the
     * game's own subclasses moved their override of the mod method's game method into (AbstractButton.renderWidget ->
     * renderContents in 1.21.11, AbstractSelectionList.Entry.render -> renderContent in 1.21.9), when the class does not
     * write it and the game no longer lets the old one be overridden (gone, or final; then the mod's method must not call
     * the game's, which calls the new one). Null otherwise.
     */
    String[] tookOver(JarPort.Mod mod, GameDiff.RawCls rc, GameDiff.RawMeth m, List<String> supers) {
        String mapped = port.desc(m.desc);
        for (String s : supers) {
            GameDiff.Cls still = Exposure.declaring(port.b, s, 'm', m.name, mapped);
            if (still != null && (still.methods.get(m.name + mapped).flags & Opcodes.ACC_FINAL) == 0) {
                return null;
            }
        }
        Deque<String> todo = new ArrayDeque<>(supers);
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            GameDiff.Cls k = port.b.classes.get(todo.poll());
            if (k == null || !seen.add(k.name)) {
                continue;
            }
            for (GameDiff.Meth n : k.methods.values()) {
                String[] was = (n.flags & Opcodes.ACC_ABSTRACT) == 0 ? null : takeover(k.name, n);
                if (was != null && was[0].equals(m.name) && was[1].equals(m.desc)) {
                    JarPort.why(rc.name + "." + m.name + m.desc, "T took " + n.name + " written=" + rc.methods.containsKey(n.name + port.oldDesc(n.desc)) + " super=" + callsSuper(mod, rc.name, m));
                }
                if (was != null && was[0].equals(m.name) && was[1].equals(m.desc) && !rc.methods.containsKey(n.name + port.oldDesc(n.desc))
                    && !callsSuper(mod, rc.name, m)) {
                    return new String[] { k.name, n.name, n.desc };
                }
            }
            if (k.sup != null) {
                todo.add(k.sup);
            }
            todo.addAll(k.ifaces);
        }
        return null;
    }

    final Map<String, Optional<String[]>> takeovers = new HashMap<>();
    final Map<String, Optional<String[]>> movedStatics = new HashMap<>(); // Sites.movedStatic: the same for every mod of the port

    /**
     * {name, desc} (old names) of the method the game's own subclasses of k overrode in the old release and no longer do
     * in the new one, where they write k's new abstract method n instead: at least two, and most of those the old release
     * had, agreeing. Null when they do not show one.
     */
    String[] takeover(String k, GameDiff.Meth n) {
        return takeovers.computeIfAbsent(k + "." + n.name + n.desc, key -> {
            Map<String, Integer> dropped = new HashMap<>();
            Map<String, List<String>> by = new HashMap<>(); // dropped method -> the subclasses that dropped it
            int had = 0;
            for (GameDiff.Cls sub : port.links.declaring(port.b, n.name + n.desc, k)) {
                GameDiff.Meth own = sub.methods.get(n.name + n.desc);
                GameDiff.Cls old = port.a.classes.get(port.inverse.getOrDefault(sub.name, sub.name));
                if (own == null || (own.flags & Opcodes.ACC_ABSTRACT) != 0 || sub.name.equals(k) || old == null || !Linking.subtype(port.b, sub.name, k)
                    || old.methods.containsKey(n.name + port.oldDesc(n.desc))) {
                    continue;
                }
                had++;
                for (GameDiff.Meth om : old.methods.values()) {
                    if (om.name.startsWith("<") || om.isStatic() || (om.flags & Opcodes.ACC_PRIVATE) != 0) {
                        continue;
                    }
                    boolean overrides = old.sup != null && Exposure.declaring(port.a, old.sup, 'm', om.name, om.desc) != null
                        || old.ifaces.stream().anyMatch(i -> Exposure.declaring(port.a, i, 'm', om.name, om.desc) != null);
                    // No longer written under its own name (the route map may follow it to the new method: renderWidget ->
                    // renderContents in each game button, while AbstractButton's final renderWidget stays).
                    if (overrides && !sub.methods.containsKey(om.name + port.desc(om.desc))) {
                        dropped.merge(om.name + om.desc, 1, Integer::sum);
                        by.computeIfAbsent(om.name + om.desc, x -> new ArrayList<>()).add(sub.name);
                    }
                }
            }
            int total = had;
            List<Map.Entry<String, Integer>> best = dropped.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(2).toList();
            if (best.isEmpty() || best.get(0).getValue() < 2 || best.get(0).getValue() * 10 < total * 8
                || best.size() > 1 && best.get(1).getValue().equals(best.get(0).getValue())) {
                return Optional.empty();
            }
            String sig = best.get(0).getKey();
            String[] was = { sig.substring(0, sig.indexOf('(')), sig.substring(sig.indexOf('(')) };
            // Methods that went at the same time are not one moved to the other (1.19.4 dropped changeFocus as it added
            // visitWidgets): the subclasses' new bodies must be mostly their old ones.
            return moved(by.get(sig), was, n) ? Optional.of(was) : Optional.empty();
        }).orElse(null);
    }

    /**
     * Whether most of the game subclasses' new bodies of n are their old bodies of was: at least half of each body's
     * instructions in common, for most of them. False when the game's code cannot be read.
     */
    boolean moved(List<String> subs, String[] was, GameDiff.Meth n) {
        GameCode oldCode = port.code(port.a), newCode = port.code(port.b);
        if (oldCode == null || newCode == null) {
            return false;
        }
        int alike = 0;
        for (String sub : subs) {
            Map<String, String> before = oldCode.bodies(port.inverse.getOrDefault(sub, sub), port::cls), now = newCode.bodies(sub, x -> x);
            String x = before == null ? null : before.get(was[0] + port.desc(was[1])), y = now == null ? null : now.get(n.name + n.desc);
            if (x != null && y != null && alike(x, y)) {
                alike++;
            }
        }
        return alike * 10 >= subs.size() * 8;
    }

    /** At least half of two bodies' instructions (as GameCode.bodies writes them) in common, counted as multisets. */
    static boolean alike(String x, String y) {
        Map<String, Integer> left = new HashMap<>();
        String[] a = GameCode.instructions(x), b = GameCode.instructions(y);
        for (String t : a) {
            left.merge(t, 1, Integer::sum);
        }
        int common = 0;
        for (String t : b) {
            Integer n = left.get(t);
            if (n != null && n > 0) {
                common++;
                left.put(t, n - 1);
            }
        }
        return common * 2 >= Math.max(a.length, b.length);
    }

    /**
     * Whether a mod method reaches the game method of its own name and descriptor (super.name(...), or this.name(...) in
     * a helper of the mod's it calls, a lambda included): moved to the game's new method, which the game's final old one
     * calls, it would call itself back. A call that reaches another mod class's method of that name moves with it.
     */
    boolean callsSuper(JarPort.Mod mod, String owner, GameDiff.RawMeth m) {
        Deque<String[]> todo = new ArrayDeque<>(List.<String[]>of(new String[] { owner, m.name, m.desc }));
        Set<String> seen = new HashSet<>();
        boolean[] reaches = { false };
        while (!todo.isEmpty() && !reaches[0]) {
            String[] at = todo.poll();
            byte[] bytes = mod.bytes.get(at[0]);
            if (bytes == null || !seen.add(at[0] + "." + at[1] + at[2])) {
                continue;
            }
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    return !name.equals(at[1]) || !desc.equals(at[2]) ? null : new MethodVisitor(Opcodes.ASM9) {
                        void call(String o, String n, String d) {
                            JarPort.Decl decl = o.startsWith("[") ? null : port.links.declaringOld(mod, o, 'm', n, d);
                            if (n.equals(m.name) && d.equals(m.desc) && (decl == null || decl.game())) {
                                reaches[0] = true;
                            } else if (decl != null && !decl.game()) {
                                todo.add(new String[] { decl.owner(), n, d });
                            }
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String o, String n, String d, boolean itf) {
                            call(o, n, d);
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
                            for (Object arg : args) {
                                if (arg instanceof Handle h) {
                                    call(h.getOwner(), h.getName(), h.getDesc());
                                }
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return reaches[0];
    }

    /**
     * The game method a mod method overrides took other parameters (moved: its new owner, name and descriptor). The mod
     * class gets a method with the new signature that calls the mod's own with the arguments it had: each found among the
     * new ones by type (same types in another order, some added, one now narrower), or a default where the mod's body
     * never reads it. The result must serve as the new one. Anything else stays an override the game no longer calls.
     */
    boolean bridge(JarPort.Mod mod, GameDiff.RawCls rc, GameDiff.RawMeth m, String gameOwner, String[] moved, String mapped, List<String> supers) {
        GameDiff.Meth now = null;
        for (String s : supers) {
            GameDiff.Cls holder = Exposure.declaring(port.b, s, 'm', moved[1], moved[2]);
            if (holder != null) {
                now = holder.methods.get(moved[1] + moved[2]);
                break;
            }
        }
        if (now == null || moved[2].equals(mapped) || (now.flags & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) != 0) {
            return false;
        }
        String signature = moved[1] + moved[2];
        boolean taken = rc.methods.containsKey(moved[1] + port.oldDesc(moved[2]))
            || mod.bridges.getOrDefault(rc.name, List.of()).stream().anyMatch(br -> (br.name() + br.desc()).equals(signature))
            || mod.renamedDecl.entrySet().stream().anyMatch(e -> e.getKey().startsWith(rc.name + ".") && (e.getValue()[0] + e.getValue()[1]).equals(signature));
        if (taken) {
            return false; // the class already has a method with the new signature
        }
        Type result = Type.getReturnType(mapped), wanted = Type.getReturnType(moved[2]);
        if (!result.equals(wanted) && (wanted.getSort() == Type.VOID || !port.links.usable(result, wanted))) {
            return false;
        }
        Type[] was = Type.getArgumentTypes(mapped), params = Type.getArgumentTypes(moved[2]);
        boolean[] unread = unread(mod, rc.name, m.name, m.desc, (m.flags & Opcodes.ACC_STATIC) != 0, was.length);
        int[] from = convert(params, was, unread);
        int access = (rc.flags & Opcodes.ACC_INTERFACE) != 0 ? Opcodes.ACC_PUBLIC : m.flags & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED);
        if (from != null) {
            mod.bridges.computeIfAbsent(rc.name, k -> new ArrayList<>()).add(new Bridge(moved[1], moved[2], m.name, m.desc, from, access | Opcodes.ACC_SYNTHETIC, null));
            return true;
        }
        // Parameters that changed type: each old one from a new one, through what the game's own overrides use for it.
        if ((rc.flags & Opcodes.ACC_INTERFACE) != 0 || (m.flags & Opcodes.ACC_ABSTRACT) != 0 || mod.bytes.get(rc.name) == null
            || (new ClassReader(mod.bytes.get(rc.name)).readShort(6) & 0xFFFF) < Opcodes.V1_6) {
            return false; // a moved body needs stack map frames to carry over
        }
        int[] paired = pair(params, was);
        String[][] via = port.retype.conversions(gameOwner, m.name, m.desc, moved, paired);
        JarPort.why(rc.name + "." + m.name + m.desc, "B moved=" + Arrays.toString(moved) + " paired=" + Arrays.toString(paired) + " via=" + (via == null ? null : Arrays.deepToString(via))
            + " unread=" + Arrays.toString(unread));
        from = new int[was.length];
        String[][] steps = new String[was.length][];
        for (int i = 0; i < was.length; i++) {
            if (paired[i] >= 0) {
                from[i] = paired[i];
            } else if (via != null && via[i] != null) {
                from[i] = Integer.parseInt(via[i][0]);
                steps[i] = Arrays.copyOfRange(via[i], 1, via[i].length);
            } else if (unread != null && unread[i] || onlyPassed(mod, rc.name, m, Conversions.slots(was)[i])) {
                from[i] = -1;
            } else {
                return false;
            }
        }
        mod.bridges.computeIfAbsent(rc.name, k -> new ArrayList<>()).add(new Bridge(moved[1], moved[2], m.name, m.desc, from, m.flags & ~Opcodes.ACC_ABSTRACT, steps));
        return true;
    }

    /**
     * Whether a mod method only ever passes the parameter in slot to game methods whose parameters changed: then it needs
     * no value of its own in a moved body, because each such call is given what the game passes now instead (Sites.given;
     * keyPressed(key, scan, mods) only passing scan and mods on to super.keyPressed). Read as GameDiff reads bodies.
     */
    boolean onlyPassed(JarPort.Mod mod, String owner, GameDiff.RawMeth m, int slot) {
        byte[] bytes = mod.bytes.get(owner);
        GameDiff.Origin o = new GameDiff.Origin(bytes, Map.of(), null, new GameDiff.Names(null), new HashMap<>());
        for (MethodModel mm : ClassFile.of().parse(bytes).methods()) {
            if (mm.methodName().stringValue().equals(m.name) && mm.methodType().stringValue().equals(m.desc)) {
                GameDiff.Body body = new BodyDecoder(o, owner).run(mm);
                if (body == null) {
                    return false;
                }
                int[] seen = new int[1];
                for (GameDiff.Node s : body.statements) {
                    if (!passedOnly(mod, s, slot, seen)) {
                        return false;
                    }
                }
                return seen[0] > 0;
            }
        }
        return false;
    }

    boolean passedOnly(JarPort.Mod mod, GameDiff.Node n, int slot, int[] seen) {
        for (GameDiff.Node k : n.kids) {
            if (k.op == 'p' && Integer.valueOf(slot).equals(k.value)) {
                seen[0]++;
                if (!(n.op == 'v' || n.op == 's') || n.ref == null || n.ref.kind() != 'm') {
                    return false;
                }
                JarPort.Decl decl = n.ref.owner().startsWith("[") ? null : port.links.declaringOld(mod, n.ref.owner(), 'm', n.ref.name(), n.ref.desc());
                if (decl == null || !decl.game() || port.route.method(decl.owner(), n.ref.name(), n.ref.desc())[2].equals(port.desc(n.ref.desc()))) {
                    return false;
                }
            } else if (!passedOnly(mod, k, slot, seen)) {
                return false;
            }
        }
        return true;
    }

    /**
     * For each parameter of to, the parameter of from with the same type: a type found once on each side wherever it
     * stands, or in order when both sides have it equally often; -2 where there is none.
     */
    static int[] pair(Type[] from, Type[] to) {
        int[] out = new int[to.length];
        for (int j = 0; j < to.length; j++) {
            out[j] = -2;
            List<Integer> same = new ArrayList<>();
            for (int i = 0; i < from.length; i++) {
                if (from[i].equals(to[j])) {
                    same.add(i);
                }
            }
            long wanted = Arrays.stream(to).filter(to[j]::equals).count();
            if (!same.isEmpty() && same.size() == wanted) {
                int nth = (int) Arrays.stream(to, 0, j).filter(to[j]::equals).count();
                out[j] = same.get(nth);
            }
        }
        return out;
    }

    /**
     * For each parameter of to (new names), the parameter of from that supplies it, or -1 where unread says to's method
     * never reads it; null when one cannot be supplied. A type found once on each side is taken wherever it stands; a
     * type that appears several times pairs up in order only when both sides have it as often. A parameter with no
     * match takes the one parameter of from whose type now serves as it (a subtype).
     */
    int[] convert(Type[] from, Type[] to, boolean[] unread) {
        int[] out = pair(from, to);
        boolean[] taken = new boolean[from.length];
        for (int j = 0; j < to.length; j++) {
            if (out[j] >= 0) {
                taken[out[j]] = true;
            }
        }
        for (int j = 0; j < to.length; j++) {
            if (out[j] != -2) {
                continue;
            }
            List<Integer> fits = new ArrayList<>();
            for (int i = 0; i < from.length; i++) {
                if (!taken[i] && port.links.usable(from[i], to[j])) {
                    fits.add(i);
                }
            }
            if (fits.size() == 1) {
                out[j] = fits.get(0);
                taken[fits.get(0)] = true;
            } else if (unread != null && unread[j]) {
                out[j] = -1;
            } else {
                return null;
            }
        }
        return out;
    }

    /** Which of a mod method's first n parameters its body never reads; null when it has no body here. */
    boolean[] unread(JarPort.Mod mod, String owner, String name, String desc, boolean isStatic, int n) {
        byte[] bytes = mod.bytes.get(owner);
        if (bytes == null) {
            return null;
        }
        Type[] params = Type.getArgumentTypes(desc);
        Map<Integer, Integer> slots = new HashMap<>(); // slot -> parameter
        int slot = isStatic ? 0 : 1;
        for (int i = 0; i < n && i < params.length; i++) {
            slots.put(slot, i);
            slot += params[i].getSize();
        }
        boolean[] out = new boolean[n];
        boolean[] seen = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String mname, String mdesc, String signature, String[] exceptions) {
                if (!mname.equals(name) || !mdesc.equals(desc) || (access & Opcodes.ACC_ABSTRACT) != 0) {
                    return null;
                }
                seen[0] = true;
                Arrays.fill(out, true);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitVarInsn(int opcode, int var) {
                        if (opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD || opcode == Opcodes.RET) {
                            read(var);
                        }
                    }

                    @Override
                    public void visitIincInsn(int var, int increment) {
                        read(var);
                    }

                    void read(int var) {
                        Integer p = slots.get(var);
                        if (p != null) {
                            out[p] = false;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        return seen[0] ? out : null;
    }

    /**
     * A mod method whose game method's parameters changed type, written with the new signature (br): its old parameters
     * are worked out from the new ones first, its body follows with every local moved past the new parameters, and calls
     * in it that need what the game passes now get it (Sites.given). Read with expanded frames, which are rewritten with
     * the new parameters in front.
     */
    void moved(JarPort.Mod mod, byte[] bytes, String owner, Bridge br, ClassVisitor out) {
        Set<Integer> stored = new HashSet<>();
        ClassReader reader = new ClassReader(bytes);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return !name.equals(br.target()) || !desc.equals(br.targetDesc()) ? null : new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitVarInsn(int opcode, int var) {
                        if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) {
                            stored.add(var);
                        }
                    }

                    @Override
                    public void visitIincInsn(int var, int increment) {
                        stored.add(var);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (!name.equals(br.target()) || !desc.equals(br.targetDesc())) {
                    return null;
                }
                String newDesc = port.oldDesc(br.desc());
                String key = owner + "." + name + desc;
                // A handler made static, as its target now is (staticizes): written as a static method under everything else.
                boolean staticized = mod.staticized.contains(key) && mod.retargets.keySet().stream().anyMatch(r -> r.startsWith(key + " "));
                MethodVisitor written = out.visitMethod(staticized ? access | Opcodes.ACC_STATIC : access, br.name(), newDesc, null, exceptions);
                Track track = new Track(owner, access, br.name(), newDesc, staticized ? new Staticize(written) : written);
                Shift shift = new Shift(port, track, br, desc, stored, (access & Opcodes.ACC_STATIC) != 0);
                // A mixin handler's code runs in its targets, and its annotations name them (as in rewrite).
                List<String> targets = mod.mixinTargets.getOrDefault(owner, List.of());
                List<String> callers = new ArrayList<>(targets.isEmpty() ? List.of(owner) : targets.stream().map(port::cls).toList());
                if (targets.stream().anyMatch(t -> !mod.classes.containsKey(t) && !port.a.classes.containsKey(t))) {
                    callers.add(owner);
                }
                Sites sites = new Sites(port, mod, shift, mod.maxLocals.getOrDefault(key, 0), targets, callers, key, Exposure.dotted(owner) + "." + name);
                sites.moved = shift;
                return sites;
            }
        }, ClassReader.EXPAND_FRAMES);
    }
    /** The bridge's code: the mod's own method called on this with the arguments it takes, its result returned. */
    void bridgeCode(MethodVisitor mv, String owner, boolean itf, Bridge br) {
        Type[] now = Type.getArgumentTypes(port.oldDesc(br.desc()));
        Type[] was = Type.getArgumentTypes(br.targetDesc());
        boolean isStatic = (br.access() & Opcodes.ACC_STATIC) != 0; // a static lambda body's (JarPort.lambdaBody)
        int[] slots = new int[now.length];
        int next = isStatic ? 0 : 1;
        for (int i = 0; i < now.length; i++) {
            slots[i] = next;
            next += now[i].getSize();
        }
        mv.visitCode();
        if (!isStatic) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
        }
        int stack = isStatic ? 0 : 1;
        for (int j = 0; j < was.length; j++) {
            int f = br.from()[j];
            if (f < 0) {
                Conversions.pushDefault(mv, was[j]);
            } else {
                mv.visitVarInsn(now[f].getOpcode(Opcodes.ILOAD), slots[f]);
            }
            stack += was[j].getSize();
        }
        mv.visitMethodInsn(isStatic ? Opcodes.INVOKESTATIC : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, owner, br.target(), br.targetDesc(), itf);
        Type ret = Type.getReturnType(br.targetDesc());
        mv.visitInsn(ret.getOpcode(Opcodes.IRETURN));
        mv.visitMaxs(Math.max(stack, ret.getSize()), next);
        mv.visitEnd();
    }

    /** entries with from renamed to to, in the same place, keeping its time. */
    static Map<String, byte[]> moved(Map<String, byte[]> entries, Map<String, Long> times, String from, String to) {
        Map<String, byte[]> moved = new LinkedHashMap<>();
        entries.forEach((k, v) -> moved.put(k.equals(from) ? to : k, v));
        times.put(to, times.getOrDefault(from, 0L));
        return moved;
    }
}

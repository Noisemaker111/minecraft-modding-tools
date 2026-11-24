import java.util.*;
import java.util.regex.*;
import org.objectweb.asm.*;

final class Sites extends MethodVisitor {

    final RetypedValues retypedValues = new RetypedValues(this);
    final SuperCalls superCalls = new SuperCalls(this);
    final MovedCalls movedCalls = new MovedCalls(this);
    final ConvertedCalls convertedCalls = new ConvertedCalls(this);
    final VoidedCalls voidedCalls = new VoidedCalls(this);
    final DefaultedArguments defaultedArguments = new DefaultedArguments(this);
    final ChainedCalls chainedCalls = new ChainedCalls(this);
    final RuleAnswers ruleAnswers = new RuleAnswers(this);
    final MovedBodies movedBodies = new MovedBodies(this);
    final LambdaSites lambdaSites = new LambdaSites(this);
    final JarPort port;

    final JarPort.Mod mod;
    final List<String> targets;
    final List<String> callers;
    final String key;
    final String where;
    final Dropped dropped;
    int nextLocal;
    int extraStack; // what code written in place of an instruction may push beyond it
    int fieldsSeen = -1, callsSeen = -1; // the method's field and method instructions so far, as ReadUses numbers them

    Sites(JarPort port, JarPort.Mod mod, MethodVisitor mv, int locals, List<String> targets, List<String> callers, String key, String where) {
        super(Opcodes.ASM9, new Dropped(mod, mv));
        this.port = port;
        dropped = (Dropped) this.mv;
        this.mod = mod;
        this.nextLocal = locals;
        this.targets = targets;
        this.callers = callers;
        this.key = key;
        this.where = where;
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        AnnotationVisitor av = super.visitAnnotation(desc, visible);
        String target = mod.accessorTargets.get(key);
        if (MixinMembers.ACCESSORS.containsKey(desc)) {
            // The accessor names its target in "value"; a renamed target is written there, so the method keeps its own name.
            return target == null || av == null ? av : new AnnotationVisitor(Opcodes.ASM9, av) {
                @Override
                public void visit(String n, Object v) {
                    if (!"value".equals(n)) {
                        super.visit(n, v);
                    }
                }

                @Override
                public void visitEnd() {
                    super.visit("value", target);
                    super.visitEnd();
                }
            };
        }
        return port.mixinMembers.mixinStrings(mod, targets, av, key);
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        super.visitMaxs(maxStack + 4 + extraStack, Math.max(maxLocals, nextLocal));
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
        fresh = false;
        fieldsSeen++;
        port.types(mod, desc, where);
        owner = mod.shadowTargets.getOrDefault(owner + "." + name, owner);
        String[] own = mod.renamedDecl.get(owner + "." + name);
        if (own != null) {
            super.visitFieldInsn(opcode, owner, own[0], desc);
            return;
        }
        JarPort.Decl decl = owner.startsWith("[") ? null : port.links.declaringOld(mod, owner, 'f', name, desc);
        if (decl == null || !decl.game()) {
            super.visitFieldInsn(opcode, owner, name, desc);
            return;
        }
        boolean read = opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC;
        String[] t = port.links.target(mod, callers, owner, read ? 'r' : 'f', name, desc, decl);
        if (t == null && read && chainedCalls.chain(decl.owner() + "." + name, Type.getType(desc), opcode == Opcodes.GETSTATIC, "")) {
            mod.change("field read through a chain");
            return;
        }
        if (t == null && read) {
            String[] added = ruleAnswers.fix(List.of(owner, decl.owner()), name, null, opcode == Opcodes.GETSTATIC ? "()" + desc : "(L" + owner + ";)" + desc, opcode == Opcodes.GETFIELD);
            if (added != null) {
                invokeAdded(added);
                mod.change("field read replaced by a port rule");
                return;
            }
        }
        if (t == null && !read) {
            // A write through what replaces it ("name=": $0 the receiver unless static, then the value).
            String[] added = ruleAnswers.fix(List.of(owner, decl.owner()), name + "=", null, opcode == Opcodes.PUTSTATIC ? "(" + desc + ")V" : "(L" + owner + ";" + desc + ")V", opcode == Opcodes.PUTFIELD);
            if (added != null) {
                invokeAdded(added);
                mod.change("field write replaced by a port rule");
                return;
            }
        }
        if (t == null && read && retypedValues.retyped(opcode, owner, name, desc, decl)) {
            return;
        }
        if (t == null) {
            // The JVM finds a field by name and type: a reference of another type than the old release's field did not
            // link before the update either (code for another version behind a check, a type of a later release), so
            // the update broke nothing there and it stays as written.
            GameDiff.Cls old = Exposure.declaring(port.a, decl.owner(), 'f', name, null);
            if (old != null && old.fields.get(name).desc.equals(desc)) {
                mod.unlinked.add("field: " + Exposure.dotted(decl.owner()) + "." + name);
            }
            super.visitFieldInsn(opcode, owner, name, desc);
            return;
        }
        if (!t[1].equals(name) || !t[0].equals(port.cls(owner))) {
            mod.change("field renamed");
        }
        super.visitFieldInsn(opcode, port.inverse.getOrDefault(t[0], t[0]), t[1], port.oldDesc(t[2]));
    }

    boolean gameDeclared(String owner, String name, String desc) {
        return port.links.gameDeclared(mod, owner, name, desc);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
        fresh = false;
        callsSeen++;
        String hookCall = opcode == Opcodes.INVOKESPECIAL && mod.superHook.containsKey(mod.self + "." + name + desc) && gameDeclared(owner, name, desc)
            ? mod.superHook.get(mod.self + "." + name + desc) : null;
        if (hookCall != null) {
            // A moved override's super call to the method the update made final: to the hook it calls instead.
            if (hookCall.startsWith("copy:")) {
                super.visitMethodInsn(Opcodes.INVOKESPECIAL, mod.self, hookCall.substring(5), desc, false);
            } else if (hookCall.startsWith("own:")) {
                super.visitMethodInsn(mod.selfInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, mod.self, hookCall.substring(4), desc, mod.selfInterface);
            } else {
                super.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, hookCall, desc, itf);
            }
            return;
        }
        String[] onNew = retypedValues.onNewType.remove(callsSeen);
        if (onNew != null) {
            int op = Integer.parseInt(onNew[0]);
            GameDiff.Cls holder = port.b.classes.get(port.cls(onNew[1]));
            super.visitMethodInsn(op, onNew[1], onNew[2], onNew[3], op == Opcodes.INVOKEINTERFACE || holder != null && (holder.flags & Opcodes.ACC_INTERFACE) != 0);
            mod.change("call on a field's value made on its new type");
            return;
        }
        String[] closing = opcode == Opcodes.INVOKESPECIAL && name.equals("<init>") && !news.isEmpty() && news.peek()[0].equals(owner) ? news.peek() : null;
        String[] replaced = mod.supers.get(mod.self);
        if (closing == null && replaced != null && opcode == Opcodes.INVOKESPECIAL && name.equals("<init>") && owner.equals(replaced[0])) {
            superCalls.superCall(replaced, desc, itf);
            return;
        }
        call(opcode, owner, name, desc, itf);
        if (closing != null && news.remove(closing) && "null".equals(closing[2])) {
            // Nothing replaced it: the class cannot be made in the new release.
            mod.unlinked.add("new of a class the new release cannot make: " + Exposure.dotted(owner) + " (in " + where + ")");
        }
    }

    /**
     * A mixin's super(...) (desc, old names) written as one to class now (new names; owner in the old ones, as the
     * renamer runs after): the constructor moved names when now has it, else its one with the fewest parameters,
     * each passed its type's zero. False when now has no constructor a subclass may call.
     */
    boolean zeroSuper(String owner, String now, String desc, String[] moved) {
        GameDiff.Cls holder = port.b.classes.get(now);
        if (holder == null && !now.equals("java/lang/Object")) {
            return false;
        }
        String chosen = holder == null ? "()V" : moved != null && moved[0].equals(now) && holder.methods.containsKey("<init>" + moved[2]) ? moved[2] : null;
        if (chosen == null) {
            for (GameDiff.Meth m : holder.methods.values()) {
                if (m.name.equals("<init>") && (m.flags & Opcodes.ACC_PRIVATE) == 0
                    && (chosen == null || Type.getArgumentTypes(m.desc).length < Type.getArgumentTypes(chosen).length)) {
                    chosen = m.desc;
                }
            }
        }
        if (chosen == null) {
            return false;
        }
        drop(desc, new int[0]);
        for (Type t : Type.getArgumentTypes(chosen)) {
            Conversions.pushDefault(mv, t);
        }
        extraStack = Math.max(extraStack, 2 * Type.getArgumentTypes(chosen).length);
        super.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", port.oldDesc(chosen), false);
        return true;
    }

    /**
     * Whether the mod's moved method body works out each argument this moved body passes it from a new parameter the
     * way this body does (the same steps): one helper moved for a HUD handler's partial tick, read as (false), is not
     * what a world handler's (true) wants.
     */
    boolean sameReadings(Overrides.Bridge body, String desc) {
        Integer[] src = moved.track.sources(Type.getArgumentTypes(desc));
        for (int k = 0; src != null && k < src.length; k++) {
            String[] mine = src[k] == null ? null : moved.br.via()[src[k]];
            if (mine != null && (body.via() == null || k >= body.via().length || !Arrays.equals(mine, body.via()[k]))) {
                return false;
            }
        }
        return true;
    }

    void call(int opcode, String owner, String name, String desc, boolean itf) {
        String[] own = mod.renamedDecl.get(owner + "." + name + desc);
        // An override moved under a final game method is still reached through it; only super calls go straight there.
        if (own != null && (opcode == Opcodes.INVOKESPECIAL || !mod.superHook.containsKey(owner + "." + name + desc))) {
            super.visitMethodInsn(opcode, owner, own[0], port.oldDesc(own[1]), itf);
            return;
        }
        JarPort.Decl decl = owner.startsWith("[") ? null : port.links.declaringOld(mod, owner, 'm', name, desc);
        if (superCalls.inheritedGone(opcode, owner, name, desc, decl)) {
            super.visitMethodInsn(opcode, owner, name, desc, itf);
            return;
        }
        Overrides.Bridge body = decl == null || decl.game() ? null : mod.bridges.getOrDefault(decl.owner(), List.of()).stream()
            .filter(br -> br.via() != null && br.target().equals(name) && br.targetDesc().equals(desc)).findFirst().orElse(null);
        if (body != null) {
            // The mod's own method moved to the game's new parameter types: a call made where they are at hand follows it.
            // Only where it works out each old argument as this body does: two handlers may read one tracker two ways.
            if (moved == null || !sameReadings(body, desc) || !movedBodies.given(opcode, owner, desc, List.<String[]>of(new String[] { decl.owner(), body.name(), body.desc(), "mod" }))) {
                if (DEBUG) {
                    System.err.println("kept " + name + desc + " for a call in " + where + " moved=" + (moved != null) + " src=" + (moved == null ? "" : Arrays.toString(moved.track.sources(Type.getArgumentTypes(desc)))));
                }
                mod.kept.add(name + desc); // the old methods of that name stay for this call (see port)
                super.visitMethodInsn(opcode, owner, name, desc, itf);
            }
            return;
        }
        if (moved != null && decl != null && !decl.game()) {
            movedBodies.twin(decl.owner(), name, desc);
        }
        if (decl == null || !decl.game()) {
            if (decl != null) {
                String[] inherited = mod.renamedDecl.get(decl.owner() + "." + name + desc);
                if (inherited != null && (opcode == Opcodes.INVOKESPECIAL || !mod.superHook.containsKey(decl.owner() + "." + name + desc))) {
                    super.visitMethodInsn(opcode, owner, inherited[0], port.oldDesc(inherited[1]), itf);
                    return;
                }
            }
            super.visitMethodInsn(opcode, owner, name, desc, itf);
            return;
        }
        String[] t = port.links.target(mod, callers, owner, 'm', name, desc, decl);
        String declNow = port.cls(decl.owner()), named = port.cls(owner);
        if (t != null && (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE) && !t[0].equals(named) && !isA(named, t[0])
            && !t[0].equals(declNow) && !isA(declNow, t[0])) {
            // A method the game moved to a class that neither the call's class nor the one declaring it is (26.2's
            // Gui.getChat() is Hud.getChat()) is not called on the old receiver, which the verifier refuses: a rule
            // says how to reach the new one (gui.hud.getChat()). Either class will do: a call on 1.18's TextComponent
            // (gone in 1.19) names MutableComponent's withStyle, which its ported value is.
            t = null;
        }
        String key = decl.owner() + "." + name + desc;
        boolean superCall = opcode == Opcodes.INVOKESPECIAL;
        if (t == null && moved != null) {
            List<String[]> now = new ArrayList<>();
            String[] m = port.route.method(decl.owner(), name, desc);
            String retyped = name.equals("<init>") ? port.constructors.retypedConstructor(decl.owner(), desc) : null;
            if (retyped != null) {
                m = new String[] { port.cls(decl.owner()), name, retyped }; // a moved constructor's super(...)
            }
            now.add(new String[] { m[0], m[1], m[2], opcode == Opcodes.INVOKESTATIC ? "static" : "" });
            for (JarPort.Call c : port.rewrites.getOrDefault(key, List.of())) {
                now.add(new String[] { c.owner(), c.name(), c.desc(), c.isStatic() ? "static" : "" });
            }
            if (movedBodies.given(opcode, owner, desc, now) || movedBodies.onOrigin(opcode, owner, decl.owner(), name, desc)) {
                return;
            }
            if (moved.defaulted(Type.getArgumentTypes(desc))) {
                // The body has no value to pass here: it is not moved after all (see port), and stays as it was.
                mod.unmoved.add(Sites.this.key);
                super.visitMethodInsn(opcode, owner, name, desc, itf);
                return;
            }
        }
        if (t == null && !superCall && movedCalls.rewritten(key, owner, desc, opcode)) {
            return;
        }
        if (t == null && defaultedArguments.defaulted(key, owner, desc, opcode)) {
            return;
        }
        if (t == null && opcode == Opcodes.INVOKEVIRTUAL || t == null && opcode == Opcodes.INVOKEINTERFACE) {
            if (chainedCalls.chain(key, Type.getReturnType(desc), false, desc)) {
                mod.change("call through a chain");
                return;
            }
        }
        if (t == null) {
            String[] moved = port.route.method(decl.owner(), name, desc);
            int[] keep = port.links.linksNew(mod, callers, moved[0], 'm', moved[1], moved[2]) ? MixinRetargets.kept(port.desc(desc), moved[2]) : null;
            if (keep != null) {
                drop(desc, keep);
                mod.change("call arguments dropped");
                super.visitMethodInsn(opcode, port.inverse.getOrDefault(moved[0], moved[0]), moved[1], port.oldDesc(moved[2]), itf);
                return;
            }
            if (port.removals.containsKey(decl.owner() + "." + name + desc) && Type.getReturnType(desc) == Type.VOID_TYPE) {
                drop(desc, new int[0]);
                if (opcode != Opcodes.INVOKESTATIC) {
                    super.visitInsn(Opcodes.POP);
                } else if (Type.getArgumentTypes(desc).length == 0) {
                    // Nothing else is written in its place: a frame after it would fall on the frame before it.
                    super.visitInsn(Opcodes.NOP);
                }
                mod.change("call removed");
                return;
            }
            if (opcode == Opcodes.INVOKESPECIAL && name.equals("<init>") && superCalls.mixinSuper(owner, desc)) {
                mod.change("mixin's super call to its superclass's new constructor");
                return;
            }
            if (opcode == Opcodes.INVOKESPECIAL && name.equals("<init>") && constructed(owner, decl.owner(), desc)) {
                mod.change("constructor arguments from a port rule");
                return;
            }
            if (opcode == Opcodes.INVOKESPECIAL && name.equals("<init>") && ruleAnswers.made(owner, decl.owner(), desc)) {
                mod.change("constructor replaced by a factory");
                return;
            }
            if (opcode != Opcodes.INVOKESPECIAL) {
                String opDesc = opcode == Opcodes.INVOKESTATIC ? desc : "(L" + owner + ";" + desc.substring(1);
                String[] added = ruleAnswers.fix(List.of(owner, decl.owner()), name, desc, opDesc, opcode != Opcodes.INVOKESTATIC);
                if (added != null) {
                    invokeAdded(added);
                    mod.change("call replaced by a port rule");
                    return;
                }
            }
            if (opcode == Opcodes.INVOKESPECIAL && !name.equals("<init>") && Sites.this.moved == null) {
                // Not in a body moved to a game method's new parameters: a stand-in that draws through the new method
                // (Renderable.render(pose) calls render(graphics)) would call that body back.
                String[] added = ruleAnswers.fix(port.superFixes, List.of(owner, decl.owner()), name, desc, "(L" + owner + ";" + desc.substring(1), true);
                if (added != null) {
                    invokeAdded(added);
                    mod.change("super call answered by a stand-in");
                    return;
                }
            }
            String unlinked = "call: " + Exposure.dotted(decl.owner()) + "." + name + desc + (WHERE ? " @ " + where + (Sites.this.moved != null ? " (moved)" : "") : "");
            if (convertedCalls.converted(opcode, owner, decl.owner(), name, desc)) {
                return;
            }
            if (retypedValues.resultConverted(opcode, owner, decl.owner(), name, desc)) {
                return;
            }
            if (movedCalls.relocated(decl.owner(), name, desc)) {
                mod.change("call to where the game or loader moved the method");
                return;
            }
            if (!voidedCalls.voided(opcode, owner, decl.owner(), key, name, desc, unlinked)) {
                mod.unlinked.add(unlinked);
            }
            super.visitMethodInsn(opcode, owner, name, desc, itf);
            return;
        }
        if (!t[1].equals(name) || !t[2].equals(port.desc(desc)) || !t[0].equals(port.cls(owner))) {
            mod.change("call renamed");
        }
        boolean nowInterface = itf;
        GameDiff.Cls holder = port.b.classes.get(t[0]);
        // The class may have become an interface under the same name (1.21.2's InteractionResult was an enum).
        if (holder != null) {
            nowInterface = (holder.flags & Opcodes.ACC_INTERFACE) != 0;
            if (opcode == Opcodes.INVOKEVIRTUAL && nowInterface) {
                opcode = Opcodes.INVOKEINTERFACE;
            } else if (opcode == Opcodes.INVOKEINTERFACE && !nowInterface) {
                opcode = Opcodes.INVOKEVIRTUAL;
            }
        }
        super.visitMethodInsn(opcode, port.inverse.getOrDefault(t[0], t[0]), t[1], port.oldDesc(t[2]), nowInterface);
    }

    boolean isA(String c, String target) {
        return port.links.isA(mod, c, target);
    }

    /**
     * new X(old arguments), or super(...), where X's constructor changed: a rule's "new X(...)" gives the new
     * arguments from the old ones, which are set aside in fresh locals while the object being made stays on the
     * stack, and X's new constructor is called on it. The object cannot be passed to a method before its constructor
     * runs, so this is written in place rather than in an added method.
     */
    boolean constructed(String owner, String declOwner, String desc) {
        String now = port.cls(owner);
        if (!port.b.classes.containsKey(now)) {
            return false;
        }
        Type[] old = Type.getArgumentTypes(desc);
        Type[] params = new Type[old.length];
        for (int i = 0; i < old.length; i++) {
            params[i] = Type.getType(port.desc(old[i].getDescriptor()));
            if (!port.links.present(mod, params[i])) {
                return false;
            }
        }
        for (String o : new LinkedHashSet<>(List.of(owner, declOwner))) {
            for (JarPort.Fix f : port.fixes.getOrDefault(o, List.of())) {
                Matcher n = f.name().matcher("<init>");
                if (!n.matches() || f.args() != null && !desc.startsWith(f.args()) || !ruleAnswers.holds(f)) {
                    continue;
                }
                try {
                    FixExpr.Node node = port.ruleCode.parsed(FixExpr.fill(f.expr(), n));
                    if (!(node instanceof FixExpr.New made) || !made.type().replace('.', '/').equals(now) || RuleCode.dropsUnnamed(f, node, old.length)) {
                        continue;
                    }
                    Lowering low = new Lowering(port, mod, callers, params, false, nextLocal);
                    Linking.Member m = low.choose(low.methods(now, "<init>"), made.args(), now + ".<init>");
                    RuleCode.Code code = new RuleCode.Code();
                    low.arguments(m, made.args(), code);
                    for (int i = old.length - 1; i >= 0; i--) {
                        super.visitVarInsn(old[i].getOpcode(Opcodes.ISTORE), low.slots[i]);
                    }
                    nextLocal = low.locals;
                    extraStack = Math.max(extraStack, code.max);
                    code.steps.forEach(step -> step.accept(mv));
                    super.visitMethodInsn(Opcodes.INVOKESPECIAL, port.inverse.getOrDefault(now, now), "<init>", port.oldDesc(m.desc()), false);
                    return true;
                } catch (RuleCode.Refused | IllegalArgumentException notHere) {
                    // Another rule may still give one that links.
                }
            }
        }
        return false;
    }

    void invokeAdded(String[] added) {
        super.visitMethodInsn(Opcodes.INVOKESTATIC, mod.self, added[0], added[1], mod.selfInterface);
    }

    /** A call in the new release's names, written in the old ones (the class renamer runs after), as an interface call where it is one. */
    void invoke(boolean isStatic, JarPort.Call c) {
        GameDiff.Cls holder = port.b.classes.get(c.owner());
        boolean itf = holder != null && (holder.flags & Opcodes.ACC_INTERFACE) != 0;
        int op = isStatic ? Opcodes.INVOKESTATIC : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
        super.visitMethodInsn(op, port.inverse.getOrDefault(c.owner(), c.owner()), c.name(), port.oldDesc(c.desc()), itf);
    }

    /** Stores the arguments from index from on, which are on top of the stack, in fresh locals; returns each one's slot. */
    int[] spill(Type[] args, int from) {
        int[] slots = new int[args.length];
        for (int i = args.length - 1; i >= from; i--) {
            slots[i] = nextLocal;
            nextLocal += args[i].getSize();
            super.visitVarInsn(args[i].getOpcode(Opcodes.ISTORE), slots[i]);
        }
        return slots;
    }

    Shift moved; // set when this body moved to a game method's new parameter types

    /** -Djarport.where=true: each unlinked call says which method makes it. */
    static final boolean WHERE = Boolean.getBoolean("jarport.where");

    static final boolean DEBUG = Boolean.getBoolean("jarport.debug");

    /** Takes the arguments not in keep off the stack: all are stored in fresh locals and the kept ones loaded back. */
    void drop(String desc, int[] keep) {
        Type[] args = Type.getArgumentTypes(desc);
        int[] slots = spill(args, 0);
        for (int k : keep) {
            super.visitVarInsn(args[k].getOpcode(Opcodes.ILOAD), slots[k]);
        }
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) {
        port.types(mod, desc, where);
        if (lambdaSites.callback(name, desc, bsm, args) || moved != null && lambdaSites.capturing(name, desc, bsm, args)) {
            return;
        }
        for (Object o : args) {
            if (o instanceof Handle h && mod.bridges.getOrDefault(h.getOwner(), List.of()).stream()
                .anyMatch(br -> br.via() != null && br.target().equals(h.getName()) && br.targetDesc().equals(h.getDesc()))) {
                mod.kept.add(h.getName() + h.getDesc()); // a lambda body that moved stays too for this lambda, made where it cannot follow
            }
        }
        Object[] mapped = args.clone();
        for (int i = 0; i < mapped.length; i++) {
            if (mapped[i] instanceof Handle h) {
                mapped[i] = lambdaSites.handle(h);
            }
        }
        for (Object o : mapped) {
            if (o instanceof Type t) {
                port.types(mod, t.getDescriptor(), where);
            }
        }
        super.visitInvokeDynamicInsn(name, desc, bsm, mapped);
    }

    @Override
    public void visitLdcInsn(Object value) {
        fresh = false;
        if (value instanceof Type t) {
            port.types(mod, t.getDescriptor(), where);
        }
        super.visitLdcInsn(value instanceof Handle h ? lambdaSites.handle(h) : value);
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        if (opcode == Opcodes.INSTANCEOF && !type.startsWith("[") && !mod.classes.containsKey(type)) {
            String[] added = ruleAnswers.fix(List.of(type), "instanceof", null, "(Ljava/lang/Object;)Z", false);
            if (added != null) {
                invokeAdded(added);
                mod.change("instanceof answered by a stand-in");
                lastLabel = null;
                fresh = false;
                return;
            }
        }
        port.types(mod, type.startsWith("[") ? type : "L" + type + ";", where);
        boolean unmakeable = opcode == Opcodes.NEW && becameUnmakeable(type);
        if (unmakeable) {
            // A class the new release made an interface or abstract (1.21.5's ClickEvent) is never made here: a
            // replacement gives the value (made()), so a null holds the object's place until then, and the frames
            // that named the object not yet constructed name the null.
            super.visitInsn(Opcodes.ACONST_NULL);
            if (lastLabel != null) {
                nulled.add(lastLabel);
            }
        } else {
            super.visitTypeInsn(opcode, type);
        }
        lastLabel = null;
        if (opcode == Opcodes.NEW) {
            news.push(new String[] { type, null, unmakeable ? "null" : null });
            fresh = true;
        } else {
            fresh = false;
        }
    }

    /** A game class the old release could make and the new one cannot: an interface or abstract now. Not the mod's own copy. */
    boolean becameUnmakeable(String type) {
        if (mod.classes.containsKey(type)) {
            return false;
        }
        GameDiff.Cls was = port.a.classes.get(type), now = port.b.classes.get(port.cls(type));
        int never = Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
        return was != null && now != null && (was.flags & never) == 0 && (now.flags & never) != 0;
    }

    Label lastLabel; // the label just before the next instruction, where a new's frames find it
    final Set<Label> nulled = new HashSet<>(); // the labels of news written as null

    @Override
    public void visitLabel(Label label) {
        super.visitLabel(label);
        lastLabel = label;
    }

    @Override
    public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
        super.visitFrame(type, numLocal, nulls(local, numLocal), numStack, nulls(stack, numStack));
    }

    Object[] nulls(Object[] items, int n) {
        if (items == null || nulled.isEmpty()) {
            return items;
        }
        Object[] out = items.clone();
        for (int i = 0; i < n && i < out.length; i++) {
            if (out[i] instanceof Label l && nulled.contains(l)) {
                out[i] = Opcodes.NULL;
            }
        }
        return out;
    }

    // Objects made with new and not yet constructed, innermost first: {class, "dup" when new was followed by dup,
    // "null" when written as a null}.
    final Deque<String[]> news = new ArrayDeque<>();
    boolean fresh; // the last instruction was a new

    @Override
    public void visitInsn(int opcode) {
        if (opcode == Opcodes.DUP && fresh && !news.isEmpty()) {
            news.peek()[1] = "dup";
        }
        fresh = false;
        super.visitInsn(opcode);
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        fresh = false;
        super.visitVarInsn(opcode, var);
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        fresh = false;
        super.visitIntInsn(opcode, operand);
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        fresh = false;
        super.visitJumpInsn(opcode, label);
    }

    @Override
    public void visitMultiANewArrayInsn(String desc, int dims) {
        port.types(mod, desc, where);
        super.visitMultiANewArrayInsn(desc, dims);
    }

    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
        if (type != null) {
            port.types(mod, "L" + type + ";", where);
        }
        super.visitTryCatchBlock(start, end, handler, type);
    }

    /** MethodVisitor's own visitFieldInsn, for the code of this class that lives in its parts. */
    void emitFieldInsn(int arg0, String arg1, String arg2, String arg3) {
        super.visitFieldInsn(arg0, arg1, arg2, arg3);
    }

    /** MethodVisitor's own visitMethodInsn, for the code of this class that lives in its parts. */
    void emitMethodInsn(int arg0, String arg1, String arg2, String arg3, boolean arg4) {
        super.visitMethodInsn(arg0, arg1, arg2, arg3, arg4);
    }

    /** MethodVisitor's own visitTypeInsn, for the code of this class that lives in its parts. */
    void emitTypeInsn(int arg0, String arg1) {
        super.visitTypeInsn(arg0, arg1);
    }

    /** MethodVisitor's own visitInsn, for the code of this class that lives in its parts. */
    void emitInsn(int arg0) {
        super.visitInsn(arg0);
    }

    /** MethodVisitor's own visitVarInsn, for the code of this class that lives in its parts. */
    void emitVarInsn(int arg0, int arg1) {
        super.visitVarInsn(arg0, arg1);
    }

    /** MethodVisitor's own visitInvokeDynamicInsn, for the code of this class that lives in its parts. */
    void emitInvokeDynamicInsn(String arg0, String arg1, Handle arg2, Object... arg3) {
        super.visitInvokeDynamicInsn(arg0, arg1, arg2, arg3);
    }
}

import java.util.*;
import java.util.regex.*;
import org.objectweb.asm.*;

/** Mixin handlers whose target method changed its parameters: the handler re-declared, its locals and sugar followed. */
final class MixinRetargets {

    final JarPort port;

    MixinRetargets(JarPort port) {
        this.port = port;
    }

    static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    static final Set<String> CALLBACKS = Set.of(
        "org/spongepowered/asm/mixin/injection/callback/CallbackInfo",
        "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable"
    );

    /** Mixin's and MixinExtras' annotations that inject at an @At into a target method. */
    static final Set<String> INJECTORS = Set.of(INJECT,
        "Lorg/spongepowered/asm/mixin/injection/Redirect;", "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;", "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
        "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
        "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;", "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
        "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;", "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;");

    /**
     * A handler's injector (its annotation): the target method strings, each @At's {value, target, ordinal} (in @Slice
     * too), and its require (-1: the mixin config's defaultRequire).
     */
    /** ordinal: the injector's own (a @ModifyVariable's nth local of its type), -1 when it gives none, then its index (a @ModifyArg's argument); an @At's is in ats. */
    record Injector(String kind, List<String> methods, List<String[]> ats, int[] require, int[] ordinal, boolean[] cancellable) {}

    /**
     * A handler re-declared with its target's new parameters (desc, new names) and the original body moved to name$mmt:
     * each of the first captured parameters of the original comes from the new parameter from[i], or is a default
     * where the body never reads it.
     */
    record Adapter(String desc, int[] from, int captured, String[][] via) {}

    /** In a Bridge's from: the old parameter is read from the object itself (this, then via's steps), not a new parameter. */
    static final int THIS = -3;

    /** Collects an @Inject's method strings and @At values; at is the @At being read, or null. */
    static AnnotationVisitor injection(Injector inj, String[] at) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String n, Object v) {
                if (at == null && "require".equals(n) && v instanceof Integer i) {
                    inj.require()[0] = i;
                } else if (at == null && "cancellable".equals(n) && Boolean.TRUE.equals(v)) {
                    inj.cancellable()[0] = true;
                } else if (at == null && "ordinal".equals(n) && v instanceof Integer i && i >= 0) {
                    inj.ordinal()[0] = i;
                } else if (at == null && "index".equals(n) && v instanceof Integer i && i >= 0) {
                    inj.ordinal()[1] = i;
                } else if (at != null && v instanceof String s && ("value".equals(n) || "target".equals(n))) {
                    at["value".equals(n) ? 0 : 1] = s;
                } else if (at != null && "ordinal".equals(n) && v instanceof Integer i && i >= 0) {
                    at[2] = String.valueOf(i);
                }
            }

            @Override
            public AnnotationVisitor visitArray(String n) {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String x, Object v) {
                        if (at == null && "method".equals(n) && v instanceof String s) {
                            inj.methods().add(s);
                        }
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String x, String d) {
                        return nested(d);
                    }
                };
            }

            @Override
            public AnnotationVisitor visitAnnotation(String n, String d) {
                return nested(d);
            }

            AnnotationVisitor nested(String d) {
                if (!d.equals(AT)) {
                    return injection(inj, null);
                }
                String[] found = new String[3];
                inj.ats().add(found);
                return injection(inj, found);
            }
        };
    }

    /** Positions of the new parameters among the old ones when the new list is the old one with some taken out (one way only), else null. */
    static int[] kept(String from, String to) {
        if (!Type.getReturnType(from).equals(Type.getReturnType(to))) {
            return null;
        }
        return MapFix.uniqueEmbedding(MapFix.paramTypes(from), MapFix.paramTypes(to));
    }

    /** The old release's method a game method was (the same name and parameters, or one the route map follows to it); null for a new one. */
    GameDiff.Meth kept(GameDiff.Meth d) {
        GameDiff.Cls old = port.a.classes.get(port.inverse.getOrDefault(d.owner.name, d.owner.name));
        if (old == null) {
            return null;
        }
        GameDiff.Meth same = old.methods.get(d.name + port.oldDesc(d.desc));
        if (same != null) {
            return same;
        }
        for (GameDiff.Meth om : old.methods.values()) {
            String[] to = port.route.method(old.name, om.name, om.desc);
            if (to[0].equals(d.owner.name) && to[1].equals(d.name) && to[2].equals(d.desc)) {
                return om;
            }
        }
        return null;
    }

    /**
     * Whether a handler follows a target the update made static by being made static itself, as Mixin asks of a handler
     * into a static method: the old target was an instance method, the handler is one, and its body never reads its own
     * object (slot 0) and no method of the mixin calls it. Records it in staticized when so.
     */
    boolean staticizes(JarPort.Mod mod, String key, GameDiff.RawMeth handler, GameDiff.Meth before, GameDiff.Meth after) {
        if (before.isStatic() || !after.isStatic() || (handler.flags & Opcodes.ACC_STATIC) != 0) {
            return false;
        }
        String owner = key.substring(0, key.indexOf('.'));
        byte[] bytes = mod.bytes.get(owner);
        if (bytes == null) {
            return false;
        }
        boolean[] blocked = { false };
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                boolean self = name.equals(handler.name) && desc.equals(handler.desc);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitVarInsn(int op, int var) {
                        blocked[0] |= self && var == 0;
                    }

                    @Override
                    public void visitIincInsn(int var, int inc) {
                        blocked[0] |= self && var == 0;
                    }

                    @Override
                    public void visitMethodInsn(int op, String o, String n, String d, boolean itf) {
                        blocked[0] |= o.equals(owner) && n.equals(handler.name) && d.equals(handler.desc);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (blocked[0]) {
            JarPort.why(key, "T1 its target is static now and it reads its own object");
            return false;
        }
        mod.staticized.add(key);
        return true;
    }

    static final String WRAP_CONDITION = "WrapWithCondition;", REDIRECT = "/Redirect;", MODIFY_RECEIVER = "/ModifyReceiver;",
        MODIFY_VALUE = "/ModifyExpressionValue;", MODIFY_ARG = "/ModifyArg;";

    /**
     * An injector other than @Inject whose @At names a call that only took other arguments, in a method that kept its
     * signature (a call the update changed, as 1.21.4's ClientInput.tick(boolean, float) became tick()). Its @At moves to
     * the call's new signature where the handler stays valid there, by what the handler takes of the call:
     * a @ModifyExpressionValue takes only its result, so the result type must be the same; a @ModifyArg takes one
     * argument, found in the new call where its type stands once on each side, the index rewritten; a @Redirect,
     * @WrapWithCondition or @ModifyReceiver takes the receiver and every argument, so it is re-declared with the new
     * arguments and its body gets the old ones from them (convert: a type found once on each side, or a subtype), a
     * default only where the body never reads one. A @WrapOperation passes the arguments on to the call as an array and
     * stays reported. The call must be as static as before, and still made where the old method made it (pointsKept).
     */
    void followCall(JarPort.Mod mod, String key, List<String> targets, GameDiff.RawMeth handler, Injector inj, List<MixinMembers.Found> into, List<MixinMembers.Found> points) {
        String kind = inj.kind();
        String label = "A-" + kind.substring(kind.lastIndexOf('/') + 1, kind.length() - 1);
        int which = -1;
        for (int i = 0; i < points.size(); i++) {
            MixinMembers.Found at = points.get(i);
            if (at != null && at.kind() == 's') {
                if (which >= 0 || at.desc() == null) {
                    JarPort.why(key, label + "1 several changed calls, or no one new signature");
                    return;
                }
                which = i;
            }
        }
        String[] point = inj.ats().get(which);
        MixinMembers.Found at = points.get(which);
        Matcher m = MixinMembers.MEMBER.matcher(point[1]);
        if (!point[0].startsWith("INVOKE") || !m.matches() || m.group(1) == null || m.group(3) == null) {
            JarPort.why(key, label + "2 not a call written with its owner and descriptor");
            return;
        }
        JarPort.Decl oldDecl = port.links.declaringOld(mod, m.group(1), 'm', m.group(2), m.group(3));
        GameDiff.Cls oldCls = oldDecl == null ? null : port.a.classes.get(oldDecl.owner());
        GameDiff.Meth before = oldCls == null ? null : oldCls.methods.get(m.group(2) + m.group(3));
        GameDiff.Meth after = port.b.method(at.owner(), at.name(), at.desc());
        if (before == null || after == null || before.isStatic() != after.isStatic()) {
            JarPort.why(key, label + "3 the call is not read on both sides, or changed staticness");
            return;
        }
        if (!port.mixinPoints.pointsKept(targets, inj, into, points)) {
            JarPort.why(key, label + "4 the new call is not where the old one was");
            return;
        }
        Type[] was = Type.getArgumentTypes(port.desc(m.group(3))), now = Type.getArgumentTypes(at.desc());
        Type wasResult = Type.getReturnType(port.desc(m.group(3))), nowResult = Type.getReturnType(at.desc());
        Type[] hp = Type.getArgumentTypes(port.desc(handler.desc));
        if (kind.endsWith(MODIFY_VALUE)) {
            if (!wasResult.equals(nowResult)) {
                JarPort.why(key, label + "5 its result changed type");
                return;
            }
        } else if (kind.endsWith(MODIFY_ARG)) {
            int index = inj.ordinal().length > 1 ? inj.ordinal()[1] : -1;
            if (hp.length != 1) {
                JarPort.why(key, label + "6 it takes every argument");
                return;
            }
            int old = index;
            if (old < 0) {
                List<Integer> same = new ArrayList<>();
                for (int k = 0; k < was.length; k++) {
                    if (was[k].equals(hp[0])) {
                        same.add(k);
                    }
                }
                old = same.size() == 1 ? same.get(0) : -1;
            }
            int[] paired = Overrides.pair(was, now);
            int fresh = -1;
            for (int j = 0; j < now.length; j++) {
                if (old >= 0 && paired[j] == old) {
                    fresh = j;
                }
            }
            if (fresh < 0 || !now[fresh].equals(hp[0]) || index < 0 && Arrays.stream(now).filter(hp[0]::equals).count() != 1) {
                JarPort.why(key, label + "7 its argument is not in the new call once");
                return;
            }
            if (index >= 0 && fresh != index) {
                mod.argIndexes.put(key, fresh);
            }
        } else if (kind.endsWith(REDIRECT) || kind.endsWith(WRAP_CONDITION) || kind.endsWith(MODIFY_RECEIVER)) {
            int r = before.isStatic() ? 0 : 1;
            int captured = r + was.length;
            Type wantResult = kind.endsWith(REDIRECT) ? wasResult : kind.endsWith(WRAP_CONDITION) ? Type.BOOLEAN_TYPE : null;
            Type result = Type.getReturnType(port.desc(handler.desc));
            if (hp.length < captured || !Arrays.equals(Arrays.copyOfRange(hp, r, captured), was) || kind.endsWith(REDIRECT) && !wasResult.equals(nowResult)
                || wantResult != null && !result.equals(wantResult)) {
                JarPort.why(key, label + "8 it does not take the call's arguments as they were, or the result changed");
                return;
            }
            boolean[] unread = port.overrides.unread(mod, key.substring(0, key.indexOf('.')), handler.name, handler.desc, (handler.flags & Opcodes.ACC_STATIC) != 0, captured);
            boolean[] args = unread == null ? null : Arrays.copyOfRange(unread, r, captured);
            int[] conv = port.overrides.convert(now, was, args);
            String[][] via = new String[captured][];
            if (conv == null) {
                // An argument that changed type: what the game's code reads from the new one wherever it used the old one
                // as it was (Retype.follow).
                conv = Overrides.pair(now, was);
                for (int k = 0; k < was.length && conv != null; k++) {
                    String[] seen = conv[k] >= 0 ? null : port.retype.follow(oldDecl.owner(), m.group(2), m.group(3), k);
                    if (seen != null && Integer.parseInt(seen[0]) >= 0) {
                        conv[k] = Integer.parseInt(seen[0]);
                        via[r + k] = Arrays.copyOfRange(seen, 1, seen.length);
                    } else if (conv[k] < 0 && args != null && args[k]) {
                        conv[k] = -1;
                    } else if (conv[k] < 0) {
                        conv = null;
                    }
                }
            }
            if (conv == null) {
                JarPort.why(key, label + "9 it reads an argument the new call does not take");
                return;
            }
            int rest = hp.length - captured;
            Type[] desc = new Type[r + now.length + rest];
            int[] from = new int[captured];
            for (int k = 0; k < r; k++) {
                desc[k] = hp[k];
                from[k] = k;
            }
            for (int j = 0; j < now.length; j++) {
                desc[r + j] = now[j];
            }
            for (int k = 0; k < was.length; k++) {
                from[r + k] = conv[k] < 0 ? -1 : r + conv[k];
            }
            for (int k = 0; k < rest; k++) {
                desc[r + now.length + k] = hp[captured + k];
            }
            mod.adapters.put(key, new Adapter(Type.getMethodDescriptor(result, desc), from, captured, via));
        } else {
            JarPort.why(key, label + "0 this injector passes the call's arguments on");
            return;
        }
        mod.retargets.put(key + " " + point[1], at.changed());
    }

    void retargetOther(JarPort.Mod mod, String key, List<String> targets, GameDiff.RawMeth handler, Injector inj) {
        List<MixinMembers.Found> into = inj.methods().stream().map(s -> port.mixinMembers.find(targets, "method", s)).toList();
        List<MixinMembers.Found> points = inj.ats().stream().map(at -> at[1] == null ? null : port.mixinMembers.find(targets, "target", at[1])).toList();
        into = Objects.requireNonNullElse(port.mixinMovedCode.deeper(targets, inj, into, points, cancels(mod, key, handler, inj)), into);
        if (into.stream().noneMatch(f -> f.kind() == 's')) {
            if (points.stream().anyMatch(at -> at != null && at.kind() == 's')) {
                followCall(mod, key, targets, handler, inj, into, points);
            }
            { JarPort.why(key, "O1"); return; }
        }
        Type[] hp = Type.getArgumentTypes(port.desc(handler.desc));
        for (int i = 0; i < into.size(); i++) {
            MixinMembers.Found f = into.get(i);
            if (f.kind() != 's') {
                continue;
            }
            GameDiff.Meth before = port.mixinMembers.oldMethod(targets, inj.methods().get(i));
            GameDiff.Meth after = f.desc() == null ? null : port.b.method(f.owner(), f.name(), f.desc());
            if (before == null || after == null || f.changed() == null || !Type.getReturnType(port.desc(before.desc)).equals(Type.getReturnType(after.desc))
                || ((before.flags ^ after.flags) & Opcodes.ACC_STATIC) != 0 && !staticizes(mod, key, handler, before, after)) {
                { JarPort.why(key, before == null ? "O2-old" : after == null ? "O2-new" : f.changed() == null ? "O2-changed" : ((before.flags ^ after.flags) & Opcodes.ACC_STATIC) != 0 ? "O2-static" : "O2-result"); return; }
            }
            Type[] bp = Type.getArgumentTypes(port.desc(before.desc)), ap = Type.getArgumentTypes(after.desc);
            // A @ModifyVariable without an index finds its variable by type and ordinal, which other parameters' sizes do
            // not move; only one whose type the update added to or took from the parameters may find another one.
            Type variable = Type.getReturnType(port.desc(handler.desc));
            boolean byType = inj.ordinal()[1] < 0 && Arrays.stream(bp).filter(variable::equals).count() == Arrays.stream(ap).filter(variable::equals).count();
            if (inj.kind().endsWith("/ModifyVariable;") && !byType && !Arrays.equals(Arrays.stream(bp).mapToInt(Type::getSize).toArray(), Arrays.stream(ap).mapToInt(Type::getSize).toArray())) {
                { JarPort.why(key, "O3 a local named by its slot would be another one"); return; } // a local named by its slot would be another one
            }
            for (int n = Math.min(hp.length, bp.length); n >= 1; n--) {
                if (Arrays.equals(Arrays.copyOfRange(hp, hp.length - n, hp.length), Arrays.copyOf(bp, n))) {
                    if (ap.length < n || !Arrays.equals(Arrays.copyOf(ap, n), Arrays.copyOf(bp, n))) {
                        { JarPort.why(key, "O4 it captures an argument that changed"); return; } // it captures an argument that changed
                    }
                    break;
                }
            }
        }
        List<MixinMembers.Found> ats = inj.ats().stream().map(at -> at[1] == null ? null : port.mixinMembers.find(targets, "target", at[1])).toList();
        if (!port.mixinPoints.pointsKept(targets, inj, into, ats)) {
            { JarPort.why(key, "O5"); return; }
        }
        for (int i = 0; i < into.size(); i++) {
            if (into.get(i).kind() == 's') {
                mod.retargets.put(key + " " + inj.methods().get(i), into.get(i).changed());
            }
        }
    }

    /**
     * The field a game method (old release) stored its parameter i in on its own object (this.minecraft = minecraft, as
     * 1.21.10's Screen.init did), as a step for what this answers, when the new release's target class still has that
     * field with that type, open to it, and the method stored nothing else there: the object kept what it was told, so a
     * handler that took the parameter reads the field where the new method no longer passes it. Only where every new
     * constructor stores it (1.21.11's Screen(Component) hands over to Screen(Minecraft, Font, Component)) and no other
     * method does. Null otherwise.
     */
    String kept(GameDiff.Meth before, int i, String target) {
        GameCode oldCode = port.code(port.a);
        Map<String, String> bodies = oldCode == null || before.isStatic() ? null : oldCode.bodies(before.owner.name, port::cls);
        String body = bodies == null ? null : bodies.get(before.name + port.desc(before.desc));
        if (body == null) {
            return null;
        }
        Type[] params = Type.getArgumentTypes(port.desc(before.desc));
        int slot = 1;
        for (int k = 0; k < i; k++) {
            slot += params[k].getSize();
        }
        String put = "181:([^\\n]+)\\.(\\w+)" + Pattern.quote(params[i].getDescriptor());
        Matcher stored = Pattern.compile("(?m)^25:0\\n" + params[i].getOpcode(Opcodes.ILOAD) + ":" + slot + "\\n" + put + "$").matcher(body);
        if (!stored.find()) {
            return null;
        }
        String owner = stored.group(1), field = stored.group(2), store = "181:" + owner + "." + field + params[i].getDescriptor();
        if (Arrays.stream(GameCode.instructions(body)).filter(store::equals).count() != 1) {
            return null;
        }
        GameDiff.Cls decl = Exposure.declaring(port.b, owner, 'f', field, null);
        // The new release must set it before any method runs on every object: no other method stores it, and each
        // constructor stores it (not a null) or hands over to another constructor of the class that does, so it holds
        // the value at the new method's very start too.
        Map<String, String> now = decl == null || port.code(port.b) == null ? null : port.code(port.b).bodies(decl.name, x -> x);
        String own = "181:" + (decl == null ? "" : decl.name) + "." + field + params[i].getDescriptor();
        if (now == null || now.keySet().stream().noneMatch(k -> k.startsWith("<init>"))) {
            return null;
        }
        for (var e : now.entrySet()) {
            List<String> code = Arrays.asList(GameCode.instructions(e.getValue()));
            int at = code.indexOf(own);
            boolean init = e.getKey().startsWith("<init>");
            boolean delegates = code.stream().anyMatch(x -> x.startsWith("183:" + decl.name + ".<init>("));
            if (!init && at >= 0 || init && !delegates && (at < 0 || at > 0 && code.get(at - 1).equals(String.valueOf(Opcodes.ACONST_NULL)))) {
                return null;
            }
        }
        String step = "f:" + owner + "." + field + ":" + params[i].getDescriptor();
        return decl != null && decl.fields.get(field).desc.equals(params[i].getDescriptor()) && Linking.subtype(port.b, target, owner)
            && openTo(new String[][] { { String.valueOf(THIS), step } }, target) ? step : null;
    }

    /**
     * Whether every member the readings of what this answers (THIS) go through is open to code in the class target (new
     * names): public, declared by target, or protected and declared by one of its superclasses.
     */
    boolean openTo(String[][] via, String target) {
        for (String[] v : via == null ? new String[0][] : via) {
            if (v == null || Integer.parseInt(v[0]) != THIS) {
                continue;
            }
            for (int i = 1; i < v.length; i++) {
                if (v[i].startsWith("x:") || v[i].startsWith("k:") || v[i].startsWith("o:") || v[i].startsWith("s:")) {
                    continue;
                }
                String[] st = Retype.step(v[i]);
                GameDiff.Cls decl = Exposure.declaring(port.b, st[1], st[0].charAt(0), st[2], st[0].equals("m") ? st[3] : null);
                int flags = decl == null ? -1 : st[0].equals("m") ? decl.methods.get(st[2] + st[3]).flags : decl.fields.get(st[2]).flags;
                boolean open = flags >= 0 && ((flags & Opcodes.ACC_PUBLIC) != 0 || decl.name.equals(target)
                    || (flags & Opcodes.ACC_PROTECTED) != 0 && Linking.subtype(port.b, target, decl.name));
                if (!open) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Whether a handler may cancel the method it injects into: a cancellable @Inject, or a CallbackInfo parameter marked
     * with MixinExtras' @Cancellable. Moved into a helper (deeper), its cancel would end only the helper, and the method
     * the mod meant to stop would run on.
     */
    boolean cancels(JarPort.Mod mod, String key, GameDiff.RawMeth handler, Injector inj) {
        if (inj.cancellable()[0]) {
            return true;
        }
        byte[] bytes = mod.bytes.get(key.substring(0, key.indexOf('.')));
        boolean[] marked = new boolean[1];
        if (bytes != null) {
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    return !name.equals(handler.name) || !desc.equals(handler.desc) ? null : new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitParameterAnnotation(int parameter, String d, boolean visible) {
                            marked[0] |= d.equals(SUGAR + "Cancellable;");
                            return null;
                        }
                    };
                }
            }, ClassReader.SKIP_CODE);
        }
        return marked[0];
    }

    /** The first CallbackInfo parameter of a handler, or -1. */
    static int callbackAt(Type[] params) {
        for (int i = 0; i < params.length; i++) {
            if (params[i].getSort() == Type.OBJECT && CALLBACKS.contains(params[i].getInternalName())) {
                return i;
            }
        }
        return -1;
    }

    static final String SUGAR = "Lcom/llamalad7/mixinextras/sugar/";

    /**
     * Whether every parameter of a handler after its CallbackInfo (at ci) is MixinExtras sugar that still finds what it
     * names in the target's new signature: @Share and @Cancellable name nothing of it; a @Local of a type that is one of
     * the target's parameters, once in the old signature and once in the new one, and of no local the new method stores,
     * took that parameter (or says argsOnly).
     */
    boolean sugarFollows(JarPort.Mod mod, String key, GameDiff.RawMeth handler, int ci, MixinMembers.Found into, Injector inj, List<MixinMembers.Found> ats) {
        String owner = key.substring(0, key.indexOf('.'));
        byte[] bytes = mod.bytes.get(owner);
        GameDiff.Meth before = port.mixinMembers.oldMethod(mod.mixinTargets.getOrDefault(owner, List.of()), inj.methods().get(0));
        GameDiff.Meth after = into.desc() == null ? null : port.b.method(into.owner(), into.name(), into.desc());
        if (bytes == null || before == null || after == null) {
            return false;
        }
        Type[] params = Type.getArgumentTypes(handler.desc);
        String[] sugar = new String[params.length];
        boolean[] argsOnly = new boolean[params.length];
        boolean[] picked = new boolean[params.length];
        int[] ordinals = new int[params.length];
        Arrays.fill(ordinals, -1);
        boolean[] indexed = new boolean[params.length];
        String[] generic = new String[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (!name.equals(handler.name) || !desc.equals(handler.desc)) {
                    return null;
                }
                generic[0] = signature;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitParameterAnnotation(int parameter, String d, boolean visible) {
                        if (parameter < sugar.length && d.startsWith(SUGAR)) {
                            sugar[parameter] = d;
                        }
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String n, Object value) {
                                if (parameter < sugar.length && n.equals("argsOnly")) {
                                    argsOnly[parameter] = Boolean.TRUE.equals(value);
                                } else if (parameter < sugar.length && (n.equals("ordinal") || n.equals("index"))) {
                                    picked[parameter] = true;
                                    if (n.equals("index")) {
                                        indexed[parameter] = true;
                                    } else if (value instanceof Integer o) {
                                        ordinals[parameter] = o;
                                    }
                                }
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE);
        List<Type> was = Arrays.stream(Type.getArgumentTypes(port.desc(before.desc))).toList(), now = Arrays.asList(Type.getArgumentTypes(after.desc));
        GameCode code = port.code(port.b);
        Set<Integer> stores = code == null ? null : code.localStores(into.owner(), into.name(), into.desc());
        for (int i = ci + 1; i < params.length; i++) {
            if (sugar[i] == null) {
                return false;
            }
            if (sugar[i].equals(SUGAR + "Share;") || sugar[i].equals(SUGAR + "Cancellable;")) {
                continue;
            }
            // A LocalRef stands for a local of the type it holds, which a reference to it reads and writes.
            String held = params[i].getSort() == Type.OBJECT ? localRef(params[i].getInternalName(), generic[0], i) : null;
            Type t = Type.getType(port.desc(held != null ? held : params[i].getDescriptor()));
            if (!sugar[i].equals(SUGAR + "Local;") || argsOnly[i] && (picked[i] || Collections.frequency(now, t) != 1)
                || held == null && params[i].getSort() == Type.OBJECT && params[i].getInternalName().startsWith(SUGAR.substring(1) + "ref/")) {
                return false;
            }
            if (argsOnly[i]) {
                continue;
            }
            // Without argsOnly a @Local also sees the method's own locals, so one the new method stores of that kind
            // could make the capture ambiguous (reference locals are not told apart by type here).
            boolean param = !picked[i] && Collections.frequency(was, t) == 1 && Collections.frequency(now, t) == 1
                && stores != null && !stores.contains(t.getOpcode(Opcodes.ISTORE));
            if (!param && !localFound(key, before, after, inj, ats, t, ordinals[i], indexed[i])) {
                return false;
            }
        }
        return true;
    }

    static final Map<String, String> PRIMITIVE_REFS = Map.of("LocalBooleanRef", "Z", "LocalByteRef", "B", "LocalCharRef", "C", "LocalShortRef", "S",
        "LocalIntRef", "I", "LocalLongRef", "J", "LocalFloatRef", "F", "LocalDoubleRef", "D");

    /**
     * The descriptor of the local a MixinExtras LocalRef parameter (the i-th of a handler) stands for: its primitive kind
     * (LocalIntRef), or the type argument of LocalRef in the handler's generic signature. Null for any other type, or a
     * LocalRef whose type cannot be read.
     */
    static String localRef(String internal, String signature, int param) {
        String prefix = SUGAR.substring(1) + "ref/";
        if (!internal.startsWith(prefix)) {
            return null;
        }
        String kind = internal.substring(prefix.length());
        if (PRIMITIVE_REFS.containsKey(kind)) {
            return PRIMITIVE_REFS.get(kind);
        }
        if (!kind.equals("LocalRef") || signature == null) {
            return null;
        }
        String[] found = new String[1];
        int[] at = { -1 };
        new org.objectweb.asm.signature.SignatureReader(signature).accept(new org.objectweb.asm.signature.SignatureVisitor(Opcodes.ASM9) {
            int depth; // 0: a parameter's own type, 1: its type argument

            @Override
            public org.objectweb.asm.signature.SignatureVisitor visitParameterType() {
                at[0]++;
                depth = 0;
                return this;
            }

            @Override
            public org.objectweb.asm.signature.SignatureVisitor visitReturnType() {
                at[0] = Integer.MIN_VALUE;
                return this;
            }

            @Override
            public void visitClassType(String name) {
                if (at[0] == param && depth == 1 && found[0] == null) {
                    found[0] = "L" + name + ";";
                }
            }

            @Override
            public org.objectweb.asm.signature.SignatureVisitor visitTypeArgument(char wildcard) {
                depth++;
                return this;
            }

            @Override
            public void visitEnd() {
                depth = Math.max(0, depth - 1);
            }
        });
        return found[0];
    }

    /**
     * Whether a @Local of type t (without argsOnly) finds the same local in a target's new method as in its old one, at
     * every place each @At finds: the locals there are the method's arguments, then the live locals the game's own frames
     * give (GameCode.localsAt), as Mixin reads them. Without an ordinal the one local of that type, in both methods; with
     * one, the arguments of that type are as many in both and so are the other locals, so the nth is the same one. A
     * @Local by slot index, or of a type Mixin's analysis does not tell apart (an int-like type, a LocalRef), is not
     * followed.
     */
    boolean localFound(String key, GameDiff.Meth before, GameDiff.Meth after, Injector inj, List<MixinMembers.Found> ats, Type t, int ordinal, boolean indexed) {
        GameCode oldCode = port.code(port.a), newCode = port.code(port.b);
        // An int-like local reads as I where the method has no local variable table, so a boolean one is found only where
        // the table says boolean, as Mixin's reading would.
        if (indexed || oldCode == null || newCode == null) {
            JarPort.why(key, "S1 a @Local by index: " + t);
            return false;
        }
        String d = t.getDescriptor();
        long argsWas = Arrays.stream(Type.getArgumentTypes(port.desc(before.desc))).map(Type::getDescriptor).filter(d::equals).count();
        long argsNow = Arrays.stream(Type.getArgumentTypes(after.desc)).map(Type::getDescriptor).filter(d::equals).count();
        for (int i = 0; i < inj.ats().size(); i++) {
            String[] point = inj.ats().get(i);
            String[] was = null, now = null;
            if (point[1] != null) {
                Matcher m = MixinMembers.MEMBER.matcher(point[1]);
                MixinMembers.Found at = ats.get(i);
                if (!m.matches() || m.group(1) == null || at == null || at.owner() == null || at.name() == null) {
                    JarPort.why(key, "S2 an @At without its owner");
                    return false;
                }
                was = new String[] { m.group(1), m.group(2), m.group(3) != null ? m.group(3) : m.group(4) };
                now = new String[] { at.owner(), at.name(), at.desc() };
            }
            List<List<String>> old = oldCode.localsAt(before.owner.name, before.name, before.desc, point[0], was, point[2]);
            List<List<String>> fresh = newCode.localsAt(after.owner.name, after.name, after.desc, point[0], now, point[2]);
            if (old == null || fresh == null || old.isEmpty() || fresh.isEmpty()) {
                JarPort.why(key, "S3 locals not read at " + point[0] + " " + point[1] + (old == null ? " old" : fresh == null ? " new" : " none"));
                return false;
            }
            Set<Long> counts = new HashSet<>();
            for (List<String> l : old) {
                counts.add(l.stream().map(port::desc).filter(d::equals).count());
            }
            Set<Long> countsNow = new HashSet<>();
            for (List<String> l : fresh) {
                countsNow.add(l.stream().filter(d::equals).count());
            }
            boolean same = counts.size() == 1 && counts.equals(countsNow);
            long locals = same ? counts.iterator().next() : -1;
            if (ordinal < 0 ? !same || argsWas + locals != 1 || argsNow != argsWas : !same || argsNow != argsWas || argsWas + locals <= ordinal) {
                JarPort.why(key, "S4 the @Local's " + d + " is not the same local: arguments " + argsWas + " -> " + argsNow + ", locals " + counts + " -> " + countsNow);
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the locals a handler captures after its CallbackInfo (at ci; LocalCapture) are the ones Mixin hands it in
     * the target's new method: at every place each @At finds in the old method they are the first locals there (so the
     * reading is Mixin's), and at every place in the new method the first locals have the same types (GameCode.localsAt,
     * from the game's own frames). The handler must take all of the target's old arguments, as Mixin asks of one that
     * captures locals.
     */
    boolean localsFollow(JarPort.Mod mod, String key, GameDiff.RawMeth handler, int ci, MixinMembers.Found into, Injector inj, List<MixinMembers.Found> ats) {
        String owner = key.substring(0, key.indexOf('.'));
        GameDiff.Meth before = port.mixinMembers.oldMethod(mod.mixinTargets.getOrDefault(owner, List.of()), inj.methods().get(0));
        GameDiff.Meth after = into.desc() == null ? null : port.b.method(into.owner(), into.name(), into.desc());
        GameCode oldCode = port.code(port.a), newCode = port.code(port.b);
        if (before == null || after == null || oldCode == null || newCode == null || ci != Type.getArgumentTypes(port.desc(before.desc)).length) {
            JarPort.why(key, "L1 not every argument of the target, or a target not read");
            return false;
        }
        Type[] params = Type.getArgumentTypes(handler.desc);
        List<String> wanted = Arrays.stream(params, ci + 1, params.length).map(Type::getDescriptor).toList();
        for (int i = 0; i < inj.ats().size(); i++) {
            String[] point = inj.ats().get(i);
            String[] was = null, now = null;
            if (point[1] != null) {
                Matcher m = MixinMembers.MEMBER.matcher(point[1]);
                MixinMembers.Found at = ats.get(i);
                if (!m.matches() || m.group(1) == null || at == null || at.owner() == null || at.name() == null) {
                    JarPort.why(key, "L2 an @At without its owner");
                    return false;
                }
                was = new String[] { m.group(1), m.group(2), m.group(3) != null ? m.group(3) : m.group(4) };
                now = new String[] { at.owner(), at.name(), at.desc() };
            }
            List<List<String>> old = oldCode.localsAt(before.owner.name, before.name, before.desc, point[0], was, point[2]);
            List<List<String>> fresh = newCode.localsAt(after.owner.name, after.name, after.desc, point[0], now, point[2]);
            if (old == null || fresh == null || old.isEmpty() || fresh.isEmpty()) {
                JarPort.why(key, "L3 locals not read at " + point[0] + " " + point[1] + (old == null ? " old" : fresh == null ? " new" : " none"));
                return false;
            }
            for (List<String> l : old) {
                if (l.size() < wanted.size() || !intLike(l.subList(0, wanted.size()), wanted)) {
                    JarPort.why(key, "L4 the old method's locals are not the handler's " + l);
                    return false;
                }
            }
            List<String> mapped = old.get(0).subList(0, wanted.size()).stream().map(port::desc).toList();
            for (List<String> l : fresh) {
                if (l.size() < wanted.size() || !l.subList(0, wanted.size()).equals(mapped)) {
                    JarPort.why(key, "L5 the new method's locals differ " + l + " for " + mapped);
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether locals as Mixin's analysis reads them (an int-like local as I) are the handler's types. */
    static boolean intLike(List<String> read, List<String> handler) {
        for (int i = 0; i < read.size(); i++) {
            String r = read.get(i), h = handler.get(i);
            if (!r.equals(h) && !(r.equals("I") && Set.of("Z", "B", "C", "S").contains(h))) {
                return false;
            }
        }
        return true;
    }

    void retarget(JarPort.Mod mod, String key, List<String> targets, GameDiff.RawMeth handler, Injector inj) {
        List<MixinMembers.Found> into = inj.methods().stream().map(s -> port.mixinMembers.find(targets, "method", s)).toList();
        // An @Inject takes only a place, not a call's arguments: an @At at a call the game replaced finds its place at the
        // replacement (font.draw(pose, ...) is graphics.drawString(font, ...) in the new method).
        List<MixinMembers.Found> ats = inj.ats().stream().map(at -> at[1] == null ? null : replacedAt(at, port.mixinMembers.find(targets, "target", at[1]))).toList();
        into = Objects.requireNonNullElse(port.mixinMovedCode.deeper(targets, inj, into, ats, cancels(mod, key, handler, inj)), into);
        boolean moving = into.stream().anyMatch(f -> f.kind() == 's');
        if (moving) {
            Type[] params = Type.getArgumentTypes(handler.desc);
            int captured = params.length - 1;
            // MixinExtras' sugar after the CallbackInfo (@Local, @Share, @Cancellable) is resolved by Mixin, not taken from
            // the target's arguments: a handler with only that after its CallbackInfo follows as one without it does.
            int ci = callbackAt(params);
            if (ci >= 0 && ci < captured && into.size() == 1 && sugarFollows(mod, key, handler, ci, into.get(0), inj, ats)) {
                captured = ci;
            } else if (ci >= 0 && ci < captured && into.size() == 1 && localsFollow(mod, key, handler, ci, into.get(0), inj, ats)) {
                // Locals captured after the CallbackInfo (LocalCapture) are the ones Mixin hands the handler in the new
                // method too: they pass through as they are, and only the target's arguments change.
                captured = ci;
            }
            if (captured < 0 || params[captured].getSort() != Type.OBJECT || !CALLBACKS.contains(params[captured].getInternalName())) {
                { JarPort.why(key, "I6 locals captured after the CallbackInfo, or no CallbackInfo at all"); return; } // locals captured after the CallbackInfo, or no CallbackInfo at all
            }
            if (captured > 0 && into.size() != 1) {
                { JarPort.why(key, "I7"); return; }
            }
            for (int i = 0; i < into.size(); i++) {
                MixinMembers.Found f = into.get(i);
                if (f.kind() == 's') {
                    String old = inj.methods().get(i);
                    GameDiff.Meth before = port.mixinMembers.oldMethod(targets, old);
                    GameDiff.Meth after = f.desc() == null ? null : port.b.method(f.owner(), f.name(), f.desc());
                    if (before == null || after == null || !Type.getReturnType(port.desc(before.desc)).equals(Type.getReturnType(after.desc))
                        || ((before.flags ^ after.flags) & Opcodes.ACC_STATIC) != 0 && !staticizes(mod, key, handler, before, after)) {
                        { JarPort.why(key, before == null ? "I8-old" : after == null ? "I8-new" : ((before.flags ^ after.flags) & Opcodes.ACC_STATIC) != 0 ? "I8-static" : "I8-result"); return; }
                    }
                }
            }
            if (!port.mixinPoints.pointsKept(targets, inj, into, ats)) {
                { JarPort.why(key, "I9"); return; }
            }
            MixinMembers.Found f = into.get(0);
            if (captured > 0) {
                GameDiff.Meth before = port.mixinMembers.oldMethod(targets, inj.methods().get(0));
                Type[] now = Type.getArgumentTypes(f.desc());
                Type[] was = Arrays.copyOf(Type.getArgumentTypes(port.desc(handler.desc)), captured);
                if (!Arrays.equals(was, Arrays.copyOf(Type.getArgumentTypes(port.desc(before.desc)), captured))) {
                    { JarPort.why(key, "I10 not the target's own arguments"); return; } // not the target's own arguments
                }
                boolean[] unread = port.overrides.unread(mod, key.substring(0, key.indexOf('.')), handler.name, handler.desc, (handler.flags & Opcodes.ACC_STATIC) != 0, captured);
                int[] from = port.overrides.convert(now, was, unread);
                String[][] via = new String[captured][];
                if (from == null) {
                    // Arguments that changed type: worked out from the new ones as the game's own overrides do (Retype).
                    int[] paired = Overrides.pair(now, was);
                    String[][] read = port.retype.conversions(before.owner.name, before.name, before.desc, new String[] { f.owner(), f.name(), f.desc() }, paired);
                    // A handler merged into its target runs on the target object, so what that object answers (Screen.init's
                    // Minecraft, now this.minecraft) is read there, where the members read are open to the target's code.
                    String[][] learned = (handler.flags & Opcodes.ACC_STATIC) == 0 && !mod.staticized.contains(key) && openTo(read, f.owner()) ? read : Retype.withoutThis(read);
                    from = new int[captured];
                    for (int i = 0; i < captured; i++) {
                        if (paired[i] >= 0) {
                            from[i] = paired[i];
                        } else if (learned != null && learned[i] != null) {
                            from[i] = Integer.parseInt(learned[i][0]);
                            via[i] = Arrays.copyOfRange(learned[i], 1, learned[i].length);
                        } else if (unread != null && unread[i]) {
                            from[i] = -1;
                        } else if ((handler.flags & Opcodes.ACC_STATIC) == 0 && !mod.staticized.contains(key) && kept(before, i, f.owner()) != null) {
                            from[i] = THIS;
                            via[i] = new String[] { kept(before, i, f.owner()) };
                        } else {
                            { JarPort.why(key, "I11"); return; }
                        }
                    }
                }
                // The CallbackInfo and what follows it (sugar, captured locals) pass through as they are.
                int rest = params.length - captured;
                Type[] desc = Arrays.copyOf(now, now.length + rest);
                for (int i = 0; i < rest; i++) {
                    desc[now.length + i] = Type.getType(port.desc(params[captured + i].getDescriptor()));
                }
                if (Arrays.stream(via).anyMatch(Objects::nonNull) && (mod.bytes.get(key.substring(0, key.indexOf('.'))) != null)
                    && (new ClassReader(mod.bytes.get(key.substring(0, key.indexOf('.')))).readShort(6) & 0xFFFF) >= Opcodes.V1_6) {
                    // An argument that changed type: the handler's body moves whole to the new parameters, as an override's
                    // does, so the calls it makes on that argument get what the game passes now (Sites.given, onOrigin).
                    int[] all = Arrays.copyOf(from, captured + rest);
                    for (int i = 0; i < rest; i++) {
                        all[captured + i] = now.length + i;
                    }
                    mod.bridges.computeIfAbsent(key.substring(0, key.indexOf('.')), k -> new ArrayList<>())
                        .add(new Overrides.Bridge(handler.name, Type.getMethodDescriptor(Type.VOID_TYPE, desc), handler.name, handler.desc, all, handler.flags, Arrays.copyOf(via, captured + rest)));
                } else {
                    mod.adapters.put(key, new Adapter(Type.getMethodDescriptor(Type.VOID_TYPE, desc), from, captured, via));
                }
            }
            for (int i = 0; i < into.size(); i++) {
                if (into.get(i).kind() == 's') {
                    mod.retargets.put(key + " " + inj.methods().get(i), into.get(i).changed());
                }
            }
        }
        // @At targets that only took other parameters, in methods that are themselves known in the new release.
        List<GameDiff.Meth[]> methods = port.mixinPoints.enclosing(targets, inj, into);
        for (int i = 0; i < ats.size(); i++) {
            MixinMembers.Found at = ats.get(i);
            int k = i;
            if (at != null && (at.kind() == 's' || at.kind() == 'r') && at.desc() != null && methods != null && methods.stream().allMatch(m -> port.mixinPoints.pointKept(inj.ats().get(k), at, m))) {
                mod.retargets.put(key + " " + inj.ats().get(i)[1], at.changed());
            }
        }
    }

    /**
     * An @At at a call the new release no longer has (found names it gone, or gives no new descriptor), as the call the
     * game's own code writes in its place: the route map's rewrite (GuiComponent.fill(pose, ...) -> graphics.fill(...)),
     * or the outermost call of what the game computes there on a retyped parameter (GameDiff.Retyped). Kind 'r', the
     * replacement's text in changed. Anything else is found as it was.
     */
    MixinMembers.Found replacedAt(String[] point, MixinMembers.Found found) {
        if (!Set.of("INVOKE", "INVOKE_ASSIGN").contains(point[0]) || found == null || found.kind() != 0 && found.desc() != null) {
            return found;
        }
        Matcher m = MixinMembers.MEMBER.matcher(point[1]);
        if (!m.matches() || m.group(1) == null || m.group(3) == null) {
            return found;
        }
        String owner = m.group(1), name = m.group(2), desc = m.group(3);
        JarPort.Decl decl = port.links.declaringOld(new JarPort.Mod(), owner, 'm', name, desc);
        String declOwner = decl == null ? owner : decl.owner();
        List<String[]> calls = new ArrayList<>();
        for (JarPort.Call c : port.rewrites.getOrDefault(declOwner + "." + name + desc, List.of())) {
            calls.add(new String[] { c.owner(), c.name(), c.desc() });
        }
        for (JarPort.RetypedCall c : port.retypedCalls.getOrDefault(declOwner, List.of())) {
            Matcher outer = Pattern.compile("^\\$(\\d+)\\.(\\w+)\\((.*)\\)$").matcher(c.expr());
            if (!c.name().equals(name) || !c.desc().equals(desc) || !outer.matches() || Integer.parseInt(outer.group(1)) != c.at()) {
                continue;
            }
            String as = Type.getType(c.as()).getInternalName();
            int arity = arguments(outer.group(3));
            List<GameDiff.Meth> fits = MixinMembers.named(port.b, as, outer.group(2)).stream().filter(x -> Type.getArgumentTypes(x.desc).length == arity && !x.isStatic()).toList();
            if (fits.size() == 1) {
                calls.add(new String[] { as, fits.get(0).name, fits.get(0).desc });
            }
        }
        if (calls.size() != 1) {
            return found;
        }
        String[] c = calls.get(0);
        return new MixinMembers.Found(point[1], null, null, 'r', c[0], c[1], c[2], "L" + c[0] + ";" + c[1] + c[2]);
    }

    /** How many arguments a call's argument text has, counting commas outside brackets and quotes. */
    static int arguments(String text) {
        if (text.isBlank()) {
            return 0;
        }
        int depth = 0, count = 1;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
                quoted = !quoted;
            } else if (!quoted && (ch == '(' || ch == '[' || ch == '{')) {
                depth++;
            } else if (!quoted && (ch == ')' || ch == ']' || ch == '}')) {
                depth--;
            } else if (!quoted && ch == ',' && depth == 0) {
                count++;
            }
        }
        return count;
    }

    /** Whether the new release still has owner.name (old names) as the old release declared it: then a use links and needs no answer. */
    boolean kept(String owner, boolean field, String name, String desc) {
        String now = port.cls(owner);
        GameDiff.Cls c = Exposure.declaring(port.b, now, field ? 'f' : 'm', name, field ? null : port.desc(desc));
        return c != null && (field ? c.fields.get(name) != null && c.fields.get(name).desc.equals(port.desc(desc)) : c.methods.containsKey(name + port.desc(desc)));
    }
}

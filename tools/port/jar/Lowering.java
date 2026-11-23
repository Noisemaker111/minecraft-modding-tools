import java.util.*;
import org.objectweb.asm.*;

/**
 * One rule's replacement compiled for one site, the way javac would compile it against the new release: names are
 * resolved there (or in the JDK), overloads chosen by the operands' types, and every member must be one the caller may
 * use. Anything else is Refused. Code is written in the old release's names; the class renamer runs after.
 */
final class Lowering {
    final JarPort port;

    final JarPort.Mod mod;
    final List<String> callers;
    final Type[] params;
    final boolean receiver;
    final int[] slots;
    int locals;

    /**
     * params: the old member's operands in new names, held in locals from first on; with receiver the first is $0,
     * else $1 is the first.
     */
    Lowering(JarPort port, JarPort.Mod mod, List<String> callers, Type[] params, boolean receiver, int first) {
        this.port = port;
        this.mod = mod;
        this.callers = callers;
        this.params = params;
        this.receiver = receiver;
        slots = new int[params.length];
        locals = first;
        for (int i = 0; i < params.length; i++) {
            slots[i] = locals;
            locals += params[i].getSize();
        }
    }

    Type lower(FixExpr.Node n, RuleCode.Code out) {
        return switch (n) {
            case FixExpr.Arg a -> arg(a.index(), out);
            case FixExpr.Lit l -> literal(l, out);
            case FixExpr.Path p -> path(p.dotted(), out);
            case FixExpr.Get g -> field(lower(g.target(), out), g.name(), false, out);
            case FixExpr.Invoke i -> invoke(i, out);
            case FixExpr.New w -> construct(w, out);
            case FixExpr.Cast c -> cast(c, out);
            case FixExpr.Ref r -> throw new RuleCode.Refused("a method reference is taken only as an argument");
            case FixExpr.Sum a -> sum(a, out);
        };
    }

    Type sum(FixExpr.Sum a, RuleCode.Code out) {
        Type l = lower(a.left(), out);
        boolean wide = l.getSort() == Type.LONG;
        if (!wide && (l.getSort() < Type.CHAR || l.getSort() > Type.INT)) {
            throw new RuleCode.Refused("cannot add " + l);
        }
        Type r = lower(a.right(), out);
        if (r.getSort() == Type.LONG != wide || !wide && (r.getSort() < Type.CHAR || r.getSort() > Type.INT)) {
            throw new RuleCode.Refused("cannot add " + l + " and " + r);
        }
        int op = a.op() == '+' ? (wide ? Opcodes.LADD : Opcodes.IADD) : (wide ? Opcodes.LSUB : Opcodes.ISUB);
        out.add(0, mv -> mv.visitInsn(op));
        return wide ? Type.LONG_TYPE : Type.INT_TYPE;
    }

    Type arg(int index, RuleCode.Code out) {
        int k = receiver ? index : index - 1;
        if (k < 0 || k >= params.length) {
            throw new RuleCode.Refused("$" + index + " is not an operand of the old member");
        }
        Type t = params[k];
        int slot = slots[k];
        out.add(t.getSize(), mv -> mv.visitVarInsn(t.getOpcode(Opcodes.ILOAD), slot));
        return t;
    }

    Type literal(FixExpr.Lit l, RuleCode.Code out) {
        if (l.type() == null) {
            out.add(1, mv -> mv.visitInsn(Opcodes.ACONST_NULL));
            return RuleCode.NULL;
        }
        Type t = Type.getType(l.type());
        Object v = l.value();
        if (v instanceof Boolean z) {
            out.add(1, mv -> mv.visitInsn(z ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        } else {
            out.add(t.getSize(), mv -> mv.visitLdcInsn(v));
        }
        return t;
    }

    boolean known(String internal) {
        return port.b.classes.containsKey(internal) || Linking.jdk(internal) != null;
    }

    String type(String dotted) {
        String internal = dotted.replace('.', '/');
        if (!known(internal)) {
            throw new RuleCode.Refused(dotted + " is not a class of the new release");
        }
        return internal;
    }

    /** a.b.C.F.G: the longest start that is a class, then its static field F and G of that. */
    Type path(String dotted, RuleCode.Code out) {
        String[] parts = dotted.split("\\.");
        for (int i = parts.length - 1; i >= 1; i--) {
            String owner = String.join("/", Arrays.copyOf(parts, i));
            if (!known(owner)) {
                continue;
            }
            Type t = field(Type.getObjectType(owner), parts[i], true, out);
            for (int k = i + 1; k < parts.length; k++) {
                t = field(t, parts[k], false, out);
            }
            return t;
        }
        throw new RuleCode.Refused(dotted + " is not a field of the new release");
    }

    Type field(Type owner, String name, boolean isStatic, RuleCode.Code out) {
        if (owner.getSort() != Type.OBJECT || owner == RuleCode.NULL) {
            throw new RuleCode.Refused("no field " + name + " on " + owner);
        }
        String o = owner.getInternalName();
        String desc;
        Class<?> k = Linking.jdk(o);
        if (k != null) {
            java.lang.reflect.Field f;
            try {
                f = k.getField(name);
            } catch (NoSuchFieldException absent) {
                throw new RuleCode.Refused("no field " + name + " on " + o);
            }
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) != isStatic) {
                throw new RuleCode.Refused(name + " is not a " + (isStatic ? "static" : "instance") + " field");
            }
            desc = Type.getDescriptor(f.getType());
        } else {
            Linking.FieldHit f = port.links.fieldNew(mod, callers, o, name);
            GameDiff.Cls holder = Exposure.declaring(port.b, o, 'f', name, null);
            // A closed field the game's own code reads there (this.font in its screens) is opened when the loader lets the mod.
            if (f == null || !f.open() && !port.links.opened(mod, callers, o, 'f', name, null) || holder == null
                || ((holder.fields.get(name).flags & Opcodes.ACC_STATIC) != 0) != isStatic) {
                throw new RuleCode.Refused("no field " + name + " the caller may read on " + o);
            }
            desc = f.desc();
        }
        Type t = Type.getType(desc);
        String ownerOld = port.inverse.getOrDefault(o, o);
        String descOld = port.oldDesc(desc);
        out.add(t.getSize() - (isStatic ? 0 : 1), mv -> mv.visitFieldInsn(isStatic ? Opcodes.GETSTATIC : Opcodes.GETFIELD, ownerOld, name, descOld));
        return t;
    }

    /** Every method named name that a call on owner reaches, most derived first; static ones are named on their own class. */
    List<Linking.Member> methods(String owner, String name) {
        List<Linking.Member> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean itf = port.links.isInterface(mod, owner);
        boolean open = port.links.classOpen(mod, callers, owner);
        Deque<String> queue = new ArrayDeque<>(List.of(owner));
        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            String c = queue.poll();
            if (!visited.add(c)) {
                continue;
            }
            GameDiff.RawCls own = mod.classes.get(c);
            GameDiff.Cls game = own == null ? port.b.classes.get(c) : null;
            Class<?> k = own == null && game == null ? Linking.jdk(c) : null;
            if (game != null) {
                for (GameDiff.Meth m : game.methods.values()) {
                    if (m.name.equals(name) && seen.add(m.desc)) {
                        boolean st = (m.flags & Opcodes.ACC_STATIC) != 0;
                        boolean usable = open && port.links.memberOpen(mod, callers, c, m.name + m.desc, m.flags) && (m.flags & Opcodes.ACC_SYNTHETIC) == 0;
                        out.add(new Linking.Member(st ? c : owner, name, m.desc, st, st ? port.links.isInterface(mod, c) : itf, usable));
                    }
                }
            } else if (k != null) {
                boolean pub = java.lang.reflect.Modifier.isPublic(k.getModifiers());
                if (name.equals("<init>")) {
                    for (var ctor : k.getConstructors()) {
                        if (seen.add(Type.getConstructorDescriptor(ctor))) {
                            out.add(new Linking.Member(c, name, Type.getConstructorDescriptor(ctor), false, false, pub));
                        }
                    }
                }
                for (var m : k.getMethods()) {
                    String d = Type.getMethodDescriptor(m);
                    if (m.getName().equals(name) && !m.isBridge() && seen.add(d)) {
                        boolean st = java.lang.reflect.Modifier.isStatic(m.getModifiers());
                        String decl = Type.getInternalName(m.getDeclaringClass());
                        out.add(new Linking.Member(st ? decl : owner, name, d, st, st ? m.getDeclaringClass().isInterface() : itf, pub));
                    }
                }
            }
            if (name.equals("<init>")) {
                break;
            }
            String sup = own != null ? (own.sup == null ? null : port.cls(own.sup)) : game != null ? game.sup : null;
            if (sup != null) {
                queue.add(sup);
            }
            if (own != null) {
                own.ifaces.forEach(i -> queue.add(port.cls(i)));
            } else if (game != null) {
                queue.addAll(game.ifaces);
            }
        }
        return out;
    }

    Type invoke(FixExpr.Invoke i, RuleCode.Code out) {
        RuleCode.Code target = new RuleCode.Code();
        String owner;
        boolean isStatic = i.target() instanceof FixExpr.Path p && known(p.dotted().replace('.', '/'));
        if (isStatic) {
            owner = ((FixExpr.Path) i.target()).dotted().replace('.', '/');
        } else {
            Type recv = lower(i.target(), target);
            if (recv.getSort() != Type.OBJECT || recv == RuleCode.NULL) {
                throw new RuleCode.Refused("no method " + i.name() + " on " + recv);
            }
            owner = recv.getInternalName();
        }
        boolean wantStatic = isStatic;
        Linking.Member m = choose(methods(owner, i.name()).stream().filter(x -> x.isStatic() == wantStatic).toList(), i.args(), owner + "." + i.name());
        out.append(target);
        int popped = arguments(m, i.args(), out) + (isStatic ? 0 : 1);
        Type ret = Type.getReturnType(m.desc());
        int op = isStatic ? Opcodes.INVOKESTATIC : m.itf() ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
        out.add(ret.getSize() - popped, mv -> mv.visitMethodInsn(op, port.inverse.getOrDefault(m.owner(), m.owner()), m.name(), port.oldDesc(m.desc()), m.itf()));
        return ret;
    }

    Type construct(FixExpr.New w, RuleCode.Code out) {
        String owner = type(w.type());
        GameDiff.Cls game = port.b.classes.get(owner);
        Class<?> k = Linking.jdk(owner);
        int flags = game != null ? game.flags : k.getModifiers();
        if ((flags & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0) {
            throw new RuleCode.Refused(w.type() + " cannot be made with new");
        }
        Linking.Member m = choose(methods(owner, "<init>"), w.args(), owner + ".<init>");
        String old = port.inverse.getOrDefault(owner, owner);
        out.add(1, mv -> mv.visitTypeInsn(Opcodes.NEW, old));
        out.add(1, mv -> mv.visitInsn(Opcodes.DUP));
        int popped = arguments(m, w.args(), out) + 1;
        out.add(-popped, mv -> mv.visitMethodInsn(Opcodes.INVOKESPECIAL, old, "<init>", port.oldDesc(m.desc()), false));
        return Type.getObjectType(owner);
    }

    /** Writes each argument converted to m's parameter; returns the stack slots they take. */
    int arguments(Linking.Member m, List<FixExpr.Node> args, RuleCode.Code out) {
        Type[] ps = Type.getArgumentTypes(m.desc());
        int slots = 0;
        for (int k = 0; k < ps.length; k++) {
            Type p = ps[k];
            if (args.get(k) instanceof FixExpr.Ref r) {
                Object[] indy = port.links.lambda(mod, callers, type(r.type()), r.name(), p);
                Object[] bsmArgs = (Object[]) indy[3];
                out.add(1, mv -> mv.visitInvokeDynamicInsn((String) indy[0], (String) indy[1], (Handle) indy[2], bsmArgs));
            } else {
                Type got = lower(args.get(k), out);
                widen(got, p, out);
            }
            slots += p.getSize();
        }
        return slots;
    }

    /** The one most specific candidate the arguments fit, as javac picks it (no varargs expansion, no boxing). */
    Linking.Member choose(List<Linking.Member> candidates, List<FixExpr.Node> args, String what) {
        List<Type> types = new ArrayList<>();
        for (FixExpr.Node a : args) {
            types.add(a instanceof FixExpr.Ref ? null : lower(a, new RuleCode.Code()));
        }
        List<Linking.Member> fit = new ArrayList<>();
        for (Linking.Member m : candidates) {
            Type[] ps = Type.getArgumentTypes(m.desc());
            boolean ok = ps.length == args.size();
            for (int k = 0; ok && k < ps.length; k++) {
                ok = types.get(k) == null
                    ? args.get(k) instanceof FixExpr.Ref r && known(r.type().replace('.', '/')) && port.links.lambda(mod, callers, r.type().replace('.', '/'), r.name(), ps[k]) != null
                    : fits(types.get(k), ps[k]);
            }
            if (ok) {
                fit.add(m);
            }
        }
        List<Linking.Member> best = fit.stream().filter(m -> fit.stream().allMatch(o -> o == m || narrower(m, o))).toList();
        if (best.size() != 1) {
            throw new RuleCode.Refused(what + (fit.isEmpty() ? " takes no such arguments" : " is ambiguous for these arguments"));
        }
        if (!best.get(0).open()) {
            throw new RuleCode.Refused(what + " is closed to the caller");
        }
        return best.get(0);
    }

    boolean narrower(Linking.Member m, Linking.Member o) {
        Type[] a = Type.getArgumentTypes(m.desc());
        Type[] b = Type.getArgumentTypes(o.desc());
        for (int k = 0; k < a.length; k++) {
            if (!fits(a[k], b[k])) {
                return false;
            }
        }
        return true;
    }

    Type cast(FixExpr.Cast c, RuleCode.Code out) {
        Type got = lower(c.expr(), out);
        if (FixExpr.PRIMITIVES.contains(c.type())) {
            Type to = Type.getType(switch (c.type()) {
                case "boolean" -> "Z";
                case "byte" -> "B";
                case "char" -> "C";
                case "short" -> "S";
                case "int" -> "I";
                case "long" -> "J";
                case "float" -> "F";
                default -> "D";
            });
            if (got.getSort() < Type.CHAR || got.getSort() > Type.DOUBLE || to.getSort() == Type.BOOLEAN) {
                throw new RuleCode.Refused("cannot cast " + got + " to " + c.type());
            }
            convert(got, to, out);
            return to;
        }
        String to = type(c.type());
        if (got.getSort() != Type.OBJECT && got.getSort() != Type.ARRAY) {
            throw new RuleCode.Refused("cannot cast " + got + " to " + c.type());
        }
        String old = port.inverse.getOrDefault(to, to);
        out.add(0, mv -> mv.visitTypeInsn(Opcodes.CHECKCAST, old));
        return Type.getObjectType(to);
    }

    /** Whether a value of type from may be passed where to is wanted, as Java allows without a cast (no boxing). */
    boolean fits(Type from, Type to) {
        if (from == RuleCode.NULL) {
            return to.getSort() == Type.OBJECT || to.getSort() == Type.ARRAY;
        }
        if (from.equals(to)) {
            return true;
        }
        if (from.getSort() < Type.ARRAY || to.getSort() < Type.ARRAY) {
            return from.getSort() >= Type.CHAR && to.getSort() > Type.CHAR && from.getSort() <= Type.DOUBLE && to.getSort() <= Type.DOUBLE
                && RuleCode.WIDER.getOrDefault(from.getSort(), Set.of()).contains(to.getSort());
        }
        if (to.getDescriptor().equals("Ljava/lang/Object;")) {
            return true;
        }
        if (from.getSort() == Type.ARRAY) {
            if (to.getSort() != Type.ARRAY) {
                return to.getInternalName().equals("java/lang/Cloneable") || to.getInternalName().equals("java/io/Serializable");
            }
            Type fe = Type.getType(from.getDescriptor().substring(1));
            Type te = Type.getType(to.getDescriptor().substring(1));
            return fe.getSort() >= Type.ARRAY && te.getSort() >= Type.ARRAY && fits(fe, te);
        }
        return to.getSort() == Type.OBJECT && port.links.isA(mod, from.getInternalName(), to.getInternalName());
    }

    /** The conversion Java makes when a value is passed or returned where a wider primitive is wanted. */
    void widen(Type from, Type to, RuleCode.Code out) {
        if (from.getSort() < Type.ARRAY && to.getSort() < Type.ARRAY && !from.equals(to)) {
            convert(from, to, out);
        }
    }

    void convert(Type from, Type to, RuleCode.Code out) {
        char f = from.getSort() <= Type.INT ? 'I' : from.getDescriptor().charAt(0);
        char t = to.getSort() <= Type.INT ? 'I' : to.getDescriptor().charAt(0);
        String pair = "" + f + t;
        Integer op = RuleCode.CONVERSIONS.get(pair);
        if (op != null) {
            out.add(to.getSize() - from.getSize(), mv -> mv.visitInsn(op));
        }
        int narrow = switch (to.getSort()) {
            case Type.BYTE -> Opcodes.I2B;
            case Type.CHAR -> Opcodes.I2C;
            case Type.SHORT -> Opcodes.I2S;
            default -> 0;
        };
        if (narrow != 0 && from.getSort() != to.getSort()) {
            out.add(0, mv -> mv.visitInsn(narrow));
        }
    }
}

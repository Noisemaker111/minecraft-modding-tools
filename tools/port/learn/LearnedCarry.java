import java.lang.constant.*;
import java.util.*;

/**
 * A replacement learned at one update of a chain, carried through each later update as a mod's own call is: every
 * class, method and field it names is followed to its counterpart, and a member a later update changed takes what
 * that update learned for it (its reading, or the call the game's code switched to). 1.21.5 learned
 * PoseStack.mulPose(Quaternionf) -> $0.mulPose($1), a Quaternionfc; 26.3 renamed that mulPose to rotate, so across both
 * it is $0.rotate($1). JarPort compiles a learned expression against the release it ports to, so one left in its own
 * update's names is refused on every longer jump. A name that cannot be followed (gone with nothing learned, or a call
 * it cannot tell from another) leaves the expression where it got to.
 */
final class LearnedCarry {

    /** Why an expression stops where it is. */
    static final class Stuck extends RuntimeException {

        Stuck(String why) {
            super(why, null, false, false);
        }
    }

    /** An expression in b's names and its type, a descriptor in a's names ("?" a method reference, null the null literal). */
    record Typed(String text, String type) {}

    final GameHop h;
    final GameLearner learner;
    final Map<String, FixExpr.Node> readings = new HashMap<>(); // Meth.sig(), or owner.name of a field, in a -> what h learned
    final Set<String> unclear = new HashSet<>(); // members h learned two readings for
    final Map<String, GameDiff.Meth> calls = new HashMap<>(); // Meth.sig() in a -> the call h's code switched to

    LearnedCarry(GameHop h, GameLearner learner, List<GameLearner.Learned> learned, List<HopRewrites.Rewrite> rewrites) {
        this.h = h;
        this.learner = learner;
        for (GameLearner.Learned l : learned) {
            // A reading for one constant is not the member's: it is not written into other expressions.
            if (l.votes() * 2 <= l.of() || l.expr().isEmpty() || l.name().endsWith("=") || l.when() != null) {
                continue;
            }
            String key = l.owner().replace('.', '/') + "." + l.name() + (l.field() ? "" : l.desc());
            if (unclear.contains(key) || readings.remove(key) != null) {
                unclear.add(key);
                continue;
            }
            try {
                readings.put(key, FixExpr.parse(l.expr()));
            } catch (IllegalArgumentException unreadable) {
                unclear.add(key);
            }
        }
        for (HopRewrites.Rewrite r : rewrites) {
            if (r.to() != null && r.votes() * 2 > r.total()) {
                calls.putIfAbsent(r.from().sig(), r.to());
            }
        }
    }

    /** expr, over operands of the types given ($0 the receiver; a's names), in b's names; null where it cannot be followed. */
    String carry(String expr, String[] types) {
        try {
            return e(FixExpr.parse(expr), types).text();
        } catch (Stuck | IllegalArgumentException s) {
            if (GameDiff.verbose) {
                System.err.println("  carry " + h.a.version + " -> " + h.b.version + ": " + expr + " stops: " + s.getMessage());
            }
            return null;
        }
    }

    /** The operands' types of what l replaces ($0 its receiver, null where there is none), in b's names; null if unknown. */
    static String[] operands(GameHop h, GameLearner.Learned l) {
        String owner = l.owner().replace('.', '/');
        boolean write = l.name().endsWith("=");
        String name = write ? l.name().substring(0, l.name().length() - 1) : l.name();
        List<String> out = new ArrayList<>();
        if (l.field()) {
            GameDiff.Fld f = h.a.field(owner, name);
            if (f == null) {
                return null;
            }
            out.add((f.flags & GameDiff.ACC_STATIC) != 0 ? null : "L" + owner + ";");
            if (write) {
                out.add(f.desc);
            }
        } else {
            GameDiff.Meth m = h.a.method(owner, name, l.desc());
            if (m == null) {
                return null;
            }
            out.add(m.isStatic() || m.special() ? null : "L" + owner + ";");
            for (ClassDesc p : MethodTypeDesc.ofDescriptor(l.desc()).parameterList()) {
                out.add(p.descriptorString());
            }
        }
        String[] types = out.toArray(String[]::new);
        for (int i = 0; i < types.length; i++) {
            types[i] = types[i] == null ? null : descAB(h, types[i]);
        }
        return types;
    }

    /** A descriptor of a in b's names; a class b dropped keeps its name. */
    static String descAB(GameHop h, String d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length();) {
            char ch = d.charAt(i);
            if (ch == 'L') {
                int semi = d.indexOf(';', i);
                String n = d.substring(i + 1, semi);
                GameDiff.Cls c = h.a.classes.get(n);
                GameDiff.Cls y = c == null ? null : h.cAB.get(c);
                sb.append('L').append(y == null ? n : y.name).append(';');
                i = semi + 1;
            } else {
                sb.append(ch);
                i++;
            }
        }
        return sb.toString();
    }

    Typed e(FixExpr.Node n, String[] types) {
        return switch (n) {
            case FixExpr.Arg x -> {
                if (x.index() >= types.length || types[x.index()] == null) {
                    throw new Stuck("$" + x.index() + " is no operand");
                }
                yield new Typed("$" + x.index(), types[x.index()]);
            }
            case FixExpr.Lit l -> new Typed(literal(l), l.type());
            case FixExpr.Cast c -> {
                Typed t = e(c.expr(), types);
                String p = primitiveDesc(c.type());
                if (p != null) {
                    yield new Typed("(" + c.type() + ") " + t.text(), p);
                }
                String owner = c.type().replace('.', '/');
                yield new Typed("(" + GameLearner.dotted(mapped(owner)) + ") " + t.text(), "L" + owner + ";");
            }
            case FixExpr.Path p -> path(p.dotted());
            case FixExpr.Get g -> {
                Typed t = e(g.target(), types);
                yield field(objectType(t), g.name(), false, t.text());
            }
            case FixExpr.Invoke i -> {
                boolean isStatic = i.target() instanceof FixExpr.Path p && known(p.dotted().replace('.', '/'));
                String owner, recv = null;
                if (isStatic) {
                    owner = ((FixExpr.Path) i.target()).dotted().replace('.', '/');
                } else {
                    Typed t = e(i.target(), types);
                    owner = objectType(t);
                    recv = t.text();
                }
                List<Typed> args = new ArrayList<>();
                for (FixExpr.Node a : i.args()) {
                    args.add(e(a, types));
                }
                yield invoke(owner, i.name(), isStatic, recv, args);
            }
            case FixExpr.New w -> {
                String owner = w.type().replace('.', '/');
                List<Typed> args = new ArrayList<>();
                for (FixExpr.Node a : w.args()) {
                    args.add(e(a, types));
                }
                if (cls(owner) == null) {
                    if (jdk(owner) == null) {
                        throw new Stuck("no class " + owner);
                    }
                    yield new Typed("new " + w.type() + "(" + texts(args) + ")", "L" + owner + ";");
                }
                GameDiff.Meth m = choose(methods(owner, "<init>"), args, false, owner + ".<init>");
                if (learner.broken(new GameDiff.Ref('m', m.owner.name, m.name, m.desc))) {
                    yield new Typed(instead(m, null, args), "L" + owner + ";");
                }
                yield new Typed("new " + GameLearner.dotted(mapped(owner)) + "(" + texts(args) + ")", "L" + owner + ";");
            }
            case FixExpr.Sum a -> {
                Typed l = e(a.left(), types), r = e(a.right(), types);
                boolean wide = l.type().equals("J") && r.type().equals("J");
                if (!wide && !(l.type().length() == 1 && "BSCI".contains(l.type()) && r.type().length() == 1 && "BSCI".contains(r.type()))) {
                    throw new Stuck("a sum of " + l.type() + " and " + r.type());
                }
                yield new Typed("(" + l.text() + " " + a.op() + " " + r.text() + ")", wide ? "J" : "I");
            }
            case FixExpr.Ref r -> {
                String owner = r.type().replace('.', '/');
                if (cls(owner) == null) {
                    if (jdk(owner) == null) {
                        throw new Stuck("no class " + owner);
                    }
                    yield new Typed(r.type() + "::" + r.name(), "?");
                }
                Set<String> now = new HashSet<>();
                for (GameDiff.Meth m : methods(owner, r.name())) {
                    if (!m.isStatic()) {
                        continue;
                    }
                    if (learner.broken(new GameDiff.Ref('m', m.owner.name, m.name, m.desc))) {
                        throw new Stuck("changed " + m.sig());
                    }
                    GameDiff.Meth y = h.mAB.get(m);
                    now.add(y == null ? GameLearner.dotted(mapped(m.owner.name)) + "::" + m.name : GameLearner.dotted(y.owner.name) + "::" + y.name);
                }
                if (now.size() != 1) {
                    throw new Stuck("no one static " + owner + "::" + r.name());
                }
                yield new Typed(now.iterator().next(), "?");
            }
        };
    }

    /** a.b.C.F.G: the longest start that is a class, its static field F, and G of that. */
    Typed path(String dotted) {
        String[] parts = dotted.split("\\.");
        for (int i = parts.length - 1; i >= 1; i--) {
            String owner = String.join("/", Arrays.copyOf(parts, i));
            if (!known(owner)) {
                continue;
            }
            Typed t = field(owner, parts[i], true, null);
            for (int k = i + 1; k < parts.length; k++) {
                t = field(objectType(t), parts[k], false, t.text());
            }
            return t;
        }
        throw new Stuck("no field " + dotted);
    }

    Typed field(String owner, String name, boolean isStatic, String recv) {
        if (cls(owner) == null) {
            Class<?> k = jdk(owner);
            try {
                java.lang.reflect.Field f = k == null ? null : k.getField(name);
                if (f == null || java.lang.reflect.Modifier.isStatic(f.getModifiers()) != isStatic) {
                    throw new Stuck("no field " + owner + "." + name);
                }
                return new Typed((isStatic ? GameLearner.dotted(owner) : LearnReading.receiver(recv)) + "." + name, f.getType().descriptorString());
            } catch (NoSuchFieldException absent) {
                throw new Stuck("no field " + owner + "." + name);
            }
        }
        GameDiff.Fld f = null;
        for (String c : supers(owner)) {
            GameDiff.Cls k = cls(c);
            f = k == null ? null : k.fields.get(name);
            if (f != null) {
                break;
            }
        }
        if (f == null || ((f.flags & GameDiff.ACC_STATIC) != 0) != isStatic) {
            throw new Stuck("no field " + owner + "." + name);
        }
        if (learner.broken(new GameDiff.Ref('f', f.owner.name, f.name, f.desc))) {
            FixExpr.Node read = readings.get(f.owner.name + "." + f.name);
            if (read == null) {
                throw new Stuck("changed " + f.owner.name + "." + f.name);
            }
            return new Typed(print(read, Collections.singletonList(recv)), f.desc);
        }
        GameDiff.Fld y = h.fAB.get(f);
        String head = isStatic ? GameLearner.dotted(y == null ? mapped(f.owner.name) : y.owner.name) : LearnReading.receiver(recv);
        return new Typed(head + "." + (y == null ? f.name : y.name), f.desc);
    }

    Typed invoke(String owner, String name, boolean isStatic, String recv, List<Typed> args) {
        List<GameDiff.Meth> found = methods(owner, name);
        if (found.isEmpty()) {
            // A method of the JDK: the class itself, or one a class of a extends or implements (AutoCloseable.close).
            Stuck first = null;
            for (String c : supers(owner)) {
                Class<?> k = cls(c) == null ? jdk(c) : null;
                if (k != null) {
                    try {
                        return jdkCall(k, c, name, isStatic, recv, args);
                    } catch (Stuck s) {
                        first = first == null ? s : first;
                    }
                }
            }
            throw first != null ? first : new Stuck("no " + owner + "." + name);
        }
        GameDiff.Meth m = choose(found, args, isStatic, owner + "." + name);
        String ret = MethodTypeDesc.ofDescriptor(m.desc).returnType().descriptorString();
        if (learner.broken(new GameDiff.Ref('m', m.owner.name, m.name, m.desc))) {
            return new Typed(instead(m, recv, args), ret);
        }
        GameDiff.Meth y = h.mAB.get(m);
        String head = isStatic ? GameLearner.dotted(y == null ? mapped(m.owner.name) : y.owner.name) : LearnReading.receiver(recv);
        return new Typed(head + "." + (y == null ? m.name : y.name) + "(" + texts(args) + ")", ret);
    }

    /** What h's code computes in place of a member it changed: its learned reading, or the call it switched to. */
    String instead(GameDiff.Meth m, String recv, List<Typed> args) {
        FixExpr.Node read = readings.get(m.sig());
        if (read != null) {
            List<String> ops = new ArrayList<>();
            ops.add(recv);
            args.forEach(t -> ops.add(t.text()));
            return print(read, ops);
        }
        GameDiff.Meth to = calls.get(m.sig());
        if (to != null && !m.special() && to.isStatic() == m.isStatic()
            && MethodTypeDesc.ofDescriptor(to.desc).parameterCount() == args.size()) {
            return (to.isStatic() ? GameLearner.dotted(to.owner.name) : LearnReading.receiver(recv)) + "." + to.name + "(" + texts(args) + ")";
        }
        throw new Stuck("changed " + m.sig());
    }

    Typed jdkCall(Class<?> k, String owner, String name, boolean isStatic, String recv, List<Typed> args) {
        Set<String> returns = new HashSet<>();
        for (java.lang.reflect.Method m : k.getMethods()) {
            if (m.getName().equals(name) && !m.isBridge() && m.getParameterCount() == args.size()
                && java.lang.reflect.Modifier.isStatic(m.getModifiers()) == isStatic) {
                returns.add(m.getReturnType().descriptorString());
            }
        }
        if (returns.size() != 1) {
            throw new Stuck("no one " + owner + "." + name);
        }
        String head = isStatic ? GameLearner.dotted(owner) : LearnReading.receiver(recv);
        return new Typed(head + "." + name + "(" + texts(args) + ")", returns.iterator().next());
    }

    /** The one method a call with these arguments reaches, preferring one whose parameters are exactly theirs. */
    GameDiff.Meth choose(List<GameDiff.Meth> found, List<Typed> args, boolean isStatic, String what) {
        List<GameDiff.Meth> fit = new ArrayList<>(), exact = new ArrayList<>();
        for (GameDiff.Meth m : found) {
            if ((m.flags & GameDiff.ACC_SYNTHETIC) != 0 || !m.special() && m.isStatic() != isStatic) {
                continue;
            }
            List<ClassDesc> ps = MethodTypeDesc.ofDescriptor(m.desc).parameterList();
            if (ps.size() != args.size()) {
                continue;
            }
            boolean fits = true, same = true;
            for (int i = 0; i < ps.size() && fits; i++) {
                String p = ps.get(i).descriptorString(), t = args.get(i).type();
                fits = fits(t, p);
                same &= p.equals(t);
            }
            if (fits) {
                fit.add(m);
            }
            if (fits && same) {
                exact.add(m);
            }
        }
        if (fit.size() == 1) {
            return fit.get(0);
        }
        if (exact.size() == 1) {
            return exact.get(0);
        }
        throw new Stuck((fit.isEmpty() ? "no " : "more than one ") + what + " for " + args.stream().map(Typed::type).toList());
    }

    boolean fits(String arg, String param) {
        if (arg == null) {
            return param.startsWith("L") || param.startsWith("[");
        }
        if (arg.equals(param) || "?".equals(arg) && param.startsWith("L")) {
            return true;
        }
        if (arg.length() == 1 || param.length() == 1) {
            String order = "BSIJFD";
            int x = order.indexOf(arg.charAt(0)), y = order.indexOf(param.charAt(0));
            return arg.length() == 1 && param.length() == 1 && x >= 0 && y >= x || "C".equals(arg) && "IJFD".contains(param);
        }
        if (param.equals("Ljava/lang/Object;")) {
            return true;
        }
        return arg.startsWith("L") && param.startsWith("L") && isA(arg.substring(1, arg.length() - 1), param.substring(1, param.length() - 1));
    }

    boolean isA(String sub, String sup) {
        for (String c : supers(sub)) {
            if (c.equals(sup)) {
                return true;
            }
            Class<?> k = cls(c) == null ? jdk(c) : null;
            Class<?> s = jdk(sup);
            if (k != null && s != null && s.isAssignableFrom(k)) {
                return true;
            }
        }
        return false;
    }

    /** owner and every class and interface above it that a or its libraries declare, then the first one they do not. */
    List<String> supers(String owner) {
        List<String> out = new ArrayList<>();
        Deque<String> todo = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            String c = todo.poll();
            if (!seen.add(c)) {
                continue;
            }
            out.add(c);
            GameDiff.Cls k = cls(c);
            if (k != null) {
                if (k.sup != null) {
                    todo.add(k.sup);
                }
                todo.addAll(k.ifaces);
            }
        }
        return out;
    }

    /** Every method named name a call on owner reaches in a or its libraries, most derived first. */
    List<GameDiff.Meth> methods(String owner, String name) {
        List<GameDiff.Meth> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String c : name.equals("<init>") ? List.of(owner) : supers(owner)) {
            GameDiff.Cls k = cls(c);
            if (k == null) {
                continue;
            }
            for (GameDiff.Meth m : k.methods.values()) {
                if (m.name.equals(name) && seen.add(m.desc)) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    GameDiff.Cls cls(String internal) {
        GameDiff.Cls c = h.a.classes.get(internal);
        return c != null ? c : h.a.libraries.get(internal);
    }

    boolean known(String internal) {
        return cls(internal) != null || jdk(internal) != null;
    }

    /** A class in b's names: its counterpart, or the same library or JDK class. */
    String mapped(String internal) {
        GameDiff.Cls c = h.a.classes.get(internal);
        if (c == null || c.library) {
            if (h.a.libraries.containsKey(internal) && h.b.libraries.containsKey(internal) || c != null && h.b.classes.containsKey(internal) || jdk(internal) != null) {
                return internal;
            }
            throw new Stuck("no class " + internal + " in " + h.b.version);
        }
        GameDiff.Cls y = h.cAB.get(c);
        if (y == null) {
            throw new Stuck("class " + internal + " gone");
        }
        return y.name;
    }

    static String objectType(Typed t) {
        if (t.type() == null || !t.type().startsWith("L")) {
            throw new Stuck("no members on " + t.type());
        }
        return t.type().substring(1, t.type().length() - 1);
    }

    static Class<?> jdk(String internal) {
        if (!internal.startsWith("java/")) {
            return null;
        }
        try {
            return Class.forName(internal.replace('/', '.'), false, null);
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }

    static String primitiveDesc(String keyword) {
        return switch (keyword) {
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            default -> null;
        };
    }

    static String texts(List<Typed> args) {
        StringJoiner out = new StringJoiner(", ");
        args.forEach(t -> out.add(t.text()));
        return out.toString();
    }

    /** A reading of b (FixExpr) written out with its operands ($0 the receiver) as the texts given. */
    static String print(FixExpr.Node n, List<String> ops) {
        return switch (n) {
            case FixExpr.Arg x -> {
                if (x.index() >= ops.size() || ops.get(x.index()) == null) {
                    throw new Stuck("$" + x.index() + " has no operand");
                }
                yield ops.get(x.index());
            }
            case FixExpr.Lit l -> literal(l);
            case FixExpr.Path p -> p.dotted();
            case FixExpr.Get g -> LearnReading.receiver(print(g.target(), ops)) + "." + g.name();
            case FixExpr.Invoke i -> {
                StringJoiner args = new StringJoiner(", ");
                i.args().forEach(a -> args.add(print(a, ops)));
                yield LearnReading.receiver(print(i.target(), ops)) + "." + i.name() + "(" + args + ")";
            }
            case FixExpr.New w -> {
                StringJoiner args = new StringJoiner(", ");
                w.args().forEach(a -> args.add(print(a, ops)));
                yield "new " + w.type() + "(" + args + ")";
            }
            case FixExpr.Cast c -> "(" + c.type() + ") " + print(c.expr(), ops);
            case FixExpr.Ref r -> r.type() + "::" + r.name();
            case FixExpr.Sum a -> "(" + print(a.left(), ops) + " " + a.op() + " " + print(a.right(), ops) + ")";
        };
    }

    static String literal(FixExpr.Lit l) {
        Object v = l.value();
        if (l.type() == null) {
            return "null";
        }
        return switch (l.type()) {
            case "Ljava/lang/String;" -> "\"" + ((String) v).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            case "J" -> v + "L";
            case "F" -> v + "F";
            case "D" -> v + "D";
            default -> String.valueOf(v);
        };
    }
}

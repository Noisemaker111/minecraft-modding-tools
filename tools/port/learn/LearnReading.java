import java.lang.classfile.*;
import java.lang.constant.*;
import java.util.*;
import java.util.regex.*;

/** One lined-up use read as a replacement expression over the old member's operands. */
final class LearnReading {

    final GameLearner learner;

    LearnReading(GameLearner learner) {
        this.learner = learner;
    }

    final Map<GameDiff.Node, Boolean> mergedMemo = new IdentityHashMap<>();

    /** The tree holds a value that depends on the path taken. */
    boolean merged(GameDiff.Node n) {
        Boolean known = mergedMemo.get(n);
        if (known != null) {
            return known;
        }
        boolean out = BodyDecoder.phi(n);
        for (int i = 0; i < n.kids.length && !out; i++) {
            out = merged(n.kids[i]);
        }
        mergedMemo.put(n, out);
        return out;
    }

    /** The argument a b method gives back as its result, by its generic signature (`<T> T m(T)`), or null. */
    Integer givesArgument(GameDiff.Ref r) {
        GameDiff.Meth mb = learner.h.b.method(r.owner(), r.name(), r.desc());
        ClassModel cm = mb == null ? null : learner.model(learner.h.b, mb.owner.name);
        if (cm == null) {
            return null;
        }
        GameDiff.Origin o = learner.h.b.origins.get(mb.owner.name);
        String self = cm.thisClass().asInternalName();
        for (MethodModel mm : cm.methods()) {
            String rn = mm.methodName().stringValue(), rd = mm.methodType().stringValue();
            if (!(o.names().method(self, rn, rd) + o.names().desc(rd)).equals(mb.name + mb.desc)) {
                continue;
            }
            var sig = mm.findAttribute(Attributes.signature());
            if (sig.isEmpty()) {
                return null;
            }
            MethodSignature ms = MethodSignature.parseFrom(sig.get().signature().stringValue());
            if (!(ms.result() instanceof Signature.TypeVarSig t)) {
                return null;
            }
            Integer found = null;
            for (int i = 0; i < ms.arguments().size(); i++) {
                if (ms.arguments().get(i) instanceof Signature.TypeVarSig a && a.identifier().equals(t.identifier())) {
                    if (found != null) {
                        return null;
                    }
                    found = i;
                }
            }
            return found;
        }
        return null;
    }

    /** An a descriptor's class in b's names, dotted (through the links, or a class rewrite). */
    String mappedName(String desc) {
        String old = desc.substring(1, desc.length() - 1);
        GameDiff.Cls ca = learner.h.a.classes.get(old);
        GameDiff.Cls cb = ca == null ? null : learner.h.cAB.get(ca);
        if (cb == null && ca != null) {
            cb = learner.classRewrites().get(ca);
        }
        return (cb != null ? cb.name : old).replace('/', '.');
    }

    /** b computes m where a used the changed member in use: every way to write m over use's operands. */
    void read(GameDiff.Node use, GameDiff.Node m) {
        String key = use.ref.owner() + "." + use.ref.name() + (use.ref.kind() == 'm' ? use.ref.desc() : "");
        learner.keys.putIfAbsent(key, use.ref);
        if (!learner.weak) {
            learner.lined.computeIfAbsent(use, k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(m);
        }
        learner.learnChains.chain(use, m);
        if (learner.weak) {
            return;
        }
        Set<String> readings = learner.sites.computeIfAbsent(key, k -> new IdentityHashMap<>()).computeIfAbsent(use, k -> new LinkedHashSet<>());
        int first = use.op == 'v' || use.op == 'f' ? 0 : 1;
        String cast = "";
        if (!fits(m.type, use.type)) {
            // A generic call gives back its argument's own type (<T extends GuiEventListener> T addRenderableWidget(T)):
            // erased it says GuiEventListener, but javac gives the argument's type, so the value is cast back to it.
            Integer j = (m.op == 'v' || m.op == 's') ? givesArgument(m.ref) : null;
            GameDiff.Node arg = j == null ? null : m.kids[j + (m.op == 'v' ? 1 : 0)];
            if (arg == null || !use.type.startsWith("L") || !fits(arg.type, use.type)) {
                if (learner.deferred != null && learner.learnTypeMap.typeVote(use.type, m.type)) {
                    learner.deferred.add(new GameDiff.Node[] { use, m });
                }
                return;
            }
            cast = "(" + mappedName(use.type) + ") ";
        }
        String body = express(m, use.kids, first, use.type, 0);
        if (use.type.equals("V") && endsLike(use, m)) {
            // A void call whose place b fills with only its receiver's value (the vertex chain endVertex() ended,
            // even where the game also rewrote that chain: color(255, 255, 255, 255) became color(-1)) did nothing
            // b still does: it is dropped.
            body = "";
            align(use.kids[0], m);
        }
        if (body == null && learner.later != null && Arrays.stream(use.kids).anyMatch(k -> learner.learnPairing.hole(k) && !learner.lined.containsKey(k))) {
            // An operand is a changed member's value not lined up yet: read again once every statement has been.
            learner.later.add(new GameDiff.Node[] { use, m });
        }
        boolean unsure = false;
        for (int i = 0; body != null && !body.isEmpty() && i < use.kids.length; i++) {
            // An operand that depends on the path taken, or holds a changed member b showed nothing for, left out of the
            // reading: b's code may be that operand's value
            // written out (a builder made on one branch), which says nothing about the member, so the use abstains.
            if ((merged(use.kids[i]) || learner.learnPairing.holed(use.kids[i]) && !learner.lined.containsKey(use.kids[i])) && !Pattern.compile("\\$" + (i + first) + "(?!\\d)").matcher(body).find()
                && LearnChains.MADE.matcher(body).find()) {
                body = null;
                unsure = true;
            }
        }
        String reading = body == null ? null : cast.isEmpty() ? body : cast + receiver(body);
        if (reading == null && (m.op == 'v' || m.op == 'f') && m.ref != null && m.kids.length > 0 && local(m.kids[0])) {
            // Only the receiver is the method's own: the same call with the same arguments, read over the operands.
            String args = m.op == 'v' ? arguments(m, 1, use.kids, first, 0) : "";
            if (args != null) {
                learner.aside.put(use, m.op == 'v' ? m.ref.name() + "(" + args + ")" : m.ref.name());
            }
        }
        if (reading == null && readings.isEmpty() && (unsure || merged(m) || Arrays.stream(use.kids).anyMatch(this::merged))) {
            // Unreadable only because a value depends on the path taken (a loop's variable): that says nothing
            // either way, so the use abstains instead of counting against.
            learner.sites.get(key).remove(use);
            return;
        }
        if (reading != null) {
            readings.add(reading);
            // A one-line static wrapper reads the same as its body: uses that wrote either agree
            // (FMLEnvironment.getDist() is FMLLoader.getCurrent().getDist()).
            GameDiff.Node inner = m.op == 's' && cast.isEmpty() ? learner.learnSameMeaning.oneLine(m) : null;
            String alt = inner == null ? null : express(inner, use.kids, first, use.type, 0);
            if (alt != null) {
                readings.add(alt);
            }
        }
    }

    /** A value read from the method's own parameters or locals, nothing else: what a mod's call site does not have. */
    static boolean local(GameDiff.Node n) {
        if (n.op == 'p' || n.op == 'l') {
            return true;
        }
        if ((n.op == 'v' || n.op == 'f' || n.op == 't') && n.kids.length > 0) {
            return local(n.kids[0]);
        }
        return false;
    }

    /** Two trees the same in shape: each use of a changed member in a, with the value b has in its place. */
    void align(GameDiff.Node x, GameDiff.Node y) {
        if (learner.learnPairing.hole(x)) {
            learner.lined.computeIfAbsent(x, z -> Collections.newSetFromMap(new IdentityHashMap<>())).add(y);
        } else if (x.op == y.op && x.kids.length == y.kids.length) {
            for (int i = 0; i < x.kids.length; i++) {
                align(x.kids[i], y.kids[i]);
            }
        }
    }

    /** use is an instance call and m is the same call its receiver ends with: same member, same number of operands. */
    boolean endsLike(GameDiff.Node use, GameDiff.Node m) {
        if (use.op != 'v' || use.kids.length != 1) {
            return false;
        }
        GameDiff.Node r = use.kids[0];
        return (r.op == 'v' || r.op == 's') && !learner.learnPairing.hole(r) && r.op == m.op && r.kids.length == m.kids.length && learner.learnPairing.head(r, true).equals(learner.learnPairing.head(m, false));
    }

    /** b's value of type got can stand where a's code had a value of type had: anything for nothing, else the same type or a subtype. */
    boolean fits(String got, String had) {
        if (had.equals("V")) {
            return true;
        }
        if (got.equals("null")) {
            return had.startsWith("L") || had.startsWith("[");
        }
        if (learner.h.descBA(got).equals(had)) {
            return true;
        }
        if (!got.startsWith("L") || !had.startsWith("L")) {
            return false;
        }
        String old = had.substring(1, had.length() - 1);
        GameDiff.Cls ca = learner.h.a.classes.get(old);
        GameDiff.Cls cb = ca == null ? null : learner.h.cAB.get(ca);
        if (cb == null && ca != null) {
            cb = learner.classRewrites().get(ca);
        }
        return learner.h.hopChains.subtypeB(got.substring(1, got.length() - 1), cb != null ? cb.name : old);
    }

    /**
     * b's value m written over the old member's operands ($first.. for ops), or null: an operand where m is that
     * operand's code, a constant as itself or as an operand that held the same number (a choice, settled by vote);
     * nothing that reads the method's parameters, locals or a member a mod may not use.
     */
    String express(GameDiff.Node m, GameDiff.Node[] ops, int first, String want, int depth) {
        List<String> same = new ArrayList<>();
        for (int i = 0; i < ops.length; i++) {
            learner.learnPairing.budget = 2_000;
            if (learner.learnPairing.match(ops[i], m, null) || linesUpThrough(ops[i], m) || learner.lined.getOrDefault(ops[i], Set.of()).contains(m)) {
                same.add("$" + (i + first));
            } else if (boxed(ops[i]) && primitive(m.type) != null) {
                // An operand the old code boxed (withInitialValue(Boolean.valueOf(x))) where b passes the primitive itself
                // (booleanBuilder(a, b, x)): the operand unboxed.
                learner.learnPairing.budget = 2_000;
                if (learner.learnPairing.match(ops[i].kids[0], m, null)) {
                    same.add("((" + GameLearner.dotted(ops[i].ref.owner()) + ") $" + (i + first) + ")." + primitive(m.type) + "Value()");
                }
            }
        }
        if (m.op == 'k') {
            String p = primitive(m.type);
            for (int i = 0; i < ops.length; i++) {
                if (ops[i].op == 'k' && !ops[i].type.equals(m.type) && p != null && ops[i].value instanceof Number u && m.value instanceof Number v
                    && u.doubleValue() == v.doubleValue()) {
                    same.add("(" + p + ") $" + (i + first));
                }
            }
            String lit = literal(m, want);
            if (lit != null) {
                same.add(lit);
            }
            return GameLearner.choice(same);
        }
        if (!same.isEmpty() || depth > 8) {
            return GameLearner.choice(same);
        }
        return switch (m.op) {
            case 'n' -> "null";
            case 'g' -> open(m.ref, true) ? GameLearner.dotted(m.ref.owner()) + "." + m.ref.name() : null;
            case 'f' -> {
                String t = open(m.ref, false) ? express(m.kids[0], ops, first, null, depth + 1) : null;
                yield t == null ? null : receiver(t) + "." + m.ref.name();
            }
            case 's', 'w' -> {
                String args = open(m.ref, m.op == 's') ? arguments(m, 0, ops, first, depth) : null;
                yield args == null ? null : (m.op == 's' ? GameLearner.dotted(m.ref.owner()) + "." + m.ref.name() : "new " + GameLearner.dotted(m.ref.owner())) + "(" + args + ")";
            }
            case 'v' -> {
                String t = open(m.ref, false) && m.value == null ? express(m.kids[0], ops, first, null, depth + 1) : null;
                String args = t == null ? null : arguments(m, 1, ops, first, depth);
                yield args == null ? null : receiver(t) + "." + m.ref.name() + "(" + args + ")";
            }
            case 't' -> {
                String t = m.ref.owner().startsWith("[") ? null : express(m.kids[0], ops, first, null, depth + 1);
                yield t == null ? null : "(" + GameLearner.dotted(m.ref.owner()) + ") " + t;
            }
            case 'x' -> {
                String p = primitive(m.type);
                String t = p == null ? null : express(m.kids[0], ops, first, null, depth + 1);
                if (t != null && t.charAt(0) == GameLearner.OPEN && t.indexOf(GameLearner.CLOSE) == t.length() - 1) {
                    // A converted operand is one choice, as a constant that operand held would be: (float) $2.
                    yield GameLearner.OPEN + "(" + p + ") " + t.substring(1, t.length() - 1).replace(String.valueOf(GameLearner.OR), GameLearner.OR + "(" + p + ") ") + GameLearner.CLOSE;
                }
                yield t == null ? null : "(" + p + ") " + t;
            }
            case 'y' -> {
                GameDiff.Meth impl = m.ref == null || m.kids.length > 0 ? null : learner.h.b.method(m.ref.owner(), m.ref.name(), m.ref.desc());
                yield impl != null && impl.isStatic() && open(m.ref, true) ? GameLearner.dotted(m.ref.owner()) + "::" + m.ref.name() : null;
            }
            default -> null;
        };
    }

    /**
     * An operand that is b's code m except inside uses of other changed members, each where b has a value that can
     * stand in its place: a mod's operand, ported, is what b computes there. 1.21's vertex chain
     * vertex(x, y, z).color(c) before endVertex() is addVertex(x, y, z).setColor(c), vertex having changed too. The
     * operand itself must line up, not be such a use.
     */
    boolean linesUpThrough(GameDiff.Node op, GameDiff.Node m) {
        if (learner.learnPairing.hole(op) || !learner.learnPairing.holed(op)) {
            return false;
        }
        Map<GameDiff.Node, GameDiff.Node> inner = new IdentityHashMap<>();
        learner.learnPairing.budget = 2_000;
        if (!learner.learnPairing.match(op, m, inner)) {
            return false;
        }
        for (var e : inner.entrySet()) {
            if (!fits(e.getValue().type, e.getKey().type)) {
                return false;
            }
        }
        inner.forEach((k, v) -> learner.lined.computeIfAbsent(k, z -> Collections.newSetFromMap(new IdentityHashMap<>())).add(v));
        return true;
    }

    /** A call's arguments (m's kids from index from), each typed for its parameter, or null. */
    String arguments(GameDiff.Node m, int from, GameDiff.Node[] ops, int first, int depth) {
        List<ClassDesc> params = MethodTypeDesc.ofDescriptor(m.ref.desc()).parameterList();
        StringJoiner out = new StringJoiner(", ");
        for (int i = from; i < m.kids.length; i++) {
            int p = i - from;
            String one = express(m.kids[i], ops, first, p < params.size() ? params.get(p).descriptorString() : null, depth + 1);
            if (one == null) {
                return null;
            }
            out.add(one);
        }
        return out.toString();
    }

    /** A member of b a mod may name: b's own and not private, or the JDK's. */
    boolean open(GameDiff.Ref r, boolean wantStatic) {
        if (r.name() != null && r.name().contains("$")) {
            return false;
        }
        if (r.kind() == 'm') {
            GameDiff.Meth m = learner.h.b.method(r.owner(), r.name(), r.desc());
            m = m != null ? m : learner.h.b.libraryMethod(r.owner(), r.name(), r.desc());
            if (m == null) {
                return r.owner().startsWith("java/");
            }
            return (m.flags & (GameDiff.ACC_PUBLIC | 0x0004)) != 0 && (m.flags & GameDiff.ACC_SYNTHETIC) == 0 && (m.special() || m.isStatic() == wantStatic) && !m.name.equals("<clinit>");
        }
        GameDiff.Fld f = learner.h.b.field(r.owner(), r.name());
        f = f != null ? f : learner.h.b.libraryField(r.owner(), r.name());
        if (f == null) {
            return r.owner().startsWith("java/");
        }
        return (f.flags & (GameDiff.ACC_PUBLIC | 0x0004)) != 0 && ((f.flags & GameDiff.ACC_STATIC) != 0) == wantStatic;
    }

    /** A cast written as a receiver needs its own parentheses: ((T) x).m(). */
    static String receiver(String t) {
        return t.startsWith("(") ? "(" + t + ")" : t;
    }

    /** A primitive boxed by its wrapper's valueOf (Boolean.valueOf(x)). */
    static boolean boxed(GameDiff.Node n) {
        return n.op == 's' && n.kids.length == 1 && n.ref != null && n.ref.name().equals("valueOf") && n.ref.owner().startsWith("java/lang/")
            && n.kids[0].type.length() == 1;
    }

    static String primitive(String desc) {
        return switch (desc) {
            case "Z" -> "boolean";
            case "C" -> "char";
            case "B" -> "byte";
            case "S" -> "short";
            case "I" -> "int";
            case "J" -> "long";
            case "F" -> "float";
            case "D" -> "double";
            default -> null;
        };
    }

    /** A constant as FixExpr reads it, typed for where it goes (an int constant passed as a boolean is true or false). */
    static String literal(GameDiff.Node k, String want) {
        Object v = k.value;
        if (v instanceof String s) {
            return s.chars().anyMatch(c -> c < 0x20) ? null : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        if (v instanceof Integer i) {
            if ("Z".equals(want)) {
                return i == 0 ? "false" : i == 1 ? "true" : null;
            }
            if ("C".equals(want) || "B".equals(want) || "S".equals(want)) {
                return "(" + primitive(want) + ") " + i;
            }
            return Integer.toString(i);
        }
        if (v instanceof Long l) {
            return l + "L";
        }
        String text = v instanceof Float f ? Float.toString(f) + "F" : v instanceof Double d ? Double.toString(d) + "D" : null;
        return text == null || text.contains("E") || text.contains("N") || text.contains("I") ? null : text;
    }
}

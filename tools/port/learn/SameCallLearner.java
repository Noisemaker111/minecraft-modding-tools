import java.lang.constant.*;
import java.util.*;
import java.util.regex.*;

/**
 * A method the game kept with other parameters (the "call, game: linked" break), called as its old self behaved: each
 * parameter of the new method is read from where the two bodies line up. Where the new body uses a parameter, the old
 * body's value at that place is what to pass: one of the old operands, a fixed value, or the fixed value solved through
 * a sum (1.21.2's Biome.getHeightAdjustedTemperature(pos, seaLevel) tests seaLevel + 17 where the old one tested 80, so
 * the old call is the new one with 63). A parameter only handed on to a callee that changed the same way is solved in
 * that callee first (getTemperature(pos) passes seaLevel to getHeightAdjustedTemperature). A value of another type is
 * made with the new type's own public constructor or its one static factory from the old type (TriState.from(boolean)),
 * and a parameter the new body never reads takes its type's zero. Every place a parameter is used must agree, every
 * other statement must line up, and the result must serve where the old one did; otherwise nothing is learned.
 */
final class SameCallLearner {

    /** A value over the old method's operands, and its type (a descriptor; in b's names when inB). */
    record R(String text, String type, boolean inB) {}

    final GameHop h;
    final GameLearner learner;
    final Map<GameDiff.Meth, List<String>> solved = new LinkedHashMap<>();
    final Set<GameDiff.Meth> solving = new LinkedHashSet<>();
    final Map<String, List<GameDiff.Cls>> declaring = new HashMap<>(); // name + desc -> b's classes that declare it
    final Map<String, String> readings = new HashMap<>(); // a's field "owner/name" -> what b reads on $0 for it ("getBottom()", "width")
    Map<String, List<GameDiff.Meth>> users; // a's field "owner/name" -> a's methods other than constructors that use it
    static final String WHY = System.getProperty("gamediff.samecall", ""); // a method name: why it is or is not solved

    SameCallLearner(GameHop h, GameLearner learner) {
        this.h = h;
        this.learner = learner;
        for (GameDiff.Cls c : h.b.classes.values()) {
            for (GameDiff.Meth m : c.methods.values()) {
                if (!m.isStatic() && !m.special()) {
                    declaring.computeIfAbsent(m.name + m.desc, k -> new ArrayList<>()).add(c);
                }
            }
        }
    }

    /**
     * Whether a call of y always runs y's own body: static, a constructor, final, or nothing in b overrides it. An
     * override can read a parameter the base body ignores, so only a body the call surely runs says what to pass.
     */
    boolean runs(GameDiff.Meth y) {
        if (y.isStatic() || y.special() || (y.flags & 0x0010) != 0 || (y.owner.flags & 0x0010) != 0) {
            return true;
        }
        for (GameDiff.Cls c : declaring.getOrDefault(y.name + y.desc, List.of())) {
            if (c != y.owner && below(c, y.owner.name)) {
                return false;
            }
        }
        return (y.flags & 0x0400) == 0;
    }

    boolean below(GameDiff.Cls c, String owner) {
        Deque<String> todo = new ArrayDeque<>();
        if (c.sup != null) {
            todo.push(c.sup);
        }
        c.ifaces.forEach(todo::push);
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            String n = todo.pop();
            if (!seen.add(n)) {
                continue;
            }
            if (n.equals(owner)) {
                return true;
            }
            GameDiff.Cls k = h.b.classes.get(n);
            if (k != null) {
                if (k.sup != null) {
                    todo.push(k.sup);
                }
                k.ifaces.forEach(todo::push);
            }
        }
        return false;
    }

    /** A learned line for every kept method whose parameters changed and whose old behaviour the new one can give. */
    List<GameLearner.Learned> learn(List<GameLearner.Learned> known) {
        Set<String> have = new HashSet<>();
        for (GameLearner.Learned l : known) {
            have.add(l.owner().replace('.', '/') + "." + l.name() + l.desc());
            if (l.field() && l.expr().startsWith("$0.")) {
                readings.put(l.owner().replace('.', '/') + "." + l.name(), l.expr().substring(3));
            }
        }
        List<GameLearner.Learned> out = new ArrayList<>();
        for (var e : h.mAB.entrySet()) {
            GameDiff.Meth x = e.getKey(), y = e.getValue();
            // A private method is reached through a mod's own access transformer, which JarPort reads.
            if (x.isStatic() != y.isStatic() || !x.hasCode || !y.hasCode || x.name.equals("<clinit>")
                || h.descBA(y.desc).equals(x.desc) || have.contains(x.sig())
                || !h.descBA(MethodTypeDesc.ofDescriptor(y.desc).returnType().descriptorString())
                    .equals(MethodTypeDesc.ofDescriptor(x.desc).returnType().descriptorString())) {
                continue;
            }
            List<String> r = solve(x, y, 0);
            if (r == null) {
                continue;
            }
            String args = String.join(", ", r.subList(1, r.size()));
            String expr = y.name.equals("<init>") ? "new " + GameLearner.dotted(y.owner.name) + "(" + args + ")"
                : y.isStatic() ? GameLearner.dotted(y.owner.name) + "." + y.name + "(" + args + ")" : "$0." + y.name + "(" + args + ")";
            out.add(new GameLearner.Learned(GameLearner.dotted(x.owner.name), x.name, x.desc, false, true, expr, 1, 1));
        }
        return out;
    }

    /** m's operand types ($0 the receiver, null when static or a constructor's this) and each parameter slot's operand. */
    static String[] operands(GameDiff.Meth m) {
        List<ClassDesc> ps = MethodTypeDesc.ofDescriptor(m.desc).parameterList();
        String[] out = new String[ps.size() + 1];
        out[0] = m.isStatic() || m.special() ? null : "L" + m.owner.name + ";";
        for (int i = 0; i < ps.size(); i++) {
            out[i + 1] = ps.get(i).descriptorString();
        }
        return out;
    }

    static int operand(GameDiff.Meth m, Object slot) {
        if (!(slot instanceof Integer s)) {
            return -1;
        }
        int at = m.isStatic() ? 0 : 1;
        if (!m.isStatic() && s == 0) {
            return 0;
        }
        List<ClassDesc> ps = MethodTypeDesc.ofDescriptor(m.desc).parameterList();
        for (int i = 0; i < ps.size(); i++) {
            if (at == s) {
                return i + 1;
            }
            String d = ps.get(i).descriptorString();
            at += d.equals("J") || d.equals("D") ? 2 : 1;
        }
        return -1;
    }

    /** Each parameter of y ($0 its receiver) as a value over x's operands, in b's names; null when the bodies do not say. */
    List<String> solve(GameDiff.Meth x, GameDiff.Meth y, int depth) {
        if (solved.containsKey(x)) {
            return solved.get(x);
        }
        if (depth > 4 || !runs(y) || !solving.add(x)) {
            if (!WHY.isEmpty() && x.sig().contains(WHY)) {
                System.err.println("samecall " + x.sig() + ": depth " + depth + ", runs " + runs(y) + ", solving " + solving.contains(x));
            }
            return null;
        }
        List<String> out = null;
        try {
            out = work(x, y, depth);
            if (out == null && x.name.equals("<init>") && (x.owner.flags & GameDiff.ACC_ENUM) == 0) {
                out = new Built(x, y).solve();
            }
        } catch (RuntimeException unreadable) {
            out = null;
        } finally {
            solving.remove(x);
        }
        if (!WHY.isEmpty() && x.sig().contains(WHY)) {
            System.err.println("samecall " + x.sig() + " -> " + y.sig() + ": " + out);
        }
        solved.put(x, out);
        return out;
    }

    final class Ctx {
        final GameDiff.Meth x, y;
        final int depth;
        final Map<Integer, Set<R>> read = new HashMap<>();

        Ctx(GameDiff.Meth x, GameDiff.Meth y, int depth) {
            this.x = x;
            this.y = y;
            this.depth = depth;
        }

        void read(int j, R r) {
            read.computeIfAbsent(j, k -> new LinkedHashSet<>()).add(r);
        }

        R ofX(GameDiff.Node n) {
            return ofX(n, 0);
        }

        /**
         * A value of the old body as an expression in b's names: an operand, a constant, or calls, fields and casts over
         * those that both releases have (1.21.2's onCrystalDestroyed took the ServerLevel its old body read as
         * this.level()).
         */
        R ofX(GameDiff.Node n, int depth) {
            if (depth > 4) {
                return null;
            }
            return switch (n.op) {
                case 'v', 'f', 's', 'g' -> {
                    R recv = n.op == 'v' || n.op == 'f' ? (n.kids.length == 0 ? null : ofX(n.kids[0], depth + 1)) : null;
                    if ((n.op == 'v' || n.op == 'f') && recv == null) {
                        yield null;
                    }
                    String name, owner, type;
                    if (n.op == 'v' || n.op == 's') {
                        GameDiff.Meth m = h.a.method(n.ref.owner(), n.ref.name(), n.ref.desc());
                        GameDiff.Meth my = m == null ? null : h.mAB.get(m);
                        if (my == null || !h.descBA(my.desc).equals(m.desc) || my.isStatic() != (n.op == 's') || (my.flags & GameDiff.ACC_PUBLIC) == 0) {
                            yield null;
                        }
                        StringJoiner args = new StringJoiner(", ");
                        for (int i = n.op == 'v' ? 1 : 0; i < n.kids.length; i++) {
                            R a = ofX(n.kids[i], depth + 1);
                            if (a == null) {
                                yield null;
                            }
                            args.add(a.text());
                        }
                        name = my.name + "(" + args + ")";
                        owner = my.owner.name;
                        type = MethodTypeDesc.ofDescriptor(m.desc).returnType().descriptorString();
                    } else {
                        GameDiff.Fld f = h.a.field(n.ref.owner(), n.ref.name());
                        GameDiff.Fld fy = f == null ? null : h.fAB.get(f);
                        if (fy == null || !h.descBA(fy.desc).equals(f.desc) || (fy.flags & GameDiff.ACC_PUBLIC) == 0) {
                            yield null;
                        }
                        name = fy.name;
                        owner = fy.owner.name;
                        type = f.desc;
                    }
                    yield new R((recv == null ? GameLearner.dotted(owner) : LearnReading.receiver(recv.text())) + "." + name, type, false);
                }
                default -> one(n);
            };
        }

        R one(GameDiff.Node n) {
            return switch (n.op) {
                case 'p' -> {
                    int i = operand(x, n.value);
                    String t = i < 0 ? null : operands(x)[i];
                    yield t == null && !(i == 0 && !x.isStatic() && !x.special()) ? null : new R("$" + i, i == 0 ? "L" + x.owner.name + ";" : t, false);
                }
                case 'k' -> {
                    String lit = LearnReading.literal(n, n.type);
                    yield lit == null ? null : new R(lit, n.type, false);
                }
                case 'n' -> new R("null", null, false);
                default -> null;
            };
        }
    }

    /** A sum of atoms times whole numbers, plus a constant; an atom is a value's text (an old operand, "#j" the new j-th). */
    record Lin(Map<String, Long> terms, long k, String type) {

        static Lin of(long k, String type) {
            return new Lin(Map.of(), k, type);
        }

        static Lin atom(String a, String type) {
            return new Lin(Map.of(a, 1L), 0, type);
        }

        boolean numeric() {
            return type != null && "BSIJCZ".contains(type) && type.length() == 1;
        }

        Lin plus(Lin o, long sign) {
            if (o.terms.isEmpty() && o.k == 0) {
                return this;
            }
            if (!numeric() || !o.numeric()) {
                return null;
            }
            Map<String, Long> t = new TreeMap<>(terms);
            o.terms.forEach((a, c) -> t.merge(a, sign * c, Long::sum));
            t.values().removeIf(c -> c == 0);
            return new Lin(t, k + sign * o.k, type);
        }

        Lin times(long c) {
            if (!numeric()) {
                return null;
            }
            Map<String, Long> t = new TreeMap<>();
            terms.forEach((a, v) -> t.put(a, v * c));
            t.values().removeIf(v -> v == 0);
            return new Lin(t, k * c, type);
        }

        boolean same(Lin o) {
            return terms.equals(o.terms) && k == o.k;
        }

        /** As Java over the atoms: "$5 - $4", "$1". */
        String text() {
            StringBuilder s = new StringBuilder();
            List<Map.Entry<String, Long>> order = new ArrayList<>(new TreeMap<>(terms).entrySet());
            order.sort(Comparator.comparing(e -> e.getValue() < 0)); // "$5 - $4", not "-$4 + $5"
            for (var e : order) {
                long c = e.getValue();
                String a = e.getKey().matches("[$#]?[\\w.]+") ? e.getKey() : "(" + e.getKey() + ")";
                s.append(s.isEmpty() ? (c < 0 ? "-" : "") : (c < 0 ? " - " : " + "));
                s.append(Math.abs(c) == 1 ? a : Math.abs(c) + " * " + a);
            }
            if (s.isEmpty()) {
                return Long.toString(k);
            }
            if (k != 0) {
                s.append(k < 0 ? " - " : " + ").append(Math.abs(k));
            }
            return s.toString();
        }
    }

    /**
     * A kept constructor whose parameters changed, called so the object it builds is the object the old one built
     * (1.20.3's AbstractSelectionList(minecraft, width, height, y0, y1, itemHeight) became (minecraft, width, height, y,
     * itemHeight) over AbstractWidget's fields). Both constructors are run over their parameters as sums, through the
     * super constructors and this object's own methods they call, to the value each field ends with. Each field the old
     * one set is read in the new release as the learner learned the game reads it ($0.y1 -> $0.getBottom()) or, kept in
     * the same class, as itself; that reading, worked out over the new object's fields (getBottom() is getY() +
     * getHeight()), must equal what the old constructor stored, and each new parameter is solved from those equations
     * (height = y1 - y0). A field the update moved up into another class under its name (the list's height, the
     * screen's before and the widget's now) only settles what those leave open. A field with no reading may be left
     * out only when nothing in the old release used it; every new parameter must be solved, and every sure equation
     * without one must hold, else nothing is learned.
     */
    final class Built {

        final GameDiff.Meth x, y;
        final Ctx c;

        Built(GameDiff.Meth x, GameDiff.Meth y) {
            this.x = x;
            this.y = y;
            this.c = new Ctx(x, y, 0);
        }

        List<String> solve() {
            String[] tx = operands(x), ty = operands(y);
            Lin[] xa = new Lin[tx.length], ya = new Lin[ty.length];
            for (int i = 1; i < tx.length; i++) {
                xa[i] = Lin.atom("$" + i, tx[i]);
            }
            for (int j = 1; j < ty.length; j++) {
                ya[j] = Lin.atom("#" + j, ty[j]);
            }
            Map<String, Lin> was = new LinkedHashMap<>(), now = new LinkedHashMap<>();
            Set<String> opaque = new HashSet<>();
            if (!run(h.a, x, xa, was, opaque, true, 0) || !run(h.b, y, ya, now, new HashSet<>(), false, 0)) {
                why("not run");
                return null;
            }
            // The learner's readings and fields the same class kept are what the new release is shown to read; a
            // field the update moved up into another class under its name (the list's height, now the widget's)
            // may have changed meaning there, so it only settles what the others leave open.
            List<Lin[]> sure = new ArrayList<>(), named = new ArrayList<>();
            for (var e : was.entrySet()) {
                String read = reading(e.getKey());
                boolean weak = false;
                if (read == null) {
                    read = movedUp(e.getKey());
                    weak = read != null;
                }
                if (read == null) {
                    GameDiff.Meth user = user(e.getKey());
                    if (user != null && (e.getValue() != null || opaque.contains(e.getKey()))) {
                        // What was passed for it did something (1.21.2's food effects, now a consumable's), and the new
                        // release says nothing about where that went: dropping it would change what the mod does.
                        why("field " + e.getKey() + " has no reading, yet " + user.sig() + " used it");
                        return null;
                    }
                    continue; // only ever stored: what was passed for it did nothing
                }
                if (opaque.contains(e.getKey())) {
                    why("field " + e.getKey() + " holds what no sum says, and is read as " + read);
                    return null;
                }
                if (e.getValue() == null) {
                    continue; // set to a value that does not depend on the operands
                }
                Lin r = readOn(read, now, 0);
                if (r == null) {
                    why("field " + e.getKey() + ", read as " + read + ", not worked out on the new object");
                    return null;
                }
                (weak ? named : sure).add(new Lin[] {r, e.getValue()});
            }
            Map<String, Lin> sol = new HashMap<>();
            if (!settle(sure, sol, false) || !settle(named, sol, true)) {
                return null;
            }
            List<String> out = new ArrayList<>();
            out.add("$0");
            for (int j = 1; j < ty.length; j++) {
                Lin v = sol.get("#" + j);
                if (v == null) {
                    why("parameter " + j + " not solved");
                    return null;
                }
                String f = ty[j].equals("Z") && v.terms().isEmpty() ? Boolean.toString(v.k() != 0)
                    : fit(new R(v.text(), v.terms().size() == 1 && v.k() == 0 && v.terms().values().iterator().next() == 1 ? v.type() : v.numeric() ? v.type() : null, false), ty[j]);
                if (f == null) {
                    why("parameter " + j + ": " + v.text() + " is not a " + ty[j]);
                    return null;
                }
                out.add(f);
            }
            return out;
        }

        /**
         * Solves each equation (the new object's reading = the old value) for the one new parameter left in it, until
         * none moves. An equation with nothing left to solve must hold; a weak one that does not is the field meaning
         * something else now, and is passed over. Every sure equation must be used.
         */
        boolean settle(List<Lin[]> left, Map<String, Lin> sol, boolean weak) {
            for (boolean moved = true; moved && !left.isEmpty();) {
                moved = false;
                List<Lin[]> next = new ArrayList<>();
                for (Lin[] q : left) {
                    Lin r = q[0];
                    for (var s : sol.entrySet()) {
                        Long cf = r == null ? null : r.terms().get(s.getKey());
                        if (cf != null) {
                            Lin rest = new Lin(without(r.terms(), s.getKey()), r.k(), r.type());
                            Lin v = s.getValue().times(cf);
                            r = v == null ? (cf == 1 && rest.terms().isEmpty() && rest.k() == 0 ? s.getValue() : null) : rest.plus(v, 1);
                        }
                    }
                    if (r == null) {
                        if (weak) {
                            moved = true;
                            continue;
                        }
                        why("an equation mixes values that are not sums");
                        return false;
                    }
                    List<String> unknown = r.terms().keySet().stream().filter(a -> a.startsWith("#")).toList();
                    if (unknown.isEmpty()) {
                        if (!r.same(q[1]) && !weak) {
                            why("the new object holds " + r.text() + " where the old one held " + q[1].text());
                            return false;
                        }
                        moved = true;
                    } else if (unknown.size() == 1 && Math.abs(r.terms().get(unknown.get(0))) == 1) {
                        String u = unknown.get(0);
                        long cu = r.terms().get(u);
                        Lin rest = new Lin(without(r.terms(), u), r.k(), r.type());
                        Lin v = q[1].plus(rest, -1);
                        v = v == null ? null : cu == 1 ? v : v.times(-1);
                        if (v == null) {
                            why("no sum solves " + u);
                            return false;
                        }
                        sol.put(u, v);
                        moved = true;
                    } else {
                        next.add(q);
                    }
                }
                left = next;
            }
            if (!left.isEmpty() && !weak) {
                why(left.size() + " equations left");
                return false;
            }
            return true;
        }

        /**
         * a's field key as the new object has it in a class above its own ("height", the widget's now): where the route
         * map takes it, else the field of its name and type the new object resolves, as a mod's own read of it links.
         */
        String movedUp(String key) {
            int dot = key.lastIndexOf('.');
            GameDiff.Fld f = h.a.field(key.substring(0, dot), key.substring(dot + 1));
            GameDiff.Fld fy = f == null ? null : h.fAB.get(f);
            if (fy == null && f != null) {
                String at = fieldKey(h.b, y.owner.name, f.name);
                GameDiff.Fld same = at == null ? null : h.b.field(at.substring(0, at.lastIndexOf('.')), f.name);
                fy = same != null && same.desc.equals(LearnedCarry.descAB(h, f.desc)) ? same : null;
            }
            return fy != null && (fy.owner.name + "." + fy.name).equals(fieldKey(h.b, y.owner.name, fy.name)) ? fy.name : null;
        }

        /** A method of the old release, other than a constructor, that uses a's field key. */
        GameDiff.Meth user(String key) {
            if (users == null) {
                users = new HashMap<>();
                for (GameDiff.Cls k : h.a.classes.values()) {
                    for (GameDiff.Meth m : k.methods.values()) {
                        for (GameDiff.Ref r : m.refs) {
                            if (r != null && r.kind() == 'f' && !m.name.equals("<init>")) {
                                users.computeIfAbsent(r.owner() + "." + r.name(), z -> new ArrayList<>()).add(m);
                            }
                        }
                    }
                }
            }
            List<GameDiff.Meth> all = users.getOrDefault(key, List.of());
            return all.isEmpty() ? null : all.get(0);
        }

        void why(String s) {
            if (!WHY.isEmpty() && x.sig().contains(WHY)) {
                System.err.println("samecall   built: " + s);
            }
        }

        static Map<String, Long> without(Map<String, Long> t, String a) {
            Map<String, Long> m = new TreeMap<>(t);
            m.remove(a);
            return m;
        }

        /** How the new release reads a's field key on the object: the learner's reading, or the field the same class kept. */
        String reading(String key) {
            String r = readings.get(key);
            if (r != null) {
                return r;
            }
            int dot = key.lastIndexOf('.');
            GameDiff.Fld f = h.a.field(key.substring(0, dot), key.substring(dot + 1));
            GameDiff.Fld fy = f == null ? null : h.fAB.get(f);
            return fy != null && h.clsBA(fy.owner.name).equals(f.owner.name) ? fy.name : null;
        }

        /** A reading ("getBottom()", "width") worked out over the new object's fields after its constructor. */
        Lin readOn(String read, Map<String, Lin> now, int depth) {
            Matcher call = Pattern.compile("(\\w+)\\(\\)").matcher(read);
            if (call.matches()) {
                GameDiff.Meth m = find(h.b, y.owner.name, call.group(1), null);
                return m == null ? null : got(h.b, m, now, depth);
            }
            if (read.matches("\\w+")) {
                String key = fieldKey(h.b, y.owner.name, read);
                GameDiff.Fld f = key == null ? null : h.b.field(key.substring(0, key.lastIndexOf('.')), read);
                return f == null ? null : field(key, f.desc, now);
            }
            return null;
        }

        /** What a field holds after the constructor: its value, or its type's zero when the constructor never set it. */
        Lin field(String key, String desc, Map<String, Lin> state) {
            if (state.containsKey(key)) {
                return state.get(key);
            }
            return desc.length() == 1 ? Lin.of(0, desc) : null;
        }

        /** What a method of this object that only returns a value gives, over the object's fields. */
        Lin got(GameDiff.Snap s, GameDiff.Meth m, Map<String, Lin> state, int depth) {
            if (depth > 4 || m.isStatic() || !MethodTypeDesc.ofDescriptor(m.desc).parameterList().isEmpty()) {
                return null;
            }
            GameDiff.Body b = learner.decode(s, m.owner.name, m.name + m.desc, false);
            if (b == null || !b.whole || b.statements.size() != 1 || !"return".equals(b.statements.get(0).value) || b.statements.get(0).kids.length != 1) {
                return null;
            }
            return value(s, m, b.statements.get(0).kids[0], new Lin[1], state, false, depth + 1);
        }

        /** m named name (with desc, or any taking nothing) as the JVM finds it from cls: its class, supers, interfaces. */
        GameDiff.Meth find(GameDiff.Snap s, String cls, String name, String desc) {
            Deque<String> todo = new ArrayDeque<>(List.of(cls));
            Set<String> seen = new HashSet<>();
            while (!todo.isEmpty()) {
                GameDiff.Cls k = s.classes.get(todo.poll());
                if (k == null || !seen.add(k.name)) {
                    continue;
                }
                for (GameDiff.Meth m : k.methods.values()) {
                    if (m.name.equals(name) && (desc == null ? m.desc.startsWith("()") : m.desc.equals(desc)) && (m.flags & 0x0400) == 0 && m.hasCode) {
                        return m;
                    }
                }
                if (k.sup != null) {
                    todo.add(k.sup);
                }
                todo.addAll(k.ifaces);
            }
            return null;
        }

        /** The declaring class and name of a field as the JVM resolves it from owner. */
        String fieldKey(GameDiff.Snap s, String owner, String name) {
            for (String o = owner; o != null;) {
                GameDiff.Cls k = s.classes.get(o);
                if (k == null) {
                    return null;
                }
                if (k.fields.containsKey(name)) {
                    return k.name + "." + name;
                }
                o = k.sup;
            }
            return null;
        }

        static boolean self(GameDiff.Node n) {
            return n.op == 'p' && Integer.valueOf(0).equals(n.value);
        }

        static boolean param(GameDiff.Node n) {
            if (n.op == 'p' && n.value instanceof Integer i && i > 0) {
                return true;
            }
            for (GameDiff.Node k : n.kids) {
                if (param(k)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Runs a constructor, or a method of the object it calls, over args, to the value each field of the object
         * ends with (null: not a sum of the operands). False when that is not one answer: a branch, a throw, a
         * call into code that cannot be read.
         */
        boolean run(GameDiff.Snap s, GameDiff.Meth m, Lin[] args, Map<String, Lin> state, Set<String> opaque, boolean old, int depth) {
            if (depth > 4) {
                return false;
            }
            GameDiff.Body b = learner.decode(s, m.owner.name, m.name + m.desc, false);
            if (b == null || !b.whole) {
                return false;
            }
            for (GameDiff.Node st : b.statements) {
                switch (String.valueOf(st.value)) {
                    case "put" -> {
                        if (st.kids.length != 2 || !self(st.kids[0])) {
                            continue; // a static field, or another object's
                        }
                        String key = fieldKey(s, st.ref.owner(), st.ref.name());
                        if (key == null) {
                            return false;
                        }
                        Lin v = value(s, m, st.kids[1], args, state, old && depth == 0, 0);
                        state.put(key, v);
                        if (v == null && param(st.kids[1])) {
                            opaque.add(key);
                        } else {
                            opaque.remove(key);
                        }
                    }
                    case "init" -> {
                        if (st.kids.length == 0 || !self(st.kids[0])) {
                            continue;
                        }
                        GameDiff.Meth callee = s.method(st.ref.owner(), "<init>", st.ref.desc());
                        if (callee == null) {
                            if (s.classes.containsKey(st.ref.owner())) {
                                return false;
                            }
                            continue; // Object's, or a library's: none of the fields read here
                        }
                        Lin[] a = new Lin[operands(callee).length];
                        for (int i = 1; i < st.kids.length && i < a.length; i++) {
                            a[i] = value(s, m, st.kids[i], args, state, old && depth == 0, 0);
                        }
                        if (!run(s, callee, a, state, opaque, old, depth + 1)) {
                            return false;
                        }
                    }
                    case "expr" -> {
                        GameDiff.Node call = st.kids.length == 1 ? st.kids[0] : null;
                        if (call != null && call.op == 'v' && call.kids.length > 0 && self(call.kids[0])) {
                            // A method of this object may set its fields: followed through, or nothing is learned.
                            GameDiff.Meth callee = find(s, call.ref.owner(), call.ref.name(), call.ref.desc());
                            if (callee == null || callee.isStatic()) {
                                return false;
                            }
                            Lin[] a = new Lin[operands(callee).length];
                            for (int i = 1; i < call.kids.length && i < a.length; i++) {
                                a[i] = value(s, m, call.kids[i], args, state, old && depth == 0, 0);
                            }
                            if (!run(s, callee, a, state, opaque, old, depth + 1)) {
                                return false;
                            }
                        }
                    }
                    case "return" -> {
                        return true;
                    }
                    case "store", "astore" -> {
                    }
                    default -> {
                        return false; // a branch or a throw: which fields end set is not one answer
                    }
                }
            }
            return true;
        }

        /** A value of m's body as a sum over args and the object's fields; the old constructor's own other values as their text. */
        Lin value(GameDiff.Snap s, GameDiff.Meth m, GameDiff.Node n, Lin[] args, Map<String, Lin> state, boolean text, int depth) {
            Lin v = sum(s, m, n, args, state, depth);
            if (v == null && text) {
                R r = c.ofX(n);
                v = r == null ? null : Lin.atom(r.text(), r.type());
            }
            return v;
        }

        Lin sum(GameDiff.Snap s, GameDiff.Meth m, GameDiff.Node n, Lin[] args, Map<String, Lin> state, int depth) {
            if (depth > 8) {
                return null;
            }
            switch (n.op) {
                case 'p' -> {
                    int i = operand(m, n.value);
                    return i <= 0 || i >= args.length ? null : args[i];
                }
                case 'k' -> {
                    if (n.value instanceof Integer || n.value instanceof Long || n.value instanceof Short || n.value instanceof Byte) {
                        return Lin.of(((Number) n.value).longValue(), n.type);
                    }
                    String lit = LearnReading.literal(n, n.type);
                    return lit == null ? null : Lin.atom(lit, n.type);
                }
                case 'f' -> {
                    if (n.kids.length != 1 || !self(n.kids[0])) {
                        return null;
                    }
                    String key = fieldKey(s, n.ref.owner(), n.ref.name());
                    return key == null ? null : field(key, n.ref.desc(), state);
                }
                case 'v' -> {
                    if (n.kids.length != 1 || !self(n.kids[0])) {
                        return null;
                    }
                    GameDiff.Meth g = find(s, n.ref.owner(), n.ref.name(), n.ref.desc());
                    return g == null ? null : got(s, g, state, depth + 1);
                }
                case 'x' -> {
                    Lin a = n.kids.length == 1 ? sum(s, m, n.kids[0], args, state, depth + 1) : null;
                    return a != null && a.numeric() && "IJ".contains(n.type) && n.type.length() == 1 ? new Lin(a.terms(), a.k(), n.type) : null;
                }
                case 'o' -> {
                    String op = String.valueOf(n.value);
                    if (n.kids.length == 1 && (op.equals("INEG") || op.equals("LNEG"))) {
                        Lin a = sum(s, m, n.kids[0], args, state, depth + 1);
                        return a == null ? null : a.times(-1);
                    }
                    if (n.kids.length != 2) {
                        return null;
                    }
                    Lin a = sum(s, m, n.kids[0], args, state, depth + 1), b = sum(s, m, n.kids[1], args, state, depth + 1);
                    if (a == null || b == null) {
                        return null;
                    }
                    return switch (op) {
                        case "IADD", "LADD" -> a.plus(b, 1);
                        case "ISUB", "LSUB" -> a.plus(b, -1);
                        case "IMUL", "LMUL" -> b.terms().isEmpty() ? a.times(b.k()) : a.terms().isEmpty() ? b.times(a.k()) : null;
                        default -> null;
                    };
                }
                default -> {
                    return null;
                }
            }
        }
    }

    List<String> work(GameDiff.Meth x, GameDiff.Meth y, int depth) {
        GameDiff.Body bx = learner.decode(h.a, x.owner.name, x.name + x.desc, false), by = learner.decode(h.b, y.owner.name, y.name + y.desc, false);
        if (bx == null || by == null || !bx.whole || !by.whole || bx.statements.size() > 400 || by.statements.size() > 400) {
            return null;
        }
        List<GameDiff.Node> xs = bx.statements, ys = by.statements;
        if (!WHY.isEmpty() && x.sig().contains(WHY)) {
            xs.forEach(n -> System.err.println("samecall   old " + RetypedLearner.show(n, 0)));
            ys.forEach(n -> System.err.println("samecall   new " + RetypedLearner.show(n, 0)));
        }
        int[][] lcs = new int[xs.size() + 1][ys.size() + 1];
        for (int i = xs.size() - 1; i >= 0; i--) {
            for (int j = ys.size() - 1; j >= 0; j--) {
                lcs[i][j] = key(xs.get(i)).equals(key(ys.get(j))) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        Ctx c = new Ctx(x, y, depth);
        List<GameDiff.Node> extra = new ArrayList<>();
        int i = 0, j = 0;
        while (i < xs.size() || j < ys.size()) {
            if (i < xs.size() && j < ys.size() && key(xs.get(i)).equals(key(ys.get(j)))) {
                if (!match(xs.get(i), ys.get(j), c)) {
                    if (!WHY.isEmpty() && x.sig().contains(WHY)) {
                        System.err.println("samecall   no match at statement " + i + ": " + key(xs.get(i)));
                    }
                    return null;
                }
                i++;
                j++;
            } else if (j < ys.size() && (i == xs.size() || lcs[i][j + 1] >= lcs[i + 1][j])) {
                // A statement only the new body has: a local it keeps (a value it reads again later) and nothing else.
                if (!"store".equals(ys.get(j).value)) {
                    return null;
                }
                extra.add(ys.get(j++));
            } else {
                return null;
            }
        }
        String[] ty = operands(y);
        if (!WHY.isEmpty() && x.sig().contains(WHY)) {
            System.err.println("samecall   read " + c.read);
        }
        List<String> out = new ArrayList<>();
        for (int k = 0; k < ty.length; k++) {
            Set<R> rs = c.read.get(k);
            if (k == 0) {
                if (rs != null && !rs.stream().allMatch(r -> r.text().equals("$0"))) {
                    return null;
                }
                out.add("$0");
                continue;
            }
            if (rs == null) {
                int kk = k;
                if (extra.stream().anyMatch(n -> reads(n, y, kk))) {
                    return null;
                }
                out.add(zero(ty[k]));
                continue;
            }
            if (rs.size() != 1) {
                return null;
            }
            String v = fit(rs.iterator().next(), ty[k]);
            if (v == null) {
                return null;
            }
            out.add(v);
        }
        return out;
    }

    boolean reads(GameDiff.Node n, GameDiff.Meth m, int k) {
        if (n.op == 'p' && operand(m, n.value) == k) {
            return true;
        }
        for (GameDiff.Node kid : n.kids) {
            if (reads(kid, m, k)) {
                return true;
            }
        }
        return false;
    }

    static String key(GameDiff.Node n) {
        GameDiff.Node last = n.kids.length == 0 ? null : n.kids[n.kids.length - 1];
        return n.op + "|" + n.value + "|" + (n.ref == null ? "" : n.ref.name()) + "|" + (last == null || last.ref == null ? "" : last.ref.name());
    }

    boolean match(GameDiff.Node xn, GameDiff.Node yn, Ctx c) {
        if (yn.op == 'p') {
            int j = operand(c.y, yn.value);
            if (j == 0 && xn.op == 'p' && operand(c.x, xn.value) == 0) {
                return true; // the receiver, or a constructor's own this
            }
            R r = j < 0 ? null : c.ofX(xn);
            if (r == null) {
                return false;
            }
            c.read(j, r);
            return true;
        }
        if (xn.op == 'k' && yn.op == 'x' && yn.kids.length == 1) {
            return match(xn, yn.kids[0], c); // a widened value where the old body had the constant
        }
        if (xn.op == 'k' && yn.op == 'o' && ("IADD".equals(yn.value) || "ISUB".equals(yn.value)) && yn.kids.length == 2 && xn.value instanceof Number k0
            && k0.doubleValue() == Math.rint(k0.doubleValue())) {
            GameDiff.Node a = yn.kids[0], b = yn.kids[1];
            boolean add = "IADD".equals(yn.value);
            GameDiff.Node p = a.op == 'p' && b.op == 'k' ? a : add && b.op == 'p' && a.op == 'k' ? b : null;
            GameDiff.Node k = p == a ? b : a;
            int j = p == null ? -1 : operand(c.y, p.value);
            if (j < 0 || !(k.value instanceof Integer kv)) {
                return false;
            }
            long v = add ? k0.longValue() - kv : k0.longValue() + kv;
            if (v != (int) v) {
                return false;
            }
            c.read(j, new R(Long.toString(v), "I", true));
            return true;
        }
        if (xn.op != yn.op || !Objects.equals(xn.value, yn.value)) {
            return false;
        }
        int same = xn.op == 'y' && xn.ref != null && yn.ref != null && xn.ref.name().equals(yn.ref.name())
            && h.clsBA(yn.ref.owner()).equals(xn.ref.owner()) ? 0 : same(xn.ref, yn.ref);
        if (same < 0) {
            return false;
        }
        // A lambda is the new code's own: only what it captures is compared, each value as its new type wants.
        if (same == 1 && xn.op != 'y') {
            return callee(xn, yn, c);
        }
        if (xn.kids.length != yn.kids.length) {
            return false;
        }
        for (int i = 0; i < xn.kids.length; i++) {
            if (!match(xn.kids[i], yn.kids[i], c)) {
                return false;
            }
        }
        return true;
    }

    /** A call to a method that changed the same way: its parameters solved first, then read through its arguments. */
    boolean callee(GameDiff.Node xn, GameDiff.Node yn, Ctx c) {
        GameDiff.Meth cx = h.a.method(xn.ref.owner(), xn.ref.name(), xn.ref.desc()), cy = h.b.method(yn.ref.owner(), yn.ref.name(), yn.ref.desc());
        List<String> sub = cx == null || cy == null ? null : solve(cx, cy, c.depth + 1);
        if (sub == null) {
            return false;
        }
        // Kids are the callee's operands: a receiver first for an instance call or a constructor's own init.
        int shift = xn.op == 's' || xn.op == 'w' ? 1 : 0;
        int yShift = yn.op == 's' || yn.op == 'w' ? 1 : 0;
        for (int q = 0; q < yn.kids.length; q++) {
            String s = sub.get(q + yShift);
            Matcher one = Pattern.compile("\\$(\\d+)").matcher(s);
            if (s.matches("\\$\\d+")) {
                int i = Integer.parseInt(s.substring(1)) - shift;
                if (i < 0 || i >= xn.kids.length || !match(xn.kids[i], yn.kids[q], c)) {
                    return false;
                }
                continue;
            }
            GameDiff.Node yk = yn.kids[q];
            if (yk.op != 'p') {
                return false;
            }
            StringBuilder text = new StringBuilder();
            while (one.find()) {
                int i = Integer.parseInt(one.group(1)) - shift;
                R r = i < 0 || i >= xn.kids.length ? null : c.ofX(xn.kids[i]);
                if (r == null) {
                    return false;
                }
                one.appendReplacement(text, Matcher.quoteReplacement(r.text()));
            }
            one.appendTail(text);
            int j = operand(c.y, yk.value);
            if (j < 0) {
                return false;
            }
            c.read(j, new R(text.toString(), operands(cy)[q + yShift], true));
        }
        return true;
    }

    /** 0 the same member, 1 a method the hop linked whose parameters changed, -1 another. */
    int same(GameDiff.Ref xr, GameDiff.Ref yr) {
        if (xr == null || yr == null) {
            return xr == yr ? 0 : -1;
        }
        if (xr.kind() != yr.kind()) {
            return -1;
        }
        if (xr.kind() == 'm') {
            GameDiff.Meth mx = h.a.method(xr.owner(), xr.name(), xr.desc()), my = h.b.method(yr.owner(), yr.name(), yr.desc());
            if (mx != null && my != null && h.mAB.get(mx) == my) {
                return h.descBA(my.desc).equals(mx.desc) ? 0 : 1;
            }
        } else if (xr.kind() == 'f') {
            // The same field, retyped with the parameter it holds (TextureStateShard.blur, a boolean, is a TriState):
            // what is written there is compared, each value as its new type wants.
            GameDiff.Fld fx = h.a.field(xr.owner(), xr.name()), fy = h.b.field(yr.owner(), yr.name());
            if (fx != null && fy != null && h.fAB.get(fx) == fy) {
                return 0;
            }
        }
        boolean named = h.clsBA(yr.owner()).equals(xr.owner()) && Objects.equals(xr.name(), yr.name());
        return named && (xr.desc() == null || h.descBA(yr.desc()).equals(xr.desc())) ? 0 : -1;
    }

    /** r as a value of type want (b's names): as it is, or made with want's constructor or one static factory. */
    String fit(R r, String want) {
        String have = r.type() == null ? null : r.inB() ? r.type() : LearnedCarry.descAB(h, r.type());
        if (have == null) {
            return want.startsWith("L") || want.startsWith("[") ? r.text() : null;
        }
        if (have.equals(want) || want.startsWith("L") && have.startsWith("L") && Exposure.assignable(h.b, dot(have), dot(want))) {
            return r.text();
        }
        if (want.startsWith("L") && have.startsWith("L") && Exposure.assignable(h.b, dot(want), dot(have))) {
            // The new method takes a narrower type of the same value (Level -> ServerLevel in 1.21.2's server-only
            // methods): every call the new game makes passes one, so the old value is that type where it is called.
            return "(" + dot(want) + ") " + r.text();
        }
        if (have.length() == 1 && want.length() == 1) {
            String order = "BSIJFD";
            return order.indexOf(have.charAt(0)) >= 0 && order.indexOf(want.charAt(0)) > order.indexOf(have.charAt(0)) ? r.text() : null;
        }
        GameDiff.Cls into = want.startsWith("L") ? h.b.classes.get(want.substring(1, want.length() - 1)) : null;
        if (into == null) {
            return null;
        }
        List<String> ways = new ArrayList<>();
        for (GameDiff.Meth m : into.methods.values()) {
            if ((m.flags & GameDiff.ACC_PUBLIC) == 0 || (m.flags & GameDiff.ACC_SYNTHETIC) != 0) {
                continue;
            }
            List<ClassDesc> ps = MethodTypeDesc.ofDescriptor(m.desc).parameterList();
            if (ps.size() != 1 || !(ps.get(0).descriptorString().equals(have)
                || have.startsWith("L") && ps.get(0).descriptorString().startsWith("L") && Exposure.assignable(h.b, dot(have), dot(ps.get(0).descriptorString())))) {
                continue;
            }
            if (m.name.equals("<init>") && (into.flags & (GameDiff.ACC_INTERFACE | 0x0400)) == 0) {
                ways.add("new " + GameLearner.dotted(into.name) + "(" + r.text() + ")");
            } else if (m.isStatic() && MethodTypeDesc.ofDescriptor(m.desc).returnType().descriptorString().equals(want) && !m.name.startsWith("lambda$")) {
                ways.add(GameLearner.dotted(into.name) + "." + m.name + "(" + r.text() + ")");
            }
        }
        return ways.size() == 1 ? ways.get(0) : null;
    }

    static String dot(String desc) {
        return desc.substring(1, desc.length() - 1).replace('/', '.');
    }

    static String zero(String type) {
        return switch (type) {
            case "Z" -> "false";
            case "J" -> "0L";
            case "F" -> "0.0F";
            case "D" -> "0.0D";
            case "B" -> "(byte) 0";
            case "S" -> "(short) 0";
            case "C" -> "(char) 0";
            case "I" -> "0";
            default -> "null";
        };
    }
}

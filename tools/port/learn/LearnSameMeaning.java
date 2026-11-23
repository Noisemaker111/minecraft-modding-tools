import java.lang.constant.*;
import java.util.*;
import java.util.regex.*;

/** A removed member whose replacement does the same thing by another route: found by what both bodies compute. */
final class LearnSameMeaning {

    final GameLearner learner;

    LearnSameMeaning(GameLearner learner) {
        this.learner = learner;
    }

    /**
     * A public method or constructor the game closed or dropped, read from what it computed: the public member of its
     * class in b that takes the same parameters and gives the same type, and whose body (with the game methods it
     * calls written in, three deep) does what the old body did. 1.21 made new ResourceLocation(ns, path) private: it
     * validated both parts and stored them, which is what fromNamespaceAndPath does once createUntrusted is written
     * in; withDefaultNamespace, which takes no namespace, and tryBuild, which checks without throwing, do less alike.
     * Taken only when it is clearly the closest (see SAME, MARGIN) and nothing was learned from the game's uses.
     */
    List<GameLearner.Learned> sameMeaning(List<GameLearner.Learned> found, StringBuilder log) {
        Set<String> have = new HashSet<>();
        for (GameLearner.Learned l : found) {
            have.add(l.owner().replace('.', '/') + "." + l.name() + l.desc());
        }
        List<GameLearner.Learned> out = new ArrayList<>();
        for (GameDiff.Cls ca : learner.h.a.classes.values()) {
            GameDiff.Cls cb = learner.h.cAB.get(ca);
            if (cb == null || (ca.flags & GameDiff.ACC_PUBLIC) == 0) {
                continue;
            }
            for (GameDiff.Meth m : ca.methods.values()) {
                if ((m.flags & (GameDiff.ACC_PUBLIC | 0x0004)) == 0 || m.name.equals("<clinit>") || !m.hasCode || have.contains(ca.name + "." + m.name + m.desc)
                    || !learner.broken(new GameDiff.Ref('m', ca.name, m.name, m.desc))) {
                    continue;
                }
                String params = m.desc.substring(0, m.desc.indexOf(')') + 1);
                String result = m.special() ? "L" + ca.name + ";" : m.desc.substring(m.desc.indexOf(')') + 1);
                long[] before = meaning(m, true);
                // A candidate is a public wrapper of the old member where b still has it (closed), taken as it is; or
                // what the game's own new code wrote at a use of it, weighed by what its body does.
                GameDiff.Meth alive = learner.h.mAB.get(m);
                int n = MethodTypeDesc.ofDescriptor(m.desc).parameterCount();
                StringJoiner args = new StringJoiner(", ");
                for (int i = 1; i <= n; i++) {
                    args.add("$" + i);
                }
                // What the game's uses wrote, choices settled every way they allow: only a plain call of a candidate
                // with the old operands in order counts (not one inside a conversion, as getPixel inside ARGB's).
                Set<String> written = new HashSet<>();
                for (Set<String> readings : learner.sites.getOrDefault(ca.name + "." + m.name + m.desc, Map.of()).values()) {
                    for (String r : readings) {
                        written.add(GameLearner.CHOICE.matcher(r).replaceAll(x -> Matcher.quoteReplacement(x.group(1).split(String.valueOf(GameLearner.OR))[0])));
                    }
                }
                GameDiff.Meth best = null;
                double top = 0, second = 0;
                for (GameDiff.Meth c : cb.methods.values()) {
                    boolean wantStatic = m.special() || m.isStatic();
                    if ((c.flags & GameDiff.ACC_PUBLIC) == 0 || c.special() || c.isStatic() != wantStatic || !c.hasCode
                        || !learner.h.descBA(c.desc).equals(params + result)) {
                        continue;
                    }
                    String call = (c.isStatic() ? cb.name.replace('/', '.') : "$0") + "." + c.name + "(" + args + ")";
                    if (alive != null && wraps(c, alive)) {
                        // It is the closed member, called with the same arguments: nothing to weigh.
                        best = c;
                        top = 1;
                        second = 0;
                        break;
                    }
                    // Where b still has the old member, only a wrapper of it is the same thing; anything else calls
                    // it and changes the result (getPixel converts what getPixelABGR returns).
                    if (alive != null || !written.contains(call) || before.length < 3) {
                        continue;
                    }
                    // Most of what the old body did, and not much else: a caller that also does it (die for
                    // dropAllDeathLoot) covers the old body but is mostly something more.
                    long[] now = meaning(c, false);
                    double s = covered(now, before) < REST ? 0 : covered(before, now);
                    if (s > top) {
                        second = top;
                        top = s;
                        best = c;
                    } else if (s > second) {
                        second = s;
                    }
                }
                if (log != null && best != null) {
                    log.append("same meaning\t").append(ca.name).append('.').append(m.name).append(m.desc).append('\t').append(best.name)
                        .append(String.format(Locale.ROOT, "\t%.2f\t%.2f\t%.2f\n", top, second, covered(meaning(best, false), before)));
                }
                if (best == null || top < SAME || top - second < MARGIN) {
                    continue;
                }
                String expr = (best.isStatic() ? cb.name.replace('/', '.') : "$0") + "." + best.name + "(" + args + ")";
                out.add(new GameLearner.Learned(ca.name.replace('/', '.'), m.name, m.desc, false, learner.h.mAB.containsKey(m), expr, 1, 1));
            }
        }
        return out;
    }

    /**
     * c's body is only `return alive(args)` (private one-line helpers written in), each argument c's own parameter in
     * order, or passed through a check that returns it unchanged: fromNamespaceAndPath(ns, path) is createUntrusted,
     * which is new ResourceLocation(assertValidNamespace(ns, path), assertValidPath(ns, path)), and each assert
     * returns what it was given. getPixel, which converts what getPixelABGR returns, is not one.
     */
    boolean wraps(GameDiff.Meth c, GameDiff.Meth alive) {
        GameDiff.Body body = learner.decode(learner.h.b, c.owner.name, c.name + c.desc, false);
        if (body == null || body.statements.size() != 1 || !"return".equals(body.statements.get(0).value) || body.statements.get(0).kids.length != 1) {
            return false;
        }
        GameDiff.Node e = body.statements.get(0).kids[0];
        for (int depth = 0; depth < 3 && e.op == 's' && !same(e.ref, alive); depth++) {
            GameDiff.Node inner = oneLine(e);
            if (inner == null) {
                return false;
            }
            e = inner;
        }
        boolean call = (e.op == 'w' || e.op == 's' || e.op == 'v') && same(e.ref, alive);
        if (!call) {
            return false;
        }
        int[] slots = slots(c);
        int first = e.op == 'v' ? 1 : 0;
        if (e.op == 'v' && !(e.kids[0].op == 'p' && e.kids[0].value.equals(0))) {
            return false;
        }
        if (e.kids.length - first != slots.length) {
            return false;
        }
        for (int i = first; i < e.kids.length; i++) {
            GameDiff.Node arg = through(e.kids[i], 0);
            if (arg == null || arg.op != 'p' || !arg.value.equals(slots[i - first])) {
                return false;
            }
        }
        return true;
    }

    boolean same(GameDiff.Ref r, GameDiff.Meth m) {
        return r != null && r.kind() == 'm' && r.owner().equals(m.owner.name) && r.name().equals(m.name) && r.desc().equals(m.desc);
    }

    /** The local slot of each parameter of m. */
    static int[] slots(GameDiff.Meth m) {
        List<ClassDesc> ps = MethodTypeDesc.ofDescriptor(m.desc).parameterList();
        int[] out = new int[ps.size()];
        int slot = m.isStatic() ? 0 : 1;
        for (int i = 0; i < out.length; i++) {
            out[i] = slot;
            slot += ps.get(i).descriptorString().matches("[JD]") ? 2 : 1;
        }
        return out;
    }

    /** A static game call whose body is one `return expr`: expr with the call's arguments for its parameters. */
    GameDiff.Node oneLine(GameDiff.Node call) {
        GameDiff.Meth callee = learner.h.b.method(call.ref.owner(), call.ref.name(), call.ref.desc());
        GameDiff.Body body = callee == null ? null : learner.decode(learner.h.b, callee.owner.name, callee.name + callee.desc, false);
        if (body == null || body.statements.size() != 1 || !"return".equals(body.statements.get(0).value) || body.statements.get(0).kids.length != 1) {
            return null;
        }
        int[] slots = slots(callee);
        Map<Object, GameDiff.Node> bySlot = new HashMap<>();
        for (int i = 0; i < slots.length; i++) {
            bySlot.put(slots[i], call.kids[i]);
        }
        return substitute(body.statements.get(0).kids[0], bySlot);
    }

    static GameDiff.Node substitute(GameDiff.Node n, Map<Object, GameDiff.Node> bySlot) {
        if (n.op == 'p') {
            return bySlot.get(n.value);
        }
        GameDiff.Node[] kids = new GameDiff.Node[n.kids.length];
        for (int i = 0; i < kids.length; i++) {
            kids[i] = substitute(n.kids[i], bySlot);
            if (kids[i] == null) {
                return null;
            }
        }
        return new GameDiff.Node(n.op, n.ref, n.value, n.type, kids);
    }

    /** A value passed through checks that return what they were given, to what it was before them; or null. */
    GameDiff.Node through(GameDiff.Node n, int depth) {
        if (n.op == 'p' || depth > 3) {
            return n.op == 'p' ? n : null;
        }
        if (n.op != 's') {
            return null;
        }
        GameDiff.Meth callee = learner.h.b.method(n.ref.owner(), n.ref.name(), n.ref.desc());
        GameDiff.Body body = callee == null ? null : learner.decode(learner.h.b, callee.owner.name, callee.name + callee.desc, false);
        if (body == null) {
            return null;
        }
        int[] slots = slots(callee);
        Object given = null;
        for (GameDiff.Node s : body.statements) {
            if (!"return".equals(s.value)) {
                continue;
            }
            if (s.kids.length != 1 || s.kids[0].op != 'p' || given != null && !given.equals(s.kids[0].value)) {
                return null;
            }
            given = s.kids[0].value;
        }
        for (int i = 0; given != null && i < slots.length; i++) {
            if (given.equals(slots[i])) {
                return through(n.kids[i], depth + 1);
            }
        }
        return null;
    }

    /**
     * How much of the old body the candidate must also do, how far ahead of the next candidate, and how much of the
     * candidate must be what the old body did.
     */
    static final double SAME = 0.8, MARGIN = 0.15, REST = 0.5;

    /** The share of what the old body did that the candidate also does (both sorted, distinct). */
    static double covered(long[] old, long[] now) {
        int i = 0, j = 0, both = 0;
        while (i < old.length && j < now.length) {
            if (old[i] == now[j]) {
                both++;
                i++;
                j++;
            } else if (old[i] < now[j]) {
                i++;
            } else {
                j++;
            }
        }
        return old.length == 0 ? 0 : (double) both / old.length;
    }

    final Map<GameDiff.Meth, long[]> meanings = new LinkedHashMap<>();

    /**
     * The distinct things a body references, with the game methods it calls written in five deep, each in a's names: a
     * wrapper one level deeper (fromNamespaceAndPath calls createUntrusted, which does the work) reads the same.
     */
    long[] meaning(GameDiff.Meth m, boolean sideA) {
        long[] known = meanings.get(m);
        if (known != null) {
            return known;
        }
        Set<Long> out = new HashSet<>();
        gather(m, sideA, 0, new HashSet<>(), out);
        long[] t = out.stream().mapToLong(Long::longValue).sorted().toArray();
        meanings.put(m, t);
        return t;
    }

    void gather(GameDiff.Meth m, boolean sideA, int depth, Set<GameDiff.Meth> seen, Set<Long> out) {
        if (!seen.add(m)) {
            return;
        }
        GameDiff.Snap s = sideA ? learner.h.a : learner.h.b;
        for (GameDiff.Ref r : m.refs) {
            out.add(GameDiff.fnv(learner.h.token(r, sideA)));
            if (r.kind() == 'm' && depth < 5) {
                GameDiff.Meth callee = s.method(r.owner(), r.name(), r.desc());
                if (callee != null && callee.hasCode) {
                    gather(callee, sideA, depth + 1, seen, out);
                }
            }
        }
    }

    /** A receiver as one value: the node itself, or the member a no-argument static call or static field read names (Tesselator.getInstance()). */
    static Object same(GameDiff.Node r) {
        return (r.op == 's' && r.kids.length == 0 || r.op == 'g') && r.ref != null ? r.op + r.ref.toString() : new IdentityKey(r);
    }

    record IdentityKey(GameDiff.Node n) {

        @Override
        public boolean equals(Object o) {
            return o instanceof IdentityKey k && k.n == n;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(n);
        }
    }
}

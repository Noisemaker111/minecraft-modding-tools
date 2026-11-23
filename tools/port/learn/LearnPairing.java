import java.util.*;

/** Two versions of one method body lined up statement by statement, so a changed use sits opposite what replaced it. */
final class LearnPairing {

    final GameLearner learner;

    LearnPairing(GameLearner learner) {
        this.learner = learner;
    }

    final Map<GameDiff.Node, Long> hashA = new IdentityHashMap<>(), hashB = new IdentityHashMap<>();
    final Map<GameDiff.Node, Boolean> holed = new IdentityHashMap<>();
    int budget;

    /** Lines up two bodies and reads a replacement at every use of a changed member that lines up; then their lambdas. */
    void pair(String ca, GameDiff.Body x, String cb, GameDiff.Body y, int depth) {
        if (x == null || y == null) {
            return;
        }
        learner.learnChains.consumers(x);
        long[] ta = new long[x.statements.size()], tb = new long[y.statements.size()];
        for (int i = 0; i < ta.length; i++) {
            ta[i] = hash(x.statements.get(i), true);
        }
        for (int j = 0; j < tb.length; j++) {
            tb[j] = hash(y.statements.get(j), false);
        }
        List<int[]> same = matches(ta, tb);
        // Lambdas are paired where the statements that make them line up; the game renumbers them freely.
        Map<String, String> lambdas = new LinkedHashMap<>();
        for (int[] p : same) {
            lambdas(x.statements.get(p[0]), y.statements.get(p[1]), lambdas);
        }
        List<int[]> bounds = new ArrayList<>(same);
        bounds.add(new int[] { ta.length, tb.length });
        int i0 = 0, j0 = 0;
        for (int[] p : bounds) {
            int na = p[0] - i0, nb = p[1] - j0;
            boolean dropped = dropped(x, i0, p[0], y, j0, p[1]);
            for (int i = i0; i < p[0] && nb > 0; i++) {
                GameDiff.Node sa = x.statements.get(i);
                if (!holed(sa) || dropped && droppable(sa)) {
                    continue;
                }
                // A statement that is only the use (a call made for what it does, a value stored) has no context to
                // line up by: only the statement in the same place of a gap of the same length counts.
                boolean bare = sa.kids.length == 1 && hole(sa.kids[0]) && (sa.value.equals("expr") || sa.value.equals("store"));
                for (int j = j0; j < p[1]; j++) {
                    // Next to the same anchor on both sides: a store against a store lines up; a call against a call is weak, read
                    // only for chains and for the value it lines up, never as a vote on one member.
                    boolean edge = (i == i0 && j == j0 || p[0] - i == 1 && p[1] - j == 1) && sa.value.equals(y.statements.get(j).value);
                    // A bare call with no operands has nothing to check a pairing by (the statement next to a dropped call reads as
                    // its replacement): such pairings are weak, and its drops come from the gap check.
                    boolean operandless = bare && sa.kids[0].kids.length == 0;
                    boolean strong = !operandless && (!bare || na == nb && j - j0 == i - i0 || nb == 1 || edge && "store".equals(sa.value));
                    edge |= operandless && (na == nb && j - j0 == i - i0 || nb == 1);
                    if (bare && !strong && !edge) {
                        continue;
                    }
                    Map<GameDiff.Node, GameDiff.Node> holes = new IdentityHashMap<>();
                    budget = 20_000;
                    if (match(sa, y.statements.get(j), holes)) {
                        learner.weak = !strong;
                        holes.forEach(learner.learnReading::read);
                        learner.weak = false;
                        lambdas(sa, y.statements.get(j), lambdas);
                    }
                }
            }
            i0 = p[0] + 1;
            j0 = p[1] + 1;
        }
        if (x.lambdas.size() == y.lambdas.size()) {
            Set<String> taken = new HashSet<>(lambdas.values());
            for (int i = 0; i < x.lambdas.size(); i++) {
                if (!lambdas.containsKey(x.lambdas.get(i)) && !taken.contains(y.lambdas.get(i))) {
                    lambdas.put(x.lambdas.get(i), y.lambdas.get(i));
                }
            }
        }
        if (depth < 3) {
            lambdas.forEach((la, lb) -> pair(ca, learner.decode(learner.h.a, ca, la, true), cb, learner.decode(learner.h.b, cb, lb, true), depth + 1));
        }
    }

    /**
     * A call the game simply removed: between two statements that line up, a's gap is b's gap plus statements that
     * are only a call of a member b no longer has, made for what it does (1.21's endVertex(), 1.19.4's
     * disableTexture()). Each such call either was dropped or lines up with a statement of b that is only its
     * receiver (the vertex chain endVertex() ended); every other statement of the gap must line up with b's, in
     * order. Each such call is a use whose reading is "nothing": empty for a void call, its receiver for one that
     * returns it.
     */
    boolean dropped(GameDiff.Body x, int i0, int i1, GameDiff.Body y, int j0, int j1) {
        boolean any = false;
        for (int i = i0; i < i1 && !any; i++) {
            any = droppable(x.statements.get(i));
        }
        long cells = (long) (i1 - i0 + 1) * (j1 - j0 + 1);
        if (!any || cells > 40_000) {
            return false;
        }
        byte[][] memo = new byte[i1 - i0 + 1][j1 - j0 + 1]; // 0 unknown, 1 explained, 2 not
        if (!explained(x, i0, i1, y, j0, j1, i0, j0, memo)) {
            return false;
        }
        List<GameDiff.Node> gone = new ArrayList<>();
        for (int i = i0, j = j0; i < i1;) {
            GameDiff.Node s = x.statements.get(i);
            if (j < j1 && pairs(s, y.statements.get(j)) && explained(x, i0, i1, y, j0, j1, i + 1, j + 1, memo)) {
                j++;
            } else if (!skippable(s)) {
                return false;
            }
            if (droppable(s)) {
                gone.add(s);
            }
            i++;
        }
        for (GameDiff.Node s : gone) {
            GameDiff.Node use = s.kids[0];
            String key = use.ref.owner() + "." + use.ref.name() + use.ref.desc();
            learner.keys.putIfAbsent(key, use.ref);
            String reading = use.type.equals("V") ? "" : use.op == 'v' && returnsReceiver(use.ref) ? GameLearner.choice(List.of("$0")) : null;
            Set<String> readings = learner.sites.computeIfAbsent(key, k -> new IdentityHashMap<>()).computeIfAbsent(use, k -> new LinkedHashSet<>());
            if (reading != null) {
                readings.add(reading);
            }
        }
        return true;
    }

    /** a's statements from i and b's from j, to the gap's ends, line up with some removed calls left out. */
    boolean explained(GameDiff.Body x, int i0, int i1, GameDiff.Body y, int j0, int j1, int i, int j, byte[][] memo) {
        if (i == i1) {
            return j == j1;
        }
        if (memo[i - i0][j - j0] != 0) {
            return memo[i - i0][j - j0] == 1;
        }
        GameDiff.Node s = x.statements.get(i);
        boolean ok = j < j1 && pairs(s, y.statements.get(j)) && explained(x, i0, i1, y, j0, j1, i + 1, j + 1, memo)
            || skippable(s) && explained(x, i0, i1, y, j0, j1, i + 1, j, memo);
        memo[i - i0][j - j0] = (byte) (ok ? 1 : 2);
        return ok;
    }

    /** s lines up with t; a removed call only with a statement that is its receiver's value, as the game wrote it. */
    boolean pairs(GameDiff.Node s, GameDiff.Node t) {
        budget = 20_000;
        if (!droppable(s)) {
            return match(s, t, new IdentityHashMap<>());
        }
        GameDiff.Node use = s.kids[0];
        if (!"expr".equals(t.value) || t.kids.length != 1 || use.op != 'v') {
            return false;
        }
        return match(use.kids[0], t.kids[0], null) || learner.learnReading.linesUpThrough(use.kids[0], t.kids[0]) || use.kids.length == 1 && learner.learnReading.endsLike(use, t.kids[0]);
    }

    /**
     * A removed call left out with nothing in b in its place: only one whose operands hold no other changed member's
     * value. Where they do, the game may have folded the call into that member's replacement (1.19.3's
     * matrix.multiply(q) after pose.copy() became new Matrix4f(pose).rotate(q)), which is not a drop.
     */
    boolean skippable(GameDiff.Node s) {
        if (!droppable(s)) {
            return false;
        }
        for (GameDiff.Node k : s.kids[0].kids) {
            if (holed(k)) {
                return false;
            }
        }
        return true;
    }
    /** A statement that is only a call, made for what it does, of a member b removed (no counterpart at all). */
    boolean droppable(GameDiff.Node s) {
        if (!"expr".equals(s.value) || s.kids.length != 1 || !hole(s.kids[0]) || s.kids[0].op != 's' && s.kids[0].op != 'v') {
            return false;
        }
        GameDiff.Meth m = learner.h.a.method(s.kids[0].ref.owner(), s.kids[0].ref.name(), s.kids[0].ref.desc());
        return m != null && !m.special() && learner.h.mAB.get(m) == null;
    }

    /** A method that gives back the object it was called on, by its type: a builder's fluent step. */
    boolean returnsReceiver(GameDiff.Ref r) {
        String ret = r.desc().substring(r.desc().indexOf(')') + 1);
        GameDiff.Meth m = learner.h.a.method(r.owner(), r.name(), r.desc());
        if (!ret.startsWith("L") || m == null || m.isStatic() || !subtypeA(r.owner(), ret.substring(1, ret.length() - 1))) {
            return false;
        }
        // Where a has the body, every return gives back `this`; an interface's abstract step is taken by its type.
        GameDiff.Body body = m.hasCode ? learner.decode(learner.h.a, m.owner.name, m.name + m.desc, false) : null;
        return !m.hasCode || body != null && body.statements.stream().filter(s -> "return".equals(s.value))
            .allMatch(s -> s.kids.length == 1 && s.kids[0].op == 'p' && s.kids[0].value.equals(0));
    }

    boolean subtypeA(String sub, String sup) {
        Deque<String> todo = new ArrayDeque<>(List.of(sub));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            String c = todo.poll();
            if (c.equals(sup)) {
                return true;
            }
            GameDiff.Cls k = learner.h.a.classes.get(c);
            if (k != null && seen.add(c)) {
                todo.addAll(GameDiff.supertypes(k));
            }
        }
        return false;
    }

    /** The lambdas two lined-up trees make at the same place (not inside a use of a changed member). */
    void lambdas(GameDiff.Node x, GameDiff.Node y, Map<String, String> out) {
        if (hole(x) || x.kids.length != y.kids.length) {
            return;
        }
        if (x.body != null && y.body != null) {
            out.putIfAbsent(x.body, y.body);
        }
        for (int i = 0; i < x.kids.length; i++) {
            lambdas(x.kids[i], y.kids[i], out);
        }
    }

    /**
     * Index pairs of statements that are the same on both sides, in order: common start and end, then the longest
     * common subsequence when that is small enough, else statements found exactly once on each side as anchors (in
     * increasing order on both), and the same again between anchors.
     */
    static List<int[]> matches(long[] ta, long[] tb) {
        List<int[]> out = new ArrayList<>();
        matches(ta, 0, ta.length, tb, 0, tb.length, out, 0);
        return out;
    }

    static void matches(long[] ta, int a0, int a1, long[] tb, int b0, int b1, List<int[]> out, int depth) {
        List<int[]> tail = new ArrayList<>();
        while (a0 < a1 && b0 < b1 && ta[a0] == tb[b0]) {
            out.add(new int[] { a0++, b0++ });
        }
        while (a0 < a1 && b0 < b1 && ta[a1 - 1] == tb[b1 - 1]) {
            tail.add(0, new int[] { --a1, --b1 });
        }
        int n = a1 - a0, m = b1 - b0;
        if (n > 0 && m > 0 && (long) n * m <= 250_000) {
            int[][] l = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; i--) {
                for (int j = m - 1; j >= 0; j--) {
                    l[i][j] = ta[a0 + i] == tb[b0 + j] ? l[i + 1][j + 1] + 1 : Math.max(l[i + 1][j], l[i][j + 1]);
                }
            }
            for (int i = 0, j = 0; i < n && j < m;) {
                if (ta[a0 + i] == tb[b0 + j]) {
                    out.add(new int[] { a0 + i++, b0 + j++ });
                } else if (l[i + 1][j] >= l[i][j + 1]) {
                    i++;
                } else {
                    j++;
                }
            }
        } else if (n > 0 && m > 0 && depth < 8) {
            Map<Long, int[]> once = new HashMap<>(); // hash -> {count a, index a, count b, index b}
            for (int i = a0; i < a1; i++) {
                int[] c = once.computeIfAbsent(ta[i], k -> new int[4]);
                c[0]++;
                c[1] = i;
            }
            for (int j = b0; j < b1; j++) {
                int[] c = once.computeIfAbsent(tb[j], k -> new int[4]);
                c[2]++;
                c[3] = j;
            }
            List<int[]> anchors = new ArrayList<>();
            for (int[] c : once.values()) {
                if (c[0] == 1 && c[2] == 1) {
                    anchors.add(new int[] { c[1], c[3] });
                }
            }
            anchors.sort(Comparator.comparingInt(p -> p[0]));
            // Longest run increasing on b as well (patience sorting).
            int[] prev = new int[anchors.size()];
            List<Integer> piles = new ArrayList<>();
            for (int k = 0; k < anchors.size(); k++) {
                int lo = 0, hi = piles.size();
                while (lo < hi) {
                    int mid = (lo + hi) >>> 1;
                    if (anchors.get(piles.get(mid))[1] < anchors.get(k)[1]) {
                        lo = mid + 1;
                    } else {
                        hi = mid;
                    }
                }
                prev[k] = lo == 0 ? -1 : piles.get(lo - 1);
                if (lo == piles.size()) {
                    piles.add(k);
                } else {
                    piles.set(lo, k);
                }
            }
            List<int[]> chain = new ArrayList<>();
            for (int k = piles.isEmpty() ? -1 : piles.get(piles.size() - 1); k >= 0; k = prev[k]) {
                chain.add(0, anchors.get(k));
            }
            int i = a0, j = b0;
            for (int[] p : chain) {
                matches(ta, i, p[0], tb, j, p[1], out, depth + 1);
                out.add(p);
                i = p[0] + 1;
                j = p[1] + 1;
            }
            if (!chain.isEmpty()) {
                matches(ta, i, a1, tb, j, b1, out, depth + 1);
            }
        }
        out.addAll(tail);
    }

    boolean hole(GameDiff.Node n) {
        return (n.op == 'g' || n.op == 'f' || n.op == 's' || n.op == 'v' || n.op == 'w') && learner.broken(n.ref);
    }

    boolean holed(GameDiff.Node n) {
        Boolean known = holed.get(n);
        if (known != null) {
            return known;
        }
        boolean out = hole(n);
        for (int i = 0; i < n.kids.length && !out; i++) {
            out = holed(n.kids[i]);
        }
        holed.put(n, out);
        return out;
    }

    String head(GameDiff.Node n, boolean sideA) {
        return switch (n.op) {
            case 'g', 'f', 's', 'v', 'w', 't' -> learner.h.token(n.ref, sideA);
            case 'y' -> (n.ref == null ? "lambda" : learner.h.token(n.ref, sideA)) + n.value;
            case 'k' -> n.type + ":" + n.value;
            case 'x' -> n.type;
            case 'o', 'S' -> n.value + (n.ref == null ? "" : learner.h.token(n.ref, sideA));
            case 'p', 'l' -> n.value + ":" + (sideA ? n.type : learner.h.descBA(n.type));
            default -> "";
        };
    }

    /** A tree's hash in a's names; a use of a changed member hashes to itself alone, so it never equals b's code. */
    long hash(GameDiff.Node n, boolean sideA) {
        Map<GameDiff.Node, Long> memo = sideA ? hashA : hashB;
        Long known = memo.get(n);
        if (known != null) {
            return known;
        }
        long x;
        if (sideA && hole(n) || BodyDecoder.phi(n)) {
            x = 0x9E3779B97F4A7C15L * (System.identityHashCode(n) + 1L) + (sideA ? 0 : 1);
        } else {
            x = GameDiff.fnv(n.op + head(n, sideA));
            for (GameDiff.Node k : n.kids) {
                x = x * 1_000_003L + hash(k, sideA);
            }
        }
        memo.put(n, x);
        return x;
    }

    /** a's x and b's y are the same code but where x uses a changed member; holes gets each such use and b's code there. */
    boolean match(GameDiff.Node x, GameDiff.Node y, Map<GameDiff.Node, GameDiff.Node> holes) {
        if (--budget < 0) {
            return false;
        }
        if (holes != null && hole(x)) {
            GameDiff.Node had = holes.putIfAbsent(x, y);
            return had == null || had == y;
        }
        if (BodyDecoder.phi(x) || BodyDecoder.phi(y)) {
            return false;
        }
        if (hash(x, true) == hash(y, false)) {
            return true;
        }
        if (x.op != y.op || x.kids.length != y.kids.length || !head(x, true).equals(head(y, false))) {
            return false;
        }
        for (int i = 0; i < x.kids.length; i++) {
            if (!match(x.kids[i], y.kids[i], holes)) {
                return false;
            }
        }
        return true;
    }
}

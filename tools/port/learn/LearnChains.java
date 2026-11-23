import java.util.*;
import java.util.regex.*;

/** Builder chains the game reshaped: the calls that follow a changed call on the same value, read together. */
final class LearnChains {

    final GameLearner learner;

    LearnChains(GameLearner learner) {
        this.learner = learner;
    }

    /**
     * Two changed members on one value, replaced together: the value of X used only as operand `at` of Y (0 is Y's
     * receiver). "last": Y gives the value (1.19.3's Vector3f.XP.rotationDegrees(f) is Axis.XP.rotationDegrees(f); 1.21's
     * MultiBufferSource.immediate(tesselator.getBuilder()) is immediate(new ByteBufferBuilder(1536))); "first": Y is a
     * void call on X's value and X's value is what b computes (1.21's tesselator.getBuilder() then
     * builder.begin(mode, format) is tesselator.begin(mode, format)). The reading is written over X's operands, then
     * Y's others, from $1.
     */
    record Chained(GameDiff.Ref x, GameDiff.Ref y, int at, boolean first, String expr, int votes, int of) {

        String json(String step) {
            String q = VersionMap.Diff.q(x.owner().replace('/', '.'));
            return "{ \"step\": " + VersionMap.Diff.q(step) + ", \"owner\": " + q + ", \"from\": " + VersionMap.Diff.q(x.name())
                + ", \"desc\": " + VersionMap.Diff.q(x.kind() == 'f' ? "" : x.desc()) + ", \"field\": " + (x.kind() == 'f') + ", \"linked\": false"
                + ", \"expr\": " + VersionMap.Diff.q(expr) + ", \"votes\": " + votes + ", \"of\": " + of
                + ", \"then\": { \"owner\": " + VersionMap.Diff.q(y.owner().replace('/', '.')) + ", \"from\": " + VersionMap.Diff.q(y.name())
                + ", \"desc\": " + VersionMap.Diff.q(y.desc()) + ", \"at\": " + at + " }, \"result\": " + VersionMap.Diff.q(at == -2 ? "late" : at < 0 ? "sibling" : first ? "first" : "last") + " }";
        }
    }

    final Map<GameDiff.Node, List<GameDiff.Node>> consumers = new IdentityHashMap<>(); // a changed member's value -> void changed calls made on it
    final Map<String, Map<GameDiff.Node, Set<String>>> chainSites = new HashMap<>();
    final Map<Object, List<GameDiff.Node>> byReceiver = new HashMap<>(); // (receiver, body) -> the changed calls made on it that give a value
    final Map<GameDiff.Node, GameDiff.Body> bodyOf = new IdentityHashMap<>();
    final Map<GameDiff.Node, Map<GameDiff.Node, GameDiff.Node>> siblingUse = new IdentityHashMap<>();

    static final Pattern MADE = Pattern.compile("(?:new\\s+[\\w.]+|(?<![\\w$])[A-Za-z_]\\w*\\.\\w+)\\([^()$]*\\)");

    /** A reading that leaves out an operand depending on the path taken, or holding a changed member b showed nothing for: abstain. */
    boolean unsure(String r, GameDiff.Node[] ops) {
        for (int i = 0; i < ops.length; i++) {
            if ((learner.learnReading.merged(ops[i]) || learner.learnPairing.holed(ops[i]) && !learner.lined.containsKey(ops[i])) && (r == null || !Pattern.compile("\\$" + (i + 1) + "(?!\\d)").matcher(r).find() && MADE.matcher(r).find())) {
                return true;
            }
        }
        return false;
    }

    void siblingRead(GameDiff.Node x, GameDiff.Node y, GameDiff.Node m) {
        if (!learner.learnReading.fits(m.type, y.type)) {
            return;
        }
        GameDiff.Node[] ops = new GameDiff.Node[y.kids.length + 1];
        ops[0] = y.kids[0];
        ops[1] = x;
        System.arraycopy(y.kids, 1, ops, 2, y.kids.length - 1);
        GameDiff.Node site = siblingUse.computeIfAbsent(y, k -> new IdentityHashMap<>()).computeIfAbsent(x, k -> new GameDiff.Node('s', null, null, y.type, ops));
        String key = x.ref.owner() + "." + x.ref.name() + x.ref.desc() + " " + y.ref.owner() + "." + y.ref.name() + y.ref.desc() + " -1 sibling";
        chainRefs.putIfAbsent(key, new Object[] { x.ref, y.ref, -1, 1 });
        Set<String> readings = chainSites.computeIfAbsent(key, k -> new IdentityHashMap<>()).computeIfAbsent(site, k -> new LinkedHashSet<>());
        String r = learner.learnReading.express(m, site.kids, 1, y.type, 0);
        // Without X's value the reading shows b making its own, not what became of X's: it says nothing either way.
        if (unsure(r, site.kids) || r != null && !Pattern.compile("\\$2(?!\\d)").matcher(r).find()) {
            chainSites.get(key).remove(site);
            return;
        }
        if (r != null) {
            readings.add(r);
        }
    }
    final Map<String, Object[]> chainRefs = new HashMap<>(); // key -> {X, Y, at}
    final Map<GameDiff.Node, GameDiff.Node> chainUse = new IdentityHashMap<>(); // Y -> one use standing for both calls, over their operands
    final List<Chained> chains = new ArrayList<>();

    /** The void changed calls a body makes on a changed member's value, as chains read them. */
    void consumers(GameDiff.Body x) {
        Set<GameDiff.Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<GameDiff.Node> todo = new ArrayDeque<>(x.statements);
        while (!todo.isEmpty()) {
            GameDiff.Node n = todo.pop();
            if (seen.add(n)) {
                bodyOf.putIfAbsent(n, x);
                if (n.op == 'v' && !n.type.equals("V") && n.kids.length > 0 && chainable(n)) {
                    byReceiver.computeIfAbsent(List.of(LearnSameMeaning.same(n.kids[0]), System.identityHashCode(x)), k -> new ArrayList<>()).add(n);
                }
                todo.addAll(List.of(n.kids));
            }
        }
        for (GameDiff.Node s : x.statements) {
            if ("expr".equals(s.value) && s.kids.length == 1) {
                GameDiff.Node c = s.kids[0];
                if (c.op == 'v' && c.type.equals("V") && learner.learnPairing.hole(c) && c.kids.length > 0 && chainable(c.kids[0])) {
                    consumers.computeIfAbsent(c.kids[0], k -> new ArrayList<>()).add(c);
                }
            }
        }
    }

    boolean chainable(GameDiff.Node x) {
        return "svgf".indexOf(x.op) >= 0 && learner.learnPairing.hole(x) && !x.ref.name().startsWith("<");
    }

    void chain(GameDiff.Node use, GameDiff.Node m) {
        late(use, m);
        // "sibling": a call on the receiver an earlier changed call was made on, read with that call's value among its
        // operands (1.21's tesselator.end() after tesselator.getBuilder() is BufferUploader.drawWithShader(builder.buildOrThrow())).
        if (use.op == 'v' && learner.learnPairing.hole(use) && use.kids.length > 0) {
            GameDiff.Body in = bodyOf.get(use);
            for (GameDiff.Node x : in == null ? List.<GameDiff.Node>of() : byReceiver.getOrDefault(List.of(LearnSameMeaning.same(use.kids[0]), System.identityHashCode(in)), List.<GameDiff.Node>of())) {
                if (x != use && x.at >= 0 && use.at > x.at && !x.ref.equals(use.ref)) {
                    siblingRead(x, use, m);
                }
            }
        }
        if ((use.op == 'v' || use.op == 's') && !use.type.equals("V")) {
            for (int k = 0; k < use.kids.length; k++) {
                if (chainable(use.kids[k])) {
                    chainRead(use.kids[k], use, k, m, use.type);
                }
            }
        }
        for (GameDiff.Node y : consumers.getOrDefault(use, List.of())) {
            chainRead(use, y, 0, m, use.type);
        }
    }

    /**
     * "late": a value the new release takes where the object is made, which old code set later on the same object.
     * 1.21.11's CycleButton.builder(f) then withInitialValue(v), after other calls that give the builder back
     * (withValues(...)), is CycleButton.builder(f, v), and withInitialValue is gone. Y, a changed call on a value
     * made by X (a changed call) through calls that return their receiver; b's node in Y's place is the same calls
     * on X's new call, which is read over both calls' operands.
     */
    void late(GameDiff.Node y, GameDiff.Node m) {
        if (y.op != 'v' || !learner.learnPairing.hole(y) || y.kids.length < 2 || y.type.equals("V") || !y.type.equals(y.kids[0].type)) {
            return;
        }
        // The setter is gone: its value has nowhere to go but X's new call. One the update kept under a new signature
        // (1.21.5's getCompound(key) on a tag another getCompound gave) is its own reading, not a value that moved.
        GameDiff.Meth setter = learner.h.a.method(y.ref.owner(), y.ref.name(), y.ref.desc());
        if (setter != null && learner.h.mAB.containsKey(setter)) {
            return;
        }
        GameDiff.Node x = y.kids[0], now = m;
        while (x.op == 'v' && !learner.learnPairing.hole(x) && x.kids.length > 0 && x.type.equals(x.kids[0].type)) {
            if (now.op != 'v' || now.ref == null || !now.ref.name().equals(x.ref.name()) || now.kids.length != x.kids.length) {
                return;
            }
            x = x.kids[0];
            now = now.kids[0];
        }
        if (!chainable(x) || x.op != 's' && x.op != 'v' || now.op != x.op || now.ref == null || x.at < 0 || y.at < x.at) {
            return;
        }
        GameDiff.Node[] ops = new GameDiff.Node[x.kids.length + y.kids.length - 1];
        System.arraycopy(x.kids, 0, ops, 0, x.kids.length);
        System.arraycopy(y.kids, 1, ops, x.kids.length, y.kids.length - 1);
        String made = x.type;
        GameDiff.Node site = lateUse.computeIfAbsent(y, k -> new GameDiff.Node('s', null, null, made, ops));
        String key = x.ref.owner() + "." + x.ref.name() + x.ref.desc() + " " + y.ref.owner() + "." + y.ref.name() + y.ref.desc() + " -2 late";
        chainRefs.putIfAbsent(key, new Object[] { x.ref, y.ref, -2, x.kids.length });
        Set<String> readings = chainSites.computeIfAbsent(key, k -> new IdentityHashMap<>()).computeIfAbsent(site, k -> new LinkedHashSet<>());
        String r = learner.learnReading.express(now, site.kids, 1, x.type, 0);
        if (r != null) {
            readings.add(r);
        }
    }

    final Map<GameDiff.Node, GameDiff.Node> lateUse = new IdentityHashMap<>(); // Y -> one use standing for X and Y of a late chain

    void chainRead(GameDiff.Node x, GameDiff.Node y, int at, GameDiff.Node m, String type) {
        if (!learner.learnReading.fits(m.type, type)) {
            return;
        }
        GameDiff.Node[] ops = new GameDiff.Node[x.kids.length + y.kids.length - 1];
        System.arraycopy(x.kids, 0, ops, 0, x.kids.length);
        for (int i = 0, j = x.kids.length; i < y.kids.length; i++) {
            if (i != at) {
                ops[j++] = y.kids[i];
            }
        }
        GameDiff.Node site = chainUse.computeIfAbsent(y, k -> new GameDiff.Node('s', null, null, type, ops));
        boolean first = y.type.equals("V");
        String key = x.ref.owner() + "." + x.ref.name() + x.ref.desc() + " " + y.ref.owner() + "." + y.ref.name() + y.ref.desc() + " " + at + (first ? " first" : " last");
        chainRefs.putIfAbsent(key, new Object[] { x.ref, y.ref, at, x.kids.length });
        Set<String> readings = chainSites.computeIfAbsent(key, k -> new IdentityHashMap<>()).computeIfAbsent(site, k -> new LinkedHashSet<>());
        String r = learner.learnReading.express(m, site.kids, 1, type, 0);
        if (unsure(r, site.kids)) {
            chainSites.get(key).remove(site);
            return;
        }
        if (r != null) {
            readings.add(r);
        }
    }

    /**
     * Each chain the game's uses agree on: two or more of them, as many as for one member's reading, and not a reading
     * shown only for literal arguments. A "first" chain must use every argument of Y: Y's effect may not be lost (where
     * b applies it elsewhere, X's value alone reads as something that leaves it out). Operands a "last" chain leaves out
     * are still computed where they were.
     */
    void voteChains(StringBuilder log) {
        for (var e : chainSites.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue; // every use abstained
            }
            int[] tally = new int[2];
            String best = LearnVotes.vote(e.getValue().values(), tally, true);
            Object[] r = chainRefs.get(e.getKey());
            // A late chain keeps Y's value too: it is what moved into X's new call.
            boolean first = e.getKey().endsWith(" first") || e.getKey().endsWith(" sibling") || e.getKey().endsWith(" late");
            int n = e.getValue().keySet().iterator().next().kids.length;
            boolean all = best != null;
            for (int i = first ? (int) r[3] + 1 : n + 1; all && i <= n; i++) {
                all = Pattern.compile("\\$" + i + "(?!\\d)").matcher(best).find();
            }
            boolean agreed = all && tally[0] >= 2 && tally[0] * 10 >= tally[1] * 8 && LearnVotes.beyondLiterals(best, e.getValue(), true);
            if (log != null) {
                log.append("chain\t").append(e.getKey()).append('\t').append(tally[1]).append('\t').append(tally[0]).append('\t').append(agreed).append('\t')
                    .append(best == null ? "" : best).append('\t');
                e.getValue().values().stream().map(s -> s.isEmpty() ? "-" : String.join(" | ", s)).limit(9).forEach(r2 -> log.append(r2.replace(GameLearner.OPEN, '{').replace(GameLearner.OR, '|').replace(GameLearner.CLOSE, '}')).append(" ;; "));
                log.append('\n');
            }
            if (agreed) {
                chains.add(new Chained((GameDiff.Ref) r[0], (GameDiff.Ref) r[1], (int) r[2], (int) r[2] != -2 && first, best, tally[0], tally[1]));
            }
        }
        chains.sort(Comparator.comparing((Chained c) -> c.x().owner() + c.x().name() + c.y().name() + c.at()));
    }
}

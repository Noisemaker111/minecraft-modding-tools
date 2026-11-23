import java.io.*;
import java.lang.constant.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * What a call on a parameter the game retyped becomes, read from the game's own methods that took the new type. 1.20's
 * screens took a GuiGraphics where they took a PoseStack, and where the old body called font.draw(poseStack, text, x, y,
 * color) the new one calls graphics.drawString(font, text, x, y, color, false). In every pair of linked methods whose
 * parameter changed type (the rest of the signature the same), both bodies are lined up as Learner lines them up; at a
 * use of a changed member one of whose operands is that parameter, what the new body computes is written over the old
 * member's operands, with that operand standing for the new parameter ($n is the GuiGraphics where it was the
 * PoseStack), and a new value that is an old operand without its widening (int x where the old code passed (float) x)
 * as that operand narrowed back. JarPort applies it where a mod's override moved to the new type passes the parameter
 * the game passed (the "retyped" list of the route map). Uses vote as Learner's do.
 */
final class RetypedLearner {

    /** A call (owner, name, desc of a) whose operand at ($0 receiver) was the old parameter; expr has the new type as there. */
    record Reading(String owner, String name, String desc, int at, String as, String expr, int votes, int of) {

        String json(String step) {
            return "{ \"step\": " + VersionMap.Diff.q(step) + ", \"owner\": " + VersionMap.Diff.q(owner) + ", \"from\": " + VersionMap.Diff.q(name) +
                ", \"desc\": " + VersionMap.Diff.q(desc) + ", \"at\": " + at + ", \"as\": " + VersionMap.Diff.q(as) + ", \"expr\": " + VersionMap.Diff.q(expr) +
                ", \"votes\": " + votes + ", \"of\": " + of + " }";
        }
    }

    final GameHop h;
    final GameLearner l;
    final Map<String, Map<GameDiff.Node, Set<String>>> sites = new LinkedHashMap<>(); // owner.name+desc#at#as -> use -> readings
    final Map<String, Map<GameDiff.Node, Set<String>>> loaderSites = new LinkedHashMap<>(); // the same, read from the loader's code
    final Map<String, GameDiff.Ref> refs = new HashMap<>();
    boolean fromLoader; // the method pair being read is the loader's

    RetypedLearner(GameHop h, GameLearner l) {
        this.h = h;
        this.l = l;
    }

    List<Reading> learn() {
        for (var e : h.mAB.entrySet()) {
            GameDiff.Meth x = e.getKey(), y = e.getValue();
            if (!x.hasCode || !y.hasCode || x.isStatic() != y.isStatic()) {
                continue;
            }
            Map<Integer, Object[]> retyped = retyped(x, y);
            if (!retyped.isEmpty()) {
                fromLoader = x.owner.loader;
                pair(l.decode(h.a, x.owner.name, x.name + x.desc, false), l.decode(h.b, y.owner.name, y.name + y.desc, false), retyped);
            }
        }
        // The game's own code says what a call becomes; the loader's code (Fabric API reading Minecraft's font where the
        // game reads the screen's) is heard only for a call the game never makes there.
        loaderSites.forEach((key, uses) -> {
            if (sites.getOrDefault(key, Map.of()).isEmpty()) {
                sites.put(key, uses);
            }
        });
        List<Reading> out = new ArrayList<>();
        StringBuilder log = GameLearner.LEARN_LOG.isEmpty() ? null : new StringBuilder();
        for (String key : misses.keySet()) {
            sites.putIfAbsent(key, new IdentityHashMap<>());
        }
        for (var e : sites.entrySet()) {
            int[] tally = new int[2];
            String best = LearnVotes.vote(e.getValue().values(), tally);
            if (log != null) {
                log.append(e.getKey()).append('\t').append(tally[1]).append('\t').append(tally[0]).append('\t').append(best == null ? "" : best).append('\t');
                e.getValue().values().stream().flatMap(Set::stream).distinct().limit(4).forEach(r -> log.append(r).append(" ;; "));
                misses.getOrDefault(e.getKey(), List.of()).stream().limit(3).forEach(m -> log.append("MISS ").append(m).append(" ;; "));
                log.append('\n');
            }
            // Kept with 80% of the uses agreeing, as Learner keeps one; or when every use that reads as anything agrees
            // and two thirds read: a use the game rewrote past any reading (centred another way, its float math
            // folded) is not another answer.
            long read = e.getValue().values().stream().filter(s -> !s.isEmpty()).count();
            boolean unanimous = tally[0] == read && tally[0] >= 2 && tally[0] * 3 >= tally[1] * 2;
            if (best == null || !(tally[0] >= 2 && tally[0] * 10 >= tally[1] * 8 || unanimous || tally[0] == 1 && tally[1] == 1)) {
                continue;
            }
            String[] key = e.getKey().split("#");
            GameDiff.Ref r = refs.get(key[0]);
            out.add(new Reading(r.owner().replace('/', '.'), r.name(), r.desc(), Integer.parseInt(key[1]), key[2], best, tally[0], tally[1]));
        }
        if (log != null) {
            try {
                Files.writeString(Path.of(GameLearner.LEARN_LOG + "-retyped-" + h.a.version + "-" + h.b.version + ".tsv"), log, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        out.sort(Comparator.comparing(Reading::owner).thenComparing(Reading::name).thenComparing(Reading::desc).thenComparingInt(Reading::at).thenComparing(Reading::as));
        return out;
    }

    /**
     * The parameters of x whose type y changed, by x's slot: {y's slot, y's type}. Only where both take as many
     * parameters and each other one keeps its type, so the place says which new parameter took the old one's.
     */
    Map<Integer, Object[]> retyped(GameDiff.Meth x, GameDiff.Meth y) {
        Map<Integer, Object[]> out = new HashMap<>();
        List<ClassDesc> was = MethodTypeDesc.ofDescriptor(x.desc).parameterList(), now = MethodTypeDesc.ofDescriptor(y.desc).parameterList();
        if (was.size() != now.size()) {
            return out;
        }
        int sx = x.isStatic() ? 0 : 1, sy = sx;
        for (int i = 0; i < was.size(); i++) {
            String a = was.get(i).descriptorString(), b = now.get(i).descriptorString();
            if (!h.descBA(b).equals(a)) {
                if (!a.startsWith("L") || !b.startsWith("L")) {
                    return new HashMap<>();
                }
                out.put(sx, new Object[] { sy, b });
            }
            sx += a.equals("J") || a.equals("D") ? 2 : 1;
            sy += b.equals("J") || b.equals("D") ? 2 : 1;
        }
        return out;
    }

    void pair(GameDiff.Body x, GameDiff.Body y, Map<Integer, Object[]> retyped) {
        if (x == null || y == null) {
            return;
        }
        long[] ta = new long[x.statements.size()], tb = new long[y.statements.size()];
        for (int i = 0; i < ta.length; i++) {
            ta[i] = l.learnPairing.hash(x.statements.get(i), true);
        }
        for (int j = 0; j < tb.length; j++) {
            tb[j] = l.learnPairing.hash(y.statements.get(j), false);
        }
        List<int[]> bounds = new ArrayList<>(LearnPairing.matches(ta, tb));
        bounds.add(new int[] { ta.length, tb.length });
        int i0 = 0, j0 = 0;
        for (int[] p : bounds) {
            int na = p[0] - i0, nb = p[1] - j0;
            for (int i = i0; i < p[0] && nb > 0; i++) {
                GameDiff.Node sa = x.statements.get(i);
                if (!l.learnPairing.holed(sa)) {
                    continue;
                }
                boolean bare = sa.kids.length == 1 && l.learnPairing.hole(sa.kids[0]) && (sa.value.equals("expr") || sa.value.equals("store"));
                for (int j = j0; j < p[1]; j++) {
                    if (bare && !(na == nb && j - j0 == i - i0 || nb == 1)) {
                        continue;
                    }
                    Map<GameDiff.Node, GameDiff.Node> holes = new IdentityHashMap<>();
                    l.learnPairing.budget = 20_000;
                    if (l.learnPairing.match(sa, y.statements.get(j), holes)) {
                        holes.forEach((use, m) -> read(use, m, retyped));
                    }
                }
            }
            i0 = p[0] + 1;
            j0 = p[1] + 1;
        }
    }

    /** b computes m where a used a changed member in use: a reading for each operand of use that is a retyped parameter. */
    void read(GameDiff.Node use, GameDiff.Node m, Map<Integer, Object[]> retyped) {
        if (use.op != 'v' && use.op != 's') {
            return;
        }
        int first = use.op == 'v' ? 0 : 1;
        for (int k = 0; k < use.kids.length; k++) {
            GameDiff.Node op = use.kids[k];
            Object[] to = op.op == 'p' && op.value instanceof Integer slot ? retyped.get(slot) : null;
            if (to == null) {
                continue;
            }
            // Each operand (but a constant, which Learner reads as a choice) becomes a token both sides read alike, and
            // so does the new parameter, standing where the old one was.
            GameDiff.Node[] tokens = new GameDiff.Node[use.kids.length];
            for (int i = 0; i < tokens.length; i++) {
                GameDiff.Node kid = use.kids[i];
                // A constant widened ((float) 0) is that constant of the operand's type, so b's 0 there is a choice too.
                tokens[i] = kid.op == 'k' ? kid : kid.op == 'x' && kid.kids.length == 1 && kid.kids[0].op == 'k'
                    ? new GameDiff.Node('k', null, kid.kids[0].value, kid.type) : new GameDiff.Node('p', null, -1000 - i, "V");
            }
            GameDiff.Node over = tokens(m, use.kids, tokens, k, (Integer) to[0]);
            String key = use.ref.owner() + "." + use.ref.name() + use.ref.desc() + "#" + (k + first) + "#" + to[1];
            refs.putIfAbsent(use.ref.owner() + "." + use.ref.name() + use.ref.desc(), use.ref);
            Map<String, Map<GameDiff.Node, Set<String>>> heard = fromLoader ? loaderSites : sites;
            Set<String> readings = heard.computeIfAbsent(key, x -> new IdentityHashMap<>()).computeIfAbsent(use, x -> new LinkedHashSet<>());
            String reading = use.type.equals("V") || l.learnReading.fits(m.type, use.type) ? l.learnReading.express(over, tokens, first, use.type, 0) : null;
            if (reading != null) {
                readings.add(reading);
            } else if (readings.isEmpty() && (l.learnReading.merged(m) || Arrays.stream(use.kids).anyMatch(l.learnReading::merged))) {
                // Unreadable only because a value depends on the path taken (a loop's variable): it abstains, as in Learner.
                heard.get(key).remove(use);
            } else {
                misses.computeIfAbsent(key, x -> new ArrayList<>()).add(show(over, 0) + "  <-  " + show(use, 0));
            }
        }
    }

    final Map<String, List<String>> misses = new HashMap<>(); // uses that lined up and read as nothing, for the log

    /** A value tree in short, for the log. */
    static String show(GameDiff.Node n, int depth) {
        String head = n.op + (n.ref != null ? n.ref.name() == null ? n.ref.owner() : n.ref.name() : "") + (n.value != null ? "=" + n.value : "");
        if (n.kids.length == 0 || depth > 4) {
            return head;
        }
        StringJoiner kids = new StringJoiner(", ", head + "(", ")");
        for (GameDiff.Node k : n.kids) {
            kids.add(show(k, depth + 1));
        }
        return kids.toString();
    }

    /**
     * b's value n with what stands for an old operand written as that operand's token: b's parameter in slot as the
     * retyped operand k; code the same as an operand (synthetic accessors renumbered between releases count as the
     * same: access$1900 in a is access$2000 in b); and an operand without its widening ((float) x passed, int x now) as
     * that operand converted back.
     */
    GameDiff.Node tokens(GameDiff.Node n, GameDiff.Node[] ops, GameDiff.Node[] tokens, int k, int slot) {
        if (n.op == 'p' && Integer.valueOf(slot).equals(n.value)) {
            return tokens[k];
        }
        if (n.op == 'k') {
            return n; // a constant b passes is read as Learner reads one: itself, or a choice among operands holding it
        }
        for (int i = 0; i < ops.length; i++) {
            l.learnPairing.budget = 2_000;
            if (ops[i].op != 'k' && (l.learnPairing.match(ops[i], n, null) || sameAccess(ops[i], n))) {
                return tokens[i];
            }
        }
        for (int i = 0; i < ops.length; i++) {
            l.learnPairing.budget = 2_000;
            GameDiff.Node op = ops[i];
            if (op.op == 'x' && op.kids.length == 1 && (l.learnPairing.match(op.kids[0], n, null) || sameAccess(op.kids[0], n)) && LearnReading.primitive(n.type) != null) {
                return new GameDiff.Node('x', null, null, n.type, tokens[i]);
            }
        }
        GameDiff.Node[] kids = n.kids.clone();
        boolean changed = false;
        for (int i = 0; i < kids.length; i++) {
            kids[i] = tokens(n.kids[i], ops, tokens, k, slot);
            changed |= kids[i] != n.kids[i];
        }
        if (!changed) {
            return n;
        }
        GameDiff.Node out = new GameDiff.Node(n.op, n.ref, n.value, n.type, kids);
        out.body = n.body;
        return out;
    }

    /** a's x and b's y are the same code, where calls to synthetic accessors (access$N) may carry other numbers. */
    boolean sameAccess(GameDiff.Node x, GameDiff.Node y) {
        if (x.op == 's' && y.op == 's' && x.ref != null && y.ref != null && x.ref.name() != null && y.ref.name() != null
            && x.ref.name().startsWith("access$") && y.ref.name().startsWith("access$") && x.kids.length == y.kids.length && h.descBA(y.type).equals(x.type)) {
            for (int i = 0; i < x.kids.length; i++) {
                l.learnPairing.budget = 2_000;
                if (!l.learnPairing.match(x.kids[i], y.kids[i], null) && !sameAccess(x.kids[i], y.kids[i])) {
                    return false;
                }
            }
            return true;
        }
        if (x.op != y.op || x.kids.length != y.kids.length || x.kids.length == 0 || !l.learnPairing.head(x, true).equals(l.learnPairing.head(y, false))) {
            return false;
        }
        for (int i = 0; i < x.kids.length; i++) {
            l.learnPairing.budget = 2_000;
            if (!l.learnPairing.match(x.kids[i], y.kids[i], null) && !sameAccess(x.kids[i], y.kids[i])) {
                return false;
            }
        }
        return true;
    }
}

import java.lang.constant.*;
import java.util.*;
import java.util.regex.*;

/** The vote over every use the game's own code makes of a changed member: when the uses agree, and what is left out when they do not. */
final class LearnVotes {

    final GameLearner learner;

    LearnVotes(GameLearner learner) {
        this.learner = learner;
    }

    /**
     * A use that passed a literal shows the replacement only for that value: the game passed new ResourceLocation("stone")
     * and now writes withDefaultNamespace("stone"), which is wrong for a mod's "mymod:stone". So the reading must also
     * win among the uses whose operands were all computed, when there are two or more, and may not drop an operand the
     * game passed as a literal at every use it read.
     */
    static boolean beyondLiterals(String best, Map<GameDiff.Node, Set<String>> uses) {
        return beyondLiterals(best, uses, false, k -> false);
    }

    static boolean beyondLiterals(String best, Map<GameDiff.Node, Set<String>> uses, boolean ownEdits) {
        return beyondLiterals(best, uses, ownEdits, k -> false);
    }

    /** tested: whether the old member's own body only tests operand $k (see onlyTested). */
    static boolean beyondLiterals(String best, Map<GameDiff.Node, Set<String>> uses, boolean ownEdits, java.util.function.IntPredicate tested) {
        List<Set<String>> computed = new ArrayList<>();
        GameDiff.Node any = null;
        for (var u : uses.entrySet()) {
            any = u.getKey();
            if (Arrays.stream(any.kids).noneMatch(k -> k.op == 'k')) {
                computed.add(u.getValue());
            }
        }
        // A member with no operands passes no literal: there is nothing to recount.
        if (computed.size() >= 2 && any.kids.length > 0) {
            int[] tally = new int[2];
            String again = vote(computed, tally, ownEdits);
            if (!best.equals(again) || tally[0] * 10 < tally[1] * 8) {
                return false;
            }
        }
        int first = any.op == 'v' || any.op == 'f' ? 0 : 1;
        for (int i = 0; i < any.kids.length; i++) {
            int k = i;
            boolean used = Pattern.compile("\\$" + (i + first) + "(?!\\d)").matcher(best).find();
            List<GameDiff.Node> read = uses.entrySet().stream().filter(u -> !u.getValue().isEmpty()).map(Map.Entry::getKey).toList();
            // Unless the game passed different values there, every one read the same, and the old member only tested
            // it in a way that chooses nothing (see onlyTested). The game dropping a check at its own uses says its own
            // data passes it, not a mod's: 1.21.4's getList(key, type) gave an empty list for a list of another element
            // type, and getListOrEmpty(key) at the game's uses would hand a mod that list.
            if (!used && read.stream().allMatch(u -> u.kids.length > k && u.kids[k].op == 'k')
                && (read.stream().map(u -> String.valueOf(u.kids[k].value)).distinct().count() < 2 || !tested.test(i + first))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The reading most uses agree on. Uses are grouped by the reading's shape (choices left open); in the shape most
     * uses have, each choice takes the option most of them allow (an operand before a constant, then the next operand
     * in order), and a use agrees when it allows every option taken. tally: {agreeing uses, uses read}.
     */
    static String vote(Collection<Set<String>> uses, int[] tally) {
        return vote(uses, tally, false);
    }

    /**
     * With ownEdits, a use that differs from the reading only where it passes a fixed value (no operand) in place of an
     * operand the reading passes abstains: the game changed that argument in that one place of its own code.
     */
    static String vote(Collection<Set<String>> uses, int[] tally, boolean ownEdits) {
        Map<String, Integer> shapes = new HashMap<>();
        // Every use that lined up counts, read or not: a use the new code rewrote into something no expression over
        // the operands says (another parameter, a local) is evidence against every reading.
        int of = uses.size();
        for (Set<String> readings : uses) {
            if (!readings.isEmpty()) {
                readings.stream().map(r -> GameLearner.CHOICE.matcher(r).replaceAll(GameLearner.OPEN + "" + GameLearner.CLOSE)).distinct().forEach(s -> shapes.merge(s, 1, Integer::sum));
            }
        }
        tally[1] = of;
        String shape = shapes.entrySet().stream().max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
            .map(Map.Entry::getKey).orElse(null);
        if (shape == null) {
            return null;
        }
        // For each use with this shape, the options it allows at each choice.
        List<List<Set<String>>> allowed = new ArrayList<>();
        for (Set<String> readings : uses) {
            List<Set<String>> per = null;
            for (String r : readings) {
                if (!GameLearner.CHOICE.matcher(r).replaceAll(GameLearner.OPEN + "" + GameLearner.CLOSE).equals(shape)) {
                    continue;
                }
                Matcher m = GameLearner.CHOICE.matcher(r);
                List<Set<String>> mine = new ArrayList<>();
                while (m.find()) {
                    mine.add(new HashSet<>(List.of(m.group(1).split(String.valueOf(GameLearner.OR)))));
                }
                if (per == null) {
                    per = mine;
                } else {
                    for (int i = 0; i < per.size(); i++) {
                        per.get(i).addAll(mine.get(i));
                    }
                }
            }
            if (per != null) {
                allowed.add(per);
            }
        }
        int choices = allowed.get(0).size();
        List<String> taken = new ArrayList<>();
        int lastOperand = -1;
        for (int i = 0; i < choices; i++) {
            Map<String, Integer> count = new HashMap<>();
            for (List<Set<String>> per : allowed) {
                per.get(i).forEach(o -> count.merge(o, 1, Integer::sum));
            }
            int last = lastOperand;
            String pick = count.entrySet().stream().max(Comparator
                .<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                .thenComparing(o -> o.getKey().contains("$"))
                .thenComparing(o -> operandIndex(o.getKey()) > last)
                .thenComparing(o -> -operandIndex(o.getKey()))).get().getKey();
            taken.add(pick);
            lastOperand = Math.max(lastOperand, operandIndex(pick));
        }
        // A fixed value most uses of this shape agree on, where the old member had no operand (1.21.5's getInt(key) is
        // getIntOr(key, 0) in most places): a use that passes another fixed value there chose its own default and
        // abstains, as the game's own edit of that one place.
        boolean[] settled = new boolean[choices];
        for (int i = 0; i < choices; i++) {
            int k = i;
            long with = allowed.stream().filter(per -> per.get(k).contains(taken.get(k))).count();
            settled[i] = !taken.get(i).contains("$") && with * 2 > allowed.size();
        }
        int agree = 0;
        for (List<Set<String>> per : allowed) {
            boolean all = true, own = true, chosen = true;
            for (int i = 0; i < choices; i++) {
                if (!per.get(i).contains(taken.get(i))) {
                    all = false;
                    own &= taken.get(i).contains("$") && per.get(i).stream().noneMatch(o -> o.contains("$"));
                    chosen &= settled[i] && per.get(i).stream().noneMatch(o -> o.contains("$"));
                }
            }
            agree += all ? 1 : 0;
            tally[1] -= !all && (own && ownEdits || chosen) ? 1 : 0;
        }
        tally[0] = agree;
        if (ownEdits) {
            // Uses of another shape that are this one with a fixed value (a constant or a static field, no operand) where
            // this one passes an operand: the game changed that argument itself there.
            StringBuilder rx = new StringBuilder();
            Matcher at = Pattern.compile(GameLearner.OPEN + "" + GameLearner.CLOSE).matcher(shape);
            int last = 0, k = 0;
            while (at.find()) {
                rx.append(Pattern.quote(shape.substring(last, at.start())));
                rx.append(taken.get(k++).contains("$") ? "(?:" + GameLearner.OPEN + "[^" + GameLearner.CLOSE + "]*" + GameLearner.CLOSE + "|[\\w.$]+)" : "" + GameLearner.OPEN + "[^" + GameLearner.CLOSE + "]*" + GameLearner.CLOSE);
                last = at.end();
            }
            rx.append(Pattern.quote(shape.substring(last)));
            Pattern own = Pattern.compile(rx.toString());
            for (Set<String> readings : uses) {
                boolean sameShape = readings.stream().anyMatch(r -> GameLearner.CHOICE.matcher(r).replaceAll(GameLearner.OPEN + "" + GameLearner.CLOSE).equals(shape));
                if (!sameShape && readings.stream().anyMatch(r -> own.matcher(r).matches() && !r.contains("$" + "$"))) {
                    tally[1]--;
                }
            }
        }
        Matcher m = Pattern.compile(GameLearner.OPEN + "" + GameLearner.CLOSE).matcher(shape);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(taken.get(i++)));
        }
        return m.appendTail(out).toString();
    }

    static int operandIndex(String option) {
        Matcher m = Pattern.compile("\\$(\\d+)").matcher(option);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /**
     * uses without the unread ones where the new code put a value of an unrelated type: 1.21.5 reads a whole Vec3 or
     * EntityType with a codec where the old code read a ListTag or a String and built it. That rewrote the code around
     * the call, not the call, so it says nothing for or against a reading of it (an unread use with a value of the
     * member's type, another parameter or a local, still counts against every reading).
     */
    Map<GameDiff.Node, Set<String>> withoutOtherValues(GameDiff.Ref old, Map<GameDiff.Node, Set<String>> uses) {
        String gave = old.kind() == 'f' ? old.desc() : old.name().equals("<init>") ? "L" + old.owner() + ";" : MethodTypeDesc.ofDescriptor(old.desc()).returnType().descriptorString();
        if (!gave.startsWith("L")) {
            return uses;
        }
        String want = LearnedCarry.descAB(learner.h, gave);
        // What the uses that read give now: an unread use giving the same is the same rewrite and counts.
        Set<String> readTypes = new HashSet<>();
        uses.forEach((u, r) -> {
            if (!r.isEmpty() && learner.lined.get(u) != null) {
                learner.lined.get(u).forEach(m -> readTypes.add(m.type));
            }
        });
        Map<GameDiff.Node, Set<String>> kept = new IdentityHashMap<>();
        uses.forEach((u, r) -> {
            Set<GameDiff.Node> now = learner.lined.get(u);
            boolean other = r.isEmpty() && now != null && !now.isEmpty() && now.stream().allMatch(m -> m.type != null && (m.type.startsWith("L") || m.type.startsWith("["))
                && !m.type.equals(want) && !readTypes.contains(m.type) && !Exposure.assignable(learner.h.b, SameCallLearner.dot(m.type), SameCallLearner.dot(want)) && !Exposure.assignable(learner.h.b, SameCallLearner.dot(want), SameCallLearner.dot(m.type)));
            if (!other) {
                kept.put(u, r);
            }
        });
        return kept.size() == uses.size() ? uses : kept;
    }

    /**
     * Uses the new code wrote as the member's own successor with the value insisted on, not defaulted (1.21.5's
     * getCompound(key).orElseThrow() where the game knows the tag is there): for a value that is there they agree
     * with any reading, and where it is not, the game chose to fail at that one place. They abstain.
     */
    int insisting(String best, GameDiff.Ref old, Map<GameDiff.Node, Set<String>> uses) {
        if (best == null) {
            return 0;
        }
        Pattern insist = Pattern.compile("\\." + Pattern.quote(old.name()) + "\\(.*\\)\\.(orElseThrow|get)\\(\\)$");
        if (insist.matcher(best).find()) {
            return 0; // the reading insists too: those uses are its own
        }
        int n = 0;
        for (Set<String> r : uses.values()) {
            if (!r.isEmpty() && !r.contains(best) && r.stream().allMatch(one -> insist.matcher(one.replace(String.valueOf(GameLearner.OPEN), "").replace(String.valueOf(GameLearner.CLOSE), "")).find())) {
                n++;
            }
        }
        return n;
    }

    /**
     * Unread uses where the new code makes the reading's own call, on the same number of operands (1.21.5's
     * getFloatOr(tag, "walkSpeed", 0.1F) on a tag getCompoundOrEmpty gave, getFloatOr(tag, "Health", getMaxHealth())):
     * what it passes there, a default of the place's own or a receiver another change rewrote, could not be written
     * over the old operands. Like a use that chose its own fixed default, it abstains.
     */
    int sameCallUnread(String best, Map<GameDiff.Node, Set<String>> uses) {
        if (best == null) {
            return 0;
        }
        FixExpr.Node read;
        try {
            read = FixExpr.parse(best.replace(GameLearner.OPEN, ' ').replace(GameLearner.CLOSE, ' '));
        } catch (IllegalArgumentException unreadable) {
            return 0;
        }
        if (!(read instanceof FixExpr.Invoke call)) {
            return 0;
        }
        int first = call.target() instanceof FixExpr.Path ? 0 : 1;
        int operands = call.args().size() + first;
        if (call.args().stream().allMatch(arg -> !FixExpr.operands(arg).isEmpty())) {
            // No fixed value in the reading: what such a use passes differently is a value the reading cannot write.
            return 0;
        }
        int n = 0;
        for (var u : uses.entrySet()) {
            Set<GameDiff.Node> now = learner.lined.get(u.getKey());
            if (u.getValue().isEmpty() && now != null && now.stream().anyMatch(m -> (m.op == 'v' || m.op == 's') && m.ref != null
                && m.ref.name().equals(call.name()) && m.kids.length == operands && fixedWhereFixed(call, m, first))) {
                n++;
            }
        }
        return n;
    }

    /**
     * Where the reading passes a fixed value, the use passes one too: a constant or a static, nothing of its own method
     * (find(o, this) where the reading has find(o, Pal.none()) says the call needs what only that method has).
     */
    static boolean fixedWhereFixed(FixExpr.Invoke call, GameDiff.Node m, int first) {
        for (int i = 0; i < call.args().size(); i++) {
            if (FixExpr.operands(call.args().get(i)).isEmpty() && !fixed(m.kids[i + first])) {
                return false;
            }
        }
        return true;
    }

    /** A value made of constants and statics only. */
    static boolean fixed(GameDiff.Node n) {
        if (n.op == 'p' || n.op == 'l' || n.op == 'o') {
            return false;
        }
        for (GameDiff.Node k : n.kids) {
            if (!fixed(k)) {
                return false;
            }
        }
        return true;
    }

    /** best uses each of old's operands ($0 the receiver unless static), or leaves out only ones old's own body only tests. */
    boolean keepsOperands(String best, GameDiff.Ref old) {
        GameDiff.Meth m = old.kind() == 'm' ? learner.h.a.method(old.owner(), old.name(), old.desc()) : null;
        if (m == null) {
            return true;
        }
        int n = MethodTypeDesc.ofDescriptor(old.desc()).parameterCount();
        for (int k = m.isStatic() || m.special() ? 1 : 0; k <= n; k++) {
            if (!Pattern.compile("\\$" + k + "(?!\\d)").matcher(best).find() && !onlyTested(old, k)) {
                return false;
            }
        }
        return true;
    }

    /** Whether the old member's own body reads operand $k only in a condition: never passes it on, stores or returns it. */
    boolean onlyTested(GameDiff.Ref old, int k) {
        GameDiff.Meth m = old.kind() == 'm' ? learner.h.a.method(old.owner(), old.name(), old.desc()) : null;
        if (m == null || !m.hasCode) {
            return false;
        }
        // $k is the k-th parameter ($0 the receiver, never only tested here); its slot follows the receiver's.
        List<ClassDesc> ps = MethodTypeDesc.ofDescriptor(old.desc()).parameterList();
        if (k < 1 || k > ps.size()) {
            return false;
        }
        int slot = m.isStatic() ? 0 : 1;
        for (int i = 0; i < k - 1; i++) {
            String d = ps.get(i).descriptorString();
            slot += d.equals("J") || d.equals("D") ? 2 : 1;
        }
        GameDiff.Body body;
        try {
            body = learner.decode(learner.h.a, old.owner(), old.name() + old.desc(), false);
        } catch (RuntimeException unreadable) {
            return false;
        }
        if (body == null || !body.whole) {
            return false;
        }
        boolean seen = false;
        GameDiff.Node gives = null;
        int exits = 0;
        for (GameDiff.Node st : body.statements) {
            boolean test = st.op == 'S' && String.valueOf(st.value).startsWith("if:");
            for (GameDiff.Node kid : st.kids) {
                if (test && kid.op == 'p' && Integer.valueOf(slot).equals(kid.value)) {
                    seen = true;
                } else if (LearnAccessors.reads(kid, slot)) {
                    return false;
                }
            }
            if (test) {
                continue;
            }
            // A test chooses nothing only where every way out gives the same and nothing else is done: 1.21.4's
            // getList(key, type) gives the list or, where its element type is not type, an empty one, so dropping
            // type would hand a mod a list of the wrong type.
            GameDiff.Node v = st.kids.length == 0 ? null : st.kids[0];
            if (st.op != 'S' || !"return".equals(st.value) || exits++ > 0 && !sameTree(gives, v)) {
                return false;
            }
            gives = v;
        }
        return seen;
    }

    /** The same value built the same way. */
    static boolean sameTree(GameDiff.Node a, GameDiff.Node b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.op != b.op || !Objects.equals(a.ref, b.ref) || !Objects.equals(a.value, b.value) || !Objects.equals(a.type, b.type) || a.kids.length != b.kids.length) {
            return false;
        }
        for (int i = 0; i < a.kids.length; i++) {
            if (!sameTree(a.kids[i], b.kids[i])) {
                return false;
            }
        }
        return true;
    }
}

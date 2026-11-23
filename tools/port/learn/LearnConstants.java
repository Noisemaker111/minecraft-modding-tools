import java.lang.constant.*;
import java.util.*;
import java.util.regex.*;

/** A replacement that depends on the constant passed: one answer per constant, or per class of constant. */
final class LearnConstants {

    final GameLearner learner;

    LearnConstants(GameLearner learner) {
        this.learner = learner;
    }

    final List<GameLearner.Learned> byConstant = new ArrayList<>();

    /**
     * A member whose uses disagree because one operand picks what it does: one of its type's own constants (1.21.5's new
     * ClickEvent(action, value) is one class per action: new ClickEvent.SuggestCommand(s) where action was
     * SUGGEST_COMMAND, new ClickEvent.CopyToClipboard(s) where it was COPY_TO_CLIPBOARD). The uses are voted apart by the
     * constant, each group as any vote, and its reading holds only where a call passes that constant. A constant no use
     * passed is read from the new release's types: the enum's constant is built with a static value of one class that
     * is a kind of what the member gives (RUN_COMMAND with RunCommand.CODEC), made from the other operands by that
     * class's one constructor they fit.
     */
    List<GameLearner.Learned> perConstant(GameDiff.Ref old, Map<GameDiff.Node, Set<String>> uses) {
        String[] declared = learner.declared(old);
        if (declared == null) {
            return List.of();
        }
        List<GameLearner.Learned> out = new ArrayList<>();
        boolean linked = learner.h.mAB.containsKey(learner.h.a.method(old.owner(), old.name(), old.desc()));
        for (int k = 1; k < declared.length; k++) {
            String d = declared[k];
            GameDiff.Cls e = d.startsWith("L") ? learner.h.a.classes.get(d.substring(1, d.length() - 1)) : null;
            if (e == null) {
                continue;
            }
            Map<String, Map<GameDiff.Node, Set<String>>> groups = new TreeMap<>();
            for (var u : uses.entrySet()) {
                int at = k - (u.getKey().op == 'v' || u.getKey().op == 'f' ? 0 : 1);
                GameDiff.Node kid = at < u.getKey().kids.length ? u.getKey().kids[at] : null;
                GameDiff.Fld f = kid != null && kid.op == 'g' && kid.ref.owner().equals(e.name) ? e.fields.get(kid.ref.name()) : null;
                if (f != null && constant(e, f)) {
                    groups.computeIfAbsent(kid.ref.name(), z -> new IdentityHashMap<>()).put(u.getKey(), u.getValue());
                }
            }
            if (groups.isEmpty()) {
                continue;
            }
            Set<String> done = new HashSet<>();
            for (var g : groups.entrySet()) {
                String reading = byGroup(old, declared, k, g.getValue());
                if (reading != null) {
                    int[] tally = new int[2];
                    LearnVotes.vote(g.getValue().values(), tally);
                    out.add(new GameLearner.Learned(old.owner().replace('/', '.'), old.name(), old.desc(), false, linked, reading, Math.max(tally[0], 1), Math.max(tally[1], 1),
                        k + ":" + g.getKey()));
                    done.add(g.getKey());
                }
            }
            for (GameDiff.Fld c : e.fields.values()) {
                if (constant(e, c) && !done.contains(c.name)) {
                    String reading = byConstantClass(old, declared, k, e, c.name);
                    if (reading != null) {
                        out.add(new GameLearner.Learned(old.owner().replace('/', '.'), old.name(), old.desc(), false, linked, reading, 1, 1, k + ":" + c.name));
                    }
                }
            }
            break;
        }
        return out;
    }

    /** One of a class's own constants: a static final field of the class's type (an enum's, or 1.21.4's HoverEvent.Action.SHOW_TEXT). */
    static boolean constant(GameDiff.Cls e, GameDiff.Fld f) {
        return (f.flags & (GameDiff.ACC_STATIC | 0x0010)) == (GameDiff.ACC_STATIC | 0x0010) && f.desc.equals("L" + e.name + ";");
    }

    /** The reading one constant's uses agree on, without the constant and with every other operand, compiling for the declared types. */
    String byGroup(GameDiff.Ref old, String[] declared, int k, Map<GameDiff.Node, Set<String>> group) {
        Map<GameDiff.Node, Set<String>> kept = new IdentityHashMap<>();
        Pattern constant = Pattern.compile("\\$" + k + "(?!\\d)");
        group.forEach((u, r) -> {
            Set<String> keep = new LinkedHashSet<>();
            for (String one : r) {
                if (!constant.matcher(one).find()) {
                    keep.add(one);
                }
            }
            kept.put(u, keep);
        });
        int[] tally = new int[2];
        String best = LearnVotes.vote(kept.values(), tally);
        if (best == null || !(tally[0] >= 2 && tally[0] * 10 >= tally[1] * 8 || tally[0] == 1 && tally[1] == 1)) {
            return null;
        }
        for (int j = old.name().equals("<init>") || learner.h.a.method(old.owner(), old.name(), old.desc()).isStatic() ? 1 : 0; j < declared.length; j++) {
            if (j != k && !Pattern.compile("\\$" + j + "(?!\\d)").matcher(best).find()) {
                return null;
            }
        }
        if (Pattern.compile("(^|[(, ])(-?\\d|\"|true|false|null)").matcher(best).find()) {
            return null;
        }
        if (learner.learnCompiles.compiles(best, declared, old)) {
            return best;
        }
        // An operand declared wider than the constant's own value (HoverEvent's Object, a Component for SHOW_TEXT) is
        // cast to the type the game's own uses passed there, the widest of them the reading compiles with.
        for (int j = 1; j < declared.length; j++) {
            if (j == k || !declared[j].startsWith("L")) {
                continue;
            }
            int at = j - 1;
            Set<String> passed = new LinkedHashSet<>();
            for (GameDiff.Node u : group.keySet()) {
                int idx = at + (u.op == 'v' || u.op == 'f' ? 1 : 0);
                if (idx < u.kids.length && u.kids[idx].type != null && u.kids[idx].type.startsWith("L")) {
                    passed.add(LearnedCarry.descAB(learner.h, u.kids[idx].type));
                }
            }
            List<String> widest = passed.stream().filter(t -> passed.stream().allMatch(o -> learner.learnCompiles.fitsIn(o, t))).toList();
            for (String t : widest) {
                String cast = Pattern.compile("\\$" + j + "(?!\\d)").matcher(best).replaceAll(Matcher.quoteReplacement("(" + GameLearner.dotted(t.substring(1, t.length() - 1)) + ") $" + j));
                if (learner.learnCompiles.compiles(cast, declared, old)) {
                    return cast;
                }
            }
        }
        return null;
    }

    /**
     * A constant no use passed: the one class C, a kind of what old gives, whose static value the new release builds the
     * constant with (in the enum's static initialiser), and C's one public constructor the other operands make.
     */
    String byConstantClass(GameDiff.Ref old, String[] declared, int k, GameDiff.Cls e, String constant) {
        GameDiff.Cls now = learner.h.cAB.get(e);
        if (now == null) {
            return null;
        }
        String gave = old.name().equals("<init>") ? "L" + old.owner() + ";" : MethodTypeDesc.ofDescriptor(old.desc()).returnType().descriptorString();
        String want = LearnedCarry.descAB(learner.h, gave);
        GameDiff.Body clinit;
        try {
            clinit = learner.decode(learner.h.b, now.name, "<clinit>()V", false);
        } catch (RuntimeException unreadable) {
            return null;
        }
        if (clinit == null) {
            return null;
        }
        Set<String> classes = new LinkedHashSet<>();
        for (GameDiff.Node st : clinit.statements) {
            if (st.op != 'S' || st.ref == null || !st.ref.owner().equals(now.name) || !st.ref.name().equals(constant) || st.kids.length != 1) {
                continue;
            }
            Deque<GameDiff.Node> todo = new ArrayDeque<>(List.of(st.kids[0]));
            while (!todo.isEmpty()) {
                GameDiff.Node n = todo.poll();
                if (n.op == 'g' && n.ref != null) {
                    GameDiff.Cls c = learner.h.b.classes.get(n.ref.owner());
                    if (c != null && (c.flags & (GameDiff.ACC_INTERFACE | 0x0400)) == 0 && !c.name.equals(now.name) && learner.learnCompiles.fitsIn("L" + c.name + ";", want)) {
                        classes.add(c.name);
                    }
                }
                todo.addAll(Arrays.asList(n.kids));
            }
        }
        if (classes.size() != 1) {
            return null;
        }
        GameDiff.Cls c = learner.h.b.classes.get(classes.iterator().next());
        List<Integer> others = new ArrayList<>();
        for (int j = 1; j < declared.length; j++) {
            if (j != k) {
                others.add(j);
            }
        }
        List<String> ways = new ArrayList<>();
        for (GameDiff.Meth m : c.methods.values()) {
            List<ClassDesc> ps = MethodTypeDesc.ofDescriptor(m.desc).parameterList();
            if (!m.name.equals("<init>") || (m.flags & GameDiff.ACC_PUBLIC) == 0 || (m.flags & GameDiff.ACC_SYNTHETIC) != 0 || ps.size() != others.size()) {
                continue;
            }
            List<List<String>> per = new ArrayList<>();
            for (int i = 0; i < ps.size(); i++) {
                String p = ps.get(i).descriptorString();
                per.add(p.equals("Ljava/lang/Object;") ? List.of() : learner.learnByTypes.makes(LearnedCarry.descAB(learner.h, declared[others.get(i)]), p, "$" + others.get(i), 2));
            }
            if (per.stream().allMatch(w -> w.size() == 1)) {
                ways.add("new " + GameLearner.dotted(c.name) + "(" + String.join(", ", per.stream().map(w -> w.get(0)).toList()) + ")");
            } else if (per.stream().anyMatch(w -> w.size() > 1)) {
                return null;
            }
        }
        // A constructor taking the operands as they are is the class's own form for them (OpenFile(String) over
        // OpenFile(new File(s))).
        List<String> plain = ways.stream().filter(w -> !w.substring(w.indexOf('(') + 1).matches(".*[(].*")).toList();
        List<String> pick = plain.size() == 1 ? plain : ways;
        return pick.size() == 1 && learner.learnCompiles.compiles(pick.get(0), declared, old) ? pick.get(0) : null;
    }
}

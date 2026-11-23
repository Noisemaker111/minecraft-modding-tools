import java.lang.constant.*;
import java.util.*;

/** Types the game replaced by another type everywhere it used them. */
final class LearnTypeMap {

    final GameLearner learner;

    LearnTypeMap(GameLearner learner) {
        this.learner = learner;
    }

    final Map<GameDiff.Cls, Map<GameDiff.Cls, Integer>> typeVotes = new LinkedHashMap<>();
    final List<HopRewrites.ClassRewrite> typeRewrites = new ArrayList<>();

    /**
     * A gone class of a where b has a value of another class: one vote that b's class took its place. Only a game class
     * b dropped with no class rewrite yet, and a game class of b of the same kind.
     */
    boolean typeVote(String had, String got) {
        if (!had.startsWith("L") || !got.startsWith("L")) {
            return false;
        }
        GameDiff.Cls ca = learner.h.a.classes.get(had.substring(1, had.length() - 1));
        GameDiff.Cls cb = learner.h.b.classes.getOrDefault(got.substring(1, got.length() - 1), learner.h.b.libraries.get(got.substring(1, got.length() - 1)));
        if (ca == null || cb == null || learner.h.cAB.containsKey(ca) || learner.classRewrites().containsKey(ca) || GameDiff.anonymous(ca.name) || ca.kind() != cb.kind()) {
            return false;
        }
        typeVotes.computeIfAbsent(ca, k -> new HashMap<>()).merge(cb, 1, Integer::sum);
        return true;
    }

    /**
     * The class map from the types the game's own code switched to: each linked method's parameters and result and
     * each linked field's type, place by place, where a's is a class b dropped (1.19's TextComponent fields and
     * results became MutableComponent), with the values b computes where a's code made one (votes from read). Kept
     * as a class rewrite with two votes and 80% agreement, as the route map's own class rewrites are.
     */
    void typeMap() {
        for (var e : learner.h.mAB.entrySet()) {
            List<ClassDesc> pa = MethodTypeDesc.ofDescriptor(e.getKey().desc).parameterList();
            List<ClassDesc> pb = MethodTypeDesc.ofDescriptor(e.getValue().desc).parameterList();
            if (pa.size() == pb.size()) {
                for (int i = 0; i < pa.size(); i++) {
                    typeVote(pa.get(i).descriptorString(), pb.get(i).descriptorString());
                }
            }
            typeVote(MethodTypeDesc.ofDescriptor(e.getKey().desc).returnType().descriptorString(),
                MethodTypeDesc.ofDescriptor(e.getValue().desc).returnType().descriptorString());
        }
        for (var e : learner.h.fAB.entrySet()) {
            typeVote(e.getKey().desc, e.getValue().desc);
        }
        for (var e : typeVotes.entrySet()) {
            GameDiff.Cls best = null;
            int top = 0, of = 0;
            for (var v : e.getValue().entrySet()) {
                of += v.getValue();
                if (v.getValue() > top || v.getValue() == top && best != null && v.getKey().name.compareTo(best.name) < 0) {
                    top = v.getValue();
                    best = v.getKey();
                }
            }
            if (top >= 2 && top * 10 >= of * 8) {
                learner.classRewrites().put(e.getKey(), best);
                typeRewrites.add(new HopRewrites.ClassRewrite(e.getKey(), best, top, of));
            }
        }
        typeRewrites.sort(Comparator.comparing((HopRewrites.ClassRewrite r) -> r.from().name));
    }
}

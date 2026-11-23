import java.util.*;

/** What replaced a game method mods override, learned from the game's own overrides of it. */
final class LearnOverrides {

    final GameLearner learner;

    LearnOverrides(GameLearner learner) {
        this.learner = learner;
    }

    /**
     * A call names the class it was made on, which may override the method the game's code was read for (a mod calls
     * clientLevel.getRecipeManager(), the game level.getRecipeManager()). An override that changed the same way and
     * has no reading of its own takes its nearest supertype's: the call dispatches the same way in both releases.
     */
    List<GameLearner.Learned> overrides(List<GameLearner.Learned> found) {
        Map<String, GameLearner.Learned> byKey = new HashMap<>();
        for (GameLearner.Learned l : found) {
            if (!l.field()) {
                byKey.put(l.owner().replace('.', '/') + "." + l.name() + l.desc(), l);
            }
        }
        List<GameLearner.Learned> out = new ArrayList<>();
        for (GameDiff.Cls c : learner.h.a.classes.values()) {
            for (GameDiff.Meth m : c.methods.values()) {
                String key = c.name + "." + m.name + m.desc;
                // An override the game's own code shows something else for (its uses lined up, read or not) keeps
                // that evidence: it was rejected, so it is left unresolved rather than given its supertype's reading.
                if (m.isStatic() || m.special() || byKey.containsKey(key) || !learner.sites.getOrDefault(key, Map.of()).isEmpty()
                    || !learner.broken(new GameDiff.Ref('m', c.name, m.name, m.desc))) {
                    continue;
                }
                GameLearner.Learned up = null;
                GameDiff.Meth upMethod = null;
                Deque<String> todo = new ArrayDeque<>(GameDiff.supertypes(c));
                Set<String> seen = new HashSet<>();
                while (up == null && !todo.isEmpty()) {
                    String s = todo.poll();
                    GameDiff.Cls sc = learner.h.a.classes.get(s);
                    if (sc == null || !seen.add(s)) {
                        continue;
                    }
                    up = byKey.get(s + "." + m.name + m.desc);
                    upMethod = sc.methods.get(m.name + m.desc);
                    todo.addAll(GameDiff.supertypes(sc));
                }
                if (up != null && stillOverrides(m, upMethod)) {
                    out.add(new GameLearner.Learned(c.name.replace('/', '.'), m.name, m.desc, false, learner.h.mAB.containsKey(m), up.expr(), up.votes(), up.of()));
                }
            }
        }
        return out;
    }

    /**
     * The new release keeps sub overriding sup: both dropped, or both linked to methods of the same name and
     * descriptor where the one's class extends the other's. Anything else changed the two apart.
     */
    boolean stillOverrides(GameDiff.Meth sub, GameDiff.Meth sup) {
        GameDiff.Meth ys = learner.h.mAB.get(sub), yp = sup == null ? null : learner.h.mAB.get(sup);
        if (ys == null || yp == null) {
            return ys == null && yp == null;
        }
        return ys.name.equals(yp.name) && ys.desc.equals(yp.desc) && learner.h.hopChains.subtypeB(ys.owner.name, yp.owner.name);
    }
}

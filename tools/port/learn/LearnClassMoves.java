import java.lang.constant.*;
import java.util.*;

/** Members that went with their class when the game renamed, merged or split it. */
final class LearnClassMoves {

    final GameLearner learner;

    LearnClassMoves(GameLearner learner) {
        this.learner = learner;
    }

    List<GameLearner.Learned> throughClassMap(List<GameLearner.Learned> found) {
        Set<String> have = new HashSet<>();
        for (GameLearner.Learned l : found) {
            have.add(l.owner().replace('.', '/') + "." + l.name() + l.desc());
        }
        List<GameLearner.Learned> out = new ArrayList<>();
        for (var e : learner.classRewrites().entrySet()) {
            GameDiff.Cls ca = e.getKey(), cb = e.getValue();
            if (learner.h.cAB.containsKey(ca) || (ca.flags & GameDiff.ACC_PUBLIC) == 0) {
                continue;
            }
            for (GameDiff.Meth m : ca.methods.values()) {
                if ((m.flags & (GameDiff.ACC_PUBLIC | 0x0004)) == 0 || m.name.equals("<clinit>") || have.contains(ca.name + "." + m.name + m.desc)
                    || !learner.broken(new GameDiff.Ref('m', ca.name, m.name, m.desc))) {
                    continue;
                }
                MethodTypeDesc old = MethodTypeDesc.ofDescriptor(m.desc);
                List<GameDiff.Meth> picks = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                Deque<String> todo = new ArrayDeque<>(List.of(cb.name));
                while (!todo.isEmpty()) {
                    String c = todo.poll();
                    GameDiff.Cls k = learner.h.b.classes.getOrDefault(c, learner.h.b.libraries.get(c));
                    if (k == null || !seen.add(c) || m.special() && !c.equals(cb.name)) {
                        continue;
                    }
                    for (GameDiff.Meth n : k.methods.values()) {
                        if (n.name.equals(m.name) && n.isStatic() == m.isStatic() && (n.flags & GameDiff.ACC_PUBLIC) != 0 && (n.flags & GameDiff.ACC_SYNTHETIC) == 0
                            && accepts(old, MethodTypeDesc.ofDescriptor(n.desc), m.special()) && picks.stream().noneMatch(p -> p.desc.equals(n.desc))) {
                            picks.add(n);
                        }
                    }
                    todo.addAll(GameDiff.supertypes(k));
                }
                if (picks.size() != 1) {
                    continue;
                }
                StringJoiner args = new StringJoiner(", ");
                for (int i = 1; i <= old.parameterCount(); i++) {
                    args.add("$" + i);
                }
                String expr = m.special() ? "new " + GameLearner.dotted(cb.name) + "(" + args + ")"
                    : (m.isStatic() ? GameLearner.dotted(picks.get(0).owner.name) : "$0") + "." + m.name + "(" + args + ")";
                out.add(new GameLearner.Learned(GameLearner.dotted(ca.name), m.name, m.desc, false, false, expr, 1, 1));
            }
        }
        return out;
    }

    /**
     * An overload the game removed while renaming the rest of its family: 1.21 renamed VertexConsumer.vertex(Matrix4f,
     * FFF) and vertex(Pose, FFF) to addVertex and dropped vertex(DDD), whose counterpart is addVertex(FFF). Every renamed
     * sibling must take the same new name; the one method of that name in the new class (or its supertypes) with the
     * same number of parameters takes the old arguments, each the same or a double where a float is wanted, narrowed as
     * javac would be told to, and its result serves where the old one did.
     */
    List<GameLearner.Learned> renamedFamily(List<GameLearner.Learned> found) {
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
                if ((m.flags & (GameDiff.ACC_PUBLIC | 0x0004)) == 0 || m.special() || learner.h.mAB.get(m) != null || have.contains(ca.name + "." + m.name + m.desc)) {
                    continue;
                }
                String renamed = null;
                boolean one = true;
                for (GameDiff.Meth s : ca.methods.values()) {
                    GameDiff.Meth t = s == m || !s.name.equals(m.name) ? null : learner.h.mAB.get(s);
                    if (t != null && !t.name.equals(m.name)) {
                        one &= renamed == null || renamed.equals(t.name);
                        renamed = t.name;
                    }
                }
                if (renamed == null || !one) {
                    continue;
                }
                MethodTypeDesc old = MethodTypeDesc.ofDescriptor(m.desc);
                List<GameDiff.Meth> picks = new ArrayList<>();
                for (GameDiff.Cls k = cb; k != null; k = k.sup == null ? null : learner.h.b.classes.get(k.sup)) {
                    for (GameDiff.Meth n : k.methods.values()) {
                        if (n.name.equals(renamed) && n.isStatic() == m.isStatic() && (n.flags & GameDiff.ACC_PUBLIC) != 0 && narrows(old, MethodTypeDesc.ofDescriptor(n.desc))
                            && picks.stream().noneMatch(p -> p.desc.equals(n.desc))) {
                            picks.add(n);
                        }
                    }
                }
                if (picks.size() != 1) {
                    continue;
                }
                MethodTypeDesc now = MethodTypeDesc.ofDescriptor(picks.get(0).desc);
                StringJoiner args = new StringJoiner(", ");
                for (int i = 0; i < old.parameterCount(); i++) {
                    boolean cast = old.parameterType(i).descriptorString().equals("D") && now.parameterType(i).descriptorString().equals("F");
                    args.add((cast ? "(float) " : "") + "$" + (i + 1));
                }
                String expr = (m.isStatic() ? GameLearner.dotted(picks.get(0).owner.name) : "$0") + "." + renamed + "(" + args + ")";
                out.add(new GameLearner.Learned(GameLearner.dotted(ca.name), m.name, m.desc, false, false, expr, 1, 1));
            }
        }
        return out;
    }

    /** The new parameters take the old arguments, a double where a float is wanted allowed, and the new result serves. */
    boolean narrows(MethodTypeDesc old, MethodTypeDesc now) {
        if (old.parameterCount() != now.parameterCount()) {
            return false;
        }
        boolean narrowed = false;
        for (int i = 0; i < old.parameterCount(); i++) {
            String o = mapType(old.parameterType(i).descriptorString()), n = now.parameterType(i).descriptorString();
            if (o.equals("D") && n.equals("F")) {
                narrowed = true;
            } else if (!assignableB(o, n)) {
                return false;
            }
        }
        String had = old.returnType().descriptorString();
        return narrowed && (had.equals("V") || assignableB(now.returnType().descriptorString(), mapType(had)));
    }

    /** Old arguments, mapped, fit the new parameters one by one, and the new result serves where the old one did. */
    boolean accepts(MethodTypeDesc old, MethodTypeDesc now, boolean constructor) {
        if (old.parameterCount() != now.parameterCount()) {
            return false;
        }
        for (int i = 0; i < old.parameterCount(); i++) {
            if (!assignableB(mapType(old.parameterType(i).descriptorString()), now.parameterType(i).descriptorString())) {
                return false;
            }
        }
        String had = old.returnType().descriptorString();
        return constructor || had.equals("V") || assignableB(now.returnType().descriptorString(), mapType(had));
    }

    /** An a descriptor in b's names, through the links and the class map. */
    String mapType(String desc) {
        return desc.startsWith("L") ? "L" + learner.learnReading.mappedName(desc).replace('.', '/') + ";" : desc;
    }

    /** A value of b type from serves where b type to is wanted: the same, or a subtype through b's classes and libraries. */
    boolean assignableB(String from, String to) {
        if (from.equals(to)) {
            return true;
        }
        if (!from.startsWith("L") || !to.startsWith("L")) {
            return false;
        }
        String want = to.substring(1, to.length() - 1);
        if (want.equals("java/lang/Object")) {
            return true;
        }
        Deque<String> todo = new ArrayDeque<>(List.of(from.substring(1, from.length() - 1)));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            String c = todo.poll();
            if (c.equals(want)) {
                return true;
            }
            GameDiff.Cls k = learner.h.b.classes.getOrDefault(c, learner.h.b.libraries.get(c));
            if (k != null && seen.add(c)) {
                todo.addAll(GameDiff.supertypes(k));
            }
        }
        return false;
    }
}

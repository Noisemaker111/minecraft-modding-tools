import java.lang.constant.*;
import java.util.*;

/** A field the game put behind a getter or setter, and a member now reached through an accessor. */
final class LearnAccessors {

    final GameLearner learner;

    LearnAccessors(GameLearner learner) {
        this.learner = learner;
    }

    /**
     * A member of a class the class map replaced, where the game's uses showed nothing: the one public member of the
     * new class (or its supertypes, the libraries' too) with the same name, staticness and number of parameters that
     * javac would pick for the old arguments, mapped: each old parameter fits the new one, and the result fits the old
     * one unless the old member gave nothing. 1.19.3: Vector3f.set(FFF)V is org.joml.Vector3f.set(FFF), which returns
     * the vector; Quaternion.mul(Quaternion) is Quaternionf.mul(Quaternionfc).
     */
    /**
     * Static methods a class made instance ones, reached through the accessor the game's own code uses for the class's
     * other such methods. NeoForge 1.21.9 made FMLLoader's statics instance methods of FMLLoader.getCurrent(), and its
     * own code calls getCurrent().getDist(), getCurrent().isProduction(), ... where it called FMLLoader.getDist(): a
     * changed static it never calls (getBindings()) is FMLLoader.getCurrent().getBindings() too. At least two methods
     * show the same accessor (a public static method of the class, no arguments, giving the class) and none another.
     */
    List<GameLearner.Learned> throughAccessor(List<GameLearner.Learned> found) {
        Map<String, Set<String>> accessors = new HashMap<>(); // owner (a, internal) -> accessors its learned lines use
        Map<String, Integer> shown = new HashMap<>();
        Set<String> have = new HashSet<>();
        for (GameLearner.Learned l : found) {
            String owner = l.owner().replace('.', '/');
            have.add(owner + "." + l.name() + l.desc());
            GameDiff.Meth x = l.field() ? null : learner.h.a.method(owner, l.name(), l.desc());
            GameDiff.Meth y = x == null ? null : learner.h.mAB.get(x);
            if (x == null || y == null || !x.isStatic() || y.isStatic()) {
                continue;
            }
            StringBuilder args = new StringBuilder();
            for (int i = 1; i <= MethodTypeDesc.ofDescriptor(x.desc).parameterCount(); i++) {
                args.append(i == 1 ? "" : ", ").append("$").append(i);
            }
            String prefix = GameLearner.dotted(y.owner.name) + ".";
            String tail = "()." + y.name + "(" + args + ")";
            if (l.expr().startsWith(prefix) && l.expr().endsWith(tail)) {
                String accessor = l.expr().substring(prefix.length(), l.expr().length() - tail.length());
                GameDiff.Meth acc = accessor.matches("[\\w$]+") ? learner.h.b.method(y.owner.name, accessor, "()L" + y.owner.name + ";") : null;
                if (acc != null && acc.isStatic() && (acc.flags & GameDiff.ACC_PUBLIC) != 0) {
                    accessors.computeIfAbsent(x.owner.name, k -> new HashSet<>()).add(accessor);
                    shown.merge(x.owner.name, 1, Integer::sum);
                    continue;
                }
            }
            // Another static path (FMLEnvironment.isProduction()) says nothing about the accessor.
        }
        List<GameLearner.Learned> out = new ArrayList<>();
        for (var e : accessors.entrySet()) {
            if (e.getValue().size() != 1 || shown.getOrDefault(e.getKey(), 0) < 2) {
                continue;
            }
            String accessor = e.getValue().iterator().next();
            GameDiff.Cls c = learner.h.a.classes.get(e.getKey());
            for (GameDiff.Meth x : c == null ? List.<GameDiff.Meth>of() : c.methods.values()) {
                GameDiff.Meth y = learner.h.mAB.get(x);
                if (!x.isStatic() || x.special() || y == null || y.isStatic() || (y.flags & GameDiff.ACC_PUBLIC) == 0 || !learner.h.descBA(y.desc).equals(x.desc)
                    || have.contains(x.sig())) {
                    continue;
                }
                StringBuilder args = new StringBuilder();
                for (int i = 1; i <= MethodTypeDesc.ofDescriptor(x.desc).parameterCount(); i++) {
                    args.append(i == 1 ? "" : ", ").append("$").append(i);
                }
                out.add(new GameLearner.Learned(GameLearner.dotted(x.owner.name), x.name, x.desc, false, true, GameLearner.dotted(y.owner.name) + "." + accessor + "()." + y.name + "(" + args + ")", 1, 1));
            }
        }
        return out;
    }

    /**
     * A field b still has but closed, read or written through the accessor its class now has for it: a public method
     * whose whole body is `return this.field` (1.17's Player.getInventory() for inventory) or `this.field = value`
     * (1.19.3's AbstractWidget.setY for y). Exact, so no vote is needed. A write is learned as "name=" with $1 the
     * value written.
     */
    List<GameLearner.Learned> accessors(List<GameLearner.Learned> found) {
        Set<String> have = new HashSet<>();
        for (GameLearner.Learned l : found) {
            have.add(l.owner().replace('.', '/') + "." + l.name());
        }
        List<GameLearner.Learned> out = new ArrayList<>();
        for (var e : learner.h.fAB.entrySet()) {
            GameDiff.Fld f = e.getKey(), y = e.getValue();
            GameDiff.Cls ca = learner.h.a.classes.get(f.owner.name);
            if (!GameLearner.closed(f.flags, y.flags) || !learner.h.descBA(y.desc).equals(f.desc) || ca == null || (ca.flags & GameDiff.ACC_PUBLIC) == 0) {
                continue;
            }
            boolean isStatic = (f.flags & GameDiff.ACC_STATIC) != 0;
            String owner = y.owner.name.replace('/', '.');
            for (GameDiff.Meth c : y.owner.methods.values()) {
                if ((c.flags & GameDiff.ACC_PUBLIC) == 0 || c.isStatic() != isStatic || c.special() || !c.hasCode) {
                    continue;
                }
                boolean getter = c.desc.equals("()" + y.desc), setter = c.desc.equals("(" + y.desc + ")V");
                if (!getter && !setter) {
                    continue;
                }
                GameDiff.Body body = learner.decode(learner.h.b, y.owner.name, c.name + c.desc, false);
                String call = (isStatic ? owner : "$0") + "." + c.name;
                String read = f.owner.name + "." + f.name, write = read + "=";
                if (getter && body != null && !have.contains(read) && reads(body, y, isStatic)) {
                    out.add(new GameLearner.Learned(f.owner.name.replace('/', '.'), f.name, "", true, true, call + "()", 1, 1));
                    have.add(read);
                } else if (setter && body != null && !have.contains(write) && writes(body, y, isStatic)) {
                    out.add(new GameLearner.Learned(f.owner.name.replace('/', '.'), f.name + "=", "", true, true, call + "($1)", 1, 1));
                    have.add(write);
                }
            }
        }
        return out;
    }

    static boolean reads(GameDiff.Body body, GameDiff.Fld y, boolean isStatic) {
        if (body.statements.size() != 1 || !"return".equals(body.statements.get(0).value) || body.statements.get(0).kids.length != 1) {
            return false;
        }
        GameDiff.Node v = body.statements.get(0).kids[0];
        return v.ref != null && v.ref.owner().equals(y.owner.name) && v.ref.name().equals(y.name)
            && (isStatic ? v.op == 'g' : v.op == 'f' && v.kids[0].op == 'p' && v.kids[0].value.equals(0));
    }

    static boolean writes(GameDiff.Body body, GameDiff.Fld y, boolean isStatic) {
        if (body.statements.size() != 2 || !"return".equals(body.statements.get(1).value) || body.statements.get(1).kids.length != 0) {
            return false;
        }
        GameDiff.Node put = body.statements.get(0);
        if (!"put".equals(put.value) || put.ref == null || !put.ref.owner().equals(y.owner.name) || !put.ref.name().equals(y.name)) {
            return false;
        }
        boolean receiver = isStatic || put.kids[0].op == 'p' && put.kids[0].value.equals(0);
        GameDiff.Node v = put.kids[put.kids.length - 1];
        return receiver && v.op == 'p' && v.value.equals(isStatic ? 0 : 1);
    }

    static boolean reads(GameDiff.Node n, int slot) {
        if (n.op == 'p' && Integer.valueOf(slot).equals(n.value)) {
            return true;
        }
        for (GameDiff.Node k : n.kids) {
            if (reads(k, slot)) {
                return true;
            }
        }
        return false;
    }
}

import java.lang.constant.*;
import java.util.*;

/** A value the game now hands over wrapped (a Holder, an Optional, a record component) or unwrapped. */
final class LearnHolders {

    final GameLearner learner;

    LearnHolders(GameLearner learner) {
        this.learner = learner;
    }

    /**
     * An instance method the game moved to another class the old one is not (26.2's Gui.getChat() is Hud.getChat()):
     * called on the class's one public field or getter that holds that class (Gui.hud), $0.hud.getChat(). The map alone
     * would call it on the old receiver, which the verifier refuses.
     */
    List<GameLearner.Learned> throughHolder(List<GameLearner.Learned> found) {
        Set<String> have = new HashSet<>();
        for (GameLearner.Learned l : found) {
            have.add(l.owner().replace('.', '/') + "." + l.name() + l.desc());
        }
        List<GameLearner.Learned> out = new ArrayList<>();
        for (var e : learner.h.mAB.entrySet()) {
            GameDiff.Meth x = e.getKey(), y = e.getValue();
            GameDiff.Cls now = learner.h.cAB.get(x.owner);
            if (now == null || x.isStatic() || y.isStatic() || x.special() || (x.flags & (GameDiff.ACC_PUBLIC | 0x0004)) == 0 || (y.flags & GameDiff.ACC_PUBLIC) == 0
                || y.owner == now || have.contains(x.sig()) || !learner.h.descBA(y.desc).equals(x.desc)
                || Exposure.assignable(learner.h.b, GameLearner.dotted(now.name), GameLearner.dotted(y.owner.name))
                // Moved down to a subclass (1.20.5's Entity.getAllSlots() is LivingEntity's): only some receivers have
                // it, and no part of the old one holds it (getControllingPassenger() gives another entity).
                || Exposure.assignable(learner.h.b, GameLearner.dotted(y.owner.name), GameLearner.dotted(now.name))) {
                continue;
            }
            String held = "L" + y.owner.name + ";";
            List<String> ways = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Deque<String> todo = new ArrayDeque<>(List.of(now.name));
            while (!todo.isEmpty()) {
                GameDiff.Cls k = learner.h.b.classes.get(todo.poll());
                if (k == null || !seen.add(k.name)) {
                    continue;
                }
                for (GameDiff.Fld f : k.fields.values()) {
                    if ((f.flags & (GameDiff.ACC_PUBLIC | GameDiff.ACC_STATIC)) == GameDiff.ACC_PUBLIC && f.desc.equals(held)) {
                        ways.add(f.name);
                    }
                }
                for (GameDiff.Meth g : k.methods.values()) {
                    if (!g.isStatic() && !g.special() && (g.flags & GameDiff.ACC_PUBLIC) != 0 && (g.flags & GameDiff.ACC_SYNTHETIC) == 0 && g.desc.equals("()" + held)) {
                        ways.add(g.name + "()");
                    }
                }
                if (k.sup != null) {
                    todo.add(k.sup);
                }
                todo.addAll(k.ifaces);
            }
            if (new HashSet<>(ways).size() != 1) {
                continue;
            }
            StringBuilder args = new StringBuilder();
            for (int i = 1; i <= MethodTypeDesc.ofDescriptor(x.desc).parameterCount(); i++) {
                args.append(i == 1 ? "" : ", ").append("$").append(i);
            }
            out.add(new GameLearner.Learned(GameLearner.dotted(x.owner.name), x.name, x.desc, false, true, "$0." + ways.get(0) + "." + y.name + "(" + args + ")", 1, 1));
        }
        return out;
    }

    /**
     * A method whose plain result (a number, a String) the update wrapped in a record, the same arguments otherwise
     * (1.21.9's packVersion(type) gives a PackFormat where it gave the int): the record's one factory from that plain
     * value (PackFormat.of(int)) puts it in one component (new PackFormat(n, 0): the first), and that component's
     * accessor gives it back, $0.packVersion($1).major().
     */
    List<GameLearner.Learned> unwrapped(List<GameLearner.Learned> found) {
        Set<String> have = new HashSet<>();
        for (GameLearner.Learned l : found) {
            have.add(l.owner().replace('.', '/') + "." + l.name() + l.desc());
        }
        List<GameLearner.Learned> out = new ArrayList<>();
        for (var e : learner.h.mAB.entrySet()) {
            GameDiff.Meth x = e.getKey(), y = e.getValue();
            if (x.special() || have.contains(x.sig()) || (y.flags & GameDiff.ACC_PUBLIC) == 0 || x.isStatic() != y.isStatic()) {
                continue;
            }
            MethodTypeDesc xt = MethodTypeDesc.ofDescriptor(x.desc), yt = MethodTypeDesc.ofDescriptor(y.desc);
            String was = xt.returnType().descriptorString(), now = yt.returnType().descriptorString();
            boolean plain = was.length() == 1 && !was.equals("V") || was.equals("Ljava/lang/String;");
            if (!plain || !now.startsWith("L") || xt.parameterCount() != yt.parameterCount()
                || !learner.h.descBA(y.desc).substring(0, learner.h.descBA(y.desc).indexOf(')')).equals(x.desc.substring(0, x.desc.indexOf(')')))) {
                continue;
            }
            GameDiff.Cls r = learner.h.b.classes.get(now.substring(1, now.length() - 1));
            String component = r == null ? null : componentOf(r, was);
            if (component == null) {
                continue;
            }
            StringBuilder args = new StringBuilder();
            for (int i = 1; i <= xt.parameterCount(); i++) {
                args.append(i == 1 ? "" : ", ").append("$").append(i);
            }
            String call = (x.isStatic() ? GameLearner.dotted(y.owner.name) : "$0") + "." + y.name + "(" + args + ")";
            out.add(new GameLearner.Learned(GameLearner.dotted(x.owner.name), x.name, x.desc, false, true, call + "." + component + "()", 1, 1));
        }
        return out;
    }

    /** The accessor of the component of record r that r's one public static factory from a value of type plain fills with it. */
    String componentOf(GameDiff.Cls r, String plain) {
        String rt = "L" + r.name + ";";
        List<GameDiff.Meth> makers = r.methods.values().stream().filter(m -> m.isStatic() && (m.flags & GameDiff.ACC_PUBLIC) != 0 && m.desc.equals("(" + plain + ")" + rt)).toList();
        List<GameDiff.Fld> parts = r.fields.values().stream().filter(f -> (f.flags & GameDiff.ACC_STATIC) == 0).toList();
        if (makers.size() != 1 || !"java/lang/Record".equals(r.sup) || parts.isEmpty()) {
            return null;
        }
        GameDiff.Body body;
        try {
            body = learner.decode(learner.h.b, r.name, makers.get(0).name + makers.get(0).desc, false);
        } catch (RuntimeException unreadable) {
            return null;
        }
        if (body == null || !body.whole) {
            return null;
        }
        for (GameDiff.Node st : body.statements) {
            if (st.op == 'S' && "return".equals(st.value) && st.kids.length == 1 && st.kids[0].op == 'w' && st.kids[0].kids.length == parts.size()) {
                GameDiff.Node made = st.kids[0];
                int at = -1;
                for (int i = 0; i < made.kids.length; i++) {
                    if (made.kids[i].op == 'p' && Integer.valueOf(0).equals(made.kids[i].value)) {
                        at = at == -1 ? i : -2;
                    }
                }
                GameDiff.Fld part = at >= 0 ? parts.get(at) : null;
                GameDiff.Meth accessor = part == null ? null : r.methods.get(part.name + "()" + plain);
                if (accessor != null && part.desc.equals(plain) && (accessor.flags & GameDiff.ACC_PUBLIC) != 0) {
                    return accessor.name;
                }
            }
        }
        return null;
    }
}

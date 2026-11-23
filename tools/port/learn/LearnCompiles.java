import java.lang.constant.*;
import java.util.*;
import java.util.regex.*;

/** Whether a learned expression compiles against the new release, typed as the porter will type it. */
final class LearnCompiles {

    final GameLearner learner;

    LearnCompiles(GameLearner learner) {
        this.learner = learner;
    }

    static Class<?> fromJdkClass(String internal) {
        try {
            return Class.forName(internal.replace('/', '.'), false, null);
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }

    /** reading (with its choices, any way of taking them) compiles in b over operands of the declared types and gives what old gave. */
    boolean compiles(String reading, String[] declared, GameDiff.Ref old) {
        String[] types = new String[declared.length];
        for (int i = 0; i < types.length; i++) {
            types[i] = declared[i] == null ? null : LearnedCarry.descAB(learner.h, declared[i]);
        }
        String gave = old.name().equals("<init>") ? "L" + old.owner() + ";" : MethodTypeDesc.ofDescriptor(old.desc()).returnType().descriptorString();
        String want = LearnedCarry.descAB(learner.h, gave);
        for (String one : ways(reading, 16)) {
            try {
                String got = typeIn(FixExpr.parse(one), types);
                if (got != null && (want.equals("V") || fitsIn(got, want))) {
                    return true;
                }
            } catch (IllegalArgumentException unreadable) {
                // not an expression: it does not compile
            }
        }
        return false;
    }

    /** Every way of taking reading's choices, at most limit of them. */
    static List<String> ways(String reading, int limit) {
        Matcher c = GameLearner.CHOICE.matcher(reading);
        if (!c.find()) {
            return List.of(reading);
        }
        List<String> out = new ArrayList<>();
        List<String> rests = ways(reading.substring(c.end()), limit);
        for (String option : c.group(1).split(String.valueOf(GameLearner.OR))) {
            for (String rest : rests) {
                if (out.size() == limit) {
                    return out;
                }
                out.add(reading.substring(0, c.start()) + option + rest);
            }
        }
        return out;
    }

    /** n's type in b over operands of these types (b's descriptors), or null where it does not compile or is not known. */
    String typeIn(FixExpr.Node n, String[] types) {
        if (n instanceof FixExpr.Arg a) {
            return a.index() < types.length ? types[a.index()] : null;
        }
        if (n instanceof FixExpr.Lit l) {
            return l.type() == null ? "null" : l.type();
        }
        if (n instanceof FixExpr.Sum a) {
            String l = typeIn(a.left(), types), r = typeIn(a.right(), types);
            return l == null || r == null ? null : l.equals("J") && r.equals("J") ? "J" : "BSCI".contains(l) && "BSCI".contains(r) && l.length() == 1 && r.length() == 1 ? "I" : null;
        }
        if (n instanceof FixExpr.Cast c) {
            if (FixExpr.PRIMITIVES.contains(c.type())) {
                return Map.of("boolean", "Z", "byte", "B", "char", "C", "short", "S", "int", "I", "long", "J", "float", "F", "double", "D").get(c.type());
            }
            String k = c.type().replace('.', '/');
            boolean known = learner.h.b.classes.containsKey(k) || k.startsWith("java/") && fromJdkClass(k) != null;
            return known && typeIn(c.expr(), types) != null ? "L" + k + ";" : null;
        }
        if (n instanceof FixExpr.New made) {
            String k = made.type().replace('.', '/');
            GameDiff.Cls cls = learner.h.b.classes.get(k);
            List<GameDiff.Meth> ctors = cls == null ? List.of() : cls.methods.values().stream().filter(m -> m.name.equals("<init>")).toList();
            return called(ctors, made.args(), types) == null ? null : "L" + k + ";";
        }
        if (n instanceof FixExpr.Invoke call) {
            if (call.target() instanceof FixExpr.Path p && p.dotted().startsWith("java.") && !learner.h.b.classes.containsKey(p.dotted().replace('.', '/'))) {
                return jdkStatic(p.dotted(), call.name(), call.args(), types);
            }
            if (call.target() instanceof FixExpr.Path p && learner.h.b.classes.containsKey(p.dotted().replace('.', '/'))) {
                GameDiff.Cls cls = learner.h.b.classes.get(p.dotted().replace('.', '/'));
                return called(cls.methods.values().stream().filter(m -> m.isStatic() && m.name.equals(call.name())).toList(), call.args(), types);
            }
            String recv = typeIn(call.target(), types);
            if (recv == null || !recv.startsWith("L")) {
                return null;
            }
            return called(learner.learnByTypes.instanceMethods(recv.substring(1, recv.length() - 1)).stream().filter(m -> m.name.equals(call.name())).toList(), call.args(), types);
        }
        return null;
    }

    /** The result of a JDK class's public static method of that name these arguments fit, or null. */
    String jdkStatic(String cls, String name, List<FixExpr.Node> args, String[] types) {
        Class<?> k;
        try {
            k = Class.forName(cls, false, null);
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
        List<String> got = new ArrayList<>();
        for (FixExpr.Node a : args) {
            String t = typeIn(a, types);
            if (t == null) {
                return null;
            }
            got.add(t);
        }
        Set<String> results = new HashSet<>();
        for (var m : k.getMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers()) || !m.getName().equals(name) || m.getParameterCount() != got.size() || m.isVarArgs()) {
                continue;
            }
            boolean fit = true;
            for (int i = 0; i < got.size() && fit; i++) {
                fit = fitsIn(got.get(i), m.getParameterTypes()[i].descriptorString());
            }
            if (fit) {
                results.add(m.getReturnType().descriptorString());
            }
        }
        return results.size() == 1 ? results.iterator().next() : null;
    }

    /** The result type of the one call among found these arguments fit (several with one result type count as one). */
    String called(List<GameDiff.Meth> found, List<FixExpr.Node> args, String[] types) {
        List<String> got = new ArrayList<>();
        for (FixExpr.Node a : args) {
            String t = typeIn(a, types);
            if (t == null) {
                return null;
            }
            got.add(t);
        }
        Set<String> results = new HashSet<>();
        for (GameDiff.Meth m : found) {
            List<ClassDesc> ps = MethodTypeDesc.ofDescriptor(m.desc).parameterList();
            if ((m.flags & GameDiff.ACC_PUBLIC) == 0 || ps.size() != got.size()) {
                continue;
            }
            boolean fit = true;
            for (int i = 0; i < ps.size() && fit; i++) {
                fit = fitsIn(got.get(i), ps.get(i).descriptorString());
            }
            if (fit) {
                results.add(MethodTypeDesc.ofDescriptor(m.desc).returnType().descriptorString());
            }
        }
        return results.size() == 1 ? results.iterator().next() : null;
    }

    /**
     * A value of type got (b's descriptors) can be passed where want is taken. A parameter taking any object is most
     * often an erased type variable (Cat's setVariant(Object) from VariantHolder wants a Holder) and takes only an Object.
     */
    boolean fitsIn(String got, String want) {
        if (got.equals(want)) {
            return true;
        }
        if (want.equals("Ljava/lang/Object;")) {
            return got.equals("null");
        }
        if (got.equals("null")) {
            return want.startsWith("L") || want.startsWith("[");
        }
        if (got.length() == 1 && want.length() == 1) {
            String order = "BSIJFD";
            return order.indexOf(got) >= 0 && order.indexOf(got) <= order.indexOf(want);
        }
        return got.startsWith("L") && want.startsWith("L") && Exposure.assignable(learner.h.b, SameCallLearner.dot(got), SameCallLearner.dot(want));
    }
}

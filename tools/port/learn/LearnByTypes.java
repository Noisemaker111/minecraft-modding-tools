import java.lang.constant.*;
import java.util.*;

/** A replacement found by its types alone: the one member or factory of the new release that takes what the old one took and gives what it gave. */
final class LearnByTypes {

    final GameLearner learner;

    LearnByTypes(GameLearner learner) {
        this.learner = learner;
    }

    /**
     * A method of one argument the game took away from a class, with nothing of its own code to read, and the same
     * question asked of something the class now hands out: one public accessor of the new class gives an object with a
     * method of the same name and result, whose argument is made from the old one through the new release's own
     * public constructors and factories, each the only one of its kind. 1.21.11's CommandSourceStack.hasPermission(int)
     * is $0.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId($1))): permissions() is
     * the one accessor whose result answers hasPermission, a Permission is made from a PermissionLevel only by
     * HasCommandLevel's constructor, and a PermissionLevel from an int only by PermissionLevel.byId. A factory step only
     * reads a primitive or a String. Any second way at any step and nothing is learned.
     */
    List<GameLearner.Learned> byTypes(List<GameLearner.Learned> found) {
        Set<String> have = new HashSet<>();
        for (GameLearner.Learned l : found) {
            have.add(l.owner().replace('.', '/') + "." + l.name() + l.desc());
        }
        List<GameLearner.Learned> out = new ArrayList<>();
        for (GameDiff.Cls c : learner.h.a.classes.values()) {
            GameDiff.Cls now = learner.h.cAB.get(c);
            if (now == null) {
                continue;
            }
            for (GameDiff.Meth x : c.methods.values()) {
                List<ClassDesc> ps = MethodTypeDesc.ofDescriptor(x.desc).parameterList();
                if (x.isStatic() || x.special() || ps.size() != 1 || (x.flags & (GameDiff.ACC_PUBLIC | 0x0004)) == 0 || have.contains(x.sig())
                    || learner.h.mAB.containsKey(x) && learner.h.descBA(learner.h.mAB.get(x).desc).equals(x.desc)) {
                    continue;
                }
                String result = LearnedCarry.descAB(learner.h, MethodTypeDesc.ofDescriptor(x.desc).returnType().descriptorString());
                String param = LearnedCarry.descAB(learner.h, ps.get(0).descriptorString());
                // The class's own method of that name first, so a getter giving the class back (withSuppressedOutput())
                // is never asked what the class itself answers.
                List<String> ways = ask("$0", now.name, x.name, result, param);
                for (GameDiff.Meth g : ways.isEmpty() ? instanceMethods(now.name) : List.<GameDiff.Meth>of()) {
                    String gives = MethodTypeDesc.ofDescriptor(g.desc).returnType().descriptorString();
                    if (MethodTypeDesc.ofDescriptor(g.desc).parameterCount() != 0 || !gives.startsWith("L") || gives.equals("Ljava/lang/Object;")
                        || (g.flags & GameDiff.ACC_PUBLIC) == 0 || (g.flags & GameDiff.ACC_SYNTHETIC) != 0 || g.name.equals(x.name)) {
                        continue;
                    }
                    ways.addAll(ask("$0." + g.name + "()", gives.substring(1, gives.length() - 1), x.name, result, param));
                }
                if (ways.size() == 1) {
                    out.add(new GameLearner.Learned(GameLearner.dotted(c.name), x.name, x.desc, false, learner.h.mAB.containsKey(x), ways.get(0), 1, 1));
                }
            }
        }
        return out;
    }

    /** recv's methods named name (on class cls) giving result, each with its argument made from $1 (type param). */
    List<String> ask(String recv, String cls, String name, String result, String param) {
        List<String> ways = new ArrayList<>();
        for (GameDiff.Meth q : instanceMethods(cls)) {
            List<ClassDesc> qs = MethodTypeDesc.ofDescriptor(q.desc).parameterList();
            if (!q.name.equals(name) || qs.size() != 1 || (q.flags & GameDiff.ACC_PUBLIC) == 0 || (q.flags & GameDiff.ACC_SYNTHETIC) != 0
                || !MethodTypeDesc.ofDescriptor(q.desc).returnType().descriptorString().equals(result) || qs.get(0).descriptorString().equals(param)
                || qs.get(0).descriptorString().equals("Ljava/lang/Object;")) {
                continue;
            }
            for (String made : makes(param, qs.get(0).descriptorString(), "$1", 2)) {
                ways.add(recv + "." + q.name + "(" + made + ")");
            }
        }
        return ways;
    }

    final Map<String, List<GameDiff.Meth>> instanceMethods = new HashMap<>();
    final Map<String, List<GameDiff.Cls>> makers = new HashMap<>();

    /** Every public instance method a value of class cls has in b (its own, its superclasses' and interfaces'), one per name and descriptor. */
    List<GameDiff.Meth> instanceMethods(String cls) {
        return instanceMethods.computeIfAbsent(cls, this::instanceMethodsOf);
    }

    List<GameDiff.Meth> instanceMethodsOf(String cls) {
        List<GameDiff.Meth> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(cls));
        Set<String> visited = new HashSet<>();
        while (!todo.isEmpty()) {
            GameDiff.Cls k = learner.h.b.classes.get(todo.poll());
            if (k == null || !visited.add(k.name)) {
                continue;
            }
            for (GameDiff.Meth m : k.methods.values()) {
                if (!m.isStatic() && !m.special() && seen.add(m.name + m.desc)) {
                    out.add(m);
                }
            }
            if (k.sup != null) {
                todo.add(k.sup);
            }
            todo.addAll(k.ifaces);
        }
        return out;
    }

    /**
     * The expressions that make a value of type want from expr, a value of type have (b's descriptors): expr itself when
     * it already is one, else want's (or a class implementing want's) one public constructor or static factory taking a
     * value made the same way, at most depth steps deep. Each way found is listed; the caller wants exactly one.
     */
    List<String> makes(String have, String want, String expr, int depth) {
        if (have.equals(want) || have.startsWith("L") && want.startsWith("L") && Exposure.assignable(learner.h.b, SameCallLearner.dot(have), SameCallLearner.dot(want))) {
            return List.of(expr);
        }
        if (depth == 0 || !want.startsWith("L")) {
            return List.of();
        }
        String target = want.substring(1, want.length() - 1);
        List<String> out = new ArrayList<>();
        for (GameDiff.Cls k : makers(target)) {
            boolean concrete = (k.flags & (GameDiff.ACC_INTERFACE | 0x0400)) == 0;
            for (GameDiff.Meth m : k.methods.values()) {
                List<ClassDesc> ps = MethodTypeDesc.ofDescriptor(m.desc).parameterList();
                if (ps.size() != 1 || (m.flags & GameDiff.ACC_PUBLIC) == 0 || (m.flags & GameDiff.ACC_SYNTHETIC) != 0 || m.name.startsWith("lambda$")) {
                    continue;
                }
                String from = ps.get(0).descriptorString();
                boolean ctor = m.name.equals("<init>") && concrete;
                // A factory only reads a plain value into the type (PermissionLevel.byId(int)): one from another object can
                // mean something else of the same erased type (ResourceKey.createRegistryKey(id) is a registry's key, not
                // a recipe's). And it is the class's only factory: a class with several (RenderType.solid(), endPortal(int))
                // names what each makes, and one taking an int is not the int's meaning.
                boolean factory = m.isStatic() && MethodTypeDesc.ofDescriptor(m.desc).returnType().descriptorString().equals("L" + k.name + ";")
                    && !m.name.equals("valueOf") && (from.length() == 1 || from.equals("Ljava/lang/String;")) && factories(k) == 1;
                // A maker taking any object (Holder.Direct(T)) holds whatever it is given, so it says nothing of what it is made from.
                if (!ctor && !factory || from.equals(want) || from.equals("Ljava/lang/Object;")) {
                    continue;
                }
                for (String inner : makes(have, from, expr, depth - 1)) {
                    out.add(ctor ? "new " + GameLearner.dotted(k.name) + "(" + inner + ")" : GameLearner.dotted(k.name) + "." + m.name + "(" + inner + ")");
                }
            }
        }
        if (out.isEmpty() && target.startsWith("java/")) {
            String jdk = fromJdk(have, target, expr);
            if (jdk != null) {
                out.add(jdk);
            }
        }
        return out;
    }

    /**
     * A JDK value made from one of exactly the type in hand by its one public constructor or static factory that
     * declares no checked exception: the old code passed the value with nothing to handle one (a URI from a String is
     * URI.create(s); new URI(s) throws URISyntaxException).
     */
    static String fromJdk(String have, String target, String expr) {
        Class<?> k;
        try {
            k = Class.forName(target.replace('/', '.'), false, null);
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
        List<String> ways = new ArrayList<>();
        for (var c : k.getConstructors()) {
            if (c.getParameterCount() == 1 && c.getParameterTypes()[0].descriptorString().equals(have) && unchecked(c.getExceptionTypes())) {
                ways.add("new " + k.getName() + "(" + expr + ")");
            }
        }
        for (var m : k.getMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getReturnType() == k && m.getParameterCount() == 1
                && m.getParameterTypes()[0].descriptorString().equals(have) && !m.getName().equals("valueOf") && unchecked(m.getExceptionTypes())) {
                ways.add(k.getName() + "." + m.getName() + "(" + expr + ")");
            }
        }
        return ways.size() == 1 ? ways.get(0) : null;
    }

    static boolean unchecked(Class<?>[] thrown) {
        return Arrays.stream(thrown).allMatch(t -> RuntimeException.class.isAssignableFrom(t) || Error.class.isAssignableFrom(t));
    }

    /** How many public static methods of k give a k, valueOf and values apart. */
    static long factories(GameDiff.Cls k) {
        return k.methods.values().stream().filter(m -> m.isStatic() && (m.flags & GameDiff.ACC_PUBLIC) != 0 && (m.flags & GameDiff.ACC_SYNTHETIC) == 0
            && !m.name.equals("valueOf") && MethodTypeDesc.ofDescriptor(m.desc).returnType().descriptorString().equals("L" + k.name + ";")).count();
    }

    /** target and the classes of b that extend or implement it directly: the ones that can make a target. */
    List<GameDiff.Cls> makers(String target) {
        if (makers.isEmpty()) {
            for (GameDiff.Cls k : learner.h.b.classes.values()) {
                if (k.sup != null) {
                    makers.computeIfAbsent(k.sup, z -> new ArrayList<>()).add(k);
                }
                for (String i : k.ifaces) {
                    makers.computeIfAbsent(i, z -> new ArrayList<>()).add(k);
                }
            }
        }
        List<GameDiff.Cls> out = new ArrayList<>();
        GameDiff.Cls t = learner.h.b.classes.get(target);
        if (t != null) {
            out.add(t);
        }
        out.addAll(makers.getOrDefault(target, List.of()));
        return out;
    }
}

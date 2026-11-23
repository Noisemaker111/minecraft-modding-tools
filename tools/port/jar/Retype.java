import java.io.*;
import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * How the game turns a method's new parameters into its old ones, learned from its own code. A game method whose
 * parameter changed type (1.20's Screen.render(PoseStack, ...) -> render(GuiGraphics, ...); 1.21.9's
 * keyPressed(int, int, int) -> keyPressed(KeyEvent)) is overridden by Mojang's own classes in both releases. Their
 * old and new bodies are read as GameDiff reads them (statements of value trees) and lined up statement by statement
 * the way GameDiff's learner lines them up: a statement that is the same on both sides except where the old body
 * used the old parameter says what the new body uses there instead. Where that is the new parameter read through
 * no-argument calls or fields (graphics.pose(), event.key()) giving the old parameter's type, it is a conversion; the
 * game's uses must agree (80%, see Retype.agreed). Statements line up through the route map, so a call whose
 * signature changed matches its new form.
 */
final class Retype {
    final JarPort port;

    Retype(JarPort port) {
        this.port = port;
    }

    final Map<String, String[][]> learned = new HashMap<>(); // game method key -> per old parameter: {from, steps...} or null

    /**
     * For each old parameter of a game method (old owner, name, desc) that paired leaves without a new parameter of the
     * same type (-2), the new parameter it comes from and the steps that give it ("m:owner.name()desc" a call,
     * "f:owner.name:desc" a field read, new names); null where the game's code shows none. moved is the method's new
     * owner, name and desc.
     */
    String[][] conversions(String owner, String name, String desc, String[] moved, int[] paired) {
        // The whole override family is the evidence: every game class that overrides the topmost declaration.
        String root = root(owner, name, desc);
        String[][] found = learned.computeIfAbsent(root + "." + name + desc + "->" + moved[1] + moved[2] + Arrays.toString(paired),
            k -> Objects.requireNonNullElse(learn(root, name, desc, moved, paired), NONE));
        if (filling) {
            return found == NONE ? null : found;
        }
        // A parameter its own family shows nothing for takes what the game's code uses for that type everywhere else
        // in this update, when that agrees and one new parameter has the type (1.20's list entries, whose render
        // passes the pose only to calls that changed with it, get graphics.pose() like every screen).
        Type[] was = Type.getArgumentTypes(desc), now = Type.getArgumentTypes(moved[2]);
        String[][] out = found == NONE ? new String[was.length][] : found.clone();
        boolean any = found != NONE;
        for (int i = 0; i < was.length; i++) {
            // A primitive too: 1.21's float tick delta is deltaTracker.getGameTimeDeltaPartialTick(false) wherever the game
            // reads it from the DeltaTracker that took its place.
            if (paired[i] >= 0 || out[i] != null || was[i].getSort() == Type.VOID) {
                continue;
            }
            String wanted = port.desc(was[i].getDescriptor());
            int only = -1, count = 0;
            String steps = null;
            for (int j = 0; j < now.length; j++) {
                String best = now[j].getSort() == Type.OBJECT ? pooled(now[j].getDescriptor(), wanted) : null;
                if (best != null) {
                    only = j;
                    steps = best;
                    count++;
                }
            }
            if (count == 1) {
                out[i] = (only + " " + steps).split(" ");
                any = true;
            }
        }
        return any ? out : null;
    }

    static final String[][] NONE = new String[0][];

    /** Readings without what an overriding method's this answers, for code that runs without that object (a lambda, a mixin). */
    static String[][] withoutThis(String[][] via) {
        if (via == null) {
            return null;
        }
        String[][] out = via.clone();
        for (int i = 0; i < out.length; i++) {
            if (out[i] != null && Integer.parseInt(out[i][0]) == MixinRetargets.THIS) {
                out[i] = null;
            }
        }
        return out;
    }
    final Map<String, Map<String, Integer>> pool = new HashMap<>(); // "new type>old type" -> steps -> the game's uses
    final Set<String> pooledPairs = new HashSet<>();
    boolean filling;

    /**
     * What the game's code reads from a value of type got (new names) where it used to have one of type had, agreed
     * over every family of game methods in this update whose parameters changed between the two.
     */
    String pooled(String got, String had) {
        String key = got + ">" + had;
        if (pooledPairs.add(key)) {
            filling = true;
            try {
                for (var e : port.route.methods.entrySet()) {
                    String k = e.getKey();
                    int dot = k.lastIndexOf('.', k.indexOf('('));
                    String owner = k.substring(0, dot), rest = k.substring(dot + 1), name = rest.substring(0, rest.indexOf('(')), desc = rest.substring(name.length());
                    String[] moved = port.route.method(owner, name, desc);
                    if (!port.a.classes.containsKey(owner) || !port.desc(desc).contains(had) || !moved[2].contains(got) || moved[2].equals(port.desc(desc))) {
                        continue;
                    }
                    Type[] was = Type.getArgumentTypes(desc), now = Type.getArgumentTypes(moved[2]);
                    conversions(owner, name, desc, moved, Overrides.pair(now, Arrays.stream(was).map(t -> Type.getType(port.desc(t.getDescriptor()))).toArray(Type[]::new)));
                }
            } finally {
                filling = false;
            }
        }
        String best = agreed(pool.getOrDefault(key, Map.of()));
        return best != null && stepsLink("0 " + best) ? best : null;
    }

    /** The class highest above owner, through superclasses and interfaces, that declares name+desc in the old release. */
    String root(String owner, String name, String desc) {
        String top = owner;
        Deque<String> todo = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            GameDiff.Cls c = port.a.classes.get(todo.poll());
            if (c == null || !seen.add(c.name)) {
                continue;
            }
            if (c.methods.containsKey(name + desc) && Linking.subtype(port.a, top, c.name)) {
                top = c.name;
            }
            if (c.sup != null) {
                todo.add(c.sup);
            }
            todo.addAll(c.ifaces);
        }
        return top;
    }

    String[][] learn(String owner, String name, String desc, String[] moved, int[] paired) {
        GameCode oldCode = port.code(port.a), newCode = port.code(port.b);
        if (oldCode == null || newCode == null) {
            return null;
        }
        Type[] was = Type.getArgumentTypes(desc), now = Type.getArgumentTypes(moved[2]);
        int[] oldSlots = Conversions.slots(was), newSlots = Conversions.slots(now);
        Map<Integer, Integer> holes = new HashMap<>(); // old slot -> old parameter that needs a conversion
        Map<Integer, Integer> same = new HashMap<>(); // old slot -> new slot of the parameter paired with it
        same.put(0, 0);
        for (int i = 0; i < was.length; i++) {
            if (paired[i] >= 0) {
                same.put(oldSlots[i], newSlots[paired[i]]);
            } else {
                holes.put(oldSlots[i], i);
            }
        }
        Map<Integer, Integer> newParam = new HashMap<>(); // new slot -> new parameter
        for (int j = 0; j < now.length; j++) {
            newParam.put(newSlots[j], j);
        }
        List<Map<String, Integer>> votes = new ArrayList<>();
        for (int i = 0; i < was.length; i++) {
            votes.add(new HashMap<>());
        }
        for (String c : overriders(owner, name, desc)) {
            String nc = port.cls(c);
            GameDiff.Cls holder = port.b.classes.get(nc);
            if (holder == null || !holder.methods.containsKey(moved[1] + moved[2])) {
                continue;
            }
            GameDiff.Body x = body(oldCode, c, name + desc), y = body(newCode, nc, moved[1] + moved[2]);
            if (x != null && y != null) {
                new Lining(port, holes, same).pair(x, y, (i, m) -> {
                    String read = reading(m, newParam, was[holes.get(i)]);
                    if (read != null) {
                        votes.get(holes.get(i)).merge(read, 1, Integer::sum);
                    }
                });
            }
        }
        for (int i = 0; i < was.length; i++) {
            for (var e : votes.get(i).entrySet()) {
                String[] parts = e.getKey().split(" ", 2);
                if (parts.length == 2 && Integer.parseInt(parts[0]) != MixinRetargets.THIS) {
                    pool.computeIfAbsent(now[Integer.parseInt(parts[0])].getDescriptor() + ">" + port.desc(was[i].getDescriptor()), k -> new HashMap<>())
                        .merge(parts[1], e.getValue(), Integer::sum);
                }
            }
        }
        String[][] out = new String[was.length][];
        boolean any = false;
        for (int i = 0; i < was.length; i++) {
            String best = agreed(votes.get(i));
            if (best != null && stepsLink(best)) {
                out[i] = best.split(" ");
                any = true;
            }
        }
        if (!LOG.isEmpty()) {
            try {
                Files.writeString(Path.of(LOG), owner + "." + name + desc + " -> " + moved[1] + moved[2] + "\t" + votes + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return any ? out : null;
    }

    /** -Djarport.retypelog=FILE: each game method whose parameters changed type, with the game's readings for each old parameter. */
    static final String LOG = System.getProperty("jarport.retypelog", "");

    /** The reading most of the game's uses agree on: at least 80% of them, and two unless it is the only use. */
    static String agreed(Map<String, Integer> votes) {
        int total = votes.values().stream().mapToInt(Integer::intValue).sum();
        return votes.entrySet().stream()
            .filter(e -> e.getValue() * 10 >= total * 8 && (e.getValue() >= 2 || total == 1))
            .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    /** Every step reads a member of the new release a mod may use. */
    boolean stepsLink(String reading) {
        String[] parts = reading.split(" ");
        for (int s = 1; s < parts.length; s++) {
            if (parts[s].startsWith("x:") || parts[s].startsWith("k:")) {
                continue;
            }
            String[] step = step(parts[s]);
            if (step[0].equals("m")) {
                GameDiff.Meth m = port.b.method(step[1], step[2], step[3]);
                if (m == null || (m.flags & Opcodes.ACC_PUBLIC) == 0 || (m.flags & Opcodes.ACC_STATIC) != 0) {
                    return false;
                }
            } else {
                GameDiff.Fld f = port.b.field(step[1], step[2]);
                if (f == null || (f.flags & Opcodes.ACC_PUBLIC) == 0 || (f.flags & Opcodes.ACC_STATIC) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /** {kind, owner, name, desc} of one step. */
    static String[] step(String text) {
        int dot = text.lastIndexOf('.', text.indexOf(text.charAt(0) == 'm' ? '(' : ':', 2));
        String owner = text.substring(2, dot), rest = text.substring(dot + 1);
        if (text.charAt(0) == 'm') {
            int p = rest.indexOf('(');
            return new String[] { "m", owner, rest.substring(0, p), rest.substring(p) };
        }
        int colon = rest.indexOf(':');
        return new String[] { "f", owner, rest.substring(0, colon), rest.substring(colon + 1) };
    }

    /**
     * What the new body computes where the old one used a parameter, as a conversion: "j step..." for new parameter j
     * read through no-argument calls or fields, giving the old parameter's type. Anything else (the new parameter
     * passed as it is to a call that changed with it, or other code) is no conversion.
     */
    String reading(GameDiff.Node m, Map<Integer, Integer> newParam, Type wanted) {
        if (!(m.type.equals(port.desc(wanted.getDescriptor())) || wanted.getSort() == Type.OBJECT && m.type.startsWith("L")
            && Linking.subtype(port.b, m.type.substring(1, m.type.length() - 1), port.cls(wanted.getInternalName())))) {
            return null;
        }
        Deque<String> steps = new ArrayDeque<>();
        GameDiff.Node n = m;
        while (steps.size() < 6 && (n.kids.length == 1 && (n.op == 'v' && n.value == null && n.ref.desc().startsWith("()") || n.op == 'f'
            || n.op == 'x' && Conversions.CONVERT.containsKey(n.kids[0].type + n.type)) || constantArguments(n))) {
            steps.push(switch (n.op) {
                case 'v' -> "m:" + declarer(n.ref.owner(), n.ref.name(), n.ref.desc()) + "." + n.ref.name() + n.ref.desc();
                case 'f' -> "f:" + n.ref.owner() + "." + n.ref.name() + ":" + n.ref.desc();
                default -> "x:" + n.kids[0].type + n.type; // charTyped's char from the event's int code point
            });
            // Its other arguments are constants the game passes there (deltaTracker.getGameTimeDeltaPartialTick(false)).
            for (int k = n.kids.length - 1; k >= 1; k--) {
                steps.push("k:" + n.kids[k].type + ":" + n.kids[k].value);
            }
            n = n.kids[0];
        }
        Integer j = n.op == 'p' && n.value instanceof Integer slot ? newParam.get(slot) : null;
        // What the object itself now answers where the old method was told it (1.21.9: an entry's top, which render
        // took as a parameter, is this.getContentY() in renderContent).
        if (j == null && n.op == 'p' && Integer.valueOf(0).equals(n.value) && !steps.isEmpty()) {
            j = MixinRetargets.THIS;
        }
        // The new parameter itself is a conversion only where it is the old one's very type at another place
        // (mouseDragged's drag distances after the event that replaced x, y and button).
        if (j == null || steps.isEmpty() && !m.type.equals(port.desc(wanted.getDescriptor()))) {
            return null;
        }
        return steps.isEmpty() ? String.valueOf(j) : j + " " + String.join(" ", steps);
    }

    /** The class of the new release that declares a method a call names, so one call made through subclasses reads alike. */
    String declarer(String owner, String name, String desc) {
        GameDiff.Cls holder = Exposure.declaring(port.b, owner, 'm', name, desc);
        return holder == null ? owner : holder.name;
    }

    /** An instance call on one value whose other arguments are all primitive constants. */
    boolean constantArguments(GameDiff.Node n) {
        if (n.op != 'v' || n.value != null || n.kids.length < 2 || n.ref == null) {
            return false;
        }
        for (int k = 1; k < n.kids.length; k++) {
            if (n.kids[k].op != 'k' || n.kids[k].value == null || !"ZBCSIJFD".contains(n.kids[k].type) || n.kids[k].type.length() != 1) {
                return false;
            }
        }
        return true;
    }

    final Map<String, List<String>> overridersOf = new HashMap<>();

    /** The old release's classes that declare name+desc and are owner or extend it. Asked again for every override of a mod, and the answer is every game class read once. */
    List<String> overriders(String owner, String name, String desc) {
        return overridersOf.computeIfAbsent(owner + "." + name + desc, k -> {
            List<String> out = new ArrayList<>();
            for (GameDiff.Cls c : port.links.declaring(port.a, name + desc)) {
                if (Linking.subtype(port.a, c.name, owner)) {
                    out.add(c.name);
                }
            }
            Collections.sort(out);
            return List.copyOf(out);
        });
    }

    /** A game method's body as GameDiff reads it (Mojang names), or null. */
    GameDiff.Body body(GameCode code, String owner, String key) {
        String jar = code.toJar.getOrDefault(owner, owner);
        byte[] bytes = code.bytes.apply(jar);
        if (bytes == null) {
            return null;
        }
        Map<String, GameDiff.RawCls> raw = new HashMap<>() {
            @Override
            public GameDiff.RawCls get(Object k) {
                return code.raw((String) k);
            }
        };
        GameDiff.Origin o = new GameDiff.Origin(bytes, raw, null, code.names, new HashMap<>());
        for (MethodModel mm : ClassFile.of().parse(bytes).methods()) {
            String rn = mm.methodName().stringValue(), rd = mm.methodType().stringValue();
            if ((code.names.method(jar, rn, rd) + code.names.desc(rd)).equals(key)) {
                return new BodyDecoder(o, jar).run(mm);
            }
        }
        return null;
    }
}

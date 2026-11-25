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
        GameDiff.Cls declared = Exposure.declaring(port.a, owner, 'm', name, desc);
        String[] itself = port.route.method(declared == null ? owner : declared.name, name, desc);
        boolean own = itself[1].equals(moved[1]) && itself[2].equals(moved[2]);
        for (int i = 0; i < was.length; i++) {
            // A primitive too: 1.21's float tick delta is deltaTracker.getGameTimeDeltaPartialTick(false) wherever the game
            // reads it from the DeltaTracker that took its place.
            if (paired[i] >= 0 || was[i].getSort() == Type.VOID) {
                continue;
            }
            // What the game's code has wherever it used this very parameter as it was, followed through calls and
            // callers; where those places read it two ways, what the method's own statements show is not enough.
            // Only for the method's own new signature: a handler moved into another method takes that one's parameters.
            String seen = own ? followed(owner, name, desc, i, 0) : "";
            if (seen.equals(CONFLICT)) {
                out[i] = null;
                continue;
            }
            if (!seen.isEmpty() && stepsLink(seen)) {
                out[i] = seen.split(" ");
                any = true;
                continue;
            }
            if (out[i] != null) {
                continue;
            }
            String wanted = port.desc(was[i].getDescriptor());
            if (wanted.startsWith("L" + StandinClasses.STANDIN)) {
                // The old type is a stand-in's (a class the new release does not have yet, on a port back): what its from(T)
                // makes of the one new parameter of type T (1.20's GuiGraphics from the PoseStack 1.19.4 passes).
                String old = wanted.substring(1 + StandinClasses.STANDIN.length(), wanted.length() - 1);
                int at = -1, fits = 0;
                String via = null;
                for (int j = 0; j < now.length; j++) {
                    String step = port.standIn.from(old, now[j].getDescriptor());
                    if (step != null) {
                        at = j;
                        via = step;
                        fits++;
                    }
                }
                if (fits == 1) {
                    out[i] = new String[] { String.valueOf(at), via };
                    any = true;
                    continue;
                }
            }
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

    /** The constants Conversions.steps can write: primitives (a string or class literal stays a break, not a guess). */
    static final Set<String> WRITTEN = Set.of("I", "J", "F", "D", "Z", "S", "B", "C");

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
            if (parts[s].startsWith("x:") || parts[s].startsWith("k:") || parts[s].startsWith("o:")) {
                continue;
            }
            String[] step = step(parts[s]);
            if (step[0].equals("s")) {
                GameDiff.Meth m = port.b.method(step[1], step[2], step[3]);
                if (m == null || (m.flags & Opcodes.ACC_PUBLIC) == 0 || (m.flags & Opcodes.ACC_STATIC) == 0
                    || (port.b.classes.get(step[1]).flags & Opcodes.ACC_PUBLIC) == 0) {
                    return false;
                }
            } else if (step[0].equals("m")) {
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
        boolean call = text.charAt(0) == 'm' || text.charAt(0) == 's';
        int dot = text.lastIndexOf('.', text.indexOf(call ? '(' : ':', 2));
        String owner = text.substring(2, dot), rest = text.substring(dot + 1);
        if (call) {
            int p = rest.indexOf('(');
            return new String[] { text.substring(0, 1), owner, rest.substring(0, p), rest.substring(p) };
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
        return reading(m, newParam, port.desc(wanted.getDescriptor()));
    }

    /** reading, for a value of type want (a descriptor in the new release's names). */
    String reading(GameDiff.Node m, Map<Integer, Integer> newParam, String want) {
        if (!(m.type.equals(want) || want.startsWith("L") && m.type.startsWith("L")
            && Linking.subtype(port.b, m.type.substring(1, m.type.length() - 1), want.substring(1, want.length() - 1)))) {
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
        if (j == null || steps.isEmpty() && !m.type.equals(want)) {
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

    static final int FOLLOW_DEPTH = 8, FOLLOW_CALLERS = 64;
    static final String CONFLICT = "!";
    final Map<String, String> followed = new HashMap<>(); // "owner.name+desc#i" -> a reading, CONFLICT, or "" for nothing shown
    final Set<String> following = new HashSet<>();
    final Map<String, String> asked = new HashMap<>();
    // The bodies read last, few enough that a port of many jars does not keep every game method it ever read.
    final Map<String, Optional<GameDiff.Body>> bodies = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Optional<GameDiff.Body>> eldest) {
            return size() > 2_000;
        }
    };
    boolean cut; // a reading was cut short by a loop or the depth: what it found is not kept

    /**
     * Old parameter i of a game method (old owner, name, desc) as its new method reads it, {j, steps...} for new
     * parameter j read through Retype.reading's steps, from every place the game's own code used the old parameter as
     * it was, the value itself and not something worked out from it:
     * - in the method's body and its overrides', an argument of a call: the same call (each one called as often on
     *   both sides, taken in order) has the new parameter there read through steps, or passes the new parameter on
     *   to a parameter of its own that is followed so in turn;
     * - in a caller, the caller's own old parameter passed as it was, where its new code passes its new one: what
     *   that parameter of the caller is followed to;
     * - for an interface or abstract method, each lambda and method reference the game makes for it.
     * Only where every reading agrees: a place with the new parameter in another form says nothing either way, and
     * a second reading answers nothing. 1.21's DeltaTracker is read as getGameTimeDeltaPartialTick(true) and as (false); the float a method
     * took before matches the reading the game's code has wherever that float was used as it was (GameRenderer
     * .renderLevel's: true; the HUD's: false), and a method whose float reached both is not converted.
     */
    String[] follow(String owner, String name, String desc, int i) {
        String r = followed(owner, name, desc, i, 0);
        return r.isEmpty() || r.equals(CONFLICT) || !stepsLink(r) ? null : r.split(" ");
    }

    String followed(String owner, String name, String desc, int i, int depth) {
        String key = owner + "." + name + desc + "#" + i;
        if (depth == 0) {
            asked.clear();
        }
        String known = followed.containsKey(key) ? followed.get(key) : asked.get(key);
        if (known != null) {
            return known;
        }
        if (depth > FOLLOW_DEPTH || !following.add(key)) {
            cut = true;
            return "";
        }
        boolean outer = cut;
        cut = false;
        String out;
        Map<String, Integer> votes = new HashMap<>();
        try {
            gather(owner, name, desc, i, depth, votes);
            out = agree(votes);
        } finally {
            following.remove(key);
        }
        if (!cut) {
            followed.put(key, out);
        } else {
            asked.put(key, out); // cut short by a loop: kept for this question only, which would cut it the same way
        }
        cut |= outer;
        if (!LOG.isEmpty()) {
            log("  ".repeat(depth) + key + " followed: " + out + (out.equals(CONFLICT) ? " " + votes : "") + "\n");
        }
        return out;
    }

    /**
     * The one reading every vote means, or CONFLICT: readings that differ only by a getter whose new body hands back
     * another reading of its object (MouseButtonEvent.button() is buttonInfo().button()) mean the same, and the one
     * most places use is kept.
     */
    String agree(Map<String, Integer> votes) {
        if (votes.containsKey(CONFLICT)) {
            return CONFLICT;
        }
        Map<String, String> meant = new HashMap<>();
        for (String r : votes.keySet()) {
            String c = canonical(r);
            String had = meant.get(c);
            if (had == null || votes.get(r) > votes.get(had)) {
                meant.put(c, r);
            }
        }
        return meant.isEmpty() ? "" : meant.size() > 1 ? CONFLICT : meant.values().iterator().next();
    }

    final Map<String, String> canonicalSteps = new HashMap<>();

    /** A reading with each getter written out as what its new body reads from its object, as far as that goes. */
    String canonical(String reading) {
        String[] parts = reading.split(" ");
        StringBuilder out = new StringBuilder(parts[0]);
        for (int s = 1; s < parts.length; s++) {
            out.append(' ').append(canonicalStep(parts[s], 0));
        }
        return out.toString();
    }

    String canonicalStep(String step, int depth) {
        if (!step.startsWith("m:") || !step.contains("()") || depth > 4) {
            return step;
        }
        String known = canonicalSteps.get(step);
        if (known != null) {
            return known;
        }
        String[] st = step(step);
        String out = step;
        GameCode newCode = port.code(port.b);
        GameDiff.Meth m = port.b.method(st[1], st[2], st[3]);
        GameDiff.Body body = newCode == null || m == null || m.isStatic() || (m.flags & Opcodes.ACC_ABSTRACT) != 0 ? null : body(newCode, st[1], st[2] + st[3], false);
        if (body != null && body.statements.size() == 1 && "return".equals(body.statements.get(0).value) && body.statements.get(0).kids.length == 1) {
            String r = reading(body.statements.get(0).kids[0], Map.of(0, 0), Type.getReturnType(st[3]).getDescriptor());
            if (r != null && r.startsWith("0 ")) {
                StringBuilder b2 = new StringBuilder();
                for (String inner : r.substring(2).split(" ")) {
                    b2.append(b2.isEmpty() ? "" : " ").append(canonicalStep(inner, depth + 1));
                }
                out = b2.toString();
            }
        }
        canonicalSteps.put(step, out);
        return out;
    }

    void log(String line) {
        try {
            Files.writeString(Path.of(LOG), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void gather(String owner, String name, String desc, int i, int depth, Map<String, Integer> votes) {
        GameDiff.Cls decl = Exposure.declaring(port.a, owner, 'm', name, desc);
        GameDiff.Meth m = decl == null ? null : decl.methods.get(name + desc);
        GameCode oldCode = port.code(port.a), newCode = port.code(port.b);
        if (m == null || oldCode == null || newCode == null) {
            return;
        }
        String[] moved = port.route.method(decl.name, name, desc);
        GameDiff.Cls nowDecl = Exposure.declaring(port.b, moved[0], 'm', moved[1], moved[2]);
        GameDiff.Meth now = nowDecl == null ? null : nowDecl.methods.get(moved[1] + moved[2]);
        Type[] was = Type.getArgumentTypes(desc), fresh = Type.getArgumentTypes(moved[2]);
        if (now == null || m.isStatic() != now.isStatic() || i >= was.length) {
            return;
        }
        int first = m.isStatic() ? 0 : 1;
        int hole = Conversions.slots(was, first)[i];
        Map<Integer, Integer> newParam = new HashMap<>();
        int[] at = Conversions.slots(fresh, first);
        for (int j = 0; j < fresh.length; j++) {
            newParam.put(at[j], j);
        }
        String want = port.desc(was[i].getDescriptor());
        // Parameters that kept their type stand for themselves where the statements are lined up.
        Map<Integer, Integer> same = new HashMap<>();
        if (first == 1) {
            same.put(0, 0);
        }
        int[] paired = Overrides.pair(fresh, Arrays.stream(was).map(t -> Type.getType(port.desc(t.getDescriptor()))).toArray(Type[]::new));
        int[] oldAt = Conversions.slots(was, first);
        for (int k = 0; k < was.length; k++) {
            if (paired[k] >= 0 && k != i) {
                same.put(oldAt[k], at[paired[k]]);
            }
        }
        // The method's own code, and each override's: its calls, and its statements lined up as Retype.learn lines them.
        for (String c : overriders(decl.name, name, desc)) {
            GameDiff.Body x = body(oldCode, c, name + desc, true), y = body(newCode, port.cls(c), moved[1] + moved[2], false);
            if (x != null && y != null) {
                along(x, y, hole, newParam, want, depth, votes);
                new Lining(port, Map.of(hole, i), same).pair(x, y, (s, there) -> {
                    // The new parameter passed on as it is goes to a call that changed with it, which along follows.
                    String r = reading(there, newParam, want);
                    vote(votes, r != null && !r.startsWith(MixinRetargets.THIS + " ") ? r : "");
                });
            }
        }
        // Each lambda and method reference the game makes for it.
        if ((m.flags & Opcodes.ACC_ABSTRACT) != 0) {
            made(oldCode, newCode, decl.name, name, desc, moved, nowDecl.name, i, depth, votes);
        }
        // Each caller that passed its own parameter as it was.
        Set<String> callers = oldCode.callers(decl.name, name, desc);
        if (callers == null || callers.size() > FOLLOW_CALLERS) {
            return;
        }
        String self = nowDecl.name + "." + moved[1] + moved[2];
        for (String c : new TreeSet<>(callers)) {
            int p = c.indexOf('(');
            int dot = c.lastIndexOf('.', p);
            String co = c.substring(0, dot), cn = c.substring(dot + 1, p), cd = c.substring(p);
            GameDiff.Cls cdecl = Exposure.declaring(port.a, co, 'm', cn, cd);
            GameDiff.Meth caller = cdecl == null ? null : cdecl.methods.get(cn + cd);
            String[] cm = port.route.method(co, cn, cd);
            GameDiff.Body x = body(oldCode, co, cn + cd, true), y = body(newCode, cm[0], cm[1] + cm[2], false);
            if (caller == null || x == null || y == null) {
                continue;
            }
            List<GameDiff.Node> before = calls(x, true).get(self), after = calls(y, false).get(self);
            if (before == null || after == null || before.size() != after.size()) {
                continue;
            }
            Type[] cwas = Type.getArgumentTypes(cd), cnow = Type.getArgumentTypes(cm[2]);
            int cfirst = caller.isStatic() ? 0 : 1;
            int[] cslots = Conversions.slots(cwas, cfirst), nslots = Conversions.slots(cnow, cfirst);
            for (int k = 0; k < before.size(); k++) {
                GameDiff.Node old = before.get(k), fresh2 = after.get(k);
                GameDiff.Node arg = old.kids[receiver(old) + i];
                int q = arg.op == 'p' && arg.value instanceof Integer s ? indexOf(cslots, s) : -1;
                if (q < 0) {
                    // The caller worked the old value out of something it now passes as it is (1.21.2's glyphs took
                    // red, green, blue and alpha that Font read out of the colour it now passes): the same steps.
                    vote(votes, derived(arg, fresh2, cwas, cnow, cfirst, want));
                    continue;
                }
                String sub = followed(cdecl.name, cn, cd, q, depth + 1);
                if (sub.isEmpty() || sub.equals(CONFLICT)) {
                    vote(votes, sub);
                    continue;
                }
                String[] parts = sub.split(" ", 2);
                int q2 = Integer.parseInt(parts[0]);
                if (q2 < 0 || q2 >= nslots.length) {
                    continue; // what the caller's object answers is not this method's
                }
                int found = -1;
                for (int j = 0; j < fresh.length; j++) {
                    GameDiff.Node there = fresh2.kids[receiver(fresh2) + j];
                    if (there.op == 'p' && Integer.valueOf(nslots[q2]).equals(there.value)) {
                        found = found == -1 ? j : -2;
                    }
                }
                if (found >= 0) {
                    vote(votes, found + (parts.length > 1 ? " " + parts[1] : ""));
                }
            }
        }
    }

    /**
     * An old argument (arg) worked out, through primitive conversions, arithmetic with a constant and static calls
     * whose other arguments are constants, from a value the new call (now) passes as one of its arguments: "j steps"
     * for that argument j, the steps in the order the value goes through them; "" when there is none, or the value
     * is a constant (a constant passed in both says nothing of which parameter it is).
     */
    String derived(GameDiff.Node arg, GameDiff.Node now, Type[] cwas, Type[] cnow, int cfirst, String want) {
        Map<Integer, Integer> same = new HashMap<>();
        if (cfirst == 1) {
            same.put(0, 0);
        }
        int[] paired = Overrides.pair(cnow, Arrays.stream(cwas).map(t -> Type.getType(port.desc(t.getDescriptor()))).toArray(Type[]::new));
        int[] oldAt = Conversions.slots(cwas, cfirst), newAt = Conversions.slots(cnow, cfirst);
        for (int k = 0; k < cwas.length; k++) {
            if (paired[k] >= 0) {
                same.put(oldAt[k], newAt[paired[k]]);
            }
        }
        Lining equal = new Lining(port, Map.of(), same);
        List<List<String>> levels = new ArrayList<>(); // outermost first
        GameDiff.Node n = arg;
        for (int depth = 0; depth < 8 && n != null; depth++) {
            if (!constant(n)) {
                int found = -1;
                for (int j = receiver(now); j < now.kids.length; j++) {
                    equal.budget = 20_000;
                    if (now.kids[j].type.equals(port.desc(n.type)) && equal.match(n, now.kids[j], new IdentityHashMap<>())) {
                        found = found == -1 ? j - receiver(now) : -2;
                    }
                }
                if (found >= 0) {
                    List<String> steps = new ArrayList<>();
                    for (int l = levels.size() - 1; l >= 0; l--) {
                        steps.addAll(levels.get(l));
                    }
                    return arg.type.equals(want) || port.desc(arg.type).equals(want) ? (found + (steps.isEmpty() ? "" : " " + String.join(" ", steps))) : "";
                }
            }
            if (n.op == 'x' && n.kids.length == 1 && Conversions.CONVERT.containsKey(n.kids[0].type + n.type)) {
                levels.add(List.of("x:" + n.kids[0].type + n.type));
                n = n.kids[0];
            } else if (n.op == 'o' && n.kids.length == 2 && n.kids[1].op == 'k' && n.kids[1].value != null && ARITHMETIC.contains(String.valueOf(n.value))) {
                levels.add(List.of("o:" + java.lang.classfile.Opcode.valueOf((String) n.value).bytecode() + ":" + n.kids[1].type + ":" + n.kids[1].value));
                n = n.kids[0];
            } else if (n.op == 's' && n.kids.length >= 1 && n.ref != null
                && Arrays.stream(n.kids, 1, n.kids.length).allMatch(k -> k.op == 'k' && k.value != null && WRITTEN.contains(k.type))) {
                GameDiff.Cls decl = Exposure.declaring(port.a, n.ref.owner(), 'm', n.ref.name(), n.ref.desc());
                String[] moved = port.route.method(decl == null ? n.ref.owner() : decl.name, n.ref.name(), n.ref.desc());
                List<String> level = new ArrayList<>();
                for (int k = 1; k < n.kids.length; k++) {
                    level.add("k:" + n.kids[k].type + ":" + n.kids[k].value);
                }
                level.add("s:" + declarer(moved[0], moved[1], moved[2]) + "." + moved[1] + moved[2]);
                levels.add(level);
                n = n.kids[0];
            } else {
                return "";
            }
        }
        return "";
    }

    /** Operations on a value and a constant that the steps can write again (the value first, then the constant). */
    static final Set<String> ARITHMETIC = Set.of("IADD", "ISUB", "IMUL", "IDIV", "IREM", "ISHL", "ISHR", "IUSHR", "IAND", "IOR", "IXOR",
        "LADD", "LSUB", "LMUL", "LDIV", "LSHL", "LSHR", "LUSHR", "LAND", "LOR", "LXOR", "FADD", "FSUB", "FMUL", "FDIV", "DADD", "DSUB", "DMUL", "DDIV");

    /** Whether a tree is made of constants alone. */
    static boolean constant(GameDiff.Node n) {
        if (n.op == 'p' || n.op == 'f' || n.op == 'g' || n.op == 'v' || n.op == 'l' || n.op == 'S') {
            return false;
        }
        if (n.op != 'k' && n.op != 'n' && n.op != 'x' && n.op != 'o' && n.op != 's') {
            return false;
        }
        for (GameDiff.Node k : n.kids) {
            if (!constant(k)) {
                return false;
            }
        }
        return n.op != 'o' || !"phi".equals(n.value);
    }

    static void vote(Map<String, Integer> votes, String reading) {
        if (!reading.isEmpty()) {
            votes.merge(reading, 1, Integer::sum);
        }
    }

    static int indexOf(int[] slots, int slot) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    /** How many of a call node's kids come before its arguments: the receiver of an instance call. */
    static int receiver(GameDiff.Node call) {
        return call.op == 'v' || call.op == 'S' ? 1 : 0;
    }

    /**
     * Lining up an old body (x) and a new one (y) by the calls they make: where an old call takes the hole parameter
     * as it was, what the same call in the new body takes there.
     */
    void along(GameDiff.Body x, GameDiff.Body y, int hole, Map<Integer, Integer> newParam, String want, int depth, Map<String, Integer> votes) {
        Map<String, List<GameDiff.Node>> before = calls(x, true), after = calls(y, false);
        for (var e : before.entrySet()) {
            List<GameDiff.Node> was = e.getValue(), now = after.get(e.getKey());
            for (int n = 0; n < was.size(); n++) {
                GameDiff.Node co = was.get(n);
                for (int k = receiver(co); k < co.kids.length; k++) {
                    GameDiff.Node arg = co.kids[k];
                    if (arg.op != 'p' || !Integer.valueOf(hole).equals(arg.value)) {
                        continue;
                    }
                    if (now == null || now.size() != was.size()) {
                        break; // the call is made another number of times: nothing lines up
                    }
                    place(co, now.get(n), k - receiver(co), newParam, want, depth, votes);
                }
            }
        }
    }

    /** What the new call cn has where the old call co took the hole parameter as its argument k. */
    void place(GameDiff.Node co, GameDiff.Node cn, int k, Map<Integer, Integer> newParam, String want, int depth, Map<String, Integer> votes) {
        int on = receiver(cn);
        if (port.desc(co.ref.desc()).equals(cn.ref.desc())) {
            // Only a reading counts: the new value passed on whole, or worked into something else there, says nothing
            // of which reading the old one was.
            GameDiff.Node there = cn.kids[on + k];
            String r = reading(there, newParam, want);
            vote(votes, r != null && !r.startsWith(MixinRetargets.THIS + " ") ? r : "");
            return;
        }
        // The call's own parameter changed with it: what the callee's new code reads from it.
        GameDiff.Cls decl = Exposure.declaring(port.a, co.ref.owner(), 'm', co.ref.name(), co.ref.desc());
        if (decl == null) {
            return;
        }
        String sub = followed(decl.name, co.ref.name(), co.ref.desc(), k, depth + 1);
        if (sub.isEmpty() || sub.equals(CONFLICT)) {
            vote(votes, sub);
            return;
        }
        String[] parts = sub.split(" ", 2);
        int k2 = Integer.parseInt(parts[0]);
        Type[] params = Type.getArgumentTypes(cn.ref.desc());
        if (k2 < 0 || k2 >= params.length) {
            return;
        }
        GameDiff.Node there = cn.kids[on + k2];
        String r = reading(there, newParam, params[k2].getDescriptor());
        if (r == null || r.startsWith(MixinRetargets.THIS + " ")) {
            return;
        }
        vote(votes, r + (parts.length > 1 ? " " + parts[1] : ""));
    }

    /**
     * The lambdas and method references the game makes for an abstract method: each one the old release made that
     * reads the parameter is followed into what it runs, where the new release makes it from the same method (a
     * method reference); one of a lambda, whose name each release numbers anew, answers nothing.
     */
    void made(GameCode oldCode, GameCode newCode, String owner, String name, String desc, String[] moved, String nowOwner, int i, int depth, Map<String, Integer> votes) {
        List<GameCode.Made> before = oldCode.made(owner, name, desc), after = newCode.made(nowOwner, moved[1], moved[2]);
        if (before == null || after == null) {
            return;
        }
        for (GameCode.Made o : before) {
            int shift = shift(o);
            int p = i + shift;
            Type[] params = Type.getArgumentTypes(o.desc());
            if (p < 0 || p >= params.length) {
                continue; // the receiver of an unbound reference, not a parameter
            }
            boolean isStatic = o.tag() == Opcodes.H_INVOKESTATIC || o.tag() == Opcodes.H_NEWINVOKESPECIAL;
            GameDiff.Body x = body(oldCode, o.owner(), o.name() + o.desc(), true);
            if (x == null || !mentions(x, Conversions.slots(params, isStatic ? 0 : 1)[p])) {
                continue; // it never reads it
            }
            GameCode.Made n = o.name().startsWith("lambda$") ? lambda(before, after, o) : null;
            if (!o.name().startsWith("lambda$")) {
                String[] went = port.route.method(o.owner(), o.name(), o.desc());
                n = after.stream().filter(m -> m.owner().equals(went[0]) && m.name().equals(went[1]) && m.desc().equals(went[2]) && m.tag() == o.tag())
                    .findFirst().orElse(null);
            }
            if (n == null) {
                continue; // a lambda the new release makes nowhere the same says nothing either way
            }
            String sub = followed(o.owner(), o.name(), o.desc(), p, depth + 1);
            if (sub.isEmpty() || sub.equals(CONFLICT)) {
                vote(votes, sub);
                continue;
            }
            String[] parts = sub.split(" ", 2);
            int j = Integer.parseInt(parts[0]) - shift(n);
            if (j >= 0) {
                vote(votes, j + (parts.length > 1 ? " " + parts[1] : ""));
            }
        }
    }

    /**
     * The new release's lambda for an old one: the one in the same place, where the method that makes the old lambda
     * (route-mapped) makes as many for the interface method in the new release, taken in order. Javac numbers a
     * class's lambdas anew in each release, so the name says nothing.
     */
    GameCode.Made lambda(List<GameCode.Made> before, List<GameCode.Made> after, GameCode.Made o) {
        int p = o.in().indexOf('(');
        int dot = o.in().lastIndexOf('.', p);
        String[] went = port.route.method(o.in().substring(0, dot), o.in().substring(dot + 1, p), o.in().substring(p));
        String in = went[0] + "." + went[1] + went[2];
        List<GameCode.Made> was = before.stream().filter(m -> m.in().equals(o.in())).toList();
        List<GameCode.Made> now = after.stream().filter(m -> m.in().equals(in)).toList();
        return was.size() == now.size() && now.get(was.indexOf(o)).name().startsWith("lambda$") ? now.get(was.indexOf(o)) : null;
    }

    /** Where the interface method's first parameter stands among the parameters of the method a lambda runs. */
    static int shift(GameCode.Made m) {
        boolean instance = m.tag() != Opcodes.H_INVOKESTATIC && m.tag() != Opcodes.H_NEWINVOKESPECIAL;
        return instance ? m.captures() == 0 ? -1 : m.captures() - 1 : m.captures();
    }

    /** Whether a tree reads one of the new parameters at all. */
    static boolean uses(GameDiff.Node n, Map<Integer, Integer> newParam) {
        if (n.op == 'p' && n.value instanceof Integer s && newParam.containsKey(s)) {
            return true;
        }
        for (GameDiff.Node k : n.kids) {
            if (uses(k, newParam)) {
                return true;
            }
        }
        return false;
    }

    static boolean mentions(GameDiff.Body body, int slot) {
        Map<Integer, Integer> one = Map.of(slot, 0);
        return body.statements.stream().anyMatch(s -> uses(s, one));
    }

    /**
     * A body's calls by the method each one calls in the new release (owner.name+desc, its declaring class), each list in
     * the order the code makes them.
     */
    Map<String, List<GameDiff.Node>> calls(GameDiff.Body body, boolean old) {
        Map<String, List<GameDiff.Node>> out = new LinkedHashMap<>();
        Deque<GameDiff.Node> todo = new ArrayDeque<>();
        for (int s = body.statements.size() - 1; s >= 0; s--) {
            todo.push(body.statements.get(s));
        }
        while (!todo.isEmpty()) {
            GameDiff.Node n = todo.pop();
            if (n.ref != null && n.ref.kind() == 'm' && n.ref.desc() != null && (n.op == 'v' || n.op == 's' || n.op == 'w' || n.op == 'S' && "init".equals(n.value))) {
                out.computeIfAbsent(callKey(n.ref, old), k -> new ArrayList<>()).add(n);
            }
            for (int k = n.kids.length - 1; k >= 0; k--) {
                todo.push(n.kids[k]);
            }
        }
        return out;
    }

    String callKey(GameDiff.Ref r, boolean old) {
        if (!old) {
            return declarer(r.owner(), r.name(), r.desc()) + "." + r.name() + r.desc();
        }
        GameDiff.Cls decl = Exposure.declaring(port.a, r.owner(), 'm', r.name(), r.desc());
        String[] moved = port.route.method(decl == null ? r.owner() : decl.name, r.name(), r.desc());
        return declarer(moved[0], moved[1], moved[2]) + "." + moved[1] + moved[2];
    }

    /** A game method's body (body), read once. */
    GameDiff.Body body(GameCode code, String owner, String key, boolean old) {
        String k = (old ? "a " : "b ") + owner + "." + key;
        Optional<GameDiff.Body> known = bodies.get(k);
        if (known == null) {
            known = Optional.ofNullable(body(code, owner, key));
            bodies.put(k, known);
        }
        return known.orElse(null);
    }

    final Map<String, List<String>> overridersOf = new HashMap<>();

    /** The old release's classes that declare name+desc and are owner or extend it. Asked again for every override of a mod, and the answer is every game class read once. */
    List<String> overriders(String owner, String name, String desc) {
        return overridersOf.computeIfAbsent(owner + "." + name + desc, k -> {
            List<String> out = new ArrayList<>();
            for (GameDiff.Cls c : port.links.declaring(port.a, name + desc, owner)) {
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

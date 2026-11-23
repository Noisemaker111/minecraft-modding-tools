import java.io.*;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.SimpleRemapper;

/**
 * JarPort - move a compiled mod (.jar) to another game release without its source and without a build.
 *
 * Every reference the mod's classes make to the game (calls, field access, method references, overrides, and what its
 * mixins name: @Shadow members, injection targets, @At targets, accessors) is looked up where the old release declares
 * it, followed through GameDiff's route map, and linked against the new release exactly as the JVM and Mixin will link
 * it: same owner chain, name and descriptor. A reference that links is rewritten to the new name; one that does not is
 * reported, never guessed. Class renames are applied everywhere. The mod's metadata is moved to the new release, nested
 * mods (jar-in-jar) are ported the same way, and signatures that no longer match are removed.
 *
 *   java JarPort --from 1.21.1 --to 1.21.2 --map route.json [--loader neoforge] [--fixes knowledge/fixes.json] --out OUT.jar|DIR [--report R.json] IN.jar ...
 *
 * --fixes applies the port rules' "inClass" replacements (FixExpr) to members nothing in the game's own code replaces.
 *
 * The report lists per jar what was rewritten and every reference that does not link in the new release ("unlinked");
 * a jar with no unlinked reference is expected to load. Exit 0 when every jar was written, whatever it reports.
 */
public final class JarPort {

    final GameDiff.Snap a, b;
    String loader; // "neoforge" or "fabric": the loader ported for, which a rule may name
    final Exposure.Route route;
    final String to, loaderTo;
    final Map<String, String> classMap = new HashMap<>();
    final Map<String, String> inverse = new HashMap<>();
    final Map<String, List<Map<String, Object>>> removals = new HashMap<>(); // owner.name+desc -> rewrite to nothing

    /** One jar's classes (old names), what was changed in it, and what does not link. */
    final class Mod {
        final Map<String, GameDiff.RawCls> classes = new HashMap<>();
        final Map<String, Integer> maxLocals = new HashMap<>(); // class.method+desc
        final Map<String, String[]> renamedDecl = new HashMap<>(); // class.name+desc (or class.field) -> {name, desc}
        final Map<String, List<Copy>> copies = new HashMap<>(); // class -> game code copied into it
        final Map<String, String> superHook = new HashMap<>(); // class.name+desc of a moved override -> what its super call to the game becomes: the hook's name, or "own:" + the class's renamed hook
        final Map<String, List<String>> mixinTargets = new HashMap<>(); // mixin class -> target classes (old names)
        final Map<String, String[]> accessors = new HashMap<>(); // class.method+desc -> {Accessor|Invoker, value or null}
        final Set<String> shadowFields = new HashSet<>(); // mixin class.field annotated @Shadow; its other fields are its own
        final Map<String, String> shadowTargets = new HashMap<>(); // mixin class.field -> the target (old name) whose field the update broke
        final Map<String, String> accessorTargets = new HashMap<>(); // class.method+desc -> the target's new name to write
        final Set<String> widened = new HashSet<>(); // "owner.name" or "owner.name+desc" the mod's access transformer opens
        final Set<String> unlinked = new TreeSet<>();
        final Set<String> silent = new TreeSet<>(); // overrides the new release no longer calls: the mod loads, a hook is quiet
        final Set<String> skipped = new TreeSet<>(); // nested jars the loader does not load on the new release, each with why: left as they are
        final Set<String> refused = new TreeSet<>(); // why a mixin handler did not follow its target (why), for the scoreboard
        final Set<String> data = new TreeSet<>(); // game ids the mod's data names that the release lacks (DataIds), when judged
        final Map<String, Integer> changed = new TreeMap<>();
        String self; // the class being rewritten, and what a method added to it must respect
        boolean selfInterface;
        int selfVersion;
        final Map<String, Added> added = new LinkedHashMap<>(); // rule replacement + operands -> method added to self
        final Map<String, Boolean> subscribers = new HashMap<>(); // @EventBusSubscriber class -> whether it names its bus
        final Map<String, List<String>> listeners = new HashMap<>(); // class -> event types of its @SubscribeEvent methods (old names)
        final Set<String> toModBus = new HashSet<>(); // subscriber classes whose annotation gets bus = MOD
        final Map<String, List<Object[]>> subscriberValues = new HashMap<>(); // its other annotation values: {name, value} or {name, [desc, enum]...}
        final Map<String, List<Listener>> listenerMethods = new HashMap<>();
        final Map<String, List<Listener>> split = new HashMap<>(); // mixed subscriber -> its mod-bus listeners, moved to a companion
        final Map<String, byte[]> bytes = new HashMap<>(); // class -> its bytes as read, for what a method body reads
        final Map<String, Injector> injectors = new HashMap<>(); // mixin class.handler+desc -> its @Inject, @Redirect...
        final Map<String, String> retargets = new HashMap<>(); // class.handler+desc + " " + annotation string -> what is written instead
        final Map<String, String[]> points = new HashMap<>(); // class.handler+desc + " " + an @At's value, target and ordinal -> those written instead
        final Map<String, Adapter> adapters = new HashMap<>(); // class.handler+desc -> the handler re-declared for its target's new parameters
        final Set<String> staticized = new HashSet<>(); // class.handler+desc of handlers made static, as their targets now are
        final Map<String, Integer> argIndexes = new HashMap<>(); // class.handler+desc of a @ModifyArg -> the index its argument has in the call's new signature
        final Map<String, List<Bridge>> bridges = new HashMap<>(); // class -> methods added with a game method's new signature
        final Set<String> grants = new TreeSet<>(); // lines the port adds to the mod's access transformer (new names)
        boolean canWiden; // the loader reads this jar's access transformer
        final Set<String> kept = new HashSet<>(); // name+desc of moved methods also kept as they were, for calls that cannot follow
        final Set<String> unmoved = new HashSet<>(); // class.name+desc of bodies a call in them cannot be given its arguments
        final Map<String, Object[]> twins = new LinkedHashMap<>(); // class.name+desc -> {parameter, new type, steps} (Sites.twin)
        final Map<String, Integer> requires = new HashMap<>(); // mixin class -> its config's injectors.defaultRequire
        final Map<String, String[]> supers = new HashMap<>(); // class -> {removed superclass (old name), what it extends now (new name)}
        final Set<String> foreign = new HashSet<>(); // entries of classes built for another loader (otherLoader), left as they are
        int lowered; // the Java this jar's classes were lowered to (lowerJava), 0 when the new release's Java runs them as they came

        void change(String kind) {
            changed.merge(kind, 1, Integer::sum);
        }
    }

    /** a and b are the old and the new release (with their loader); map is GameDiff's route map between them. */
    JarPort(GameDiff.Snap a, GameDiff.Snap b, Path map) throws IOException {
        this.a = a;
        this.b = b;
        loader = b.loaderVersion != null ? "neoforge" : "fabric";
        to = b.version;
        loaderTo = b.loaderVersion;
        route = Exposure.Route.read(map);
        // A library the new release no longer lists may still be there at run time (the loader brings ASM, for one): only
        // what changed inside a library both releases list is checked. A class gone from a library the new release still
        // lists is gone at run time (1.20.5's DataFixerUpper dropped DataResult.PartialResult).
        Set<String> listed = new HashSet<>();
        for (GameDiff.Cls c : b.classes.values()) {
            if (c.library && c.artifact != null) {
                listed.add(c.artifact);
            }
        }
        for (GameDiff.Cls c : a.classes.values()) {
            if (c.library && !b.classes.containsKey(c.name) && !listed.contains(c.artifact)) {
                b.classes.put(c.name, c);
            }
        }
        classMap.putAll(route.classes);
        for (var e : route.classRewrites.entrySet()) {
            classMap.putIfAbsent(e.getKey(), e.getValue());
        }
        classMap.entrySet().removeIf(e -> e.getKey().equals(e.getValue()) || !b.classes.containsKey(e.getValue()));
        for (var e : classMap.entrySet()) {
            inverse.putIfAbsent(e.getValue(), e.getKey());
        }
        Map<String, Object> raw = Json.read(map);
        for (Object o : Json.array(raw.getOrDefault("rewrites", List.of()))) {
            var w = Json.object(o);
            String key = Exposure.Route.internal(Json.text(w, "owner", "")) + "." + Json.text(w, "from", "") + Json.text(w, "desc", "");
            if (Json.text(w, "to", "").isEmpty() && Json.text(w, "toOwner", "").isEmpty()) {
                removals.computeIfAbsent(key, k -> new ArrayList<>()).add(w);
            } else if (majority(w)) {
                rewrites.computeIfAbsent(key, k -> new ArrayList<>()).add(new Call(
                    Exposure.Route.internal(Json.text(w, "toOwner", "")), Json.text(w, "to", ""), Json.text(w, "newDesc", ""),
                    Boolean.TRUE.equals(w.get("newStatic"))
                ));
            }
        }
        for (Object o : Json.array(raw.getOrDefault("argDefaults", List.of()))) {
            var w = Json.object(o);
            if (majority(w)) {
                String key = Exposure.Route.internal(Json.text(w, "owner", "")) + "." + Json.text(w, "from", "") + Json.text(w, "desc", "");
                defaults.put(key, new Default(
                    new Call(Exposure.Route.internal(Json.text(w, "toOwner", "")), Json.text(w, "to", ""), Json.text(w, "newDesc", ""), false),
                    ((Number) w.get("index")).intValue(), Json.text(w, "value", "")
                ));
            }
        }
        for (Object o : Json.array(raw.getOrDefault("chains", List.of()))) {
            var w = Json.object(o);
            if (majority(w)) {
                String key = Exposure.Route.internal(Json.text(w, "owner", "")) + "." + Json.text(w, "from", "") + Json.text(w, "desc", "");
                chains.put(key, new Chain(Exposure.Route.internal(Json.text(w, "newOwner", "")), Json.text(w, "steps", "")));
            }
        }
        // What the game's own code computes in place of a member (GameDiff's Learner), applied as a rule's inClass is: on
        // any update of the port whose old member the release ported from has by that name (a mod names it so), and
        // compiled against the release ported to, so an expression a later update broke is refused, not written.
        for (Object o : Json.array(raw.getOrDefault("retyped", List.of()))) {
            var w = Json.object(o);
            String owner = Exposure.Route.internal(Json.text(w, "owner", ""));
            if (majority(w) && covers(Json.text(w, "step", ""), "")) {
                retypedCalls.computeIfAbsent(owner, k -> new ArrayList<>()).add(new RetypedCall(Json.text(w, "from", ""), Json.text(w, "desc", ""),
                    ((Number) w.get("at")).intValue(), Json.text(w, "as", ""), Json.text(w, "expr", "")));
            }
        }
        for (Object o : Json.array(raw.getOrDefault("learned", List.of()))) {
            var w = Json.object(o);
            String owner = Exposure.Route.internal(Json.text(w, "owner", "")), from = Json.text(w, "from", ""), desc = Json.text(w, "desc", "");
            String member = from.endsWith("=") ? from.substring(0, from.length() - 1) : from; // "name=": a write
            boolean named = desc.isEmpty() ? a.field(owner, member) != null : a.method(owner, from, desc) != null;
            if (w.get("then") != null) {
                if (majority(w) && named && covers(Json.text(w, "step", ""), "")) {
                    chain(w, owner, from, desc);
                }
                continue;
            }
            if (majority(w) && named && covers(Json.text(w, "step", ""), "") && Json.text(w, "expr", "").isEmpty()) {
                // A call the game removed with nothing in its place: dropped as a removal is, its operands still evaluated.
                removals.computeIfAbsent(owner + "." + from + desc, k -> new ArrayList<>()).add(w);
            } else if (majority(w) && named && covers(Json.text(w, "step", ""), "")) {
                fixes.computeIfAbsent(owner, k -> new ArrayList<>()).add(new Fix("game " + Json.text(w, "step", ""),
                    Pattern.compile(Pattern.quote(from)), desc.isEmpty() ? null : desc.substring(0, desc.indexOf(')') + 1), Json.text(w, "expr", ""),
                    w.get("when") == null ? null : Json.text(w, "when", "")));
            }
        }
    }

    final List<PortChains.Spec> pairs = new ArrayList<>();

    /** Whether owner (the mod's, the old release's or the JDK's) is an interface, for a call PortChains writes again; null if unknown. */
    Boolean interfaceIn(Map<String, byte[]> entries, String owner) {
        byte[] own = entries.get(owner + ".class");
        if (own != null) {
            return (new ClassReader(own).getAccess() & Opcodes.ACC_INTERFACE) != 0;
        }
        GameDiff.Cls c = a.classes.get(owner);
        c = c != null ? c : a.libraries.get(owner);
        if (c != null) {
            return (c.flags & Opcodes.ACC_INTERFACE) != 0;
        }
        try {
            return Class.forName(owner.replace('/', '.'), false, null).isInterface();
        } catch (ClassNotFoundException | LinkageError unknown) {
            return null;
        }
    }

    /** A member of a library class that both releases declare with the same type and access. */
    boolean unchangedLibrary(char kind, String owner, String name, String desc) {
        GameDiff.Cls old = a.classes.get(owner), now = b.classes.get(owner);
        if (old == null || now == null || !old.library) {
            return false;
        }
        if (kind == 'f' || kind == 'r') {
            GameDiff.Fld x = old.fields.get(name), y = now.fields.get(name);
            return x != null && y != null && x.desc.equals(y.desc) && x.flags == y.flags;
        }
        GameDiff.Meth x = old.methods.get(name + desc), y = now.methods.get(name + desc);
        return x != null && y != null && x.flags == y.flags;
    }

    /** A library's abstract method the old release's copy of that library had too: a mod that skips it skipped it before the port. */
    boolean alreadyAbstract(GameDiff.Cls k, GameDiff.Meth m) {
        GameDiff.Cls old = k.library ? a.classes.get(k.name) : null;
        GameDiff.Meth before = old == null ? null : old.methods.get(m.name + m.desc);
        return before != null && (before.flags & Opcodes.ACC_ABSTRACT) != 0;
    }

    /**
     * Two old members replaced together (GameDiff's chains): a stand-in static method of the first one's class, taking both
     * members' operands, is added to the old release with the learned expression as its replacement; PortChains rewrites
     * the mod's pair into a call of it before the class is read.
     */
    void chain(Map<String, Object> w, String owner, String from, String desc) {
        var then = Json.object(w.get("then"));
        String yOwner = Exposure.Route.internal(Json.text(then, "owner", "")), yName = Json.text(then, "from", ""), yDesc = Json.text(then, "desc", "");
        GameDiff.Cls xc = a.classes.get(owner);
        boolean field = desc.isEmpty();
        GameDiff.Fld f = field ? a.field(owner, from) : null;
        GameDiff.Meth m = field ? null : a.method(owner, from, desc);
        GameDiff.Meth y = a.method(yOwner, yName, yDesc);
        int at = then.get("at") instanceof Number n ? n.intValue() : 0;
        if (xc == null || y == null) {
            return;
        }
        boolean xStatic = field ? (f.flags & Opcodes.ACC_STATIC) != 0 : m.isStatic();
        boolean first = "first".equals(Json.text(w, "result", ""));
        StringBuilder params = new StringBuilder("(");
        if (at == -2) {
            // A late chain: X's operands, then Y's arguments (the value the new release takes where the object is made); the
            // stand-in gives what X gave, and Y, on the object later, only drops its argument (PortChains).
            if (field || y.isStatic() || m == null) {
                return;
            }
            if (!xStatic) {
                params.append('L').append(owner).append(';');
            }
            params.append(desc, 1, desc.indexOf(')')).append(yDesc, 1, yDesc.indexOf(')')).append(')');
            String fake = from + "+" + yName + "+late", fakeDesc = params + desc.substring(desc.indexOf(')') + 1);
            xc.methods.put(fake + fakeDesc, new GameDiff.Meth(xc, fake, fakeDesc, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
            fixes.computeIfAbsent(owner, k -> new ArrayList<>()).add(new Fix("game chain " + Json.text(w, "step", ""), Pattern.compile(Pattern.quote(fake)),
                params.toString(), Json.text(w, "expr", "")));
            pairs.add(new PortChains.Spec('m', xStatic, owner, from, desc, yOwner, yName, yDesc, false, -2, false, fake, fakeDesc,
                (xc.flags & Opcodes.ACC_INTERFACE) != 0));
            return;
        }
        if (at < 0) {
            // A sibling: Y on X's receiver, with X's value (kept in a local) passed after Y's receiver.
            if (field || y.isStatic() || xStatic) {
                return;
            }
            params.append('L').append(yOwner).append(';').append(desc.substring(desc.indexOf(')') + 1)).append(yDesc, 1, yDesc.indexOf(')')).append(')');
            String fake = from + "+" + yName + "+r", fakeDesc = params + yDesc.substring(yDesc.indexOf(')') + 1);
            xc.methods.put(fake + fakeDesc, new GameDiff.Meth(xc, fake, fakeDesc, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
            fixes.computeIfAbsent(owner, k -> new ArrayList<>()).add(new Fix("game chain " + Json.text(w, "step", ""), Pattern.compile(Pattern.quote(fake)),
                params.toString(), Json.text(w, "expr", "")));
            pairs.add(new PortChains.Spec('m', false, owner, from, desc, yOwner, yName, yDesc, false, -1, false, fake, fakeDesc,
                (xc.flags & Opcodes.ACC_INTERFACE) != 0));
            return;
        }
        if (!xStatic) {
            params.append('L').append(owner).append(';');
        }
        if (!field) {
            params.append(desc, 1, desc.indexOf(')'));
        }
        List<Type> yOps = new ArrayList<>();
        if (!y.isStatic()) {
            yOps.add(Type.getObjectType(yOwner));
        }
        yOps.addAll(List.of(Type.getArgumentTypes(yDesc)));
        if (at >= yOps.size()) {
            return;
        }
        for (int i = 0; i < yOps.size(); i++) {
            if (i != at) {
                params.append(yOps.get(i).getDescriptor());
            }
        }
        params.append(')');
        String result = first ? (field ? f.desc : desc.substring(desc.indexOf(')') + 1)) : yDesc.substring(yDesc.indexOf(')') + 1);
        String fake = from + "+" + yName + (at == 0 ? "" : "+" + at), fakeDesc = params + result;
        xc.methods.put(fake + fakeDesc, new GameDiff.Meth(xc, fake, fakeDesc, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
        fixes.computeIfAbsent(owner, k -> new ArrayList<>()).add(new Fix("game chain " + Json.text(w, "step", ""), Pattern.compile(Pattern.quote(fake)),
            params.toString(), Json.text(w, "expr", "")));
        pairs.add(new PortChains.Spec(field ? 'f' : 'm', xStatic, owner, from, desc, yOwner, yName, yDesc, y.isStatic(), at, first, fake, fakeDesc,
            (xc.flags & Opcodes.ACC_INTERFACE) != 0));
    }

    /**
     * A call whose operand at ($0 its receiver) was a parameter the game retyped, and what the game's own code wrote in its
     * place with that operand as the new type (GameDiff.Retyped): font.draw(pose, ...) -> $1.drawString($0, ...).
     */
    record RetypedCall(String name, String desc, int at, String as, String expr) {}

    final Map<String, List<RetypedCall>> retypedCalls = new HashMap<>(); // owner (old names) -> calls

    /** A replacement the game's own code shows in more than half of the places it changed. */
    static boolean majority(Map<String, Object> w) {
        Object votes = w.get("votes"), of = w.get("of");
        return !(votes instanceof Number v) || !(of instanceof Number n) || v.intValue() * 2 > n.intValue();
    }

    /** A call in the new release's names. */
    record Call(String owner, String name, String desc, boolean isStatic) {}

    /** A parameter the game added, and the one value its own code passes there ("a.b.C::m", "a.b.C.FIELD", "a.b.C.m()"). */
    record Default(Call to, int index, String value) {}

    /** What replaces a member read from the same receiver: "f:name" and "m:name" steps; the last takes the old call's arguments. */
    record Chain(String newOwner, String steps) {}

    final Map<String, List<Call>> rewrites = new HashMap<>(); // owner.name+desc (old names) -> calls the game's code switched to
    final Map<String, Default> defaults = new HashMap<>();
    final Map<String, Chain> chains = new HashMap<>(); // owner.name+desc, or owner.name for a field

    /**
     * What a port holds whatever its workers, measured on Fabric 1.20.1 -> 26.3's 147 mods (554 MB of jars): one small mod
     * peaked at 1.4 GB after collections (the step's releases, the next one read ahead, their code), and the 147 mods at
     * 3.3 to 3.7 GB with 4 workers (each jar is kept between steps, uncompressed).
     */
    static final long RELEASES_HEAP = 1536L << 20;
    static final long JAR_HEAP = 4;
    /**
     * Room per worker for the mod it is porting: live heap grew only about 50 MB a worker (3.7 GB with 4, 4.4 GB with 16),
     * but 4 workers left 1.3 GB spent 77 s in collections and 8 workers left 3.3 GB spent 29 s, as long as 4 with 6 GB to spare.
     */
    static final long PORT_WORKER = 400L << 20;

    public static void main(String[] args) throws Exception {
        String from = null, to = null, out = null, loader = null, report = null, book = null, chain = null, plan = null;
        boolean runtime = false, hopsOnly = false;
        Path originalsDir = null;
        List<String> via = new ArrayList<>(), maps = new ArrayList<>();
        List<Path> jars = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--runtime-names" -> runtime = true;
                case "--from" -> from = args[++i];
                case "--to" -> to = args[++i];
                case "--via" -> via.addAll(Arrays.stream(args[++i].split(",")).filter(v -> !v.isBlank()).toList());
                case "--map" -> maps.add(args[++i]);
                case "--hops" -> hopsOnly = true;
                case "--chain" -> chain = args[++i];
                case "--out" -> out = args[++i];
                case "--loader" -> loader = args[++i];
                case "--report" -> report = args[++i];
                case "--fixes" -> book = args[++i];
                case "--cache" -> VersionMap.cache = Path.of(args[++i]).toAbsolutePath();
                case "--originals" -> originalsDir = Path.of(args[++i]);
                case "--plan" -> plan = args[++i];
                default -> jars.add(Path.of(args[i]));
            }
        }
        if (hopsOnly) {
            if (chain == null || book == null) {
                System.err.println("usage: JarPort --hops --chain V1,V2,... --fixes knowledge/fixes.json [--loader neoforge|fabric]");
                System.exit(2);
            }
            System.out.println(String.join(",", hops(List.of(chain.split(",")), Path.of(book).resolveSibling("standins"), loader == null ? "" : loader)));
            return;
        }
        List<Target> targets = new ArrayList<>();
        if (plan != null) {
            for (Object o : Json.array(Json.read(Path.of(plan)).get("ports"))) {
                Map<String, Object> t = Json.object(o);
                List<String> stops = new ArrayList<>();
                stops.add(from);
                for (Object v : Json.array(t.getOrDefault("via", List.of()))) {
                    stops.add(v.toString());
                }
                stops.add(Json.text(t, "to", ""));
                targets.add(new Target(stops, Json.array(t.get("maps")).stream().map(Object::toString).toList(), Path.of(Json.text(t, "out", "")),
                    t.get("report") == null ? null : Json.text(t, "report", "")));
            }
        } else if (to != null && out != null) {
            List<String> stops = new ArrayList<>();
            stops.add(from);
            stops.addAll(via);
            stops.add(to);
            targets.add(new Target(stops, maps, Path.of(out), report));
        }
        if (from == null || targets.isEmpty() || jars.isEmpty() || targets.stream().anyMatch(t -> t.maps().size() != t.stops().size() - 1)) {
            System.err.println("usage: JarPort --from V [--via V,...] --to V --map route.json (one per step) [--loader neoforge|fabric] [--runtime-names] [--fixes knowledge/fixes.json] --out OUT.jar|DIR [--report R.json] IN.jar ...");
            System.err.println("       JarPort --from V --plan PLAN.json ... IN.jar ...   (PLAN: {\"ports\": [{\"to\", \"via\", \"maps\", \"out\", \"report\"}, ...]}, ports that share their steps before the last)");
            System.exit(2);
        }
        portAll(targets, jars, loader, runtime, book, originalsDir);
    }

    /** One port of a plan: the releases it stops at (the first the release ported from), each step's route map, and where its jars and report go. */
    record Target(List<String> stops, List<String> maps, Path out, String report) {
        String to() {
            return stops.get(stops.size() - 1);
        }
    }

    /**
     * Ports the jars to every target of a plan in one process. The targets share every step before their last (a port to
     * a later release passes the stops of a port to an earlier one: JarPort.hops), so those steps run once, as one trunk,
     * and each target branches off it with its own last step: the most-used release's mods ported to every release
     * after it took one step per release instead of one per release times the releases before it. A branch reads its
     * own copy of its release and of every result before it, so what one target's last step adds is never seen by
     * another, and each target's jars and report are what a port to it alone writes. A jar whose nested jars the port
     * carries differ by target (bundleChoices: a bundle of one module per release) is ported to each target on its own,
     * as its very first step already depends on the release the port ends at.
     */
    static void portAll(List<Target> targets, List<Path> jars, String loader, boolean runtime, String book, Path originalsDir) throws Exception {
        List<Path> apart = new ArrayList<>(), shared = new ArrayList<>();
        for (Path jar : jars) {
            (targets.size() > 1 && !sameChoices(jar, targets, loader) ? apart : shared).add(jar);
        }
        Map<Target, Trunk> own = new HashMap<>();
        if (!apart.isEmpty()) {
            for (Target t : targets) {
                portTrunk(List.of(t), apart, loader, runtime, book, originalsDir, own::put);
            }
        }
        java.util.function.BiConsumer<Target, Trunk> written = (t, state) -> {
            if (own.containsKey(t)) {
                state.add(own.get(t));
            }
            try {
                write(t, jars, state, loader);
            } catch (IOException failed) {
                throw new java.io.UncheckedIOException(failed);
            }
        };
        if (shared.isEmpty()) {
            targets.forEach(t -> written.accept(t, new Trunk()));
        } else {
            portTrunk(targets, shared, loader, runtime, book, originalsDir, written);
        }
    }

    /** Whether the port carries the same nested jars of jar to every target (bundleChoices); a jar it cannot read is shared, and its port says why. */
    static boolean sameChoices(Path jar, List<Target> targets, String loader) {
        if (!"fabric".equals(loader)) {
            return true;
        }
        try {
            byte[] bytes = Files.readAllBytes(jar);
            String name = jar.getFileName().toString();
            Map<String, Map<String, String>> first = null;
            for (Target t : targets) {
                Map<String, Map<String, String>> choices = new TreeMap<>();
                bundleChoices(bytes, name, t.stops().get(0), t.to(), choices);
                if (first == null) {
                    first = choices;
                } else if (!first.equals(choices)) {
                    return false;
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return true;
        }
        return true;
    }

    /** The jars of jar and every nested jar it carries that the loader passes over from to until (notLoaded), by name as port names them. */
    static void bundleChoices(byte[] jar, String name, String from, String until, Map<String, Map<String, String>> out) throws IOException {
        Map<String, byte[]> entries = entries(jar, name, new HashMap<>());
        Map<String, String> skipped = fabricNotLoaded(entries, from, until);
        if (!skipped.isEmpty()) {
            out.put(name, skipped);
        }
        for (String n : nested(entries)) {
            if (!skipped.containsKey(n)) {
                bundleChoices(entries.get(n), name + "!" + n.substring(n.lastIndexOf('/') + 1), from, until, out);
            }
        }
    }

    /** Ports jars along the plan's trunk and hands each target's result to branched as its last step ends. */
    static void portTrunk(List<Target> targets, List<Path> jars, String loader, boolean runtime, String book, Path originalsDir,
                          java.util.function.BiConsumer<Target, Trunk> branched) throws Exception {
        Target longest = targets.stream().max(Comparator.comparingInt((Target t) -> t.stops().size())).orElseThrow();
        List<String> trunk = longest.stops().subList(0, longest.stops().size() - 1);
        List<String> trunkMaps = longest.maps().subList(0, longest.maps().size() - 1);
        Map<Integer, List<Target>> branches = new TreeMap<>();
        for (Target t : targets) {
            int at = t.stops().size() - 2;
            if (!t.stops().subList(0, at + 1).equals(trunk.subList(0, at + 1)) || !t.maps().subList(0, at).equals(trunkMaps.subList(0, at))) {
                throw new IllegalArgumentException("the ports of a plan share every step before their last: " + t.stops() + " and " + longest.stops());
            }
            branches.computeIfAbsent(at, k -> new ArrayList<>()).add(t);
        }
        // Step by step, every jar through one step before the next: each step holds two releases of the game, and a port
        // across a dozen updates would not fit holding all of them. Within a step the jars are ported on every processor,
        // each worker with a porter of its own on the step's two releases: a jar's port does not depend on the jars beside it.
        long jarBytes = 0;
        for (Path jar : jars) {
            jarBytes += Files.size(jar);
        }
        Integer asked = Integer.getInteger("jarport.workers");
        int workers = asked != null ? Math.max(1, Math.min(jars.size(), asked)) : GameDiff.workers(jars.size(), PORT_WORKER, RELEASES_HEAP + JAR_HEAP * jarBytes);
        // Biggest first, so no big jar starts when the others are done.
        List<Path> order = new ArrayList<>(jars);
        order.sort(Comparator.comparingLong((Path j) -> j.toFile().length()).reversed().thenComparing(Path::toString));
        // The releases in the order the steps read them, each read while the step before it ports: a branch's release,
        // then the trunk's next one, which is also the old release of the next branches.
        List<String> reads = new ArrayList<>();
        for (int p = 0; p < trunk.size(); p++) {
            for (Target t : branches.getOrDefault(p, List.of())) {
                reads.add(t.to());
            }
            if (p + 1 < trunk.size()) {
                reads.add(trunk.get(p + 1));
            }
        }
        Reads reader = new Reads(reads, loader);
        Trunk state = new Trunk();
        GameDiff.Snap was = Exposure.snap(trunk.get(0), loader);
        for (int p = 0; p < trunk.size(); p++) {
            for (Target t : branches.getOrDefault(p, List.of())) {
                Trunk mine = state.copy();
                GameDiff.Snap now = reader.next();
                step(was, now, t.maps().get(p), trunk.get(0), trunk.get(p), t.to(), t.to(), true, p == 0, mine, order, workers, loader, runtime, book, originalsDir);
                branched.accept(t, mine);
                done(t.to(), reader);
            }
            if (p + 1 < trunk.size()) {
                GameDiff.Snap now = reader.next();
                step(was, now, trunkMaps.get(p), trunk.get(0), trunk.get(p), trunk.get(p + 1), longest.to(), false, p == 0, state, order, workers, loader, runtime, book, originalsDir);
                done(trunk.get(p), reader);
                was = now;
            }
        }
        reader.shutdown();
    }

    /** Every jar's port so far: its bytes after the last step, each step's results, and the step it failed at. */
    static final class Trunk {
        final Map<Path, byte[]> bytes = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<Path, Map<String, Set<String>>> originals = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<Path, List<Map<String, Object>>> nested = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<Path, String> errors = new java.util.concurrent.ConcurrentHashMap<>();
        // Each jar's choice of nested jars (JarPort.decided), made at its first step; a branch shares it, as the jars on
        // one trunk choose the same for every target (portAll).
        Map<String, Map<String, String>> decided = new java.util.concurrent.ConcurrentHashMap<>();

        /** Another port's jars, of other names, joined to these. */
        void add(Trunk other) {
            bytes.putAll(other.bytes);
            originals.putAll(other.originals);
            nested.putAll(other.nested);
            errors.putAll(other.errors);
        }

        /** A copy a branch ports on: the bytes are never written in place, but each step adds to and edits its last results. */
        Trunk copy() {
            Trunk c = new Trunk();
            c.decided = decided;
            c.bytes.putAll(bytes);
            c.originals.putAll(originals);
            c.errors.putAll(errors);
            for (var e : nested.entrySet()) {
                List<Map<String, Object>> results = new ArrayList<>();
                for (Map<String, Object> r : e.getValue()) {
                    @SuppressWarnings("unchecked") Map<String, Object> copied = (Map<String, Object>) copied(r);
                    results.add(copied);
                }
                c.nested.put(e.getKey(), results);
            }
            return c;
        }
    }

    /** A step's result as it was: maps and lists copied, sorted maps kept sorted. */
    static Object copied(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<Object, Object> c = m instanceof SortedMap ? new TreeMap<>() : new LinkedHashMap<>();
            m.forEach((k, v) -> c.put(k, copied(v)));
            return c;
        }
        if (value instanceof List<?> l) {
            List<Object> c = new ArrayList<>();
            for (Object v : l) {
                c.add(copied(v));
            }
            return c;
        }
        return value;
    }

    /** Releases read one ahead of the step that needs them, on a thread of their own. */
    static final class Reads {
        final java.util.concurrent.ExecutorService thread = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        final Deque<String> upcoming;
        final String loader;
        java.util.concurrent.Future<GameDiff.Snap> coming;

        Reads(List<String> versions, String loader) {
            upcoming = new ArrayDeque<>(versions);
            this.loader = loader;
            ahead();
        }

        void ahead() {
            String version = upcoming.peekFirst();
            coming = version == null ? null : thread.submit(() -> Exposure.snap(version, loader));
        }

        GameDiff.Snap next() throws Exception {
            try {
                return coming.get();
            } catch (java.util.concurrent.ExecutionException failed) {
                throw failed.getCause() instanceof Exception e ? e : failed;
            } finally {
                upcoming.pollFirst();
                ahead();
            }
        }

        boolean later(String version) {
            return upcoming.contains(version);
        }

        void shutdown() {
            thread.shutdown();
        }
    }

    /** Lets go of what was read for a release no step still to come reads. */
    static void done(String version, Reads reader) {
        if (reader.later(version)) {
            return;
        }
        Exposure.forget(version);
        Object[] gone = GameCode.READ.remove(version);
        if (gone != null) {
            synchronized (GameCode.CALLERS) {
                GameCode.CALLERS.remove(gone[0]);
            }
        }
    }

    /** Every jar of state through one step from was to now; last: the step into the release a target asked for. */
    static void step(GameDiff.Snap was, GameDiff.Snap now, String map, String origin, String from, String to, String portTo, boolean last, boolean first, Trunk state,
                     List<Path> order, int workers, String loader, boolean runtime, String book, Path originalsDir) throws Exception {
        // What making the porters adds to the new release (the step's stand-ins, libraries carried from the old one) is
        // taken out again after the step, so the next step reads the release as it was read.
        Set<String> read = new HashSet<>(now.classes.keySet());
        // Made one after another: making a porter adds the step's stand-ins and carried library classes to the releases.
        List<JarPort> porters = new ArrayList<>();
        for (int w = 0; w < workers; w++) {
            JarPort port = new JarPort(was, now, Path.of(map));
            if (loader != null) {
                port.loader = loader;
            }
            port.runtimeNames = runtime && last;
            port.between = !last;
            port.originalsDir = originalsDir;
            port.origin = origin;
            port.portTo = portTo;
            port.decided = state.decided;
            if ("fabric".equals(loader)) {
                port.platform(Exposure.FabricApi.of(from), Exposure.FabricApi.of(to));
            }
            if (book != null) {
                port.fixes(Path.of(book));
            }
            porters.add(port);
        }
        java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        List<Thread> running = new ArrayList<>();
        for (JarPort port : porters) {
            running.add(Thread.ofPlatform().start(() -> {
                for (int j; (j = next.getAndIncrement()) < order.size(); ) {
                    Path jar = order.get(j);
                    if (state.errors.containsKey(jar)) {
                        continue;
                    }
                    String name = jar.getFileName().toString();
                    try {
                        byte[] in = first ? Files.readAllBytes(jar) : state.bytes.get(jar);
                        if (first && !state.originals.containsKey(jar)) {
                            state.originals.put(jar, port.originals(in, name));
                        }
                        List<Map<String, Object>> results = state.nested.computeIfAbsent(jar, k -> new ArrayList<>());
                        state.bytes.put(jar, port.port(in, name, results, state.originals.get(jar), last));
                    } catch (IOException | RuntimeException failed) {
                        state.errors.put(jar, String.valueOf(failed));
                        state.bytes.remove(jar);
                    }
                }
            }));
        }
        for (Thread t : running) {
            t.join();
        }
        now.classes.keySet().retainAll(read);
    }

    /** A target's ported jars and its report: each jar's references that did not link, at every step. */
    static void write(Target t, List<Path> jars, Trunk state, String loader) throws IOException {
        Path target = t.out();
        boolean folder = jars.size() > 1 || Files.isDirectory(target);
        if (folder) {
            Files.createDirectories(target);
        }
        List<Object> rows = new ArrayList<>();
        int linked = 0;
        Map<String, Set<String>> byMods = new HashMap<>();
        for (Path jar : jars) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mod", jar.getFileName().toString());
            if (state.errors.containsKey(jar)) {
                row.put("error", state.errors.get(jar));
            } else try {
                byte[] ported = state.bytes.get(jar);
                Path dest = folder ? target.resolve(jar.getFileName().toString()) : target;
                Files.write(dest, ported);
                // Every step's results: a reference one step could not link stays as it was, in that step's names, and breaks there.
                Set<String> unlinked = new TreeSet<>(), silent = new TreeSet<>(), skipped = new TreeSet<>(), refused = new TreeSet<>(), data = new TreeSet<>();
                boolean judged = false;
                Map<String, Integer> changed = new TreeMap<>();
                for (Map<String, Object> n : state.nested.getOrDefault(jar, List.of())) {
                    for (Object u : Json.array(n.get("unlinked"))) {
                        if (unlinked.add(n.get("jar") + ": " + u)) {
                            byMods.computeIfAbsent(u.toString().replaceAll(" \\(in [^)]*\\)$", ""), k -> new TreeSet<>()).add(jar.getFileName().toString());
                        }
                    }
                    for (Object u : Json.array(n.get("silent"))) {
                        silent.add(n.get("jar") + ": " + u);
                    }
                    for (Object u : n.get("skipped") == null ? List.of() : Json.array(n.get("skipped"))) {
                        skipped.add(n.get("jar") + ": " + u);
                    }
                    if (n.get("data") != null) {
                        judged = true;
                        for (Object u : Json.array(n.get("data"))) {
                            data.add(n.get("jar") + ": " + u);
                        }
                    }
                    for (Object u : n.get("refused") == null ? List.of() : Json.array(n.get("refused"))) {
                        refused.add(u.toString());
                    }
                    for (var e : Json.object(n.get("changed")).entrySet()) {
                        changed.merge(e.getKey(), ((Number) e.getValue()).intValue(), Integer::sum);
                    }
                }
                row.put("out", dest.toString());
                // What the port wrote, as a hash: a mod whose ported jar is the same as before needs no new proof in game.
                row.put("ported", HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(ported), 0, 8));
                row.put("changed", changed);
                row.put("unlinked", new ArrayList<>(unlinked));
                row.put("silent", new ArrayList<>(silent));
                row.put("skipped", new ArrayList<>(skipped));
                row.put("refused", new ArrayList<>(refused));
                if (judged) {
                    // What the mod's data names that the release lacks: the server stops on it, which linking cannot show.
                    row.put("data", new ArrayList<>(data));
                }
                linked += unlinked.isEmpty() ? 1 : 0;
            } catch (IOException | RuntimeException | java.security.NoSuchAlgorithmException failed) {
                row.put("error", String.valueOf(failed));
            }
            rows.add(row);
            System.out.println(row.get("mod") + ": " + (row.containsKey("error") ? row.get("error")
                : Json.array(row.get("unlinked")).size() + " unlinked, changed " + row.get("changed")));
        }
        System.out.printf("%d of %d jars link against %s%n", linked, jars.size(), t.to());
        if (t.report() != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("from", t.stops().get(0));
            r.put("to", t.to());
            r.put("loader", loader == null ? "" : loader);
            r.put("linked", linked);
            r.put("mods", rows);
            // What to teach the porter next: each reference that does not link, by how many mods it holds back.
            List<Object> ranked = new ArrayList<>();
            byMods.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, Set<String>> e) -> -e.getValue().size()).thenComparing(Map.Entry::getKey))
                .forEach(e -> ranked.add(Map.of("member", e.getKey(), "mods", e.getValue().size())));
            r.put("unlinkedByMods", ranked);
            Files.writeString(Path.of(t.report()), Json.write(r), StandardCharsets.UTF_8);
        }
    }

    /** A whole jar, nested mods included; each jar's result is added to results. */
    byte[] port(byte[] jar, String name, List<Map<String, Object>> results) throws IOException {
        return port(jar, name, results, originals(jar, name), true);
    }

    /**
     * The jar's own intermediary method names, which the hand-back gives back where it cannot follow a class to the game:
     * from the jar, or from the jar as it came where it was read into Mojang's names before (--originals). Read before
     * the first step of a port, whose later steps read the jar in Mojang's names.
     */
    Map<String, Set<String>> originals(byte[] jar, String name) throws IOException {
        if (!loader.equals("fabric")) {
            return Map.of();
        }
        Path came = originalsDir == null ? null : originalsDir.resolve(name);
        return intermediary(jar) ? ModNames.Back.methodNames(jar) : came != null && Files.isRegularFile(came) ? ModNames.Back.methodNames(Files.readAllBytes(came)) : Map.of();
    }

    /** One step of a port; last: the step into the release asked for, the only one a Fabric jar is handed back from. */
    byte[] port(byte[] jar, String name, List<Map<String, Object>> results, Map<String, Set<String>> originals, boolean last) throws IOException {
        boolean fabric = loader.equals("fabric");
        if (fabric && compareVersions(a.version, UNOBFUSCATED) < 0 && intermediary(jar)) {
            jar = fabricIn().jar(jar, new ModNames.Counts());
        }
        byte[] out = fitting(jar, name, results);
        if (last && fabric && compareVersions(b.version, UNOBFUSCATED) < 0 && !results.isEmpty()) {
            // Fabric runs the jar in intermediary: a method whose name the hand-back cannot give is never called.
            @SuppressWarnings("unchecked") List<Object> unlinked = (List<Object>) results.get(results.size() - 1).get("unlinked");
            ModNames.Back back = handBack();
            for (String gap : back != null ? back.gaps(out, originals) : List.<String>of()) {
                unlinked.add("method name lost in the Fabric hand-back: " + gap);
            }
            // Two of a class's methods the hand-back gives one intermediary name is a class the JVM refuses to load
            // (GeckoLib's GeoProjectilesRenderer on 1.19); only the names Fabric runs show it.
            byte[] handed = back == null ? null : back.jar(out, originals);
            for (byte[] c : handed == null ? List.<byte[]>of() : ModNames.Back.classesIn(handed, b.version)) {
                for (String twice : declaredTwice(c)) {
                    unlinked.add("does not verify in Fabric's names: method declared twice: " + twice);
                }
            }
            if (runtimeNames && handed != null) {
                return handed;
            }
        }
        if (last && fabric && runtimeNames && compareVersions(b.version, UNOBFUSCATED) < 0) {
            out = fabricOut().jar(out, originals);
        }
        return out;
    }

    /** Methods (class.name+desc) a class declares twice, which the JVM refuses to load it for (ClassFormatError). */
    static List<String> declaredTwice(byte[] bytes) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ClassReader r = new ClassReader(bytes);
        r.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (!seen.add(name + desc)) {
                    out.add(Exposure.dotted(r.getClassName()) + "." + name + desc);
                }
                return null;
            }
        }, ClassReader.SKIP_CODE);
        return out;
    }

    /** The first release Fabric runs in Mojang's names; before it, in that release's intermediary. */
    static final String UNOBFUSCATED = "26.1";

    boolean runtimeNames; // hand a Fabric jar back in the names Fabric runs, not Mojang's
    // A step before the last of a port: its jar is only read back by the next step, so it is written without compressing
    // (compressing every step's jars was a quarter of a long port), and only the last step's jar is the port. Nested jars
    // stay compressed: a later step may keep one as it is (a module the newer platform provides), bytes and all.
    boolean between;
    Path originalsDir; // where each jar is as it came, by its file name, when the jars given were read into Mojang's names first
    int[] packFormats; // the release ported to: its resource and data pack formats when its reader takes min_format/max_format, else {}

    /**
     * The pack formats of the release ported to, as its own version.json gives them ("pack_version"), when that release reads
     * pack.mcmeta's min_format and max_format (1.21.9 on, where the formats have a minor part); empty before.
     */
    int[] packFormats() {
        if (packFormats == null) {
            try {
                Path client = GameDiff.verified(GameDiff.download(GameDiff.versionJson(to), "client"), "client-" + to + ".jar");
                Map<String, Object> v = Json.object(Json.parse(VersionMap.zipEntry(client, "version.json")));
                Map<String, Object> pv = v.get("pack_version") instanceof Map<?, ?> m ? Json.object(m) : Map.of();
                packFormats = pv.containsKey("resource_minor") && pv.get("resource_major") instanceof Number r && pv.get("data_major") instanceof Number d
                    ? new int[] { r.intValue(), d.intValue() } : new int[0];
            } catch (Exception unreadable) {
                packFormats = new int[0];
            }
        }
        return packFormats;
    }

    /** The last pack formats without a minor part, resources then data (the game's PackFormat.lastPreMinorVersion). */
    static final int[] LAST_FORMATS_WITHOUT_MINOR = { 64, 81 };

    /**
     * A mod's pack.mcmeta declares the formats of the release it was built for, and the reader from 1.21.9 on
     * (PackFormat.IntermediaryFormat.validate) refuses some shapes the releases before took. Against each kind's last
     * format without a minor part, a min_format above it must have no supported_formats, and one at or below it must
     * have supported_formats from min_format to max_format and a pack_format inside the range. A mod's jar is one pack
     * read as both kinds, so a range starting between the two (1.21.9's 69 to 88) is refused by one kind whichever way
     * it is written. Ported, the range is the release's own two formats when it starts above both; otherwise it starts
     * at or below both, with the fields both kinds then ask for. Anything else in the section is kept, and an unreadable
     * file is left as it is.
     */
    byte[] packMetadata(Mod mod, byte[] data) {
        int[] formats = packFormats();
        if (formats.length < 2) {
            return data;
        }
        int low = Math.min(formats[0], formats[1]);
        int high = Math.max(formats[0], formats[1]);
        boolean legacy = low <= Math.max(LAST_FORMATS_WITHOUT_MINOR[0], LAST_FORMATS_WITHOUT_MINOR[1]);
        int min = legacy ? Math.min(low, Math.min(LAST_FORMATS_WITHOUT_MINOR[0], LAST_FORMATS_WITHOUT_MINOR[1])) : low;
        Map<String, Object> meta;
        try {
            meta = Json.object(Json.parse(new String(data, StandardCharsets.UTF_8).replaceFirst("^\uFEFF", "")));
        } catch (RuntimeException unreadable) {
            return data;
        }
        if (!(meta.get("pack") instanceof Map<?, ?> section)) {
            return data;
        }
        Map<String, Object> pack = Json.object(section);
        List<Object> was = new ArrayList<>(List.of(String.valueOf(pack.get("pack_format")), String.valueOf(pack.get("min_format")), String.valueOf(pack.get("max_format")),
            String.valueOf(pack.get("supported_formats"))));
        pack.remove("pack_format");
        pack.remove("supported_formats");
        if (legacy) {
            pack.put("pack_format", low);
            pack.put("supported_formats", List.of(min, high));
        }
        pack.put("min_format", min);
        pack.put("max_format", high);
        List<Object> now = List.of(String.valueOf(pack.get("pack_format")), String.valueOf(min), String.valueOf(high), String.valueOf(pack.get("supported_formats")));
        if (was.equals(now)) {
            return data;
        }
        mod.change("pack formats declared for " + to);
        return Json.write(meta).getBytes(StandardCharsets.UTF_8);
    }
    ModNames in;
    ModNames.Back out;

    ModNames fabricIn() throws IOException {
        try {
            return in != null ? in : (in = new ModNames(Exposure.Intermediary.of(a.version)));
        } catch (Exception unreadable) {
            throw new IOException("Fabric's intermediary names for " + a.version + " could not be read: " + unreadable.getMessage(), unreadable);
        }
    }

    /** The hand-back to the target's intermediary, or null where a release has none to read (a test's made-up one). */
    ModNames.Back handBack() {
        if (handBackMissing) {
            return null;
        }
        try {
            return fabricOut();
        } catch (IOException unreadable) {
            handBackMissing = true;
            return null;
        }
    }

    boolean handBackMissing;

    ModNames.Back fabricOut() throws IOException {
        try {
            return out != null ? out : (out = new ModNames.Back(b.version, b));
        } catch (Exception unreadable) {
            throw new IOException("Fabric's intermediary names for " + b.version + " could not be read: " + unreadable.getMessage(), unreadable);
        }
    }

    /** Whether a jar's classes, or its nested jars', name the game in Fabric's intermediary (net/minecraft/class_N). */
    static boolean intermediary(byte[] jar) throws IOException {
        byte[] mark = "net/minecraft/class_".getBytes(StandardCharsets.ISO_8859_1);
        Map<String, byte[]> entries = entries(jar, "", new HashMap<>());
        for (var e : entries.entrySet()) {
            if (e.getKey().endsWith(".class") && indexOf(e.getValue(), mark) >= 0) {
                return true;
            }
        }
        for (String n : nested(entries)) {
            if (intermediary(entries.get(n))) {
                return true;
            }
        }
        return false;
    }

    /** Where mark first is in data, or -1 (Boyer-Moore-Horspool: every class of a jar is searched at every step of a long port). */
    static int indexOf(byte[] data, byte[] mark) {
        return indexOf(data, mark, 0);
    }

    static int indexOf(byte[] data, byte[] mark, int from) {
        int m = mark.length;
        if (m == 0) {
            return from;
        }
        int[] skip = new int[256];
        Arrays.fill(skip, m);
        for (int j = 0; j < m - 1; j++) {
            skip[mark[j] & 0xFF] = m - 1 - j;
        }
        for (int i = from; i + m <= data.length; i += skip[data[i + m - 1] & 0xFF]) {
            int j = m - 1;
            while (j >= 0 && data[i + j] == mark[j]) {
                j--;
            }
            if (j < 0) {
                return i;
            }
        }
        return -1;
    }

    Set<String> platformIds = Set.of(); // mod ids the platform the port is for provides (Fabric API's modules), --platform
    Map<String, String> platformAliases = Map.of(); // an old platform's other name for a module -> the module's id, --platform-aliases

    /**
     * What the old and new releases' platforms (Fabric API) answer to: every id of the new one's modules, and the names the
     * old one answered to only as another ("provides") that the new one dropped, each to the module that gave it.
     */
    void platform(Path was, Path now) throws IOException {
        if (now == null) {
            return;
        }
        Map<String, String> has = provided(Files.readAllBytes(now)), had = was == null ? Map.of() : provided(Files.readAllBytes(was));
        platformIds = Set.copyOf(has.keySet());
        Map<String, String> aliases = new HashMap<>();
        had.forEach((alias, owner) -> {
            if (!alias.equals(owner) && !has.containsKey(alias) && has.containsKey(owner)) {
                aliases.put(alias, owner);
            }
        });
        // A module the new platform no longer has by its id was renamed or merged (26.1's fabric-key-binding-api-v1 became
        // fabric-key-mapping-api-v1): it is the module that now holds most of its classes, in the new release's names. One
        // whose classes no module holds was removed, and is asked of no one: what the mod used of it is reported where used.
        if (was != null) {
            Map<String, Set<String>> before = modules(Files.readAllBytes(was)), after = modules(Files.readAllBytes(now));
            Map<String, String> holder = new HashMap<>();
            after.forEach((id, classes) -> classes.forEach(c -> holder.putIfAbsent(c, id)));
            before.forEach((id, classes) -> {
                if (has.containsKey(id) || aliases.containsKey(id)) {
                    return;
                }
                Map<String, Integer> votes = new TreeMap<>();
                classes.forEach(c -> {
                    String to = holder.get(cls(c));
                    if (to != null) {
                        votes.merge(to, 1, Integer::sum);
                    }
                });
                aliases.put(id, votes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(""));
            });
        }
        platformAliases = aliases;
    }

    /** The classes each module of a platform jar declares (its own and its nested jars'), by mod id; mixins left out. */
    static Map<String, Set<String>> modules(byte[] jar) throws IOException {
        Map<String, Set<String>> out = new TreeMap<>();
        String id = null;
        Set<String> classes = new TreeSet<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName();
                if (n.equals("fabric.mod.json")) {
                    try {
                        id = Json.object(Json.parse(new String(z.readAllBytes(), StandardCharsets.UTF_8))).get("id") instanceof String s ? s : null;
                    } catch (RuntimeException unreadable) {
                        id = null;
                    }
                } else if (n.endsWith(".class") && !n.contains("/mixin/") && !n.startsWith("META-INF/")) {
                    classes.add(n.substring(0, n.length() - 6));
                } else if (n.startsWith("META-INF/jars/") && n.endsWith(".jar")) {
                    modules(z.readAllBytes()).forEach((k, v) -> out.computeIfAbsent(k, x -> new TreeSet<>()).addAll(v));
                }
            }
        }
        if (id != null) {
            out.computeIfAbsent(id, x -> new TreeSet<>()).addAll(classes);
        }
        return out;
    }

    /** Every id a jar and the jars nested in it answer to (its own and what it provides), each with the module that answers. */
    static Map<String, String> provided(byte[] jar) throws IOException {
        Map<String, String> out = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName();
                if (n.equals("fabric.mod.json")) {
                    Map<String, Object> meta;
                    try {
                        meta = Json.object(Json.parse(new String(z.readAllBytes(), StandardCharsets.UTF_8)));
                    } catch (RuntimeException unreadable) {
                        continue;
                    }
                    if (meta.get("id") instanceof String id) {
                        out.putIfAbsent(id, id);
                        for (Object alias : meta.get("provides") instanceof List<?> l ? l : List.of()) {
                            out.putIfAbsent(String.valueOf(alias), id);
                        }
                    }
                } else if (n.startsWith("META-INF/jars/") && n.endsWith(".jar")) {
                    provided(z.readAllBytes()).forEach(out::putIfAbsent);
                }
            }
        }
        return out;
    }

    /** The mod id of a nested jar when the platform provides it, else null. */
    String platformModule(byte[] jar) {
        if (platformIds.isEmpty()) {
            return null;
        }
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (e.getName().equals("fabric.mod.json")) {
                    Object id = Json.object(Json.parse(new String(z.readAllBytes(), StandardCharsets.UTF_8))).get("id");
                    return id instanceof String s && platformIds.contains(s) ? s : null;
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        return null;
    }

    static final Pattern SRG = Pattern.compile("\\b[mf]_\\d+_\\b");
    static final Pattern INTERMEDIARY = Pattern.compile("net/minecraft/class_\\d+");

    /** Whether the bytes hold m_N_ or f_N_ anywhere: SRG without its word bounds, which raw bytes beside a name can break. */
    static boolean srgAnywhere(byte[] b) {
        for (int i = 0; i + 3 < b.length; i++) {
            if ((b[i] == 'm' || b[i] == 'f') && b[i + 1] == '_' && b[i + 2] >= '0' && b[i + 2] <= '9') {
                int j = i + 3;
                while (j < b.length && b[j] >= '0' && b[j] <= '9') {
                    j++;
                }
                if (j < b.length && b[j] == '_') {
                    return true;
                }
            }
        }
        return false;
    }

    static final byte[] INTERMEDIARY_CLASS = "net/minecraft/class_".getBytes(StandardCharsets.ISO_8859_1);

    /** Whether the bytes hold net/minecraft/class_N anywhere. */
    static boolean intermediaryAnywhere(byte[] b) {
        for (int i = indexOf(b, INTERMEDIARY_CLASS, 0); i >= 0; i = indexOf(b, INTERMEDIARY_CLASS, i + 1)) {
            int next = i + INTERMEDIARY_CLASS.length;
            if (next < b.length && b[next] >= '0' && b[next] <= '9') {
                return true;
            }
        }
        return false;
    }

    /**
     * A class a jar for several loaders carries for another one (a Forge class beside the Fabric ones), which the loader
     * ported for never loads, so it is no part of the mod there, before the port or after. It names the game as only
     * that loader's build does. A Fabric jar is read in Mojang's names through intermediary, so a class of it still
     * naming members by SRG id (m_1234_) was built for Forge; any other jar is read in Mojang's names too, so a class
     * still naming a game class by intermediary (class_1234) was built for Fabric. (An SRG id left in a NeoForge jar of
     * the SRG releases is a gap in NeoForm's names, not another loader.)
     */
    boolean otherLoader(byte[] bytes) {
        // Read from what the JVM links (types, calls, fields, declared names), not from strings: a mixin for every
        // loader lists each loader's names in its annotations and runs on all of them.
        Pattern other = loader.equals("fabric") ? SRG : INTERMEDIARY;
        // Every name the class links is text in its constant pool: a class whose bytes hold no such name anywhere has none,
        // and reading every class of every jar at every step of a long port was a tenth of the port.
        if (!(loader.equals("fabric") ? srgAnywhere(bytes) : intermediaryAnywhere(bytes))) {
            return false;
        }
        boolean[] found = new boolean[1];
        java.util.function.Consumer<String> seen = text -> found[0] |= text != null && other.matcher(text).find();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                seen.accept(superName);
                for (String i : interfaces == null ? new String[0] : interfaces) {
                    seen.accept(i);
                }
            }

            @Override
            public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                seen.accept(name);
                seen.accept(desc);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                seen.accept(name);
                seen.accept(desc);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String n, String d, boolean itf) {
                        seen.accept(owner);
                        seen.accept(n);
                        seen.accept(d);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String n, String d) {
                        seen.accept(owner);
                        seen.accept(n);
                        seen.accept(d);
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        seen.accept(type);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    static final Pattern MOJANG_NAMED = Pattern.compile("net/minecraft/(?!class_\\d)[a-z]");

    /**
     * grants: what every access transformer in the outermost jar and its nested jars opens (null: read it here). The loader
     * applies an access transformer to the game for every mod, so a nested helper may rely on its parent's.
     */
    byte[] port(byte[] jar, String name, List<Map<String, Object>> results, Set<String> grants) throws IOException {
        Map<String, Long> times = new HashMap<>();
        Map<String, byte[]> entries = entries(jar, name, times);
        String renamed = null;
        if (loader.equals("neoforge")) {
            String wanted = metadataFile(), other = wanted.equals(NEW_TOML) ? OLD_TOML : NEW_TOML;
            if (entries.containsKey(other) && !entries.containsKey(wanted)) {
                renamed = wanted.substring(wanted.lastIndexOf('/') + 1);
                entries = moved(entries, times, other, wanted);
            }
        }
        Map<String, String> notLoaded = notLoaded(entries, name);
        Set<String> nested = nested(entries);
        nested.removeAll(notLoaded.keySet());
        List<String> copied = copyNewer(entries, name);
        boolean outermost = grants == null;
        if (outermost) {
            typesUsed = false;
            Map<String, List<byte[]>> all = new HashMap<>();
            carried(entries, name, all);
            tree = new Carried(all);
            grants = new HashSet<>();
            for (String text : accessTransformers(entries, name)) {
                widen(grants, text);
            }
        }
        Mod mod = new Mod();
        if (renamed != null) {
            mod.change("metadata renamed to " + renamed);
        }
        notLoaded.forEach((n, why) -> mod.skipped.add(n + ": " + why));
        if (!copied.isEmpty()) {
            mod.change("newer game code copied for: " + copied);
        }
        mod.widened.addAll(grants);
        boolean judged = data(mod, entries);
        // NeoForge's loader (FML 1 to 11) reads the access transformers its metadata lists, or else
        // META-INF/accesstransformer.cfg, for every mod file, nested ones included.
        // Fabric's reads the access widener fabric.mod.json names, for every mod file too.
        byte[] fmj = entries.get("fabric.mod.json");
        mod.canWiden = loader.equals("neoforge") && entries.containsKey(metadataFile()) || fmj != null;
        boolean nativeGame = false; // a class naming the game as this loader's build does
        Map<String, byte[]> jarEntries = entries;
        for (var e : entries.entrySet()) {
            if (e.getKey().endsWith(".class") && !e.getKey().startsWith("META-INF/versions/") && otherLoader(e.getValue())) {
                mod.foreign.add(e.getKey());
                mod.change("classes built for another loader left as they are");
                continue;
            }
            if (e.getKey().endsWith(".class") && !e.getKey().startsWith("META-INF/versions/") && !nativeGame) {
                nativeGame = MOJANG_NAMED.matcher(new String(e.getValue(), StandardCharsets.ISO_8859_1)).find();
            }
            if (e.getKey().endsWith(".class") && !e.getKey().startsWith("META-INF/versions/")) {
                e.setValue(PortChains.apply(e.getValue(), pairs, (o, k, n, d) -> {
                    GameDiff.Cls c = Exposure.declaring(a, o, k, n, d);
                    return c == null ? null : c.name;
                }, owner -> interfaceIn(jarEntries, owner)));
                read(mod, e.getValue());
            }
        }
        if (!mod.foreign.isEmpty() && !nativeGame) {
            // Not a jar for this loader at all (a Forge build listed for NeoForge): no class of it names the game as this
            // loader's build does, so the ones naming nothing of the game are that build's too. It never loaded here.
            if (name.contains("!")) {
                // A jar nested for the other loader of a jar for several (Fabric API's modules beside the NeoForge classes)
                // is never loaded by this one.
                mod.skipped.add("nested jar for another loader, not loaded here: " + name);
            } else {
                mod.unlinked.add("built for another loader: every class names the game as another loader's build does");
            }
        }
        requires(mod, entries);
        PLANNING.set(mod);
        try {
            plan(mod);
        } finally {
            PLANNING.remove();
        }
        buses(mod);
        for (var e : mod.split.entrySet()) {
            String file = e.getKey() + "$ModBusListeners.class";
            entries.put(file, companion(mod, e.getKey(), e.getValue()));
            mod.change("mod-bus listeners moved to a companion class");
        }
        // Classes first: what they need opened goes into the access transformer written after them.
        Map<String, byte[]> rewritten = new HashMap<>();
        // A mod method moved to a game method's new parameter types that some call cannot follow (no new arguments at
        // hand there) is kept as well, as it was: that call still links, as it did before the port.
        Set<String> wasUnlinked = new TreeSet<>(mod.unlinked), wasSilent = new TreeSet<>(mod.silent), wasGranted = new TreeSet<>(mod.grants);
        Map<String, Integer> wasChanged = new TreeMap<>(mod.changed);
        Set<String> twinned = new HashSet<>();
        for (int pass = 0; pass < 8; pass++) {
            int kept = mod.kept.size(), unmoved = mod.unmoved.size();
            rewritten.clear();
            for (var e : entries.entrySet()) {
                if (e.getKey().endsWith(".class") && !e.getKey().startsWith("META-INF/versions/") && !mod.foreign.contains(e.getKey())) {
                    rewritten.put(e.getKey(), rewriteOrReport(mod, e.getValue()));
                }
            }
            // Helpers a moved body passes a worked-out parameter to move too, one level more each pass.
            int added = 0;
            for (var e : mod.twins.entrySet()) {
                if (twinned.add(e.getKey())) {
                    added += twin(mod, e.getKey(), e.getValue()) ? 1 : 0;
                }
            }
            if (mod.kept.size() == kept && mod.unmoved.size() == unmoved && added == 0) {
                break;
            }
            // A body that cannot be moved whole stays as it was, an override the game no longer calls, as before the port.
            for (String key : mod.unmoved) {
                String owner = key.substring(0, key.indexOf('.')), member = key.substring(key.indexOf('.') + 1);
                if (mod.bridges.getOrDefault(owner, new ArrayList<>()).removeIf(br -> br.via() != null && (br.target() + br.targetDesc()).equals(member))) {
                    wasSilent.add("override no longer called: " + Exposure.dotted(owner) + "." + member + " (a call in it needs what the game passes now)");
                }
            }
            mod.unlinked.clear();
            mod.unlinked.addAll(wasUnlinked);
            mod.silent.clear();
            mod.silent.addAll(wasSilent);
            mod.grants.clear();
            mod.grants.addAll(wasGranted);
            mod.changed.clear();
            mod.changed.putAll(wasChanged);
        }
        if (!between) {
            lowerJava(mod, rewritten, entries, name);
        }
        verify(mod, rewritten, entries);
        bundle(mod, rewritten, entries, name);
        List<String> atFiles = atFiles(entries);
        String widener = fmj == null ? null : widenerFile(fmj);
        if (fmj != null && widener == null && !mod.grants.isEmpty()) {
            widener = "mmt-port.accesswidener";
            entries.put(widener, "accessWidener v2 named\n".getBytes(StandardCharsets.UTF_8));
        }
        if (fmj == null && !mod.grants.isEmpty()) {
            entries.putIfAbsent(atFiles.get(0), new byte[0]);
        }
        // The platform's own modules the jar nests: the platform's copy runs instead, so what the mod asks of them is asked of it.
        Set<String> platformNested = new HashSet<>();
        for (var e : entries.entrySet()) {
            if (nested.contains(e.getKey()) && platformModule(e.getValue()) instanceof String id) {
                platformNested.add(id);
            }
        }
        // Nested jars first: whether any jar of the tree uses a stand-in type decides what the outermost one carries.
        Map<String, byte[]> portedNested = new HashMap<>();
        Set<String> loadedByItself = selfLoaded(entries, listed(entries));
        for (var e : entries.entrySet()) {
            String n = e.getKey();
            boolean own = loadedByItself.contains(n);
            if (own && !loader.equals("fabric") && compareVersions(a.version, UNOBFUSCATED) < 0 && intermediary(e.getValue())) {
                // Written in Fabric's intermediary names in a mod for a loader that runs Mojang's: its own code renames it at
                // start. Read in Mojang's names, ported, and handed back in the new release's intermediary.
                byte[] read = fabricIn().jar(e.getValue(), new ModNames.Counts());
                byte[] ported = port(read, name + "!" + n.substring(n.lastIndexOf('/') + 1), results, grants);
                ModNames.Back back = compareVersions(b.version, UNOBFUSCATED) < 0 ? handBack() : null;
                portedNested.put(n, back == null ? ported : back.jar(ported, ModNames.Back.methodNames(e.getValue())));
                mod.change("jar in Fabric's intermediary names its own code renames at start, ported through Mojang's");
                continue;
            }
            String foreignName = own ? notIn(e.getValue(), a.classes::containsKey) : null;
            if (foreignName != null) {
                portedNested.put(n, e.getValue());
                mod.change("jar its own code renames at start left as it came: " + n + " names " + Exposure.dotted(foreignName) + ", which " + a.version + " lacks");
            } else if ((nested.contains(n) || n.startsWith("META-INF/jarjar/") && n.endsWith(".jar")) && platformModule(e.getValue()) == null) {
                portedNested.put(n, port(e.getValue(), name + "!" + n.substring(n.lastIndexOf('/') + 1), results, grants));
            }
        }
        redigest(entries, entries, portedNested);
        String typesPath = null;
        if (outermost && typesUsed) {
            try {
                typesPath = carryTypes(entries, fmj != null);
                mod.change("stand-in types carried in " + typesPath);
            } catch (RuntimeException unreadable) {
                // Metadata Json cannot read (a key given twice) cannot list the jar, and the loader loads no jar it does not list.
                mod.unlinked.add("stand-in types not carried: the jar's metadata cannot be read (" + unreadable.getMessage() + ")");
            }
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(buffer)) {
            if (between && outermost) {
                out.setLevel(Deflater.NO_COMPRESSION);
            }
            for (var e : entries.entrySet()) {
                String n = e.getKey();
                byte[] data = e.getValue();
                if (n.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)")) {
                    continue;
                }
                if (n.equals("META-INF/MANIFEST.MF")) {
                    data = unsigned(data);
                } else if (n.equals(NEW_TOML) || n.equals(OLD_TOML)) {
                    data = metadata(mod, new String(data, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                } else if (n.equals("fabric.mod.json")) {
                    data = runsOn(fabricMetadata(data, widener, platformNested), mod.lowered);
                } else if (n.equals("pack.mcmeta")) {
                    data = packMetadata(mod, data);
                } else if (n.equals(widener)) {
                    String text = accessWidener(mod, new String(data, StandardCharsets.UTF_8));
                    if (!mod.grants.isEmpty()) {
                        text += (text.endsWith("\n") ? "" : "\n") + "# Opened by mmt port: closed in " + to + ", used by this mod\n" + widenerLines(mod.grants);
                        mod.change("members opened through the mod's access widener");
                    }
                    data = text.getBytes(StandardCharsets.UTF_8);
                } else if (nested.contains(n) || n.startsWith("META-INF/jarjar/") && n.endsWith(".jar")) {
                    String nestedId = platformModule(data);
                    if (nestedId != null) {
                        // The platform's own module (a Fabric API module a mod nests): the loader runs the platform's newer
                        // copy of that mod id instead, so this one is no part of the mod there.
                        mod.skipped.add(n + ": the platform's own module, its copy of " + nestedId + " runs instead");
                    } else {
                        data = portedNested.get(n);
                    }
                } else if (rewritten.containsKey(n)) {
                    data = rewritten.get(n);
                } else if (atFiles.contains(n)) {
                    String text = accessTransformer(mod, new String(data, StandardCharsets.UTF_8));
                    if (fmj == null && n.equals(atFiles.get(0)) && !mod.grants.isEmpty()) {
                        text += (text.isEmpty() || text.endsWith("\n") ? "" : "\n") + "# Opened by mmt port: closed in " + to + ", used by this mod\n" + String.join("\n", mod.grants) + "\n";
                        mod.change("members opened through the mod's access transformer");
                    }
                    data = text.getBytes(StandardCharsets.UTF_8);
                }
                if (mod.lowered > 0 && n.endsWith(".json") && !n.equals("fabric.mod.json")) {
                    data = mixinLevel(data, mod.lowered);
                }
                ZipEntry entry = new ZipEntry(n);
                entry.setTime(times.getOrDefault(n, 0L));
                out.putNextEntry(entry);
                out.write(data);
                out.closeEntry();
            }
            if (typesPath != null) {
                // Written after the loop: the types are on the new release already, so this step does not port them again.
                ZipEntry entry = new ZipEntry(typesPath);
                entry.setTime(0L);
                out.putNextEntry(entry);
                out.write(typesJar());
                out.closeEntry();
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jar", name);
        result.put("changed", mod.changed);
        result.put("unlinked", new ArrayList<>(mod.unlinked));
        result.put("silent", new ArrayList<>(mod.silent));
        result.put("skipped", new ArrayList<>(mod.skipped));
        result.put("refused", new ArrayList<>(mod.refused));
        if (judged) {
            result.put("data", new ArrayList<>(mod.data));
        }
        results.add(result);
        return buffer.toByteArray();
    }

    /** The data pass (DataIds) on a jar's own entries; whether it judged the ids against the release ported to. */
    boolean data(Mod mod, Map<String, byte[]> entries) {
        if (dataStep == null && origin == null) {
            return false; // a porter made in code (tests) has no releases to read reports of unless it is given a step
        }
        if (dataStep == null) {
            dataStep = DataIds.Step.of(origin == null ? a.version : origin, a.version, b.version, !between);
        }
        if (dataStep.idle()) {
            return false;
        }
        DataIds pass = new DataIds(dataStep);
        pass.apply(entries);
        pass.changed.forEach((kind, n) -> mod.changed.merge(kind, n, Integer::sum));
        mod.silent.addAll(pass.silent);
        mod.data.addAll(pass.left);
        return dataStep.target() != null;
    }

    /** A jar's files by name (directories left out); times gets each file's time. An entry pointing outside the jar refuses it. */
    static Map<String, byte[]> entries(byte[] jar, String name, Map<String, Long> times) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (unsafe(e.getName())) {
                    throw new IOException(name + " has an entry that points outside the jar: " + e.getName());
                }
                if (!e.isDirectory()) {
                    entries.put(e.getName(), zip.readAllBytes());
                    times.put(e.getName(), e.getTime());
                }
            }
        }
        return entries;
    }

    /**
     * Jar-in-jar: nested jars are where META-INF/jarjar/metadata.json says ("path", often META-INF/jars/), in
     * META-INF/jarjar/, where fabric.mod.json lists them ("jars": [{"file": ...}]), and every other jar inside whose classes
     * use the game (selfLoaded). Every reader of a mod's code (ModNames, the hand-back, the port) reads this one set.
     */
    static Set<String> nested(Map<String, byte[]> entries) {
        Set<String> nested = listed(entries);
        nested.addAll(selfLoaded(entries, nested));
        return nested;
    }

    /**
     * The jars inside a jar that no loader metadata lists and whose classes use the game: code the mod's own classes put on
     * the class path at start (a mod locator, a language adapter, a loader of its own that unpacks it), which runs in the
     * game as surely as a listed jar. A jar whose classes only name the game in text (a crash-log reader's patterns) is no
     * game code.
     */
    static Set<String> selfLoaded(Map<String, byte[]> entries, Set<String> listed) {
        Set<String> out = new TreeSet<>();
        for (var e : entries.entrySet()) {
            if (e.getKey().endsWith(".jar") && !listed.contains(e.getKey()) && usesGame(e.getValue())) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    static final byte[] GAME_PACKAGE = "net/minecraft/".getBytes(StandardCharsets.ISO_8859_1), BLAZE3D_PACKAGE = "com/mojang/blaze3d/".getBytes(StandardCharsets.ISO_8859_1);
    static final Map<byte[], Boolean> USES_GAME = Collections.synchronizedMap(new WeakHashMap<>());

    /** Whether a jar's classes, or those of the jars inside it, refer to a class of the game (its class constants). */
    static boolean usesGame(byte[] jar) {
        Boolean known = USES_GAME.get(jar);
        if (known != null) {
            return known;
        }
        boolean uses = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; !uses && (e = zip.getNextEntry()) != null; ) {
                if (e.getName().endsWith(".class")) {
                    uses = !gameClasses(zip.readAllBytes()).isEmpty();
                } else if (e.getName().endsWith(".jar")) {
                    uses = usesGame(zip.readAllBytes());
                }
            }
        } catch (IOException notAJar) {
            uses = false;
        }
        USES_GAME.put(jar, uses);
        return uses;
    }

    /**
     * The game classes (net/minecraft/, com/mojang/blaze3d/) a class refers to as types: in its references, descriptors,
     * signatures and annotations, never in its text.
     */
    static Set<String> gameClasses(byte[] bytes) {
        Set<String> out = new TreeSet<>();
        if (indexOf(bytes, GAME_PACKAGE) < 0 && indexOf(bytes, BLAZE3D_PACKAGE) < 0) {
            return out; // most library classes: no parse
        }
        try {
            new ClassReader(bytes).accept(new ClassRemapper(new ClassVisitor(Opcodes.ASM9) { }, new Remapper(Opcodes.ASM9) {
                @Override
                public String map(String internalName) {
                    if (internalName.startsWith("net/minecraft/") || internalName.startsWith("com/mojang/blaze3d/")) {
                        out.add(internalName);
                    }
                    return internalName;
                }
            }), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException unparsed) {
            // a class the JVM refuses too
        }
        return out;
    }

    /**
     * The first game class a jar's classes name that a release lacks, or null. A jar the mod's own code loads that names
     * game classes its release lacks is not written in that release's names: its own code renames it at start, through a
     * table of its own (one file for every release), so the port leaves it, and the hand-back too, as it came.
     */
    static String notIn(byte[] jar, java.util.function.Predicate<String> release) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.getName().endsWith(".class") && !e.getName().startsWith("META-INF/versions/")) {
                    for (String c : gameClasses(zip.readAllBytes())) {
                        if (!release.test(c)) {
                            return c;
                        }
                    }
                }
            }
        } catch (IOException notAJar) {
            return null;
        }
        return null;
    }

    static final Pattern DIGEST = Pattern.compile("(?i)\\b[0-9a-f]{32}(?:[0-9a-f]{8})?(?:[0-9a-f]{24})?\\b");

    /**
     * A jar that records the digest (MD5, SHA-1 or SHA-256, as hex) of a jar it carries in a text file beside it (a loader
     * of its own checking the jar it unpacks) records the new jar's: the digest of what was ported is no longer that of the
     * jar carried. files: the jar's files, rewritten in place; was and after: its nested jars by name, as they came and as
     * they are written now.
     */
    static void redigest(Map<String, byte[]> files, Map<String, byte[]> was, Map<String, byte[]> after) {
        Map<String, String> moved = new HashMap<>();
        for (var e : after.entrySet()) {
            byte[] before = was.get(e.getKey());
            if (before == null || Arrays.equals(before, e.getValue())) {
                continue;
            }
            for (String algorithm : List.of("MD5", "SHA-1", "SHA-256")) {
                try {
                    var digest = java.security.MessageDigest.getInstance(algorithm);
                    moved.put(HexFormat.of().formatHex(digest.digest(before)), HexFormat.of().formatHex(digest.digest(e.getValue())));
                } catch (java.security.NoSuchAlgorithmException missing) {
                    throw new IllegalStateException(missing);
                }
            }
        }
        if (moved.isEmpty()) {
            return;
        }
        for (var e : files.entrySet()) {
            String n = e.getKey();
            if (n.endsWith(".class") || n.endsWith(".jar") || e.getValue().length > 1 << 20) {
                continue;
            }
            String text = new String(e.getValue(), StandardCharsets.ISO_8859_1);
            Matcher m = DIGEST.matcher(text);
            StringBuilder out = new StringBuilder();
            boolean changed = false;
            while (m.find()) {
                String now = moved.get(m.group().toLowerCase(Locale.ROOT));
                changed |= now != null;
                String written = now == null ? m.group() : m.group().equals(m.group().toUpperCase(Locale.ROOT)) ? now.toUpperCase(Locale.ROOT) : now;
                m.appendReplacement(out, written);
            }
            if (changed) {
                m.appendTail(out);
                e.setValue(out.toString().getBytes(StandardCharsets.ISO_8859_1));
            }
        }
    }

    /** The nested jars a loader reads from its metadata, and META-INF/jarjar/, where NeoForge's loader looks. */
    static Set<String> listed(Map<String, byte[]> entries) {
        Set<String> nested = new HashSet<>();
        byte[] list = entries.get("META-INF/jarjar/metadata.json");
        if (list != null) {
            for (Object j : Json.array(Json.object(Json.parse(new String(list, StandardCharsets.UTF_8))).getOrDefault("jars", List.of()))) {
                nested.add(Json.text(Json.object(j), "path", ""));
            }
        }
        byte[] fmj = entries.get("fabric.mod.json");
        if (fmj != null) {
            try {
                for (Object j : Json.object(Json.parse(new String(fmj, StandardCharsets.UTF_8))).get("jars") instanceof List<?> l ? l : List.of()) {
                    if (j instanceof Map<?, ?> m && m.get("file") instanceof String file) {
                        nested.add(file);
                    }
                }
            } catch (RuntimeException unreadable) {
                // the loader rejects it too
            }
        }
        for (String n : entries.keySet()) {
            if (n.startsWith("META-INF/jarjar/") && n.endsWith(".jar")) {
                nested.add(n);
            }
        }
        nested.retainAll(entries.keySet());
        return nested;
    }

    /** The jars nested in a jar (named as port names it) that the loader loads on the release ported to. */
    Set<String> loadedNested(Map<String, byte[]> entries, String name) {
        Set<String> out = nested(entries);
        out.removeAll(notLoaded(entries, name).keySet());
        return out;
    }

    // The last release of the whole port, which with origin decides the nested jars it carries (notLoaded): a step between
    // them would retarget a module it carries to a release its bundle has no module for. Null: this step's own releases.
    String portTo;
    // A port of several steps: each jar's choice of nested jars by its name as port names it (outer.jar!inner.jar), made
    // at the first step and kept for every step after, where a module a step ported asks for that step's release. Null: chosen each time.
    Map<String, Map<String, String>> decided;

    /**
     * The nested jars the loader passes over on the release ported to, each with why. They are left as they are, neither
     * ported nor linked, and keep the releases their metadata names, so the loader goes on passing them over.
     *
     * Fabric Loader loads a nested mod where its own "minecraft" holds (fabricLoads), which is how a bundle of one module
     * per release (ViaFabric's viafabric-mc1144 to -mc12111) runs only the module for the release it starts on. A module
     * that accepts neither release is loaded on neither. One that accepts the old release only is passed over when the
     * bundle carries a module that accepts the new release and not the old: the loader runs that one instead. With no
     * such module, the old release's module is ported and asks for the new release, as every nested mod is.
     *
     * NeoForge's loader picks among nested jars by their artifacts' version ranges, and refuses a mod whose "minecraft"
     * range fails instead of passing it over, so no jar's metadata picks a module per release there: every nested jar is
     * ported, as before. A bundle that picks its module in its own code reads to the porter as modules loaded together.
     */
    Map<String, String> notLoaded(Map<String, byte[]> entries, String name) {
        Map<String, String> kept = decided == null ? null : decided.get(name);
        if (kept != null) {
            return kept;
        }
        Map<String, String> out = loader.equals("fabric") ? fabricNotLoaded(entries, origin != null ? origin : a.version, portTo != null ? portTo : to) : new TreeMap<>();
        if (decided != null) {
            decided.put(name, out);
        }
        return out;
    }

    /** notLoaded for Fabric Loader on a port from from to until. */
    static Map<String, String> fabricNotLoaded(Map<String, byte[]> entries, String from, String until) {
        Map<String, String> out = new TreeMap<>();
        Map<String, Object> ranges = new TreeMap<>();
        for (String n : nested(entries)) {
            Object range = fabricMinecraft(entries.get(n));
            if (range != null) {
                ranges.put(n, range);
            }
        }
        String forNew = null;
        for (var e : ranges.entrySet()) {
            if (forNew == null && fabricAccepts(e.getValue(), until) && !fabricAccepts(e.getValue(), from)) {
                forNew = e.getKey();
            }
        }
        for (var e : ranges.entrySet()) {
            boolean old = fabricAccepts(e.getValue(), from);
            boolean now = fabricAccepts(e.getValue(), until);
            String range = "minecraft " + Json.write(e.getValue());
            if (!old && !now) {
                out.put(e.getKey(), "not loaded on " + from + " or " + until + " (" + range + ")");
            } else if (old && !now && forNew != null) {
                out.put(e.getKey(), "for " + from + " (" + range + "); the loader runs " + forNew.substring(forNew.lastIndexOf('/') + 1) + " on " + until + " instead");
            }
        }
        return out;
    }

    /**
     * Whether Fabric Loader loads a nested mod on a release, by the mod's own fabric.mod.json: its resolver takes a nested
     * mod only when the mod's "depends" hold, and passes it over otherwise. A jar that names no "minecraft", or whose
     * metadata cannot be read, is loaded.
     */
    static boolean fabricLoads(byte[] jar, String release) {
        Object range = fabricMinecraft(jar);
        return range == null || fabricAccepts(range, release);
    }

    /** The "minecraft" a jar's fabric.mod.json depends on, or null. */
    static Object fabricMinecraft(byte[] jar) {
        if (jar == null) {
            return null;
        }
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (e.getName().equals("fabric.mod.json")) {
                    Object meta = Json.parse(new String(z.readAllBytes(), StandardCharsets.UTF_8));
                    return meta instanceof Map<?, ?> m && m.get("depends") instanceof Map<?, ?> d ? d.get("minecraft") : null;
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        return null;
    }

    /**
     * Whether a fabric.mod.json version predicate accepts a release: a string of terms that must all hold ("||" between
     * alternatives), or a list any one of which may. Build metadata after "+" never counts in Fabric's comparison.
     */
    static boolean fabricAccepts(Object range, String release) {
        if (range instanceof List<?> any) {
            for (Object r : any) {
                if (fabricAccepts(r, release)) {
                    return true;
                }
            }
            return any.isEmpty();
        }
        for (String alternative : String.valueOf(range).split("\\|\\|")) {
            if (Versions.matches(alternative.replaceAll("\\+[^\\s]*", ""), release)) {
                return true;
            }
        }
        return false;
    }

    /** Every access transformer in a jar and its nested jars. */
    List<String> accessTransformers(Map<String, byte[]> entries, String name) throws IOException {
        List<String> out = new ArrayList<>();
        for (String file : atFiles(entries)) {
            byte[] at = entries.get(file);
            if (at != null) {
                out.add(new String(at, StandardCharsets.UTF_8));
            }
        }
        for (String n : loadedNested(entries, name)) {
            String inner = name + "!" + n.substring(n.lastIndexOf('/') + 1);
            out.addAll(accessTransformers(entries(entries.get(n), inner, new HashMap<>()), inner));
        }
        return out;
    }

    static final Pattern DECLARED_AT = Pattern.compile("\\[\\[accessTransformers\\]\\][^\\[]*?file\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)')");

    /**
     * The access transformers the loader reads from a jar: those its metadata lists (FML 1, NeoForge 20.2, already reads
     * [[accessTransformers]]), else the default one.
     */
    static List<String> atFiles(Map<String, byte[]> entries) {
        List<String> out = new ArrayList<>();
        byte[] toml = entries.getOrDefault(NEW_TOML, entries.get(OLD_TOML));
        if (toml != null) {
            Matcher m = DECLARED_AT.matcher(new String(toml, StandardCharsets.UTF_8));
            while (m.find()) {
                out.add((m.group(1) != null ? m.group(1) : m.group(2)).replaceFirst("^/", ""));
            }
        }
        if (out.isEmpty()) {
            out.add("META-INF/accesstransformer.cfg");
        }
        return out;
    }

    /** An entry name that an unzip would write outside its folder: absolute, a drive, a backslash or a ".." part. */
    static boolean unsafe(String entry) {
        return entry.startsWith("/") || entry.contains("\\") || entry.matches("^[A-Za-z]:.*") || Arrays.asList(entry.split("/")).contains("..");
    }

    void read(Mod mod, byte[] bytes) {
        GameDiff.RawCls rc;
        try {
            rc = GameDiff.parse(bytes);
        } catch (RuntimeException unreadable) {
            return;
        }
        mod.classes.put(rc.name, rc);
        mod.bytes.put(rc.name, bytes);
        ClassModel cm = ClassFile.of().parse(bytes);
        for (MethodModel m : cm.methods()) {
            m.findAttribute(java.lang.classfile.Attributes.code()).ifPresent(code ->
                mod.maxLocals.put(rc.name + "." + m.methodName().stringValue() + m.methodType().stringValue(), ((CodeAttribute) code).maxLocals())
            );
        }
        // A mixin's targets, from @Mixin(value = {Class...}, targets = {"name"...}) in its class annotations.
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if (desc.equals(SUBSCRIBER)) {
                    mod.subscribers.put(rc.name, false);
                    List<Object[]> values = mod.subscriberValues.computeIfAbsent(rc.name, k -> new ArrayList<>());
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(String name, Object value) {
                            values.add(new Object[] { name, value });
                        }

                        @Override
                        public void visitEnum(String name, String d, String value) {
                            if ("bus".equals(name)) {
                                mod.subscribers.put(rc.name, true);
                            }
                        }

                        @Override
                        public AnnotationVisitor visitArray(String name) {
                            List<String[]> items = new ArrayList<>();
                            values.add(new Object[] { name, items });
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public void visitEnum(String n, String d, String value) {
                                    items.add(new String[] { d, value });
                                }
                            };
                        }
                    };
                }
                if (!desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) {
                    return null;
                }
                List<String> targets = mod.mixinTargets.computeIfAbsent(rc.name, k -> new ArrayList<>());
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String n, Object value) {
                                if (value instanceof Type t) {
                                    targets.add(t.getInternalName());
                                } else if (value instanceof String s) {
                                    targets.add(s.replace('.', '/'));
                                }
                            }
                        };
                    }

                    @Override
                    public void visit(String name, Object value) {
                        if (value instanceof Type t) {
                            targets.add(t.getInternalName());
                        }
                    }
                };
            }

            @Override
            public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        if (annotation.equals(SHADOW)) {
                            mod.shadowFields.add(rc.name + "." + name);
                        }
                        return null;
                    }
                };
            }

            // @Accessor("field") / @Invoker("method"), or with no value the name Mixin infers from the method's own name.
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        Type[] params = Type.getArgumentTypes(desc);
                        if (annotation.equals(LISTENER) && params.length == 1 && params[0].getSort() == Type.OBJECT) {
                            mod.listeners.computeIfAbsent(rc.name, k -> new ArrayList<>()).add(params[0].getInternalName());
                            Listener l = new Listener(name, desc, access, params[0].getInternalName(), new ArrayList<>());
                            mod.listenerMethods.computeIfAbsent(rc.name, k -> new ArrayList<>()).add(l);
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public void visit(String n, Object value) {
                                    l.values().add(new Object[] { n, value });
                                }

                                @Override
                                public void visitEnum(String n, String d, String value) {
                                    l.values().add(new Object[] { n, d, value });
                                }
                            };
                        }
                        if (INJECTORS.contains(annotation)) {
                            Injector inj = new Injector(annotation, new ArrayList<>(), new ArrayList<>(), new int[] { -1 }, new int[] { -1, -1 }, new boolean[1]);
                            mod.injectors.put(rc.name + "." + name + desc, inj);
                            return injection(inj, null);
                        }
                        String kind = ACCESSORS.get(annotation);
                        if (kind == null) {
                            return null;
                        }
                        String key = rc.name + "." + name + desc;
                        mod.accessors.put(key, new String[] { kind, null });
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String n, Object value) {
                                if ("value".equals(n) && value instanceof String s && !s.isEmpty()) {
                                    mod.accessors.put(key, new String[] { kind, s });
                                }
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE);
    }

    static final String SUBSCRIBER = "Lnet/neoforged/fml/common/EventBusSubscriber;";
    static final String LISTENER = "Lnet/neoforged/bus/api/SubscribeEvent;";
    static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    static final Set<String> CALLBACKS = Set.of(
        "org/spongepowered/asm/mixin/injection/callback/CallbackInfo",
        "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable"
    );

    /** Mixin's and MixinExtras' annotations that inject at an @At into a target method. */
    static final Set<String> INJECTORS = Set.of(INJECT,
        "Lorg/spongepowered/asm/mixin/injection/Redirect;", "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;", "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
        "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
        "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;", "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
        "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;", "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;");

    /**
     * A handler's injector (its annotation): the target method strings, each @At's {value, target, ordinal} (in @Slice
     * too), and its require (-1: the mixin config's defaultRequire).
     */
    /** ordinal: the injector's own (a @ModifyVariable's nth local of its type), -1 when it gives none, then its index (a @ModifyArg's argument); an @At's is in ats. */
    record Injector(String kind, List<String> methods, List<String[]> ats, int[] require, int[] ordinal, boolean[] cancellable) {}

    /**
     * A handler re-declared with its target's new parameters (desc, new names) and the original body moved to name$mmt:
     * each of the first captured parameters of the original comes from the new parameter from[i], or is a default
     * where the body never reads it.
     */
    record Adapter(String desc, int[] from, int captured, String[][] via) {}

    /**
     * A method added to a mod class with a game method's new name and signature (new names), calling the mod's own
     * (target). Each old argument i is the new parameter from[i], or a default when from[i] is -1. When via is not null
     * the game method's parameters changed type, and the mod's method is replaced: its body moves to the new signature,
     * with each old parameter worked out first from new parameter from[i] through the steps via[i] (null: as it is), so
     * calls in the body that need what the game passes now get it (see Retype).
     */
    record Bridge(String name, String desc, String target, String targetDesc, int[] from, int access, String[][] via) {}

    /** A game class's method (new names) copied into a mod class as a method named as, with access; no source: an empty one. */
    record Copy(String source, String name, String desc, String as, int access) {}

    /** In a Bridge's from: the old parameter is read from the object itself (this, then via's steps), not a new parameter. */
    static final int THIS = -3;

    /** Collects an @Inject's method strings and @At values; at is the @At being read, or null. */
    static AnnotationVisitor injection(Injector inj, String[] at) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String n, Object v) {
                if (at == null && "require".equals(n) && v instanceof Integer i) {
                    inj.require()[0] = i;
                } else if (at == null && "cancellable".equals(n) && Boolean.TRUE.equals(v)) {
                    inj.cancellable()[0] = true;
                } else if (at == null && "ordinal".equals(n) && v instanceof Integer i && i >= 0) {
                    inj.ordinal()[0] = i;
                } else if (at == null && "index".equals(n) && v instanceof Integer i && i >= 0) {
                    inj.ordinal()[1] = i;
                } else if (at != null && v instanceof String s && ("value".equals(n) || "target".equals(n))) {
                    at["value".equals(n) ? 0 : 1] = s;
                } else if (at != null && "ordinal".equals(n) && v instanceof Integer i && i >= 0) {
                    at[2] = String.valueOf(i);
                }
            }

            @Override
            public AnnotationVisitor visitArray(String n) {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String x, Object v) {
                        if (at == null && "method".equals(n) && v instanceof String s) {
                            inj.methods().add(s);
                        }
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String x, String d) {
                        return nested(d);
                    }
                };
            }

            @Override
            public AnnotationVisitor visitAnnotation(String n, String d) {
                return nested(d);
            }

            AnnotationVisitor nested(String d) {
                if (!d.equals(AT)) {
                    return injection(inj, null);
                }
                String[] found = new String[3];
                inj.ats().add(found);
                return injection(inj, found);
            }
        };
    }

    /** A @SubscribeEvent method and its annotation's values ({name, value} or {name, enum desc, value}). */
    record Listener(String name, String desc, int access, String event, List<Object[]> values) {}

    /**
     * Newer loaders send each listener of an @EventBusSubscriber class that names no bus to the bus its event belongs to;
     * older ones (1.21.2's NeoForge, FML 4.0.29) put the whole class on the game bus, and refuse a mod-bus event there.
     * A class whose listeners are all mod-bus events gets bus = MOD written, which every loader honours. A class with
     * both kinds keeps its game-bus listeners; its mod-bus ones are called from a companion class on the mod bus.
     */
    void buses(Mod mod) {
        for (var e : mod.subscribers.entrySet()) {
            List<String> events = mod.listeners.getOrDefault(e.getKey(), List.of());
            long onMod = events.stream().filter(t -> modBus(mod, cls(t))).count();
            if (e.getValue() || onMod == 0) {
                continue;
            }
            if (onMod == events.size()) {
                mod.toModBus.add(e.getKey());
                continue;
            }
            List<Listener> moved = mod.listenerMethods.getOrDefault(e.getKey(), List.of()).stream().filter(l -> modBus(mod, cls(l.event()))).toList();
            if (moved.stream().allMatch(l -> (l.access() & Opcodes.ACC_STATIC) != 0)) {
                mod.split.put(e.getKey(), moved);
            } else {
                mod.unlinked.add("listeners for both event buses in one @EventBusSubscriber class: " + Exposure.dotted(e.getKey()));
            }
        }
    }

    /** The companion of a subscriber with listeners on both buses: on the mod bus, each listener calls the original. */
    byte[] companion(Mod mod, String owner, List<Listener> listeners) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, owner + "$ModBusListeners", null, "java/lang/Object", null);
        AnnotationVisitor av = w.visitAnnotation(SUBSCRIBER, true);
        for (Object[] v : mod.subscriberValues.getOrDefault(owner, List.of())) {
            if (v[1] instanceof List<?> items) {
                AnnotationVisitor array = av.visitArray((String) v[0]);
                for (Object item : items) {
                    String[] e = (String[]) item;
                    array.visitEnum(null, e[0], e[1]);
                }
                array.visitEnd();
            } else {
                av.visit((String) v[0], v[1]);
            }
        }
        av.visitEnum("bus", "Lnet/neoforged/fml/common/EventBusSubscriber$Bus;", "MOD");
        av.visitEnd();
        for (Listener l : listeners) {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, l.name(), l.desc(), null, null);
            AnnotationVisitor sub = m.visitAnnotation(LISTENER, true);
            for (Object[] v : l.values()) {
                if (v.length == 3) {
                    sub.visitEnum((String) v[0], (String) v[1], (String) v[2]);
                } else {
                    sub.visit((String) v[0], v[1]);
                }
            }
            sub.visitEnd();
            m.visitCode();
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, owner, l.name(), l.desc(), false);
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(1, 1);
            m.visitEnd();
        }
        w.visitEnd();
        return w.toByteArray();
    }

    /** Whether an event (new names) is dispatched on a mod's own bus: the loader's lifecycle events and every IModBusEvent. */
    boolean modBus(Mod mod, String event) {
        return event.startsWith("net/neoforged/fml/event/") || isA(mod, event, "net/neoforged/fml/event/IModBusEvent");
    }

    // ---- Verifying: the ported classes as the JVM's verifier sees them on the new release.

    final Set<String> unfit = new TreeSet<>(); // removed classes (old names) whose route made a class of the jar being ported fail the verifier

    /**
     * Ports a jar, and again without each route the verifier found unfit for it (verify), until none is. A route moves a
     * removed class to the class the game put in its place (GuiComponent to GuiGraphics in 1.20), which is not always one a
     * mod's code can use as the old one: a subclass or a value passed as the old class's supertype fails the verifier.
     * Without the route the old class is a stand-in's where one is written for its update (knowledge/standins), and is
     * otherwise reported gone. A mod whose use of the class verifies keeps the route.
     */
    byte[] fitting(byte[] jar, String name, List<Map<String, Object>> results) throws IOException {
        Set<String> dropped = new TreeSet<>();
        for (int round = 0; ; round++) {
            int before = results.size();
            unfit.clear();
            byte[] out = without(dropped, () -> port(jar, name, results, null));
            if (dropped.containsAll(unfit) || round == 4) {
                if (!dropped.isEmpty() && results.size() > before) {
                    @SuppressWarnings("unchecked") Map<String, Integer> changed = (Map<String, Integer>) results.get(results.size() - 1).get("changed");
                    for (String old : dropped) {
                        changed.merge("class route left out, the verifier refuses it: " + Exposure.dotted(old) + " -> " + Exposure.dotted(cls(old)), 1, Integer::sum);
                    }
                }
                return out;
            }
            dropped.addAll(unfit);
            results.subList(before, results.size()).clear();
        }
    }

    interface Porting {
        byte[] run() throws IOException;
    }

    /** Runs a port with the routes of the removed classes dropped left out (a stand-in for one standing in instead). */
    byte[] without(Set<String> dropped, Porting body) throws IOException {
        if (dropped.isEmpty()) {
            return body.run();
        }
        Map<String, String> map = new HashMap<>(classMap), back = new HashMap<>(inverse), routed = new HashMap<>(route.classes), rewritten = new HashMap<>(route.classRewrites);
        Map<String, String> supers = new HashMap<>(successors);
        Map<String, Optional<String[]>> taken = new HashMap<>(takeovers), statics = new HashMap<>(movedStatics);
        Map<String, Optional<GameDiff.Meth>> code = new HashMap<>(movedCode);
        for (String old : dropped) {
            String to = classMap.remove(old);
            route.classes.remove(old);
            route.classRewrites.remove(old);
            if (to != null && old.equals(inverse.get(to))) {
                inverse.remove(to);
                classMap.forEach((k, v) -> {
                    if (v.equals(to)) {
                        inverse.putIfAbsent(to, k);
                    }
                });
            }
            if (standins.containsKey(STANDIN + old)) {
                classMap.put(old, STANDIN + old);
                inverse.put(STANDIN + old, old);
            }
        }
        successors.clear();
        takeovers.clear();
        movedStatics.clear();
        movedCode.clear();
        try {
            return body.run();
        } finally {
            classMap.clear();
            classMap.putAll(map);
            inverse.clear();
            inverse.putAll(back);
            route.classes.clear();
            route.classes.putAll(routed);
            route.classRewrites.clear();
            route.classRewrites.putAll(rewritten);
            successors.clear();
            successors.putAll(supers);
            takeovers.clear();
            takeovers.putAll(taken);
            movedStatics.clear();
            movedStatics.putAll(statics);
            movedCode.clear();
            movedCode.putAll(code);
        }
    }

    static final Pattern TYPE_NAME = Pattern.compile("[\\w$]+(?:/[\\w$]+)+");

    /**
     * Every ported class run through the JVM's verifier against the new release (Exposure.verify), and each failure the port
     * made reported: a method that verified against the old release and fails against the new one throws VerifyError in
     * game though every reference in it links. A failure that names the class a route moved a removed class to marks that
     * route unfit for this jar (fitting), whatever else the jar lacks.
     */
    void verify(Mod mod, Map<String, byte[]> ported, Map<String, byte[]> read) {
        // A failure in a jar that already has a reference that does not link is mostly that reference, left as written.
        boolean report = mod.unlinked.isEmpty();
        Map<String, GameDiff.Cls> now = headers(mod, ported, true), was = headers(mod, read, false);
        Map<String, Set<String>> routedTo = new HashMap<>(); // a class of the new release -> removed classes routed to it
        for (var e : classMap.entrySet()) {
            if (a.classes.containsKey(e.getKey()) && !b.classes.containsKey(e.getKey()) && !e.getValue().startsWith(STANDIN)) {
                routedTo.computeIfAbsent(e.getValue(), k -> new TreeSet<>()).add(e.getKey());
            }
        }
        for (var e : ported.entrySet()) {
            byte[] bytes = e.getValue();
            // Before Java 6's class files the JVM verifies by inference, which the JDK's copy of the verifier does not do.
            if (bytes.length < 8 || ((bytes[6] & 0xFF) << 8 | (bytes[7] & 0xFF)) < 50) {
                continue;
            }
            List<String> errors = Exposure.verify(bytes, n -> known(now, b, n));
            if (errors.isEmpty()) {
                continue;
            }
            byte[] before = read.get(e.getKey());
            Set<String> failedBefore = new HashSet<>();
            if (before != null) {
                for (String x : Exposure.verify(before, n -> known(was, a, n))) {
                    failedBefore.add(verifiedMethod(x));
                }
            }
            for (String x : errors) {
                if (failedBefore.contains(verifiedMethod(x))) {
                    continue;
                }
                unfit.addAll(blamed(x, routedTo, was));
                if (Sites.WHERE) {
                    System.err.println("verifier: " + x);
                }
                if (report) {
                    mod.unlinked.add("does not verify: " + verifyReason(x));
                }
            }
        }
    }

    static final Pattern NOT_ASSIGNABLE = Pattern.compile("\\(([\\w/$]+) is not assignable from ([\\w/$]+)\\)");

    /**
     * The routes a verifier line blames: where a value is not of the type wanted, the route that made the value's class
     * (TranslatableComponent, now TranslatableContents, passed as a MutableComponent), else the one that made the wanted
     * type; where the line names no types, the routes of the classes it names; and the route of the superclass of the class
     * that fails, which a subclass of a routed class (a mod's own TranslatableComponent) no longer extends as it did.
     */
    Set<String> blamed(String line, Map<String, Set<String>> routedTo, Map<String, GameDiff.Cls> was) {
        Set<String> out = new TreeSet<>();
        Matcher n = NOT_ASSIGNABLE.matcher(line);
        if (n.find()) {
            out.addAll(routedTo.getOrDefault(n.group(2), Set.of()));
            if (out.isEmpty()) {
                out.addAll(routedTo.getOrDefault(n.group(1), Set.of()));
            }
        } else {
            Matcher t = TYPE_NAME.matcher(line);
            while (t.find()) {
                out.addAll(routedTo.getOrDefault(t.group(), Set.of()));
            }
        }
        Matcher m = Exposure.VERIFIED_METHOD.matcher(line);
        GameDiff.Cls own = m.find() ? was.get(m.group(1)) : null;
        if (own != null && own.sup != null && routedTo.values().stream().anyMatch(olds -> olds.contains(own.sup))) {
            out.add(own.sup);
        }
        return out;
    }

    /**
     * The classes of a jar as the verifier's class hierarchy needs them: name, access, superclass, interfaces. A mixin's
     * code runs as its target's, where this is the target: it is read as the target's subclass (with several targets, as
     * any of them), so what the port points at the target ("this.field" moved to the target's renamed field) verifies as
     * it will once merged.
     */
    Map<String, GameDiff.Cls> headers(Mod mod, Map<String, byte[]> classes, boolean ported) {
        Map<String, GameDiff.Cls> out = new HashMap<>();
        for (var e : classes.entrySet()) {
            if (!e.getKey().endsWith(".class")) {
                continue;
            }
            try {
                ClassReader r = new ClassReader(e.getValue());
                String sup = r.getSuperName();
                List<String> targets = mod.mixinTargets.getOrDefault(r.getClassName(), List.of());
                if (!targets.isEmpty() && (r.getAccess() & Opcodes.ACC_INTERFACE) == 0) {
                    sup = targets.size() > 1 ? null : ported ? cls(targets.get(0)) : targets.get(0);
                }
                out.put(r.getClassName(), new GameDiff.Cls(r.getClassName(), r.getAccess(), sup, List.of(r.getInterfaces()), false));
            } catch (RuntimeException unreadable) {
                // Not a class file the verifier could read either; it reports it.
            }
        }
        return out;
    }

    static GameDiff.Cls known(Map<String, GameDiff.Cls> own, GameDiff.Snap release, String name) {
        GameDiff.Cls c = own.get(name);
        c = c != null ? c : release.classes.get(name);
        return c != null ? c : release.libraries.get(name);
    }

    /** Which method a verifier line is about, "Class::name", so a failure the jar had before the port is told apart. */
    static String verifiedMethod(String line) {
        Matcher m = Exposure.VERIFIED_METHOD.matcher(line);
        return m.find() ? m.group(1) + "::" + m.group(2) : line;
    }

    /** A verifier line as the report shows it: its reason and the types it names, then where (in Class.method). */
    static String verifyReason(String line) {
        String first = line.lines().findFirst().orElse(line).trim();
        Matcher m = Exposure.VERIFIED_METHOD.matcher(first);
        if (!m.find()) {
            return first;
        }
        String reason = first.substring(0, m.start()).trim();
        String rest = first.substring(m.end()).replaceFirst("^[^)]*\\)\\s*(@\\d+)?\\s*", "").trim();
        return reason + (rest.isEmpty() ? "" : ": " + Exposure.dotted(rest)) + " (in " + Exposure.dotted(m.group(1)) + "." + m.group(2) + ")";
    }

    // ---- Linking: where the old release declares a member, and whether the new one has it as the JVM sees it.

    String cls(String internal) {
        return classMap.getOrDefault(internal, internal);
    }

    final Map<String, String> successors = new HashMap<>(); // game superclass (old name) -> successor(), "" for none

    /**
     * What a class that extended the game's class s extends in the new release, decided the way the game decided for its
     * own subclasses of s: the class most of them extend now (Gui, AbstractWidget and the rest of GuiComponent's extend
     * Object in 1.20, though the route map follows GuiComponent's members to GuiGraphics). Null when s is still there under
     * its name, or when its subclasses extend what s became (a rename).
     */
    String successor(String s) {
        String found = successors.computeIfAbsent(s, k -> {
            GameDiff.Cls old = a.classes.get(k);
            if (old == null || b.classes.containsKey(k) && !becameInterface(k)) {
                return "";
            }
            // The old class written on the new release: a mod's subclasses extend it, though the route moves the class's
            // values elsewhere (1.19.4's GuiComponent, whose drawing went to GuiGraphics, an object the game passes in).
            String standin = STANDIN + k;
            if (standins.containsKey(standin) && extendable(standins.get(standin))) {
                return standin.equals(classMap.get(k)) ? "" : standin;
            }
            Map<String, Integer> votes = new TreeMap<>();
            for (GameDiff.Cls x : a.classes.values()) {
                GameDiff.Cls now = k.equals(x.sup) ? b.classes.get(cls(x.name)) : null;
                if (now != null && now.sup != null) {
                    votes.merge(now.sup, 1, Integer::sum);
                }
            }
            String moved = votes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            if (moved == null) {
                if (becameInterface(k)) {
                    return "java/lang/Object";
                }
                if (b.classes.containsKey(cls(k))) {
                    return "";
                }
                String up = old.sup == null ? null : cls(old.sup);
                moved = up != null && (b.classes.containsKey(up) || up.startsWith("java/")) ? up : "java/lang/Object";
            }
            return moved.equals(cls(k)) ? "" : moved;
        });
        return found.isEmpty() ? null : found;
    }

    /**
     * Whether a game class became an interface (1.17's BlockEntityRenderer): what extended it extends what the game's own
     * subclasses extend now, and implements it.
     */
    boolean becameInterface(String s) {
        GameDiff.Cls old = a.classes.get(s), now = b.classes.get(cls(s));
        return old != null && now != null && (old.flags & Opcodes.ACC_INTERFACE) == 0 && (now.flags & Opcodes.ACC_INTERFACE) != 0;
    }

    /** A mod class's superclass in the new release (new names): what replaces one the game removed, or its new name. */
    String superOf(GameDiff.RawCls own) {
        if (own.sup == null) {
            return null;
        }
        String replaced = successor(own.sup);
        return replaced != null ? replaced : cls(own.sup);
    }

    String desc(String d) {
        return mapDesc(d, classMap);
    }

    String oldDesc(String d) {
        return mapDesc(d, inverse);
    }

    static final Pattern CLASS_IN_DESC = Pattern.compile("L([^;<]+)([;<])");

    static String mapDesc(String d, Map<String, String> names) {
        if (d == null) {
            return null;
        }
        Matcher m = CLASS_IN_DESC.matcher(d);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement("L" + names.getOrDefault(m.group(1), m.group(1)) + m.group(2)));
        }
        return m.appendTail(sb).toString();
    }

    record Decl(String owner, boolean game) {}

    Carried tree = new Carried(Map.of()); // every class the jar being ported and its nested jars carry

    /**
     * The classes a jar and its nested jars carry, each read only when asked for: a long port asked for few of them at each
     * step, and reading every class of every jar at every step was a twentieth of the port. A name carried twice is the
     * first copy that reads.
     */
    static final class Carried {
        final Map<String, List<byte[]>> copies;
        final Map<String, Optional<GameDiff.RawCls>> read = new HashMap<>();

        Carried(Map<String, List<byte[]>> copies) {
            this.copies = copies;
        }

        GameDiff.RawCls get(String name) {
            return read.computeIfAbsent(name, n -> {
                for (byte[] bytes : copies.getOrDefault(n, List.of())) {
                    try {
                        return Optional.of(GameDiff.parse(bytes));
                    } catch (RuntimeException unreadable) {
                        // read() skips such a class too
                    }
                }
                return Optional.empty();
            }).orElse(null);
        }
    }

    /**
     * A class the jar carries in one of its own nested jars, which the new release lacks and whose package the new release
     * no longer has (a module the platform stopped shipping, so the bundled copy is what runs): it is the mod's, not
     * gone. A class whose package the new release still has is left to the game: the platform's newer copy runs.
     */
    GameDiff.RawCls provided(String c) {
        GameDiff.RawCls own = tree.get(c);
        if (own == null || b.classes.containsKey(cls(c))) {
            return null;
        }
        return packagesB().contains(pkg(c)) ? null : own;
    }

    Set<String> packagesB;

    Set<String> packagesB() {
        if (packagesB == null) {
            packagesB = new HashSet<>();
            b.classes.keySet().forEach(k -> packagesB.add(pkg(k)));
        }
        return packagesB;
    }

    /** Every class in a jar and its nested jars, by name. */
    void carried(Map<String, byte[]> entries, String name, Map<String, List<byte[]>> out) throws IOException {
        for (var e : entries.entrySet()) {
            if (e.getKey().endsWith(".class") && !e.getKey().startsWith("META-INF/versions/")) {
                try {
                    out.computeIfAbsent(ClassFile.of().parse(e.getValue()).thisClass().asInternalName(), k -> new ArrayList<>()).add(e.getValue());
                } catch (RuntimeException unreadable) {
                    // read() skips such a class too
                }
            }
        }
        for (String n : loadedNested(entries, name)) {
            String inner = name + "!" + n.substring(n.lastIndexOf('/') + 1);
            carried(entries(entries.get(n), inner, new HashMap<>()), inner, out);
        }
    }

    /** The class that declares a member reached through owner in the old release: the mod's own classes first, then the game. */
    Decl declaringOld(Mod mod, String owner, char kind, String name, String desc) {
        Deque<String> queue = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String c = queue.poll();
            if (!seen.add(c)) {
                continue;
            }
            GameDiff.RawCls own = mod.classes.get(c);
            if (own == null && provided(c) != null) {
                // A class another of the mod's jars carries: its own members are the mod's; what it inherits is left as
                // written, as for any library class this jar does not hold.
                GameDiff.RawCls carried = provided(c);
                return kind == 'f' ? carried.fields.containsKey(name) ? new Decl(c, false) : null
                    : carried.methods.containsKey(name + desc) ? new Decl(c, false) : null;
            }
            if (own != null) {
                if (kind == 'f' ? own.fields.containsKey(name) : own.methods.containsKey(name + desc)) {
                    return new Decl(c, false);
                }
                if (own.sup != null) {
                    queue.add(own.sup);
                }
                queue.addAll(own.ifaces);
                continue;
            }
            GameDiff.Cls game = Exposure.declaring(a, c, kind, name, desc);
            if (game != null) {
                return new Decl(game.name, true);
            }
        }
        return null;
    }

    /**
     * Whether the new release (with the mod's own classes, renamed) resolves the member from owner (new names), and one of
     * callers (the class the code runs in; a mixin's code runs in each of its targets) may use it, as the JVM checks.
     */
    boolean linksNew(Mod mod, List<String> callers, String owner, char kind, String name, String desc) {
        if (!classOpen(mod, callers, owner)) {
            return false;
        }
        Deque<String> queue = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String c = queue.poll();
            if (!seen.add(c)) {
                continue;
            }
            GameDiff.RawCls own = mod.classes.get(c);
            if (own != null) {
                String[] renamed = mod.renamedDecl.get(c + "." + name + (kind == 'f' ? "" : desc));
                if (renamed == null) {
                    if (kind == 'f' ? own.fields.containsKey(name) : own.methods.containsKey(name + oldDesc(desc))) {
                        return true;
                    }
                }
                for (var r : mod.renamedDecl.entrySet()) {
                    if (r.getKey().startsWith(c + ".") && r.getValue()[0].equals(name) && (kind == 'f' || desc.equals(r.getValue()[1]))) {
                        return true;
                    }
                }
                if (kind == 'm' && name.startsWith("<")) {
                    return false; // a constructor is the class's own
                }
                if (own.sup != null) {
                    queue.add(cls(own.sup));
                }
                for (String i : own.ifaces) {
                    queue.add(cls(i));
                }
                continue;
            }
            GameDiff.Cls holder = Exposure.declaring(b, c, kind, name, kind == 'f' ? null : desc);
            if (holder != null) {
                int flags = kind == 'f' ? holder.fields.get(name).flags : holder.methods.get(name + desc).flags;
                return memberOpen(mod, callers, holder.name, kind == 'f' ? name : name + desc, flags);
            }
            if (c.startsWith("java/") && kind == 'm') {
                return true; // Object's methods reached through an interface
            }
        }
        return false;
    }

    /** A game class of the new release that callers may name: public, or in their package. */
    boolean classOpen(Mod mod, List<String> callers, String owner) {
        GameDiff.Cls c = b.classes.get(owner);
        if (c == null || (c.flags & Opcodes.ACC_PUBLIC) != 0 || mod.widened.contains(owner)) {
            return true;
        }
        return callers.stream().anyMatch(caller -> pkg(caller).equals(pkg(owner)));
    }

    /** Whether one of callers may use a member with these flags declared in holder (new names), after the mod's access transformer. */
    boolean memberOpen(Mod mod, List<String> callers, String holder, String member, int flags) {
        boolean method = member.contains("(");
        String every = holder + (method ? ".*()" : ".*");
        if ((flags & Opcodes.ACC_PUBLIC) != 0 || mod.widened.contains(holder + "." + member) || mod.widened.contains(every) && !member.startsWith("<init>")) {
            return true;
        }
        if (mod.widened.contains("~" + holder + "." + member) || mod.widened.contains("~" + every) && !member.startsWith("<init>")) {
            flags = flags & ~Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED;
        }
        for (String caller : callers) {
            if (caller.equals(holder)) {
                return true;
            }
            if ((flags & Opcodes.ACC_PRIVATE) != 0) {
                continue;
            }
            if (pkg(caller).equals(pkg(holder)) || (flags & Opcodes.ACC_PROTECTED) != 0 && subclass(mod, caller, holder)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A game member reached from owner (new names) that the callers may not use: when the loader reads the mod's access
     * transformer, the member, and its class when that is closed too, is opened there and counts as open from here on.
     * The loader's own classes are left as they are. Returns whether it was opened.
     */
    boolean opened(Mod mod, List<String> callers, String owner, char kind, String name, String desc) {
        if (!mod.canWiden) {
            return false;
        }
        String c = owner;
        while (c != null && mod.classes.containsKey(c)) {
            GameDiff.RawCls own = mod.classes.get(c);
            if (kind == 'f' ? own.fields.containsKey(name) : own.methods.containsKey(name + oldDesc(desc))) {
                return false;
            }
            c = own.sup == null ? null : cls(own.sup);
        }
        GameDiff.Cls holder = c == null ? null : Exposure.declaring(b, c, kind, name, desc);
        if (holder == null || holder.loader) {
            return false;
        }
        String member = kind == 'f' ? name : name + desc;
        boolean classOk = classOpen(mod, callers, owner);
        boolean memberOk = memberOpen(mod, callers, holder.name, member, kind == 'f' ? holder.fields.get(name).flags : holder.methods.get(member).flags);
        GameDiff.Cls site = b.classes.get(owner);
        if (classOk && memberOk || !classOk && (site == null || site.loader)) {
            return false;
        }
        if (!classOk) {
            mod.grants.add("public " + Exposure.dotted(owner));
            mod.widened.add(owner);
        }
        if (!memberOk) {
            mod.grants.add("public " + Exposure.dotted(holder.name) + " " + member);
            mod.widened.add(holder.name + "." + member);
        }
        return true;
    }

    static String pkg(String internal) {
        int slash = internal.lastIndexOf('/');
        return slash < 0 ? "" : internal.substring(0, slash);
    }

    /** Whether c (new names) extends holder, through the mod's own classes and the new release. */
    boolean subclass(Mod mod, String c, String holder) {
        for (String k = c; k != null; ) {
            if (k.equals(holder)) {
                return true;
            }
            GameDiff.RawCls own = mod.classes.get(k);
            if (own != null) {
                k = superOf(own);
                continue;
            }
            GameDiff.Cls game = b.classes.get(k);
            k = game == null ? null : game.sup;
        }
        return false;
    }

    /** Whether c (new names) is target or extends or implements it, through the mod's own classes and the new release. */
    boolean isA(Mod mod, String c, String target) {
        Deque<String> queue = new ArrayDeque<>(List.of(c));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String k = queue.poll();
            if (k.equals(target)) {
                return true;
            }
            if (!seen.add(k)) {
                continue;
            }
            GameDiff.RawCls own = mod.classes.get(k);
            if (own != null) {
                if (own.sup != null) {
                    queue.add(superOf(own));
                }
                own.ifaces.forEach(i -> queue.add(cls(i)));
                continue;
            }
            GameDiff.Cls game = b.classes.get(k);
            if (game != null) {
                if (game.sup != null) {
                    queue.add(game.sup);
                }
                queue.addAll(game.ifaces);
                continue;
            }
            Class<?> jdk = jdk(k);
            if (jdk != null) {
                if (jdk.getSuperclass() != null) {
                    queue.add(Type.getInternalName(jdk.getSuperclass()));
                }
                for (Class<?> i : jdk.getInterfaces()) {
                    queue.add(Type.getInternalName(i));
                }
            }
        }
        return false;
    }

    /** {name, descriptor} of the one abstract method of a functional interface (the game's or the JDK's), or null. */
    String[] functional(String internal) {
        List<String[]> found = new ArrayList<>();
        GameDiff.Cls game = b.classes.get(internal);
        if (game != null) {
            Deque<GameDiff.Cls> queue = new ArrayDeque<>(List.of(game));
            Set<String> defaults = new HashSet<>();
            while (!queue.isEmpty()) {
                GameDiff.Cls k = queue.poll();
                for (GameDiff.Meth m : k.methods.values()) {
                    if ((m.flags & Opcodes.ACC_ABSTRACT) != 0 && !OBJECT.contains(m.name + m.desc) && !defaults.contains(m.name + m.desc)) {
                        found.add(new String[] { m.name, m.desc });
                    } else if ((m.flags & Opcodes.ACC_STATIC) == 0) {
                        defaults.add(m.name + m.desc);
                    }
                }
                k.ifaces.stream().map(b.classes::get).filter(Objects::nonNull).forEach(queue::add);
            }
        } else if (internal.startsWith("java/")) {
            try {
                Class<?> type = Class.forName(internal.replace('/', '.'), false, null);
                for (java.lang.reflect.Method m : type.getMethods()) {
                    if (java.lang.reflect.Modifier.isAbstract(m.getModifiers()) && !OBJECT.contains(m.getName() + Type.getMethodDescriptor(m))) {
                        found.add(new String[] { m.getName(), Type.getMethodDescriptor(m) });
                    }
                }
            } catch (ClassNotFoundException | LinkageError notJdk) {
                return null;
            }
        }
        return found.stream().map(f -> f[0] + f[1]).distinct().count() == 1 ? found.get(0) : null;
    }

    record FieldHit(String desc, boolean open) {}

    /** {owner, name, desc} in new names of the field a read now resolves to, open to the mod, whatever its type; or null. */
    String[] fieldNow(Mod mod, List<String> callers, String siteOwner, String name, Decl decl) {
        String[] moved = route.field(decl.owner(), name);
        for (String[] t : List.of(new String[] { cls(siteOwner), name }, new String[] { cls(siteOwner), moved[1] }, new String[] { moved[0], moved[1] })) {
            FieldHit found = fieldNew(mod, callers, t[0], t[1]);
            if (found != null && (found.open() || t[1].equals(moved[1]) && opened(mod, callers, t[0], 'f', t[1], null))) {
                return new String[] { t[0], t[1], found.desc() };
            }
            if (found != null) {
                return null;
            }
        }
        return null;
    }

    /**
     * The steps that give a value of type had (a descriptor, new names) from one of type got, as the game's own code gets
     * the value a holder of got holds (holder.value() cast to it, option.get() unboxed), agreed over the game's uses; or null.
     * Each step is "name()desc" (a call on got), a class to cast to, or a box's "xValue()X".
     */
    String[] unwrap(String got, String had) {
        GameCode code = code(b);
        if (code == null || !got.startsWith("L") || got.equals(had)) {
            return null;
        }
        String held = Type.getType(got).getInternalName();
        Map<String, Integer> votes = new HashMap<>();
        boolean jdk = false;
        for (var e : code.unwraps().getOrDefault(had, Map.of()).entrySet()) {
            if (subtype(b, held, e.getKey())) {
                e.getValue().forEach((steps, n) -> votes.merge(steps, n, Integer::sum));
                jdk |= e.getKey().startsWith("java/");
            }
        }
        String best = Retype.agreed(votes);
        if (best == null) {
            return null;
        }
        String[] steps = best.split(" ");
        GameDiff.Cls holder = Exposure.declaring(b, held, 'm', steps[0].substring(0, steps[0].indexOf('(')), steps[0].substring(steps[0].indexOf('(')));
        GameDiff.Meth m = holder == null ? null : holder.methods.get(steps[0]);
        return m != null && (m.flags & Opcodes.ACC_PUBLIC) != 0 && !m.isStatic() || holder == null && jdk ? steps : null;
    }

    /** The field the new release resolves from owner (new names), through the mod's own classes, or null. */
    FieldHit fieldNew(Mod mod, List<String> callers, String owner, String name) {
        for (String c = owner; c != null; ) {
            GameDiff.RawCls own = mod.classes.get(c);
            if (own == null) {
                GameDiff.Cls holder = Exposure.declaring(b, c, 'f', name, null);
                if (holder == null) {
                    return null;
                }
                GameDiff.Fld f = holder.fields.get(name);
                return new FieldHit(f.desc, classOpen(mod, callers, owner) && memberOpen(mod, callers, holder.name, name, f.flags));
            }
            if (own.fields.containsKey(name)) {
                return new FieldHit(desc(own.fields.get(name)[0]), true);
            }
            c = own.sup == null ? null : cls(own.sup);
        }
        return null;
    }

    /**
     * {owner, name, desc} in new names that a reference to a game member becomes, or null when nothing in the new release
     * links. A new descriptor is taken only when the arguments already on the stack still fit it: the same types after class
     * renames, or parameters that became supertypes, with the same return or one that serves where the old one was used
     * (a subtype; a field read may also now hold a subtype). Anything else (an added, removed or narrowed
     * parameter, another return type) does not link here; dropped arguments are handled at the call site.
     */
    /**
     * t, or null where the game made the method static or an instance one: the old call's instruction no longer fits and
     * the JVM refuses it (IncompatibleClassChangeError: NeoForge 1.21.9's FMLLoader.getLoadingModList() is an instance
     * method of FMLLoader.getCurrent()), so the call is not linked; a replacement learned for it may still take its place.
     */
    String[] sameKind(Decl decl, String name, String desc, String[] t) {
        GameDiff.Cls was = Exposure.declaring(a, decl.owner(), 'm', name, desc), now = Exposure.declaring(b, t[0], 'm', t[1], t[2]);
        GameDiff.Meth x = was == null ? null : was.methods.get(name + desc), y = now == null ? null : now.methods.get(t[1] + t[2]);
        return x != null && y != null && x.isStatic() != y.isStatic() ? null : t;
    }

    String[] target(Mod mod, List<String> callers, String siteOwner, char kind, String name, String desc, Decl decl) {
        String newSite = cls(siteOwner);
        if (unchangedLibrary(kind, decl.owner(), name, desc)) {
            // A library member both releases have as it was: whatever lets the mod use it (its own newer copy of the
            // library, say) still does; only what changed inside a library is the port's.
            return new String[] { newSite, name, desc(desc) };
        }
        if (kind == 'f' || kind == 'r') {
            String[] moved = route.field(decl.owner(), name);
            String fieldDesc = desc(desc);
            for (String[] t : List.of(new String[] { newSite, name }, new String[] { newSite, moved[1] }, new String[] { moved[0], moved[1] })) {
                FieldHit found = fieldNew(mod, callers, t[0], t[1]);
                // A field read ('r') may now hold a subtype (1.21.2: InteractionResult.SUCCESS is an InteractionResult$Success):
                // the value read still serves where the old type was used. A write must match exactly.
                boolean fits = found != null && (found.desc().equals(fieldDesc) || kind == 'r' && usable(Type.getType(found.desc()), Type.getType(fieldDesc)));
                if (fits && (found.open() || t[1].equals(moved[1]) && opened(mod, callers, t[0], 'f', t[1], null))) {
                    return new String[] { t[0], t[1], found.desc() };
                }
                if (found != null) {
                    return null; // there with another type, or closed to the mod: the access fails to link
                }
            }
            return null;
        }
        String[] moved = route.method(decl.owner(), name, desc);
        String mapped = desc(desc);
        if (linksNew(mod, callers, newSite, 'm', name, mapped)) {
            return sameKind(decl, name, desc, new String[] { newSite, name, mapped });
        }
        // The same method, closed to the mod now: opened through the mod's access transformer.
        if (moved[1].equals(name) && moved[2].equals(mapped) && opened(mod, callers, newSite, 'm', name, mapped)) {
            return sameKind(decl, name, desc, new String[] { newSite, name, mapped });
        }
        if (moved[2].equals(mapped) || widens(mapped, moved[2])) {
            if (linksNew(mod, callers, newSite, 'm', moved[1], moved[2])) {
                return sameKind(decl, name, desc, new String[] { newSite, moved[1], moved[2] });
            }
            if (linksNew(mod, callers, moved[0], 'm', moved[1], moved[2])) {
                return sameKind(decl, name, desc, moved);
            }
        }
        // Parameters that became supertypes (1.21.2: NonNullList -> List) with the same return: the old arguments still fit.
        GameDiff.Cls holder = b.classes.get(newSite);
        for (GameDiff.Cls c = holder; c != null; c = c.sup == null ? null : b.classes.get(c.sup)) {
            for (GameDiff.Meth m : c.methods.values()) {
                if (m.name.equals(moved[1]) && widens(mapped, m.desc) && linksNew(mod, callers, newSite, 'm', m.name, m.desc)) {
                    return sameKind(decl, name, desc, new String[] { newSite, m.name, m.desc });
                }
            }
        }
        return null;
    }

    /**
     * Whether a call written for from (new names) still works against to: the same number of arguments, each old one
     * acceptable where the new one is asked (a supertype now), and a result the caller can use as the old one (a subtype now).
     */
    boolean widens(String from, String to) {
        Type[] before = Type.getArgumentTypes(from), after = Type.getArgumentTypes(to);
        if (before.length != after.length || !usable(Type.getReturnType(to), Type.getReturnType(from))) {
            return false;
        }
        for (int i = 0; i < before.length; i++) {
            if (!usable(before[i], after[i])) {
                return false;
            }
        }
        return true;
    }

    /** A value of type value can be used where type wanted is asked, without a cast. */
    boolean usable(Type value, Type wanted) {
        if (value.equals(wanted)) {
            return true;
        }
        if (value.getSort() != Type.OBJECT || wanted.getSort() != Type.OBJECT) {
            return false;
        }
        return Exposure.assignable(b, value.getClassName(), wanted.getClassName());
    }

    /** Positions of the new parameters among the old ones when the new list is the old one with some taken out (one way only), else null. */
    static int[] kept(String from, String to) {
        if (!Type.getReturnType(from).equals(Type.getReturnType(to))) {
            return null;
        }
        return VersionMap.Fix.uniqueEmbedding(VersionMap.Fix.paramTypes(from), VersionMap.Fix.paramTypes(to));
    }

    // ---- What changes in the mod's own declarations: overrides of renamed game methods, mixin shadows and accessors.

    void plan(Mod mod) {
        for (GameDiff.RawCls rc : mod.classes.values()) {
            lambdas(mod, rc);
            List<String> targets = mod.mixinTargets.getOrDefault(rc.name, List.of());
            if (!targets.isEmpty()) {
                for (String t : targets) {
                    if (a.classes.containsKey(t) && !b.classes.containsKey(cls(t))) {
                        mod.unlinked.add("mixin target class gone: " + Exposure.dotted(t) + " (in " + Exposure.dotted(rc.name) + ")");
                    }
                }
                selfCasts(mod, rc.name, targets);
                for (var f : rc.fields.entrySet()) {
                    // A mixin's own field (often @Unique, or named like the target's) is added to the target, not looked up in it.
                    if (mod.shadowFields.contains(rc.name + "." + f.getKey())) {
                        shadowField(mod, rc.name, targets, f.getKey(), f.getValue()[0]);
                    }
                }
                for (GameDiff.RawMeth m : rc.methods.values()) {
                    String key = rc.name + "." + m.name + m.desc;
                    if (mod.accessors.containsKey(key)) {
                        accessor(mod, rc.name, targets, m, mod.accessors.get(key));
                    } else if (!m.name.startsWith("<")) {
                        shadowMethod(mod, rc.name, targets, m.name, m.desc, m.flags);
                    }
                    Injector inj = mod.injectors.get(key);
                    if (inj != null && inj.kind().equals(INJECT)) {
                        retarget(mod, key, targets, m, inj);
                    } else if (inj != null) {
                        retargetOther(mod, key, targets, m, inj);
                    }
                    if (inj != null) {
                        pointsGone(mod, rc.name, key, targets, inj, m.desc);
                    }
                }
                continue;
            }
            for (GameDiff.RawMeth m : rc.methods.values()) {
                if (!m.name.startsWith("<") && (m.flags & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0) {
                    override(mod, rc, m);
                }
            }
        }
        for (GameDiff.RawCls rc : mod.classes.values()) {
            if (mod.mixinTargets.getOrDefault(rc.name, List.of()).isEmpty()) {
                abstracts(mod, rc);
            }
        }
        // A mod class's constructor moves after its mod superclass's has (1.17: a renderer extending the mod's own renderer).
        for (boolean grew = true; grew;) {
            grew = false;
            for (GameDiff.RawCls rc : mod.classes.values()) {
                if (mod.mixinTargets.getOrDefault(rc.name, List.of()).isEmpty()) {
                    for (GameDiff.RawMeth m : List.copyOf(rc.methods.values())) {
                        grew |= m.name.equals("<init>") && constructor(mod, rc, m);
                    }
                }
            }
        }
    }

    /**
     * A mixin's code that casts its own this ((Access) (Object) this) relies on each target being that type, or, when the
     * type is another of the mod's mixins (an accessor), one of that mixin's targets. A target the new release no longer
     * makes one fails the cast when the code runs.
     */
    void selfCasts(Mod mod, String mixin, List<String> targets) {
        byte[] bytes = mod.bytes.get(mixin);
        if (bytes == null) {
            return;
        }
        Set<String> casts = new TreeSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return (access & Opcodes.ACC_STATIC) != 0 ? null : new SelfCast(casts);
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (String cast : casts) {
            List<String> wanted = cast.equals(mixin) ? List.of()
                : mod.mixinTargets.containsKey(cast) ? mod.mixinTargets.get(cast) : a.classes.containsKey(cast) ? List.of(cast) : List.of();
            for (String t : targets) {
                if (!wanted.isEmpty() && b.classes.containsKey(cls(t)) && wanted.stream().anyMatch(w -> isA(a, t, w))
                    && wanted.stream().noneMatch(w -> isA(b, cls(t), cls(w)))) {
                    mod.unlinked.add("mixin target no longer a " + wanted.stream().map(Exposure::dotted).collect(java.util.stream.Collectors.joining(" or ")) + ": "
                        + Exposure.dotted(t) + " (in " + Exposure.dotted(mixin) + ")");
                }
            }
        }
    }

    /** Collects the types this (local 0) is cast to straight after it is loaded. */
    static final class SelfCast extends MethodVisitor {
        final Set<String> casts;
        boolean self;

        SelfCast(Set<String> casts) {
            super(Opcodes.ASM9);
            this.casts = casts;
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            self = opcode == Opcodes.ALOAD && var == 0;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (self && opcode == Opcodes.CHECKCAST) {
                casts.add(type);
            }
            self = false;
        }

        @Override
        public void visitInsn(int opcode) {
            self = false;
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            self = false;
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            self = false;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            self = false;
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            self = false;
        }

        @Override
        public void visitLdcInsn(Object value) {
            self = false;
        }
    }

    /** Whether c is target or extends or implements it, through one release's classes and libraries. */
    static boolean isA(GameDiff.Snap s, String c, String target) {
        Deque<String> queue = new ArrayDeque<>(List.of(c));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String k = queue.poll();
            if (k.equals(target)) {
                return true;
            }
            GameDiff.Cls game = s.classes.getOrDefault(k, s.libraries.get(k));
            if (game != null && seen.add(k)) {
                queue.addAll(GameDiff.supertypes(game));
            }
        }
        return false;
    }

    /**
     * The game method a mod method overrides, found through the mod's own parents up to the game (ModChild.foo over
     * ModBase.foo over GameBase.foo is GameBase.foo), so every member of an override family follows a rename.
     */
    Decl gameRoot(Mod mod, List<String> parents, String name, String desc) {
        Deque<String> queue = new ArrayDeque<>(parents);
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String c = queue.poll();
            if (!seen.add(c)) {
                continue;
            }
            GameDiff.RawCls own = mod.classes.get(c);
            if (own != null) {
                if (own.sup != null) {
                    queue.add(own.sup);
                }
                queue.addAll(own.ifaces);
                continue;
            }
            GameDiff.Cls game = Exposure.declaring(a, c, 'm', name, desc);
            if (game != null) {
                return new Decl(game.name, true);
            }
        }
        return null;
    }

    /** The game classes (new names) a mod class extends or implements, directly or through the mod's own classes. */
    List<String> gameParents(Mod mod, GameDiff.RawCls rc) {
        List<String> out = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        if (rc.sup != null) {
            queue.add(rc.sup);
        }
        queue.addAll(rc.ifaces);
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String c = queue.poll();
            if (!seen.add(c)) {
                continue;
            }
            GameDiff.RawCls own = mod.classes.get(c);
            if (own == null) {
                out.add(cls(c));
                continue;
            }
            if (own.sup != null) {
                queue.add(own.sup);
            }
            queue.addAll(own.ifaces);
        }
        return out;
    }

    void override(Mod mod, GameDiff.RawCls rc, GameDiff.RawMeth m) {
        List<String> parents = new ArrayList<>(rc.ifaces);
        if (rc.sup != null) {
            parents.add(0, rc.sup);
        }
        Decl decl = gameRoot(mod, parents, m.name, m.desc);
        if (decl == null) {
            return;
        }
        GameDiff.Meth over = a.method(decl.owner(), m.name, m.desc);
        if (over == null || (over.flags & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) != 0) {
            return;
        }
        String[] moved = route.method(decl.owner(), m.name, m.desc);
        String mapped = desc(m.desc);
        // Looked up from the game classes the mod actually extends: a game subclass may keep the old name while its
        // ancestor renames it, and then the mod's method still overrides as written.
        List<String> supers = gameParents(mod, rc);
        String[] took = moved[1].equals(m.name) && moved[2].equals(mapped) ? tookOver(mod, rc, m, supers) : null;
        if (took != null) {
            moved = took;
        } else if (finalHook(mod, rc, m, supers) instanceof String[] hook && moveToHook(mod, rc, m, hook, supers)) {
            return;
        } else if (supers.stream().anyMatch(s -> Exposure.declaring(b, s, 'm', m.name, mapped) != null)) {
            return; // still overrides as written
        }
        String[] to = moved;
        if (moved[2].equals(mapped) && supers.stream().anyMatch(s -> Exposure.declaring(b, s, 'm', to[1], to[2]) != null)) {
            mod.renamedDecl.put(rc.name + "." + m.name + m.desc, new String[] { moved[1], moved[2] });
            mod.change(took != null ? "override moved where the game's own overrides moved" : "override renamed");
            return;
        }
        if (bridge(mod, rc, m, decl.owner(), moved, mapped, supers)) {
            boolean body = mod.bridges.get(rc.name).get(mod.bridges.get(rc.name).size() - 1).via() != null;
            mod.change(body ? "override moved to the game method's new parameter types" : "override bridged to the game method's new signature");
            return;
        }
        // The game no longer calls it: the class still loads, the hook is silent.
        mod.silent.add("override no longer called: " + Exposure.dotted(decl.owner()) + "." + m.name + m.desc + " (in " + Exposure.dotted(rc.name) + ")");
    }

    /**
     * Lambdas and method references of a class that implement a game or loader interface method whose parameters changed
     * type (a Fabric API callback: HudRenderCallback.onHudRender(PoseStack, float) -> (GuiGraphics, float)). The body, a
     * method of the class nothing else can call or override, moves to the new types as an override's does, each old
     * parameter worked out from a new one as the game's own code does (graphics.pose()), and the lambda is made for the
     * new method (Sites.capturing writes it). One that cannot move is reported: the interface's method would go unwritten.
     */
    void lambdas(Mod mod, GameDiff.RawCls rc) {
        byte[] bytes = mod.bytes.get(rc.name);
        if (bytes == null || (new ClassReader(bytes).readShort(6) & 0xFFFF) < Opcodes.V1_8) {
            return;
        }
        List<Object[]> made = new ArrayList<>(); // {interface, method, sam descriptor, impl handle, captured descriptor}
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
                        if (bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") && args.length >= 3 && args[0] instanceof Type sam
                            && args[1] instanceof Handle impl && args[2] instanceof Type made2) {
                            made.add(new Object[] { Type.getReturnType(d).getInternalName(), n, sam.getDescriptor(), impl, d, made2.getDescriptor() });
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        for (Object[] l : made) {
            String iface = (String) l[0], name = (String) l[1], sam = (String) l[2], captured = (String) l[4];
            Handle impl = (Handle) l[3];
            if (!a.classes.containsKey(iface) || a.method(iface, name, sam) == null || Exposure.declaring(b, cls(iface), 'm', name, desc(sam)) != null) {
                continue;
            }
            String[] moved = route.method(iface, name, sam);
            GameDiff.Cls holder = Exposure.declaring(b, cls(iface), 'm', moved[1], moved[2]);
            String where = Exposure.dotted(iface) + "." + name + sam + " (in " + Exposure.dotted(rc.name) + ")";
            Bridge body = holder == null || !moved[1].equals(name) || !sam.equals(l[5]) ? null : lambdaBody(mod, rc, impl, captured, iface, name, sam, moved);
            if (body == null) {
                mod.unlinked.add("lambda for a changed method: " + where);
                continue;
            }
            mod.bridges.computeIfAbsent(impl.getOwner(), k -> new ArrayList<>()).add(body);
            mod.change("lambda moved to its interface method's new parameter types");
        }
    }

    /**
     * The moved body of a lambda for iface.name(sam), now moved[2], or null when its method cannot move: the lambda's own
     * private method, or a static method of any of the mod's classes (Hud::render), which no class can override.
     */
    Bridge lambdaBody(Mod mod, GameDiff.RawCls rc, Handle impl, String captured, String iface, String name, String sam, String[] moved) {
        GameDiff.RawCls holder = mod.classes.get(impl.getOwner());
        GameDiff.RawMeth m = holder == null ? null : holder.methods.get(impl.getName() + impl.getDesc());
        if (m == null || (m.flags & Opcodes.ACC_STATIC) == 0 && ((m.flags & Opcodes.ACC_PRIVATE) == 0 || holder != rc) || (m.flags & Opcodes.ACC_ABSTRACT) != 0
            || mod.bytes.get(holder.name) == null || (new ClassReader(mod.bytes.get(holder.name)).readShort(6) & 0xFFFF) < Opcodes.V1_6
            || mod.bridges.getOrDefault(holder.name, List.of()).stream().anyMatch(br -> br.target().equals(m.name) && br.targetDesc().equals(m.desc))) {
            return null;
        }
        Type[] caps = Type.getArgumentTypes(captured), was = Type.getArgumentTypes(desc(sam)), now = Type.getArgumentTypes(moved[2]);
        int skip = (m.flags & Opcodes.ACC_STATIC) != 0 ? 0 : 1; // an instance body captures its this first
        Type[] own = Type.getArgumentTypes(m.desc);
        if (own.length != caps.length - skip + was.length || !Type.getReturnType(desc(sam)).equals(Type.getReturnType(moved[2]))) {
            return null;
        }
        int[] paired = pair(now, was);
        String[][] via = Retype.withoutThis(retype.conversions(iface, name, sam, moved, paired));
        boolean[] unread = unread(mod, holder.name, m.name, m.desc, (m.flags & Opcodes.ACC_STATIC) != 0, own.length);
        int k = caps.length - skip;
        Type[] params = new Type[k + now.length];
        int[] from = new int[own.length];
        String[][] steps = new String[own.length][];
        for (int i = 0; i < k; i++) {
            params[i] = Type.getType(desc(own[i].getDescriptor()));
            from[i] = i;
        }
        for (int i = 0; i < now.length; i++) {
            params[k + i] = now[i];
        }
        for (int i = 0; i < was.length; i++) {
            if (paired[i] >= 0) {
                from[k + i] = k + paired[i];
            } else if (via != null && via[i] != null) {
                from[k + i] = k + Integer.parseInt(via[i][0]);
                steps[k + i] = Arrays.copyOfRange(via[i], 1, via[i].length);
            } else if (unread != null && unread[k + i]) {
                from[k + i] = -1; // the body never reads it ((mc, parent) -> new Screen(parent)): a default goes there
            } else {
                return null;
            }
        }
        String newDesc = Type.getMethodDescriptor(Type.getType(desc(Type.getReturnType(m.desc).getDescriptor())), params);
        if (Arrays.stream(steps).allMatch(Objects::isNull)) {
            // Nothing to work out: an added method with the new parameters calls the body with the ones it had.
            return new Bridge(m.name, newDesc, m.name, m.desc, from, m.flags | Opcodes.ACC_SYNTHETIC, null);
        }
        return new Bridge(m.name, newDesc, m.name, m.desc, from, m.flags, steps);
    }

    /**
     * {owner, name, desc} (new names) of an abstract method the new release adds to a mod class's game supertypes that the
     * game's own subclasses moved their override of the mod method's game method into (AbstractButton.renderWidget ->
     * renderContents in 1.21.11, AbstractSelectionList.Entry.render -> renderContent in 1.21.9), when the class does not
     * write it and the game no longer lets the old one be overridden (gone, or final; then the mod's method must not call
     * the game's, which calls the new one). Null otherwise.
     */
    String[] tookOver(Mod mod, GameDiff.RawCls rc, GameDiff.RawMeth m, List<String> supers) {
        String mapped = desc(m.desc);
        for (String s : supers) {
            GameDiff.Cls still = Exposure.declaring(b, s, 'm', m.name, mapped);
            if (still != null && (still.methods.get(m.name + mapped).flags & Opcodes.ACC_FINAL) == 0) {
                return null;
            }
        }
        Deque<String> todo = new ArrayDeque<>(supers);
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            GameDiff.Cls k = b.classes.get(todo.poll());
            if (k == null || !seen.add(k.name)) {
                continue;
            }
            for (GameDiff.Meth n : k.methods.values()) {
                String[] was = (n.flags & Opcodes.ACC_ABSTRACT) == 0 ? null : takeover(k.name, n);
                if (was != null && was[0].equals(m.name) && was[1].equals(m.desc)) {
                    why(rc.name + "." + m.name + m.desc, "T took " + n.name + " written=" + rc.methods.containsKey(n.name + oldDesc(n.desc)) + " super=" + callsSuper(mod, rc.name, m));
                }
                if (was != null && was[0].equals(m.name) && was[1].equals(m.desc) && !rc.methods.containsKey(n.name + oldDesc(n.desc))
                    && !callsSuper(mod, rc.name, m)) {
                    return new String[] { k.name, n.name, n.desc };
                }
            }
            if (k.sup != null) {
                todo.add(k.sup);
            }
            todo.addAll(k.ifaces);
        }
        return null;
    }

    /**
     * {class, name, desc} (new names) of the overridable method a mod method's game method hands its call to, where the
     * update made that game method final (1.20.3's AbstractWidget.render calls renderWidget with its own arguments;
     * 1.21.11's AbstractButton.renderWidget calls renderContents): the one method of the same signature on this that the
     * final one's body calls. Null when the game method is not newly final or calls no such one.
     */
    String[] finalHook(Mod mod, GameDiff.RawCls rc, GameDiff.RawMeth m, List<String> supers) {
        String mapped = desc(m.desc);
        GameDiff.Cls fin = null;
        for (String s : supers) {
            GameDiff.Cls k = Exposure.declaring(b, s, 'm', m.name, mapped);
            if (k != null) {
                fin = (k.methods.get(m.name + mapped).flags & Opcodes.ACC_FINAL) != 0 ? k : null;
                break;
            }
        }
        GameCode code = code(b);
        if (fin == null || code == null || finalBefore(mod, rc, m)) {
            return null;
        }
        Map<String, String> bodies = code.bodies(fin.name, x -> x);
        String body = bodies == null ? null : bodies.get(m.name + mapped);
        if (body == null) {
            return null;
        }
        Set<String> called = new HashSet<>();
        Matcher calls = Pattern.compile("(?m)^18[25]:([^\\n.]+)\\.([^\\n.(]+)" + Pattern.quote(mapped) + "$").matcher(body);
        while (calls.find()) {
            if (!calls.group(2).equals(m.name) && subtype(b, fin.name, calls.group(1))) {
                called.add(calls.group(2));
            }
        }
        if (called.size() != 1) {
            return null;
        }
        String h = called.iterator().next();
        GameDiff.Cls holder = Exposure.declaring(b, fin.name, 'm', h, mapped);
        GameDiff.Meth hook = holder == null ? null : holder.methods.get(h + mapped);
        return hook == null || (hook.flags & (Opcodes.ACC_FINAL | Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0 ? null : new String[] { fin.name, h, mapped };
    }

    /** Whether the game method a mod method overrides was final in the old release too (opened for the mod, then). */
    boolean finalBefore(Mod mod, GameDiff.RawCls rc, GameDiff.RawMeth m) {
        for (GameDiff.RawCls c = rc; c != null; c = c.sup == null ? null : mod.classes.get(c.sup)) {
            List<String> parents = new ArrayList<>(c.ifaces);
            if (c.sup != null) {
                parents.add(c.sup);
            }
            for (String p : parents) {
                GameDiff.Cls k = mod.classes.containsKey(p) ? null : Exposure.declaring(a, p, 'm', m.name, m.desc);
                if (k != null && (k.methods.get(m.name + m.desc).flags & Opcodes.ACC_FINAL) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A mod method overriding a method the update made final moves to the hook the final one calls, as its code ran
     * around the old method's: the final method still does the rest (the visibility test, the hover, the tooltip).
     * The mod's super call to the old method becomes one to the hook the game class above implements; where the class
     * writes the hook itself, that method keeps its body under another name and the super call goes there, as the old
     * method called it. Not where the code calls the method on its own object again (the final one would call it back),
     * or where nothing above implements the hook it would call.
     */
    boolean moveToHook(Mod mod, GameDiff.RawCls rc, GameDiff.RawMeth m, String[] hook, List<String> supers) {
        String h = hook[1], hd = hook[2];
        if (rc.methods.containsKey(h + "$mmt" + oldDesc(hd)) || callsOnSelf(mod, rc.name, m)) {
            why(rc.name + "." + m.name, "H calls itself on its object");
            return false;
        }
        boolean writes = rc.methods.containsKey(h + oldDesc(hd));
        // A mod subclass writing the hook itself, without the old method, would take the final method's call before the
        // moved code (before, the old method ran first and reached its hook through its super call).
        for (GameDiff.RawCls sub : mod.classes.values()) {
            if (sub != rc && subtype(mod, sub.name, rc.name) && sub.methods.containsKey(h + oldDesc(hd)) && !sub.methods.containsKey(m.name + m.desc)) {
                why(rc.name + "." + m.name, "H a subclass writes the hook: " + sub.name);
                return false;
            }
        }
        if (!writes && callsSuperDirectly(mod, rc.name, m)) {
            GameDiff.Cls impl = null;
            for (String s : supers) {
                impl = Exposure.declaring(b, s, 'm', h, hd);
                if (impl != null) {
                    break;
                }
            }
            Copy copy = null;
            if (impl == null || (impl.methods.get(h + hd).flags & Opcodes.ACC_ABSTRACT) != 0) {
                // The old method's own code went into parts the game's subclasses call (1.21.11: Button.Plain's
                // renderContents calls renderDefaultSprite and renderDefaultLabel): the super call runs that code.
                copy = supers.isEmpty() ? null : defaultBody(supers.get(0), m.name, m.desc, h, hd, rc.name);
                if (copy == null) {
                    why(rc.name + "." + m.name, "H no hook above to call: " + (impl == null ? null : impl.name) + " supers=" + supers);
                    return false;
                }
                mod.copies.computeIfAbsent(rc.name, x -> new ArrayList<>()).add(copy);
                mod.superHook.put(rc.name + "." + m.name + m.desc, "copy:" + copy.as());
            }
            if (copy != null) {
                mod.renamedDecl.put(rc.name + "." + m.name + m.desc, new String[] { h, hd });
                mod.change("override of a method the update made final moved to the hook it calls, its super call to the code the game split from it");
                return true;
            }
        }
        if (writes) {
            mod.renamedDecl.put(rc.name + "." + h + oldDesc(hd), new String[] { h + "$mmt", hd });
        }
        mod.superHook.put(rc.name + "." + m.name + m.desc, writes ? "own:" + h + "$mmt" : h);
        mod.renamedDecl.put(rc.name + "." + m.name + m.desc, new String[] { h, hd });
        mod.change("override of a method the update made final moved to the hook it calls");
        return true;
    }

    /**
     * The game code a super call to an old method k inherited runs in the new release, where the update split that
     * method's code into parts: the hook h as written by k's game subclasses that relied on the old method as it was
     * (they did not override it in the old release; 1.21.11's new Button.Plain), when they all write the same code
     * and it uses only what the mod class inherits too. Null otherwise.
     */
    Copy defaultBody(String k, String o, String od, String h, String hd, String modClass) {
        GameCode code = code(b);
        if (code == null) {
            return null;
        }
        Set<String> seen = new HashSet<>();
        String chosen = null;
        for (GameDiff.Cls sub : declaring(b, h + hd)) {
            GameDiff.Meth own = sub.methods.get(h + hd);
            GameDiff.Cls old = a.classes.get(inverse.getOrDefault(sub.name, sub.name));
            if (own == null || (own.flags & Opcodes.ACC_ABSTRACT) != 0 || (sub.flags & Opcodes.ACC_ABSTRACT) != 0 || sub.name.equals(k) || !subtype(b, sub.name, k)
                || old != null && old.methods.containsKey(o + od)) {
                continue;
            }
            Map<String, String> bodies = code.bodies(sub.name, x -> x);
            String text = bodies == null ? null : bodies.get(h + hd);
            if (text != null && copyable(code, sub.name, h, hd, k)) {
                seen.add(text);
                chosen = sub.name;
            }
        }
        return seen.size() == 1 ? new Copy(chosen, h, hd, h + "$default", Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC) : null;
    }

    /**
     * Whether a game class's method uses only what a mod class under k can use too: members it inherits from k and
     * above (not private), and other classes' public members. No new instances or casts of the class itself.
     */
    boolean copyable(GameCode code, String source, String h, String hd, String k) {
        String jar = code.toJar.getOrDefault(source, source);
        byte[] bytes = code.bytes.apply(jar);
        if (bytes == null) {
            return false;
        }
        boolean[] ok = { false };
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                if (!(code.names.method(jar, n, d) + code.names.desc(d)).equals(h + hd)) {
                    return null;
                }
                ok[0] = true;
                return new MethodVisitor(Opcodes.ASM9) {
                    void member(String owner, String name, String desc, boolean field) {
                        String declObf = code.declarer(owner, name, desc, field);
                        String decl = declObf == null ? null : code.names.cls(declObf);
                        String mapped = declObf == null ? name : field ? code.names.field(declObf, name) : code.names.method(declObf, name, desc);
                        GameDiff.Cls dc = decl == null ? null : b.classes.get(decl);
                        if (dc == null) {
                            ok[0] &= !code.names.cls(owner).equals(source); // a JDK member, not one of the class's own
                            return;
                        }
                        int flags = field ? (dc.fields.containsKey(mapped) ? dc.fields.get(mapped).flags : Opcodes.ACC_PRIVATE)
                            : dc.methods.entrySet().stream().filter(e -> e.getKey().startsWith(mapped + "(") && e.getValue().desc.equals(code.names.desc(desc)))
                                .map(e -> e.getValue().flags).findFirst().orElse(Opcodes.ACC_PRIVATE);
                        boolean inherited = subtype(b, k, decl) && (flags & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0; // a mod class is in another package
                        boolean open = (flags & Opcodes.ACC_PUBLIC) != 0 && !code.names.cls(owner).equals(source);
                        ok[0] &= inherited || open;
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                        member(owner, name, desc, true);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        member(owner, name, desc, false);
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        ok[0] &= !code.names.cls(type).equals(source);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) {
                        ok[0] = false;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return ok[0];
    }

    /** Writes a copy of a game class's method into the mod class being written, its own class read as the mod's. */
    void copyBody(Copy copy, String modClass, ClassVisitor out) {
        if (copy.source() == null) {
            String d = oldDesc(copy.desc());
            MethodVisitor mv = out.visitMethod(copy.access(), copy.as(), d, null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 1 + Arrays.stream(Type.getArgumentTypes(d)).mapToInt(Type::getSize).sum());
            mv.visitEnd();
            return;
        }
        GameCode code = code(b);
        String jar = code.toJar.getOrDefault(copy.source(), copy.source());
        org.objectweb.asm.commons.Remapper names = new org.objectweb.asm.commons.Remapper(Opcodes.ASM9) {
            @Override
            public String map(String internal) {
                String n = code.names.cls(internal);
                return n.equals(copy.source()) ? modClass : inverse.getOrDefault(n, n);
            }

            @Override
            public String mapMethodName(String owner, String name, String desc) {
                String decl = name.startsWith("<") ? null : code.declarer(owner, name, desc, false);
                return decl == null ? name : code.names.method(decl, name, desc);
            }

            @Override
            public String mapFieldName(String owner, String name, String desc) {
                String decl = code.declarer(owner, name, desc, true);
                return decl == null ? name : code.names.field(decl, name);
            }
        };
        new ClassReader(code.bytes.apply(jar)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                if (!(code.names.method(jar, n, d) + code.names.desc(d)).equals(copy.name() + copy.desc())) {
                    return null;
                }
                MethodVisitor mv = out.visitMethod(copy.access(), copy.as(), names.mapMethodDesc(d), null, null);
                return mv == null ? null : new org.objectweb.asm.commons.MethodRemapper(mv, names);
            }
        }, 0);
    }

    /**
     * Whether any method of a mod class makes a super call that reaches the game's method of a mod method's name and
     * descriptor (the redirect to the hook applies to the whole class: a helper's super.renderWidget too), through mod
     * parents that only inherit it.
     */
    boolean callsSuperDirectly(Mod mod, String owner, GameDiff.RawMeth m) {
        byte[] bytes = mod.bytes.get(owner);
        boolean[] calls = { false };
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String o, String n, String d, boolean itf) {
                        calls[0] |= opcode == Opcodes.INVOKESPECIAL && n.equals(m.name) && d.equals(m.desc) && gameDeclared(mod, o, n, d);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return calls[0];
    }

    /** Whether mod class c is sup or extends it through the mod's own classes. */
    static boolean subtype(Mod mod, String c, String sup) {
        for (GameDiff.RawCls k = mod.classes.get(c); k != null; k = k.sup == null ? null : mod.classes.get(k.sup)) {
            if (k.name.equals(sup)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a call names a method the game declares, reached through mod classes that only inherit it. */
    boolean gameDeclared(Mod mod, String owner, String name, String desc) {
        Decl decl = owner.startsWith("[") ? null : declaringOld(mod, owner, 'm', name, desc);
        return decl != null && decl.game();
    }

    /**
     * Whether a mod method, or a method of its class it calls on its own object, calls the method of its own name and
     * descriptor on its own object other than as a super call (this.render(...)): the final one would call it back.
     */
    boolean callsOnSelf(Mod mod, String owner, GameDiff.RawMeth m) {
        byte[] bytes = mod.bytes.get(owner);
        if (bytes == null) {
            return true;
        }
        Deque<String> todo = new ArrayDeque<>(List.of(m.name + m.desc));
        Set<String> seen = new HashSet<>();
        boolean[] found = { false };
        while (!todo.isEmpty() && !found[0]) {
            String at = todo.poll();
            if (!seen.add(at)) {
                continue;
            }
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    if (!(name + desc).equals(at) || (access & Opcodes.ACC_STATIC) != 0) {
                        return null;
                    }
                    Object self = new Object();
                    return new org.objectweb.asm.commons.AnalyzerAdapter(Opcodes.ASM9, owner, access, name, desc, null) {
                        @Override
                        public void visitVarInsn(int opcode, int var) {
                            super.visitVarInsn(opcode, var);
                            if (opcode == Opcodes.ALOAD && var == 0 && stack != null) {
                                stack.set(stack.size() - 1, self);
                            }
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String o, String n, String d, boolean itf) {
                            int depth = Arrays.stream(Type.getArgumentTypes(d)).mapToInt(Type::getSize).sum();
                            boolean onSelf = opcode != Opcodes.INVOKESTATIC && stack != null && stack.size() > depth && stack.get(stack.size() - 1 - depth) == self;
                            if (onSelf && opcode != Opcodes.INVOKESPECIAL && n.equals(m.name) && d.equals(m.desc)) {
                                found[0] = true;
                            } else if (onSelf && o.equals(owner) && !n.startsWith("<")) {
                                todo.add(n + d);
                            }
                            super.visitMethodInsn(opcode, o, n, d, itf);
                        }
                    };
                }
            }, ClassReader.EXPAND_FRAMES);
        }
        return found[0];
    }

    final Map<String, Optional<String[]>> takeovers = new HashMap<>();
    final Map<String, Optional<String[]>> movedStatics = new HashMap<>(); // Sites.movedStatic: the same for every mod of the port
    final Map<GameDiff.Snap, Map<String, List<GameDiff.Cls>>> declaringBy = new IdentityHashMap<>(); // release -> name+desc -> its classes declaring it

    /**
     * A release's classes that declare name+desc, in the order its classes map lists them, so a search over them answers as
     * one over every class did. Searching every class at each unlinked call was a third of the port's time (JFR, 150 mods).
     */
    List<GameDiff.Cls> declaring(GameDiff.Snap release, String nameDesc) {
        return declaringBy.computeIfAbsent(release, r -> {
            Map<String, List<GameDiff.Cls>> index = new HashMap<>();
            for (GameDiff.Cls c : r.classes.values()) {
                for (String m : c.methods.keySet()) {
                    index.computeIfAbsent(m, k -> new ArrayList<>()).add(c);
                }
            }
            return index;
        }).getOrDefault(nameDesc, List.of());
    }

    /**
     * {name, desc} (old names) of the method the game's own subclasses of k overrode in the old release and no longer do
     * in the new one, where they write k's new abstract method n instead: at least two, and most of those the old release
     * had, agreeing. Null when they do not show one.
     */
    String[] takeover(String k, GameDiff.Meth n) {
        return takeovers.computeIfAbsent(k + "." + n.name + n.desc, key -> {
            Map<String, Integer> dropped = new HashMap<>();
            Map<String, List<String>> by = new HashMap<>(); // dropped method -> the subclasses that dropped it
            int had = 0;
            for (GameDiff.Cls sub : declaring(b, n.name + n.desc)) {
                GameDiff.Meth own = sub.methods.get(n.name + n.desc);
                GameDiff.Cls old = a.classes.get(inverse.getOrDefault(sub.name, sub.name));
                if (own == null || (own.flags & Opcodes.ACC_ABSTRACT) != 0 || sub.name.equals(k) || old == null || !subtype(b, sub.name, k)
                    || old.methods.containsKey(n.name + oldDesc(n.desc))) {
                    continue;
                }
                had++;
                for (GameDiff.Meth om : old.methods.values()) {
                    if (om.name.startsWith("<") || om.isStatic() || (om.flags & Opcodes.ACC_PRIVATE) != 0) {
                        continue;
                    }
                    boolean overrides = old.sup != null && Exposure.declaring(a, old.sup, 'm', om.name, om.desc) != null
                        || old.ifaces.stream().anyMatch(i -> Exposure.declaring(a, i, 'm', om.name, om.desc) != null);
                    // No longer written under its own name (the route map may follow it to the new method: renderWidget ->
                    // renderContents in each game button, while AbstractButton's final renderWidget stays).
                    if (overrides && !sub.methods.containsKey(om.name + desc(om.desc))) {
                        dropped.merge(om.name + om.desc, 1, Integer::sum);
                        by.computeIfAbsent(om.name + om.desc, x -> new ArrayList<>()).add(sub.name);
                    }
                }
            }
            int total = had;
            List<Map.Entry<String, Integer>> best = dropped.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(2).toList();
            if (best.isEmpty() || best.get(0).getValue() < 2 || best.get(0).getValue() * 10 < total * 8
                || best.size() > 1 && best.get(1).getValue().equals(best.get(0).getValue())) {
                return Optional.empty();
            }
            String sig = best.get(0).getKey();
            String[] was = { sig.substring(0, sig.indexOf('(')), sig.substring(sig.indexOf('(')) };
            // Methods that went at the same time are not one moved to the other (1.19.4 dropped changeFocus as it added
            // visitWidgets): the subclasses' new bodies must be mostly their old ones.
            return moved(by.get(sig), was, n) ? Optional.of(was) : Optional.empty();
        }).orElse(null);
    }

    /**
     * Whether most of the game subclasses' new bodies of n are their old bodies of was: at least half of each body's
     * instructions in common, for most of them. False when the game's code cannot be read.
     */
    boolean moved(List<String> subs, String[] was, GameDiff.Meth n) {
        GameCode oldCode = code(a), newCode = code(b);
        if (oldCode == null || newCode == null) {
            return false;
        }
        int alike = 0;
        for (String sub : subs) {
            Map<String, String> before = oldCode.bodies(inverse.getOrDefault(sub, sub), this::cls), now = newCode.bodies(sub, x -> x);
            String x = before == null ? null : before.get(was[0] + desc(was[1])), y = now == null ? null : now.get(n.name + n.desc);
            if (x != null && y != null && alike(x, y)) {
                alike++;
            }
        }
        return alike * 10 >= subs.size() * 8;
    }

    /** Whether most (four in five) of a body's instructions, at least six of them, are also in another's, counted as multisets. */
    static boolean within(String part, String whole) {
        Map<String, Integer> left = new HashMap<>();
        for (String t : GameCode.instructions(whole)) {
            left.merge(t, 1, Integer::sum);
        }
        String[] mine = GameCode.instructions(part);
        int common = 0;
        for (String t : mine) {
            Integer n = left.get(t);
            if (n != null && n > 0) {
                common++;
                left.put(t, n - 1);
            }
        }
        return mine.length >= 6 && common * 5 >= mine.length * 4;
    }

    /** The share of the longer of two bodies' instructions the other has too, counted as multisets. */
    static double likeness(String x, String y) {
        Map<String, Integer> left = new HashMap<>();
        String[] a = GameCode.instructions(x), b = GameCode.instructions(y);
        for (String t : a) {
            left.merge(t, 1, Integer::sum);
        }
        int common = 0;
        for (String t : b) {
            Integer n = left.get(t);
            if (n != null && n > 0) {
                common++;
                left.put(t, n - 1);
            }
        }
        return Math.max(a.length, b.length) == 0 ? 0 : (double) common / Math.max(a.length, b.length);
    }

    /** At least half of two bodies' instructions (as GameCode.bodies writes them) in common, counted as multisets. */
    static boolean alike(String x, String y) {
        Map<String, Integer> left = new HashMap<>();
        String[] a = GameCode.instructions(x), b = GameCode.instructions(y);
        for (String t : a) {
            left.merge(t, 1, Integer::sum);
        }
        int common = 0;
        for (String t : b) {
            Integer n = left.get(t);
            if (n != null && n > 0) {
                common++;
                left.put(t, n - 1);
            }
        }
        return common * 2 >= Math.max(a.length, b.length);
    }

    /**
     * Whether a mod method reaches the game method of its own name and descriptor (super.name(...), or this.name(...) in
     * a helper of the mod's it calls, a lambda included): moved to the game's new method, which the game's final old one
     * calls, it would call itself back. A call that reaches another mod class's method of that name moves with it.
     */
    boolean callsSuper(Mod mod, String owner, GameDiff.RawMeth m) {
        Deque<String[]> todo = new ArrayDeque<>(List.<String[]>of(new String[] { owner, m.name, m.desc }));
        Set<String> seen = new HashSet<>();
        boolean[] reaches = { false };
        while (!todo.isEmpty() && !reaches[0]) {
            String[] at = todo.poll();
            byte[] bytes = mod.bytes.get(at[0]);
            if (bytes == null || !seen.add(at[0] + "." + at[1] + at[2])) {
                continue;
            }
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    return !name.equals(at[1]) || !desc.equals(at[2]) ? null : new MethodVisitor(Opcodes.ASM9) {
                        void call(String o, String n, String d) {
                            Decl decl = o.startsWith("[") ? null : declaringOld(mod, o, 'm', n, d);
                            if (n.equals(m.name) && d.equals(m.desc) && (decl == null || decl.game())) {
                                reaches[0] = true;
                            } else if (decl != null && !decl.game()) {
                                todo.add(new String[] { decl.owner(), n, d });
                            }
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String o, String n, String d, boolean itf) {
                            call(o, n, d);
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
                            for (Object arg : args) {
                                if (arg instanceof Handle h) {
                                    call(h.getOwner(), h.getName(), h.getDesc());
                                }
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return reaches[0];
    }

    /**
     * The game method a mod method overrides took other parameters (moved: its new owner, name and descriptor). The mod
     * class gets a method with the new signature that calls the mod's own with the arguments it had: each found among the
     * new ones by type (same types in another order, some added, one now narrower), or a default where the mod's body
     * never reads it. The result must serve as the new one. Anything else stays an override the game no longer calls.
     */
    boolean bridge(Mod mod, GameDiff.RawCls rc, GameDiff.RawMeth m, String gameOwner, String[] moved, String mapped, List<String> supers) {
        GameDiff.Meth now = null;
        for (String s : supers) {
            GameDiff.Cls holder = Exposure.declaring(b, s, 'm', moved[1], moved[2]);
            if (holder != null) {
                now = holder.methods.get(moved[1] + moved[2]);
                break;
            }
        }
        if (now == null || moved[2].equals(mapped) || (now.flags & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) != 0) {
            return false;
        }
        String signature = moved[1] + moved[2];
        boolean taken = rc.methods.containsKey(moved[1] + oldDesc(moved[2]))
            || mod.bridges.getOrDefault(rc.name, List.of()).stream().anyMatch(br -> (br.name() + br.desc()).equals(signature))
            || mod.renamedDecl.entrySet().stream().anyMatch(e -> e.getKey().startsWith(rc.name + ".") && (e.getValue()[0] + e.getValue()[1]).equals(signature));
        if (taken) {
            return false; // the class already has a method with the new signature
        }
        Type result = Type.getReturnType(mapped), wanted = Type.getReturnType(moved[2]);
        if (!result.equals(wanted) && (wanted.getSort() == Type.VOID || !usable(result, wanted))) {
            return false;
        }
        Type[] was = Type.getArgumentTypes(mapped), params = Type.getArgumentTypes(moved[2]);
        boolean[] unread = unread(mod, rc.name, m.name, m.desc, (m.flags & Opcodes.ACC_STATIC) != 0, was.length);
        int[] from = convert(params, was, unread);
        int access = (rc.flags & Opcodes.ACC_INTERFACE) != 0 ? Opcodes.ACC_PUBLIC : m.flags & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED);
        if (from != null) {
            mod.bridges.computeIfAbsent(rc.name, k -> new ArrayList<>()).add(new Bridge(moved[1], moved[2], m.name, m.desc, from, access | Opcodes.ACC_SYNTHETIC, null));
            return true;
        }
        // Parameters that changed type: each old one from a new one, through what the game's own overrides use for it.
        if ((rc.flags & Opcodes.ACC_INTERFACE) != 0 || (m.flags & Opcodes.ACC_ABSTRACT) != 0 || mod.bytes.get(rc.name) == null
            || (new ClassReader(mod.bytes.get(rc.name)).readShort(6) & 0xFFFF) < Opcodes.V1_6) {
            return false; // a moved body needs stack map frames to carry over
        }
        int[] paired = pair(params, was);
        String[][] via = retype.conversions(gameOwner, m.name, m.desc, moved, paired);
        why(rc.name + "." + m.name + m.desc, "B moved=" + Arrays.toString(moved) + " paired=" + Arrays.toString(paired) + " via=" + (via == null ? null : Arrays.deepToString(via))
            + " unread=" + Arrays.toString(unread));
        from = new int[was.length];
        String[][] steps = new String[was.length][];
        for (int i = 0; i < was.length; i++) {
            if (paired[i] >= 0) {
                from[i] = paired[i];
            } else if (via != null && via[i] != null) {
                from[i] = Integer.parseInt(via[i][0]);
                steps[i] = Arrays.copyOfRange(via[i], 1, via[i].length);
            } else if (unread != null && unread[i] || onlyPassed(mod, rc.name, m, slots(was)[i])) {
                from[i] = -1;
            } else {
                return false;
            }
        }
        mod.bridges.computeIfAbsent(rc.name, k -> new ArrayList<>()).add(new Bridge(moved[1], moved[2], m.name, m.desc, from, m.flags & ~Opcodes.ACC_ABSTRACT, steps));
        return true;
    }

    /**
     * A mod constructor whose super(...) or this(...) goes to a constructor that now takes another type in a place, one
     * the old value is read from (1.17's EntityRenderer(EntityRenderDispatcher) became EntityRenderer(EntityRendererProvider
     * .Context), whose getEntityRenderDispatcher() is the dispatcher): it moves whole to take the new type in place of its
     * own parameter of the old one, as the game's own subclasses' constructors did, the old value worked out first as the
     * game's code reads it (Retype). Its super call is then given the new value (Sites.given), and so is each call that
     * makes the class where that value is at hand (a moved lambda or override); a call that has none keeps the old
     * constructor, whose super call is reported. A mod class's constructor follows its mod superclass's that moved. True
     * when a constructor moved.
     */
    boolean constructor(Mod mod, GameDiff.RawCls rc, GameDiff.RawMeth m) {
        byte[] bytes = mod.bytes.get(rc.name);
        if (bytes == null || (new ClassReader(bytes).readShort(6) & 0xFFFF) < Opcodes.V1_6
            || mod.bridges.getOrDefault(rc.name, List.of()).stream().anyMatch(br -> br.target().equals(m.name) && br.targetDesc().equals(m.desc))) {
            return false;
        }
        String[] call = delegate(bytes, m.desc);
        if (call == null) {
            return false;
        }
        String owner = call[0], desc = call[1];
        Type[] was = Type.getArgumentTypes(desc(desc));
        String newCall;
        String[][] via = new String[was.length][];
        if (mod.classes.containsKey(owner)) {
            Bridge parent = mod.bridges.getOrDefault(owner, List.of()).stream()
                .filter(br -> br.via() != null && br.target().equals("<init>") && br.targetDesc().equals(desc)).findFirst().orElse(null);
            if (parent == null) {
                return false;
            }
            newCall = parent.desc();
            for (int i = 0; i < was.length; i++) {
                if (parent.via()[i] != null) {
                    String[] steps = new String[parent.via()[i].length + 1];
                    steps[0] = String.valueOf(parent.from()[i]);
                    System.arraycopy(parent.via()[i], 0, steps, 1, parent.via()[i].length);
                    via[i] = steps;
                }
            }
        } else {
            newCall = retypedConstructor(owner, desc);
            if (newCall == null) {
                return false;
            }
            Type[] now = Type.getArgumentTypes(newCall);
            for (int i = 0; i < was.length; i++) {
                if (now[i].equals(was[i])) {
                    continue;
                }
                String[] steps = reading(owner, was[i], now[i], 0);
                if (steps == null || !sameShape(owner, was[i], now[i])) {
                    why(rc.name + ".<init>" + m.desc, "C super " + owner + ".<init>" + desc + " -> " + newCall + " reading=" + (steps == null ? null : String.join(" ", steps))
                        + " shape=" + sameShape(owner, was[i], now[i]));
                    return false;
                }
                via[i] = new String[steps.length + 1];
                via[i][0] = String.valueOf(i);
                System.arraycopy(steps, 0, via[i], 1, steps.length);
            }
        }
        // Each place that changed type is filled from the constructor's one parameter of the old type, which becomes the new.
        Type[] own = Type.getArgumentTypes(desc(m.desc)), params = own.clone(), newTypes = Type.getArgumentTypes(newCall);
        int[] from = new int[own.length];
        String[][] steps = new String[own.length][];
        boolean any = false;
        for (int k = 0; k < own.length; k++) {
            from[k] = k;
        }
        for (int i = 0; i < was.length; i++) {
            if (via[i] == null) {
                continue;
            }
            int p = -1;
            for (int k = 0; k < own.length; k++) {
                if (own[k].equals(was[i])) {
                    p = p == -1 ? k : -2;
                }
            }
            if (p < 0 || steps[p] != null) {
                return false;
            }
            params[p] = newTypes[Integer.parseInt(via[i][0])];
            steps[p] = Arrays.copyOfRange(via[i], 1, via[i].length);
            any = true;
        }
        String newDesc = Type.getMethodDescriptor(Type.VOID_TYPE, params);
        if (!any || rc.methods.containsKey("<init>" + oldDesc(newDesc))) {
            return false;
        }
        mod.bridges.computeIfAbsent(rc.name, k -> new ArrayList<>()).add(new Bridge("<init>", newDesc, "<init>", m.desc, from, m.flags, steps));
        mod.change("constructor moved to what its superclass's constructor takes now");
        return true;
    }

    /**
     * The new release's constructor (new names) a game class's old constructor (old names) became when the update only
     * changed the types of some of its parameters: the route map's, else the class's one constructor with as many
     * parameters whose other parameters are the old ones in place. Null when the old one still links, or none or several fit.
     */
    String retypedConstructor(String owner, String desc) {
        GameDiff.Cls holder = b.classes.get(cls(owner));
        Type[] was = Type.getArgumentTypes(desc(desc));
        if (!a.classes.containsKey(owner) || holder == null || holder.methods.containsKey("<init>" + desc(desc))) {
            return null;
        }
        String[] moved = route.method(owner, "<init>", desc);
        if (moved[0].equals(holder.name) && holder.methods.containsKey("<init>" + moved[2])) {
            return Type.getArgumentTypes(moved[2]).length == was.length ? moved[2] : null;
        }
        String found = null;
        for (GameDiff.Meth k : holder.methods.values()) {
            Type[] t = Type.getArgumentTypes(k.desc);
            if (!k.name.equals("<init>") || (k.flags & Opcodes.ACC_PRIVATE) != 0 || t.length != was.length) {
                continue;
            }
            boolean fits = true;
            for (int i = 0; i < t.length && fits; i++) {
                // A type in another place, or a primitive in place of another, is a reshaped constructor, not a retyped one.
                fits = t[i].equals(was[i]) || t[i].getSort() == Type.OBJECT && was[i].getSort() == Type.OBJECT && !Arrays.asList(was).contains(t[i]);
            }
            if (fits) {
                if (found != null) {
                    return null;
                }
                found = k.desc;
            }
        }
        return found;
    }

    /**
     * How the new release reads a value of type old from the value of type now that a game constructor takes in its place
     * (steps as Retype gives them, new names): what the class's own two constructor bodies show, else its game
     * superclass's, which it hands the value to (MobRenderer passes the context on; EntityRenderer reads
     * context.getEntityRenderDispatcher() where it stored the dispatcher).
     */
    String[] reading(String owner, Type old, Type now, int depth) {
        GameDiff.Cls c = a.classes.get(owner);
        if (c == null || depth > 6) {
            return null;
        }
        for (GameDiff.Meth k : c.methods.values()) {
            Type[] was = Type.getArgumentTypes(desc(k.desc));
            if (!k.name.equals("<init>") || Arrays.stream(was).filter(old::equals).count() != 1) {
                continue;
            }
            String newDesc = retypedConstructor(owner, k.desc);
            if (newDesc == null) {
                continue;
            }
            Type[] t = Type.getArgumentTypes(newDesc);
            int i = Arrays.asList(was).indexOf(old);
            if (!t[i].equals(now)) {
                continue;
            }
            String[][] found = retype.conversions(owner, "<init>", k.desc, new String[] { cls(owner), "<init>", newDesc }, pair(t, was));
            if (found != null && found[i] != null && Integer.parseInt(found[i][0]) == i) {
                return Arrays.copyOfRange(found[i], 1, found[i].length);
            }
        }
        return c.sup == null ? null : reading(c.sup, old, now, depth + 1);
    }

    /**
     * Whether the game's own subclasses of owner moved their constructors the same way: most of those that took the old
     * type take the new one now (1.17's renderers, each EntityRenderDispatcher parameter an EntityRendererProvider.Context).
     */
    boolean sameShape(String owner, Type old, Type now) {
        int yes = 0, total = 0;
        for (GameDiff.Cls c : a.classes.values()) {
            if (!owner.equals(c.sup) || c.methods.values().stream().noneMatch(k -> takes(k, old, true))) {
                continue;
            }
            total++;
            GameDiff.Cls after = b.classes.get(cls(c.name));
            if (after != null && after.methods.values().stream().anyMatch(k -> takes(k, now, false)) && after.methods.values().stream().noneMatch(k -> takes(k, old, false))) {
                yes++;
            }
        }
        return yes >= 2 && yes * 10 >= total * 8;
    }

    /** Whether k is a constructor with a parameter of type t (new names; k's in old names when old). */
    boolean takes(GameDiff.Meth k, Type t, boolean old) {
        return k.name.equals("<init>") && Arrays.stream(Type.getArgumentTypes(old ? desc(k.desc) : k.desc)).anyMatch(t::equals);
    }

    /** The class and descriptor of the super(...) or this(...) call a constructor's body makes, or null. */
    static String[] delegate(byte[] bytes, String desc) {
        String[][] out = new String[1][];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            String self, sup;

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                self = name;
                sup = superName;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String d, String signature, String[] exceptions) {
                return !name.equals("<init>") || !d.equals(desc) ? null : new MethodVisitor(Opcodes.ASM9) {
                    int news;

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        news += opcode == Opcodes.NEW ? 1 : 0;
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String d, boolean itf) {
                        if (opcode != Opcodes.INVOKESPECIAL || !name.equals("<init>") || out[0] != null) {
                            return;
                        }
                        if (news > 0) {
                            news--;
                        } else if (owner.equals(sup) || owner.equals(self)) {
                            out[0] = new String[] { owner, d };
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return out[0];
    }

    /**
     * Whether a mod method only ever passes the parameter in slot to game methods whose parameters changed: then it needs
     * no value of its own in a moved body, because each such call is given what the game passes now instead (Sites.given;
     * keyPressed(key, scan, mods) only passing scan and mods on to super.keyPressed). Read as GameDiff reads bodies.
     */
    boolean onlyPassed(Mod mod, String owner, GameDiff.RawMeth m, int slot) {
        byte[] bytes = mod.bytes.get(owner);
        GameDiff.Origin o = new GameDiff.Origin(bytes, Map.of(), null, new GameDiff.Names(null), new HashMap<>());
        for (MethodModel mm : ClassFile.of().parse(bytes).methods()) {
            if (mm.methodName().stringValue().equals(m.name) && mm.methodType().stringValue().equals(m.desc)) {
                GameDiff.Body body = new GameDiff.Decoder(o, owner).run(mm);
                if (body == null) {
                    return false;
                }
                int[] seen = new int[1];
                for (GameDiff.Node s : body.statements) {
                    if (!passedOnly(mod, s, slot, seen)) {
                        return false;
                    }
                }
                return seen[0] > 0;
            }
        }
        return false;
    }

    boolean passedOnly(Mod mod, GameDiff.Node n, int slot, int[] seen) {
        for (GameDiff.Node k : n.kids) {
            if (k.op == 'p' && Integer.valueOf(slot).equals(k.value)) {
                seen[0]++;
                if (!(n.op == 'v' || n.op == 's') || n.ref == null || n.ref.kind() != 'm') {
                    return false;
                }
                Decl decl = n.ref.owner().startsWith("[") ? null : declaringOld(mod, n.ref.owner(), 'm', n.ref.name(), n.ref.desc());
                if (decl == null || !decl.game() || route.method(decl.owner(), n.ref.name(), n.ref.desc())[2].equals(desc(n.ref.desc()))) {
                    return false;
                }
            } else if (!passedOnly(mod, k, slot, seen)) {
                return false;
            }
        }
        return true;
    }

    /**
     * For each parameter of to, the parameter of from with the same type: a type found once on each side wherever it
     * stands, or in order when both sides have it equally often; -2 where there is none.
     */
    static int[] pair(Type[] from, Type[] to) {
        int[] out = new int[to.length];
        for (int j = 0; j < to.length; j++) {
            out[j] = -2;
            List<Integer> same = new ArrayList<>();
            for (int i = 0; i < from.length; i++) {
                if (from[i].equals(to[j])) {
                    same.add(i);
                }
            }
            long wanted = Arrays.stream(to).filter(to[j]::equals).count();
            if (!same.isEmpty() && same.size() == wanted) {
                int nth = (int) Arrays.stream(to, 0, j).filter(to[j]::equals).count();
                out[j] = same.get(nth);
            }
        }
        return out;
    }

    /**
     * For each parameter of to (new names), the parameter of from that supplies it, or -1 where unread says to's method
     * never reads it; null when one cannot be supplied. A type found once on each side is taken wherever it stands; a
     * type that appears several times pairs up in order only when both sides have it as often. A parameter with no
     * match takes the one parameter of from whose type now serves as it (a subtype).
     */
    int[] convert(Type[] from, Type[] to, boolean[] unread) {
        int[] out = pair(from, to);
        boolean[] taken = new boolean[from.length];
        for (int j = 0; j < to.length; j++) {
            if (out[j] >= 0) {
                taken[out[j]] = true;
            }
        }
        for (int j = 0; j < to.length; j++) {
            if (out[j] != -2) {
                continue;
            }
            List<Integer> fits = new ArrayList<>();
            for (int i = 0; i < from.length; i++) {
                if (!taken[i] && usable(from[i], to[j])) {
                    fits.add(i);
                }
            }
            if (fits.size() == 1) {
                out[j] = fits.get(0);
                taken[fits.get(0)] = true;
            } else if (unread != null && unread[j]) {
                out[j] = -1;
            } else {
                return null;
            }
        }
        return out;
    }

    /** Which of a mod method's first n parameters its body never reads; null when it has no body here. */
    boolean[] unread(Mod mod, String owner, String name, String desc, boolean isStatic, int n) {
        byte[] bytes = mod.bytes.get(owner);
        if (bytes == null) {
            return null;
        }
        Type[] params = Type.getArgumentTypes(desc);
        Map<Integer, Integer> slots = new HashMap<>(); // slot -> parameter
        int slot = isStatic ? 0 : 1;
        for (int i = 0; i < n && i < params.length; i++) {
            slots.put(slot, i);
            slot += params[i].getSize();
        }
        boolean[] out = new boolean[n];
        boolean[] seen = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String mname, String mdesc, String signature, String[] exceptions) {
                if (!mname.equals(name) || !mdesc.equals(desc) || (access & Opcodes.ACC_ABSTRACT) != 0) {
                    return null;
                }
                seen[0] = true;
                Arrays.fill(out, true);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitVarInsn(int opcode, int var) {
                        if (opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD || opcode == Opcodes.RET) {
                            read(var);
                        }
                    }

                    @Override
                    public void visitIincInsn(int var, int increment) {
                        read(var);
                    }

                    void read(int var) {
                        Integer p = slots.get(var);
                        if (p != null) {
                            out[p] = false;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        return seen[0] ? out : null;
    }

    /**
     * A concrete mod class must implement every abstract method of its game supertypes in the new release (1.21.2 added
     * Recipe.placementInfo()); one it does not is reported, never filled in.
     */
    void abstracts(Mod mod, GameDiff.RawCls rc) {
        finals(mod, rc);
        if ((rc.flags & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) != 0) {
            return;
        }
        Set<String> written = new HashSet<>();
        Deque<String> game = new ArrayDeque<>();
        for (GameDiff.RawCls c = rc; c != null; c = c.sup == null ? null : mod.classes.get(c.sup)) {
            for (GameDiff.RawMeth m : c.methods.values()) {
                String[] renamed = mod.renamedDecl.get(c.name + "." + m.name + m.desc);
                written.add(renamed == null ? m.name + desc(m.desc) : renamed[0] + renamed[1]);
            }
            mod.bridges.getOrDefault(c.name, List.of()).forEach(br -> written.add(br.name() + br.desc()));
            mod.copies.getOrDefault(c.name, List.of()).stream().filter(cp -> cp.as().equals(cp.name())).forEach(cp -> written.add(cp.name() + cp.desc()));
            if (c.sup != null && !mod.classes.containsKey(c.sup)) {
                game.add(cls(c.sup));
            }
            for (String i : c.ifaces) {
                game.add(mod.classes.containsKey(i) ? i : cls(i));
            }
        }
        // A mod interface's own default methods count as written; its game parents are searched too.
        Set<String> seen = new HashSet<>();
        List<GameDiff.Cls> supers = new ArrayList<>();
        while (!game.isEmpty()) {
            String c = game.poll();
            if (!seen.add(c)) {
                continue;
            }
            GameDiff.RawCls own = mod.classes.get(c);
            if (own != null) {
                for (GameDiff.RawMeth m : own.methods.values()) {
                    if ((m.flags & Opcodes.ACC_ABSTRACT) == 0) {
                        String[] renamed = mod.renamedDecl.get(own.name + "." + m.name + m.desc);
                        written.add(renamed == null ? m.name + desc(m.desc) : renamed[0] + renamed[1]);
                    }
                }
                mod.bridges.getOrDefault(own.name, List.of()).forEach(br -> written.add(br.name() + br.desc()));
                own.ifaces.forEach(i -> game.add(mod.classes.containsKey(i) ? i : cls(i)));
                continue;
            }
            GameDiff.Cls k = b.classes.get(c);
            if (k == null) {
                continue;
            }
            supers.add(k);
            if (k.sup != null) {
                game.add(k.sup);
            }
            game.addAll(k.ifaces);
        }
        for (GameDiff.Cls k : supers) {
            for (GameDiff.Meth m : k.methods.values()) {
                if (m.name.startsWith("<")) {
                    continue;
                }
                written.add((m.flags & Opcodes.ACC_ABSTRACT) == 0 ? m.name + m.desc : "");
            }
        }
        Set<String> before = unwrittenBefore(mod, rc);
        for (GameDiff.Cls k : supers) {
            for (GameDiff.Meth m : k.methods.values()) {
                if ((m.flags & Opcodes.ACC_ABSTRACT) != 0 && !written.contains(m.name + m.desc) && !OBJECT.contains(m.name + m.desc) && !generic(supers, m)
                    && !alreadyAbstract(k, m) && !before.contains(m.name + oldDesc(m.desc))) {
                    Copy fill = gameDefault(mod, rc, k, m, supers);
                    if (fill != null) {
                        mod.copies.computeIfAbsent(rc.name, x -> new ArrayList<>()).add(fill);
                        written.add(m.name + m.desc);
                        mod.change(fill.source() == null ? "abstract method the update added written empty, as a game subclass writes it: the old release never asked the class" : "abstract method the update added written as the game's own subclasses write it");
                        continue;
                    }
                    mod.unlinked.add("abstract method not written: " + Exposure.dotted(k.name) + "." + m.name + m.desc + " (in " + Exposure.dotted(rc.name) + ")");
                }
            }
        }
    }

    /**
     * The body a mod class gets for an abstract method n of game class k that the update added and the class does not
     * write: the one most of the game's own subclasses of k write for it, of those whose body a mod class could carry
     * (1.17's widgets narrate through AbstractWidget's defaultButtonNarrationText, 1.19.4's key list entries refresh
     * nothing). Where the game's subclasses moved an old method into n instead, a class that relied on the old method's
     * default gets what it became (defaultBody). None where n takes over work: where a method the class inherited in the
     * old release now hands its work to n on the object (1.17's LevelHeightAccessor.getMaxBuildHeight asks getHeight),
     * or where the class writes a method the game no longer calls, whose code n may be meant to hold.
     */
    Copy gameDefault(Mod mod, GameDiff.RawCls rc, GameDiff.Cls k, GameDiff.Meth n, List<GameDiff.Cls> supers) {
        GameCode code = code(b);
        // Only from the game's own code: a loader's or a library's type, or one whose code is not there to read, gets nothing.
        if (code == null || k.loader || k.library || code.bytes.apply(code.toJar.getOrDefault(k.name, k.name)) == null) {
            return null;
        }
        for (GameDiff.RawCls c = rc; c != null; c = c.sup == null ? null : mod.classes.get(c.sup)) {
            String in = " (in " + Exposure.dotted(c.name) + ")";
            if (mod.silent.stream().anyMatch(s -> s.endsWith(in))) {
                why(rc.name + "." + n.name + n.desc, "D writes a method the game no longer calls");
                return null;
            }
        }
        // A method of that name the old class had makes n another form of it (1.21.11's ItemModelOutput.accept took a
        // third argument), which the class may write in its old form: a bridge's work, not a default's.
        GameDiff.Cls before = a.classes.get(inverse.getOrDefault(k.name, k.name));
        if (before != null && before.methods.values().stream().anyMatch(om -> om.name.equals(n.name))) {
            why(rc.name + "." + n.name + n.desc, "D the old release had it in another form");
            return null;
        }
        int access = n.flags & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED);
        String[] was = takeover(k.name, n);
        if (was != null) {
            for (GameDiff.RawCls c = rc; c != null; c = c.sup == null ? null : mod.classes.get(c.sup)) {
                if (c.methods.containsKey(was[0] + was[1])) {
                    return null;
                }
            }
            Copy old = defaultBody(k.name, was[0], was[1], n.name, n.desc, rc.name);
            if (old == null) {
                why(rc.name + "." + n.name + n.desc, "D the game moved " + was[0] + was[1] + " into it, and its default became no one body");
                return null;
            }
            return new Copy(old.source(), n.name, n.desc, n.name, access);
        }
        for (GameDiff.Cls s : supers) {
            if (s.loader || s.library) {
                continue;
            }
            for (String d : Objects.requireNonNullElse(handsTo(code, s.name, n.name, n.desc), Set.<String>of())) {
                Decl had = declaringOld(mod, rc.name, 'm', d.substring(0, d.indexOf('(')), oldDesc(d.substring(d.indexOf('('))));
                if (had != null && had.game()) {
                    why(rc.name + "." + n.name + n.desc, "D " + s.name + "." + d + ", which the class had, hands its work to it");
                    return null;
                }
            }
        }
        String took = tookWork(k.name, n);
        if (took != null) {
            why(rc.name + "." + n.name + n.desc, "D " + took + " calls it where its old code did more");
            return null;
        }
        List<String> parents = gameParents(mod, rc).stream().filter(p -> subtype(b, p, k.name)).toList();
        Map<String, Integer> count = new HashMap<>();
        Map<String, String> source = new HashMap<>();
        for (GameDiff.Cls w : declaring(b, n.name + n.desc)) {
            GameDiff.Meth own = w.methods.get(n.name + n.desc);
            if (own == null || (own.flags & Opcodes.ACC_ABSTRACT) != 0 || w.name.equals(k.name) || w.loader || !subtype(b, w.name, k.name)) {
                continue;
            }
            Map<String, String> bodies = code.bodies(w.name, x -> x);
            String text = bodies == null ? null : bodies.get(n.name + n.desc);
            if (text != null && parents.stream().anyMatch(p -> copyable(code, w.name, n.name, n.desc, p))) {
                String same = text.replace(w.name + ".", "this.");
                count.merge(same, 1, Integer::sum);
                source.putIfAbsent(same, w.name);
            }
        }
        List<Map.Entry<String, Integer>> best = count.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(2).toList();
        if (best.isEmpty() || best.get(0).getValue() < 2 || best.size() > 1 && best.get(1).getValue().equals(best.get(0).getValue())) {
            // Nothing the class had hands its work to n and the game did not move anything into it, and one of the game's
            // own subclasses does nothing there (1.19.4's KeyBindsList.CategoryEntry.refreshEntry): the old release never
            // asked the class this, and doing nothing is what it did there before. Without that, doing nothing may drop
            // what the game now hands the class (a SubmitNodeCollector's submit calls).
            if (count.containsKey(EMPTY)) {
                if (WHY) {
                    System.err.println("WHY D0 " + count.values() + " | " + rc.name + "." + n.name + n.desc);
                }
                return new Copy(null, n.name, n.desc, n.name, access);
            }
            why(rc.name + "." + n.name + n.desc, "D no body most of the game's subclasses a mod class could carry write: " + count.values());
            return null;
        }
        if (WHY) {
            System.err.println("WHY D+ " + source.get(best.get(0).getKey()) + " " + count.values() + " | " + rc.name + "." + n.name + n.desc);
        }
        return new Copy(source.get(best.get(0).getKey()), n.name, n.desc, n.name, access);
    }

    /** A method body that only returns, as GameCode.bodies writes it. */
    static final String EMPTY = Opcodes.RETURN + "\n";

    final Map<String, Optional<String>> tookWork = new HashMap<>();

    /**
     * A game method (class.name+desc, new names) that calls k's method n, was in the old release too (as it is, or
     * under the name the route map follows), and no longer makes a call its old code made: n may do that work now (1.17's
     * ProgramManager linked a shader's two programs itself; now it calls Shader.attachToProgram). Null when every
     * caller is new, or calls n beside all it called before.
     */
    String tookWork(String k, GameDiff.Meth n) {
        return tookWork.computeIfAbsent(k + "." + n.name + n.desc, key -> {
            for (GameDiff.Cls c : b.classes.values()) {
                for (GameDiff.Meth d : c.methods.values()) {
                    boolean calls = false;
                    Set<String> now = new HashSet<>();
                    for (GameDiff.Ref r : d.refs) {
                        if (r.kind() == 'm') {
                            now.add(r.name() + r.desc());
                            calls |= r.name().equals(n.name) && r.desc().equals(n.desc) && (subtype(b, r.owner(), k) || subtype(b, k, r.owner()));
                        }
                    }
                    GameDiff.Meth was = calls ? kept(d) : null;
                    if (was == null) {
                        continue;
                    }
                    for (GameDiff.Ref r : was.refs) {
                        if (r.kind() == 'm' && !r.name().startsWith("<")) {
                            String[] to = route.method(r.owner(), r.name(), r.desc());
                            if (!now.contains(to[1] + to[2])) {
                                return Optional.of(c.name + "." + d.name + d.desc);
                            }
                        }
                    }
                }
            }
            return Optional.<String>empty();
        }).orElse(null);
    }

    /** The old release's method a game method was (the same name and parameters, or one the route map follows to it); null for a new one. */
    GameDiff.Meth kept(GameDiff.Meth d) {
        GameDiff.Cls old = a.classes.get(inverse.getOrDefault(d.owner.name, d.owner.name));
        if (old == null) {
            return null;
        }
        GameDiff.Meth same = old.methods.get(d.name + oldDesc(d.desc));
        if (same != null) {
            return same;
        }
        for (GameDiff.Meth om : old.methods.values()) {
            String[] to = route.method(old.name, om.name, om.desc);
            if (to[0].equals(d.owner.name) && to[1].equals(d.name) && to[2].equals(d.desc)) {
                return om;
            }
        }
        return null;
    }

    /**
     * The methods (new names) of a game class whose code calls name+desc on its own object; null when its code is not
     * the game's (a loader class).
     */
    Set<String> handsTo(GameCode code, String cls, String name, String desc) {
        String jar = code.toJar.getOrDefault(cls, cls);
        byte[] bytes = code.bytes.apply(jar);
        if (bytes == null) {
            return null;
        }
        Set<String> out = new HashSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                if (n.startsWith("<") || (access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) {
                    return null;
                }
                String key = code.names.method(jar, n, d) + code.names.desc(d);
                Object self = new Object();
                return new org.objectweb.asm.commons.AnalyzerAdapter(Opcodes.ASM9, jar, access, n, d, null) {
                    @Override
                    public void visitVarInsn(int opcode, int var) {
                        super.visitVarInsn(opcode, var);
                        if (opcode == Opcodes.ALOAD && var == 0 && stack != null) {
                            stack.set(stack.size() - 1, self);
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String o, String mn, String md, boolean itf) {
                        int depth = Arrays.stream(Type.getArgumentTypes(md)).mapToInt(Type::getSize).sum();
                        if (opcode != Opcodes.INVOKESTATIC && stack != null && stack.size() > depth && stack.get(stack.size() - 1 - depth) == self) {
                            String decl = o.startsWith("[") ? null : code.declarer(o, mn, md, false);
                            if (((decl == null ? mn : code.names.method(decl, mn, md)) + code.names.desc(md)).equals(name + desc)) {
                                out.add(key);
                            }
                        }
                        super.visitMethodInsn(opcode, o, mn, md, itf);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return out;
    }

    /**
     * A method of a mod class still overriding one the new release made final (1.21.11's AbstractButton.renderWidget) is
     * reported: the JVM refuses the class. One the old release had final too was overridden all the same (a mod's access
     * widener or the loader's transformer opens it), and that stays as it was.
     */
    void finals(Mod mod, GameDiff.RawCls rc) {
        List<String> supers = gameParents(mod, rc);
        List<String> was = new ArrayList<>();
        for (GameDiff.RawCls c = rc; c != null; c = c.sup == null ? null : mod.classes.get(c.sup)) {
            if (c.sup != null && !mod.classes.containsKey(c.sup)) {
                was.add(c.sup);
            }
            c.ifaces.stream().filter(i -> !mod.classes.containsKey(i)).forEach(was::add);
        }
        for (GameDiff.RawMeth m : rc.methods.values()) {
            if (m.name.startsWith("<") || (m.flags & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0 || mod.renamedDecl.containsKey(rc.name + "." + m.name + m.desc)) {
                continue;
            }
            String mapped = desc(m.desc);
            boolean finalBefore = was.stream().map(s -> Exposure.declaring(a, s, 'm', m.name, m.desc))
                .anyMatch(k -> k != null && (k.methods.get(m.name + m.desc).flags & Opcodes.ACC_FINAL) != 0);
            for (String s : finalBefore ? List.<String>of() : supers) {
                GameDiff.Cls k = Exposure.declaring(b, s, 'm', m.name, mapped);
                GameDiff.Meth over = k == null ? null : k.methods.get(m.name + mapped);
                if (over != null && (over.flags & Opcodes.ACC_FINAL) != 0 && (over.flags & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0) {
                    mod.unlinked.add("final method overridden: " + Exposure.dotted(k.name) + "." + m.name + mapped + " (in " + Exposure.dotted(rc.name) + ")");
                    break;
                }
            }
        }
    }

    /**
     * The abstract methods (name + old descriptor) a mod class already left unwritten against the old release: its game
     * supertypes there had them abstract and neither the mod nor they wrote one. The class loaded all the same, so the
     * loader supplies them (NeoForge patches BakedModel.getTransforms() into a default the game's own jar does not have);
     * the port did not take them away.
     */
    Set<String> unwrittenBefore(Mod mod, GameDiff.RawCls rc) {
        Set<String> written = new HashSet<>(), abstracts = new HashSet<>(), seen = new HashSet<>(), unread = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(rc.name));
        while (!todo.isEmpty()) {
            String c = todo.poll();
            if (!seen.add(c)) {
                continue;
            }
            GameDiff.RawCls own = mod.classes.get(c);
            if (own != null) {
                for (GameDiff.RawMeth m : own.methods.values()) {
                    ((m.flags & Opcodes.ACC_ABSTRACT) == 0 ? written : abstracts).add(m.name + m.desc);
                    if (UNMAPPED.matcher(m.name).matches()) {
                        unread.add(m.desc);
                    }
                }
                if (own.sup != null) {
                    todo.add(own.sup);
                }
                todo.addAll(own.ifaces);
                continue;
            }
            GameDiff.Cls k = a.classes.get(c);
            if (k == null) {
                continue;
            }
            for (GameDiff.Meth m : k.methods.values()) {
                ((m.flags & Opcodes.ACC_ABSTRACT) == 0 ? written : abstracts).add(m.name + m.desc);
            }
            todo.addAll(GameDiff.supertypes(k));
        }
        abstracts.removeAll(written);
        // A method the class writes under an id its names were never read through (m_6055_ for getName, in a jar built
        // for another release's mappings) is that one, unread: the class did not load before either, so nothing is excused.
        abstracts.removeIf(k -> unread.contains(k.substring(k.indexOf('('))));
        return abstracts;
    }

    static final Pattern UNMAPPED = Pattern.compile("(?:m_\\d+_|method_\\d+|func_\\d+_[a-z]?)");

    static final Set<String> OBJECT = Set.of("equals(Ljava/lang/Object;)Z", "hashCode()I", "toString()Ljava/lang/String;");

    /**
     * Whether a game class among supers implements an abstract method through a generic parameter: a concrete method of
     * that name whose parameters are narrower (ShapedRecipe.matches(CraftingInput, Level) for Recipe.matches(RecipeInput,
     * Level)). javac wrote the bridge with the erased signature into that class, and the snapshot leaves bridges out.
     */
    boolean generic(List<GameDiff.Cls> supers, GameDiff.Meth abs) {
        Type[] want = Type.getArgumentTypes(abs.desc);
        for (GameDiff.Cls k : supers) {
            for (GameDiff.Meth m : k.methods.values()) {
                if (m.name.equals(abs.name) && (m.flags & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC)) == 0 && narrows(want, m.desc, abs.desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Parameters each the same as or narrower than want, and a result that serves as abs's. */
    boolean narrows(Type[] want, String desc, String abs) {
        Type[] got = Type.getArgumentTypes(desc);
        if (got.length != want.length || !usable(Type.getReturnType(desc), Type.getReturnType(abs))) {
            return false;
        }
        for (int i = 0; i < got.length; i++) {
            if (!usable(got[i], want[i])) {
                return false;
            }
        }
        return true;
    }

    /** An @Accessor or @Invoker names a target member (its value, or the name Mixin infers); a renamed one is written into the value. */
    void accessor(Mod mod, String mixin, List<String> targets, GameDiff.RawMeth m, String[] spec) {
        boolean invoker = spec[0].equals("Invoker");
        String inferred = m.name.replaceFirst(invoker ? "^(call|invoke|new|create)" : "^(get|is|set)", "");
        if (!inferred.isEmpty()) {
            inferred = Character.toLowerCase(inferred.charAt(0)) + inferred.substring(1);
        }
        String name = spec[1] != null ? spec[1] : inferred;
        if (name.equals("<init>") || invoker && spec[1] == null && m.name.matches("^(new|create).*")) {
            return; // a constructor invoker: its descriptor follows the class, which renames already cover
        }
        String key = mixin + "." + m.name + m.desc;
        Type[] args = Type.getArgumentTypes(m.desc);
        String fieldDesc = Type.getReturnType(m.desc) == Type.VOID_TYPE ? (args.length > 0 ? args[args.length - 1].getDescriptor() : null) : Type.getReturnType(m.desc).getDescriptor();
        for (String t : targets) {
            if (!a.classes.containsKey(t)) {
                continue;
            }
            if (!invoker) {
                List<String> names = spec[1] != null ? List.of(name) : List.of(name, name.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT));
                for (String n : names) {
                    GameDiff.Cls decl = Exposure.declaring(a, t, 'f', n, null);
                    if (decl == null) {
                        continue;
                    }
                    String[] moved = route.field(decl.name, n);
                    GameDiff.Cls now = Exposure.declaring(b, cls(t), 'f', moved[1], null);
                    if (now == null || fieldDesc != null && !now.fields.get(moved[1]).desc.equals(desc(fieldDesc))) {
                        mod.unlinked.add("mixin accessor target gone: " + Exposure.dotted(t) + "." + n + " (in " + Exposure.dotted(mixin) + ")");
                    } else if (!moved[1].equals(n)) {
                        mod.accessorTargets.put(key, moved[1]);
                        mod.change("mixin accessor renamed");
                    }
                    return;
                }
                continue;
            }
            GameDiff.Cls decl = Exposure.declaring(a, t, 'm', name, m.desc);
            if (decl == null) {
                continue;
            }
            String[] moved = route.method(decl.name, name, m.desc);
            String mapped = desc(m.desc);
            if (Exposure.declaring(b, cls(t), 'm', name, mapped) != null) {
                return;
            }
            if (moved[2].equals(mapped) && Exposure.declaring(b, cls(t), 'm', moved[1], moved[2]) != null) {
                mod.accessorTargets.put(key, moved[1]);
                mod.change("mixin invoker renamed");
            } else {
                mod.unlinked.add("mixin invoker target gone: " + Exposure.dotted(t) + "." + name + m.desc + " (in " + Exposure.dotted(mixin) + ")");
            }
            return;
        }
    }

    // ---- Rewriting one class.

    /**
     * A helper of the mod (class.name+desc, old names) moved whole to take its parameter at want[0] as the new type
     * want[1] (new names), the old value worked out first through the steps want[2], as the moved body that calls it
     * worked it out: a Bridge with via, like an override's. False when it cannot move (no stack map frames).
     */
    boolean twin(Mod mod, String key, Object[] want) {
        String owner = key.substring(0, key.indexOf('.')), member = key.substring(key.indexOf('.') + 1);
        GameDiff.RawCls rc = mod.classes.get(owner);
        GameDiff.RawMeth m = rc == null ? null : rc.methods.get(member);
        byte[] bytes = mod.bytes.get(owner);
        if (m == null || bytes == null || (new ClassReader(bytes).readShort(6) & 0xFFFF) < Opcodes.V1_6) {
            return false;
        }
        int k = (Integer) want[0];
        Type[] was = Type.getArgumentTypes(m.desc), now = new Type[was.length];
        int[] from = new int[was.length];
        String[][] via = new String[was.length][];
        for (int i = 0; i < was.length; i++) {
            now[i] = i == k ? Type.getType((String) want[1]) : Type.getType(desc(was[i].getDescriptor()));
            from[i] = i;
        }
        via[k] = (String[]) want[2];
        String newDesc = Type.getMethodDescriptor(Type.getType(desc(Type.getReturnType(m.desc).getDescriptor())), now);
        if (rc.methods.containsKey(m.name + oldDesc(newDesc))) {
            return false;
        }
        mod.bridges.computeIfAbsent(owner, x -> new ArrayList<>()).add(new Bridge(m.name, newDesc, m.name, m.desc, from, m.flags, via));
        mod.change("helper moved to what the game passes now");
        return true;
    }

    /**
     * rewrite, and when a body moved to a game method's new parameter types cannot be written (code the stack tracking
     * cannot follow), the class again without moving it: that override is reported, so the mod is not said to link.
     */
    byte[] rewriteOrReport(Mod mod, byte[] bytes) {
        try {
            return rewrite(mod, bytes);
        } catch (RuntimeException failed) {
            List<Bridge> own = mod.bridges.get(new ClassReader(bytes).getClassName());
            if (own == null || own.stream().noneMatch(br -> br.via() != null)) {
                throw failed;
            }
            for (Bridge br : own) {
                if (br.via() != null) {
                    mod.unlinked.add("override not moved to the game's new parameter types: " + Exposure.dotted(new ClassReader(bytes).getClassName()) + "." + br.target() + br.targetDesc() + " (" + failed.getClass().getSimpleName() + ")");
                }
            }
            own.removeIf(br -> br.via() != null);
            return rewrite(mod, bytes);
        }
    }

    byte[] rewrite(Mod mod, byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(0) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
        ClassVisitor remap = new ClassRemapper(writer, new SimpleRemapper(Opcodes.ASM9, classMap));
        String[] self = new String[1];
        ClassVisitor sites = new ClassVisitor(Opcodes.ASM9, remap) {
            List<String> targets = List.of();
            List<String> callers = List.of();

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                self[0] = name;
                mod.self = name;
                mod.selfInterface = (access & Opcodes.ACC_INTERFACE) != 0;
                mod.selfVersion = version & 0xFFFF;
                mod.added.clear();
                targets = mod.mixinTargets.getOrDefault(name, List.of());
                // A mixin's code runs inside each target class, so access is checked from there.
                callers = targets.isEmpty() ? List.of(name) : targets.stream().map(JarPort.this::cls).toList();
                // A target in another mod is unknown here. Mixin makes a mixin extend its target's superclass, so the
                // mixin's own parents stand in for the target's (protected members of the game class they extend).
                if (targets.stream().anyMatch(t -> !mod.classes.containsKey(t) && !a.classes.containsKey(t))) {
                    callers = new ArrayList<>(callers);
                    callers.add(name);
                }
                String where = Exposure.dotted(name);
                String replaced = superName == null || (access & Opcodes.ACC_INTERFACE) != 0 ? null : successor(superName);
                if (replaced != null) {
                    // Mixin wants a mixin to extend its target's superclass, which the new release names itself.
                    GameDiff.Cls target = targets.size() == 1 ? b.classes.get(cls(targets.get(0))) : null;
                    if (target != null && target.sup != null) {
                        replaced = target.sup;
                    }
                    mod.supers.put(name, new String[] { superName, replaced });
                    mod.change("superclass the game removed replaced by the one its own subclasses extend now");
                    if (becameInterface(superName) && (interfaces == null || !Arrays.asList(interfaces).contains(superName))) {
                        interfaces = interfaces == null ? new String[] { superName } : java.util.stream.Stream.concat(Arrays.stream(interfaces), java.util.stream.Stream.of(superName)).toArray(String[]::new);
                    }
                    superName = inverse.getOrDefault(replaced, replaced);
                    signature = null; // its type arguments were the old superclass's
                } else if (superName != null) {
                    types(mod, "L" + superName + ";", where);
                }
                for (String i : interfaces == null ? new String[0] : interfaces) {
                    types(mod, "L" + i + ";", where);
                }
                types(mod, signature, where);
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                AnnotationVisitor av = super.visitAnnotation(desc, visible);
                if (desc.equals(SUBSCRIBER) && av != null && mod.toModBus.contains(self[0])) {
                    mod.change("event subscriber put on the mod bus");
                    return new AnnotationVisitor(Opcodes.ASM9, av) {
                        @Override
                        public void visitEnd() {
                            super.visitEnum("bus", "Lnet/neoforged/fml/common/EventBusSubscriber$Bus;", "MOD");
                            super.visitEnd();
                        }
                    };
                }
                if (!desc.equals("Lorg/spongepowered/asm/mixin/Mixin;") || av == null) {
                    return av;
                }
                // @Mixin(targets = "a.b.C") names classes as text, which the class renamer does not touch.
                return new AnnotationVisitor(Opcodes.ASM9, av) {
                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        AnnotationVisitor inner = super.visitArray(name);
                        return !"targets".equals(name) || inner == null ? inner : new AnnotationVisitor(Opcodes.ASM9, inner) {
                            @Override
                            public void visit(String n, Object value) {
                                super.visit(n, value instanceof String s ? className(s) : value);
                            }
                        };
                    }

                    @Override
                    public void visit(String name, Object value) {
                        super.visit(name, "targets".equals(name) && value instanceof String s ? className(s) : value);
                    }
                };
            }

            @Override
            public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                if (mod.shadowTargets.containsKey(self[0] + "." + name)) {
                    return null;
                }
                types(mod, desc, Exposure.dotted(self[0]) + "." + name);
                String[] shadow = mod.renamedDecl.get(self[0] + "." + name);
                String newName = shadow == null ? name : shadow[0];
                FieldVisitor fv = super.visitField(access, newName, desc, signature, value);
                return fv == null ? null : new FieldVisitor(Opcodes.ASM9, fv) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                        return mixinStrings(mod, targets, super.visitAnnotation(d, visible), null);
                    }
                };
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                String where = Exposure.dotted(self[0]) + "." + name;
                types(mod, desc, where);
                String[] renamed = mod.renamedDecl.get(self[0] + "." + name + desc);
                String newName = name, newDesc = desc;
                if (renamed != null) {
                    newName = renamed[0];
                    newDesc = oldDesc(renamed[1]);
                }
                if (!mod.kept.contains(name + desc)
                    && mod.bridges.getOrDefault(self[0], List.of()).stream().anyMatch(br -> br.via() != null && br.target().equals(name) && br.targetDesc().equals(desc))) {
                    return null; // its body moves to the game method's new signature (visitEnd)
                }
                String key = self[0] + "." + name + desc;
                Adapter adapter = mod.adapters.get(key);
                String originalSignature = signature;
                if (adapter != null) {
                    // The handler keeps its name and annotations with the new parameters; its body moves to name$mmt.
                    newDesc = oldDesc(adapter.desc());
                    signature = null;
                }
                boolean moved = mod.split.getOrDefault(self[0], List.of()).stream().anyMatch(l -> l.name().equals(name) && l.desc().equals(desc));
                boolean staticized = mod.staticized.contains(key) && mod.retargets.keySet().stream().anyMatch(r -> r.startsWith(key + " "));
                if (staticized) {
                    access |= Opcodes.ACC_STATIC;
                }
                MethodVisitor mv = super.visitMethod(moved ? access & ~Opcodes.ACC_PRIVATE : access, newName, newDesc, signature, exceptions);
                if (moved && mv != null) {
                    mv = new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                            return d.equals(LISTENER) ? null : super.visitAnnotation(d, visible);
                        }
                    };
                }
                int locals = mod.maxLocals.getOrDefault(key, 0);
                if (staticized && adapter == null && mv != null) {
                    mv = new Staticize(mv);
                    mod.change("mixin handler made static, as its target now is");
                }
                if (adapter != null && mv != null) {
                    int body = access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED) | Opcodes.ACC_PRIVATE;
                    MethodVisitor code = super.visitMethod(body, name + "$mmt", desc, originalSignature, exceptions);
                    mv = new Split(mv, staticized ? new Staticize(code) : code, self[0], name, desc, access, adapter, mod.selfInterface);
                    mod.change("mixin handler re-declared for its target's new parameters");
                }
                return mv == null ? null : new Sites(mod, mv, locals, targets, callers, key, where);
            }

            @Override
            public void visitEnd() {
                for (Copy copy : mod.copies.getOrDefault(self[0], List.of())) {
                    copyBody(copy, self[0], remap);
                }
                for (Bridge br : mod.bridges.getOrDefault(self[0], List.of())) {
                    if (br.via() != null) {
                        moved(mod, bytes, self[0], br, remap);
                    } else {
                        bridgeCode(super.visitMethod(br.access(), br.name(), oldDesc(br.desc()), null, null), self[0], mod.selfInterface, br);
                    }
                }
                // An interface's methods are public before Java 9 (fabric-permissions-api's Permissions, nested in many mods).
                int open = mod.selfInterface && mod.selfVersion < Opcodes.V9 ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE;
                for (Added m : mod.added.values()) {
                    MethodVisitor mv = super.visitMethod(open | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, m.name(), m.desc(), null, null);
                    mv.visitCode();
                    m.code().steps.forEach(step -> step.accept(mv));
                    mv.visitMaxs(m.code().max, m.locals());
                    mv.visitEnd();
                }
                super.visitEnd();
            }
        };
        reader.accept(sites, 0);
        return writer.toByteArray();
    }

    /** A class named as text ("a.b.C" or "a/b/C") in the new release's names, in the same spelling. */
    String className(String text) {
        boolean dotted = text.indexOf('/') < 0;
        String renamed = cls(text.replace('.', '/'));
        return dotted ? renamed.replace('/', '.') : renamed;
    }

    /** A mixin's @Shadow field named as its target's field is now named; other fields keep their names. */
    String shadowField(Mod mod, String mixin, List<String> targets, String name, String desc) {
        for (String t : targets) {
            if (!a.classes.containsKey(t)) {
                continue;
            }
            GameDiff.Cls decl = Exposure.declaring(a, t, 'f', name, null);
            if (decl == null) {
                continue;
            }
            // A shadow of another type than the old release's field shadows something the snapshot doesn't hold (a field the
            // loader patches, as NeoForge retypes ParticleEngine.providers): the update did nothing to it.
            if (!decl.fields.get(name).desc.equals(desc)) {
                return name;
            }
            String[] moved = route.field(decl.name, name);
            String newDesc = desc(desc);
            GameDiff.Cls now = Exposure.declaring(b, cls(t), 'f', moved[1], null);
            if (now != null && now.fields.get(moved[1]).desc.equals(newDesc)) {
                if (!moved[1].equals(name)) {
                    mod.renamedDecl.put(mixin + "." + name, new String[] { moved[1], newDesc });
                    mod.change("mixin shadow renamed");
                }
                return moved[1];
            }
            // The shadow is the target's field under the mixin's name: each use is ported as a use of that field (what the
            // game's own code now reads instead, or a report naming the field), and the declaration goes, since Mixin
            // refuses a shadow its target no longer has.
            mod.shadowTargets.put(mixin + "." + name, t);
            mod.change("mixin shadow of a field the update broke ported as the target's field");
            return name;
        }
        return name;
    }

    /** A mixin method that shadows a target method (@Shadow, or an abstract one) takes the target's new name. */
    String[] shadowMethod(Mod mod, String mixin, List<String> targets, String name, String desc, int access) {
        String bare = name.startsWith("shadow$") ? name.substring(7) : name;
        for (String t : targets) {
            if (!a.classes.containsKey(t)) {
                continue;
            }
            GameDiff.Cls decl = Exposure.declaring(a, t, 'm', bare, desc);
            if (decl == null) {
                continue;
            }
            String[] moved = route.method(decl.name, bare, desc);
            String mapped = desc(desc);
            if (Exposure.declaring(b, cls(t), 'm', bare, mapped) != null) {
                return null;
            }
            if (Exposure.declaring(b, cls(t), 'm', moved[1], moved[2]) != null && moved[2].equals(mapped)) {
                String prefix = name.substring(0, name.length() - bare.length());
                mod.renamedDecl.put(mixin + "." + name + desc, new String[] { prefix + moved[1], moved[2] });
                mod.change("mixin method renamed");
                return new String[] { prefix + moved[1] };
            }
            if ((access & Opcodes.ACC_ABSTRACT) != 0 || (access & Opcodes.ACC_PRIVATE) == 0) {
                mod.unlinked.add("mixin method gone: " + Exposure.dotted(t) + "." + bare + desc + " (in " + Exposure.dotted(mixin) + ")");
            }
            return null;
        }
        return null;
    }

    static final Set<String> MEMBER_KEYS = Set.of("method", "target", "value");
    static final Map<String, String> ACCESSORS = Map.of(
        "Lorg/spongepowered/asm/mixin/gen/Accessor;", "Accessor",
        "Lorg/spongepowered/asm/mixin/gen/Invoker;", "Invoker"
    );

    /** Mixin annotation strings that name target members ("method", @At "target"): moved to the new names when they link, else reported. */
    AnnotationVisitor mixinStrings(Mod mod, List<String> targets, AnnotationVisitor av, String handler) {
        if (av == null || targets.isEmpty()) {
            return av;
        }
        return new AnnotationVisitor(Opcodes.ASM9, av) {
            @Override
            public void visit(String name, Object value) {
                if ("index".equals(name) && value instanceof Integer && handler != null && mod.argIndexes.containsKey(handler)) {
                    super.visit(name, mod.argIndexes.get(handler)); // a @ModifyArg's argument, where the call's new signature has it
                    return;
                }
                super.visit(name, value instanceof String s && name != null && MEMBER_KEYS.contains(name) ? member(mod, targets, name, s, handler) : value);
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                AnnotationVisitor inner = super.visitArray(name);
                if (name == null || !MEMBER_KEYS.contains(name)) {
                    return mixinStrings(mod, targets, inner, handler);
                }
                return new AnnotationVisitor(Opcodes.ASM9, inner) {
                    @Override
                    public void visit(String n, Object value) {
                        super.visit(n, value instanceof String s ? member(mod, targets, name, s, handler) : value);
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String n, String d) {
                        return at(mod, targets, super.visitAnnotation(n, d), d, handler);
                    }
                };
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String desc) {
                return at(mod, targets, super.visitAnnotation(name, desc), desc, handler);
            }
        };
    }

    /**
     * A nested annotation of a handler's: an @At whose place moved (Mod.points, see moveAt) is written with its new
     * value, target and ordinal, read whole first as its elements come in any order; anything else as mixinStrings writes it.
     */
    AnnotationVisitor at(Mod mod, List<String> targets, AnnotationVisitor av, String desc, String handler) {
        if (av == null || !AT.equals(desc) || handler == null || mod.points.keySet().stream().noneMatch(k -> k.startsWith(handler + " "))) {
            return mixinStrings(mod, targets, av, handler);
        }
        AnnotationVisitor rest = mixinStrings(mod, targets, av, handler);
        String[] read = new String[3];
        return new AnnotationVisitor(Opcodes.ASM9, rest) {
            @Override
            public void visit(String name, Object value) {
                if ("value".equals(name) && value instanceof String v) {
                    read[0] = v;
                } else if ("target".equals(name) && value instanceof String v) {
                    read[1] = v;
                } else if ("ordinal".equals(name) && value instanceof Integer i) {
                    read[2] = String.valueOf(i);
                } else {
                    super.visit(name, value);
                }
            }

            @Override
            public void visitEnd() {
                String[] moved = mod.points.get(handler + " " + read[0] + " " + read[1] + " " + read[2]);
                if (moved != null) {
                    mod.change("mixin injection point moved where the game's new code has it");
                }
                String[] out = moved != null ? moved : read;
                // What did not change is written as mixinStrings writes it.
                if (out[0] != null) {
                    av.visit("value", out[0].equals(read[0]) ? member(mod, targets, "value", out[0], handler) : out[0]);
                }
                if (out[1] != null) {
                    av.visit("target", out[1].equals(read[1]) ? member(mod, targets, "target", out[1], handler) : out[1]);
                }
                if (out[2] != null) {
                    av.visit("ordinal", Integer.parseInt(out[2]));
                }
                super.visitEnd();
            }
        };
    }

    static final Pattern REFERENCE = Pattern.compile("([\\w.$]+)::(\\w+)");
    static final Pattern STATIC_CALL = Pattern.compile("([\\w.$]+)\\.(\\w+)\\(\\)");
    static final Pattern STATIC_FIELD = Pattern.compile("([\\w.$]+)\\.(\\w+)");
    static final Pattern MEMBER = Pattern.compile("(?:L([^;]+);)?([\\w$<>]+)(?:(\\(.*)|:(.+))?");

    /**
     * One member string of a mixin annotation in the new release's names, or as written when it cannot be read or does
     * not link; handler is the class.method+desc whose annotation it is in, where a retarget may apply.
     */
    String member(Mod mod, List<String> targets, String key, String text, String handler) {
        String moved = handler == null ? null : mod.retargets.get(handler + " " + text);
        if (moved != null) {
            mod.change("mixin injection moved to the target's new signature");
            return moved;
        }
        Matcher ctor = key.equals("method") && handler != null && mod.injectors.containsKey(handler) ? MEMBER.matcher(text) : null;
        if (ctor != null && ctor.matches() && "<init>".equals(ctor.group(2))) {
            String changed = constructorChanged(ctor.group(1) != null ? List.of(ctor.group(1)) : targets, ctor.group(3), handler.substring(handler.indexOf('(')));
            if (changed != null) {
                // By name alone Mixin refuses a handler whose arguments no constructor takes. One named by its old
                // descriptor is only not found: a break where the injector requires a place and none of its other
                // selectors gives one (ferritecore names three constructors of one class; packet-fixer requires none).
                Injector inj = mod.injectors.get(handler);
                String mixin = handler.substring(0, handler.indexOf('.'));
                int require = inj.require()[0] >= 0 ? inj.require()[0] : mod.requires.getOrDefault(mixin, 0);
                boolean elsewhere = inj.methods().stream().filter(s -> !s.equals(text)).anyMatch(s -> {
                    Matcher other = MEMBER.matcher(s);
                    return !other.matches() || !"<init>".equals(other.group(2))
                        || constructorChanged(other.group(1) != null ? List.of(other.group(1)) : targets, other.group(3), handler.substring(handler.indexOf('('))) == null;
                });
                if (ctor.group(3) != null && (require == 0 || elsewhere)) {
                    mod.silent.add(changed.replace("mixin target changed signature", "mixin injection finds no place"));
                } else {
                    mod.unlinked.add(changed);
                }
                return text;
            }
        }
        Found f = find(targets, key, text);
        if (f.report() != null && f.report().contains(".lambda$") && handler != null && mod.injectors.containsKey(handler)) {
            // A lambda the new release no longer has stops the game only where the injector requires its place.
            Injector inj = mod.injectors.get(handler);
            String mixin = handler.substring(0, handler.indexOf('.'));
            int require = inj.require()[0] >= 0 ? inj.require()[0] : mod.requires.getOrDefault(mixin, 0);
            if (require == 0) {
                mod.silent.add(f.report().replace("mixin target gone", "mixin injection finds no place"));
                return f.text();
            }
        }
        if (f.report() != null) {
            mod.unlinked.add(f.report());
        }
        if (f.change() != null) {
            mod.change(f.change());
        }
        return f.text();
    }

    /**
     * What a mixin annotation string names in the new release. kind: 'f' or 'm' when it links (owner, name and desc in
     * new names; a field's desc is null when the string gives none), 's' when the method only took other parameters
     * (desc its new descriptor, null when that is not known; changed the string naming it), 0 otherwise. text is what
     * is written when nothing is moved, report what is reported then.
     */
    record Found(String text, String report, String change, char kind, String owner, String name, String desc, String changed) {}

    /**
     * A mixin selector naming one of the game's lambdas (lambda$charTyped$7), which javac numbers anew in each release
     * and the snapshot leaves out: read from the game's own classes. A name and descriptor the new class still has are
     * kept, as Mixin finds them; one it lacks follows the one new lambda whose code reads alike, or is reported gone.
     * Null when the game's code cannot be read.
     */
    Found lambdaTarget(List<String> owners, boolean named, String name, String mdesc, String text) {
        GameCode oldCode = code(a), newCode = code(b);
        if (oldCode == null || newCode == null) {
            return null;
        }
        for (String o : owners) {
            Map<String, String> before = a.classes.containsKey(o) ? oldCode.bodies(o, this::cls) : null;
            Map<String, String> now = before == null ? null : newCode.bodies(cls(o), x -> x);
            if (now == null) {
                continue;
            }
            for (var e : before.entrySet()) {
                String k = e.getKey();
                if (!k.startsWith(name + "(") || mdesc != null && !k.equals(name + desc(mdesc))) {
                    continue;
                }
                String d = k.substring(name.length());
                if (now.containsKey(k)) {
                    return new Found(text, null, null, 'm', cls(o), name, d, null); // Mixin finds it by name and descriptor
                }
                List<String> same = now.entrySet().stream().filter(n -> n.getKey().startsWith("lambda$") && n.getKey().endsWith(d)
                    && n.getKey().indexOf('(') == n.getKey().length() - d.length() && n.getValue().equals(e.getValue())).map(Map.Entry::getKey).toList();
                if (same.size() == 1) {
                    String moved = same.get(0).substring(0, same.get(0).length() - d.length());
                    return new Found((named ? "L" + cls(o) + ";" : "") + moved + (mdesc == null ? "" : d), null, "mixin target renamed", 'm', cls(o), moved, d, null);
                }
                return new Found(text, "mixin target gone: " + Exposure.dotted(o) + "." + name + d, null, (char) 0, null, null, null, null);
            }
        }
        return null;
    }

    /**
     * An injector into a target's constructor that the update reshaped: named with its old descriptor, which the new class
     * lacks, or by name alone with a handler that takes the old constructor's arguments before its CallbackInfo, which
     * no constructor of the new class takes (Mixin then refuses the handler's descriptor). The report, or null.
     */
    String constructorChanged(List<String> owners, String mdesc, String handlerDesc) {
        Type[] params = Type.getArgumentTypes(handlerDesc);
        int ci = callbackAt(params);
        List<Type> captured = ci > 0 ? Arrays.stream(params, 0, ci).map(t -> Type.getType(desc(t.getDescriptor()))).toList() : List.of();
        for (String o : owners) {
            GameDiff.Cls was = a.classes.get(o), now = b.classes.get(cls(o));
            if (was == null || now == null) {
                continue;
            }
            for (GameDiff.Meth k : was.methods.values()) {
                if (!k.name.equals("<init>") || (mdesc != null ? !k.desc.equals(mdesc) : captured.isEmpty() || !takesExactly(desc(k.desc), captured))) {
                    continue;
                }
                boolean kept = mdesc != null ? now.methods.containsKey("<init>" + desc(mdesc))
                    : now.methods.values().stream().anyMatch(n -> n.name.equals("<init>") && takesExactly(n.desc, captured));
                if (!kept) {
                    return "mixin target changed signature: " + Exposure.dotted(o) + ".<init>" + k.desc;
                }
            }
        }
        return null;
    }

    /** Whether a constructor takes exactly the arguments a handler captures before its CallbackInfo, as Mixin wants. */
    static boolean takesExactly(String desc, List<Type> first) {
        return Arrays.asList(Type.getArgumentTypes(desc)).equals(first);
    }

    static Found keep(String text) {
        return new Found(text, null, null, (char) 0, null, null, null, null);
    }

    Found find(List<String> targets, String key, String text) {
        if (text.contains("*") || text.contains(" ") || text.isEmpty() || key.equals("value") && !text.contains("(") && !text.contains(";")) {
            return keep(text); // wildcards, @At("HEAD") and friends
        }
        Matcher m = MEMBER.matcher(text);
        if (!m.matches()) {
            return keep(text);
        }
        String owner = m.group(1), name = m.group(2), mdesc = m.group(3), fdesc = m.group(4);
        if (key.equals("method") && name.startsWith("lambda$")) {
            Found lambda = lambdaTarget(owner != null ? List.of(owner) : targets, owner != null, name, mdesc, text);
            if (lambda != null) {
                return lambda;
            }
        }
        if (name.equals("<init>") || name.equals("<clinit>")) {
            return mdesc == null ? keep(text) : new Found((owner == null ? "" : "L" + cls(owner) + ";") + name + desc(mdesc), null, null, 'm', owner == null ? null : cls(owner), name, desc(mdesc), null);
        }
        if (owner != null && !a.classes.containsKey(owner) && !b.classes.containsKey(owner) && mdesc != null) {
            return new Found(text, null, null, 'm', owner, name, mdesc, null); // the JDK's or a library's: the same in both
        }
        List<String> owners = owner != null ? List.of(owner) : targets;
        for (String o : owners) {
            if (!a.classes.containsKey(o)) {
                continue;
            }
            if (fdesc != null || (mdesc == null && Exposure.declaring(a, o, 'f', name, null) != null && owner != null)) {
                GameDiff.Cls decl = Exposure.declaring(a, o, 'f', name, null);
                if (decl == null) {
                    continue;
                }
                String[] moved = route.field(decl.name, name);
                if (Exposure.declaring(b, cls(o), 'f', moved[1], null) == null) {
                    return new Found(text, "mixin target gone: " + Exposure.dotted(o) + "." + name, null, (char) 0, null, null, null, null);
                }
                return new Found((owner == null ? "" : "L" + cls(o) + ";") + moved[1] + (fdesc == null ? "" : ":" + desc(fdesc)), null,
                    moved[1].equals(name) ? null : "mixin target renamed", 'f', cls(o), moved[1], fdesc == null ? null : desc(fdesc), null);
            }
            List<String> descs = new ArrayList<>();
            GameDiff.Cls holder = a.classes.get(o);
            for (GameDiff.Cls c = holder; c != null && descs.isEmpty(); c = c.sup == null ? null : a.classes.get(c.sup)) {
                for (GameDiff.Meth gm : c.methods.values()) {
                    if (gm.name.equals(name) && (mdesc == null || gm.desc.equals(mdesc))) {
                        descs.add(gm.desc);
                    }
                }
            }
            if (descs.isEmpty()) {
                // A descriptor the old game jar lacks (the loader patched that method's parameters, as NeoForge 1.20.4 did
                // WorldOpenFlows.loadLevel): the string is kept as written, so Mixin finds it only if the new class has a
                // method of that name at all.
                if (mdesc != null && !named(a, o, name).isEmpty() && named(b, cls(o), name).isEmpty()) {
                    return new Found(text, "mixin target gone: " + Exposure.dotted(o) + "." + name + mdesc, null, (char) 0, null, null, null, null);
                }
                continue;
            }
            String d = descs.get(0);
            GameDiff.Cls decl = Exposure.declaring(a, o, 'm', name, d);
            String[] moved = route.method(decl == null ? o : decl.name, name, d);
            String mapped = desc(d);
            if (Exposure.declaring(b, cls(o), 'm', name, mapped) != null) {
                return new Found((owner == null ? "" : "L" + cls(o) + ";") + name + (mdesc == null ? "" : mapped), null, null, 'm', cls(o), name, mapped, null);
            }
            if (Exposure.declaring(b, cls(o), 'm', moved[1], moved[2]) != null && moved[2].equals(mapped)) {
                return new Found((owner == null ? "" : "L" + cls(o) + ";") + moved[1] + (mdesc == null ? "" : moved[2]), null, "mixin target renamed", 'm', cls(o), moved[1], moved[2], null);
            }
            List<GameDiff.Meth> sameName = named(b, cls(o), moved[1]);
            String report = "mixin target " + (sameName.isEmpty() ? "gone" : "changed signature") + ": " + Exposure.dotted(o) + "." + name + d;
            if (sameName.isEmpty()) {
                GameDiff.Meth went = key.equals("method") && decl != null && decl.name.equals(o) ? codeMoved(o, name, d) : null;
                if (went != null) {
                    // Followed as a method whose parameters changed: the handler moves only where Mixin will accept it there.
                    return new Found(text, report, null, 's', cls(o), went.name, went.desc, (owner == null ? "" : "L" + cls(o) + ";") + went.name + went.desc);
                }
                return new Found(text, report, null, (char) 0, null, null, null, null);
            }
            // Only its parameters changed: the route map's new signature, or the one method of that name on both sides.
            GameDiff.Meth now = !moved[2].equals(mapped) && Exposure.declaring(b, cls(o), 'm', moved[1], moved[2]) != null
                ? Exposure.declaring(b, cls(o), 'm', moved[1], moved[2]).methods.get(moved[1] + moved[2])
                : sameName.size() == 1 && named(a, o, name).size() == 1 ? sameName.get(0) : null;
            String prefix = owner == null ? "" : "L" + cls(o) + ";";
            return now == null
                ? new Found(text, report, null, 's', cls(o), moved[1], null, null)
                : new Found(text, report, null, 's', cls(o), now.name, now.desc, prefix + now.name + (mdesc == null ? "" : now.desc));
        }
        return keep(text);
    }

    final Map<String, Optional<GameDiff.Meth>> movedCode = new HashMap<>();

    /**
     * Where the code of a method the update dropped from its class (old names) went: the one method of the new class whose
     * instructions are mostly the old method's (alike) and were not before, as when an update renames a method and changes
     * its parameters at once. Where several read alike, the one that shares four in five of its instructions while no
     * other does. Null when none, or no clear one, reads alike, or the old body is too short to tell one apart.
     */
    GameDiff.Meth codeMoved(String owner, String name, String desc) {
        return movedCode.computeIfAbsent(owner + "." + name + desc, k -> {
            GameCode oldCode = code(a), newCode = code(b);
            GameDiff.Cls now = b.classes.get(cls(owner));
            Map<String, String> before = oldCode == null ? null : oldCode.bodies(owner, this::cls);
            Map<String, String> after = newCode == null || now == null ? null : newCode.bodies(cls(owner), x -> x);
            String was = before == null || after == null ? null : before.get(name + desc(desc));
            if (was == null || GameCode.instructions(was).length < 6) {
                return Optional.empty();
            }
            GameDiff.Meth found = null;
            double best = 0, next = 0;
            for (GameDiff.Meth m : now.methods.values()) {
                // A method the class kept counts when it took the dropped one's code in (1.21.2 put _blitToScreen's code
                // into the kept blitToScreen and the new blitAndBlendToScreen): then two read alike and neither is followed.
                String body = after.get(m.name + m.desc), had = before.get(m.name + m.desc);
                if (m.name.startsWith("<") || body == null || !alike(was, body) || had != null && alike(was, had)) {
                    continue;
                }
                double like = likeness(was, body);
                if (like > best) {
                    next = best;
                    best = like;
                    found = m;
                } else {
                    next = Math.max(next, like);
                }
            }
            // Several read alike: only a clear winner took the code (1.21.2's setShaderTexture(int, ResourceLocation) is
            // _setShaderTexture's body plus one call; setShaderTexture(int, int) shares half of it).
            return next > 0 && (best < 0.8 || next >= 0.8) ? Optional.empty() : Optional.ofNullable(found);
        }).orElse(null);
    }

    /**
     * An injector whose points all left the methods it names, followed to the one method of the class where the code
     * around them went: a private helper the new method calls (1.21.2's Gui.renderPlayerHealth calls the new
     * renderAirBubbles, which reads getMaxAirSupply now), or a method the update added whose instructions are mostly the
     * old method's (1.20.5 split Gui.render into layer methods it registers by reference). Every point must be a call or
     * field use and that method must have them all (as often as the old method had, when an ordinal picks one); the
     * class must name it once (a call or a method reference), so the injector runs as often as before. Or any method of
     * the class the new method calls once, when every way into it in the new release came into the old method before
     * (entered: 1.20.5's Minecraft.disconnect(Screen) hands over to the new disconnect(Screen, boolean), which every old
     * caller of disconnect(Screen) now calls). A helper the old class had must not have had the point already (it did not
     * move there). Handlers that may cancel (cancels) are moved only into a method the new one does nothing but hand over
     * to, where ending it ends the new one. into with each method as one whose parameters changed ('s'),
     * which the handler follows only where Mixin will accept it, or null. A method whose own parameters changed too is
     * followed from its new signature.
     */
    List<Found> deeper(List<String> targets, Injector inj, List<Found> into, List<Found> ats, boolean cancels) {
        if (into.isEmpty() || into.stream().anyMatch(f -> f.kind() != 'm' && f.kind() != 's' || f.desc() == null || f.name().startsWith("<"))
            || inj.ats().isEmpty() || inj.ats().stream().anyMatch(at -> !Set.of("INVOKE", "INVOKE_ASSIGN", "FIELD").contains(at[0]))
            || ats.stream().anyMatch(at -> at == null || at.kind() == 0 || at.desc() == null) || pointsKept(targets, inj, into, ats)) {
            return null;
        }
        GameCode code = code(b);
        List<Found> out = new ArrayList<>();
        for (int i = 0; i < into.size(); i++) {
            Found f = into.get(i);
            GameDiff.Meth before = oldMethod(targets, inj.methods().get(i));
            List<String[]> refs = code == null || before == null ? null : code.refs(f.owner(), f.name(), f.desc());
            Map<String, String> bodies = refs == null ? null : code.bodies(f.owner(), x -> x);
            if (bodies == null) {
                return null;
            }
            Map<String, String> was = code(a) == null ? null : code(a).bodies(before.owner.name, this::cls);
            String old = was == null ? null : was.get(before.name + desc(before.desc));
            Set<String> called = new HashSet<>();
            for (String[] r : refs) {
                if (r[0].equals("m") && r[1].equals(f.owner())) {
                    called.add(r[2] + r[3]);
                }
            }
            GameDiff.Meth found = null;
            for (GameDiff.Meth n : b.classes.get(f.owner()).methods.values()) {
                if (n.name.startsWith("<") || n.name.equals(f.name()) && n.desc.equals(f.desc())) {
                    continue;
                }
                // Reached from the new method itself as a private helper, or a method the update added whose code is
                // mostly the old method's (1.20.5 split Gui.render into layers it registers as method references).
                boolean helper = called.contains(n.name + n.desc) && (n.flags & Opcodes.ACC_PRIVATE) != 0;
                boolean part = !helper && old != null && !was.containsKey(n.name + n.desc) && bodies.get(n.name + n.desc) != null
                    && within(bodies.get(n.name + n.desc), old);
                long calls = refs.stream().filter(r -> r[0].equals("m") && r[1].equals(f.owner()) && (r[2] + r[3]).equals(n.name + n.desc)).count();
                boolean entry = calls == 1 && entered(f, before, n);
                if (!helper && !part && !entry || cancels && !(entry && handsOver(f, n))) {
                    continue;
                }
                Matcher use = Pattern.compile(Pattern.quote(":" + f.owner() + "." + n.name + n.desc) + "(?=[\\n:]|$)").matcher(String.join("\n", bodies.values()));
                int uses = 0;
                while (use.find()) {
                    uses++;
                }
                // A helper the old class had too must not have had the point already: then the point did not move there,
                // and the injector would land on other code that was always in it.
                GameDiff.Meth had = helper && was.containsKey(n.name + n.desc) ? a.method(before.owner.name, n.name, oldDesc(n.desc)) : null;
                had = entry && !helper && was.containsKey(n.name + n.desc) ? a.method(before.owner.name, n.name, oldDesc(n.desc)) : had;
                boolean all = (uses == 1 || entry) && (!helper && !entry || !was.containsKey(n.name + n.desc) || had != null);
                for (int k = 0; all && k < ats.size(); k++) {
                    all = pointKept(inj.ats().get(k), ats.get(k), new GameDiff.Meth[] { n, before }) && (had == null || hadNone(inj.ats().get(k), ats.get(k), had));
                }
                if (!all) {
                    continue;
                }
                if (found != null) {
                    return null;
                }
                found = n;
            }
            if (found == null) {
                return null;
            }
            String text = inj.methods().get(i);
            String prefix = text.startsWith("L") && text.indexOf(';') > 0 ? "L" + f.owner() + ";" : "";
            out.add(new Found(text, null, null, 's', f.owner(), found.name, found.desc, prefix + found.name + found.desc));
        }
        return out;
    }

    /**
     * Whether every way into a method n of the new release came into the old method (before) in the old release, so an
     * injector moved from the old method into n runs where it ran: each method whose code calls n (or names it) is the new
     * method f itself, a method the old release had whose code reached the old method (directly or through two calls of
     * the game), or a method new in this release every caller of which is one of these.
     */
    boolean entered(Found f, GameDiff.Meth before, GameDiff.Meth n) {
        GameCode now = code(b), then = code(a);
        Set<String> callers = now == null || then == null ? null : now.callers(n.owner.name, n.name, n.desc);
        String self = f.owner() + "." + f.name() + f.desc();
        if (callers == null || !callers.contains(self)) {
            return false;
        }
        String target = before.owner.name + "." + before.name + before.desc;
        for (String c : callers) {
            if (!c.equals(self) && !cameIn(now, then, c, target, self, 2, new HashSet<>())) {
                return false;
            }
        }
        return true;
    }

    /** One caller of entered: reached the old method in the old release, or is new and only reached from such callers. */
    boolean cameIn(GameCode now, GameCode then, String caller, String target, String self, int depth, Set<String> seen) {
        if (!seen.add(caller) || caller.equals(self)) {
            return caller.equals(self);
        }
        int dot = caller.indexOf('.'), paren = caller.indexOf('(');
        String owner = caller.substring(0, dot), name = caller.substring(dot + 1, paren), desc = caller.substring(paren);
        // A caller whose own signature changed (1.21.11's doWorldLoad took an Optional more in 26.1) is the one method of
        // its name the old class had.
        GameDiff.Cls was = a.classes.get(owner);
        List<GameDiff.Meth> same = was == null ? List.of() : was.methods.values().stream().filter(x -> x.name.equals(name)).toList();
        String oldDesc = was != null && then.steps(owner, name, desc) != null ? desc : same.size() == 1 && !name.startsWith("lambda$") ? same.get(0).desc : null;
        if (oldDesc != null) {
            return reaches(then, owner, name, oldDesc, target, 2, new HashSet<>());
        }
        Set<String> up = now.callers(owner, name, desc);
        if (depth == 0 || up == null || up.isEmpty()) {
            return false;
        }
        for (String c : up) {
            if (!cameIn(now, then, c, target, self, depth - 1, seen)) {
                return false;
            }
        }
        return true;
    }

    /** Whether an old-release method's code calls the method target (owner.name+desc), itself or through depth calls of the game. */
    boolean reaches(GameCode then, String owner, String name, String desc, String target, int depth, Set<String> seen) {
        List<String[]> steps = seen.add(owner + "." + name + desc) ? then.steps(owner, name, desc) : null;
        if (steps == null) {
            return false;
        }
        for (String[] x : steps) {
            if ((x[0].equals("m") || x[0].equals("l")) && (x[1] + "." + x[2] + x[3]).equals(target)) {
                return true;
            }
        }
        for (String[] x : steps) {
            if (depth > 0 && (x[0].equals("m") || x[0].equals("l")) && a.classes.containsKey(x[1]) && reaches(then, x[1], x[2], x[3], target, depth - 1, seen)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the new method f does nothing but hand over to n (its arguments and constants aside) and return what n gives. */
    boolean handsOver(Found f, GameDiff.Meth n) {
        List<String[]> steps = code(b) == null ? null : code(b).steps(f.owner(), f.name(), f.desc());
        if (steps == null || !Type.getReturnType(f.desc()).equals(Type.getReturnType(n.desc))) {
            return false;
        }
        List<String[]> named = steps.stream().filter(x -> !x[0].equals("c")).toList();
        return named.size() == 1 && named.get(0)[0].equals("m") && (named.get(0)[1] + "." + named.get(0)[2] + named.get(0)[3]).equals(n.owner.name + "." + n.name + n.desc);
    }

    /** Whether an old-release method made no use of an @At's member (named as the @At wrote it); false when unreadable. */
    boolean hadNone(String[] point, Found at, GameDiff.Meth old) {
        char kind = at.kind() == 's' || at.kind() == 'r' ? 'm' : at.kind();
        Matcher m = MEMBER.matcher(point[1]);
        return m.matches() && uses(code(a), a, old, kind, m.group(1), m.group(2), m.group(3) != null ? m.group(3) : m.group(4)) == 0;
    }

    /** Every method of that name a call on owner reaches through its superclasses in one release. */
    static List<GameDiff.Meth> named(GameDiff.Snap s, String owner, String name) {
        List<GameDiff.Meth> out = new ArrayList<>();
        for (GameDiff.Cls c = s.classes.get(owner); c != null; c = c.sup == null ? null : s.classes.get(c.sup)) {
            for (GameDiff.Meth gm : c.methods.values()) {
                if (gm.name.equals(name) && out.stream().noneMatch(x -> x.desc.equals(gm.desc))) {
                    out.add(gm);
                }
            }
        }
        return out;
    }

    /**
     * A mixin handler's @Inject whose target method only took other parameters is moved to the new signature when Mixin
     * will accept the handler there and its injection points are still in the new method: every @At is HEAD, RETURN or
     * TAIL, or names a call or field the new method's code still uses. A handler that takes no target arguments (only
     * its CallbackInfo) keeps its body; one that takes them is re-declared with the new parameters and calls the original
     * with the arguments it had (see convert). One that captures locals, or a target whose result type changed, stays
     * reported. An @At target of an @Inject that only took other parameters is moved when every method injected into
     * still calls the new one: an @Inject's handler takes the target method's arguments, not the call's. Other injectors
     * (@Redirect, @ModifyArg, @WrapOperation...) take the call's arguments and stay reported.
     */
    /**
     * Any other injector (@ModifyExpressionValue, @Redirect, @ModifyArg...) into a method that only took other parameters:
     * its handler takes what the injection point gives, then, optionally, the target method's first arguments (Mixin's
     * argument capture). Its selector moves to the new signature when the arguments it captures, if any, are the same in
     * the new method, its result and staticness are, and its injection points are still in the new method.
     */
    static final boolean WHY = Boolean.getBoolean("jarport.why");
    static final ThreadLocal<Mod> PLANNING = new ThreadLocal<>(); // the mod plan is reading on this thread, whose refusals why records

    /** Why a mixin handler did not follow its target's new signature, for -Djarport.why (the type's design work). */
    static void why(String key, String reason) {
        Mod planning = PLANNING.get();
        if (planning != null && !reason.equals("O1")) { // O1: nothing to follow
            planning.refused.add(reason.split(" ", 2)[0] + " | " + key);
        }
        if (WHY) {
            System.err.println("WHY " + reason + " | " + key);
        }
    }

    /**
     * Whether a handler follows a target the update made static by being made static itself, as Mixin asks of a handler
     * into a static method: the old target was an instance method, the handler is one, and its body never reads its own
     * object (slot 0) and no method of the mixin calls it. Records it in staticized when so.
     */
    boolean staticizes(Mod mod, String key, GameDiff.RawMeth handler, GameDiff.Meth before, GameDiff.Meth after) {
        if (before.isStatic() || !after.isStatic() || (handler.flags & Opcodes.ACC_STATIC) != 0) {
            return false;
        }
        String owner = key.substring(0, key.indexOf('.'));
        byte[] bytes = mod.bytes.get(owner);
        if (bytes == null) {
            return false;
        }
        boolean[] blocked = { false };
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                boolean self = name.equals(handler.name) && desc.equals(handler.desc);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitVarInsn(int op, int var) {
                        blocked[0] |= self && var == 0;
                    }

                    @Override
                    public void visitIincInsn(int var, int inc) {
                        blocked[0] |= self && var == 0;
                    }

                    @Override
                    public void visitMethodInsn(int op, String o, String n, String d, boolean itf) {
                        blocked[0] |= o.equals(owner) && n.equals(handler.name) && d.equals(handler.desc);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (blocked[0]) {
            why(key, "T1 its target is static now and it reads its own object");
            return false;
        }
        mod.staticized.add(key);
        return true;
    }

    static final String WRAP_CONDITION = "WrapWithCondition;", REDIRECT = "/Redirect;", MODIFY_RECEIVER = "/ModifyReceiver;",
        MODIFY_VALUE = "/ModifyExpressionValue;", MODIFY_ARG = "/ModifyArg;";

    /**
     * An injector other than @Inject whose @At names a call that only took other arguments, in a method that kept its
     * signature (a call the update changed, as 1.21.4's ClientInput.tick(boolean, float) became tick()). Its @At moves to
     * the call's new signature where the handler stays valid there, by what the handler takes of the call:
     * a @ModifyExpressionValue takes only its result, so the result type must be the same; a @ModifyArg takes one
     * argument, found in the new call where its type stands once on each side, the index rewritten; a @Redirect,
     * @WrapWithCondition or @ModifyReceiver takes the receiver and every argument, so it is re-declared with the new
     * arguments and its body gets the old ones from them (convert: a type found once on each side, or a subtype), a
     * default only where the body never reads one. A @WrapOperation passes the arguments on to the call as an array and
     * stays reported. The call must be as static as before, and still made where the old method made it (pointsKept).
     */
    void followCall(Mod mod, String key, List<String> targets, GameDiff.RawMeth handler, Injector inj, List<Found> into, List<Found> points) {
        String kind = inj.kind();
        String label = "A-" + kind.substring(kind.lastIndexOf('/') + 1, kind.length() - 1);
        int which = -1;
        for (int i = 0; i < points.size(); i++) {
            Found at = points.get(i);
            if (at != null && at.kind() == 's') {
                if (which >= 0 || at.desc() == null) {
                    why(key, label + "1 several changed calls, or no one new signature");
                    return;
                }
                which = i;
            }
        }
        String[] point = inj.ats().get(which);
        Found at = points.get(which);
        Matcher m = MEMBER.matcher(point[1]);
        if (!point[0].startsWith("INVOKE") || !m.matches() || m.group(1) == null || m.group(3) == null) {
            why(key, label + "2 not a call written with its owner and descriptor");
            return;
        }
        Decl oldDecl = declaringOld(mod, m.group(1), 'm', m.group(2), m.group(3));
        GameDiff.Cls oldCls = oldDecl == null ? null : a.classes.get(oldDecl.owner());
        GameDiff.Meth before = oldCls == null ? null : oldCls.methods.get(m.group(2) + m.group(3));
        GameDiff.Meth after = b.method(at.owner(), at.name(), at.desc());
        if (before == null || after == null || before.isStatic() != after.isStatic()) {
            why(key, label + "3 the call is not read on both sides, or changed staticness");
            return;
        }
        if (!pointsKept(targets, inj, into, points)) {
            why(key, label + "4 the new call is not where the old one was");
            return;
        }
        Type[] was = Type.getArgumentTypes(desc(m.group(3))), now = Type.getArgumentTypes(at.desc());
        Type wasResult = Type.getReturnType(desc(m.group(3))), nowResult = Type.getReturnType(at.desc());
        Type[] hp = Type.getArgumentTypes(desc(handler.desc));
        if (kind.endsWith(MODIFY_VALUE)) {
            if (!wasResult.equals(nowResult)) {
                why(key, label + "5 its result changed type");
                return;
            }
        } else if (kind.endsWith(MODIFY_ARG)) {
            int index = inj.ordinal().length > 1 ? inj.ordinal()[1] : -1;
            if (hp.length != 1) {
                why(key, label + "6 it takes every argument");
                return;
            }
            int old = index;
            if (old < 0) {
                List<Integer> same = new ArrayList<>();
                for (int k = 0; k < was.length; k++) {
                    if (was[k].equals(hp[0])) {
                        same.add(k);
                    }
                }
                old = same.size() == 1 ? same.get(0) : -1;
            }
            int[] paired = pair(was, now);
            int fresh = -1;
            for (int j = 0; j < now.length; j++) {
                if (old >= 0 && paired[j] == old) {
                    fresh = j;
                }
            }
            if (fresh < 0 || !now[fresh].equals(hp[0]) || index < 0 && Arrays.stream(now).filter(hp[0]::equals).count() != 1) {
                why(key, label + "7 its argument is not in the new call once");
                return;
            }
            if (index >= 0 && fresh != index) {
                mod.argIndexes.put(key, fresh);
            }
        } else if (kind.endsWith(REDIRECT) || kind.endsWith(WRAP_CONDITION) || kind.endsWith(MODIFY_RECEIVER)) {
            int r = before.isStatic() ? 0 : 1;
            int captured = r + was.length;
            Type wantResult = kind.endsWith(REDIRECT) ? wasResult : kind.endsWith(WRAP_CONDITION) ? Type.BOOLEAN_TYPE : null;
            Type result = Type.getReturnType(desc(handler.desc));
            if (hp.length < captured || !Arrays.equals(Arrays.copyOfRange(hp, r, captured), was) || kind.endsWith(REDIRECT) && !wasResult.equals(nowResult)
                || wantResult != null && !result.equals(wantResult)) {
                why(key, label + "8 it does not take the call's arguments as they were, or the result changed");
                return;
            }
            boolean[] unread = unread(mod, key.substring(0, key.indexOf('.')), handler.name, handler.desc, (handler.flags & Opcodes.ACC_STATIC) != 0, captured);
            boolean[] args = unread == null ? null : Arrays.copyOfRange(unread, r, captured);
            int[] conv = convert(now, was, args);
            if (conv == null) {
                why(key, label + "9 it reads an argument the new call does not take");
                return;
            }
            int rest = hp.length - captured;
            Type[] desc = new Type[r + now.length + rest];
            int[] from = new int[captured];
            for (int k = 0; k < r; k++) {
                desc[k] = hp[k];
                from[k] = k;
            }
            for (int j = 0; j < now.length; j++) {
                desc[r + j] = now[j];
            }
            for (int k = 0; k < was.length; k++) {
                from[r + k] = conv[k] < 0 ? -1 : r + conv[k];
            }
            for (int k = 0; k < rest; k++) {
                desc[r + now.length + k] = hp[captured + k];
            }
            mod.adapters.put(key, new Adapter(Type.getMethodDescriptor(result, desc), from, captured, new String[captured][]));
        } else {
            why(key, label + "0 this injector passes the call's arguments on");
            return;
        }
        mod.retargets.put(key + " " + point[1], at.changed());
    }

    void retargetOther(Mod mod, String key, List<String> targets, GameDiff.RawMeth handler, Injector inj) {
        List<Found> into = inj.methods().stream().map(s -> find(targets, "method", s)).toList();
        List<Found> points = inj.ats().stream().map(at -> at[1] == null ? null : find(targets, "target", at[1])).toList();
        into = Objects.requireNonNullElse(deeper(targets, inj, into, points, cancels(mod, key, handler, inj)), into);
        if (into.stream().noneMatch(f -> f.kind() == 's')) {
            if (points.stream().anyMatch(at -> at != null && at.kind() == 's')) {
                followCall(mod, key, targets, handler, inj, into, points);
            }
            { why(key, "O1"); return; }
        }
        Type[] hp = Type.getArgumentTypes(desc(handler.desc));
        for (int i = 0; i < into.size(); i++) {
            Found f = into.get(i);
            if (f.kind() != 's') {
                continue;
            }
            GameDiff.Meth before = oldMethod(targets, inj.methods().get(i));
            GameDiff.Meth after = f.desc() == null ? null : b.method(f.owner(), f.name(), f.desc());
            if (before == null || after == null || f.changed() == null || !Type.getReturnType(desc(before.desc)).equals(Type.getReturnType(after.desc))
                || ((before.flags ^ after.flags) & Opcodes.ACC_STATIC) != 0 && !staticizes(mod, key, handler, before, after)) {
                { why(key, before == null ? "O2-old" : after == null ? "O2-new" : f.changed() == null ? "O2-changed" : ((before.flags ^ after.flags) & Opcodes.ACC_STATIC) != 0 ? "O2-static" : "O2-result"); return; }
            }
            Type[] bp = Type.getArgumentTypes(desc(before.desc)), ap = Type.getArgumentTypes(after.desc);
            if (inj.kind().endsWith("/ModifyVariable;") && !Arrays.equals(Arrays.stream(bp).mapToInt(Type::getSize).toArray(), Arrays.stream(ap).mapToInt(Type::getSize).toArray())) {
                { why(key, "O3 a local named by its slot would be another one"); return; } // a local named by its slot would be another one
            }
            for (int n = Math.min(hp.length, bp.length); n >= 1; n--) {
                if (Arrays.equals(Arrays.copyOfRange(hp, hp.length - n, hp.length), Arrays.copyOf(bp, n))) {
                    if (ap.length < n || !Arrays.equals(Arrays.copyOf(ap, n), Arrays.copyOf(bp, n))) {
                        { why(key, "O4 it captures an argument that changed"); return; } // it captures an argument that changed
                    }
                    break;
                }
            }
        }
        List<Found> ats = inj.ats().stream().map(at -> at[1] == null ? null : find(targets, "target", at[1])).toList();
        if (!pointsKept(targets, inj, into, ats)) {
            { why(key, "O5"); return; }
        }
        for (int i = 0; i < into.size(); i++) {
            if (into.get(i).kind() == 's') {
                mod.retargets.put(key + " " + inj.methods().get(i), into.get(i).changed());
            }
        }
    }

    /**
     * The field a game method (old release) stored its parameter i in on its own object (this.minecraft = minecraft, as
     * 1.21.10's Screen.init did), as a step for what this answers, when the new release's target class still has that
     * field with that type, open to it, and the method stored nothing else there: the object kept what it was told, so a
     * handler that took the parameter reads the field where the new method no longer passes it. Only where every new
     * constructor stores it (1.21.11's Screen(Component) hands over to Screen(Minecraft, Font, Component)) and no other
     * method does. Null otherwise.
     */
    String kept(GameDiff.Meth before, int i, String target) {
        GameCode oldCode = code(a);
        Map<String, String> bodies = oldCode == null || before.isStatic() ? null : oldCode.bodies(before.owner.name, this::cls);
        String body = bodies == null ? null : bodies.get(before.name + desc(before.desc));
        if (body == null) {
            return null;
        }
        Type[] params = Type.getArgumentTypes(desc(before.desc));
        int slot = 1;
        for (int k = 0; k < i; k++) {
            slot += params[k].getSize();
        }
        String put = "181:([^\\n]+)\\.(\\w+)" + Pattern.quote(params[i].getDescriptor());
        Matcher stored = Pattern.compile("(?m)^25:0\\n" + params[i].getOpcode(Opcodes.ILOAD) + ":" + slot + "\\n" + put + "$").matcher(body);
        if (!stored.find()) {
            return null;
        }
        String owner = stored.group(1), field = stored.group(2), store = "181:" + owner + "." + field + params[i].getDescriptor();
        if (Arrays.stream(GameCode.instructions(body)).filter(store::equals).count() != 1) {
            return null;
        }
        GameDiff.Cls decl = Exposure.declaring(b, owner, 'f', field, null);
        // The new release must set it before any method runs on every object: no other method stores it, and each
        // constructor stores it (not a null) or hands over to another constructor of the class that does, so it holds
        // the value at the new method's very start too.
        Map<String, String> now = decl == null || code(b) == null ? null : code(b).bodies(decl.name, x -> x);
        String own = "181:" + (decl == null ? "" : decl.name) + "." + field + params[i].getDescriptor();
        if (now == null || now.keySet().stream().noneMatch(k -> k.startsWith("<init>"))) {
            return null;
        }
        for (var e : now.entrySet()) {
            List<String> code = Arrays.asList(GameCode.instructions(e.getValue()));
            int at = code.indexOf(own);
            boolean init = e.getKey().startsWith("<init>");
            boolean delegates = code.stream().anyMatch(x -> x.startsWith("183:" + decl.name + ".<init>("));
            if (!init && at >= 0 || init && !delegates && (at < 0 || at > 0 && code.get(at - 1).equals(String.valueOf(Opcodes.ACONST_NULL)))) {
                return null;
            }
        }
        String step = "f:" + owner + "." + field + ":" + params[i].getDescriptor();
        return decl != null && decl.fields.get(field).desc.equals(params[i].getDescriptor()) && subtype(b, target, owner)
            && openTo(new String[][] { { String.valueOf(THIS), step } }, target) ? step : null;
    }

    /**
     * Whether every member the readings of what this answers (THIS) go through is open to code in the class target (new
     * names): public, declared by target, or protected and declared by one of its superclasses.
     */
    boolean openTo(String[][] via, String target) {
        for (String[] v : via == null ? new String[0][] : via) {
            if (v == null || Integer.parseInt(v[0]) != THIS) {
                continue;
            }
            for (int i = 1; i < v.length; i++) {
                if (v[i].startsWith("x:") || v[i].startsWith("k:")) {
                    continue;
                }
                String[] st = Retype.step(v[i]);
                GameDiff.Cls decl = Exposure.declaring(b, st[1], st[0].charAt(0), st[2], st[0].equals("m") ? st[3] : null);
                int flags = decl == null ? -1 : st[0].equals("m") ? decl.methods.get(st[2] + st[3]).flags : decl.fields.get(st[2]).flags;
                boolean open = flags >= 0 && ((flags & Opcodes.ACC_PUBLIC) != 0 || decl.name.equals(target)
                    || (flags & Opcodes.ACC_PROTECTED) != 0 && subtype(b, target, decl.name));
                if (!open) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Whether a handler may cancel the method it injects into: a cancellable @Inject, or a CallbackInfo parameter marked
     * with MixinExtras' @Cancellable. Moved into a helper (deeper), its cancel would end only the helper, and the method
     * the mod meant to stop would run on.
     */
    boolean cancels(Mod mod, String key, GameDiff.RawMeth handler, Injector inj) {
        if (inj.cancellable()[0]) {
            return true;
        }
        byte[] bytes = mod.bytes.get(key.substring(0, key.indexOf('.')));
        boolean[] marked = new boolean[1];
        if (bytes != null) {
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    return !name.equals(handler.name) || !desc.equals(handler.desc) ? null : new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitParameterAnnotation(int parameter, String d, boolean visible) {
                            marked[0] |= d.equals(SUGAR + "Cancellable;");
                            return null;
                        }
                    };
                }
            }, ClassReader.SKIP_CODE);
        }
        return marked[0];
    }

    /** The first CallbackInfo parameter of a handler, or -1. */
    static int callbackAt(Type[] params) {
        for (int i = 0; i < params.length; i++) {
            if (params[i].getSort() == Type.OBJECT && CALLBACKS.contains(params[i].getInternalName())) {
                return i;
            }
        }
        return -1;
    }

    static final String SUGAR = "Lcom/llamalad7/mixinextras/sugar/";

    /**
     * Whether every parameter of a handler after its CallbackInfo (at ci) is MixinExtras sugar that still finds what it
     * names in the target's new signature: @Share and @Cancellable name nothing of it; a @Local of a type that is one of
     * the target's parameters, once in the old signature and once in the new one, and of no local the new method stores,
     * took that parameter (or says argsOnly).
     */
    boolean sugarFollows(Mod mod, String key, GameDiff.RawMeth handler, int ci, Found into, Injector inj, List<Found> ats) {
        String owner = key.substring(0, key.indexOf('.'));
        byte[] bytes = mod.bytes.get(owner);
        GameDiff.Meth before = oldMethod(mod.mixinTargets.getOrDefault(owner, List.of()), inj.methods().get(0));
        GameDiff.Meth after = into.desc() == null ? null : b.method(into.owner(), into.name(), into.desc());
        if (bytes == null || before == null || after == null) {
            return false;
        }
        Type[] params = Type.getArgumentTypes(handler.desc);
        String[] sugar = new String[params.length];
        boolean[] argsOnly = new boolean[params.length];
        boolean[] picked = new boolean[params.length];
        int[] ordinals = new int[params.length];
        Arrays.fill(ordinals, -1);
        boolean[] indexed = new boolean[params.length];
        String[] generic = new String[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (!name.equals(handler.name) || !desc.equals(handler.desc)) {
                    return null;
                }
                generic[0] = signature;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitParameterAnnotation(int parameter, String d, boolean visible) {
                        if (parameter < sugar.length && d.startsWith(SUGAR)) {
                            sugar[parameter] = d;
                        }
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String n, Object value) {
                                if (parameter < sugar.length && n.equals("argsOnly")) {
                                    argsOnly[parameter] = Boolean.TRUE.equals(value);
                                } else if (parameter < sugar.length && (n.equals("ordinal") || n.equals("index"))) {
                                    picked[parameter] = true;
                                    if (n.equals("index")) {
                                        indexed[parameter] = true;
                                    } else if (value instanceof Integer o) {
                                        ordinals[parameter] = o;
                                    }
                                }
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE);
        List<Type> was = Arrays.stream(Type.getArgumentTypes(desc(before.desc))).toList(), now = Arrays.asList(Type.getArgumentTypes(after.desc));
        GameCode code = code(b);
        Set<Integer> stores = code == null ? null : code.localStores(into.owner(), into.name(), into.desc());
        for (int i = ci + 1; i < params.length; i++) {
            if (sugar[i] == null) {
                return false;
            }
            if (sugar[i].equals(SUGAR + "Share;") || sugar[i].equals(SUGAR + "Cancellable;")) {
                continue;
            }
            // A LocalRef stands for a local of the type it holds, which a reference to it reads and writes.
            String held = params[i].getSort() == Type.OBJECT ? localRef(params[i].getInternalName(), generic[0], i) : null;
            Type t = Type.getType(desc(held != null ? held : params[i].getDescriptor()));
            if (!sugar[i].equals(SUGAR + "Local;") || argsOnly[i] && (picked[i] || Collections.frequency(now, t) != 1)
                || held == null && params[i].getSort() == Type.OBJECT && params[i].getInternalName().startsWith(SUGAR.substring(1) + "ref/")) {
                return false;
            }
            if (argsOnly[i]) {
                continue;
            }
            // Without argsOnly a @Local also sees the method's own locals, so one the new method stores of that kind
            // could make the capture ambiguous (reference locals are not told apart by type here).
            boolean param = !picked[i] && Collections.frequency(was, t) == 1 && Collections.frequency(now, t) == 1
                && stores != null && !stores.contains(t.getOpcode(Opcodes.ISTORE));
            if (!param && !localFound(key, before, after, inj, ats, t, ordinals[i], indexed[i])) {
                return false;
            }
        }
        return true;
    }

    static final Map<String, String> PRIMITIVE_REFS = Map.of("LocalBooleanRef", "Z", "LocalByteRef", "B", "LocalCharRef", "C", "LocalShortRef", "S",
        "LocalIntRef", "I", "LocalLongRef", "J", "LocalFloatRef", "F", "LocalDoubleRef", "D");

    /**
     * The descriptor of the local a MixinExtras LocalRef parameter (the i-th of a handler) stands for: its primitive kind
     * (LocalIntRef), or the type argument of LocalRef in the handler's generic signature. Null for any other type, or a
     * LocalRef whose type cannot be read.
     */
    static String localRef(String internal, String signature, int param) {
        String prefix = SUGAR.substring(1) + "ref/";
        if (!internal.startsWith(prefix)) {
            return null;
        }
        String kind = internal.substring(prefix.length());
        if (PRIMITIVE_REFS.containsKey(kind)) {
            return PRIMITIVE_REFS.get(kind);
        }
        if (!kind.equals("LocalRef") || signature == null) {
            return null;
        }
        String[] found = new String[1];
        int[] at = { -1 };
        new org.objectweb.asm.signature.SignatureReader(signature).accept(new org.objectweb.asm.signature.SignatureVisitor(Opcodes.ASM9) {
            int depth; // 0: a parameter's own type, 1: its type argument

            @Override
            public org.objectweb.asm.signature.SignatureVisitor visitParameterType() {
                at[0]++;
                depth = 0;
                return this;
            }

            @Override
            public org.objectweb.asm.signature.SignatureVisitor visitReturnType() {
                at[0] = Integer.MIN_VALUE;
                return this;
            }

            @Override
            public void visitClassType(String name) {
                if (at[0] == param && depth == 1 && found[0] == null) {
                    found[0] = "L" + name + ";";
                }
            }

            @Override
            public org.objectweb.asm.signature.SignatureVisitor visitTypeArgument(char wildcard) {
                depth++;
                return this;
            }

            @Override
            public void visitEnd() {
                depth = Math.max(0, depth - 1);
            }
        });
        return found[0];
    }

    /**
     * Whether a @Local of type t (without argsOnly) finds the same local in a target's new method as in its old one, at
     * every place each @At finds: the locals there are the method's arguments, then the live locals the game's own frames
     * give (GameCode.localsAt), as Mixin reads them. Without an ordinal the one local of that type, in both methods; with
     * one, the arguments of that type are as many in both and so are the other locals, so the nth is the same one. A
     * @Local by slot index, or of a type Mixin's analysis does not tell apart (an int-like type, a LocalRef), is not
     * followed.
     */
    boolean localFound(String key, GameDiff.Meth before, GameDiff.Meth after, Injector inj, List<Found> ats, Type t, int ordinal, boolean indexed) {
        GameCode oldCode = code(a), newCode = code(b);
        // An int-like local reads as I where the method has no local variable table, so a boolean one is found only where
        // the table says boolean, as Mixin's reading would.
        if (indexed || oldCode == null || newCode == null) {
            why(key, "S1 a @Local by index: " + t);
            return false;
        }
        String d = t.getDescriptor();
        long argsWas = Arrays.stream(Type.getArgumentTypes(desc(before.desc))).map(Type::getDescriptor).filter(d::equals).count();
        long argsNow = Arrays.stream(Type.getArgumentTypes(after.desc)).map(Type::getDescriptor).filter(d::equals).count();
        for (int i = 0; i < inj.ats().size(); i++) {
            String[] point = inj.ats().get(i);
            String[] was = null, now = null;
            if (point[1] != null) {
                Matcher m = MEMBER.matcher(point[1]);
                Found at = ats.get(i);
                if (!m.matches() || m.group(1) == null || at == null || at.owner() == null || at.name() == null) {
                    why(key, "S2 an @At without its owner");
                    return false;
                }
                was = new String[] { m.group(1), m.group(2), m.group(3) != null ? m.group(3) : m.group(4) };
                now = new String[] { at.owner(), at.name(), at.desc() };
            }
            List<List<String>> old = oldCode.localsAt(before.owner.name, before.name, before.desc, point[0], was, point[2]);
            List<List<String>> fresh = newCode.localsAt(after.owner.name, after.name, after.desc, point[0], now, point[2]);
            if (old == null || fresh == null || old.isEmpty() || fresh.isEmpty()) {
                why(key, "S3 locals not read at " + point[0] + " " + point[1] + (old == null ? " old" : fresh == null ? " new" : " none"));
                return false;
            }
            Set<Long> counts = new HashSet<>();
            for (List<String> l : old) {
                counts.add(l.stream().map(this::desc).filter(d::equals).count());
            }
            Set<Long> countsNow = new HashSet<>();
            for (List<String> l : fresh) {
                countsNow.add(l.stream().filter(d::equals).count());
            }
            boolean same = counts.size() == 1 && counts.equals(countsNow);
            long locals = same ? counts.iterator().next() : -1;
            if (ordinal < 0 ? !same || argsWas + locals != 1 || argsNow != argsWas : !same || argsNow != argsWas || argsWas + locals <= ordinal) {
                why(key, "S4 the @Local's " + d + " is not the same local: arguments " + argsWas + " -> " + argsNow + ", locals " + counts + " -> " + countsNow);
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the locals a handler captures after its CallbackInfo (at ci; LocalCapture) are the ones Mixin hands it in
     * the target's new method: at every place each @At finds in the old method they are the first locals there (so the
     * reading is Mixin's), and at every place in the new method the first locals have the same types (GameCode.localsAt,
     * from the game's own frames). The handler must take all of the target's old arguments, as Mixin asks of one that
     * captures locals.
     */
    boolean localsFollow(Mod mod, String key, GameDiff.RawMeth handler, int ci, Found into, Injector inj, List<Found> ats) {
        String owner = key.substring(0, key.indexOf('.'));
        GameDiff.Meth before = oldMethod(mod.mixinTargets.getOrDefault(owner, List.of()), inj.methods().get(0));
        GameDiff.Meth after = into.desc() == null ? null : b.method(into.owner(), into.name(), into.desc());
        GameCode oldCode = code(a), newCode = code(b);
        if (before == null || after == null || oldCode == null || newCode == null || ci != Type.getArgumentTypes(desc(before.desc)).length) {
            why(key, "L1 not every argument of the target, or a target not read");
            return false;
        }
        Type[] params = Type.getArgumentTypes(handler.desc);
        List<String> wanted = Arrays.stream(params, ci + 1, params.length).map(Type::getDescriptor).toList();
        for (int i = 0; i < inj.ats().size(); i++) {
            String[] point = inj.ats().get(i);
            String[] was = null, now = null;
            if (point[1] != null) {
                Matcher m = MEMBER.matcher(point[1]);
                Found at = ats.get(i);
                if (!m.matches() || m.group(1) == null || at == null || at.owner() == null || at.name() == null) {
                    why(key, "L2 an @At without its owner");
                    return false;
                }
                was = new String[] { m.group(1), m.group(2), m.group(3) != null ? m.group(3) : m.group(4) };
                now = new String[] { at.owner(), at.name(), at.desc() };
            }
            List<List<String>> old = oldCode.localsAt(before.owner.name, before.name, before.desc, point[0], was, point[2]);
            List<List<String>> fresh = newCode.localsAt(after.owner.name, after.name, after.desc, point[0], now, point[2]);
            if (old == null || fresh == null || old.isEmpty() || fresh.isEmpty()) {
                why(key, "L3 locals not read at " + point[0] + " " + point[1] + (old == null ? " old" : fresh == null ? " new" : " none"));
                return false;
            }
            for (List<String> l : old) {
                if (l.size() < wanted.size() || !intLike(l.subList(0, wanted.size()), wanted)) {
                    why(key, "L4 the old method's locals are not the handler's " + l);
                    return false;
                }
            }
            List<String> mapped = old.get(0).subList(0, wanted.size()).stream().map(this::desc).toList();
            for (List<String> l : fresh) {
                if (l.size() < wanted.size() || !l.subList(0, wanted.size()).equals(mapped)) {
                    why(key, "L5 the new method's locals differ " + l + " for " + mapped);
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether locals as Mixin's analysis reads them (an int-like local as I) are the handler's types. */
    static boolean intLike(List<String> read, List<String> handler) {
        for (int i = 0; i < read.size(); i++) {
            String r = read.get(i), h = handler.get(i);
            if (!r.equals(h) && !(r.equals("I") && Set.of("Z", "B", "C", "S").contains(h))) {
                return false;
            }
        }
        return true;
    }

    void retarget(Mod mod, String key, List<String> targets, GameDiff.RawMeth handler, Injector inj) {
        List<Found> into = inj.methods().stream().map(s -> find(targets, "method", s)).toList();
        // An @Inject takes only a place, not a call's arguments: an @At at a call the game replaced finds its place at the
        // replacement (font.draw(pose, ...) is graphics.drawString(font, ...) in the new method).
        List<Found> ats = inj.ats().stream().map(at -> at[1] == null ? null : replacedAt(at, find(targets, "target", at[1]))).toList();
        into = Objects.requireNonNullElse(deeper(targets, inj, into, ats, cancels(mod, key, handler, inj)), into);
        boolean moving = into.stream().anyMatch(f -> f.kind() == 's');
        if (moving) {
            Type[] params = Type.getArgumentTypes(handler.desc);
            int captured = params.length - 1;
            // MixinExtras' sugar after the CallbackInfo (@Local, @Share, @Cancellable) is resolved by Mixin, not taken from
            // the target's arguments: a handler with only that after its CallbackInfo follows as one without it does.
            int ci = callbackAt(params);
            if (ci >= 0 && ci < captured && into.size() == 1 && sugarFollows(mod, key, handler, ci, into.get(0), inj, ats)) {
                captured = ci;
            } else if (ci >= 0 && ci < captured && into.size() == 1 && localsFollow(mod, key, handler, ci, into.get(0), inj, ats)) {
                // Locals captured after the CallbackInfo (LocalCapture) are the ones Mixin hands the handler in the new
                // method too: they pass through as they are, and only the target's arguments change.
                captured = ci;
            }
            if (captured < 0 || params[captured].getSort() != Type.OBJECT || !CALLBACKS.contains(params[captured].getInternalName())) {
                { why(key, "I6 locals captured after the CallbackInfo, or no CallbackInfo at all"); return; } // locals captured after the CallbackInfo, or no CallbackInfo at all
            }
            if (captured > 0 && into.size() != 1) {
                { why(key, "I7"); return; }
            }
            for (int i = 0; i < into.size(); i++) {
                Found f = into.get(i);
                if (f.kind() == 's') {
                    String old = inj.methods().get(i);
                    GameDiff.Meth before = oldMethod(targets, old);
                    GameDiff.Meth after = f.desc() == null ? null : b.method(f.owner(), f.name(), f.desc());
                    if (before == null || after == null || !Type.getReturnType(desc(before.desc)).equals(Type.getReturnType(after.desc))
                        || ((before.flags ^ after.flags) & Opcodes.ACC_STATIC) != 0 && !staticizes(mod, key, handler, before, after)) {
                        { why(key, before == null ? "I8-old" : after == null ? "I8-new" : ((before.flags ^ after.flags) & Opcodes.ACC_STATIC) != 0 ? "I8-static" : "I8-result"); return; }
                    }
                }
            }
            if (!pointsKept(targets, inj, into, ats)) {
                { why(key, "I9"); return; }
            }
            Found f = into.get(0);
            if (captured > 0) {
                GameDiff.Meth before = oldMethod(targets, inj.methods().get(0));
                Type[] now = Type.getArgumentTypes(f.desc());
                Type[] was = Arrays.copyOf(Type.getArgumentTypes(desc(handler.desc)), captured);
                if (!Arrays.equals(was, Arrays.copyOf(Type.getArgumentTypes(desc(before.desc)), captured))) {
                    { why(key, "I10 not the target's own arguments"); return; } // not the target's own arguments
                }
                boolean[] unread = unread(mod, key.substring(0, key.indexOf('.')), handler.name, handler.desc, (handler.flags & Opcodes.ACC_STATIC) != 0, captured);
                int[] from = convert(now, was, unread);
                String[][] via = new String[captured][];
                if (from == null) {
                    // Arguments that changed type: worked out from the new ones as the game's own overrides do (Retype).
                    int[] paired = pair(now, was);
                    String[][] read = retype.conversions(before.owner.name, before.name, before.desc, new String[] { f.owner(), f.name(), f.desc() }, paired);
                    // A handler merged into its target runs on the target object, so what that object answers (Screen.init's
                    // Minecraft, now this.minecraft) is read there, where the members read are open to the target's code.
                    String[][] learned = (handler.flags & Opcodes.ACC_STATIC) == 0 && !mod.staticized.contains(key) && openTo(read, f.owner()) ? read : Retype.withoutThis(read);
                    from = new int[captured];
                    for (int i = 0; i < captured; i++) {
                        if (paired[i] >= 0) {
                            from[i] = paired[i];
                        } else if (learned != null && learned[i] != null) {
                            from[i] = Integer.parseInt(learned[i][0]);
                            via[i] = Arrays.copyOfRange(learned[i], 1, learned[i].length);
                        } else if (unread != null && unread[i]) {
                            from[i] = -1;
                        } else if ((handler.flags & Opcodes.ACC_STATIC) == 0 && !mod.staticized.contains(key) && kept(before, i, f.owner()) != null) {
                            from[i] = THIS;
                            via[i] = new String[] { kept(before, i, f.owner()) };
                        } else {
                            { why(key, "I11"); return; }
                        }
                    }
                }
                // The CallbackInfo and what follows it (sugar, captured locals) pass through as they are.
                int rest = params.length - captured;
                Type[] desc = Arrays.copyOf(now, now.length + rest);
                for (int i = 0; i < rest; i++) {
                    desc[now.length + i] = Type.getType(desc(params[captured + i].getDescriptor()));
                }
                if (Arrays.stream(via).anyMatch(Objects::nonNull) && (mod.bytes.get(key.substring(0, key.indexOf('.'))) != null)
                    && (new ClassReader(mod.bytes.get(key.substring(0, key.indexOf('.')))).readShort(6) & 0xFFFF) >= Opcodes.V1_6) {
                    // An argument that changed type: the handler's body moves whole to the new parameters, as an override's
                    // does, so the calls it makes on that argument get what the game passes now (Sites.given, onOrigin).
                    int[] all = Arrays.copyOf(from, captured + rest);
                    for (int i = 0; i < rest; i++) {
                        all[captured + i] = now.length + i;
                    }
                    mod.bridges.computeIfAbsent(key.substring(0, key.indexOf('.')), k -> new ArrayList<>())
                        .add(new Bridge(handler.name, Type.getMethodDescriptor(Type.VOID_TYPE, desc), handler.name, handler.desc, all, handler.flags, Arrays.copyOf(via, captured + rest)));
                } else {
                    mod.adapters.put(key, new Adapter(Type.getMethodDescriptor(Type.VOID_TYPE, desc), from, captured, via));
                }
            }
            for (int i = 0; i < into.size(); i++) {
                if (into.get(i).kind() == 's') {
                    mod.retargets.put(key + " " + inj.methods().get(i), into.get(i).changed());
                }
            }
        }
        // @At targets that only took other parameters, in methods that are themselves known in the new release.
        List<GameDiff.Meth[]> methods = enclosing(targets, inj, into);
        for (int i = 0; i < ats.size(); i++) {
            Found at = ats.get(i);
            int k = i;
            if (at != null && (at.kind() == 's' || at.kind() == 'r') && at.desc() != null && methods != null && methods.stream().allMatch(m -> pointKept(inj.ats().get(k), at, m))) {
                mod.retargets.put(key + " " + inj.ats().get(i)[1], at.changed());
            }
        }
    }

    /**
     * Each mixin class's config's injectors.defaultRequire (0 when the config gives none, as Mixin takes it), read from
     * every mixin config in the jar: a JSON file naming a package and its mixin classes.
     */
    static final byte[] PACKAGE_KEY = "package".getBytes(StandardCharsets.ISO_8859_1), ESCAPE = "\\u".getBytes(StandardCharsets.ISO_8859_1);

    static void requires(Mod mod, Map<String, byte[]> entries) {
        for (var e : entries.entrySet()) {
            // Only a file that can hold the key "package" is parsed: parsing every data file was a tenth of the port's time.
            if (!e.getKey().endsWith(".json") || e.getKey().contains("/lang/")
                || indexOf(e.getValue(), PACKAGE_KEY) < 0 && indexOf(e.getValue(), ESCAPE) < 0) {
                continue;
            }
            Map<String, Object> config;
            try {
                config = Json.object(Json.parse(new String(e.getValue(), StandardCharsets.UTF_8)));
            } catch (RuntimeException notJson) {
                continue;
            }
            Object pkg = config.get("package");
            if (!(pkg instanceof String p)) {
                continue;
            }
            Object injectors = config.get("injectors");
            int require = injectors instanceof Map<?, ?> m && m.get("defaultRequire") instanceof Number n ? n.intValue() : 0;
            for (String list : List.of("mixins", "client", "server")) {
                for (Object c : config.get(list) instanceof List<?> l ? l : List.of()) {
                    if (c instanceof String name) {
                        mod.requires.put((p + "." + name).replace('.', '/'), require);
                    }
                }
            }
        }
    }

    /**
     * An injector into a method that still links, whose @At names a call or field that method made in the old release and
     * no longer makes in the new one (or not as often as its ordinal needs), finds no place there: Mixin scans 0 targets
     * and, with the require most mods set, stops the game (1.21.2's LoadingOverlay.render stopped calling
     * RenderSystem.blendFunc). Reported for every injector, not only the ones retarget moves.
     */
    static final String MODIFY_VARIABLE = "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;";

    /** How many loads (or stores) of a local of type t a method's own code makes (lambdas apart); -1 when it cannot be read. */
    int locals(GameCode code, GameDiff.Meth m, boolean store, Type t) {
        int[] counts = code == null ? null : code.locals(m.owner.name, m.name, m.desc);
        if (counts == null) {
            return -1;
        }
        int op = t.getOpcode(store ? Opcodes.ISTORE : Opcodes.ILOAD);
        return counts[op] + params(m, t);
    }

    /**
     * The parameters (and receiver) of m that are locals a @ModifyVariable of type t could pick, by the instruction that
     * loads them: Mixin's ordinal counts them with the body's own locals (Yeetus Experimentus' "STORE, ordinal 3" boolean
     * of loadLevel was its fourth boolean after three boolean parameters), so leaving them out let a target that lost
     * them pass.
     */
    static int params(GameDiff.Meth m, Type t) {
        int op = t.getOpcode(Opcodes.ILOAD);
        int n = !m.isStatic() && op == Opcodes.ALOAD ? 1 : 0;
        for (Type p : Type.getArgumentTypes(m.desc)) {
            if (p.getOpcode(Opcodes.ILOAD) == op) {
                n++;
            }
        }
        return n;
    }

    void pointsGone(Mod mod, String mixin, String key, List<String> targets, Injector inj, String handlerDesc) {
        for (String text : inj.methods()) {
            List<List<GameDiff.Meth>> into = mod.retargets.containsKey(key + " " + text) ? null : selected(targets, text);
            if (into == null) {
                continue;
            }
            List<GameDiff.Meth> before = into.get(0), now = into.get(1);
            for (String[] point : inj.ats()) {
                String gone = null;
                int needed = point[2] == null ? 1 : Integer.parseInt(point[2]) + 1;
                // A @ModifyVariable's ordinal is its own (the nth local of its type); older handlers gave it on the @At.
                String nth = inj.kind().equals(MODIFY_VARIABLE) && inj.ordinal()[0] >= 0 ? Integer.toString(inj.ordinal()[0]) : point[2];
                if (point[0] != null && Set.of("LOAD", "STORE").contains(point[0]) && inj.kind().equals(MODIFY_VARIABLE) && nth != null) {
                    // @ModifyVariable at a load or store of its nth local of its type: the new method must still have
                    // that many of them at all (an upper bound, so a place it reports gone is gone).
                    int wanted = Integer.parseInt(nth) + 1;
                    Type local = Type.getReturnType(handlerDesc);
                    boolean store = point[0].equals("STORE");
                    int had = most(before, m -> locals(code(a), m, store, local)), has = most(now, m -> locals(code(b), m, store, local));
                    if (had >= wanted && has >= 0 && has < wanted) {
                        gone = point[0] + " #" + nth + " of a " + local.getClassName() + " local";
                    }
                } else if (point[0] != null && point[1] != null && Set.of("INVOKE", "INVOKE_ASSIGN", "FIELD").contains(point[0])) {
                    Found at = find(targets, "target", point[1]);
                    Matcher m = MEMBER.matcher(point[1]);
                    if ((at.kind() != 'm' && at.kind() != 'f') || !m.matches()) {
                        continue;
                    }
                    int had = most(before, x -> uses(code(a), a, x, at.kind(), m.group(1), m.group(2), m.group(3) != null ? m.group(3) : m.group(4)));
                    int has = most(now, x -> uses(code(b), b, x, at.kind(), at.owner(), at.name(), at.desc()));
                    if (had >= needed && has >= 0 && has < needed && !moveAt(mod, key, inj, point, at, m, before, now, false)) {
                        gone = Exposure.dotted(at.owner()) + "." + at.name() + (at.desc() == null ? "" : at.desc());
                    } else if (point[2] != null && had >= needed && has >= needed && had != has) {
                        // Still enough of them, but not as many: the ordinal-th may now be another one.
                        moveAt(mod, key, inj, point, at, m, before, now, true);
                    }
                }
                if (gone != null) {
                    // Mixin stops the game only where the injector requires a place (its require, or its config's
                    // defaultRequire); otherwise the hook is quiet, as an override the game no longer calls is.
                    int require = inj.require()[0] >= 0 ? inj.require()[0] : mod.requires.getOrDefault(mixin, 0);
                    String where = now.size() == 1 ? Exposure.dotted(now.get(0).owner.name) + "." + now.get(0).name + now.get(0).desc : text;
                    gone += " in " + where + " (in " + Exposure.dotted(mixin) + ")";
                    (require > 0 ? mod.unlinked : mod.silent).add((require > 0 ? "mixin injection point gone: " : "mixin injection finds no place: ") + gone);
                }
            }
        }
    }

    /**
     * An @At at a call or field its method no longer makes, moved to what the game's new code has in the same place
     * (GameCode.slot): the same instruction at another ordinal (any injector); another signature of the same call, for an
     * @Inject, whose handler takes a place, not the call; another method of the same class and signature (any injector, as
     * its handler's shape is the call's); a field now read or written through the one method of its class that does it,
     * as a call of that method (any injector: a field's redirect and its accessor's take the same values). The @At must
     * select one method on each side, and where it gives no ordinal the new code must have the new point as often as the
     * old code had the old one. Whether it moved; otherwise the point is reported, and GameCode.slot says what the game did.
     * kept: the ordinal still finds a place, but the method makes the call a different number of times now, so the
     * ordinal-th may be another one; it moves to the one the old one lines up with (any injector), and nothing is reported.
     */
    boolean moveAt(Mod mod, String key, Injector inj, String[] point, Found at, Matcher target, List<GameDiff.Meth> before, List<GameDiff.Meth> now, boolean kept) {
        if (before.size() != 1 || now.size() != 1 || point[0].equals("INVOKE_ASSIGN") && at.kind() == 'f') {
            return false;
        }
        GameDiff.Meth was = before.get(0), is = now.get(0);
        int ordinal = point[2] == null ? -1 : Integer.parseInt(point[2]);
        String desc = target.group(3) != null ? target.group(3) : target.group(4);
        GameCode.Slot slot = GameCode.slot(code(a), code(b), this::cls, new String[] { was.owner.name, was.name, was.desc },
            new String[] { is.owner.name, is.name, is.desc }, at.kind(), target.group(1), target.group(2), desc, ordinal);
        String[] x = slot.member();
        if (kept) {
            // The ordinal still finds a place; it moves only where the old one lines up with another one now.
            if (slot.game().equals("ordinal") && slot.ordinal() != ordinal) {
                mod.points.put(key + " " + point[0] + " " + point[1] + " " + point[2], new String[] { point[0], point[1], String.valueOf(slot.ordinal()) });
                return true;
            }
            return false;
        }
        boolean inject = inj.kind().equals(INJECT);
        boolean moves = switch (slot.game()) {
            case "ordinal" -> ordinal >= 0;
            case "overload" -> inject;
            case "renamed", "accessor" -> true;
            default -> false;
        };
        if (!moves || x == null) {
            why(key, "A-" + slot.game() + " " + point[1]);
            return false;
        }
        String value = slot.game().equals("accessor") ? "INVOKE" : point[0];
        // The same instruction keeps the @At's own target, named in the new release as every target is (member).
        String text = slot.game().equals("ordinal") ? point[1] : "L" + x[1] + ";" + x[2] + (x[0].equals("f") ? ":" : "") + x[3];
        if (ordinal < 0) {
            // Without an ordinal the injector takes every place: the new code must have the new point as often.
            int had = uses(code(a), a, was, at.kind(), target.group(1), target.group(2), desc);
            int has = uses(code(b), b, is, x[0].charAt(0), x[1], x[2], x[3], true);
            if (had != has) {
                why(key, "A-count " + point[1]);
                return false;
            }
        }
        mod.points.put(key + " " + point[0] + " " + point[1] + " " + point[2], new String[] { value, text, ordinal < 0 ? null : String.valueOf(slot.ordinal()) });
        return true;
    }

    /** The most any of the methods gives (an injector finds its place if one of the methods it selects has it); -1 when one cannot be read. */
    static int most(List<GameDiff.Meth> methods, java.util.function.ToIntFunction<GameDiff.Meth> count) {
        int best = 0;
        for (GameDiff.Meth m : methods) {
            int n = count.applyAsInt(m);
            if (n < 0) {
                return -1;
            }
            best = Math.max(best, n);
        }
        return best;
    }

    /**
     * The methods a mixin "method" string selects in the targets, {old release's, new release's}, or null when it cannot
     * be read or selects nothing on a side. A plain string names one method; a regular expression ("/^(connect|m_1)$/", or
     * "name=/.../ desc=/.../") selects every method of the target classes whose name (and descriptor) it matches, as
     * Mixin selects them, so a name either release has is found.
     */
    List<List<GameDiff.Meth>> selected(List<String> targets, String text) {
        Matcher regex = Pattern.compile("^(?:name=)?/(.+?)/(?:\\s+desc=/(.+)/)?$").matcher(text.trim());
        if (!regex.matches()) {
            Found into = find(targets, "method", text);
            GameDiff.Meth before = oldMethod(targets, text);
            GameDiff.Meth now = into.kind() == 'm' && into.desc() != null ? b.method(into.owner(), into.name(), into.desc()) : null;
            return now == null || before == null ? null : List.of(List.of(before), List.of(now));
        }
        Pattern name, desc;
        try {
            name = Pattern.compile(regex.group(1));
            desc = regex.group(2) == null ? null : Pattern.compile(regex.group(2));
        } catch (PatternSyntaxException unreadable) {
            return null;
        }
        List<GameDiff.Meth> before = new ArrayList<>(), now = new ArrayList<>();
        for (String t : targets) {
            for (var side : List.of(new Object[] { a, t, before }, new Object[] { b, cls(t), now })) {
                GameDiff.Cls c = ((GameDiff.Snap) side[0]).classes.get((String) side[1]);
                for (GameDiff.Meth m : c == null ? List.<GameDiff.Meth>of() : c.methods.values()) {
                    if (name.matcher(m.name).find() && (desc == null || desc.matcher(m.desc).find())) {
                        @SuppressWarnings("unchecked") List<GameDiff.Meth> into = (List<GameDiff.Meth>) side[2];
                        into.add(m);
                    }
                }
            }
        }
        return before.isEmpty() || now.isEmpty() ? null : List.of(before, now);
    }

    /** Whether every @At of an injector still finds its place in the methods it injects into, in the new release. */
    boolean pointsKept(List<String> targets, Injector inj, List<Found> into, List<Found> ats) {
        List<GameDiff.Meth[]> methods = enclosing(targets, inj, into);
        if (methods == null) {
            why(String.valueOf(inj.methods()), "P-enclosing");
            return false;
        }
        for (int i = 0; i < ats.size(); i++) {
            String value = inj.ats().get(i)[0];
            if (Set.of("HEAD", "RETURN", "TAIL").contains(value)) {
                continue;
            }
            Found at = ats.get(i);
            if (!Set.of("INVOKE", "INVOKE_ASSIGN", "FIELD").contains(value) || at == null || at.kind() == 0 || at.desc() == null) {
                why(value + " " + inj.ats().get(i)[1], "P-at " + (at == null ? "none" : at.kind() == 0 ? "gone" : at.desc() == null ? "no-desc" : "kind"));
                return false;
            }
            int k = i;
            if (!methods.stream().allMatch(m -> pointKept(inj.ats().get(k), at, m))) {
                why(value + " " + inj.ats().get(i)[1] + " ordinal " + inj.ats().get(i)[2], "P-notin");
                return false;
            }
        }
        return true;
    }

    /**
     * An @At at a call the new release no longer has (found names it gone, or gives no new descriptor), as the call the
     * game's own code writes in its place: the route map's rewrite (GuiComponent.fill(pose, ...) -> graphics.fill(...)),
     * or the outermost call of what the game computes there on a retyped parameter (GameDiff.Retyped). Kind 'r', the
     * replacement's text in changed. Anything else is found as it was.
     */
    Found replacedAt(String[] point, Found found) {
        if (!Set.of("INVOKE", "INVOKE_ASSIGN").contains(point[0]) || found == null || found.kind() != 0 && found.desc() != null) {
            return found;
        }
        Matcher m = MEMBER.matcher(point[1]);
        if (!m.matches() || m.group(1) == null || m.group(3) == null) {
            return found;
        }
        String owner = m.group(1), name = m.group(2), desc = m.group(3);
        Decl decl = declaringOld(new Mod(), owner, 'm', name, desc);
        String declOwner = decl == null ? owner : decl.owner();
        List<String[]> calls = new ArrayList<>();
        for (Call c : rewrites.getOrDefault(declOwner + "." + name + desc, List.of())) {
            calls.add(new String[] { c.owner(), c.name(), c.desc() });
        }
        for (RetypedCall c : retypedCalls.getOrDefault(declOwner, List.of())) {
            Matcher outer = Pattern.compile("^\\$(\\d+)\\.(\\w+)\\((.*)\\)$").matcher(c.expr());
            if (!c.name().equals(name) || !c.desc().equals(desc) || !outer.matches() || Integer.parseInt(outer.group(1)) != c.at()) {
                continue;
            }
            String as = Type.getType(c.as()).getInternalName();
            int arity = arguments(outer.group(3));
            List<GameDiff.Meth> fits = named(b, as, outer.group(2)).stream().filter(x -> Type.getArgumentTypes(x.desc).length == arity && !x.isStatic()).toList();
            if (fits.size() == 1) {
                calls.add(new String[] { as, fits.get(0).name, fits.get(0).desc });
            }
        }
        if (calls.size() != 1) {
            return found;
        }
        String[] c = calls.get(0);
        return new Found(point[1], null, null, 'r', c[0], c[1], c[2], "L" + c[0] + ";" + c[1] + c[2]);
    }

    /** How many arguments a call's argument text has, counting commas outside brackets and quotes. */
    static int arguments(String text) {
        if (text.isBlank()) {
            return 0;
        }
        int depth = 0, count = 1;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
                quoted = !quoted;
            } else if (!quoted && (ch == '(' || ch == '[' || ch == '{')) {
                depth++;
            } else if (!quoted && (ch == ')' || ch == ']' || ch == '}')) {
                depth--;
            } else if (!quoted && ch == ',' && depth == 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * Whether an @At ({value, target, ordinal}; at its target in new names) finds its place in a method injected into
     * ({new, old}): the new method's own instructions use the member, and as often as the old one's did when the @At picks
     * one occurrence by its ordinal. A use inside a lambda does not count: Mixin injects into the method's own code.
     */
    boolean pointKept(String[] point, Found at, GameDiff.Meth[] method) {
        char kind = at.kind() == 's' || at.kind() == 'r' ? 'm' : at.kind();
        // Mixin matches a call by the owner its instruction names, so a replacement written by the porter counts only those.
        int now = uses(code(b), b, method[0], kind, at.owner(), at.name(), at.desc(), at.kind() == 'r');
        if (now < 1) {
            return false;
        }
        if (point[2] == null) {
            return true;
        }
        Matcher m = MEMBER.matcher(point[1]);
        if (!m.matches()) {
            return false;
        }
        String owner = m.group(1) != null ? m.group(1) : null;
        int before = uses(code(a), a, method[1], kind, owner, m.group(2), m.group(3) != null ? m.group(3) : m.group(4));
        return before == now && now > Integer.parseInt(point[2]);
    }

    /** The methods an injector's targets name, {new release, old release} each, when all are known on both sides. */
    List<GameDiff.Meth[]> enclosing(List<String> targets, Injector inj, List<Found> into) {
        List<GameDiff.Meth[]> out = new ArrayList<>();
        for (int i = 0; i < into.size(); i++) {
            Found f = into.get(i);
            GameDiff.Meth now = (f.kind() == 'm' || f.kind() == 's') && f.desc() != null ? b.method(f.owner(), f.name(), f.desc()) : null;
            GameDiff.Meth before = oldMethod(targets, inj.methods().get(i));
            if (now == null || before == null) {
                return null;
            }
            out.add(new GameDiff.Meth[] { now, before });
        }
        return out;
    }

    /**
     * How often a method's own instructions (lambdas apart) use a member (owner null: any owner), read from the release's
     * jar; -1 when they cannot be read.
     */
    int uses(GameCode code, GameDiff.Snap s, GameDiff.Meth m, char kind, String owner, String name, String desc) {
        return uses(code, s, m, kind, owner, name, desc, false);
    }

    /** As above; exact counts only instructions naming owner itself, not another class the member resolves to. */
    int uses(GameCode code, GameDiff.Snap s, GameDiff.Meth m, char kind, String owner, String name, String desc, boolean exact) {
        List<String[]> refs = code == null ? null : code.refs(m.owner.name, m.name, m.desc);
        if (refs == null) {
            return -1;
        }
        GameDiff.Cls decl = owner == null ? null : Exposure.declaring(s, owner, kind, name, kind == 'f' ? null : desc);
        int n = 0;
        for (String[] r : refs) {
            if (r[0].charAt(0) != kind || !r[2].equals(name) || desc != null && !desc.equals(r[3])) {
                continue;
            }
            GameDiff.Cls at = Exposure.declaring(s, r[1], kind, r[2], kind == 'f' ? null : r[3]);
            if (owner == null || r[1].equals(owner) || !exact && decl != null && at != null && decl.name.equals(at.name)) {
                n++;
            }
        }
        return n;
    }

    GameCode newCode, oldCode; // read when an injection point is first checked; tests set them

    String origin; // the release the whole port started from: the data pass judges the mod's data ids against it, and with portTo it decides the nested jars carried
    DataIds.Step dataStep; // the data pass of this step: made on first use; tests set it
    boolean codeTried;

    GameCode code(GameDiff.Snap s) {
        if (!codeTried && newCode == null && oldCode == null) {
            codeTried = true;
            try {
                newCode = GameCode.of(b.version);
                oldCode = GameCode.of(a.version);
            } catch (Exception unreadable) {
                newCode = null;
                oldCode = null;
            }
        }
        return s == b ? newCode : oldCode;
    }

    /**
     * One release's own code: a method's instructions in order, in Mojang names, without its lambdas. bytes gives a
     * class file by its name in the jar; pg maps the jar's names to Mojang's (null when they are Mojang's).
     */
    static final class GameCode {
        final java.util.function.Function<String, byte[]> bytes;
        final GameDiff.Names names;
        final Map<String, String> toJar = new HashMap<>();
        final Map<String, Optional<GameDiff.RawCls>> parsed = new HashMap<>();
        final Map<String, byte[]> all; // every class by its jar name, when the whole jar was read

        GameCode(java.util.function.Function<String, byte[]> bytes, VersionMap.Proguard pg) {
            this(bytes, pg, null);
        }

        GameCode(java.util.function.Function<String, byte[]> bytes, VersionMap.Proguard pg, Map<String, byte[]> all) {
            this.bytes = bytes;
            this.all = all;
            names = new GameDiff.Names(pg);
            if (pg != null) {
                pg.classes.forEach((jar, named) -> toJar.put(named, jar));
            }
        }

        static GameCode of(String version) throws Exception {
            Object[] read = READ.get(version);
            if (read == null) {
                READ.putIfAbsent(version, read(version));
                read = READ.get(version);
            }
            @SuppressWarnings("unchecked") Map<String, byte[]> all = (Map<String, byte[]>) read[0];
            return new GameCode(all::get, (VersionMap.Proguard) read[1], all);
        }

        /**
         * A release's classes by name and its mappings, read once per process and only read after: each worker's porter
         * of each step asks for both releases' code, and reading the mappings again was a tenth of a long port.
         */
        static final Map<String, Object[]> READ = new java.util.concurrent.ConcurrentHashMap<>();

        static Object[] read(String version) throws Exception {
            String json = GameDiff.versionJson(version);
            Path jar = GameDiff.verified(GameDiff.download(json, "client"), "client-" + version + ".jar");
            String[] mappings = GameDiff.download(json, "client_mappings");
            VersionMap.Proguard pg = mappings == null ? null
                : VersionMap.Proguard.parse(Files.readString(GameDiff.verified(mappings, "client-" + version + ".txt"), StandardCharsets.UTF_8));
            Map<String, byte[]> all = new HashMap<>();
            for (byte[] c : GameDiff.classes(jar, null)) {
                all.put(new ClassReader(c).getClassName(), c);
            }
            return new Object[] { all, pg };
        }

        /** A box's primitive and the call that unboxes it. */
        static final Map<String, String[]> UNBOX = Map.of("java/lang/Integer", new String[] { "I", "intValue()I" },
            "java/lang/Boolean", new String[] { "Z", "booleanValue()Z" }, "java/lang/Double", new String[] { "D", "doubleValue()D" },
            "java/lang/Float", new String[] { "F", "floatValue()F" }, "java/lang/Long", new String[] { "J", "longValue()J" },
            "java/lang/Short", new String[] { "S", "shortValue()S" }, "java/lang/Byte", new String[] { "B", "byteValue()B" },
            "java/lang/Character", new String[] { "C", "charValue()C" });

        Map<String, Map<String, Map<String, Integer>>> unwraps;

        /**
         * How the game's code gets the value a holder holds: a no-argument call with an erased result, cast right after
         * (holder.value(), option.get()), then unboxed where a primitive is wanted. By the type it gives (a descriptor),
         * then the type the call is made on, then "name()desc cast [unbox]", how often. Mojang names.
         */
        Map<String, Map<String, Map<String, Integer>>> unwraps() {
            if (unwraps != null) {
                return unwraps;
            }
            unwraps = new HashMap<>();
            for (var e : all == null ? Map.<String, byte[]>of().entrySet() : all.entrySet()) {
                String jarOwner = e.getKey();
                new ClassReader(e.getValue()).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            String owner, call, cast; // the last erased call (its owner and step), and it with its cast

                            void add(String type, String steps) {
                                unwraps.computeIfAbsent(type, k -> new HashMap<>()).computeIfAbsent(owner, k -> new HashMap<>()).merge(steps, 1, Integer::sum);
                            }

                            void reset() {
                                owner = call = cast = null;
                            }

                            @Override
                            public void visitMethodInsn(int op, String o, String m, String md, boolean itf) {
                                String[] box = UNBOX.get(o);
                                if (cast != null && box != null && (m + md).equals(box[1])) {
                                    add(box[0], cast + " " + box[1]);
                                }
                                reset();
                                if ((op == Opcodes.INVOKEVIRTUAL || op == Opcodes.INVOKEINTERFACE) && md.equals("()Ljava/lang/Object;")) {
                                    owner = names.cls(o);
                                    call = names.method(o, m, md) + md;
                                }
                            }

                            @Override
                            public void visitTypeInsn(int op, String t) {
                                if (op == Opcodes.CHECKCAST && call != null && !t.startsWith("[")) {
                                    String to = names.cls(t);
                                    cast = call + " " + to;
                                    add("L" + to + ";", cast);
                                    call = null;
                                    return;
                                }
                                reset();
                            }

                            @Override
                            public void visitInsn(int op) {
                                reset();
                            }

                            @Override
                            public void visitIntInsn(int op, int v) {
                                reset();
                            }

                            @Override
                            public void visitVarInsn(int op, int v) {
                                reset();
                            }

                            @Override
                            public void visitFieldInsn(int op, String o, String f, String fd) {
                                reset();
                            }

                            @Override
                            public void visitInvokeDynamicInsn(String n2, String d2, Handle bsm, Object... args) {
                                reset();
                            }

                            @Override
                            public void visitJumpInsn(int op, Label l) {
                                reset();
                            }

                            @Override
                            public void visitLdcInsn(Object v) {
                                reset();
                            }

                            @Override
                            public void visitLabel(Label l) {
                                reset();
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
            return unwraps;
        }

        GameDiff.RawCls raw(String jarName) {
            return parsed.computeIfAbsent(jarName, n -> Optional.ofNullable(bytes.apply(n)).map(GameDiff::parse)).orElse(null);
        }

        /**
         * Every method of a class (Mojang names), synthetic ones too (a lambda's lambda$name$N), as name+desc -> its code as
         * text: one instruction a line with what it names, class names passed through rename (an older release's into the
         * newer one's), so two releases' copies of one lambda read alike. A line break is the only boundary: descriptors
         * hold ';', and constants have their line breaks escaped. Null when the class is not in the jar.
         */
        Map<String, String> bodies(String owner, java.util.function.UnaryOperator<String> rename) {
            String jarOwner = toJar.getOrDefault(owner, owner);
            byte[] file = bytes.apply(jarOwner);
            if (file == null) {
                return null;
            }
            java.util.function.UnaryOperator<String> type = t -> rename.apply(names.cls(t));
            java.util.function.UnaryOperator<String> desc = d -> mapDesc(names.desc(d), null, rename);
            Map<String, String> out = new LinkedHashMap<>();
            new ClassReader(file).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                    String key = names.method(jarOwner, n, d) + desc.apply(d);
                    StringBuilder sb = new StringBuilder();
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitInsn(int op) {
                            sb.append(op).append('\n');
                        }

                        @Override
                        public void visitIntInsn(int op, int v) {
                            sb.append(op).append(':').append(v).append('\n');
                        }

                        @Override
                        public void visitVarInsn(int op, int v) {
                            sb.append(op).append(':').append(v).append('\n');
                        }

                        @Override
                        public void visitTypeInsn(int op, String t) {
                            sb.append(op).append(':').append(type.apply(t)).append('\n');
                        }

                        @Override
                        public void visitFieldInsn(int op, String o, String f, String fd) {
                            sb.append(op).append(':').append(type.apply(o)).append('.').append(names.field(o, f)).append(desc.apply(fd)).append('\n');
                        }

                        @Override
                        public void visitMethodInsn(int op, String o, String m, String md, boolean itf) {
                            sb.append(op).append(':').append(type.apply(o)).append('.').append(names.method(o, m, md)).append(desc.apply(md)).append('\n');
                        }

                        @Override
                        public void visitLdcInsn(Object v) {
                            sb.append("ldc:").append(v instanceof Type t ? desc.apply(t.getDescriptor()) : line(v)).append('\n');
                        }

                        @Override
                        public void visitJumpInsn(int op, Label l) {
                            sb.append(op).append('\n');
                        }

                        @Override
                        public void visitIincInsn(int v, int inc) {
                            sb.append("iinc:").append(v).append(':').append(inc).append('\n');
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String n2, String d2, Handle bsm, Object... args) {
                            sb.append("indy:").append(n2).append(desc.apply(d2)).append(':').append(handle(bsm));
                            for (Object arg : args) {
                                sb.append(':').append(arg instanceof Handle h ? handle(h) : arg instanceof Type t ? desc.apply(t.getDescriptor()) : line(arg));
                            }
                            sb.append('\n');
                        }

                        // A nested lambda is named by its enclosing method, not its number, which a release may change.
                        String handle(Handle h) {
                            return type.apply(h.getOwner()) + "." + names.method(h.getOwner(), h.getName(), h.getDesc()).replaceAll("^(lambda\\$.*\\$)\\d+$", "$1")
                                + desc.apply(h.getDesc());
                        }

                        @Override
                        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
                            sb.append("switch:").append(min).append(':').append(max).append('\n');
                        }

                        @Override
                        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
                            sb.append("switch:").append(Arrays.toString(keys)).append('\n');
                        }

                        @Override
                        public void visitMultiANewArrayInsn(String d2, int dims) {
                            sb.append("multianewarray:").append(desc.apply(d2)).append(':').append(dims).append('\n');
                        }

                        @Override
                        public void visitEnd() {
                            out.put(key, sb.toString());
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return out;
        }

        /**
         * The store instructions (ISTORE, ASTORE, ...) a method uses for its own locals, beyond its parameters; null when
         * it cannot be read.
         */
        /** A constant as it reads on one line of a body. */
        static String line(Object v) {
            return String.valueOf(v).replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
        }

        /** A body's instructions, as bodies writes them. */
        static String[] instructions(String body) {
            return body.isEmpty() ? new String[0] : body.split("\n");
        }

        Set<Integer> localStores(String owner, String name, String desc) {
            String jarOwner = toJar.getOrDefault(owner, owner);
            byte[] file = bytes.apply(jarOwner);
            if (file == null) {
                return null;
            }
            Set<Integer> out = new HashSet<>();
            boolean[] seen = { false };
            new ClassReader(file).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                    if (!(names.method(jarOwner, n, d) + names.desc(d)).equals(name + desc)) {
                        return null;
                    }
                    seen[0] = true;
                    int first = (Type.getArgumentsAndReturnSizes(d) >> 2) - ((access & Opcodes.ACC_STATIC) != 0 ? 1 : 0);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitVarInsn(int op, int v) {
                            if (op >= Opcodes.ISTORE && op <= Opcodes.ASTORE && v >= first) {
                                out.add(op);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return seen[0] ? out : null;
        }

        /**
         * The locals Mixin hands a handler that captures them (LocalCapture, or MixinExtras' @Local) at each place an @At
         * finds in a method (this release's names): from the first slot after the arguments, each live local's type in slot
         * order, a long or double once. Where the method has a local variable table (Mojang's jars keep one, with names
         * $$0...) Mixin takes the locals whose range holds the place, with their declared types; without one, it reads
         * them from the frames and the stores after the last one, as AnalyzerAdapter does, an int-like local as I. Places:
         * value HEAD, RETURN or TAIL, or a call (INVOKE, INVOKE_ASSIGN after its result is stored) or field (FIELD) whose
         * owner, name and descriptor are at (Mixin matches the owner its instruction names), the ordinal-th one or every
         * one. Null when the method is not in the jar, a place is of another kind, or a local's type is unknown; an empty
         * list when the @At finds no place.
         */
        List<List<String>> localsAt(String owner, String name, String desc, String value, String[] at, String ordinal) {
            String jarOwner = toJar.getOrDefault(owner, owner);
            byte[] file = bytes.apply(jarOwner);
            if (file == null || !Set.of("HEAD", "RETURN", "TAIL", "INVOKE", "INVOKE_ASSIGN", "FIELD").contains(value)
                || !Set.of("HEAD", "RETURN", "TAIL").contains(value) && at == null) {
                return null;
            }
            List<List<String>> out = new ArrayList<>();
            boolean[] seen = { false }, unknown = { false };
            new ClassReader(file).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                    if (seen[0] || !(names.method(jarOwner, n, d) + names.desc(d)).equals(name + desc)) {
                        return null;
                    }
                    seen[0] = true;
                    int first = (Type.getArgumentsAndReturnSizes(d) >> 2) - ((access & Opcodes.ACC_STATIC) != 0 ? 1 : 0);
                    org.objectweb.asm.commons.AnalyzerAdapter frames = new org.objectweb.asm.commons.AnalyzerAdapter(jarOwner, access, n, d, null);
                    // Each place: the instruction it is at (its index), and the locals the frames give there.
                    List<Object[]> places = new ArrayList<>(), returns = new ArrayList<>();
                    Map<Label, Integer> labels = new HashMap<>();
                    List<Object[]> table = new ArrayList<>(); // slot, descriptor, start, end
                    return new MethodVisitor(Opcodes.ASM9, frames) {
                        int index, count; // the instruction about to be read; the @At's matches so far
                        boolean assign; // an INVOKE_ASSIGN place: taken after the store that follows the call, or after the call

                        Object[] place() {
                            List<String> types = new ArrayList<>();
                            List<Object> locals = frames.locals;
                            if (locals == null) {
                                return new Object[] { index, null };
                            }
                            for (int i = first; i < locals.size(); i++) {
                                Object v = locals.get(i);
                                if (v == Opcodes.TOP || v instanceof Label || v == Opcodes.UNINITIALIZED_THIS) {
                                    continue;
                                }
                                types.add(v == Opcodes.NULL ? null : v == Opcodes.INTEGER ? "I" : v == Opcodes.FLOAT ? "F" : v == Opcodes.LONG ? "J"
                                    : v == Opcodes.DOUBLE ? "D" : names.desc(((String) v).startsWith("[") ? (String) v : "L" + v + ";"));
                            }
                            return new Object[] { index, types };
                        }

                        /** Before each instruction: an INVOKE_ASSIGN place waiting for its store is taken here, unless this is it. */
                        void next(boolean store) {
                            if (assign && !store) {
                                assign = false;
                                places.add(place());
                            }
                        }

                        void done() {
                            index++;
                        }

                        boolean picked() {
                            return ordinal == null || count++ == Integer.parseInt(ordinal);
                        }

                        @Override
                        public void visitCode() {
                            super.visitCode();
                            if (value.equals("HEAD")) {
                                places.add(place());
                            }
                        }

                        @Override
                        public void visitInsn(int op) {
                            next(false);
                            if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN && (value.equals("RETURN") || value.equals("TAIL"))) {
                                returns.add(place());
                            }
                            super.visitInsn(op);
                            done();
                        }

                        @Override
                        public void visitVarInsn(int op, int v) {
                            next(op >= Opcodes.ISTORE && op <= Opcodes.ASTORE);
                            super.visitVarInsn(op, v);
                            done();
                        }

                        @Override
                        public void visitMethodInsn(int op, String o, String m, String md, boolean itf) {
                            next(false);
                            boolean match = value.startsWith("INVOKE") && names.cls(o).equals(at[0]) && names.method(o, m, md).equals(at[1]) && names.desc(md).equals(at[2]) && picked();
                            if (match && value.equals("INVOKE")) {
                                places.add(place());
                            }
                            super.visitMethodInsn(op, o, m, md, itf);
                            done();
                            assign = match && value.equals("INVOKE_ASSIGN");
                        }

                        @Override
                        public void visitFieldInsn(int op, String o, String f, String fd) {
                            next(false);
                            if (value.equals("FIELD") && names.cls(o).equals(at[0]) && names.field(o, f).equals(at[1]) && (at[2] == null || names.desc(fd).equals(at[2])) && picked()) {
                                places.add(place());
                            }
                            super.visitFieldInsn(op, o, f, fd);
                            done();
                        }

                        @Override
                        public void visitTypeInsn(int op, String t) {
                            next(false);
                            super.visitTypeInsn(op, t);
                            done();
                        }

                        @Override
                        public void visitIntInsn(int op, int v) {
                            next(false);
                            super.visitIntInsn(op, v);
                            done();
                        }

                        @Override
                        public void visitJumpInsn(int op, Label l) {
                            next(false);
                            super.visitJumpInsn(op, l);
                            done();
                        }

                        @Override
                        public void visitLdcInsn(Object v) {
                            next(false);
                            super.visitLdcInsn(v);
                            done();
                        }

                        @Override
                        public void visitIincInsn(int v, int inc) {
                            next(false);
                            super.visitIincInsn(v, inc);
                            done();
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String n2, String d2, Handle bsm, Object... args) {
                            next(false);
                            super.visitInvokeDynamicInsn(n2, d2, bsm, args);
                            done();
                        }

                        @Override
                        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... ls) {
                            next(false);
                            super.visitTableSwitchInsn(min, max, dflt, ls);
                            done();
                        }

                        @Override
                        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] ls) {
                            next(false);
                            super.visitLookupSwitchInsn(dflt, keys, ls);
                            done();
                        }

                        @Override
                        public void visitMultiANewArrayInsn(String d2, int dims) {
                            next(false);
                            super.visitMultiANewArrayInsn(d2, dims);
                            done();
                        }

                        @Override
                        public void visitLabel(Label l) {
                            next(false);
                            labels.put(l, index);
                            super.visitLabel(l);
                        }

                        @Override
                        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
                            next(false);
                            super.visitFrame(type, numLocal, local, numStack, stack);
                        }

                        @Override
                        public void visitLocalVariable(String ln, String ld, String ls, Label start, Label end, int slot) {
                            table.add(new Object[] { slot, ld, start, end });
                        }

                        @Override
                        public void visitEnd() {
                            next(false);
                            if (value.equals("RETURN")) {
                                places.addAll(returns);
                            } else if (value.equals("TAIL") && !returns.isEmpty()) {
                                places.add(returns.get(returns.size() - 1));
                            }
                            for (Object[] p : places) {
                                int i = (Integer) p[0];
                                @SuppressWarnings("unchecked") List<String> types = (List<String>) p[1];
                                if (!table.isEmpty()) {
                                    types = table.stream().filter(t -> (Integer) t[0] >= first && labels.getOrDefault((Label) t[2], Integer.MAX_VALUE) <= i
                                            && i < labels.getOrDefault((Label) t[3], -1))
                                        .sorted(Comparator.comparingInt(t -> (Integer) t[0])).map(t -> names.desc((String) t[1])).toList();
                                }
                                if (types == null || types.contains(null)) {
                                    unknown[0] = true;
                                } else {
                                    out.add(types);
                                }
                            }
                        }
                    };
                }
            }, ClassReader.EXPAND_FRAMES);
            return !seen[0] || unknown[0] ? null : out;
        }

        /** A descriptor with each class name passed through rename. */
        static String mapDesc(String d, Object unused, java.util.function.UnaryOperator<String> rename) {
            Matcher m = Pattern.compile("L([^;<]+)([;<])").matcher(d);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement("L" + rename.apply(m.group(1)) + m.group(2)));
            }
            return m.appendTail(sb).toString();
        }

        /** The class (jar names) that declares a member a reference names, or the owner when none of its classes does. */
        String declarer(String owner, String name, String desc, boolean field) {
            Deque<String> queue = new ArrayDeque<>(List.of(owner));
            Set<String> seen = new HashSet<>();
            while (!queue.isEmpty()) {
                String c = queue.poll();
                GameDiff.RawCls rc = seen.add(c) ? raw(c) : null;
                if (rc == null) {
                    continue;
                }
                if (field ? rc.fields.containsKey(name) : rc.methods.containsKey(name + desc)) {
                    return c;
                }
                if (rc.sup != null) {
                    queue.add(rc.sup);
                }
                queue.addAll(rc.ifaces);
            }
            return owner;
        }

        /** A method's own loads and stores of locals, counted by opcode (ILOAD..ALOAD, ISTORE..ASTORE); null when it is not in the jar. */
        int[] locals(String owner, String name, String desc) {
            String jarOwner = toJar.getOrDefault(owner, owner);
            byte[] file = bytes.apply(jarOwner);
            if (file == null) {
                return null;
            }
            int[] out = new int[256];
            boolean[] found = new boolean[1];
            new ClassReader(file).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                    if (found[0] || !names.method(jarOwner, n, d).equals(name) || !names.desc(d).equals(desc)) {
                        return null;
                    }
                    found[0] = true;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitVarInsn(int opcode, int var) {
                            out[opcode]++;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return found[0] ? out : null;
        }

        /** {kind, owner, name, desc} of each call and field access in a method's own code; null when it is not in the jar. */
        /**
         * A method's own instructions that name something (this release's Mojang names, lambdas apart), in order: {"m", owner,
         * name, desc} a call, {"f" or "p", ...} a field read or written, {"c", "", constant, ""} a constant, {"n", type, "", ""} a
         * new object, {"l", owner, name, desc} a method a lambda or method reference names. Calls and fields are named as
         * refs names them (by the class that declares them). Null when the method is not in the jar.
         */
        List<String[]> steps(String owner, String name, String desc) {
            String key = owner + "." + name + desc;
            if (stepsRead.containsKey(key)) {
                return stepsRead.get(key);
            }
            String jarOwner = toJar.getOrDefault(owner, owner);
            byte[] file = bytes.apply(jarOwner);
            List<String[]> out = file == null ? null : new ArrayList<>();
            boolean[] found = new boolean[1];
            if (file != null) {
                new ClassReader(file).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                        if (found[0] || !names.method(jarOwner, n, d).equals(name) || !names.desc(d).equals(desc)) {
                            return null;
                        }
                        found[0] = true;
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(int op, String o, String mn, String md, boolean itf) {
                                out.add(new String[] { "m", names.cls(o), names.method(declarer(o, mn, md, false), mn, md), names.desc(md) });
                            }

                            @Override
                            public void visitFieldInsn(int op, String o, String fn, String fd) {
                                boolean read = op == Opcodes.GETFIELD || op == Opcodes.GETSTATIC;
                                out.add(new String[] { read ? "f" : "p", names.cls(o), names.field(declarer(o, fn, fd, true), fn), names.desc(fd) });
                            }

                            @Override
                            public void visitLdcInsn(Object v) {
                                out.add(new String[] { "c", "", v instanceof Type t ? names.desc(t.getDescriptor()) : line(v), "" });
                            }

                            @Override
                            public void visitTypeInsn(int op, String t) {
                                if (op == Opcodes.NEW) {
                                    out.add(new String[] { "n", names.cls(t), "", "" });
                                }
                            }

                            @Override
                            public void visitInvokeDynamicInsn(String n2, String d2, Handle bsm, Object... args) {
                                for (Object arg : args) {
                                    if (arg instanceof Handle h) {
                                        out.add(new String[] { "l", names.cls(h.getOwner()), names.method(h.getOwner(), h.getName(), h.getDesc()), names.desc(h.getDesc()) });
                                    }
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
            List<String[]> result = found[0] ? out : null;
            stepsRead.put(key, result);
            return result;
        }

        final Map<String, List<String[]>> stepsRead = new HashMap<>();
        Map<String, Set<String>> callers;

        /**
         * The methods (owner.name+desc, Mojang names) of the whole release whose own code calls or names (a method
         * reference) each method; read once, and only where the whole jar was read.
         */
        Set<String> callers(String owner, String name, String desc) {
            if (all == null) {
                return null;
            }
            if (callers == null) {
                synchronized (CALLERS) {
                    callers = CALLERS.get(all);
                }
            }
            if (callers == null) {
                Map<String, Set<String>> index = new HashMap<>();
                for (var e : all.entrySet()) {
                    String jarOwner = e.getKey();
                    String self = names.cls(jarOwner);
                    new ClassReader(e.getValue()).accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                            String me = self + "." + names.method(jarOwner, n, d) + names.desc(d);
                            return new MethodVisitor(Opcodes.ASM9) {
                                void add(String o, String mn, String md) {
                                    String callee = names.cls(declarer(o, mn, md, false)) + "." + names.method(declarer(o, mn, md, false), mn, md) + names.desc(md);
                                    index.computeIfAbsent(callee, k -> new HashSet<>()).add(me);
                                }

                                @Override
                                public void visitMethodInsn(int op, String o, String mn, String md, boolean itf) {
                                    add(o, mn, md);
                                }

                                @Override
                                public void visitInvokeDynamicInsn(String n2, String d2, Handle bsm, Object... args) {
                                    for (Object arg : args) {
                                        if (arg instanceof Handle h) {
                                            add(h.getOwner(), h.getName(), h.getDesc());
                                        }
                                    }
                                }
                            };
                        }
                    }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
                callers = index;
                synchronized (CALLERS) {
                    CALLERS.put(all, index);
                }
            }
            return callers.getOrDefault(owner + "." + name + desc, Set.of());
        }

        /** Each release's callers index, by the classes it was read from: every worker's porter reads the same release. */
        static final Map<Map<String, byte[]>, Map<String, Set<String>>> CALLERS = new IdentityHashMap<>();

        /**
         * What a game method's new code has where its old code had an injection point. game says what the game did:
         * "ordinal" (the same instruction, now the ordinal-th of its kind), "overload" (a call of the same method with other
         * parameters), "renamed" (a call of another method of the same class and signature), "accessor" (a field now read
         * or written through the one method of its class that does it), "helper" (moved into a method the new code calls,
         * of the same class or not, in member), "dropped" (nothing in its place) or "not read". member is {kind ('m' or
         * 'f'), owner, name, desc} of what is there now (new names). The old and new code are lined up by what they name
         * (the longest run in common, old class names renamed into new ones): the point's place is the gap between the
         * named instructions around it that line up.
         */
        record Slot(String game, String detail, String[] member, int ordinal) {}

        static final Slot NOT_READ = new Slot("not read", null, null, -1);

        static Slot slot(GameCode before, GameCode after, java.util.function.UnaryOperator<String> rename, String[] oldMethod, String[] newMethod,
                         char kind, String owner, String name, String desc, int ordinal) {
            List<String[]> old = before == null ? null : before.steps(oldMethod[0], oldMethod[1], oldMethod[2]);
            List<String[]> now = after == null ? null : after.steps(newMethod[0], newMethod[1], newMethod[2]);
            if (old == null || now == null || (long) old.size() * now.size() > 4_000_000L) {
                return NOT_READ;
            }
            List<String[]> was = old.stream().map(x -> new String[] { x[0], x[0].equals("c") ? "" : rename.apply(x[1]), x[2], x[0].equals("c") ? "" : mapDesc(x[3], null, rename) }).toList();
            // The point's instructions in the old code: by name and descriptor, and by owner where one names it.
            // Mixin matches the owner an instruction names; where none names the @At's owner, one the member resolves to counts.
            List<Integer> at = new ArrayList<>(), named = new ArrayList<>();
            for (int i = 0; i < old.size(); i++) {
                String[] x = old.get(i);
                if ((kind == 'm' ? x[0].equals("m") : x[0].equals("f") || x[0].equals("p")) && x[2].equals(name) && (desc == null || x[3].equals(desc))) {
                    at.add(i);
                    if (x[1].equals(owner)) {
                        named.add(i);
                    }
                }
            }
            at = named.isEmpty() ? at : named;
            if (at.isEmpty() || ordinal >= at.size()) {
                return NOT_READ;
            }
            int[] map = align(was, now);
            List<Integer> points = ordinal >= 0 ? List.of(at.get(ordinal)) : at;
            Slot found = null;
            for (int p : points) {
                Slot one = place(after, was, now, map, p, oldMethod, newMethod);
                if (found == null || found.game().equals(one.game()) && Arrays.equals(found.member(), one.member())) {
                    found = found == null || one.ordinal() < found.ordinal() || found.ordinal() < 0 ? one : found;
                } else {
                    return new Slot("mixed", found.game() + ", " + one.game(), null, -1);
                }
            }
            return found;
        }

        /** The longest run of named instructions two bodies have in common: for each old one, the new one it lines up with, or -1. */
        static int[] align(List<String[]> old, List<String[]> now) {
            int a = old.size(), b = now.size();
            String[] x = old.stream().map(GameCode::named).toArray(String[]::new), y = now.stream().map(GameCode::named).toArray(String[]::new);
            int[][] dp = new int[a + 1][b + 1];
            for (int i = a - 1; i >= 0; i--) {
                for (int j = b - 1; j >= 0; j--) {
                    dp[i][j] = x[i].equals(y[j]) ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
            int[] map = new int[a];
            Arrays.fill(map, -1);
            int i = 0, j = 0;
            while (i < a && j < b) {
                if (x[i].equals(y[j])) {
                    map[i++] = j++;
                } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                    i++;
                } else {
                    j++;
                }
            }
            return map;
        }

        /** A named instruction as it lines up: a lambda by the method it is in, as javac numbers lambdas anew in each release. */
        static String named(String[] x) {
            return x[0] + " " + x[1] + "." + (x[0].equals("l") ? x[2].replaceAll("\\$\\d+$", "") : x[2]) + x[3];
        }

        static Slot place(GameCode after, List<String[]> was, List<String[]> now, int[] map, int p, String[] oldMethod, String[] newMethod) {
            String[] point = was.get(p);
            char kind = point[0].equals("m") ? 'm' : 'f';
            if (map[p] >= 0) {
                // Still there: the ordinal-th of the instructions that name it now.
                int n = 0;
                for (int j = 0; j < map[p]; j++) {
                    n += named(now.get(j)).equals(named(point)) ? 1 : 0;
                }
                return new Slot("ordinal", "now its #" + n, new String[] { String.valueOf(kind), point[1], point[2], point[3] }, n);
            }
            int lo = -1, hi = now.size();
            for (int i = p - 1; i >= 0; i--) {
                if (map[i] >= 0) {
                    lo = map[i];
                    break;
                }
            }
            for (int i = p + 1; i < was.size(); i++) {
                if (map[i] >= 0) {
                    hi = map[i];
                    break;
                }
            }
            List<String[]> gap = now.subList(lo + 1, Math.max(lo + 1, hi));
            String self = newMethod[0] + "." + newMethod[1] + newMethod[2];
            if (kind == 'f') {
                // A field the new code reaches through a method of its class that reads (or writes) it, ahead of
                // any other method that has it: that method is the field's own way in now (1.21.5's Inventory.setSelectedSlot).
                boolean read = point[0].equals("f");
                String wanted = read ? "()" + point[3] : "(" + point[3] + ")V";
                for (String[] x : gap) {
                    if (x[0].equals("m") && x[1].equals(point[1]) && x[3].equals(wanted)) {
                        List<String[]> inner = after.steps(x[1], x[2], x[3]);
                        if (inner != null && inner.stream().anyMatch(y -> y[0].equals(point[0]) && y[2].equals(point[2]))) {
                            return found(now, x, "accessor", 'm');
                        }
                    }
                }
            }
            // Another signature of the same call in its place (often one that hands over to the old one, as 1.21.4's
            // GuiGraphics.drawString(font, text, x, y, color) does to the one taking a shadow flag).
            for (String[] x : gap) {
                if (x[0].equals(point[0]) && x[1].equals(point[1]) && x[2].equals(point[2]) && !x[3].equals(point[3])) {
                    return found(now, x, "overload", kind);
                }
            }
            // A call of a method whose code has the point: it moved there (Gui.renderPlayerHealth -> renderAirBubbles).
            for (List<String[]> where : List.of(gap, now)) {
                for (String[] x : where) {
                    if (!x[0].equals("m") && !x[0].equals("l") || (x[1] + "." + x[2] + x[3]).equals(self)) {
                        continue;
                    }
                    List<String[]> inner = after.steps(x[1], x[2], x[3]);
                    if (inner != null && inner.stream().anyMatch(y -> y[0].equals(point[0]) && y[2].equals(point[2]) && y[3].equals(point[3]))) {
                        int n = 0;
                        for (String[] y : now) {
                            n += named(y).equals(named(x)) ? 1 : 0;
                        }
                        String detail = (x[1].equals(newMethod[0]) ? "" : "another class: ") + Exposure.dotted(x[1]) + "." + x[2] + x[3] + (where == gap ? "" : ", elsewhere in the method")
                            + (n > 1 ? ", called " + n + " times" : "");
                        return new Slot("helper", detail, new String[] { "m", x[1], x[2], x[3] }, -1);
                    }
                }
            }
            if (kind == 'm') {
                // The one call in its place of another method of the class with its signature.
                List<String[]> like = gap.stream().filter(x -> x[0].equals("m") && x[1].equals(point[1]) && x[3].equals(point[3]) && !x[2].equals(point[2])).toList();
                if (like.size() == 1) {
                    return found(now, like.get(0), "renamed", 'm');
                }
            }
            return new Slot("dropped", gap.isEmpty() ? "nothing in its place" : gap.size() + " other instructions in its place", null, -1);
        }

        /** A slot holding x, with its ordinal among the instructions of the new code that name the same. */
        static Slot found(List<String[]> now, String[] x, String game, char kind) {
            int n = 0;
            for (String[] y : now) {
                if (y == x) {
                    break;
                }
                n += named(y).equals(named(x)) ? 1 : 0;
            }
            return new Slot(game, Exposure.dotted(x[1]) + "." + x[2] + x[3], new String[] { String.valueOf(kind), x[1], x[2], x[3] }, n);
        }

        List<String[]> refs(String owner, String name, String desc) {
            String jarOwner = toJar.getOrDefault(owner, owner);
            byte[] file = bytes.apply(jarOwner);
            if (file == null) {
                return null;
            }
            List<String[]> out = new ArrayList<>();
            boolean[] found = new boolean[1];
            new ClassReader(file).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                    if (found[0] || !names.method(jarOwner, n, d).equals(name) || !names.desc(d).equals(desc)) {
                        return null;
                    }
                    found[0] = true;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String o, String mn, String md, boolean itf) {
                            out.add(new String[] { "m", names.cls(o), names.method(declarer(o, mn, md, false), mn, md), names.desc(md) });
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String o, String fn, String fd) {
                            out.add(new String[] { "f", names.cls(o), names.field(declarer(o, fn, fd, true), fn), names.desc(fd) });
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return found[0] ? out : null;
        }
    }

    final Retype retype = new Retype();

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
                String wanted = desc(was[i].getDescriptor());
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
                if (out[i] != null && Integer.parseInt(out[i][0]) == THIS) {
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
                    for (var e : route.methods.entrySet()) {
                        String k = e.getKey();
                        int dot = k.lastIndexOf('.', k.indexOf('('));
                        String owner = k.substring(0, dot), rest = k.substring(dot + 1), name = rest.substring(0, rest.indexOf('(')), desc = rest.substring(name.length());
                        String[] moved = route.method(owner, name, desc);
                        if (!a.classes.containsKey(owner) || !desc(desc).contains(had) || !moved[2].contains(got) || moved[2].equals(desc(desc))) {
                            continue;
                        }
                        Type[] was = Type.getArgumentTypes(desc), now = Type.getArgumentTypes(moved[2]);
                        conversions(owner, name, desc, moved, pair(now, Arrays.stream(was).map(t -> Type.getType(desc(t.getDescriptor()))).toArray(Type[]::new)));
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
                GameDiff.Cls c = a.classes.get(todo.poll());
                if (c == null || !seen.add(c.name)) {
                    continue;
                }
                if (c.methods.containsKey(name + desc) && subtype(a, top, c.name)) {
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
            GameCode oldCode = code(a), newCode = code(b);
            if (oldCode == null || newCode == null) {
                return null;
            }
            Type[] was = Type.getArgumentTypes(desc), now = Type.getArgumentTypes(moved[2]);
            int[] oldSlots = slots(was), newSlots = slots(now);
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
                String nc = cls(c);
                GameDiff.Cls holder = b.classes.get(nc);
                if (holder == null || !holder.methods.containsKey(moved[1] + moved[2])) {
                    continue;
                }
                GameDiff.Body x = body(oldCode, c, name + desc), y = body(newCode, nc, moved[1] + moved[2]);
                if (x != null && y != null) {
                    new Lining(holes, same).pair(x, y, (i, m) -> {
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
                    if (parts.length == 2 && Integer.parseInt(parts[0]) != THIS) {
                        pool.computeIfAbsent(now[Integer.parseInt(parts[0])].getDescriptor() + ">" + desc(was[i].getDescriptor()), k -> new HashMap<>())
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
                    GameDiff.Meth m = b.method(step[1], step[2], step[3]);
                    if (m == null || (m.flags & Opcodes.ACC_PUBLIC) == 0 || (m.flags & Opcodes.ACC_STATIC) != 0) {
                        return false;
                    }
                } else {
                    GameDiff.Fld f = b.field(step[1], step[2]);
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
            if (!(m.type.equals(desc(wanted.getDescriptor())) || wanted.getSort() == Type.OBJECT && m.type.startsWith("L")
                && subtype(b, m.type.substring(1, m.type.length() - 1), cls(wanted.getInternalName())))) {
                return null;
            }
            Deque<String> steps = new ArrayDeque<>();
            GameDiff.Node n = m;
            while (steps.size() < 6 && (n.kids.length == 1 && (n.op == 'v' && n.value == null && n.ref.desc().startsWith("()") || n.op == 'f'
                || n.op == 'x' && CONVERT.containsKey(n.kids[0].type + n.type)) || constantArguments(n))) {
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
                j = THIS;
            }
            // The new parameter itself is a conversion only where it is the old one's very type at another place
            // (mouseDragged's drag distances after the event that replaced x, y and button).
            if (j == null || steps.isEmpty() && !m.type.equals(desc(wanted.getDescriptor()))) {
                return null;
            }
            return steps.isEmpty() ? String.valueOf(j) : j + " " + String.join(" ", steps);
        }

        /** The class of the new release that declares a method a call names, so one call made through subclasses reads alike. */
        String declarer(String owner, String name, String desc) {
            GameDiff.Cls holder = Exposure.declaring(b, owner, 'm', name, desc);
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
                for (GameDiff.Cls c : declaring(a, name + desc)) {
                    if (subtype(a, c.name, owner)) {
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
                    return new GameDiff.Decoder(o, jar).run(mm);
                }
            }
            return null;
        }
    }

    /** The instruction that turns a value of one primitive type into another, by the two descriptors. */
    static final Map<String, Integer> CONVERT = Map.ofEntries(Map.entry("IC", Opcodes.I2C), Map.entry("IB", Opcodes.I2B),
        Map.entry("IS", Opcodes.I2S), Map.entry("IJ", Opcodes.I2L), Map.entry("IF", Opcodes.I2F), Map.entry("ID", Opcodes.I2D),
        Map.entry("JI", Opcodes.L2I), Map.entry("FD", Opcodes.F2D), Map.entry("DF", Opcodes.D2F), Map.entry("FI", Opcodes.F2I),
        Map.entry("DI", Opcodes.D2I));

    /** Whether class c is sup or extends or implements it in one release. */
    static boolean subtype(GameDiff.Snap s, String c, String sup) {
        Deque<String> todo = new ArrayDeque<>(List.of(c));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            String k = todo.poll();
            if (k.equals(sup)) {
                return true;
            }
            GameDiff.Cls held = seen.add(k) ? s.classes.get(k) : null;
            if (held != null) {
                if (held.sup != null) {
                    todo.add(held.sup);
                }
                todo.addAll(held.ifaces);
            }
        }
        return false;
    }

    /** Each parameter's local slot in an instance method. */
    static int[] slots(Type[] params) {
        return slots(params, 1);
    }

    /** Each parameter's local slot, the first at first (0 in a static method). */
    static int[] slots(Type[] params, int first) {
        int[] out = new int[params.length];
        int next = first;
        for (int i = 0; i < params.length; i++) {
            out[i] = next;
            next += params[i].getSize();
        }
        return out;
    }

    /**
     * Two bodies of one method, the old release's (x) and the new one's (y), lined up statement by statement as
     * GameDiff.Learner lines them up: equal statements (through the route map) are anchors, and in the gaps between them
     * a statement of x that uses a hole parameter is matched against each of y's; where it matches everywhere else, y's
     * code at each use of the parameter is reported. same maps old parameter slots to the new slots of the same values.
     */
    final class Lining {
        final Map<Integer, Integer> holes, same;
        final Map<GameDiff.Node, Long> hashX = new IdentityHashMap<>(), hashY = new IdentityHashMap<>();
        int budget;

        Lining(Map<Integer, Integer> holes, Map<Integer, Integer> same) {
            this.holes = holes;
            this.same = same;
        }

        boolean hole(GameDiff.Node n) {
            return n.op == 'p' && n.value instanceof Integer s && holes.containsKey(s);
        }

        void pair(GameDiff.Body x, GameDiff.Body y, java.util.function.BiConsumer<Integer, GameDiff.Node> found) {
            long[] tx = new long[x.statements.size()], ty = new long[y.statements.size()];
            for (int i = 0; i < tx.length; i++) {
                tx[i] = hash(x.statements.get(i), true);
            }
            for (int j = 0; j < ty.length; j++) {
                ty[j] = hash(y.statements.get(j), false);
            }
            List<int[]> bounds = new ArrayList<>(GameDiff.Learner.matches(tx, ty));
            bounds.add(new int[] { tx.length, ty.length });
            int i0 = 0, j0 = 0;
            for (int[] p : bounds) {
                int na = p[0] - i0, nb = p[1] - j0;
                for (int i = i0; i < p[0] && nb > 0; i++) {
                    GameDiff.Node sx = x.statements.get(i);
                    if (!holed(sx)) {
                        continue;
                    }
                    boolean bare = sx.kids.length == 1 && sx.kids[0].kids.length == 0;
                    for (int j = j0; j < p[1]; j++) {
                        if (bare && !(na == nb && j - j0 == i - i0 || nb == 1)) {
                            continue;
                        }
                        Map<GameDiff.Node, GameDiff.Node> at = new IdentityHashMap<>();
                        budget = 20_000;
                        if (match(sx, y.statements.get(j), at)) {
                            at.forEach((h, m) -> found.accept((Integer) h.value, m));
                        }
                    }
                }
                i0 = p[0] + 1;
                j0 = p[1] + 1;
            }
        }

        boolean holed(GameDiff.Node n) {
            if (hole(n)) {
                return true;
            }
            for (GameDiff.Node k : n.kids) {
                if (holed(k)) {
                    return true;
                }
            }
            return false;
        }

        /** A node's own part, in the new release's names: a member by its new name and descriptor. */
        String head(GameDiff.Node n, boolean old) {
            String ref = n.ref == null ? "" : switch (n.ref.kind()) {
                case 'c' -> old ? cls(n.ref.owner()) : n.ref.owner();
                case 'f' -> old ? route.field(n.ref.owner(), n.ref.name())[1] : n.ref.name();
                case 'm' -> {
                    if (!old) {
                        yield n.ref.name() + n.ref.desc();
                    }
                    String[] moved = route.method(n.ref.owner(), n.ref.name(), n.ref.desc());
                    yield moved[1] + moved[2];
                }
                default -> n.ref.owner();
            };
            return switch (n.op) {
                case 'p' -> (old ? same.getOrDefault((Integer) n.value, -1) : n.value) + ":" + (old ? desc(n.type) : n.type);
                case 'l' -> n.value + ":" + (old ? desc(n.type) : n.type);
                case 'k' -> n.type + ":" + n.value;
                case 'x' -> n.type;
                case 'o', 'S', 'y' -> n.value + ref;
                default -> String.valueOf(n.value) + ref;
            };
        }

        long hash(GameDiff.Node n, boolean old) {
            Map<GameDiff.Node, Long> memo = old ? hashX : hashY;
            Long known = memo.get(n);
            if (known != null) {
                return known;
            }
            long h;
            if (old && hole(n) || GameDiff.Decoder.phi(n)) {
                h = 0x9E3779B97F4A7C15L * (System.identityHashCode(n) + 1L) + (old ? 0 : 1);
            } else {
                h = GameDiff.fnv(n.op + head(n, old));
                for (GameDiff.Node k : n.kids) {
                    h = h * 1_000_003L + hash(k, old);
                }
            }
            memo.put(n, h);
            return h;
        }

        boolean match(GameDiff.Node x, GameDiff.Node y, Map<GameDiff.Node, GameDiff.Node> at) {
            if (--budget < 0) {
                return false;
            }
            if (hole(x)) {
                GameDiff.Node had = at.putIfAbsent(x, y);
                return had == null || had == y;
            }
            if (GameDiff.Decoder.phi(x) || GameDiff.Decoder.phi(y)) {
                return false;
            }
            if (hash(x, true) == hash(y, false)) {
                return true;
            }
            if (x.op != y.op || x.kids.length != y.kids.length || !head(x, true).equals(head(y, false))) {
                return false;
            }
            for (int i = 0; i < x.kids.length; i++) {
                if (!match(x.kids[i], y.kids[i], at)) {
                    return false;
                }
            }
            return true;
        }
    }

    /** The old release's method a mixin "method" string names in one of the targets, or null. */
    GameDiff.Meth oldMethod(List<String> targets, String text) {
        Matcher m = MEMBER.matcher(text);
        if (!m.matches() || m.group(4) != null) {
            return null;
        }
        for (String o : m.group(1) != null ? List.of(m.group(1)) : targets) {
            List<GameDiff.Meth> found = named(a, o, m.group(2)).stream().filter(x -> m.group(3) == null || x.desc.equals(m.group(3))).toList();
            if (!found.isEmpty()) {
                return found.get(0);
            }
        }
        return null;
    }

    /** A mixin handler in two: its annotations go to the handler with the new parameters, its body to name$mmt, which the handler calls. */
    /**
     * An instance method's code written as a static method's, for a handler that never reads its own object: each local
     * moves down one slot, and full frames lose the object. Compressed frames are relative to the start, which the
     * writer reads from the static access.
     */
    static final class Staticize extends MethodVisitor {
        Staticize(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            super.visitVarInsn(opcode, var - 1);
        }

        @Override
        public void visitIincInsn(int var, int increment) {
            super.visitIincInsn(var - 1, increment);
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            if ((type == Opcodes.F_FULL || type == Opcodes.F_NEW) && numLocal > 0) {
                super.visitFrame(type, numLocal - 1, Arrays.copyOfRange(local, 1, numLocal), numStack, stack);
                return;
            }
            super.visitFrame(type, numLocal, local, numStack, stack);
        }

        @Override
        public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
            if (index > 0) {
                super.visitLocalVariable(name, desc, signature, start, end, index - 1);
            }
        }

        @Override
        public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath, Label[] start, Label[] end, int[] index, String descriptor, boolean visible) {
            return null;
        }
    }

    final class Split extends MethodVisitor {
        final MethodVisitor handler;
        final String owner, name, desc;
        final int access;
        final Adapter adapter;
        final boolean itf;

        Split(MethodVisitor handler, MethodVisitor body, String owner, String name, String desc, int access, Adapter adapter, boolean itf) {
            super(Opcodes.ASM9, body);
            this.itf = itf;
            this.handler = handler;
            this.owner = owner;
            this.name = name;
            this.desc = desc;
            this.access = access;
            this.adapter = adapter;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String d, boolean visible) {
            return handler.visitAnnotation(d, visible);
        }

        @Override
        public void visitAnnotableParameterCount(int parameterCount, boolean visible) {
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(int parameter, String d, boolean visible) {
            // What the handler takes after the arguments it re-declares (the CallbackInfo, sugar such as @Local) keeps its
            // annotations on the handler, where Mixin reads them; the body behind it is a plain method.
            int rest = Type.getArgumentTypes(desc).length - adapter.captured();
            int now = Type.getArgumentTypes(oldDesc(adapter.desc())).length;
            return parameter >= adapter.captured() ? handler.visitParameterAnnotation(now - rest + parameter - adapter.captured(), d, visible) : null;
        }

        @Override
        public void visitEnd() {
            super.visitEnd();
            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            Type[] now = Type.getArgumentTypes(oldDesc(adapter.desc()));
            Type[] was = Type.getArgumentTypes(desc);
            int[] slots = new int[now.length];
            int next = isStatic ? 0 : 1;
            for (int i = 0; i < now.length; i++) {
                slots[i] = next;
                next += now[i].getSize();
            }
            handler.visitCode();
            int stack = isStatic ? 0 : 1;
            if (!isStatic) {
                handler.visitVarInsn(Opcodes.ALOAD, 0);
            }
            for (int j = 0; j < adapter.captured(); j++) {
                int f = adapter.from()[j];
                if (f == THIS) {
                    handler.visitVarInsn(Opcodes.ALOAD, 0);
                    steps(handler, adapter.via()[j]);
                } else if (f < 0) {
                    pushDefault(handler, was[j]);
                } else {
                    handler.visitVarInsn(now[f].getOpcode(Opcodes.ILOAD), slots[f]);
                    steps(handler, adapter.via()[j]);
                }
                stack += was[j].getSize();
            }
            // The CallbackInfo and what the handler takes after it (sugar, captured locals) pass through as they are.
            for (int j = now.length - (was.length - adapter.captured()); j < now.length; j++) {
                handler.visitVarInsn(now[j].getOpcode(Opcodes.ILOAD), slots[j]);
                stack += now[j].getSize();
            }
            handler.visitMethodInsn(isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL, owner, name + "$mmt", desc, itf);
            handler.visitInsn(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN));
            handler.visitMaxs(stack + 3, next);
            handler.visitEnd();
        }
    }

    /**
     * A mod method whose game method's parameters changed type, written with the new signature (br): its old parameters
     * are worked out from the new ones first, its body follows with every local moved past the new parameters, and calls
     * in it that need what the game passes now get it (Sites.given). Read with expanded frames, which are rewritten with
     * the new parameters in front.
     */
    void moved(Mod mod, byte[] bytes, String owner, Bridge br, ClassVisitor out) {
        Set<Integer> stored = new HashSet<>();
        ClassReader reader = new ClassReader(bytes);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return !name.equals(br.target()) || !desc.equals(br.targetDesc()) ? null : new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitVarInsn(int opcode, int var) {
                        if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) {
                            stored.add(var);
                        }
                    }

                    @Override
                    public void visitIincInsn(int var, int increment) {
                        stored.add(var);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (!name.equals(br.target()) || !desc.equals(br.targetDesc())) {
                    return null;
                }
                String newDesc = oldDesc(br.desc());
                String key = owner + "." + name + desc;
                // A handler made static, as its target now is (staticizes): written as a static method under everything else.
                boolean staticized = mod.staticized.contains(key) && mod.retargets.keySet().stream().anyMatch(r -> r.startsWith(key + " "));
                MethodVisitor written = out.visitMethod(staticized ? access | Opcodes.ACC_STATIC : access, br.name(), newDesc, null, exceptions);
                Track track = new Track(owner, access, br.name(), newDesc, staticized ? new Staticize(written) : written);
                Shift shift = new Shift(track, br, desc, stored, (access & Opcodes.ACC_STATIC) != 0);
                // A mixin handler's code runs in its targets, and its annotations name them (as in rewrite).
                List<String> targets = mod.mixinTargets.getOrDefault(owner, List.of());
                List<String> callers = new ArrayList<>(targets.isEmpty() ? List.of(owner) : targets.stream().map(JarPort.this::cls).toList());
                if (targets.stream().anyMatch(t -> !mod.classes.containsKey(t) && !a.classes.containsKey(t))) {
                    callers.add(owner);
                }
                Sites sites = new Sites(mod, shift, mod.maxLocals.getOrDefault(key, 0), targets, callers, key, Exposure.dotted(owner) + "." + name);
                sites.moved = shift;
                return sites;
            }
        }, ClassReader.EXPAND_FRAMES);
    }

    /**
     * Where each value a method reads from a field goes, as the verifier follows the stack (asm-commons' AnalyzerAdapter).
     * A use is {"R", call, owner, name, desc} for the receiver of the method's call number call, {"A", call, type, i, owner,
     * name, desc, opcode} for its argument i of that type, {"C", type} for a cast, {"N"} for a null test or a pop. Anything else makes the read bad: a
     * store, a return, a jump that carries it, and what a value of another type would answer differently (==, instanceof).
     */
    static final Set<String> OBJECT_METHODS = Set.of("equals(Ljava/lang/Object;)Z", "hashCode()I", "toString()Ljava/lang/String;", "getClass()Ljava/lang/Class;");

    static final class ReadUses extends org.objectweb.asm.commons.AnalyzerAdapter {
        static final class Read {
            final List<String[]> uses = new ArrayList<>();
            boolean bad;
        }

        final List<Read> reads = new ArrayList<>(); // by field instruction number; null for a write or a primitive
        final Map<Integer, Read> results = new HashMap<>(); // by call number: where a call's object result goes
        final Map<Object, Read> marks = new IdentityHashMap<>();
        int calls = -1;

        ReadUses(String owner, int access, String name, String desc) {
            super(Opcodes.ASM9, owner, access, name, desc, null);
        }

        Map<Object, Integer> counted() {
            Map<Object, Integer> out = new IdentityHashMap<>();
            for (Object v : stack == null ? List.of() : stack) {
                if (marks.containsKey(v)) {
                    out.merge(v, 1, Integer::sum);
                }
            }
            return out;
        }

        /** The marked value at a depth below the top of the stack (0 the top), or null. */
        Object at(int depth) {
            if (stack == null || depth >= stack.size()) {
                return null;
            }
            Object v = stack.get(stack.size() - 1 - depth);
            return marks.containsKey(v) ? v : null;
        }

        /** Runs one instruction; a marked value it takes away is used as said says, or else makes its read bad. */
        void step(Map<Object, String[]> said, Runnable instruction) {
            Map<Object, Integer> before = counted();
            instruction.run();
            Map<Object, Integer> after = counted();
            before.forEach((v, n) -> {
                if (after.getOrDefault(v, 0) < n) {
                    String[] use = said.get(v);
                    if (use == null) {
                        marks.get(v).bad = true;
                    } else {
                        marks.get(v).uses.add(use);
                    }
                }
            });
        }

        /** A value still on the stack where a jump leaves goes to its target too, whose frame names its old type. */
        void carried() {
            for (Object v : stack == null ? List.of() : stack) {
                Read r = marks.get(v);
                if (r != null) {
                    r.bad = true;
                }
            }
        }

        static Map<Object, String[]> said(Object v, String... use) {
            Map<Object, String[]> out = new IdentityHashMap<>();
            if (v != null) {
                out.put(v, use);
            }
            return out;
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            step(Map.of(), () -> super.visitFieldInsn(opcode, owner, name, desc));
            boolean read = opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC;
            if (read && stack != null && (desc.startsWith("L") || desc.startsWith("["))) {
                Object mark = new String(desc.startsWith("L") ? desc.substring(1, desc.length() - 1) : desc);
                stack.set(stack.size() - 1, mark);
                Read r = new Read();
                marks.put(mark, r);
                reads.add(r);
            } else {
                reads.add(null);
            }
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            calls++;
            Map<Object, String[]> said = new IdentityHashMap<>();
            Type[] args = Type.getArgumentTypes(desc);
            int depth = 0;
            for (int i = args.length - 1; i >= 0; i--) {
                depth += args[i].getSize();
                Object v = at(depth - 1);
                if (v != null) {
                    said.putIfAbsent(v, new String[] { "A", String.valueOf(calls), args[i].getDescriptor(), String.valueOf(i), owner, name, desc, String.valueOf(opcode) });
                }
            }
            Object self = opcode == Opcodes.INVOKESTATIC ? null : at(depth);
            if (self != null && !said.containsKey(self) && opcode != Opcodes.INVOKESPECIAL) {
                said.put(self, new String[] { "R", String.valueOf(calls), owner, name, desc });
            }
            step(said, () -> super.visitMethodInsn(opcode, owner, name, desc, itf));
            Type result = Type.getReturnType(desc);
            if (stack != null && (result.getSort() == Type.OBJECT || result.getSort() == Type.ARRAY) && !name.equals("<init>")) {
                Object mark = new String(result.getSort() == Type.OBJECT ? result.getInternalName() : result.getDescriptor());
                stack.set(stack.size() - 1, mark);
                Read r = new Read();
                marks.put(mark, r);
                results.put(calls, r);
            }
        }

        @Override
        public void visitInsn(int opcode) {
            boolean test = opcode == Opcodes.POP || opcode == Opcodes.POP2 || opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT;
            Map<Object, String[]> said = test ? said(at(0), "N") : Map.of();
            if (opcode == Opcodes.POP2 && at(1) != null) {
                said = new IdentityHashMap<>(said);
                said.put(at(1), new String[] { "N" });
            }
            Map<Object, String[]> s = said;
            step(s, () -> super.visitInsn(opcode));
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            Map<Object, String[]> said = opcode == Opcodes.CHECKCAST ? said(at(0), "C", type) : Map.of();
            step(said, () -> super.visitTypeInsn(opcode, type));
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            Map<Object, String[]> said = opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL ? said(at(0), "N") : Map.of();
            step(said, () -> super.visitJumpInsn(opcode, label));
            carried();
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            step(Map.of(), () -> super.visitVarInsn(opcode, var));
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            step(Map.of(), () -> super.visitIntInsn(opcode, operand));
        }

        @Override
        public void visitLdcInsn(Object value) {
            step(Map.of(), () -> super.visitLdcInsn(value));
        }

        @Override
        public void visitIincInsn(int var, int increment) {
            step(Map.of(), () -> super.visitIincInsn(var, increment));
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) {
            step(Map.of(), () -> super.visitInvokeDynamicInsn(name, desc, bsm, args));
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            step(Map.of(), () -> super.visitTableSwitchInsn(min, max, dflt, labels));
            carried();
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            step(Map.of(), () -> super.visitLookupSwitchInsn(dflt, keys, labels));
            carried();
        }

        @Override
        public void visitMultiANewArrayInsn(String desc, int dims) {
            step(Map.of(), () -> super.visitMultiANewArrayInsn(desc, dims));
        }

        @Override
        public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
            step(Map.of(), () -> super.visitFrame(type, nLocal, local, nStack, stack));
        }

        /** What a method of a class's bytes does with each value it reads from a field, or null when it is not there. */
        static ReadUses of(byte[] bytes, String name, String desc) {
            if (bytes == null) {
                return null;
            }
            ClassReader reader = new ClassReader(bytes);
            ReadUses[] found = new ReadUses[1];
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                    if (!n.equals(name) || !d.equals(desc) || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                        return null;
                    }
                    return found[0] = new ReadUses(reader.getClassName(), access, n, d);
                }
            }, ClassReader.EXPAND_FRAMES);
            return found[0];
        }
    }

    /**
     * Which of a type's own constants each call of a method passes: a static field of its class's type read (an enum's
     * constant, or HoverEvent.Action.SHOW_TEXT) and passed on, as the verifier tracks the stack, across jumps where every
     * way in passes the same one.
     */
    static final class Constants extends Marked {
        final Map<Integer, Map<Integer, String>> at = new HashMap<>();
        final Map<Object, String> marks = new IdentityHashMap<>();
        int calls = -1;

        Constants(String owner, int access, String name, String desc) {
            super(owner, access, name, desc, null);
        }

        @Override
        boolean marked(Object value) {
            return marks.containsKey(value);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            super.visitFieldInsn(opcode, owner, name, desc);
            if (opcode == Opcodes.GETSTATIC && desc.equals("L" + owner + ";") && stack != null && !stack.isEmpty()) {
                Object mark = new String(owner);
                stack.set(stack.size() - 1, mark);
                marks.put(mark, name);
            }
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            calls++;
            if (stack != null) {
                Type[] args = Type.getArgumentTypes(desc);
                int pos = stack.size();
                for (int i = args.length - 1; i >= 0 && pos > 0; i--) {
                    pos -= args[i].getSize();
                    String c = pos >= 0 ? marks.get(stack.get(pos)) : null;
                    if (c != null) {
                        at.computeIfAbsent(calls, z -> new HashMap<>()).put(i, c);
                    }
                }
            }
            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }

        static Map<Integer, Map<Integer, String>> of(byte[] bytes, String name, String desc) {
            if (bytes == null) {
                return Map.of();
            }
            ClassReader reader = new ClassReader(bytes);
            Constants[] found = new Constants[1];
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                    if (!n.equals(name) || !d.equals(desc) || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                        return null;
                    }
                    return found[0] = new Constants(reader.getClassName(), access, n, d);
                }
            }, ClassReader.EXPAND_FRAMES);
            return found[0] == null ? Map.of() : found[0].at;
        }
    }

    /**
     * The stack as the JVM's verifier sees it (asm-commons' AnalyzerAdapter), where values a subclass marks keep who they
     * are across jumps: a frame gives the stack as types, so the marks a jump carries ("a ? b : c" as an argument) are kept
     * per label, where every way in agrees, and put back when the label's frame comes.
     */
    abstract static class Marked extends org.objectweb.asm.commons.AnalyzerAdapter {

        Marked(String owner, int access, String name, String desc, MethodVisitor mv) {
            super(Opcodes.ASM9, owner, access, name, desc, mv);
        }

        abstract boolean marked(Object value);

        final Map<Label, Object[]> carried = new HashMap<>();
        final List<Label> arrived = new ArrayList<>();

        void carry(Label label) {
            if (stack == null) {
                return;
            }
            Object[] now = stack.stream().map(v -> marked(v) ? v : null).toArray();
            carried.merge(label, now, (had, add) -> {
                if (had.length != add.length) {
                    return new Object[0];
                }
                Object[] both = new Object[had.length];
                for (int i = 0; i < had.length; i++) {
                    both[i] = had[i] == add[i] ? had[i] : null;
                }
                return both;
            });
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            if (opcode == Opcodes.GOTO) {
                carry(label);
                super.visitJumpInsn(opcode, label);
            } else {
                super.visitJumpInsn(opcode, label);
                carry(label);
            }
            arrived.clear();
        }

        @Override
        public void visitLabel(Label label) {
            carry(label); // the way in from the instruction before, when it falls through
            arrived.add(label);
            super.visitLabel(label);
        }

        @Override
        public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
            super.visitFrame(type, nLocal, local, nStack, stack);
            for (Label l : arrived) {
                Object[] marks = carried.get(l);
                if (marks != null && this.stack != null && marks.length == this.stack.size()) {
                    for (int i = 0; i < marks.length; i++) {
                        if (marks[i] != null) {
                            this.stack.set(i, marks[i]);
                        }
                    }
                }
            }
        }

        @Override
        public void visitInsn(int opcode) {
            super.visitInsn(opcode);
            arrived.clear();
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            arrived.clear();
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            arrived.clear();
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitLdcInsn(Object value) {
            arrived.clear();
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            arrived.clear();
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            arrived.clear();
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            arrived.clear();
            super.visitVarInsn(opcode, var);
        }
    }

    /**
     * The stack as the JVM's verifier sees it (asm-commons' AnalyzerAdapter), where a moved method's old parameters keep
     * who they are: each load of one pushes a mark, so a call can tell which of its arguments is which old parameter.
     */
    static final class Track extends Marked {
        final Map<Integer, Object> marks = new HashMap<>(); // slot -> mark
        final Map<Object, Integer> params = new IdentityHashMap<>(); // mark -> old parameter

        Track(String owner, int access, String name, String desc, MethodVisitor mv) {
            super(owner, access, name, desc, mv);
        }

        void mark(int slot, int param, Type t) {
            Object mark = t.getSort() == Type.OBJECT ? new String(t.getInternalName()) : t.getSort() == Type.ARRAY ? new String(t.getDescriptor()) : new Object();
            marks.put(slot, mark);
            params.put(mark, param);
        }

        @Override
        boolean marked(Object value) {
            return params.containsKey(value);
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            super.visitVarInsn(opcode, var);
            Object mark = marks.get(var);
            // A long or double is two entries, its value first.
            int at = stack == null ? -1 : stack.size() - (opcode == Opcodes.LLOAD || opcode == Opcodes.DLOAD ? 2 : 1);
            if (mark != null && opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD && at >= 0) {
                stack.set(at, mark);
            }
        }

        /** For each argument of a call about to be made (on top of the stack), the old parameter it is, or null. */
        Integer[] sources(Type[] args) {
            if (stack == null) {
                return null;
            }
            Integer[] out = new Integer[args.length];
            int at = stack.size();
            for (int k = args.length - 1; k >= 0; k--) {
                at -= args[k].getSize();
                if (at < 0) {
                    return null;
                }
                out[k] = params.get(stack.get(at));
            }
            return out;
        }
    }

    /**
     * A moved body (Bridge.via): the old parameters worked out from the new ones and stored past them, and every local
     * of the old body moved by as much. loadNew pushes a new parameter itself.
     */
    final class Shift extends MethodVisitor {
        final Track track;
        final Bridge br;
        final Type[] now, was;
        final int[] nowSlots, wasSlots;
        final int base, first; // first: the first parameter's slot, 0 in a static method
        final Set<Integer> stored;

        Shift(Track track, Bridge br, String oldDesc, Set<Integer> stored, boolean isStatic) {
            super(Opcodes.ASM9, track);
            this.track = track;
            this.br = br;
            this.stored = stored;
            first = isStatic ? 0 : 1;
            now = Type.getArgumentTypes(oldDesc(br.desc()));
            was = Type.getArgumentTypes(oldDesc);
            nowSlots = slots(now, first);
            wasSlots = slots(was, first);
            base = first + Arrays.stream(now).mapToInt(Type::getSize).sum();
        }

        int at(int var) {
            return var < first ? var : var + base - first;
        }

        void loadNew(int j) {
            mv.visitVarInsn(now[j].getOpcode(Opcodes.ILOAD), nowSlots[j]);
        }

        /** Whether a call's arguments include an old parameter the body has only a default for. */
        boolean defaulted(Type[] args) {
            Integer[] src = track.sources(args);
            return src != null && Arrays.stream(src).anyMatch(s -> s != null && br.from()[s] == -1);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            for (int i = 0; i < was.length; i++) {
                int f = br.from()[i];
                if (f == THIS) {
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    steps(mv, br.via()[i]);
                } else if (f < 0) {
                    pushDefault(mv, was[i]);
                } else {
                    loadNew(f);
                    steps(mv, br.via()[i]);
                }
                mv.visitVarInsn(was[i].getOpcode(Opcodes.ISTORE), at(wasSlots[i]));
                if (!stored.contains(wasSlots[i])) {
                    track.mark(at(wasSlots[i]), i, was[i]);
                }
            }
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            super.visitVarInsn(opcode, at(var));
        }

        @Override
        public void visitIincInsn(int var, int increment) {
            super.visitIincInsn(at(var), increment);
        }

        @Override
        public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
            super.visitLocalVariable(name, desc, signature, start, end, at(index));
        }

        @Override
        public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
            if (type != Opcodes.F_NEW || nLocal < first) {
                super.visitFrame(type, nLocal, local, nStack, stack);
                return;
            }
            Object[] out = new Object[nLocal + now.length];
            System.arraycopy(local, 0, out, 0, first);
            for (int j = 0; j < now.length; j++) {
                out[first + j] = switch (now[j].getSort()) {
                    case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> Opcodes.INTEGER;
                    case Type.FLOAT -> Opcodes.FLOAT;
                    case Type.LONG -> Opcodes.LONG;
                    case Type.DOUBLE -> Opcodes.DOUBLE;
                    case Type.ARRAY -> now[j].getDescriptor();
                    default -> now[j].getInternalName();
                };
            }
            System.arraycopy(local, first, out, first + now.length, nLocal - first);
            super.visitFrame(type, out.length, out, nStack, stack);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            super.visitMaxs(maxStack + 4, maxLocals + base - first); // a conversion's receiver and its constant arguments
        }

        @Override
        public void visitParameter(String name, int access) {
        }

        @Override
        public void visitAnnotableParameterCount(int parameterCount, boolean visible) {
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
            // A parameter that passes through as it is keeps its annotations (MixinExtras' @Local, @Share...) at its new place.
            int f = parameter < br.from().length ? br.from()[parameter] : -1;
            return f >= 0 && (br.via() == null || parameter >= br.via().length || br.via()[parameter] == null) && now[f].equals(was[parameter])
                ? super.visitParameterAnnotation(f, descriptor, visible) : null;
        }

        @Override
        public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath, Label[] start, Label[] end, int[] index, String descriptor, boolean visible) {
            return null;
        }
    }

    /** The steps a conversion reads (Retype.step) on the value on top of the stack, written in the old release's names. */
    void steps(MethodVisitor mv, String[] steps) {
        for (String text : steps == null ? new String[0] : steps) {
            if (text.startsWith("x:")) {
                mv.visitInsn(CONVERT.get(text.substring(2)));
                continue;
            }
            if (text.startsWith("k:")) {
                String type = text.substring(2, 3), value = text.substring(4);
                mv.visitLdcInsn(switch (type) {
                    case "J" -> (Object) Long.valueOf(value);
                    case "F" -> Float.valueOf(value);
                    case "D" -> Double.valueOf(value);
                    case "Z" -> value.equals("true") || value.equals("1") ? 1 : 0;
                    default -> Integer.valueOf(value);
                });
                continue;
            }
            String[] s = Retype.step(text);
            String owner = inverse.getOrDefault(s[1], s[1]);
            if (s[0].equals("m")) {
                GameDiff.Cls holder = b.classes.get(s[1]);
                boolean itf = holder != null && (holder.flags & Opcodes.ACC_INTERFACE) != 0;
                mv.visitMethodInsn(itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, owner, s[2], oldDesc(s[3]), itf);
            } else {
                mv.visitFieldInsn(Opcodes.GETFIELD, owner, s[2], oldDesc(s[3]));
            }
        }
    }
    /** The bridge's code: the mod's own method called on this with the arguments it takes, its result returned. */
    void bridgeCode(MethodVisitor mv, String owner, boolean itf, Bridge br) {
        Type[] now = Type.getArgumentTypes(oldDesc(br.desc()));
        Type[] was = Type.getArgumentTypes(br.targetDesc());
        boolean isStatic = (br.access() & Opcodes.ACC_STATIC) != 0; // a static lambda body's (JarPort.lambdaBody)
        int[] slots = new int[now.length];
        int next = isStatic ? 0 : 1;
        for (int i = 0; i < now.length; i++) {
            slots[i] = next;
            next += now[i].getSize();
        }
        mv.visitCode();
        if (!isStatic) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
        }
        int stack = isStatic ? 0 : 1;
        for (int j = 0; j < was.length; j++) {
            int f = br.from()[j];
            if (f < 0) {
                pushDefault(mv, was[j]);
            } else {
                mv.visitVarInsn(now[f].getOpcode(Opcodes.ILOAD), slots[f]);
            }
            stack += was[j].getSize();
        }
        mv.visitMethodInsn(isStatic ? Opcodes.INVOKESTATIC : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, owner, br.target(), br.targetDesc(), itf);
        Type ret = Type.getReturnType(br.targetDesc());
        mv.visitInsn(ret.getOpcode(Opcodes.IRETURN));
        mv.visitMaxs(Math.max(stack, ret.getSize()), next);
        mv.visitEnd();
    }

    /** The value Java gives a variable of type t that nothing set: 0, false or null. */
    static void pushDefault(MethodVisitor mv, Type t) {
        mv.visitInsn(switch (t.getSort()) {
            case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> Opcodes.ICONST_0;
            case Type.LONG -> Opcodes.LCONST_0;
            case Type.FLOAT -> Opcodes.FCONST_0;
            case Type.DOUBLE -> Opcodes.DCONST_0;
            default -> Opcodes.ACONST_NULL;
        });
    }

    /** Call sites, field access, method references and the types named inside one method body. */
    /**
     * A call to a method that now returns nothing (1.21.6's GuiGraphics.drawString returned where the text ended and
     * returns void), written as the new call only where the mod threw the value away: the instruction after the call pops
     * it, and the pop goes with it. Anywhere else the value is used, so the old call stays and is reported unlinked. Sites
     * offers the new call just before it writes the old one; this decides on the next instruction.
     */
    static final class Dropped extends MethodVisitor {

        final Mod mod;
        String[] offer, held; // {opcode, owner, name, desc, itf} of the new call, and of the old call held back
        String heldUnlinked, offerUnlinked;
        int offerSize, heldSize;

        Dropped(Mod mod, MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
            this.mod = mod;
        }

        void offer(int opcode, String owner, String name, String desc, boolean itf, int size, String unlinked) {
            offer = new String[] { Integer.toString(opcode), owner, name, desc, Boolean.toString(itf) };
            offerSize = size;
            offerUnlinked = unlinked;
        }

        String[] alternative;

        void flush() {
            if (held != null) {
                String[] h = held;
                held = null;
                mod.unlinked.add(heldUnlinked);
                super.visitMethodInsn(Integer.parseInt(h[0]), h[1], h[2], h[3], Boolean.parseBoolean(h[4]));
            }
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            flush();
            if (offer != null) {
                alternative = offer;
                offer = null;
                held = new String[] { Integer.toString(opcode), owner, name, desc, Boolean.toString(itf) };
                heldSize = offerSize;
                heldUnlinked = offerUnlinked;
                return;
            }
            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }

        @Override
        public void visitInsn(int opcode) {
            if (held != null && (opcode == Opcodes.POP && heldSize == 1 || opcode == Opcodes.POP2 && heldSize == 2)) {
                held = null;
                String[] w = alternative;
                super.visitMethodInsn(Integer.parseInt(w[0]), w[1], w[2], w[3], Boolean.parseBoolean(w[4]));
                mod.change("call whose value the mod dropped, now returning nothing");
                return;
            }
            flush();
            super.visitInsn(opcode);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            flush();
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            flush();
            super.visitVarInsn(opcode, var);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            flush();
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            flush();
            super.visitFieldInsn(opcode, owner, name, desc);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) {
            flush();
            super.visitInvokeDynamicInsn(name, desc, bsm, args);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            flush();
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLabel(Label label) {
            flush();
            super.visitLabel(label);
        }

        @Override
        public void visitLdcInsn(Object value) {
            flush();
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIincInsn(int var, int increment) {
            flush();
            super.visitIincInsn(var, increment);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            flush();
            super.visitTableSwitchInsn(min, max, dflt, labels);
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            flush();
            super.visitLookupSwitchInsn(dflt, keys, labels);
        }

        @Override
        public void visitMultiANewArrayInsn(String desc, int dims) {
            flush();
            super.visitMultiANewArrayInsn(desc, dims);
        }

        @Override
        public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath, String desc, boolean visible) {
            flush();
            return super.visitInsnAnnotation(typeRef, typePath, desc, visible);
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            flush();
            super.visitFrame(type, numLocal, local, numStack, stack);
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            flush();
            super.visitTryCatchBlock(start, end, handler, type);
        }

        @Override
        public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
            flush();
            super.visitLocalVariable(name, desc, signature, start, end, index);
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            flush();
            super.visitLineNumber(line, start);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            flush();
            super.visitMaxs(maxStack, maxLocals);
        }

        @Override
        public void visitEnd() {
            flush();
            super.visitEnd();
        }
    }

    final class Sites extends MethodVisitor {
        final Mod mod;
        final List<String> targets;
        final List<String> callers;
        final String key;
        final String where;
        final Dropped dropped;
        int nextLocal;
        int extraStack; // what code written in place of an instruction may push beyond it
        int fieldsSeen = -1, callsSeen = -1; // the method's field and method instructions so far, as ReadUses numbers them
        ReadUses uses;
        boolean readsTried;
        final Map<Integer, String[]> onNewType = new HashMap<>(); // call number -> {opcode, owner, name, desc} on a retyped value

        Sites(Mod mod, MethodVisitor mv, int locals, List<String> targets, List<String> callers, String key, String where) {
            super(Opcodes.ASM9, new Dropped(mod, mv));
            dropped = (Dropped) this.mv;
            this.mod = mod;
            this.nextLocal = locals;
            this.targets = targets;
            this.callers = callers;
            this.key = key;
            this.where = where;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            AnnotationVisitor av = super.visitAnnotation(desc, visible);
            String target = mod.accessorTargets.get(key);
            if (ACCESSORS.containsKey(desc)) {
                // The accessor names its target in "value"; a renamed target is written there, so the method keeps its own name.
                return target == null || av == null ? av : new AnnotationVisitor(Opcodes.ASM9, av) {
                    @Override
                    public void visit(String n, Object v) {
                        if (!"value".equals(n)) {
                            super.visit(n, v);
                        }
                    }

                    @Override
                    public void visitEnd() {
                        super.visit("value", target);
                        super.visitEnd();
                    }
                };
            }
            return mixinStrings(mod, targets, av, key);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            super.visitMaxs(maxStack + 4 + extraStack, Math.max(maxLocals, nextLocal));
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            fresh = false;
            fieldsSeen++;
            types(mod, desc, where);
            owner = mod.shadowTargets.getOrDefault(owner + "." + name, owner);
            String[] own = mod.renamedDecl.get(owner + "." + name);
            if (own != null) {
                super.visitFieldInsn(opcode, owner, own[0], desc);
                return;
            }
            Decl decl = owner.startsWith("[") ? null : declaringOld(mod, owner, 'f', name, desc);
            if (decl == null || !decl.game()) {
                super.visitFieldInsn(opcode, owner, name, desc);
                return;
            }
            boolean read = opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC;
            String[] t = target(mod, callers, owner, read ? 'r' : 'f', name, desc, decl);
            if (t == null && read && chain(decl.owner() + "." + name, Type.getType(desc), opcode == Opcodes.GETSTATIC, "")) {
                mod.change("field read through a chain");
                return;
            }
            if (t == null && read) {
                String[] added = fix(List.of(owner, decl.owner()), name, null, opcode == Opcodes.GETSTATIC ? "()" + desc : "(L" + owner + ";)" + desc, opcode == Opcodes.GETFIELD);
                if (added != null) {
                    invokeAdded(added);
                    mod.change("field read replaced by a port rule");
                    return;
                }
            }
            if (t == null && !read) {
                // A write through what replaces it ("name=": $0 the receiver unless static, then the value).
                String[] added = fix(List.of(owner, decl.owner()), name + "=", null, opcode == Opcodes.PUTSTATIC ? "(" + desc + ")V" : "(L" + owner + ";" + desc + ")V", opcode == Opcodes.PUTFIELD);
                if (added != null) {
                    invokeAdded(added);
                    mod.change("field write replaced by a port rule");
                    return;
                }
            }
            if (t == null && read && retyped(opcode, owner, name, desc, decl)) {
                return;
            }
            if (t == null) {
                // The JVM finds a field by name and type: a reference of another type than the old release's field did not
                // link before the update either (code for another version behind a check, a type of a later release), so
                // the update broke nothing there and it stays as written.
                GameDiff.Cls old = Exposure.declaring(a, decl.owner(), 'f', name, null);
                if (old != null && old.fields.get(name).desc.equals(desc)) {
                    mod.unlinked.add("field: " + Exposure.dotted(decl.owner()) + "." + name);
                }
                super.visitFieldInsn(opcode, owner, name, desc);
                return;
            }
            if (!t[1].equals(name) || !t[0].equals(cls(owner))) {
                mod.change("field renamed");
            }
            super.visitFieldInsn(opcode, inverse.getOrDefault(t[0], t[0]), t[1], oldDesc(t[2]));
        }

        boolean gameDeclared(String owner, String name, String desc) {
            return JarPort.this.gameDeclared(mod, owner, name, desc);
        }

        /**
         * A read of a field the new release has with another type (Level.random: Random, then RandomSource), read as its new
         * type where every use of the value still fits it (the calls made on it are ones the new type has, the calls it is
         * passed to take the new type), or else read and turned into the old type as the game's own code gets what a holder
         * of the new type holds (SoundEvents' sounds become holders: holder.value()).
         */
        boolean retyped(int opcode, String owner, String name, String desc, Decl decl) {
            // Only what the update changed: a read of another type than the old release's never linked and stays as written.
            GameDiff.Cls had = Exposure.declaring(a, decl.owner(), 'f', name, null);
            if (had == null || !had.fields.get(name).desc.equals(desc)) {
                return false;
            }
            String[] now = fieldNow(mod, callers, owner, name, decl);
            String want = desc(desc);
            if (now == null || now[2].equals(want) || !now[2].startsWith("L")) {
                return false;
            }
            Type type = Type.getType(now[2]);
            if (!readsTried) {
                readsTried = true;
                int dot = key.indexOf('.'), paren = key.indexOf('(');
                uses = paren < 0 ? null : ReadUses.of(mod.bytes.get(key.substring(0, dot)), key.substring(dot + 1, paren), key.substring(paren));
            }
            List<ReadUses.Read> reads = uses == null ? null : uses.reads;
            ReadUses.Read read = reads == null || fieldsSeen >= reads.size() ? null : reads.get(fieldsSeen);
            Map<Integer, String[]> calls = read == null || read.bad || read.uses.isEmpty() ? null : onNewType(read, type);
            String[] steps = calls != null ? null : unwrap(now[2], want);
            why(key, "F " + name + " " + want + " -> " + now[2] + (read == null ? " no-read" : " bad=" + read.bad + " " + read.uses.stream().map(Arrays::toString).toList())
                + " calls=" + calls + " steps=" + (steps == null ? null : Arrays.toString(steps)));
            if (calls == null && steps == null) {
                return false;
            }
            super.visitFieldInsn(opcode, inverse.getOrDefault(now[0], now[0]), now[1], oldDesc(now[2]));
            if (calls != null) {
                onNewType.putAll(calls);
                mod.change("field read as its new type");
                return true;
            }
            unwrapping(type, steps);
            mod.change("field read through what the game's code reads from its new type");
            return true;
        }

        /** Writes what the game's code reads from a value of type held to get the one it holds (see unwrap). */
        void unwrapping(Type held, String[] steps) {
            boolean itf = isInterface(mod, held.getInternalName());
            super.visitMethodInsn(itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, inverse.getOrDefault(held.getInternalName(), held.getInternalName()),
                steps[0].substring(0, steps[0].indexOf('(')), oldDesc(steps[0].substring(steps[0].indexOf('('))), itf);
            String cast = steps[1];
            super.visitTypeInsn(Opcodes.CHECKCAST, inverse.getOrDefault(cast, cast));
            if (steps.length > 2) {
                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, cast, steps[2].substring(0, steps[2].indexOf('(')), steps[2].substring(steps[2].indexOf('(')), false);
            }
        }

        /** The exact JVM conversion from a call's new primitive result to the one the code used (1.21.6's Window.getGuiScale() int, read as the double it was). */
        static final Map<String, Integer> WIDEN = Map.of("ID", Opcodes.I2D, "IJ", Opcodes.I2L, "FD", Opcodes.F2D, "BI", Opcodes.NOP, "SI", Opcodes.NOP, "CI", Opcodes.NOP);

        /** Types whose value may hold nothing (an empty Optional, a failed DataResult): reading the value out could throw where the old call answered. */
        static final Set<String> MAYBE = Set.of("java/util/Optional", "com/mojang/serialization/DataResult", "java/util/OptionalInt", "java/util/OptionalLong", "java/util/OptionalDouble");

        /**
         * A call whose method the update gave another result type, its arguments still fitting (1.19's getRandom: Random,
         * then RandomSource; 1.21.6's getGuiScale: double, then int). The new result is taken as it is where every use
         * of it means the same of its type (ReadUses, as a retyped field read), widened where a primitive converts exactly,
         * or turned into the old type as the game's own code gets what a holder of it holds. Not a result that may hold
         * nothing (Optional, DataResult): the old call answered there.
         */
        boolean resultConverted(int opcode, String owner, String declOwner, String name, String desc) {
            if (moved != null || opcode == Opcodes.INVOKESPECIAL) {
                return false;
            }
            boolean isStatic = opcode == Opcodes.INVOKESTATIC;
            String[] t = route.method(declOwner, name, desc);
            Type[] before = Type.getArgumentTypes(desc(desc)), after = Type.getArgumentTypes(t[2]);
            Type was = Type.getReturnType(desc(desc)), now = Type.getReturnType(t[2]);
            if (before.length != after.length || was.equals(now) || was.getSort() == Type.VOID || now.getSort() == Type.VOID || !isStatic && !isA(cls(owner), t[0])
                || now.getSort() == Type.OBJECT && MAYBE.contains(now.getInternalName())) {
                // A result that may hold nothing is never null itself: even a null test of it would answer otherwise.
                return false;
            }
            for (int i = 0; i < before.length; i++) {
                if (!usable(before[i], after[i])) {
                    return false;
                }
            }
            GameDiff.Cls holder = Exposure.declaring(b, t[0], 'm', t[1], t[2]);
            GameDiff.Meth m = holder == null ? null : holder.methods.get(t[1] + t[2]);
            if (m == null || m.isStatic() != isStatic || !linksNew(mod, callers, t[0], 'm', t[1], t[2])) {
                return false;
            }
            boolean objects = now.getSort() == Type.OBJECT && (was.getSort() == Type.OBJECT || was.getSort() == Type.ARRAY);
            Integer widen = WIDEN.get(now.getDescriptor() + was.getDescriptor());
            Map<Integer, String[]> calls = null;
            String[] steps = null;
            if (objects) {
                if (!readsTried) {
                    readsTried = true;
                    int dot = key.indexOf('.'), paren = key.indexOf('(');
                    uses = paren < 0 ? null : ReadUses.of(mod.bytes.get(key.substring(0, dot)), key.substring(dot + 1, paren), key.substring(paren));
                }
                ReadUses.Read read = uses == null ? null : uses.results.get(callsSeen);
                calls = read == null || read.bad || read.uses.isEmpty() ? null : onNewType(read, now);
                steps = calls != null ? null : unwrap(now.getDescriptor(), was.getDescriptor());
            }
            if (calls == null && steps == null && widen == null) {
                return false;
            }
            GameDiff.Cls named = b.classes.getOrDefault(t[0], holder);
            boolean itf = (named.flags & Opcodes.ACC_INTERFACE) != 0;
            super.visitMethodInsn(isStatic ? Opcodes.INVOKESTATIC : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, inverse.getOrDefault(t[0], t[0]), t[1], oldDesc(t[2]), itf);
            if (widen != null) {
                if (widen != Opcodes.NOP) {
                    super.visitInsn(widen);
                }
                mod.change("call whose result type changed, widened to the old one");
            } else if (calls != null) {
                onNewType.putAll(calls);
                mod.change("call whose result type changed, its result used as the new type");
            } else {
                unwrapping(now, steps);
                mod.change("call whose result type changed, read through what the game's code reads from it");
            }
            return true;
        }

        /**
         * For a read whose every use means the same of a value of type: the calls made on it, each as the new type has it;
         * else null. Passed where any object goes (a map key, equals) or asked what only Object answers, a value of
         * another type would answer otherwise, so it does not fit.
         */
        Map<Integer, String[]> onNewType(ReadUses.Read read, Type type) {
            Map<Integer, String[]> out = new HashMap<>();
            for (String[] use : read.uses) {
                switch (use[0]) {
                    case "N" -> { }
                    case "A" -> {
                        if (use[2].equals("Ljava/lang/Object;")) {
                            return null;
                        }
                        if (!usable(type, Type.getType(desc(use[2])))) {
                            // A game method whose parameter changed with the field (Level.random and randomTick's random):
                            // the call takes its new signature, the value already being the new type.
                            String[] call = movedWith(type, use);
                            if (call == null || !Arrays.equals(out.getOrDefault(Integer.parseInt(use[1]), call), call)) {
                                return null;
                            }
                            out.put(Integer.parseInt(use[1]), call);
                        }
                    }
                    case "C" -> {
                        if (!usable(type, Type.getType(desc(use[1].startsWith("[") ? use[1] : "L" + use[1] + ";")))) {
                            return null;
                        }
                    }
                    case "R" -> {
                        String[] call = OBJECT_METHODS.contains(use[3] + use[4]) ? null : onType(type.getInternalName(), use[2], use[3], use[4]);
                        if (call == null) {
                            return null;
                        }
                        out.put(Integer.parseInt(use[1]), call);
                    }
                    default -> {
                        return null;
                    }
                }
            }
            return out;
        }

        /**
         * {opcode, owner, name, desc} (output names) of a game call a retyped value is passed to (an "A" use), as the new
         * release has it, when its new parameter there takes the value and every other argument and the result still fit;
         * or null.
         */
        String[] movedWith(Type type, String[] use) {
            String owner = use[4], name = use[5], desc = use[6];
            int i = Integer.parseInt(use[3]), opcode = Integer.parseInt(use[7]);
            GameDiff.Cls was = Exposure.declaring(a, owner, 'm', name, desc);
            if (was == null || opcode == Opcodes.INVOKESPECIAL) {
                return null;
            }
            String[] moved = route.method(was.name, name, desc);
            Type[] before = Type.getArgumentTypes(desc(desc)), after = Type.getArgumentTypes(moved[2]);
            if (before.length != after.length || after[i].getDescriptor().equals("Ljava/lang/Object;") || !usable(type, after[i])
                || !usable(Type.getReturnType(moved[2]), Type.getReturnType(desc(desc)))) {
                return null;
            }
            for (int k = 0; k < before.length; k++) {
                if (k != i && !usable(before[k], after[k])) {
                    return null;
                }
            }
            String site = opcode == Opcodes.INVOKESTATIC ? moved[0] : cls(owner);
            if (!linksNew(mod, callers, site, 'm', moved[1], moved[2])) {
                return null;
            }
            GameDiff.Cls self = b.classes.get(site);
            boolean itf = self != null && (self.flags & Opcodes.ACC_INTERFACE) != 0;
            int op = opcode == Opcodes.INVOKESTATIC ? opcode : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
            return new String[] { String.valueOf(op), inverse.getOrDefault(site, site), moved[1], oldDesc(moved[2]) };
        }

        /** {opcode, owner, name, desc} (output names) of a call written on owner (old names) as type has it, public and the same; or null. */
        String[] onType(String type, String owner, String name, String desc) {
            GameDiff.Cls was = Exposure.declaring(a, owner, 'm', name, desc);
            String[] moved = was == null ? new String[] { owner, name, desc(desc) } : route.method(was.name, name, desc);
            GameDiff.Cls holder = Exposure.declaring(b, type, 'm', moved[1], moved[2]);
            GameDiff.Meth m = holder == null ? null : holder.methods.get(moved[1] + moved[2]);
            if (m == null || m.isStatic() || (m.flags & Opcodes.ACC_PUBLIC) == 0 || !Type.getReturnType(moved[2]).equals(Type.getReturnType(desc(desc)))) {
                return null;
            }
            // The arguments on the stack are the old call's: each must still fit (the receiver alone changes here).
            Type[] before = Type.getArgumentTypes(desc(desc)), after = Type.getArgumentTypes(moved[2]);
            if (before.length != after.length) {
                return null;
            }
            for (int k = 0; k < before.length; k++) {
                if (!usable(before[k], after[k])) {
                    return null;
                }
            }
            boolean itf = isInterface(mod, type);
            return new String[] { String.valueOf(itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL), inverse.getOrDefault(type, type), moved[1], oldDesc(moved[2]) };
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            fresh = false;
            callsSeen++;
            String hookCall = opcode == Opcodes.INVOKESPECIAL && mod.superHook.containsKey(mod.self + "." + name + desc) && gameDeclared(owner, name, desc)
                ? mod.superHook.get(mod.self + "." + name + desc) : null;
            if (hookCall != null) {
                // A moved override's super call to the method the update made final: to the hook it calls instead.
                if (hookCall.startsWith("copy:")) {
                    super.visitMethodInsn(Opcodes.INVOKESPECIAL, mod.self, hookCall.substring(5), desc, false);
                } else if (hookCall.startsWith("own:")) {
                    super.visitMethodInsn(mod.selfInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, mod.self, hookCall.substring(4), desc, mod.selfInterface);
                } else {
                    super.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, hookCall, desc, itf);
                }
                return;
            }
            String[] onNew = onNewType.remove(callsSeen);
            if (onNew != null) {
                int op = Integer.parseInt(onNew[0]);
                GameDiff.Cls holder = b.classes.get(cls(onNew[1]));
                super.visitMethodInsn(op, onNew[1], onNew[2], onNew[3], op == Opcodes.INVOKEINTERFACE || holder != null && (holder.flags & Opcodes.ACC_INTERFACE) != 0);
                mod.change("call on a field's value made on its new type");
                return;
            }
            String[] closing = opcode == Opcodes.INVOKESPECIAL && name.equals("<init>") && !news.isEmpty() && news.peek()[0].equals(owner) ? news.peek() : null;
            String[] replaced = mod.supers.get(mod.self);
            if (closing == null && replaced != null && opcode == Opcodes.INVOKESPECIAL && name.equals("<init>") && owner.equals(replaced[0])) {
                superCall(replaced, desc, itf);
                return;
            }
            call(opcode, owner, name, desc, itf);
            if (closing != null && news.remove(closing) && "null".equals(closing[2])) {
                // Nothing replaced it: the class cannot be made in the new release.
                mod.unlinked.add("new of a class the new release cannot make: " + Exposure.dotted(owner) + " (in " + where + ")");
            }
        }

        /**
         * A mixin constructor's super(...) to a constructor the update reshaped or removed. Mixin never runs or resolves it:
         * it finds the call by owner and name alone (Bytecode.findDelegateInit) and merges only what follows into the
         * target's constructors. So the call goes to the constructor the route map gives, else the superclass's one with
         * the fewest parameters, each passed its type's zero, and what follows stays as it was.
         */
        boolean mixinSuper(String owner, String desc) {
            GameDiff.RawCls self = mod.classes.get(mod.self);
            if (!mod.mixinTargets.containsKey(mod.self) || self == null || !owner.equals(self.sup) || !key.contains(".<init>(")) {
                return false;
            }
            String[] moved = a.classes.containsKey(owner) ? route.method(owner, "<init>", desc) : null;
            return zeroSuper(owner, cls(owner), desc, moved);
        }

        /**
         * A mixin's super(...) (desc, old names) written as one to class now (new names; owner in the old ones, as the
         * renamer runs after): the constructor moved names when now has it, else its one with the fewest parameters,
         * each passed its type's zero. False when now has no constructor a subclass may call.
         */
        boolean zeroSuper(String owner, String now, String desc, String[] moved) {
            GameDiff.Cls holder = b.classes.get(now);
            if (holder == null && !now.equals("java/lang/Object")) {
                return false;
            }
            String chosen = holder == null ? "()V" : moved != null && moved[0].equals(now) && holder.methods.containsKey("<init>" + moved[2]) ? moved[2] : null;
            if (chosen == null) {
                for (GameDiff.Meth m : holder.methods.values()) {
                    if (m.name.equals("<init>") && (m.flags & Opcodes.ACC_PRIVATE) == 0
                        && (chosen == null || Type.getArgumentTypes(m.desc).length < Type.getArgumentTypes(chosen).length)) {
                        chosen = m.desc;
                    }
                }
            }
            if (chosen == null) {
                return false;
            }
            drop(desc, new int[0]);
            for (Type t : Type.getArgumentTypes(chosen)) {
                pushDefault(mv, t);
            }
            extraStack = Math.max(extraStack, 2 * Type.getArgumentTypes(chosen).length);
            super.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", oldDesc(chosen), false);
            return true;
        }

        /** super(...) of a class whose superclass the game removed: the same constructor of the class it extends now. */
        void superCall(String[] replaced, String desc, boolean itf) {
            GameDiff.Cls now = b.classes.get(replaced[1]);
            boolean has = replaced[1].equals("java/lang/Object") ? desc.equals("()V") : now != null && now.methods.containsKey("<init>" + desc(desc));
            if (!has && mod.mixinTargets.containsKey(mod.self)) {
                // Mixin never runs a mixin's own super(...) (see mixinSuper).
                if (!zeroSuper(inverse.getOrDefault(replaced[1], replaced[1]), replaced[1], desc, null)) {
                    super.visitMethodInsn(Opcodes.INVOKESPECIAL, inverse.getOrDefault(replaced[1], replaced[1]), "<init>", desc, itf);
                }
                return;
            }
            if (!has) {
                mod.unlinked.add("constructor of a removed superclass: " + Exposure.dotted(replaced[0]) + ".<init>" + desc + " (in " + Exposure.dotted(mod.self) + ")");
            }
            super.visitMethodInsn(Opcodes.INVOKESPECIAL, has ? inverse.getOrDefault(replaced[1], replaced[1]) : replaced[0], "<init>", desc, itf);
        }

        /**
         * An instance member a class inherited from a superclass the game removed, called on that class: the class it extends
         * now must have it, or the call cannot be carried. True when the call is settled (reported, or left on the class
         * because the stand-in it extends now declares it).
         */
        boolean inheritedGone(int opcode, String owner, String name, String desc, Decl decl) {
            if (opcode == Opcodes.INVOKESTATIC || decl == null || !decl.game()) {
                return false;
            }
            for (GameDiff.RawCls own = mod.classes.get(owner); own != null && own.sup != null; own = mod.classes.get(own.sup)) {
                String now = successor(own.sup);
                if (now != null) {
                    if (decl.owner().equals(own.sup) && now.startsWith(STANDIN) && b.method(now, name, desc(desc)) != null) {
                        return true; // the old class's stand-in, which the class extends now, has it: the call stays on the class
                    }
                    if (!decl.owner().equals(own.sup) || b.method(now, name, desc(desc)) != null
                        || opcode == Opcodes.INVOKEVIRTUAL && becameInterface(own.sup) && b.method(cls(own.sup), name, desc(desc)) != null) {
                        return false; // still there, or reached through the interface the class implements now
                    }
                    mod.unlinked.add("inherited from a removed superclass: " + Exposure.dotted(own.sup) + "." + name + desc + " (in " + Exposure.dotted(mod.self) + ")");
                    return true;
                }
            }
            return false;
        }

        void call(int opcode, String owner, String name, String desc, boolean itf) {
            String[] own = mod.renamedDecl.get(owner + "." + name + desc);
            // An override moved under a final game method is still reached through it; only super calls go straight there.
            if (own != null && (opcode == Opcodes.INVOKESPECIAL || !mod.superHook.containsKey(owner + "." + name + desc))) {
                super.visitMethodInsn(opcode, owner, own[0], oldDesc(own[1]), itf);
                return;
            }
            Decl decl = owner.startsWith("[") ? null : declaringOld(mod, owner, 'm', name, desc);
            if (inheritedGone(opcode, owner, name, desc, decl)) {
                super.visitMethodInsn(opcode, owner, name, desc, itf);
                return;
            }
            Bridge body = decl == null || decl.game() ? null : mod.bridges.getOrDefault(decl.owner(), List.of()).stream()
                .filter(br -> br.via() != null && br.target().equals(name) && br.targetDesc().equals(desc)).findFirst().orElse(null);
            if (body != null) {
                // The mod's own method moved to the game's new parameter types: a call made where they are at hand follows it.
                if (moved == null || !given(opcode, owner, desc, List.<String[]>of(new String[] { decl.owner(), body.name(), body.desc(), "mod" }))) {
                    if (DEBUG) {
                        System.err.println("kept " + name + desc + " for a call in " + where + " moved=" + (moved != null) + " src=" + (moved == null ? "" : Arrays.toString(moved.track.sources(Type.getArgumentTypes(desc)))));
                    }
                    mod.kept.add(name + desc); // the old methods of that name stay for this call (see port)
                    super.visitMethodInsn(opcode, owner, name, desc, itf);
                }
                return;
            }
            if (moved != null && decl != null && !decl.game()) {
                twin(decl.owner(), name, desc);
            }
            if (decl == null || !decl.game()) {
                if (decl != null) {
                    String[] inherited = mod.renamedDecl.get(decl.owner() + "." + name + desc);
                    if (inherited != null && (opcode == Opcodes.INVOKESPECIAL || !mod.superHook.containsKey(decl.owner() + "." + name + desc))) {
                        super.visitMethodInsn(opcode, owner, inherited[0], oldDesc(inherited[1]), itf);
                        return;
                    }
                }
                super.visitMethodInsn(opcode, owner, name, desc, itf);
                return;
            }
            String[] t = target(mod, callers, owner, 'm', name, desc, decl);
            String declNow = cls(decl.owner()), named = cls(owner);
            if (t != null && (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE) && !t[0].equals(named) && !isA(named, t[0])
                && !t[0].equals(declNow) && !isA(declNow, t[0])) {
                // A method the game moved to a class that neither the call's class nor the one declaring it is (26.2's
                // Gui.getChat() is Hud.getChat()) is not called on the old receiver, which the verifier refuses: a rule
                // says how to reach the new one (gui.hud.getChat()). Either class will do: a call on 1.18's TextComponent
                // (gone in 1.19) names MutableComponent's withStyle, which its ported value is.
                t = null;
            }
            String key = decl.owner() + "." + name + desc;
            boolean superCall = opcode == Opcodes.INVOKESPECIAL;
            if (t == null && moved != null) {
                List<String[]> now = new ArrayList<>();
                String[] m = route.method(decl.owner(), name, desc);
                String retyped = name.equals("<init>") ? retypedConstructor(decl.owner(), desc) : null;
                if (retyped != null) {
                    m = new String[] { cls(decl.owner()), name, retyped }; // a moved constructor's super(...)
                }
                now.add(new String[] { m[0], m[1], m[2], opcode == Opcodes.INVOKESTATIC ? "static" : "" });
                for (Call c : rewrites.getOrDefault(key, List.of())) {
                    now.add(new String[] { c.owner(), c.name(), c.desc(), c.isStatic() ? "static" : "" });
                }
                if (given(opcode, owner, desc, now) || onOrigin(opcode, owner, decl.owner(), name, desc)) {
                    return;
                }
                if (moved.defaulted(Type.getArgumentTypes(desc))) {
                    // The body has no value to pass here: it is not moved after all (see port), and stays as it was.
                    mod.unmoved.add(Sites.this.key);
                    super.visitMethodInsn(opcode, owner, name, desc, itf);
                    return;
                }
            }
            if (t == null && !superCall && rewritten(key, owner, desc, opcode)) {
                return;
            }
            if (t == null && defaulted(key, owner, desc, opcode)) {
                return;
            }
            if (t == null && opcode == Opcodes.INVOKEVIRTUAL || t == null && opcode == Opcodes.INVOKEINTERFACE) {
                if (chain(key, Type.getReturnType(desc), false, desc)) {
                    mod.change("call through a chain");
                    return;
                }
            }
            if (t == null) {
                String[] moved = route.method(decl.owner(), name, desc);
                int[] keep = linksNew(mod, callers, moved[0], 'm', moved[1], moved[2]) ? kept(desc(desc), moved[2]) : null;
                if (keep != null) {
                    drop(desc, keep);
                    mod.change("call arguments dropped");
                    super.visitMethodInsn(opcode, inverse.getOrDefault(moved[0], moved[0]), moved[1], oldDesc(moved[2]), itf);
                    return;
                }
                if (removals.containsKey(decl.owner() + "." + name + desc) && Type.getReturnType(desc) == Type.VOID_TYPE) {
                    drop(desc, new int[0]);
                    if (opcode != Opcodes.INVOKESTATIC) {
                        super.visitInsn(Opcodes.POP);
                    } else if (Type.getArgumentTypes(desc).length == 0) {
                        // Nothing else is written in its place: a frame after it would fall on the frame before it.
                        super.visitInsn(Opcodes.NOP);
                    }
                    mod.change("call removed");
                    return;
                }
                if (opcode == Opcodes.INVOKESPECIAL && name.equals("<init>") && mixinSuper(owner, desc)) {
                    mod.change("mixin's super call to its superclass's new constructor");
                    return;
                }
                if (opcode == Opcodes.INVOKESPECIAL && name.equals("<init>") && constructed(owner, decl.owner(), desc)) {
                    mod.change("constructor arguments from a port rule");
                    return;
                }
                if (opcode == Opcodes.INVOKESPECIAL && name.equals("<init>") && made(owner, decl.owner(), desc)) {
                    mod.change("constructor replaced by a factory");
                    return;
                }
                if (opcode != Opcodes.INVOKESPECIAL) {
                    String opDesc = opcode == Opcodes.INVOKESTATIC ? desc : "(L" + owner + ";" + desc.substring(1);
                    String[] added = fix(List.of(owner, decl.owner()), name, desc, opDesc, opcode != Opcodes.INVOKESTATIC);
                    if (added != null) {
                        invokeAdded(added);
                        mod.change("call replaced by a port rule");
                        return;
                    }
                }
                if (opcode == Opcodes.INVOKESPECIAL && !name.equals("<init>") && Sites.this.moved == null) {
                    // Not in a body moved to a game method's new parameters: a stand-in that draws through the new method
                    // (Renderable.render(pose) calls render(graphics)) would call that body back.
                    String[] added = fix(superFixes, List.of(owner, decl.owner()), name, desc, "(L" + owner + ";" + desc.substring(1), true);
                    if (added != null) {
                        invokeAdded(added);
                        mod.change("super call answered by a stand-in");
                        return;
                    }
                }
                String unlinked = "call: " + Exposure.dotted(decl.owner()) + "." + name + desc + (WHERE ? " @ " + where + (Sites.this.moved != null ? " (moved)" : "") : "");
                if (converted(opcode, owner, decl.owner(), name, desc)) {
                    return;
                }
                if (resultConverted(opcode, owner, decl.owner(), name, desc)) {
                    return;
                }
                if (relocated(decl.owner(), name, desc)) {
                    mod.change("call to where the game or loader moved the method");
                    return;
                }
                if (!voided(opcode, owner, decl.owner(), key, name, desc, unlinked)) {
                    mod.unlinked.add(unlinked);
                }
                super.visitMethodInsn(opcode, owner, name, desc, itf);
                return;
            }
            if (!t[1].equals(name) || !t[2].equals(desc(desc)) || !t[0].equals(cls(owner))) {
                mod.change("call renamed");
            }
            boolean nowInterface = itf;
            GameDiff.Cls holder = b.classes.get(t[0]);
            // The class may have become an interface under the same name (1.21.2's InteractionResult was an enum).
            if (holder != null) {
                nowInterface = (holder.flags & Opcodes.ACC_INTERFACE) != 0;
                if (opcode == Opcodes.INVOKEVIRTUAL && nowInterface) {
                    opcode = Opcodes.INVOKEINTERFACE;
                } else if (opcode == Opcodes.INVOKEINTERFACE && !nowInterface) {
                    opcode = Opcodes.INVOKEVIRTUAL;
                }
            }
            super.visitMethodInsn(opcode, inverse.getOrDefault(t[0], t[0]), t[1], oldDesc(t[2]), nowInterface);
        }

        /**
         * A call the game's own code switched to another (Registry.getHolder -> HolderGetter.get): taken when the receiver
         * is one, the arguments and the result still fit, and the mod may call it.
         */
        boolean rewritten(String key, String owner, String desc, int opcode) {
            boolean isStatic = opcode == Opcodes.INVOKESTATIC;
            for (Call c : rewrites.getOrDefault(key, List.of())) {
                if (c.isStatic() != isStatic || !widens(desc(desc), c.desc()) || !linksNew(mod, callers, c.owner(), 'm', c.name(), c.desc())) {
                    continue;
                }
                if (!isStatic && !isA(cls(owner), c.owner())) {
                    continue;
                }
                invoke(isStatic, c);
                mod.change("call rewritten from the game's code");
                return true;
            }
            return false;
        }

        /**
         * A call to a method the new release dropped, written to where the game's or loader's own files say it went, when
         * nothing in them called it to line up: the old method's whole body forwarded to one method the new release still
         * has (a deprecated alias), or, for a static method, exactly one class of the same project gained a public static
         * method of its name and signature (NeoForge 1.21.7 moved PacketDistributor.sendToServer to ClientPacketDistributor).
         * Arguments and result are the same, so the call is only retargeted. False when neither says where.
         */
        boolean relocated(String declOwner, String name, String desc) {
            GameDiff.Cls oldOwner = a.classes.get(declOwner);
            GameDiff.Meth old = oldOwner == null ? null : oldOwner.methods.get(name + desc);
            if (old == null || old.special()) {
                return false;
            }
            String newDesc = desc(desc);
            String[] to = forwardedTo(old, newDesc);
            if (to == null && old.isStatic()) {
                to = movedStatic(declOwner, name, newDesc);
            }
            if (to == null || !linksNew(mod, callers, to[0], 'm', to[1], newDesc)) {
                return false;
            }
            GameDiff.Cls holder = b.classes.get(to[0]);
            boolean itf = holder != null && (holder.flags & Opcodes.ACC_INTERFACE) != 0;
            int op = old.isStatic() ? Opcodes.INVOKESTATIC : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
            super.visitMethodInsn(op, inverse.getOrDefault(to[0], to[0]), to[1], oldDesc(newDesc), itf);
            return true;
        }

        /** {owner, name} (new names) of the one method old's body called and nothing else, when it takes and gives the same and the new release has it. */
        String[] forwardedTo(GameDiff.Meth old, String newDesc) {
            if (old.refs.length != 1 || old.refs[0].kind() != 'm') {
                return null;
            }
            GameDiff.Ref r = old.refs[0];
            if (r.owner().equals(old.owner.name) && r.name().equals(old.name) || !newDesc.equals(desc(r.desc()))) {
                return null;
            }
            GameDiff.Cls target = Exposure.declaring(b, cls(r.owner()), 'm', r.name(), newDesc);
            GameDiff.Meth m = target == null ? null : target.methods.get(r.name() + newDesc);
            if (m == null || m.isStatic() != old.isStatic()) {
                return null;
            }
            // An instance alias forwards on its own receiver: the new method must be callable on the old owner's instances.
            if (!old.isStatic() && !Exposure.assignable(b, Exposure.dotted(cls(old.owner.name)), Exposure.dotted(target.name))) {
                return null;
            }
            return new String[] { target.name, r.name() };
        }

        /** {owner, name} of the one class in the same project (its first two package names) that gained a public static name+newDesc. */
        String[] movedStatic(String declOwner, String name, String newDesc) {
            return movedStatics.computeIfAbsent(declOwner + "." + name + newDesc, key -> {
                String[] parts = cls(declOwner).split("/");
                String project = parts.length > 2 ? parts[0] + "/" + parts[1] + "/" : "";
                String found = null;
                for (GameDiff.Cls c : declaring(b, name + newDesc)) {
                    GameDiff.Meth m = c.methods.get(name + newDesc);
                    if (!m.isStatic() || (m.flags & Opcodes.ACC_PUBLIC) == 0 || !c.name.startsWith(project)) {
                        continue;
                    }
                    GameDiff.Cls before = a.classes.get(inverse.getOrDefault(c.name, c.name));
                    if (before != null && before.methods.containsKey(name + oldDesc(newDesc))) {
                        continue;
                    }
                    if (found != null) {
                        return Optional.empty();
                    }
                    found = c.name;
                }
                return found == null ? Optional.<String[]>empty() : Optional.of(new String[] { found, name });
            }).orElse(null);
        }

        /**
         * A call whose method now takes a parameter of a type the new release makes from the old one with a constructor
         * (1.21.9's PlayerList.isOp(GameProfile) takes a NameAndId, and NameAndId(GameProfile) is public): each such
         * argument is passed through that constructor, the rest as they were (they must still fit), and the result must
         * serve where the old one did.
         */
        boolean converted(int opcode, String owner, String declOwner, String name, String desc) {
            if (moved != null || opcode == Opcodes.INVOKESPECIAL) {
                return false;
            }
            boolean isStatic = opcode == Opcodes.INVOKESTATIC;
            String[] t = route.method(declOwner, name, desc);
            Type[] before = Type.getArgumentTypes(desc(desc)), after = Type.getArgumentTypes(t[2]);
            Type was = Type.getReturnType(desc(desc)), now = Type.getReturnType(t[2]);
            if (before.length != after.length || !(was.equals(now) || was.getSort() != Type.VOID && usable(now, was)) || !isStatic && !isA(cls(owner), t[0])) {
                return false;
            }
            String[] make = new String[before.length];
            boolean any = false;
            for (int i = 0; i < before.length; i++) {
                if (usable(before[i], after[i])) {
                    continue;
                }
                GameDiff.Cls into = after[i].getSort() == Type.OBJECT ? b.classes.get(after[i].getInternalName()) : null;
                String ctor = "(" + before[i].getDescriptor() + ")V";
                GameDiff.Meth c = into == null || (into.flags & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0 ? null : into.methods.get("<init>" + ctor);
                if (c == null || (c.flags & Opcodes.ACC_PUBLIC) == 0) {
                    return false;
                }
                make[i] = into.name;
                any = true;
            }
            GameDiff.Cls holder = Exposure.declaring(b, t[0], 'm', t[1], t[2]);
            GameDiff.Meth m = holder == null ? null : holder.methods.get(t[1] + t[2]);
            if (!any || m == null || m.isStatic() != isStatic || !linksNew(mod, callers, t[0], 'm', t[1], t[2])) {
                return false;
            }
            Type[] old = Type.getArgumentTypes(desc);
            int[] slots = spill(old, 0);
            for (int i = 0; i < old.length; i++) {
                if (make[i] != null) {
                    String made = inverse.getOrDefault(make[i], make[i]);
                    super.visitTypeInsn(Opcodes.NEW, made);
                    super.visitInsn(Opcodes.DUP);
                    super.visitVarInsn(old[i].getOpcode(Opcodes.ILOAD), slots[i]);
                    super.visitMethodInsn(Opcodes.INVOKESPECIAL, made, "<init>", "(" + old[i].getDescriptor() + ")V", false);
                } else {
                    super.visitVarInsn(old[i].getOpcode(Opcodes.ILOAD), slots[i]);
                }
            }
            extraStack = Math.max(extraStack, 3);
            GameDiff.Cls named = b.classes.getOrDefault(t[0], holder);
            boolean itf = (named.flags & Opcodes.ACC_INTERFACE) != 0;
            super.visitMethodInsn(isStatic ? Opcodes.INVOKESTATIC : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, inverse.getOrDefault(t[0], t[0]), t[1], oldDesc(t[2]), itf);
            mod.change("argument made into the type the game takes now, with its constructor");
            return true;
        }

        /**
         * Offers Dropped the call that took this one's place and returns nothing (the route's counterpart, or the call the
         * game's code switched to), where the old one returned a value and the arguments still fit: written if the mod
         * pops the value. A moved body's stack is read as it is written (Track), so it is not held back there.
         */
        boolean voided(int opcode, String owner, String declOwner, String key, String name, String desc, String unlinked) {
            Type ret = Type.getReturnType(desc);
            if (moved != null || opcode == Opcodes.INVOKESPECIAL || ret.getSort() == Type.VOID) {
                return false;
            }
            boolean isStatic = opcode == Opcodes.INVOKESTATIC;
            String wanted = Type.getMethodDescriptor(Type.VOID_TYPE, Type.getArgumentTypes(desc(desc)));
            List<String[]> now = new ArrayList<>();
            now.add(route.method(declOwner, name, desc));
            for (Call c : rewrites.getOrDefault(key, List.of())) {
                now.add(new String[] { c.owner(), c.name(), c.desc() });
            }
            for (String[] c : now) {
                if (Type.getReturnType(c[2]).getSort() != Type.VOID || !widens(wanted, c[2]) || !isStatic && !isA(cls(owner), c[0])) {
                    continue;
                }
                GameDiff.Cls holder = Exposure.declaring(b, c[0], 'm', c[1], c[2]);
                GameDiff.Meth m = holder == null ? null : holder.methods.get(c[1] + c[2]);
                if (m == null || m.isStatic() != isStatic || !linksNew(mod, callers, c[0], 'm', c[1], c[2])) {
                    continue;
                }
                GameDiff.Cls named = b.classes.getOrDefault(c[0], holder);
                boolean itf = (named.flags & Opcodes.ACC_INTERFACE) != 0;
                int op = isStatic ? Opcodes.INVOKESTATIC : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
                dropped.offer(op, inverse.getOrDefault(c[0], c[0]), c[1], oldDesc(c[2]), itf, ret.getSize(), unlinked);
                return true;
            }
            return false;
        }

        /** A call to a method that gained one parameter, with the one value the game's own code passes there inserted. */
        boolean defaulted(String key, String owner, String desc, int opcode) {
            Default d = defaults.get(key);
            if (d == null || opcode == Opcodes.INVOKESTATIC || opcode == Opcodes.INVOKESPECIAL) {
                return false;
            }
            Type[] now = Type.getArgumentTypes(d.to().desc());
            Type[] before = Type.getArgumentTypes(desc(desc));
            if (now.length != before.length + 1 || !isA(cls(owner), d.to().owner()) || !linksNew(mod, callers, d.to().owner(), 'm', d.to().name(), d.to().desc())) {
                return false;
            }
            Runnable value = value(d.value(), now[d.index()]);
            if (value == null) {
                return false;
            }
            Type[] args = Type.getArgumentTypes(desc);
            int[] slots = spill(args, d.index());
            value.run();
            for (int i = d.index(); i < args.length; i++) {
                super.visitVarInsn(args[i].getOpcode(Opcodes.ILOAD), slots[i]);
            }
            invoke(false, d.to());
            mod.change("call given the game's default argument");
            return true;
        }

        /** Code that pushes the value the game's code passes ("a.b.C::m" as an instance of wanted, "a.b.C.FIELD", "a.b.C.m()"), or null. */
        Runnable value(String text, Type wanted) {
            Matcher ref = REFERENCE.matcher(text);
            if (ref.matches()) {
                Object[] indy = lambda(mod, callers, ref.group(1).replace('.', '/'), ref.group(2), wanted);
                return indy == null ? null : () -> super.visitInvokeDynamicInsn((String) indy[0], (String) indy[1], (Handle) indy[2], (Object[]) indy[3]);
            }
            Matcher call = STATIC_CALL.matcher(text);
            Matcher field = STATIC_FIELD.matcher(text);
            boolean isCall = call.matches();
            if (!isCall && !field.matches()) {
                return null;
            }
            String owner = (isCall ? call : field).group(1).replace('.', '/');
            String name = (isCall ? call : field).group(2);
            GameDiff.Cls holder = b.classes.get(owner);
            if (holder == null) {
                return null;
            }
            if (isCall) {
                GameDiff.Meth m = holder.methods.values().stream()
                    .filter(x -> x.name.equals(name) && x.desc.startsWith("()") && (x.flags & Opcodes.ACC_STATIC) != 0).findFirst().orElse(null);
                if (m == null || !usable(Type.getReturnType(m.desc), wanted) || !memberOpen(mod, callers, owner, m.name + m.desc, m.flags)) {
                    return null;
                }
                boolean itf = (holder.flags & Opcodes.ACC_INTERFACE) != 0;
                return () -> super.visitMethodInsn(Opcodes.INVOKESTATIC, inverse.getOrDefault(owner, owner), m.name, oldDesc(m.desc), itf);
            }
            GameDiff.Fld f = holder.fields.get(name);
            if (f == null || (f.flags & Opcodes.ACC_STATIC) == 0 || !usable(Type.getType(f.desc), wanted) || !memberOpen(mod, callers, owner, name, f.flags)) {
                return null;
            }
            return () -> super.visitFieldInsn(Opcodes.GETSTATIC, inverse.getOrDefault(owner, owner), name, oldDesc(f.desc));
        }

        /**
         * A member read from the same receiver, step by step (1.21.2: Input.jumping -> keyPresses.jump()). The receiver is
         * on the stack under the old call's arguments (desc; "" for a field read): they are set aside, the steps read
         * fields and call no-argument methods, and the last step takes them back. The result must serve as the old one.
         */
        boolean chain(String key, Type result, boolean isStatic, String desc) {
            Chain c = chains.get(key);
            if (c == null || isStatic) {
                return false;
            }
            String[] steps = c.steps().split(",");
            Type[] args = desc.isEmpty() ? new Type[0] : Type.getArgumentTypes(desc);
            Type[] newArgs = desc.isEmpty() ? new Type[0] : Type.getArgumentTypes(desc(desc));
            // Plan every step before writing any: each one must link against the new release.
            List<String[]> plan = new ArrayList<>();
            String type = c.newOwner();
            for (int i = 0; i < steps.length; i++) {
                String[] s = steps[i].split(":", 2);
                boolean last = i == steps.length - 1;
                if (s.length != 2 || type == null) {
                    return false;
                }
                if (s[0].equals("f")) {
                    FieldHit f = fieldNew(mod, callers, type, s[1]);
                    GameDiff.Cls holder = Exposure.declaring(b, type, 'f', s[1], null);
                    boolean instance = holder == null || (holder.fields.get(s[1]).flags & Opcodes.ACC_STATIC) == 0;
                    if (f == null || !f.open() || !instance || (last && !usable(Type.getType(f.desc()), Type.getType(desc(result.getDescriptor()))))) {
                        return false;
                    }
                    plan.add(new String[] { "f", type, s[1], f.desc() });
                    type = Type.getType(f.desc()).getSort() == Type.OBJECT ? Type.getType(f.desc()).getInternalName() : null;
                    continue;
                }
                GameDiff.Meth found = null;
                for (GameDiff.Cls k = b.classes.get(type); k != null && found == null; k = k.sup == null ? null : b.classes.get(k.sup)) {
                    for (GameDiff.Meth m : k.methods.values()) {
                        Type[] params = Type.getArgumentTypes(m.desc);
                        boolean argsFit = params.length == (last ? newArgs.length : 0);
                        for (int p = 0; argsFit && p < params.length; p++) {
                            argsFit = usable(newArgs[p], params[p]);
                        }
                        boolean resultFits = !last || usable(Type.getReturnType(m.desc), Type.getType(desc(result.getDescriptor())));
                        if (m.name.equals(s[1]) && (m.flags & Opcodes.ACC_STATIC) == 0 && argsFit && resultFits) {
                            found = m;
                            break;
                        }
                    }
                }
                if (found == null || !linksNew(mod, callers, type, 'm', found.name, found.desc)) {
                    return false;
                }
                plan.add(new String[] { "m", type, found.name, found.desc });
                Type ret = Type.getReturnType(found.desc);
                type = ret.getSort() == Type.OBJECT ? ret.getInternalName() : null;
            }
            if (!isA(cls(key.substring(0, key.indexOf('.'))), c.newOwner())) {
                return false;
            }
            int[] slots = spill(args, 0);
            for (int i = 0; i < plan.size(); i++) {
                String[] p = plan.get(i);
                if (i == plan.size() - 1) {
                    for (int a = 0; a < args.length; a++) {
                        super.visitVarInsn(args[a].getOpcode(Opcodes.ILOAD), slots[a]);
                    }
                }
                if (p[0].equals("f")) {
                    super.visitFieldInsn(Opcodes.GETFIELD, inverse.getOrDefault(p[1], p[1]), p[2], oldDesc(p[3]));
                } else {
                    invoke(false, new Call(p[1], p[2], p[3], false));
                }
            }
            return true;
        }

        boolean isA(String c, String target) {
            return JarPort.this.isA(mod, c, target);
        }

        /**
         * The replacement a port rule gives for the old member (owners: the class the site names and the one declaring the
         * member), compiled into a private static method of this class that takes the old operands (opDesc, old names).
         * Returns {name, desc} of that method, or null when no rule names the member or its replacement does not link and
         * type here. args is the member's descriptor a rule's "(args)" is matched against (null for a field). With
         * receiver, the first operand is the receiver ($0).
         */
        String[] fix(List<String> owners, String name, String args, String opDesc, boolean receiver) {
            return fix(fixes, owners, name, args, opDesc, receiver);
        }

        String[] fix(Map<String, List<Fix>> book, List<String> owners, String name, String args, String opDesc, boolean receiver) {
            if (mod.selfInterface && mod.selfVersion < Opcodes.V1_8) {
                return null; // an interface before Java 8 holds no static method to write the rule in
            }
            Type[] ops = Type.getArgumentTypes(opDesc);
            Type ret = Type.getReturnType(opDesc);
            Type[] params = new Type[ops.length];
            for (int i = 0; i < ops.length; i++) {
                params[i] = Type.getType(desc(ops[i].getDescriptor()));
                if (!present(mod, params[i])) {
                    return null;
                }
            }
            Type newRet = Type.getType(desc(ret.getDescriptor()));
            for (String owner : new LinkedHashSet<>(owners)) {
                for (Fix f : book.getOrDefault(owner, List.of())) {
                    Matcher n = f.name().matcher(name);
                    if (!n.matches() || f.args() != null && (args == null || !args.startsWith(f.args())) || !holds(f)) {
                        continue;
                    }
                    try {
                        String expr = FixExpr.fill(f.expr(), n);
                        FixExpr.Node node = parsed(expr);
                        if (dropsUnnamed(f, node, ops.length - (receiver ? 1 : 0))) {
                            continue;
                        }
                        Lowering low = new Lowering(mod, callers, params, receiver, 0);
                        Code code = new Code();
                        Type got = low.lower(node, code);
                        if (ret.getSort() == Type.VOID) {
                            if (got.getSize() > 0) {
                                code.add(-got.getSize(), mv -> mv.visitInsn(got.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
                            }
                            code.add(0, mv -> mv.visitInsn(Opcodes.RETURN));
                        } else if (present(mod, newRet) && got.getSort() != Type.VOID && low.fits(got, newRet)) {
                            low.widen(got, newRet, code);
                            code.add(-newRet.getSize(), mv -> mv.visitInsn(newRet.getOpcode(Opcodes.IRETURN)));
                        } else {
                            continue;
                        }
                        String desc = Type.getMethodDescriptor(ret, ops);
                        Added m = mod.added.computeIfAbsent(expr + desc, k -> new Added(
                            "mmt$fix$" + mod.added.size() + "$" + Integer.toHexString(mod.self.hashCode()), desc, code, low.locals
                        ));
                        return new String[] { m.name(), m.desc() };
                    } catch (Refused | IllegalArgumentException notHere) {
                        // Another rule may still give one that links.
                    }
                }
            }
            return null;
        }

        /**
         * new X(old arguments), or super(...), where X's constructor changed: a rule's "new X(...)" gives the new
         * arguments from the old ones, which are set aside in fresh locals while the object being made stays on the
         * stack, and X's new constructor is called on it. The object cannot be passed to a method before its constructor
         * runs, so this is written in place rather than in an added method.
         */
        boolean constructed(String owner, String declOwner, String desc) {
            String now = cls(owner);
            if (!b.classes.containsKey(now)) {
                return false;
            }
            Type[] old = Type.getArgumentTypes(desc);
            Type[] params = new Type[old.length];
            for (int i = 0; i < old.length; i++) {
                params[i] = Type.getType(desc(old[i].getDescriptor()));
                if (!present(mod, params[i])) {
                    return false;
                }
            }
            for (String o : new LinkedHashSet<>(List.of(owner, declOwner))) {
                for (Fix f : fixes.getOrDefault(o, List.of())) {
                    Matcher n = f.name().matcher("<init>");
                    if (!n.matches() || f.args() != null && !desc.startsWith(f.args()) || !holds(f)) {
                        continue;
                    }
                    try {
                        FixExpr.Node node = parsed(FixExpr.fill(f.expr(), n));
                        if (!(node instanceof FixExpr.New made) || !made.type().replace('.', '/').equals(now) || dropsUnnamed(f, node, old.length)) {
                            continue;
                        }
                        Lowering low = new Lowering(mod, callers, params, false, nextLocal);
                        Member m = low.choose(low.methods(now, "<init>"), made.args(), now + ".<init>");
                        Code code = new Code();
                        low.arguments(m, made.args(), code);
                        for (int i = old.length - 1; i >= 0; i--) {
                            super.visitVarInsn(old[i].getOpcode(Opcodes.ISTORE), low.slots[i]);
                        }
                        nextLocal = low.locals;
                        extraStack = Math.max(extraStack, code.max);
                        code.steps.forEach(step -> step.accept(mv));
                        super.visitMethodInsn(Opcodes.INVOKESPECIAL, inverse.getOrDefault(now, now), "<init>", oldDesc(m.desc()), false);
                        return true;
                    } catch (Refused | IllegalArgumentException notHere) {
                        // Another rule may still give one that links.
                    }
                }
            }
            return false;
        }

        Map<Integer, Map<Integer, String>> constants; // call number -> argument (0 the first) -> the constant passed there

        /** A rule for one constant holds here when the call being written passes that constant as its operand. */
        boolean holds(Fix f) {
            if (f.when() == null) {
                return true;
            }
            if (constants == null) {
                int dot = key.indexOf('.'), paren = key.indexOf('(');
                constants = paren < 0 ? Map.of() : Constants.of(mod.bytes.get(key.substring(0, dot)), key.substring(dot + 1, paren), key.substring(paren));
            }
            int colon = f.when().indexOf(':');
            int k = Integer.parseInt(f.when().substring(0, colon));
            return f.when().substring(colon + 1).equals(constants.getOrDefault(callsSeen, Map.of()).get(k - 1));
        }

        void invokeAdded(String[] added) {
            super.visitMethodInsn(Opcodes.INVOKESTATIC, mod.self, added[0], added[1], mod.selfInterface);
        }

        /** A call in the new release's names, written in the old ones (the class renamer runs after), as an interface call where it is one. */
        void invoke(boolean isStatic, Call c) {
            GameDiff.Cls holder = b.classes.get(c.owner());
            boolean itf = holder != null && (holder.flags & Opcodes.ACC_INTERFACE) != 0;
            int op = isStatic ? Opcodes.INVOKESTATIC : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
            super.visitMethodInsn(op, inverse.getOrDefault(c.owner(), c.owner()), c.name(), oldDesc(c.desc()), itf);
        }

        /** Stores the arguments from index from on, which are on top of the stack, in fresh locals; returns each one's slot. */
        int[] spill(Type[] args, int from) {
            int[] slots = new int[args.length];
            for (int i = args.length - 1; i >= from; i--) {
                slots[i] = nextLocal;
                nextLocal += args[i].getSize();
                super.visitVarInsn(args[i].getOpcode(Opcodes.ISTORE), slots[i]);
            }
            return slots;
        }

        Shift moved; // set when this body moved to a game method's new parameter types

        /** -Djarport.where=true: each unlinked call says which method makes it. */
        static final boolean WHERE = Boolean.getBoolean("jarport.where");

        /**
         * A call that does not link, made in a body moved to a game method's new parameter types (moved), to a method that
         * now takes what the game passes: each candidate {owner, name, desc, "static" | "" | "mod"} (new names) is tried in
         * order. Each new parameter is an argument of its type (paired as convert pairs them), or the moved method's own new
         * parameter that the old arguments of the call were worked out from (renderBackground(poseStack) inside render(
         * GuiGraphics...) becomes renderBackground(graphics); keyPressed(key, scan, mods) becomes keyPressed(event)), or the
         * moved method's new parameter that no old one came from, the one of its type. Every old argument must be passed
         * or be worked out from a new parameter that is passed. A static call may become a call on that parameter
         * (GuiComponent.fill(poseStack, ...) -> graphics.fill(...)).
         */
        boolean given(int opcode, String owner, String desc, List<String[]> candidates) {
            Type[] args = Type.getArgumentTypes(desc);
            Integer[] src = moved.track.sources(args);
            if (src == null || Arrays.stream(src).allMatch(Objects::isNull)) {
                return false;
            }
            Type[] mine = Type.getArgumentTypes(moved.br.desc());
            int[] from = moved.br.from();
            Type[] argsNew = Arrays.stream(args).map(t -> Type.getType(desc(t.getDescriptor()))).toArray(Type[]::new);
            boolean isStatic = opcode == Opcodes.INVOKESTATIC;
            for (String[] c : candidates) {
                // The mod's own moved method is static exactly where the call is (a helper moved with its parameter).
                boolean own = c[3].equals("mod"), cStatic = c[3].equals("static") || own && isStatic;
                if (!isStatic && cStatic || !own && !linksNew(mod, callers, c[0], 'm', c[1], c[2])) {
                    continue;
                }
                List<Type> params = new ArrayList<>();
                boolean receiver = isStatic && !cStatic;
                if (receiver) {
                    params.add(Type.getObjectType(c[0]));
                }
                params.addAll(Arrays.asList(Type.getArgumentTypes(c[2])));
                Type[] want = params.toArray(Type[]::new);
                int[] argOf = pair(argsNew, want), newOf = new int[want.length];
                boolean[] used = new boolean[args.length];
                Set<Integer> passed = new HashSet<>();
                boolean ok = true;
                for (int j = 0; j < want.length && ok; j++) {
                    newOf[j] = -1;
                    // An old parameter the moved body has no value for (a default) is never passed on as a value.
                    if (argOf[j] >= 0 && !used[argOf[j]] && (src[argOf[j]] == null || from[src[argOf[j]]] >= 0)) {
                        used[argOf[j]] = true;
                        continue;
                    }
                    argOf[j] = -1;
                    for (int r = 0; r < mine.length && newOf[j] < 0; r++) {
                        if (!mine[r].equals(want[j]) || passed.contains(r)) {
                            continue;
                        }
                        for (int k = 0; k < args.length; k++) {
                            if (!used[k] && src[k] != null && from[src[k]] == r) {
                                newOf[j] = r;
                            }
                        }
                    }
                    if (newOf[j] < 0) {
                        List<Integer> free = new ArrayList<>();
                        for (int r = 0; r < mine.length; r++) {
                            int rr = r;
                            if (mine[r].equals(want[j]) && Arrays.stream(from).noneMatch(f -> f == rr)) {
                                free.add(r);
                            }
                        }
                        newOf[j] = free.size() == 1 ? free.get(0) : -1;
                    }
                    ok = newOf[j] >= 0;
                    if (ok) {
                        passed.add(newOf[j]);
                    }
                }
                for (int k = 0; k < args.length && ok; k++) {
                    ok = used[k] || src[k] != null && (from[src[k]] < 0 || passed.contains(from[src[k]]));
                }
                Type was = Type.getType(desc(Type.getReturnType(desc).getDescriptor())), now = Type.getReturnType(c[2]);
                if (!ok || !(was.equals(now) || was.getSort() == Type.VOID || usable(now, was))) {
                    continue;
                }
                int[] slots = spill(args, 0);
                for (int j = 0; j < want.length; j++) {
                    if (argOf[j] >= 0) {
                        super.visitVarInsn(args[argOf[j]].getOpcode(Opcodes.ILOAD), slots[argOf[j]]);
                    } else {
                        moved.loadNew(newOf[j]);
                    }
                }
                extraStack = Math.max(extraStack, 2 * want.length);
                GameDiff.Cls holder = b.classes.get(c[0]);
                boolean itf = own ? mod.classes.get(c[0]) != null && (mod.classes.get(c[0]).flags & Opcodes.ACC_INTERFACE) != 0
                    : holder != null && (holder.flags & Opcodes.ACC_INTERFACE) != 0;
                int op = opcode == Opcodes.INVOKESPECIAL ? opcode : cStatic ? Opcodes.INVOKESTATIC : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
                String site = opcode == Opcodes.INVOKESPECIAL ? owner : own ? c[0] : inverse.getOrDefault(c[0], c[0]);
                super.visitMethodInsn(op, site, c[1], oldDesc(c[2]), itf);
                if (was.getSort() == Type.VOID && now.getSort() != Type.VOID) {
                    super.visitInsn(now.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
                }
                mod.change("call given what the game passes now");
                return true;
            }
            return false;
        }

        /**
         * A call from a moved body to one of the mod's own helpers that cannot be overridden (static, private or final),
         * passing an old parameter worked out from a new one (renderLabel(pose, ...) inside render(GuiGraphics, ...)): the
         * helper is wanted with that parameter as the new type too, so the calls it makes on it get what the game passes
         * now. port adds it and rewrites the mod again (Mod.twins).
         */
        void twin(String owner, String name, String desc) {
            GameDiff.RawCls rc = mod.classes.get(owner);
            GameDiff.RawMeth m = rc == null ? null : rc.methods.get(name + desc);
            if (m == null || name.startsWith("<") || (m.flags & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                || (m.flags & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) == 0 && (rc.flags & Opcodes.ACC_FINAL) == 0 && !alone(rc, m)) {
                return;
            }
            Type[] args = Type.getArgumentTypes(desc);
            Integer[] src = moved.track.sources(args);
            for (int k = 0; src != null && k < args.length; k++) {
                if (src[k] == null || moved.br.via()[src[k]] == null || moved.br.from()[src[k]] < 0) {
                    continue;
                }
                Type q = Type.getArgumentTypes(moved.br.desc())[moved.br.from()[src[k]]];
                mod.twins.putIfAbsent(owner + "." + name + desc, new Object[] { k, q.getDescriptor(), moved.br.via()[src[k]] });
                return;
            }
        }

        /**
         * Whether an instance method of the mod can move like a final one: no other class of the mod declares that name
         * and descriptor (nothing of the mod overrides it, or is overridden by it), and it overrides nothing of the game
         * or the loader, so every call reaches this one body.
         */
        boolean alone(GameDiff.RawCls rc, GameDiff.RawMeth m) {
            for (GameDiff.RawCls other : mod.classes.values()) {
                if (other != rc && other.methods.containsKey(m.name + m.desc)) {
                    return false;
                }
            }
            List<String> parents = new ArrayList<>(rc.ifaces);
            if (rc.sup != null) {
                parents.add(0, rc.sup);
            }
            return gameRoot(mod, parents, m.name, m.desc) == null;
        }

        static final boolean DEBUG = Boolean.getBoolean("jarport.debug");

        /**
         * A call that does not link, in a moved body, one of whose arguments is an old parameter worked out from a new one
         * (pose = graphics.pose()): written as the game's own code wrote it with the new value there (GameDiff.Retyped's
         * reading, font.draw(pose, ...) -> graphics.drawString(font, ...)), the new parameter passed in the old one's place.
         */
        boolean onOrigin(int opcode, String owner, String declOwner, String name, String desc) {
            boolean isStatic = opcode == Opcodes.INVOKESTATIC;
            Type[] args = Type.getArgumentTypes(desc);
            Integer[] src = moved.track.sources(args);
            if (DEBUG && name.equals("draw")) {
                System.err.println("onOrigin " + declOwner + "." + name + desc + " src=" + Arrays.toString(src) + " calls=" + retypedCalls.getOrDefault(declOwner, List.of()).stream().map(RetypedCall::desc).toList());
            }
            for (RetypedCall c : retypedCalls.getOrDefault(declOwner, List.of())) {
                int k = c.at() - 1; // $0 is the receiver, $1 the first argument (static calls have no $0)
                if (!c.name().equals(name) || !c.desc().equals(desc) || src == null || k < 0 || k >= args.length || src[k] == null) {
                    continue;
                }
                int r = moved.br.from()[src[k]];
                if (r < 0 || moved.br.via()[src[k]] == null || !Type.getArgumentTypes(moved.br.desc())[r].getDescriptor().equals(c.as())) {
                    continue;
                }
                List<Type> ops = new ArrayList<>();
                if (!isStatic) {
                    ops.add(Type.getObjectType(owner));
                }
                ops.addAll(Arrays.asList(args));
                ops.set(isStatic ? k : c.at(), Type.getType(oldDesc(c.as())));
                String opDesc = Type.getMethodDescriptor(Type.getReturnType(desc), ops.toArray(Type[]::new));
                Fix fix = new Fix("game " + name, Pattern.compile(Pattern.quote(name)), null, c.expr());
                String[] added = fix(Map.of(declOwner, List.of(fix)), List.of(declOwner), name, desc, opDesc, !isStatic);
                if (DEBUG) {
                    System.err.println("  fix " + c.expr() + " " + opDesc + " -> " + Arrays.toString(added));
                }
                if (added == null) {
                    continue;
                }
                int[] slots = spill(args, 0);
                for (int i = 0; i < args.length; i++) {
                    if (i == k) {
                        moved.loadNew(r);
                    } else {
                        super.visitVarInsn(args[i].getOpcode(Opcodes.ILOAD), slots[i]);
                    }
                }
                invokeAdded(added);
                mod.change("call written on what the game passes now, as the game's own code writes it");
                return true;
            }
            return false;
        }

        /** Takes the arguments not in keep off the stack: all are stored in fresh locals and the kept ones loaded back. */
        void drop(String desc, int[] keep) {
            Type[] args = Type.getArgumentTypes(desc);
            int[] slots = spill(args, 0);
            for (int k : keep) {
                super.visitVarInsn(args[k].getOpcode(Opcodes.ILOAD), slots[k]);
            }
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) {
            types(mod, desc, where);
            if (callback(name, desc, bsm, args) || moved != null && capturing(name, desc, bsm, args)) {
                return;
            }
            for (Object o : args) {
                if (o instanceof Handle h && mod.bridges.getOrDefault(h.getOwner(), List.of()).stream()
                    .anyMatch(br -> br.via() != null && br.target().equals(h.getName()) && br.targetDesc().equals(h.getDesc()))) {
                    mod.kept.add(h.getName() + h.getDesc()); // a lambda body that moved stays too for this lambda, made where it cannot follow
                }
            }
            Object[] mapped = args.clone();
            for (int i = 0; i < mapped.length; i++) {
                if (mapped[i] instanceof Handle h) {
                    mapped[i] = handle(h);
                }
            }
            for (Object o : mapped) {
                if (o instanceof Type t) {
                    types(mod, t.getDescriptor(), where);
                }
            }
            super.visitInvokeDynamicInsn(name, desc, bsm, mapped);
        }

        /**
         * A lambda made in a moved body that captures an old parameter worked out from a new one (b -> b.render(pose, ...)):
         * its body, a method of the class, moves with that parameter as the new type as a helper does (Mod.twins), and once it
         * has, the lambda captures the new parameter and is made from the moved body.
         */
        /** A lambda for an interface method whose parameters changed type, made for its moved body (JarPort.lambdas). */
        boolean callback(String name, String desc, Handle bsm, Object[] args) {
            if (!bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") || args.length < 3 || !(args[0] instanceof Type sam)
                || !(args[1] instanceof Handle impl) || !mod.classes.containsKey(impl.getOwner())) {
                return false;
            }
            String iface = Type.getReturnType(desc).getInternalName();
            if (!a.classes.containsKey(iface) || a.method(iface, name, sam.getDescriptor()) == null
                || Exposure.declaring(b, cls(iface), 'm', name, desc(sam.getDescriptor())) != null) {
                return false;
            }
            Bridge body = mod.bridges.getOrDefault(impl.getOwner(), List.of()).stream()
                .filter(br -> br.target().equals(impl.getName()) && br.targetDesc().equals(impl.getDesc()) && !br.desc().equals(desc(impl.getDesc())))
                .findFirst().orElse(null);
            String[] now = route.method(iface, name, sam.getDescriptor());
            if (body == null || !now[1].equals(name)) {
                return false;
            }
            Object[] made = args.clone();
            made[0] = Type.getMethodType(oldDesc(now[2]));
            made[1] = new Handle(impl.getTag(), impl.getOwner(), body.name(), oldDesc(body.desc()), impl.isInterface());
            made[2] = made[0];
            super.visitInvokeDynamicInsn(name, desc, bsm, made);
            mod.change("lambda made for its interface method's new signature");
            return true;
        }

        boolean capturing(String name, String desc, Handle bsm, Object[] args) {
            if (!bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") || args.length < 3 || !(args[1] instanceof Handle impl)
                || !impl.getOwner().equals(mod.self) || impl.getTag() != Opcodes.H_INVOKESTATIC && impl.getTag() != Opcodes.H_INVOKESPECIAL
                && impl.getTag() != Opcodes.H_INVOKEVIRTUAL) {
                return false;
            }
            GameDiff.RawCls rc = mod.classes.get(mod.self);
            GameDiff.RawMeth body = rc == null ? null : rc.methods.get(impl.getName() + impl.getDesc());
            if (body == null || (body.flags & Opcodes.ACC_PRIVATE) == 0) {
                return false;
            }
            Type[] captured = Type.getArgumentTypes(desc);
            Integer[] src = moved.track.sources(captured);
            int receiver = impl.getTag() == Opcodes.H_INVOKESTATIC ? 0 : 1;
            for (int k = receiver; src != null && k < captured.length; k++) {
                if (src[k] == null || moved.br.via()[src[k]] == null || moved.br.from()[src[k]] < 0) {
                    continue;
                }
                int r = moved.br.from()[src[k]];
                Type q = Type.getArgumentTypes(moved.br.desc())[r];
                Bridge twin = mod.bridges.getOrDefault(mod.self, List.of()).stream()
                    .filter(br -> br.via() != null && br.target().equals(impl.getName()) && br.targetDesc().equals(impl.getDesc())).findFirst().orElse(null);
                if (twin == null) {
                    mod.twins.putIfAbsent(mod.self + "." + impl.getName() + impl.getDesc(), new Object[] { k - receiver, q.getDescriptor(), moved.br.via()[src[k]] });
                    return false;
                }
                if (!Type.getArgumentTypes(twin.desc())[k - receiver].equals(q)) {
                    return false;
                }
                int[] slots = spill(captured, 0);
                for (int i = 0; i < captured.length; i++) {
                    if (i == k) {
                        moved.loadNew(r);
                    } else {
                        super.visitVarInsn(captured[i].getOpcode(Opcodes.ILOAD), slots[i]);
                    }
                }
                extraStack = Math.max(extraStack, 2 * captured.length);
                Type[] now = captured.clone();
                now[k] = Type.getType(oldDesc(q.getDescriptor()));
                Object[] made = args.clone();
                made[1] = new Handle(impl.getTag(), impl.getOwner(), twin.name(), oldDesc(twin.desc()), impl.isInterface());
                super.visitInvokeDynamicInsn(name, Type.getMethodDescriptor(Type.getReturnType(desc), now), bsm, made);
                mod.change("lambda moved with what the game passes now");
                return true;
            }
            return false;
        }

        @Override
        public void visitLdcInsn(Object value) {
            fresh = false;
            if (value instanceof Type t) {
                types(mod, t.getDescriptor(), where);
            }
            super.visitLdcInsn(value instanceof Handle h ? handle(h) : value);
        }

        /** A method reference keeps its call site's types, so only a rename with the same descriptor (after class renames) links. */
        Handle handle(Handle h) {
            if (h.getTag() > Opcodes.H_PUTSTATIC) {
                String[] own = mod.renamedDecl.get(h.getOwner() + "." + h.getName() + h.getDesc());
                // A reference to an override moved under a final game method still goes through the final one.
                if (own != null && (h.getTag() == Opcodes.H_INVOKESPECIAL || !mod.superHook.containsKey(h.getOwner() + "." + h.getName() + h.getDesc()))) {
                    return new Handle(h.getTag(), h.getOwner(), own[0], oldDesc(own[1]), h.isInterface());
                }
                Decl decl = h.getOwner().startsWith("[") ? null : declaringOld(mod, h.getOwner(), 'm', h.getName(), h.getDesc());
                if (decl == null || !decl.game()) {
                    return h;
                }
                String[] t = target(mod, callers, h.getOwner(), 'm', h.getName(), h.getDesc(), decl);
                if (t == null || !t[2].equals(desc(h.getDesc()))) {
                    int tag = h.getTag();
                    if (tag == Opcodes.H_INVOKESTATIC || tag == Opcodes.H_INVOKEVIRTUAL || tag == Opcodes.H_INVOKEINTERFACE || tag == Opcodes.H_NEWINVOKESPECIAL) {
                        // X::new takes the arguments and gives an X, as the factory replacing X's constructor does.
                        String opDesc = tag == Opcodes.H_INVOKESTATIC ? h.getDesc()
                            : tag == Opcodes.H_NEWINVOKESPECIAL ? h.getDesc().substring(0, h.getDesc().indexOf(')') + 1) + "L" + h.getOwner() + ";"
                            : "(L" + h.getOwner() + ";" + h.getDesc().substring(1);
                        String[] added = fix(List.of(h.getOwner(), decl.owner()), h.getName(), h.getDesc(), opDesc, tag != Opcodes.H_INVOKESTATIC && tag != Opcodes.H_NEWINVOKESPECIAL);
                        if (added != null) {
                            mod.change("method reference replaced by a port rule");
                            return new Handle(Opcodes.H_INVOKESTATIC, mod.self, added[0], added[1], mod.selfInterface);
                        }
                    }
                    mod.unlinked.add("method reference: " + Exposure.dotted(decl.owner()) + "." + h.getName() + h.getDesc());
                    return h;
                }
                if (!t[1].equals(h.getName()) || !t[0].equals(cls(h.getOwner()))) {
                    mod.change("method reference renamed");
                }
                GameDiff.Cls holder = b.classes.get(t[0]);
                boolean itf = holder == null ? h.isInterface() : (holder.flags & Opcodes.ACC_INTERFACE) != 0;
                int tag = h.getTag() == Opcodes.H_INVOKEVIRTUAL && itf ? Opcodes.H_INVOKEINTERFACE
                    : h.getTag() == Opcodes.H_INVOKEINTERFACE && !itf ? Opcodes.H_INVOKEVIRTUAL : h.getTag();
                return new Handle(tag, inverse.getOrDefault(t[0], t[0]), t[1], oldDesc(t[2]), itf);
            }
            return h;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (opcode == Opcodes.INSTANCEOF && !type.startsWith("[") && !mod.classes.containsKey(type)) {
                String[] added = fix(List.of(type), "instanceof", null, "(Ljava/lang/Object;)Z", false);
                if (added != null) {
                    invokeAdded(added);
                    mod.change("instanceof answered by a stand-in");
                    lastLabel = null;
                    fresh = false;
                    return;
                }
            }
            types(mod, type.startsWith("[") ? type : "L" + type + ";", where);
            boolean unmakeable = opcode == Opcodes.NEW && becameUnmakeable(type);
            if (unmakeable) {
                // A class the new release made an interface or abstract (1.21.5's ClickEvent) is never made here: a
                // replacement gives the value (made()), so a null holds the object's place until then, and the frames
                // that named the object not yet constructed name the null.
                super.visitInsn(Opcodes.ACONST_NULL);
                if (lastLabel != null) {
                    nulled.add(lastLabel);
                }
            } else {
                super.visitTypeInsn(opcode, type);
            }
            lastLabel = null;
            if (opcode == Opcodes.NEW) {
                news.push(new String[] { type, null, unmakeable ? "null" : null });
                fresh = true;
            } else {
                fresh = false;
            }
        }

        /** A game class the old release could make and the new one cannot: an interface or abstract now. Not the mod's own copy. */
        boolean becameUnmakeable(String type) {
            if (mod.classes.containsKey(type)) {
                return false;
            }
            GameDiff.Cls was = a.classes.get(type), now = b.classes.get(cls(type));
            int never = Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
            return was != null && now != null && (was.flags & never) == 0 && (now.flags & never) != 0;
        }

        Label lastLabel; // the label just before the next instruction, where a new's frames find it
        final Set<Label> nulled = new HashSet<>(); // the labels of news written as null

        @Override
        public void visitLabel(Label label) {
            super.visitLabel(label);
            lastLabel = label;
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            super.visitFrame(type, numLocal, nulls(local, numLocal), numStack, nulls(stack, numStack));
        }

        Object[] nulls(Object[] items, int n) {
            if (items == null || nulled.isEmpty()) {
                return items;
            }
            Object[] out = items.clone();
            for (int i = 0; i < n && i < out.length; i++) {
                if (out[i] instanceof Label l && nulled.contains(l)) {
                    out[i] = Opcodes.NULL;
                }
            }
            return out;
        }

        // Objects made with new and not yet constructed, innermost first: {class, "dup" when new was followed by dup,
        // "null" when written as a null}.
        final Deque<String[]> news = new ArrayDeque<>();
        boolean fresh; // the last instruction was a new

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.DUP && fresh && !news.isEmpty()) {
                news.peek()[1] = "dup";
            }
            fresh = false;
            super.visitInsn(opcode);
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            fresh = false;
            super.visitVarInsn(opcode, var);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            fresh = false;
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            fresh = false;
            super.visitJumpInsn(opcode, label);
        }

        /**
         * new X(args) where X's constructor does not link and a replacement gives a value of X some other way (1.21:
         * ResourceLocation.fromNamespaceAndPath for the closed constructor): the arguments are set aside, the two
         * references to the object not yet constructed that new and dup left are dropped, and the replacement is called.
         * Only where new was followed directly by dup, as javac and kotlinc write it.
         */
        boolean made(String owner, String declOwner, String desc) {
            String[] pending = news.isEmpty() ? null : news.peek();
            if (pending == null || !pending[0].equals(owner) || pending[1] == null) {
                return false;
            }
            String[] added = fix(List.of(owner, declOwner), "<init>", desc, desc.substring(0, desc.indexOf(')') + 1) + "L" + owner + ";", false);
            if (added == null) {
                return false;
            }
            news.pop();
            Type[] args = Type.getArgumentTypes(desc);
            int[] slots = spill(args, 0);
            super.visitInsn(Opcodes.POP);
            super.visitInsn(Opcodes.POP);
            for (int i = 0; i < args.length; i++) {
                super.visitVarInsn(args[i].getOpcode(Opcodes.ILOAD), slots[i]);
            }
            invokeAdded(added);
            return true;
        }

        @Override
        public void visitMultiANewArrayInsn(String desc, int dims) {
            types(mod, desc, where);
            super.visitMultiANewArrayInsn(desc, dims);
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            if (type != null) {
                types(mod, "L" + type + ";", where);
            }
            super.visitTryCatchBlock(start, end, handler, type);
        }
    }

    /** Every game class a descriptor or signature names that the new release no longer has (and no rename covers) is reported. */
    void types(Mod mod, String desc, String where) {
        if (desc == null) {
            return;
        }
        Matcher m = CLASS_IN_DESC.matcher(desc);
        while (m.find()) {
            String t = m.group(1);
            if (a.classes.containsKey(t) && !b.classes.containsKey(cls(t)) && provided(t) == null) {
                mod.unlinked.add("class gone: " + Exposure.dotted(t) + " (in " + where + ")");
            }
        }
    }

    // ---- Stand-ins: the old API written on top of the new game, bundled into the ported jar.

    /** The package every stand-in is written in; the bundled copy is moved under one of the jar's own (bundle). */
    static final String STANDIN = "mmt/standin/";

    final Map<String, byte[]> standins = new HashMap<>(); // stand-in class (internal, unrelocated) -> its bytes
    /** Stand-ins for the JDK methods an older Java lacks (standins/jdk/), which a mod lowered to that Java calls instead. */
    Downgrade.Standins jdkStandins = Downgrade.Standins.NONE;

    /**
     * Reads the stand-ins written for this update (knowledge/standins/FROM-TO/): Java sources, one class per old game
     * class, in the package mmt.standin + the old class's package. Where the game's own code has no answer for an old
     * member (a system the update rewrote), a stand-in implements it on the new game, written from both releases' code.
     *
     * Each is compiled here against the new release as the porter reads it (stubs made from the snapshot, so no game jar
     * in Mojang names is needed), and its public static methods become port rules for the old class's members:
     *   R name(Owner self, A...)   the old instance method Owner.name(A...)R      (a field: T name(Owner self), void name(Owner, T))
     *   R name(A...)               the old static method, or static field read
     *   Owner init(A...)           the old constructor Owner(A...)
     * A stand-in whose old class the new release no longer has stands in for the class itself: references to the class
     * are moved to it. A port that uses a stand-in carries its classes (bundle), so a later update ports them as mod code.
     */
    void standins(List<Path> dirs) throws IOException {
        Map<String, byte[]> compiled = compileStandins(dirs, b);
        standins.putAll(compiled);
        for (var e : compiled.entrySet()) {
            GameDiff.Cls c = standinCls(e.getValue());
            b.classes.put(c.name, c);
        }
        for (var e : compiled.entrySet()) {
            String name = e.getKey();
            String old = name.substring(STANDIN.length());
            if (a.classes.containsKey(old) && !b.classes.containsKey(classMap.getOrDefault(old, old))) {
                // The class is gone with nothing the route moves it to. A stand-in written as static methods on one
                // new type, which the game's own classes that were the old one now are, says the class became that
                // type (1.21.11's PermissionSource, on PermissionSetSupplier); otherwise the stand-in is the class.
                // Settled before any member is matched, so a stand-in method taking another gone class matches in any order.
                String became = becameType(old, e.getValue());
                String to = became != null ? became : name;
                classMap.put(old, to);
                inverse.putIfAbsent(to, old);
            }
        }
        for (var e : compiled.entrySet()) {
            String name = e.getKey();
            if (name.contains("$") && compiled.containsKey(name.substring(0, name.lastIndexOf('$'))) && !a.classes.containsKey(name.substring(STANDIN.length()))) {
                continue; // a stand-in's own nested or lambda class
            }
            String old = name.substring(STANDIN.length());
            GameDiff.Cls was = a.classes.get(old);
            if (was == null) {
                continue;
            }
            boolean gone = name.equals(classMap.get(old));
            ClassModel cm = ClassFile.of().parse(e.getValue());
            for (MethodModel mm : cm.methods()) {
                int flags = mm.flags().flagsMask();
                String n = mm.methodName().stringValue();
                if ((flags & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) != (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC) || (flags & Opcodes.ACC_SYNTHETIC) != 0) {
                    continue;
                }
                if (n.equals("isInstance") && mm.methodType().stringValue().equals("(Ljava/lang/Object;)Z")) {
                    // x instanceof Old: what the old class meant, asked of the new game (an item that was its own class is one item now).
                    fixes.computeIfAbsent(old, k -> new ArrayList<>()).add(0, new Fix("standin " + Exposure.dotted(old) + " instanceof", Pattern.compile("instanceof"), null,
                        Exposure.dotted(name) + ".isInstance($1)"));
                    continue;
                }
                if (gone) {
                    continue; // the class's own members, which references reach through the class map
                }
                // Matched in the new release's names: each old member's descriptor carried forward against the stand-in's.
                // (Carrying the stand-in's back is ambiguous where an old class now goes by a name another old class had.)
                String desc = mm.methodType().stringValue();
                Type[] params = Type.getArgumentTypes(desc);
                Type ret = Type.getReturnType(desc);
                String self = params.length > 0 && params[0].getSort() == Type.OBJECT ? params[0].getInternalName() : null;
                String now = classMap.getOrDefault(old, old);
                boolean onOld = self != null && (self.equals(now) || isA(new Mod(), now, self));
                String rest = onOld ? "(" + String.join("", Arrays.stream(params).skip(1).map(Type::getDescriptor).toList()) + ")" : null;
                String all = "(" + String.join("", Arrays.stream(params).map(Type::getDescriptor).toList()) + ")";
                String target = Exposure.dotted(name) + "." + n;
                StringBuilder args = new StringBuilder();
                String member = null, matched = null, found;
                boolean receiver = false;
                if (n.equals("init") && ret.getSort() == Type.OBJECT && (found = oldMember(old, "<init>", false, all + "V")) != null) {
                    member = "<init>";
                    matched = found.substring(0, found.indexOf(')') + 1);
                } else if (onOld && (found = oldMember(old, n, false, rest + ret.getDescriptor())) != null) {
                    member = n;
                    matched = found.substring(0, found.indexOf(')') + 1);
                    receiver = true;
                } else if ((found = oldMember(old, n, true, desc)) != null) {
                    member = n;
                    matched = found.substring(0, found.indexOf(')') + 1);
                } else if (onOld && params.length == 1 && oldField(was, n, ret.getDescriptor())) {
                    member = n;
                    receiver = true;
                } else if (onOld && params.length == 2 && ret.getSort() == Type.VOID && oldField(was, n, params[1].getDescriptor())) {
                    member = n + "=";
                    receiver = true;
                } else if (params.length == 0 && oldField(was, n, ret.getDescriptor())) {
                    member = n;
                } else if (params.length == 1 && ret.getSort() == Type.VOID && oldField(was, n, params[0].getDescriptor())) {
                    member = n + "=";
                }
                if (member == null) {
                    throw new IllegalArgumentException("stand-in " + Exposure.dotted(name) + "." + n + desc + " answers no member of " + Exposure.dotted(old) + " in " + a.version);
                }
                for (int i = receiver ? 0 : 1; i < (receiver ? params.length : params.length + 1); i++) {
                    args.append(args.isEmpty() ? "" : ", ").append('$').append(i);
                }
                Fix answer = new Fix("standin " + Exposure.dotted(old) + "." + member, Pattern.compile(Pattern.quote(member)), matched, target + "(" + args + ")");
                fixes.computeIfAbsent(old, k -> new ArrayList<>()).add(0, answer);
                if (receiver && matched != null && !member.equals("<init>")) {
                    superFixes.computeIfAbsent(old, k -> new ArrayList<>()).add(0, answer);
                }
            }
        }
    }

    /**
     * The new type a gone class's stand-in says the class became, or null: the stand-in is a holder (final, no constructor
     * a mod can call, no public instance method) whose every public static method but isInstance takes that type first,
     * and every game class that was the old class in the old release and is still there is that type in the new one (at
     * least one). A holder on a type the game's classes did not move to answers no reference to the class itself.
     */
    String becameType(String old, byte[] bytes) {
        ClassModel cm = ClassFile.of().parse(bytes);
        if ((cm.flags().flagsMask() & Opcodes.ACC_FINAL) == 0) {
            return null;
        }
        String type = null;
        for (MethodModel mm : cm.methods()) {
            int flags = mm.flags().flagsMask();
            String n = mm.methodName().stringValue();
            if ((flags & Opcodes.ACC_PUBLIC) == 0 && !n.equals("<init>") || (flags & Opcodes.ACC_SYNTHETIC) != 0 || n.equals("<clinit>")) {
                continue;
            }
            if (n.equals("<init>")) {
                if ((flags & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0) {
                    return null;
                }
                continue;
            }
            if ((flags & Opcodes.ACC_STATIC) == 0) {
                return null;
            }
            if (n.equals("isInstance")) {
                continue;
            }
            Type[] params = Type.getArgumentTypes(mm.methodType().stringValue());
            if (params.length == 0 || params[0].getSort() != Type.OBJECT || type != null && !type.equals(params[0].getInternalName())) {
                return null;
            }
            type = params[0].getInternalName();
        }
        if (type == null || type.startsWith(STANDIN) || !b.classes.containsKey(type)) {
            return null;
        }
        boolean moved = false;
        for (GameDiff.Cls c : a.classes.values()) {
            if (c.library || c.name.equals(old) || !b.classes.containsKey(c.name) || !isA(a, c.name, old)) {
                continue;
            }
            if (!isA(b, c.name, type)) {
                return null;
            }
            moved = true;
        }
        return moved ? type : null;
    }

    /**
     * The old release's descriptor of a method of an old class (or one it inherits) whose descriptor, carried into the new
     * release's names, is newDesc; null if none. A constructor is looked up on the class itself.
     */
    String oldMember(String old, String name, boolean isStatic, String newDesc) {
        for (GameDiff.Cls c = a.classes.get(old); c != null; c = name.equals("<init>") || c.sup == null ? null : a.classes.get(c.sup)) {
            for (GameDiff.Meth m : c.methods.values()) {
                if (m.name.equals(name) && ((m.flags & Opcodes.ACC_STATIC) != 0) == isStatic && desc(m.desc).equals(newDesc)) {
                    return m.desc;
                }
            }
        }
        return null;
    }

    /** Whether the old class has a field of that name whose type, carried into the new release's names, is newType. */
    boolean oldField(GameDiff.Cls was, String name, String newType) {
        GameDiff.Fld f = was.fields.get(name);
        return f != null && desc(f.desc).equals(newType);
    }

    /**
     * Whether a stand-in is a class a mod can extend: not final, not an interface, with a constructor a subclass can call
     * (the old class written on the new release, as 1.19.4's abstract GuiComponent). A mod's subclasses of the old class
     * extend it (successor), wherever the route moves the old class's values; a holder of static methods (a final class
     * with a private constructor) answers members only.
     */
    static boolean extendable(byte[] bytes) {
        ClassModel cm = ClassFile.of().parse(bytes);
        int flags = cm.flags().flagsMask();
        return (flags & (Opcodes.ACC_FINAL | Opcodes.ACC_INTERFACE)) == 0 && (flags & Opcodes.ACC_PUBLIC) != 0
            && cm.methods().stream().anyMatch(m -> m.methodName().stringValue().equals("<init>") && (m.flags().flagsMask() & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0);
    }

    /** A compiled stand-in as the link check reads it: declarations, marked as not the game's so nothing in it is ported. */
    static GameDiff.Cls standinCls(byte[] bytes) {
        ClassModel cm = ClassFile.of().parse(bytes);
        List<String> ifs = new ArrayList<>();
        cm.interfaces().forEach(i -> ifs.add(i.asInternalName()));
        GameDiff.Cls c = new GameDiff.Cls(cm.thisClass().asInternalName(), cm.flags().flagsMask(), cm.superclass().map(x -> x.asInternalName()).orElse(null), ifs, true);
        c.library = true;
        cm.fields().forEach(f -> c.fields.put(f.fieldName().stringValue(), new GameDiff.Fld(c, f.fieldName().stringValue(), f.fieldType().stringValue(), f.flags().flagsMask())));
        for (MethodModel mm : cm.methods()) {
            GameDiff.Meth m = new GameDiff.Meth(c, mm.methodName().stringValue(), mm.methodType().stringValue(), mm.flags().flagsMask());
            c.methods.put(m.name + m.desc, m);
        }
        return c;
    }

    /** The stand-in sources under dir compiled against stubs of the release (its classes' declarations, no code). */
    static Map<String, byte[]> compileStandins(List<Path> dirs, GameDiff.Snap release) throws IOException {
        List<Path> sources = new ArrayList<>();
        for (Path dir : dirs) {
            try (var walk = Files.walk(dir)) {
                walk.filter(f -> f.toString().endsWith(".java")).sorted().forEach(sources::add);
            }
        }
        Path dir = dirs.get(0).getParent();
        // Kept by what they are compiled from (the sources and the release read), so the processes of one sweep compile once.
        java.security.MessageDigest sha;
        try {
            sha = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        sha.update((release.version + "|" + release.loaderVersion + "|" + release.classes.size() + "|java " + standinJava(release.version)).getBytes(StandardCharsets.UTF_8));
        for (Path f : sources) {
            sha.update(dir.relativize(f).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
            sha.update(Files.readAllBytes(f));
        }
        Path kept = Path.of(System.getProperty("user.home"), ".mmt", "jar-port", "standins", HexFormat.of().formatHex(sha.digest()).substring(0, 24));
        if (Files.isDirectory(kept)) {
            return readClasses(kept);
        }
        Path work = Files.createTempDirectory("mmt-standins");
        try {
            Path stubs = work.resolve("stubs.jar"), out = Files.createDirectories(work.resolve("classes"));
            writeStubs(release, stubs);
            javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
            if (javac == null) {
                throw new IOException("stand-ins need a JDK's compiler (javax.tools), and this Java has none");
            }
            List<String> command = new ArrayList<>(List.of("--release", String.valueOf(standinJava(release.version)), "-proc:none", "-nowarn", "-encoding", "UTF-8", "-cp", stubs.toString(), "-d", out.toString()));
            sources.forEach(f -> command.add(f.toString()));
            StringWriter errors = new StringWriter();
            int exit = javac.run(null, new PrintStream(OutputStream.nullOutputStream()), new PrintStream(new OutputStream() {
                public void write(int x) {
                    errors.write(x);
                }
            }, true), command.toArray(String[]::new));
            if (exit != 0) {
                throw new IOException("stand-ins in " + dir + " do not compile against " + release.version + ":\n" + errors);
            }
            Map<String, byte[]> classes = readClasses(out);
            for (String name : classes.keySet()) {
                if (!name.startsWith(STANDIN)) {
                    throw new IOException("stand-in " + name + " is not in the package " + STANDIN.replace('/', '.'));
                }
            }
            Files.createDirectories(kept.getParent());
            try {
                Files.move(out, kept);
            } catch (IOException another) {
                // Another process kept the same classes first.
            }
            return classes;
        } finally {
            try (var walk = Files.walk(work)) {
                walk.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        }
    }

    /**
     * The Java a stand-in is compiled for: the one the new release runs on (Java 8 before 1.17, 16 for 1.17, 17 from 1.18,
     * 21 from 1.20.5, as Mojang's version files name), at most 21, which every later release runs too.
     */
    static int standinJava(String release) {
        if (compareVersions(release, "1.20.5") >= 0) {
            return 21;
        }
        if (compareVersions(release, "1.18") >= 0) {
            return 17;
        }
        return compareVersions(release, "1.17") >= 0 ? 16 : 8;
    }

    static Map<String, byte[]> readClasses(Path root) throws IOException {
        Map<String, byte[]> classes = new TreeMap<>();
        try (var walk = Files.walk(root)) {
            for (Path f : walk.filter(x -> x.toString().endsWith(".class")).toList()) {
                String name = root.relativize(f).toString().replace('\\', '/');
                classes.put(name.substring(0, name.length() - 6), Files.readAllBytes(f));
            }
        }
        return classes;
    }

    /**
     * A jar javac can compile against in place of the release: every class's declarations (flags, supertypes, fields,
     * methods) with no code and no generic signatures, and the nesting javac needs to read Outer.Inner.
     */
    static void writeStubs(GameDiff.Snap release, Path jar) throws IOException {
        Map<String, List<String>> nestedIn = new HashMap<>();
        for (String name : release.classes.keySet()) {
            int at = name.lastIndexOf('$');
            if (at > 0 && release.classes.containsKey(name.substring(0, at)) && !Character.isDigit(name.charAt(at + 1))) {
                nestedIn.computeIfAbsent(name.substring(0, at), k -> new ArrayList<>()).add(name);
            }
        }
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (GameDiff.Cls c : release.classes.values()) {
                if (c.name.startsWith("java/") || c.name.startsWith("javax/") || c.name.startsWith("jdk/") || c.name.startsWith("sun/")) {
                    continue;
                }
                ClassWriter w = new ClassWriter(0);
                // Everything is public here: a stand-in may use what the game keeps closed, which the port then opens
                // through the mod's access transformer (bundle).
                int flags = c.flags & ~(Opcodes.ACC_SUPER | 0x10000 | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED) | Opcodes.ACC_PUBLIC;
                w.visit(Opcodes.V21, flags, c.name, null, c.sup == null && !c.name.equals("java/lang/Object") ? "java/lang/Object" : c.sup,
                    c.ifaces.toArray(String[]::new));
                List<String> inner = new ArrayList<>(nestedIn.getOrDefault(c.name, List.of()));
                int at = c.name.lastIndexOf('$');
                if (at > 0 && nestedIn.getOrDefault(c.name.substring(0, at), List.of()).contains(c.name)) {
                    inner.add(c.name);
                }
                for (String n : inner) {
                    GameDiff.Cls ic = release.classes.get(n);
                    String outer = n.substring(0, n.lastIndexOf('$'));
                    boolean takesOuter = ic.methods.values().stream().anyMatch(m -> m.name.equals("<init>") && m.desc.startsWith("(L" + outer + ";"));
                    int innerFlags = ic.flags & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_FINAL | Opcodes.ACC_INTERFACE
                        | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ENUM | Opcodes.ACC_ANNOTATION);
                    w.visitInnerClass(n, outer, n.substring(n.lastIndexOf('$') + 1), innerFlags | (takesOuter && (ic.flags & Opcodes.ACC_INTERFACE) == 0 ? 0 : Opcodes.ACC_STATIC));
                }
                for (GameDiff.Fld f : c.fields.values()) {
                    w.visitField(open(f.flags), f.name, f.desc, null, null).visitEnd();
                }
                for (GameDiff.Meth m : c.methods.values()) {
                    w.visitMethod(open(m.flags), m.name, m.desc, null, null).visitEnd();
                }
                w.visitEnd();
                out.putNextEntry(new ZipEntry(c.name + ".class"));
                out.write(w.toByteArray());
                out.closeEntry();
            }
        }
    }

    static int open(int flags) {
        return flags & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED) | Opcodes.ACC_PUBLIC;
    }

    /**
     * The game members the stand-ins use that the game keeps closed: opened through the mod's access transformer when the
     * loader reads it, else each is reported, since the stand-in would fail where it reaches the member.
     */
    void openFor(Mod mod, Collection<String> used) {
        for (String name : used) {
            new ClassReader(standins.get(name)).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String sig, String[] ex) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int op, String owner, String mn, String md, boolean itf) {
                            need(owner, 'm', mn, md);
                        }

                        @Override
                        public void visitFieldInsn(int op, String owner, String fn, String fd) {
                            need(owner, 'f', fn, fd);
                        }
                    };
                }

                void need(String owner, char kind, String member, String desc) {
                    GameDiff.Cls site = b.classes.get(owner);
                    GameDiff.Cls holder = site == null || site.library ? null : Exposure.declaring(b, owner, kind, member, kind == 'f' ? null : desc);
                    if (holder == null || holder.library) {
                        return;
                    }
                    int flags = kind == 'f' ? holder.fields.get(member).flags : holder.methods.get(member + desc).flags;
                    boolean classOpen = (site.flags & Opcodes.ACC_PUBLIC) != 0, memberOpen = (flags & Opcodes.ACC_PUBLIC) != 0;
                    if (classOpen && memberOpen) {
                        return;
                    }
                    String line = Exposure.dotted(holder.name) + " " + member + (kind == 'f' ? "" : desc);
                    if (!mod.canWiden || holder.loader) {
                        mod.unlinked.add("stand-in uses a closed member this mod cannot open: " + line + " (in " + Exposure.dotted(name) + ")");
                        return;
                    }
                    if (!classOpen) {
                        mod.grants.add("public " + Exposure.dotted(owner));
                    }
                    if (!memberOpen) {
                        mod.grants.add("public " + line);
                    }
                }
            }, ClassReader.SKIP_DEBUG);
        }
    }

    /**
     * The stand-ins a ported jar's classes call, carried in the jar under a package of its own (mmt.standin.mID, ID from the
     * jar's whole name): two mods carrying one stand-in never put two copies of a package on the game. Adds them to entries and
     * points the classes that use them there; nothing when none is used.
     */
    void bundle(Mod mod, Map<String, byte[]> rewritten, Map<String, byte[]> entries, String jarName) {
        if (standins.isEmpty()) {
            return;
        }
        Pattern ref = Pattern.compile(Pattern.quote(STANDIN) + "[\\w/$]+");
        Set<String> used = new TreeSet<>();
        Deque<byte[]> todo = new ArrayDeque<>(rewritten.values());
        while (!todo.isEmpty()) {
            Matcher m = ref.matcher(new String(todo.pop(), StandardCharsets.ISO_8859_1));
            while (m.find()) {
                for (String name : standins.keySet()) {
                    if ((name.equals(m.group()) || name.startsWith(m.group() + "$")) && used.add(name)) {
                        todo.push(standins.get(name));
                    }
                }
            }
        }
        if (used.isEmpty()) {
            return;
        }
        openFor(mod, used);
        Set<String> types = standinTypes();
        String home = standinHome(jarName, a.version, b.version);
        // Only this update's stand-ins move: an earlier port's, carried in the jar already, are the mod's own classes now.
        // A stand-in type goes where every jar's goes, so a mod's call and its nested library's declaration name one type.
        org.objectweb.asm.commons.Remapper move = new org.objectweb.asm.commons.Remapper(Opcodes.ASM9) {
            @Override
            public String map(String name) {
                return types.contains(name) ? typesHome() + name.substring(STANDIN.length())
                    : standins.containsKey(name) ? home + name.substring(STANDIN.length()) : name;
            }
        };
        for (var e : rewritten.entrySet()) {
            if (new String(e.getValue(), StandardCharsets.ISO_8859_1).contains(STANDIN)) {
                e.setValue(relocate(e.getValue(), move));
            }
        }
        for (String name : used) {
            if (types.contains(name)) {
                typesUsed = true;
                continue;
            }
            entries.put(move.map(name) + ".class", relocate(standins.get(name), move));
            rewritten.put(move.map(name) + ".class", relocate(standins.get(name), move));
        }
        mod.change("stand-ins carried: " + used.stream().filter(n -> !n.matches(".*\\$\\d+$")).map(n -> Exposure.dotted(n.substring(STANDIN.length()))).toList());
    }

    /**
     * The jar's classes lowered to the Java the new release runs on, when they were built for a newer one (Downgrade): the
     * release's JVM refuses a newer class file outright. What that Java cannot run is reported, and the JDK stand-ins the
     * classes call now are carried under a package of the jar's own.
     */
    void lowerJava(Mod mod, Map<String, byte[]> rewritten, Map<String, byte[]> entries, String jarName) {
        int java = b.java > 0 ? b.java : standinJava(b.version);
        Set<String> breaks = new TreeSet<>();
        Map<String, Integer> changed = new TreeMap<>();
        Map<String, byte[]> carried = new Downgrade(java, jdkStandins).lower(rewritten, standinHome(jarName, "java", "java" + java), breaks, changed);
        if (changed.isEmpty()) {
            return;
        }
        mod.lowered = java;
        mod.unlinked.addAll(breaks);
        changed.forEach((k, v) -> mod.changed.merge(k, v, Integer::sum));
        entries.putAll(carried);
        rewritten.putAll(carried);
        if (!carried.isEmpty()) {
            mod.change("JDK stand-ins carried: " + carried.keySet().stream().filter(n -> !n.matches(".*\\$\\d+\\.class$"))
                .map(n -> Exposure.dotted(n.substring(n.indexOf("/java/") + 1, n.length() - 6))).toList());
        }
    }

    static final Pattern MIXIN_LEVEL = Pattern.compile("(\"compatibilityLevel\"\\s*:\\s*\"JAVA_)(\\d+)(\")");

    /**
     * A Mixin config asking for a newer Java than the jar was lowered to asks for that one: Mixin refuses a level the JVM
     * running it does not reach (JAVA_17 on Java 8), and the lowered classes need no more.
     */
    static byte[] mixinLevel(byte[] json, int java) {
        String text = new String(json, StandardCharsets.UTF_8);
        Matcher m = MIXIN_LEVEL.matcher(text);
        if (!m.find() || Integer.parseInt(m.group(2)) <= java) {
            return json;
        }
        return m.replaceFirst("$1" + java + "$3").getBytes(StandardCharsets.UTF_8);
    }

    /** fabric.mod.json asking for the Java the jar was lowered to: Fabric Loader refuses a mod whose "java" dependency the JVM does not meet. */
    static byte[] runsOn(byte[] fmj, int java) {
        if (java == 0) {
            return fmj;
        }
        try {
            Map<String, Object> meta = Json.object(Json.parse(new String(fmj, StandardCharsets.UTF_8)));
            if (meta.get("depends") instanceof Map<?, ?> depends && depends.containsKey("java")) {
                Json.object(depends).put("java", ">=" + java);
                return Json.write(meta).getBytes(StandardCharsets.UTF_8);
            }
        } catch (RuntimeException unreadable) {
            // the loader cannot read it either
        }
        return fmj;
    }

    /**
     * The package a jar carries one update's stand-ins under, from the jar's whole name and the update: a jar nested in a
     * mod ("MOD.jar!INNER.jar") is a module of its own on NeoForge, which refuses one package in two modules, and a port
     * across several updates carries each one's stand-in for a class (Screen in 1.20 and again in 1.21.11) side by side.
     */
    static String standinHome(String jarName, String from, String to) {
        return STANDIN + "m" + Integer.toHexString((jarName + "|" + from + "-" + to).hashCode() & 0x7fffffff) + "/";
    }

    boolean typesUsed; // a jar of the tree being ported uses a stand-in type: its outermost jar carries typesJar

    /**
     * This update's stand-ins for classes it dropped, which stand in for the class itself, with their nested classes and
     * every stand-in they use. Mods name these types in their own methods and fields, and so do the libraries nested in
     * them, so all of them name one copy: a jar of its own (typesJar) the loader loads once, whichever mods carry it.
     */
    Set<String> standinTypes() {
        if (types == null) {
            types = new TreeSet<>();
            Deque<String> todo = new ArrayDeque<>();
            for (String name : standins.keySet()) {
                String outer = name.contains("$") ? name.substring(0, name.indexOf('$')) : name;
                if (classMap.containsValue(name) || classMap.containsValue(outer)) {
                    todo.push(name);
                }
            }
            Pattern ref = Pattern.compile(Pattern.quote(STANDIN) + "[\\w/$]+");
            while (!todo.isEmpty()) {
                String name = todo.pop();
                if (!types.add(name)) {
                    continue;
                }
                Matcher m = ref.matcher(new String(standins.get(name), StandardCharsets.ISO_8859_1));
                while (m.find()) {
                    for (String other : standins.keySet()) {
                        if (other.equals(m.group()) || other.startsWith(m.group() + "$")) {
                            todo.push(other);
                        }
                    }
                }
            }
        }
        return types;
    }

    Set<String> types;

    /** The package of this update's stand-in types, the same in every jar (only its jar of types holds it). */
    String typesHome() {
        return STANDIN + "u" + typesTag() + "/";
    }

    String typesTag() {
        return Integer.toHexString((a.version + "-" + b.version).hashCode() & 0x7fffffff);
    }

    /**
     * Nests the update's jar of stand-in types in the outermost jar, as the loader's jar-in-jar does for any library
     * (META-INF/jarjar/ and its metadata.json on NeoForge, META-INF/jars/ and fabric.mod.json's "jars" on Fabric): the
     * loader loads one copy of a nested library however many mods carry it, so the types are one module on NeoForge
     * and one class each on Fabric. Returns where the jar goes; the metadata is written into entries.
     */
    String carryTypes(Map<String, byte[]> entries, boolean fabric) {
        String file = "mmt-standin-types-" + typesTag() + ".jar";
        if (fabric) {
            String path = "META-INF/jars/" + file;
            Map<String, Object> meta = Json.object(Json.parse(new String(entries.get("fabric.mod.json"), StandardCharsets.UTF_8)));
            List<Object> jars = new ArrayList<>(meta.get("jars") instanceof List<?> l ? l : List.of());
            jars.add(new LinkedHashMap<>(Map.of("file", path)));
            meta.put("jars", jars);
            entries.put("fabric.mod.json", Json.write(meta).getBytes(StandardCharsets.UTF_8));
            return path;
        }
        String path = "META-INF/jarjar/" + file;
        byte[] list = entries.get("META-INF/jarjar/metadata.json");
        Map<String, Object> meta = list == null ? new LinkedHashMap<>() : Json.object(Json.parse(new String(list, StandardCharsets.UTF_8)));
        List<Object> jars = new ArrayList<>(meta.get("jars") instanceof List<?> l ? l : List.of());
        Map<String, Object> jar = new LinkedHashMap<>();
        // Keys put one by one: Map.of's order changes from one JVM to the next, and the same port must write the same jar.
        Map<String, Object> identifier = new LinkedHashMap<>();
        identifier.put("group", "mmt.standin");
        identifier.put("artifact", "types-" + typesTag());
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("range", "[1,)");
        version.put("artifactVersion", "1");
        jar.put("identifier", identifier);
        jar.put("version", version);
        jar.put("path", path);
        jar.put("isObfuscated", false);
        jars.add(jar);
        meta.put("jars", jars);
        entries.put("META-INF/jarjar/metadata.json", Json.write(meta).getBytes(StandardCharsets.UTF_8));
        return path;
    }

    /**
     * The jar of this update's stand-in types: every one of them, so the copy the loader picks holds what each mod
     * carrying it needs. A game library on NeoForge (loaded with the game, whose classes it uses), a mod on Fabric.
     */
    byte[] typesJar() throws IOException {
        org.objectweb.asm.commons.Remapper move = new org.objectweb.asm.commons.Remapper(Opcodes.ASM9) {
            @Override
            public String map(String name) {
                return standinTypes().contains(name) ? typesHome() + name.substring(STANDIN.length()) : name;
            }
        };
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nFMLModType: GAMELIBRARY\n".getBytes(StandardCharsets.UTF_8));
        files.put("fabric.mod.json", ("{\"schemaVersion\": 1, \"id\": \"mmt-standin-types-" + typesTag() + "\", \"version\": \"1\", \"name\": \"Stand-ins for "
            + a.version + " -> " + b.version + "\"}").getBytes(StandardCharsets.UTF_8));
        for (String name : standinTypes()) {
            files.put(move.map(name) + ".class", relocate(standins.get(name), move));
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(buffer)) {
            for (var e : files.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTime(0L);
                out.putNextEntry(entry);
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    static byte[] relocate(byte[] bytes, org.objectweb.asm.commons.Remapper move) {
        ClassReader r = new ClassReader(bytes);
        ClassWriter w = new ClassWriter(0);
        r.accept(new ClassRemapper(w, move), 0);
        return w.toByteArray();
    }

    // ---- Copies of the newer game's code: a port back to an older release meets members the older one does not have yet.

    /**
     * On a port back to an older release (a newer than b): the game methods the jar calls that b does not have yet and
     * nothing else answers (a route, a rule, a stand-in), copied from a's own code into a class the jar carries
     * (copiedHome), where a's code uses only what b has, or other such methods. Calls to them are pointed at the copies,
     * an instance method's receiver passed first, and the copies are then ported with the jar's own classes, which
     * checks and opens what they use in b. The copy is the newer game's own code run on the older one: a method using a
     * field, constructor, class or super call b lacks has no older form here, and its call stays a break. A port across
     * several updates copies on each step, so a copy carried from one step is the jar's own code on the next. Returns
     * the members copied (owner.name+desc, a's names), the jar's classes rewritten in entries.
     */
    List<String> copyNewer(Map<String, byte[]> entries, String jarName) {
        if (compareVersions(a.version, b.version) <= 0) {
            return List.of();
        }
        GameCode code = code(a);
        if (code == null) {
            return List.of();
        }
        Map<String, GameDiff.RawCls> own = new HashMap<>();
        for (var e : entries.entrySet()) {
            if (e.getKey().endsWith(".class") && !e.getKey().startsWith("META-INF/versions/")) {
                try {
                    GameDiff.RawCls rc = GameDiff.parse(e.getValue());
                    own.put(rc.name, rc);
                } catch (RuntimeException unreadable) {
                    // read (and reported) with the jar's classes
                }
            }
        }
        Copier copier = new Copier(code, own, copiedHome(jarName));
        Map<String, byte[]> redirected = new HashMap<>();
        for (var e : entries.entrySet()) {
            if (!e.getKey().endsWith(".class") || e.getKey().startsWith("META-INF/versions/") || otherLoader(e.getValue())) {
                continue;
            }
            byte[] out = copier.redirect(e.getValue());
            if (out != null) {
                redirected.put(e.getKey(), out);
            }
        }
        if (copier.copies.isEmpty()) {
            return List.of();
        }
        entries.putAll(redirected);
        entries.putAll(copier.write());
        return copier.called.stream().sorted().map(Exposure::dotted).toList();
    }

    /**
     * The package a jar carries one step's copies of the newer game's code under, its own (as standinHome): a jar nested
     * in another is a module of its own on NeoForge, which refuses one package in two modules.
     */
    String copiedHome(String jarName) {
        return standinHome(jarName, a.version, b.version) + "copied/";
    }

    /** One jar's copies of the newer game's methods (copyNewer): which methods can be copied, the copies, and the calls moved to them. */
    final class Copier {
        final GameCode code;
        final Map<String, GameDiff.RawCls> own;
        final String home;
        final Map<String, String[]> copies = new LinkedHashMap<>(); // decl.name+desc (a's names) -> {copy class, copy name, copy desc}
        final Set<String> refused = new HashSet<>();
        final Set<String> called = new TreeSet<>(); // the copied methods the jar's own classes call
        final Map<String, Set<String>> names = new HashMap<>(); // copy class -> name+desc taken

        Copier(GameCode code, Map<String, GameDiff.RawCls> own, String home) {
            this.code = code;
            this.own = own;
            this.home = home;
        }

        /** One of the jar's classes with its calls (and method references) to the newer game's methods pointed at their copies, or null when it calls none. */
        byte[] redirect(byte[] bytes) {
            boolean[] moved = { false };
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, 0);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, signature, exceptions)) {
                        @Override
                        public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                            String[] to = target(op, owner, n, d);
                            if (to != null) {
                                moved[0] = true;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, to[0], to[1], to[2], false);
                                return;
                            }
                            super.visitMethodInsn(op, owner, n, d, itf);
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
                            Object[] now = args.clone();
                            for (int i = 0; i < now.length; i++) {
                                if (now[i] instanceof Handle h && h.getTag() >= Opcodes.H_INVOKEVIRTUAL && !h.getName().startsWith("<")) {
                                    String[] to = target(h.getTag() == Opcodes.H_INVOKESTATIC ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL, h.getOwner(), h.getName(), h.getDesc());
                                    if (to != null) {
                                        moved[0] = true;
                                        now[i] = new Handle(Opcodes.H_INVOKESTATIC, to[0], to[1], to[2], false);
                                    }
                                }
                            }
                            super.visitInvokeDynamicInsn(n, d, bsm, now);
                        }
                    };
                }
            }, 0);
            return moved[0] ? writer.toByteArray() : null;
        }

        /**
         * The copy a call from the jar goes to ({class, name, desc}), made here when it can be; null when the call stays.
         * A super call (op INVOKESPECIAL) runs the game's code whatever the jar's class overrides.
         */
        String[] target(int op, String owner, String name, String desc) {
            if (name.startsWith("<") || owner.startsWith("[")) {
                return null;
            }
            // A call on one of the jar's classes reaches the game's method through the game class it extends.
            String game = owner;
            Set<String> seen = new HashSet<>();
            while (own.containsKey(game) && seen.add(game)) {
                GameDiff.RawCls rc = own.get(game);
                if (rc.methods.containsKey(name + desc) && op != Opcodes.INVOKESPECIAL || rc.sup == null) {
                    return null; // the jar's own method, or one it overrides
                }
                game = rc.sup;
            }
            GameDiff.Cls decl = Exposure.declaring(a, game, 'm', name, desc);
            if (decl == null || decl.library || decl.loader || !GameDiff.gameClass(decl.name) || handled(game, name, desc) || handled(decl.name, name, desc)
                || Exposure.declaring(b, cls(game), 'm', name, desc(desc)) != null || Exposure.declaring(b, cls(decl.name), 'm', name, desc(desc)) != null) {
                return null;
            }
            for (GameDiff.RawCls rc : op == Opcodes.INVOKESPECIAL || op == Opcodes.INVOKESTATIC ? List.<GameDiff.RawCls>of() : own.values()) {
                if (rc.methods.containsKey(name + desc) && (rc.flags & Opcodes.ACC_INTERFACE) == 0 && reaches(rc.name, decl.name)) {
                    return null; // a class of the jar overrides it: a call on the game type may run that override
                }
            }
            String key = decl.name + "." + name + desc;
            // A method copied on the way to one that cannot be (a cycle back to it) goes with it.
            Set<String> before = new HashSet<>(copies.keySet());
            if (!copyable(decl.name, name, desc, new LinkedHashSet<>())) {
                copies.keySet().retainAll(before);
                return null;
            }
            called.add(key);
            return copies.get(key);
        }

        /** Whether c (the jar's or a's class) is decl or extends it. */
        boolean reaches(String c, String decl) {
            Deque<String> todo = new ArrayDeque<>(List.of(c));
            Set<String> seen = new HashSet<>();
            while (!todo.isEmpty()) {
                String x = todo.pop();
                if (x.equals(decl)) {
                    return true;
                }
                if (!seen.add(x)) {
                    continue;
                }
                GameDiff.RawCls rc = own.get(x);
                GameDiff.Cls gc = rc == null ? a.classes.get(x) : null;
                String sup = rc != null ? rc.sup : gc != null ? gc.sup : null;
                if (sup != null) {
                    todo.push(sup);
                }
                todo.addAll(rc != null ? rc.ifaces : gc != null ? gc.ifaces : List.of());
            }
            return false;
        }

        /**
         * Whether a's method decl.name+desc (a's names) can be copied: it has code, and every class, field and method it
         * uses is in b, is answered by the port (a route, rule or stand-in, which ports the copy as the jar's code), or
         * is another method of a that can be copied. Each copyable method gets its copy's place in copies.
         */
        boolean copyable(String decl, String name, String desc, Set<String> closure) {
            String key = decl + "." + name + desc;
            if (copies.containsKey(key) || closure.contains(key)) {
                return true;
            }
            if (refused.contains(key)) {
                return false;
            }
            String jar = code.toJar.getOrDefault(decl, decl);
            byte[] bytes = code.bytes.apply(jar);
            if (bytes == null) {
                refused.add(key);
                return false;
            }
            for (Type t : Type.getArgumentTypes(desc)) {
                if (!present(t)) {
                    refused.add(key);
                    return false;
                }
            }
            if (!present(Type.getReturnType(desc))) {
                refused.add(key);
                return false;
            }
            closure.add(key);
            List<String[]> calls = new ArrayList<>(); // {Mojang owner, name, desc} of a's methods it calls that b lacks
            boolean[] ok = { false };
            boolean[] instance = { false };
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                    if (!(code.names.method(jar, n, d) + code.names.desc(d)).equals(name + desc) || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                        return null;
                    }
                    ok[0] = true;
                    instance[0] = (access & Opcodes.ACC_STATIC) == 0;
                    return new MethodVisitor(Opcodes.ASM9) {
                        void method(int op, String o, String n2, String d2) {
                            if (!ok[0] || o.startsWith("[")) {
                                return;
                            }
                            String declObf = code.declarer(o, n2, d2, false);
                            String mo = code.names.cls(o), md = code.names.desc(d2);
                            String mn = n2.startsWith("<") ? n2 : code.names.method(declObf, n2, d2);
                            String md0 = code.names.cls(declObf);
                            if (!GameDiff.gameClass(mo)) {
                                GameDiff.Cls lib = a.classes.get(mo);
                                ok[0] &= lib == null || Exposure.declaring(b, mo, 'm', mn, md) != null;
                                return;
                            }
                            if (op == Opcodes.INVOKESPECIAL && !mn.equals("<init>") && !md0.equals(decl)) {
                                ok[0] = false; // a super call, which only the class itself can make
                                return;
                            }
                            if (Exposure.declaring(b, cls(mo), 'm', mn, desc(md)) != null || handled(mo, mn, md) || handled(md0, mn, md)) {
                                return;
                            }
                            if (mn.startsWith("<")) {
                                ok[0] = false; // a constructor b does not have
                                return;
                            }
                            calls.add(new String[] { md0, mn, md });
                        }

                        @Override
                        public void visitMethodInsn(int op, String o, String n2, String d2, boolean itf) {
                            method(op, o, n2, d2);
                        }

                        @Override
                        public void visitFieldInsn(int op, String o, String n2, String d2) {
                            String declObf = code.declarer(o, n2, d2, true);
                            String mo = code.names.cls(declObf), mn = code.names.field(declObf, n2), md = code.names.desc(d2);
                            if (!GameDiff.gameClass(mo)) {
                                ok[0] &= a.classes.get(mo) == null || Exposure.declaring(b, mo, 'f', mn, null) != null;
                                return;
                            }
                            GameDiff.Cls holder = Exposure.declaring(b, cls(mo), 'f', mn, null);
                            ok[0] &= holder != null && holder.fields.get(mn).desc.equals(desc(md)) || handled(mo, mn, "");
                        }

                        @Override
                        public void visitTypeInsn(int op, String t) {
                            ok[0] &= present(Type.getObjectType(code.names.cls(t)));
                        }

                        @Override
                        public void visitMultiANewArrayInsn(String d2, int dims) {
                            ok[0] &= present(Type.getType(code.names.desc(d2)));
                        }

                        @Override
                        public void visitLdcInsn(Object value) {
                            if (value instanceof Type t) {
                                ok[0] &= t.getSort() == Type.METHOD ? describedPresent(code.names.desc(t.getDescriptor())) : present(Type.getType(code.names.desc(t.getDescriptor())));
                            } else if (value instanceof Handle || value instanceof ConstantDynamic) {
                                ok[0] = false;
                            }
                        }

                        @Override
                        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                            ok[0] &= type == null || present(Type.getObjectType(code.names.cls(type)));
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String n2, String d2, Handle bsm, Object... args) {
                            ok[0] &= bsm.getOwner().startsWith("java/") && describedPresent(code.names.desc(d2));
                            for (Object arg : args) {
                                if (arg instanceof Handle h) {
                                    int op = switch (h.getTag()) {
                                        case Opcodes.H_INVOKESTATIC -> Opcodes.INVOKESTATIC;
                                        case Opcodes.H_INVOKESPECIAL -> Opcodes.INVOKESPECIAL;
                                        case Opcodes.H_NEWINVOKESPECIAL -> Opcodes.INVOKESPECIAL;
                                        case Opcodes.H_INVOKEINTERFACE -> Opcodes.INVOKEINTERFACE;
                                        case Opcodes.H_INVOKEVIRTUAL -> Opcodes.INVOKEVIRTUAL;
                                        default -> -1;
                                    };
                                    if (op < 0) {
                                        ok[0] = false;
                                    } else {
                                        method(op, h.getOwner(), h.getName(), h.getDesc());
                                    }
                                } else if (arg instanceof Type t) {
                                    ok[0] &= t.getSort() == Type.METHOD ? describedPresent(code.names.desc(t.getDescriptor())) : present(Type.getType(code.names.desc(t.getDescriptor())));
                                }
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            boolean all = ok[0];
            for (String[] c : calls) {
                all = all && a.classes.containsKey(c[0]) && !a.classes.get(c[0]).library && copyable(c[0], c[1], c[2], closure);
            }
            if (!all) {
                closure.remove(key);
                refused.add(key);
                return false;
            }
            String place = home + decl;
            String copyDesc = instance[0] ? "(L" + decl + ";" + desc.substring(1) : desc;
            Set<String> taken = names.computeIfAbsent(place, k -> new HashSet<>());
            String copyName = name.replaceAll("[^\\w$]", "_");
            for (int i = 1; !taken.add(copyName + copyDesc); i++) {
                copyName = name.replaceAll("[^\\w$]", "_") + "$" + i;
            }
            copies.put(key, new String[] { place, copyName, copyDesc });
            return true;
        }

        /** A type a copy names: the JDK's, or a class b has (under the route's name). */
        boolean present(Type t) {
            if (t.getSort() == Type.ARRAY) {
                t = t.getElementType();
            }
            if (t.getSort() != Type.OBJECT) {
                return true;
            }
            String n = t.getInternalName();
            return n.startsWith("java/") || b.classes.containsKey(cls(n)) || !GameDiff.gameClass(n) && !a.classes.containsKey(n);
        }

        boolean describedPresent(String methodDesc) {
            for (Type t : Type.getArgumentTypes(methodDesc)) {
                if (!present(t)) {
                    return false;
                }
            }
            return present(Type.getReturnType(methodDesc));
        }

        /**
         * The copies' classes, in a's names: each copied method public and static, an instance method's receiver its first
         * parameter, its code as a's with calls among the copies pointed at each other and a private method of b's (the
         * same class's own call) made an ordinary call, which the port then opens.
         */
        Map<String, byte[]> write() {
            Map<String, ClassWriter> classes = new TreeMap<>();
            for (var e : copies.entrySet()) {
                String key = e.getKey();
                String[] to = e.getValue();
                int dot = key.indexOf('.');
                String decl = key.substring(0, dot), member = key.substring(dot + 1);
                String jar = code.toJar.getOrDefault(decl, decl);
                byte[] bytes = code.bytes.apply(jar);
                ClassWriter cw = classes.computeIfAbsent(to[0], place -> {
                    ClassWriter w = new ClassWriter(0);
                    w.visit(new ClassReader(bytes).readUnsignedShort(6), Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC, place, null, "java/lang/Object", null);
                    return w;
                });
                org.objectweb.asm.commons.Remapper mojang = new org.objectweb.asm.commons.Remapper(Opcodes.ASM9) {
                    @Override
                    public String map(String internal) {
                        return code.names.cls(internal);
                    }

                    @Override
                    public String mapMethodName(String owner, String n, String d) {
                        String declObf = n.startsWith("<") || owner.startsWith("[") ? null : code.declarer(owner, n, d, false);
                        return declObf == null ? n : code.names.method(declObf, n, d);
                    }

                    @Override
                    public String mapFieldName(String owner, String n, String d) {
                        return code.names.field(code.declarer(owner, n, d, true), n);
                    }

                    @Override
                    public String mapInvokeDynamicMethodName(String n, String d) {
                        return n; // the functional interface's method: the JDK's or the game's, whose names a's mappings give below
                    }
                };
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                        if (!(code.names.method(jar, n, d) + code.names.desc(d)).equals(member)) {
                            return null;
                        }
                        MethodVisitor out = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, to[1], to[2], null, null);
                        return new org.objectweb.asm.commons.MethodRemapper(new MethodVisitor(Opcodes.ASM9, out) {
                            @Override
                            public void visitMethodInsn(int op, String owner, String n2, String d2, boolean itf) {
                                String[] copy = copied(owner, n2, d2);
                                if (copy != null) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, copy[0], copy[1], copy[2], false);
                                } else if (op == Opcodes.INVOKESPECIAL && !n2.equals("<init>")) {
                                    super.visitMethodInsn(itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, owner, n2, d2, itf);
                                } else {
                                    super.visitMethodInsn(op, owner, n2, d2, itf);
                                }
                            }

                            @Override
                            public void visitInvokeDynamicInsn(String n2, String d2, Handle bsm, Object... args) {
                                Object[] now = args.clone();
                                for (int i = 0; i < now.length; i++) {
                                    if (now[i] instanceof Handle h && !h.getName().startsWith("<")) {
                                        String[] copy = copied(h.getOwner(), h.getName(), h.getDesc());
                                        if (copy != null) {
                                            now[i] = new Handle(Opcodes.H_INVOKESTATIC, copy[0], copy[1], copy[2], false);
                                        } else if (h.getTag() == Opcodes.H_INVOKESPECIAL) {
                                            now[i] = new Handle(h.isInterface() ? Opcodes.H_INVOKEINTERFACE : Opcodes.H_INVOKEVIRTUAL, h.getOwner(), h.getName(), h.getDesc(), h.isInterface());
                                        }
                                    }
                                }
                                // The functional interface's method name: a's mapping of it on the interface the call site makes.
                                Type made = Type.getReturnType(d2);
                                String sam = n2;
                                if (bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") && now.length > 0 && now[0] instanceof Type erased) {
                                    String iface = code.toJar.getOrDefault(made.getInternalName(), made.getInternalName());
                                    String obfDesc = mapDescToJar(erased.getDescriptor());
                                    String named = code.names.method(code.declarer(iface, n2, obfDesc, false), n2, obfDesc);
                                    sam = named;
                                }
                                super.visitInvokeDynamicInsn(sam, d2, bsm, now);
                            }
                        }, mojang);
                    }
                }, 0);
            }
            Map<String, byte[]> out = new LinkedHashMap<>();
            classes.forEach((place, w) -> {
                w.visitEnd();
                out.put(place + ".class", w.toByteArray());
            });
            return out;
        }

        /** A descriptor in a's Mojang names written in its jar's names, for asking the mappings about it. */
        String mapDescToJar(String d) {
            return mapDesc(d, code.toJar);
        }

        /** The copy of a's method owner.name+desc (Mojang names), or null when it is not copied. */
        String[] copied(String owner, String name, String desc) {
            if (owner.startsWith("[")) {
                return null;
            }
            // A lambda's body is no member the snapshot keeps: it is copied under its own class.
            String[] own = copies.get(owner + "." + name + desc);
            GameDiff.Cls decl = own != null ? null : Exposure.declaring(a, owner, 'm', name, desc);
            return own != null ? own : decl == null ? null : copies.get(decl.name + "." + name + desc);
        }
    }

    /** Whether the port answers a's member owner.name+desc (a field: desc empty) on its own: a route, a rule or a stand-in. */
    boolean handled(String owner, String name, String desc) {
        String key = owner + "." + name + desc;
        if (rewrites.containsKey(key) || removals.containsKey(key) || chains.containsKey(key) || chains.containsKey(owner + "." + name) || defaults.containsKey(key)) {
            return true;
        }
        for (Fix f : fixes.getOrDefault(owner, List.of())) {
            if (f.name().matcher(name).matches() && (f.args() == null || desc.startsWith(f.args()))) {
                return true;
            }
        }
        for (RetypedCall c : retypedCalls.getOrDefault(owner, List.of())) {
            if (c.name().equals(name) && c.desc().equals(desc)) {
                return true;
            }
        }
        return false;
    }

    // ---- Port rules applied in the class.

    /** A port rule's replacement for game members of one old class ("inClass"): name is a regex, args a descriptor's start or null. */
    /** when: null, or "k:NAME": the rule holds only for a call whose operand $k is its type's constant NAME (GameDiff's per-constant readings). */
    record Fix(String rule, Pattern name, String args, String expr, String when) {

        Fix(String rule, Pattern name, String args, String expr) {
            this(rule, name, args, expr, null);
        }
    }

    final Map<String, List<Fix>> fixes = new HashMap<>(); // old owner (internal) -> replacements its rules give
    /**
     * The stand-ins for old instance methods, which also answer a mod's super call to one (super.handleComponentClicked(
     * style) in a screen): the old method is gone from every class above the mod's, and the stand-in is that old game
     * code written on the new release, which is what the super call ran.
     */
    final Map<String, List<Fix>> superFixes = new HashMap<>();

    static final Pattern DESCRIBED = Pattern.compile("([\\w$<>]+)(\\((?:\\[*(?:[ZBCSIJFD]|L[\\w/$]+;))*\\)\\S*)");

    /**
     * Reads every rule's "inClass" from a rule book (knowledge/fixes.json): "a.b.Owner.name[(args)]" -> expression (FixExpr),
     * or a class the new release dropped -> the class its values become.
     */
    void fixes(Path book) throws IOException {
        for (Object o : Json.array(Json.read(book).get("rules"))) {
            var rule = Json.object(o);
            if (!covers(Json.text(rule, "jump", ""), Json.text(rule, "loader", ""))) {
                continue;
            }
            for (var e : Json.object(rule.getOrDefault("inClass", Map.of())).entrySet()) {
                // A class the new release dropped, and the one its values become (BlockEntityType$Builder -> BlockEntityType).
                String gone = e.getKey().replace('.', '/');
                String now = String.valueOf(e.getValue()).replace('.', '/');
                if (a.classes.containsKey(gone) && !b.classes.containsKey(gone) && b.classes.containsKey(now)) {
                    classMap.putIfAbsent(gone, now);
                    continue;
                }
                int dot = e.getKey().lastIndexOf('.');
                String rest = e.getKey().substring(dot + 1);
                Matcher d = DESCRIBED.matcher(rest);
                boolean described = d.matches();
                Fix fix = new Fix(Json.text(rule, "id", ""), Pattern.compile(described ? Pattern.quote(d.group(1)) : rest), described ? d.group(2) : null, String.valueOf(e.getValue()));
                fixes.computeIfAbsent(e.getKey().substring(0, dot).replace('.', '/'), k -> new ArrayList<>()).add(fix);
            }
        }
        // After the rules: a class a rule moves (InteractionResultHolder -> InteractionResult) is no gone class to stand in for.
        // FROM-TO/ holds stand-ins written on the game alone, for every loader; FROM-TO.LOADER/ those that use a loader's classes.
        Path root = book.resolveSibling("standins");
        List<Path> dirs = new ArrayList<>();
        for (Path d : List.of(root.resolve(a.version + "-" + b.version), root.resolve(a.version + "-" + b.version + "." + loader))) {
            if (Files.isDirectory(d)) {
                dirs.add(d);
            }
        }
        if (!dirs.isEmpty()) {
            standins(dirs);
        }
        jdkStandins = Downgrade.Standins.of(root.resolve("jdk"));
    }

    /**
     * The releases a port along chain (every release from the one ported from to the one ported to) stops at. An update
     * with stand-ins (standins/FROM-TO/, or FROM-TO.LOADER/ for this loader) is a step of its own: its stand-ins answer
     * that update's old members and are written on its new release, and the steps after it port them like the mod's own
     * code. The updates between are one step on their composed route map.
     */
    static List<String> hops(List<String> chain, Path standins, String loader) {
        List<String> stops = new ArrayList<>(List.of(chain.get(0)));
        for (int i = 0; i + 1 < chain.size(); i++) {
            String update = chain.get(i) + "-" + chain.get(i + 1);
            if (Files.isDirectory(standins.resolve(update)) || !loader.isEmpty() && Files.isDirectory(standins.resolve(update + "." + loader))) {
                if (!stops.get(stops.size() - 1).equals(chain.get(i))) {
                    stops.add(chain.get(i));
                }
                stops.add(chain.get(i + 1));
            }
        }
        if (!stops.get(stops.size() - 1).equals(chain.get(chain.size() - 1))) {
            stops.add(chain.get(chain.size() - 1));
        }
        return stops;
    }

    /**
     * Whether a rule written for "A -> B" (and one loader, when it names one) belongs on this port: its update lies
     * between the releases ported from and to, and the loader is the one ported for.
     */
    boolean covers(String jump, String loader) {
        if (!loader.isEmpty() && !loader.equals(this.loader)) {
            return false;
        }
        String[] range = jump.replaceAll("\\(.*", "").trim().split("\\s*->\\s*");
        if (range.length != 2) {
            return true;
        }
        return compareVersions(a.version, range[1]) < 0 && compareVersions(b.version, range[0]) > 0;
    }

    /** Releases in order: 1.21.1 < 1.21.2 < 1.21.10 < 26.1. */
    static int compareVersions(String x, String y) {
        String[] p = x.split("[.-]");
        String[] q = y.split("[.-]");
        for (int i = 0; i < Math.max(p.length, q.length); i++) {
            int a = i < p.length && p[i].matches("\\d+") ? Integer.parseInt(p[i]) : 0;
            int b = i < q.length && q[i].matches("\\d+") ? Integer.parseInt(q[i]) : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return 0;
    }

    /** A private static method added to the class being rewritten: a rule's replacement, taking the old member's operands. */
    record Added(String name, String desc, Code code, int locals) {}

    /** The type of null, which fits any reference. */
    static final Type NULL = Type.getObjectType("null");

    /** Code being built: each step writes to a method, and the stack it needs is counted as it goes. */
    static final class Code {
        final List<java.util.function.Consumer<MethodVisitor>> steps = new ArrayList<>();
        int depth;
        int max;

        void add(int change, java.util.function.Consumer<MethodVisitor> step) {
            steps.add(step);
            depth += change;
            max = Math.max(max, depth);
        }

        void append(Code other) {
            steps.addAll(other.steps);
            max = Math.max(max, depth + other.max);
            depth += other.depth;
        }
    }

    /** A replacement that does not link or type in the new release; it is not written. */
    static final class Refused extends RuntimeException {
        Refused(String why) {
            super(why, null, false, false);
        }
    }

    final Map<String, FixExpr.Node> parsed = new HashMap<>(); // a rule's expression, filled, once parsed

    /**
     * Whether a port answer (a learned replacement or a rule's inClass, FixExpr) compiles against the new release for a use
     * whose operands have opDesc's types (old names; the receiver first when receiver), as Sites.fix lowers it into a class:
     * a source port (SourceAnswers) writes an answer only when a jar port of the same use would.
     */
    boolean answerLinks(String expr, String opDesc, boolean receiver) {
        Type[] ops = Type.getArgumentTypes(opDesc);
        Type ret = Type.getReturnType(opDesc);
        Mod mod = new Mod();
        Type[] params = new Type[ops.length];
        for (int i = 0; i < ops.length; i++) {
            params[i] = Type.getType(desc(ops[i].getDescriptor()));
            if (!present(mod, params[i])) {
                return false;
            }
        }
        Type newRet = Type.getType(desc(ret.getDescriptor()));
        try {
            Lowering low = new Lowering(mod, List.of(), params, receiver, 0);
            Type got = low.lower(parsed(expr), new Code());
            return ret.getSort() == Type.VOID || got.getSort() != Type.VOID && present(mod, newRet) && low.fits(got, newRet);
        } catch (RuntimeException refused) {
            return false;
        }
    }

    /** Whether the new release still has owner.name (old names) as the old release declared it: then a use links and needs no answer. */
    boolean kept(String owner, boolean field, String name, String desc) {
        String now = cls(owner);
        GameDiff.Cls c = Exposure.declaring(b, now, field ? 'f' : 'm', name, field ? null : desc(desc));
        return c != null && (field ? c.fields.get(name) != null && c.fields.get(name).desc.equals(desc(desc)) : c.methods.containsKey(name + desc(desc)));
    }

    FixExpr.Node parsed(String expr) {
        FixExpr.Node n = parsed.get(expr);
        if (n == null) {
            n = FixExpr.parse(expr);
            parsed.put(expr, n);
        }
        return n;
    }

    /**
     * An argument left out is a decision about one overload, so only a rule naming that overload's arguments may drop
     * one: spawnAtLocation(stack) -> ...($1) must not also take spawnAtLocation(stack, offset).
     */
    static boolean dropsUnnamed(Fix f, FixExpr.Node node, int arguments) {
        Set<Integer> used = FixExpr.operands(node);
        for (int i = 1; i <= arguments; i++) {
            if (!used.contains(i)) {
                return f.args() == null;
            }
        }
        return false;
    }

    /** A JDK class by internal name, or null. Only java.* is looked up: the game's own classes come from the snapshot. */
    static Class<?> jdk(String internal) {
        if (!internal.startsWith("java/")) {
            return null;
        }
        try {
            return Class.forName(internal.replace('/', '.'), false, null);
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }

    /** Whether a type exists for code running on the new release: the new release or the mod has it, or the old release never did (a library's). */
    boolean present(Mod mod, Type t) {
        Type e = t.getSort() == Type.ARRAY ? t.getElementType() : t;
        if (e.getSort() != Type.OBJECT) {
            return true;
        }
        String n = e.getInternalName();
        return b.classes.containsKey(n) || mod.classes.containsKey(n) || !a.classes.containsKey(n);
    }

    boolean isInterface(Mod mod, String internal) {
        GameDiff.Cls game = b.classes.get(internal);
        if (game != null) {
            return (game.flags & Opcodes.ACC_INTERFACE) != 0;
        }
        GameDiff.RawCls own = mod.classes.get(internal);
        if (own != null) {
            return (own.flags & Opcodes.ACC_INTERFACE) != 0;
        }
        Class<?> k = jdk(internal);
        return k != null && k.isInterface();
    }

    /** A method or constructor as a call names it (owner is the class the instruction names), and whether the caller may use it. */
    record Member(String owner, String name, String desc, boolean isStatic, boolean itf, boolean open) {}

    /** The pieces of an invokedynamic making a functional interface wanted from a static method owner::name, or null. */
    Object[] lambda(Mod mod, List<String> callers, String owner, String name, Type wanted) {
        String[] sam = wanted.getSort() == Type.OBJECT ? functional(wanted.getInternalName()) : null;
        GameDiff.Cls holder = b.classes.get(owner);
        if (sam == null || holder == null) {
            return null;
        }
        int arity = Type.getArgumentTypes(sam[1]).length;
        List<GameDiff.Meth> impls = holder.methods.values().stream()
            .filter(m -> m.name.equals(name) && (m.flags & Opcodes.ACC_STATIC) != 0 && Type.getArgumentTypes(m.desc).length == arity)
            .toList();
        if (impls.size() != 1 || !memberOpen(mod, callers, owner, impls.get(0).name + impls.get(0).desc, impls.get(0).flags)) {
            return null;
        }
        GameDiff.Meth impl = impls.get(0);
        Handle factory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
        Handle target = new Handle(Opcodes.H_INVOKESTATIC, inverse.getOrDefault(owner, owner), impl.name, oldDesc(impl.desc), (holder.flags & Opcodes.ACC_INTERFACE) != 0);
        return new Object[] { sam[0], "()" + oldDesc(wanted.getDescriptor()), factory, new Object[] { Type.getType(sam[1]), target, Type.getType(oldDesc(impl.desc)) } };
    }

    /**
     * One rule's replacement compiled for one site, the way javac would compile it against the new release: names are
     * resolved there (or in the JDK), overloads chosen by the operands' types, and every member must be one the caller may
     * use. Anything else is Refused. Code is written in the old release's names; the class renamer runs after.
     */
    final class Lowering {
        final Mod mod;
        final List<String> callers;
        final Type[] params;
        final boolean receiver;
        final int[] slots;
        int locals;

        /**
         * params: the old member's operands in new names, held in locals from first on; with receiver the first is $0,
         * else $1 is the first.
         */
        Lowering(Mod mod, List<String> callers, Type[] params, boolean receiver, int first) {
            this.mod = mod;
            this.callers = callers;
            this.params = params;
            this.receiver = receiver;
            slots = new int[params.length];
            locals = first;
            for (int i = 0; i < params.length; i++) {
                slots[i] = locals;
                locals += params[i].getSize();
            }
        }

        Type lower(FixExpr.Node n, Code out) {
            return switch (n) {
                case FixExpr.Arg a -> arg(a.index(), out);
                case FixExpr.Lit l -> literal(l, out);
                case FixExpr.Path p -> path(p.dotted(), out);
                case FixExpr.Get g -> field(lower(g.target(), out), g.name(), false, out);
                case FixExpr.Invoke i -> invoke(i, out);
                case FixExpr.New w -> construct(w, out);
                case FixExpr.Cast c -> cast(c, out);
                case FixExpr.Ref r -> throw new Refused("a method reference is taken only as an argument");
                case FixExpr.Sum a -> sum(a, out);
            };
        }

        Type sum(FixExpr.Sum a, Code out) {
            Type l = lower(a.left(), out);
            boolean wide = l.getSort() == Type.LONG;
            if (!wide && (l.getSort() < Type.CHAR || l.getSort() > Type.INT)) {
                throw new Refused("cannot add " + l);
            }
            Type r = lower(a.right(), out);
            if (r.getSort() == Type.LONG != wide || !wide && (r.getSort() < Type.CHAR || r.getSort() > Type.INT)) {
                throw new Refused("cannot add " + l + " and " + r);
            }
            int op = a.op() == '+' ? (wide ? Opcodes.LADD : Opcodes.IADD) : (wide ? Opcodes.LSUB : Opcodes.ISUB);
            out.add(0, mv -> mv.visitInsn(op));
            return wide ? Type.LONG_TYPE : Type.INT_TYPE;
        }

        Type arg(int index, Code out) {
            int k = receiver ? index : index - 1;
            if (k < 0 || k >= params.length) {
                throw new Refused("$" + index + " is not an operand of the old member");
            }
            Type t = params[k];
            int slot = slots[k];
            out.add(t.getSize(), mv -> mv.visitVarInsn(t.getOpcode(Opcodes.ILOAD), slot));
            return t;
        }

        Type literal(FixExpr.Lit l, Code out) {
            if (l.type() == null) {
                out.add(1, mv -> mv.visitInsn(Opcodes.ACONST_NULL));
                return NULL;
            }
            Type t = Type.getType(l.type());
            Object v = l.value();
            if (v instanceof Boolean z) {
                out.add(1, mv -> mv.visitInsn(z ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
            } else {
                out.add(t.getSize(), mv -> mv.visitLdcInsn(v));
            }
            return t;
        }

        boolean known(String internal) {
            return b.classes.containsKey(internal) || jdk(internal) != null;
        }

        String type(String dotted) {
            String internal = dotted.replace('.', '/');
            if (!known(internal)) {
                throw new Refused(dotted + " is not a class of the new release");
            }
            return internal;
        }

        /** a.b.C.F.G: the longest start that is a class, then its static field F and G of that. */
        Type path(String dotted, Code out) {
            String[] parts = dotted.split("\\.");
            for (int i = parts.length - 1; i >= 1; i--) {
                String owner = String.join("/", Arrays.copyOf(parts, i));
                if (!known(owner)) {
                    continue;
                }
                Type t = field(Type.getObjectType(owner), parts[i], true, out);
                for (int k = i + 1; k < parts.length; k++) {
                    t = field(t, parts[k], false, out);
                }
                return t;
            }
            throw new Refused(dotted + " is not a field of the new release");
        }

        Type field(Type owner, String name, boolean isStatic, Code out) {
            if (owner.getSort() != Type.OBJECT || owner == NULL) {
                throw new Refused("no field " + name + " on " + owner);
            }
            String o = owner.getInternalName();
            String desc;
            Class<?> k = jdk(o);
            if (k != null) {
                java.lang.reflect.Field f;
                try {
                    f = k.getField(name);
                } catch (NoSuchFieldException absent) {
                    throw new Refused("no field " + name + " on " + o);
                }
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) != isStatic) {
                    throw new Refused(name + " is not a " + (isStatic ? "static" : "instance") + " field");
                }
                desc = Type.getDescriptor(f.getType());
            } else {
                FieldHit f = fieldNew(mod, callers, o, name);
                GameDiff.Cls holder = Exposure.declaring(b, o, 'f', name, null);
                // A closed field the game's own code reads there (this.font in its screens) is opened when the loader lets the mod.
                if (f == null || !f.open() && !opened(mod, callers, o, 'f', name, null) || holder == null
                    || ((holder.fields.get(name).flags & Opcodes.ACC_STATIC) != 0) != isStatic) {
                    throw new Refused("no field " + name + " the caller may read on " + o);
                }
                desc = f.desc();
            }
            Type t = Type.getType(desc);
            String ownerOld = inverse.getOrDefault(o, o);
            String descOld = oldDesc(desc);
            out.add(t.getSize() - (isStatic ? 0 : 1), mv -> mv.visitFieldInsn(isStatic ? Opcodes.GETSTATIC : Opcodes.GETFIELD, ownerOld, name, descOld));
            return t;
        }

        /** Every method named name that a call on owner reaches, most derived first; static ones are named on their own class. */
        List<Member> methods(String owner, String name) {
            List<Member> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            boolean itf = isInterface(mod, owner);
            boolean open = classOpen(mod, callers, owner);
            Deque<String> queue = new ArrayDeque<>(List.of(owner));
            Set<String> visited = new HashSet<>();
            while (!queue.isEmpty()) {
                String c = queue.poll();
                if (!visited.add(c)) {
                    continue;
                }
                GameDiff.RawCls own = mod.classes.get(c);
                GameDiff.Cls game = own == null ? b.classes.get(c) : null;
                Class<?> k = own == null && game == null ? jdk(c) : null;
                if (game != null) {
                    for (GameDiff.Meth m : game.methods.values()) {
                        if (m.name.equals(name) && seen.add(m.desc)) {
                            boolean st = (m.flags & Opcodes.ACC_STATIC) != 0;
                            boolean usable = open && memberOpen(mod, callers, c, m.name + m.desc, m.flags) && (m.flags & Opcodes.ACC_SYNTHETIC) == 0;
                            out.add(new Member(st ? c : owner, name, m.desc, st, st ? isInterface(mod, c) : itf, usable));
                        }
                    }
                } else if (k != null) {
                    boolean pub = java.lang.reflect.Modifier.isPublic(k.getModifiers());
                    if (name.equals("<init>")) {
                        for (var ctor : k.getConstructors()) {
                            if (seen.add(Type.getConstructorDescriptor(ctor))) {
                                out.add(new Member(c, name, Type.getConstructorDescriptor(ctor), false, false, pub));
                            }
                        }
                    }
                    for (var m : k.getMethods()) {
                        String d = Type.getMethodDescriptor(m);
                        if (m.getName().equals(name) && !m.isBridge() && seen.add(d)) {
                            boolean st = java.lang.reflect.Modifier.isStatic(m.getModifiers());
                            String decl = Type.getInternalName(m.getDeclaringClass());
                            out.add(new Member(st ? decl : owner, name, d, st, st ? m.getDeclaringClass().isInterface() : itf, pub));
                        }
                    }
                }
                if (name.equals("<init>")) {
                    break;
                }
                String sup = own != null ? (own.sup == null ? null : cls(own.sup)) : game != null ? game.sup : null;
                if (sup != null) {
                    queue.add(sup);
                }
                if (own != null) {
                    own.ifaces.forEach(i -> queue.add(cls(i)));
                } else if (game != null) {
                    queue.addAll(game.ifaces);
                }
            }
            return out;
        }

        Type invoke(FixExpr.Invoke i, Code out) {
            Code target = new Code();
            String owner;
            boolean isStatic = i.target() instanceof FixExpr.Path p && known(p.dotted().replace('.', '/'));
            if (isStatic) {
                owner = ((FixExpr.Path) i.target()).dotted().replace('.', '/');
            } else {
                Type recv = lower(i.target(), target);
                if (recv.getSort() != Type.OBJECT || recv == NULL) {
                    throw new Refused("no method " + i.name() + " on " + recv);
                }
                owner = recv.getInternalName();
            }
            boolean wantStatic = isStatic;
            Member m = choose(methods(owner, i.name()).stream().filter(x -> x.isStatic() == wantStatic).toList(), i.args(), owner + "." + i.name());
            out.append(target);
            int popped = arguments(m, i.args(), out) + (isStatic ? 0 : 1);
            Type ret = Type.getReturnType(m.desc());
            int op = isStatic ? Opcodes.INVOKESTATIC : m.itf() ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
            out.add(ret.getSize() - popped, mv -> mv.visitMethodInsn(op, inverse.getOrDefault(m.owner(), m.owner()), m.name(), oldDesc(m.desc()), m.itf()));
            return ret;
        }

        Type construct(FixExpr.New w, Code out) {
            String owner = type(w.type());
            GameDiff.Cls game = b.classes.get(owner);
            Class<?> k = jdk(owner);
            int flags = game != null ? game.flags : k.getModifiers();
            if ((flags & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0) {
                throw new Refused(w.type() + " cannot be made with new");
            }
            Member m = choose(methods(owner, "<init>"), w.args(), owner + ".<init>");
            String old = inverse.getOrDefault(owner, owner);
            out.add(1, mv -> mv.visitTypeInsn(Opcodes.NEW, old));
            out.add(1, mv -> mv.visitInsn(Opcodes.DUP));
            int popped = arguments(m, w.args(), out) + 1;
            out.add(-popped, mv -> mv.visitMethodInsn(Opcodes.INVOKESPECIAL, old, "<init>", oldDesc(m.desc()), false));
            return Type.getObjectType(owner);
        }

        /** Writes each argument converted to m's parameter; returns the stack slots they take. */
        int arguments(Member m, List<FixExpr.Node> args, Code out) {
            Type[] ps = Type.getArgumentTypes(m.desc());
            int slots = 0;
            for (int k = 0; k < ps.length; k++) {
                Type p = ps[k];
                if (args.get(k) instanceof FixExpr.Ref r) {
                    Object[] indy = lambda(mod, callers, type(r.type()), r.name(), p);
                    Object[] bsmArgs = (Object[]) indy[3];
                    out.add(1, mv -> mv.visitInvokeDynamicInsn((String) indy[0], (String) indy[1], (Handle) indy[2], bsmArgs));
                } else {
                    Type got = lower(args.get(k), out);
                    widen(got, p, out);
                }
                slots += p.getSize();
            }
            return slots;
        }

        /** The one most specific candidate the arguments fit, as javac picks it (no varargs expansion, no boxing). */
        Member choose(List<Member> candidates, List<FixExpr.Node> args, String what) {
            List<Type> types = new ArrayList<>();
            for (FixExpr.Node a : args) {
                types.add(a instanceof FixExpr.Ref ? null : lower(a, new Code()));
            }
            List<Member> fit = new ArrayList<>();
            for (Member m : candidates) {
                Type[] ps = Type.getArgumentTypes(m.desc());
                boolean ok = ps.length == args.size();
                for (int k = 0; ok && k < ps.length; k++) {
                    ok = types.get(k) == null
                        ? args.get(k) instanceof FixExpr.Ref r && known(r.type().replace('.', '/')) && lambda(mod, callers, r.type().replace('.', '/'), r.name(), ps[k]) != null
                        : fits(types.get(k), ps[k]);
                }
                if (ok) {
                    fit.add(m);
                }
            }
            List<Member> best = fit.stream().filter(m -> fit.stream().allMatch(o -> o == m || narrower(m, o))).toList();
            if (best.size() != 1) {
                throw new Refused(what + (fit.isEmpty() ? " takes no such arguments" : " is ambiguous for these arguments"));
            }
            if (!best.get(0).open()) {
                throw new Refused(what + " is closed to the caller");
            }
            return best.get(0);
        }

        boolean narrower(Member m, Member o) {
            Type[] a = Type.getArgumentTypes(m.desc());
            Type[] b = Type.getArgumentTypes(o.desc());
            for (int k = 0; k < a.length; k++) {
                if (!fits(a[k], b[k])) {
                    return false;
                }
            }
            return true;
        }

        Type cast(FixExpr.Cast c, Code out) {
            Type got = lower(c.expr(), out);
            if (FixExpr.PRIMITIVES.contains(c.type())) {
                Type to = Type.getType(switch (c.type()) {
                    case "boolean" -> "Z";
                    case "byte" -> "B";
                    case "char" -> "C";
                    case "short" -> "S";
                    case "int" -> "I";
                    case "long" -> "J";
                    case "float" -> "F";
                    default -> "D";
                });
                if (got.getSort() < Type.CHAR || got.getSort() > Type.DOUBLE || to.getSort() == Type.BOOLEAN) {
                    throw new Refused("cannot cast " + got + " to " + c.type());
                }
                convert(got, to, out);
                return to;
            }
            String to = type(c.type());
            if (got.getSort() != Type.OBJECT && got.getSort() != Type.ARRAY) {
                throw new Refused("cannot cast " + got + " to " + c.type());
            }
            String old = inverse.getOrDefault(to, to);
            out.add(0, mv -> mv.visitTypeInsn(Opcodes.CHECKCAST, old));
            return Type.getObjectType(to);
        }

        /** Whether a value of type from may be passed where to is wanted, as Java allows without a cast (no boxing). */
        boolean fits(Type from, Type to) {
            if (from == NULL) {
                return to.getSort() == Type.OBJECT || to.getSort() == Type.ARRAY;
            }
            if (from.equals(to)) {
                return true;
            }
            if (from.getSort() < Type.ARRAY || to.getSort() < Type.ARRAY) {
                return from.getSort() >= Type.CHAR && to.getSort() > Type.CHAR && from.getSort() <= Type.DOUBLE && to.getSort() <= Type.DOUBLE
                    && WIDER.getOrDefault(from.getSort(), Set.of()).contains(to.getSort());
            }
            if (to.getDescriptor().equals("Ljava/lang/Object;")) {
                return true;
            }
            if (from.getSort() == Type.ARRAY) {
                if (to.getSort() != Type.ARRAY) {
                    return to.getInternalName().equals("java/lang/Cloneable") || to.getInternalName().equals("java/io/Serializable");
                }
                Type fe = Type.getType(from.getDescriptor().substring(1));
                Type te = Type.getType(to.getDescriptor().substring(1));
                return fe.getSort() >= Type.ARRAY && te.getSort() >= Type.ARRAY && fits(fe, te);
            }
            return to.getSort() == Type.OBJECT && isA(mod, from.getInternalName(), to.getInternalName());
        }

        /** The conversion Java makes when a value is passed or returned where a wider primitive is wanted. */
        void widen(Type from, Type to, Code out) {
            if (from.getSort() < Type.ARRAY && to.getSort() < Type.ARRAY && !from.equals(to)) {
                convert(from, to, out);
            }
        }

        void convert(Type from, Type to, Code out) {
            char f = from.getSort() <= Type.INT ? 'I' : from.getDescriptor().charAt(0);
            char t = to.getSort() <= Type.INT ? 'I' : to.getDescriptor().charAt(0);
            String pair = "" + f + t;
            Integer op = CONVERSIONS.get(pair);
            if (op != null) {
                out.add(to.getSize() - from.getSize(), mv -> mv.visitInsn(op));
            }
            int narrow = switch (to.getSort()) {
                case Type.BYTE -> Opcodes.I2B;
                case Type.CHAR -> Opcodes.I2C;
                case Type.SHORT -> Opcodes.I2S;
                default -> 0;
            };
            if (narrow != 0 && from.getSort() != to.getSort()) {
                out.add(0, mv -> mv.visitInsn(narrow));
            }
        }
    }

    static final Map<Integer, Set<Integer>> WIDER = Map.of(
        Type.BYTE, Set.of(Type.SHORT, Type.INT, Type.LONG, Type.FLOAT, Type.DOUBLE),
        Type.SHORT, Set.of(Type.INT, Type.LONG, Type.FLOAT, Type.DOUBLE),
        Type.CHAR, Set.of(Type.INT, Type.LONG, Type.FLOAT, Type.DOUBLE),
        Type.INT, Set.of(Type.LONG, Type.FLOAT, Type.DOUBLE),
        Type.LONG, Set.of(Type.FLOAT, Type.DOUBLE),
        Type.FLOAT, Set.of(Type.DOUBLE)
    );

    static final Map<String, Integer> CONVERSIONS = Map.ofEntries(
        Map.entry("IJ", Opcodes.I2L), Map.entry("IF", Opcodes.I2F), Map.entry("ID", Opcodes.I2D),
        Map.entry("JI", Opcodes.L2I), Map.entry("JF", Opcodes.L2F), Map.entry("JD", Opcodes.L2D),
        Map.entry("FI", Opcodes.F2I), Map.entry("FJ", Opcodes.F2L), Map.entry("FD", Opcodes.F2D),
        Map.entry("DI", Opcodes.D2I), Map.entry("DJ", Opcodes.D2L), Map.entry("DF", Opcodes.D2F)
    );

    // ---- Metadata.

    static final Pattern BLOCK = Pattern.compile("(?ms)^\\s*\\[\\[dependencies\\.[^\\]]+\\]\\].*?(?=^\\s*\\[|\\z)");

    String languageRange; // the loaderVersion the new release's javafml answers, "[N,)"; "" when unknown

    /**
     * NeoForge's javafml and lowcodefml answer to the version of the FancyModLoader that release is built on (1 for
     * 20.2-20.3, 2 for 20.4, 11 for 26.1), which its own pom names; a Forge jar asks for Forge's ([47,)), which no
     * NeoForge release answers to. "" when the pom names none.
     */
    static String languageRange(String pom) {
        for (String[] d : Exposure.dependencies(pom)) {
            if (d[0].equals("net.neoforged.fancymodloader") && d[1].equals("loader")) {
                return "[" + d[2].replaceAll("\\..*", "") + ",)";
            }
        }
        return "";
    }

    String languageRange() {
        if (languageRange == null) {
            try {
                languageRange = loader.equals("neoforge") && loaderTo != null ? languageRange(Exposure.neoforgePom(loaderTo)) : "";
            } catch (Exception unreadable) {
                languageRange = "";
            }
        }
        return languageRange;
    }

    static final String OLD_TOML = "META-INF/mods.toml", NEW_TOML = "META-INF/neoforge.mods.toml";

    /** FancyModLoader versions from Forge's line (Forge 1.20.1 is FML 47) count as older than NeoForge's own FML 1. */
    static final int FORGE_FML = 47;

    /**
     * The major version of the FancyModLoader the release's NeoForge runs, which decides the metadata it reads: from the
     * loader's own pom (languageRange), else the FML each release's newest NeoForge ships (read from their poms: 1.20.2
     * and 1.20.3 run FML 1, 1.20.4 FML 2, 1.20.5 and 1.20.6 FML 3, 1.21 and 1.21.1 FML 4; before 1.20.2 Forge's FML 47).
     */
    int fml() {
        Matcher range = Pattern.compile("\\[(\\d+),").matcher(languageRange());
        if (range.lookingAt()) {
            int major = Integer.parseInt(range.group(1));
            return major >= FORGE_FML ? 0 : major;
        }
        return compareVersions(to, "1.20.2") < 0 ? 0 : compareVersions(to, "1.20.4") < 0 ? 1 : compareVersions(to, "1.20.5") < 0 ? 2
            : compareVersions(to, "1.21") < 0 ? 3 : 4;
    }

    /**
     * The metadata file the release's NeoForge reads. FML 3 (NeoForge 20.5) renamed META-INF/mods.toml to
     * META-INF/neoforge.mods.toml (NeoForge's 20.5 release notes, "mods.toml renamed"), and FML 1 and 2 read only the old
     * name (their ModFileParser names "mods.toml", not "META-INF/neoforge.mods.toml"). A jar keeping the other name is
     * skipped without a word, so the port writes the one the release reads.
     */
    String metadataFile() {
        return fml() >= 3 ? NEW_TOML : OLD_TOML;
    }

    /** entries with from renamed to to, in the same place, keeping its time. */
    static Map<String, byte[]> moved(Map<String, byte[]> entries, Map<String, Long> times, String from, String to) {
        Map<String, byte[]> moved = new LinkedHashMap<>();
        entries.forEach((k, v) -> moved.put(k.equals(from) ? to : k, v));
        times.put(to, times.getOrDefault(from, 0L));
        return moved;
    }

    /**
     * Keys of a mod's [[mods]] entry that only newer NeoForge reads, each with the first FML that does. Read on an older
     * one, the key is ignored and what it declares never happens, and nothing older says it another way: data-driven enum
     * extensions came with FML 4 (NeoForge 21.0: net.neoforged.fml.common.asm.enumextension, which FML 3 lacks; it
     * replaced IExtensibleEnum's create methods), and feature flags a mod declares with NeoForge 21.1's FeatureFlagLoader.
     */
    static final Map<String, String> NEWER_KEYS = Map.of("enumExtensions", "1.21", "featureFlags", "1.21.1");

    /** Dependency types FML 1's mandatory flag can say; incompatible and discouraged (FML 2) it cannot. */
    static final Map<String, String> MANDATORY = Map.of("required", "true", "optional", "false");

    static final Pattern DEPENDENCY_TYPE = Pattern.compile("(?m)^(\\s*)type\\s*=\\s*\"([A-Za-z]+)\"[ \\t]*\\r?\\n?");
    static final Pattern DEPENDENCY_MANDATORY = Pattern.compile("(?m)^(\\s*)mandatory\\s*=\\s*\"?(true|false)\"?[ \\t]*\\r?\\n?");

    String metadata(String toml) {
        return metadata(new Mod(), toml);
    }

    /**
     * The loader metadata asks for the new release: the game's and the loader's version ranges of each dependency block,
     * and the version of the loader's own language provider. Each dependency says whether it is needed the way the
     * release's FML reads it: FML 1 (NeoForge 20.2, 20.3) only by mandatory=true|false and fails a block without it
     * ("Missing required field mandatory in dependency"); FML 2 and 3 read type="required|optional|incompatible|discouraged"
     * and still the deprecated mandatory; FML 4 on reads only type, so a mandatory=false block is required there. A key
     * only newer releases read is a break on an older one.
     */
    String metadata(Mod mod, String toml) {
        String language = languageRange();
        if (!language.isEmpty() && Pattern.compile("(?m)^\\s*modLoader\\s*=\\s*\"(javafml|lowcodefml)\"").matcher(toml).find()) {
            toml = toml.replaceFirst("(?m)^(\\s*loaderVersion\\s*=\\s*)\"[^\"]*\"", "$1" + Matcher.quoteReplacement("\"" + language + "\""));
        }
        if (loader.equals("neoforge")) {
            for (var key : NEWER_KEYS.entrySet()) {
                if (compareVersions(to, key.getValue()) < 0 && Pattern.compile("(?m)^\\s*" + key.getKey() + "\\s*=").matcher(toml).find()) {
                    mod.unlinked.add("metadata " + key.getKey() + " has no form before " + key.getValue() + ": NeoForge for " + to + " does not read it");
                }
            }
        }
        int fml = loader.equals("neoforge") ? fml() : -1;
        Matcher m = BLOCK.matcher(toml);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String block = m.group();
            if (fml >= 0) {
                block = dependencyType(mod, block, fml);
            }
            Matcher id = Pattern.compile("modId\\s*=\\s*\"([^\"]+)\"").matcher(block);
            if (block.isEmpty()) {
                // dropped: FML 1 cannot say it
            } else if (id.find()) {
                String range = switch (id.group(1)) {
                    case "minecraft" -> "[" + to + "]";
                    case "neoforge", "forge" -> loaderTo == null ? null : "[" + loaderTo.replaceAll("^(\\d+\\.\\d+).*", "$1") + ",)";
                    default -> null;
                };
                if (range != null) {
                    block = block.replaceFirst("versionRange\\s*=\\s*\"[^\"]*\"", Matcher.quoteReplacement("versionRange=\"" + range + "\""));
                }
                // NeoForge's own releases (from 1.20.2) provide the mod "neoforge" and none called "forge", so a Forge jar's
                // dependency on the loader fails before anything loads.
                if (id.group(1).equals("forge") && loader.equals("neoforge") && compareVersions(to, "1.20.2") >= 0) {
                    block = block.replaceFirst("modId\\s*=\\s*\"forge\"", "modId=\"neoforge\"");
                }
                // And the reverse: NeoForge for 1.20.1 (47.1) is Forge's line, which provides "forge".
                if (id.group(1).equals("neoforge") && loader.equals("neoforge") && compareVersions(to, "1.20.2") < 0) {
                    block = block.replaceFirst("modId\\s*=\\s*\"neoforge\"", "modId=\"forge\"");
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(block));
        }
        return m.appendTail(sb).toString();
    }

    /**
     * A dependency block saying whether it is needed as the release's FML reads it (metadata). A type FML 1 cannot say
     * (incompatible, discouraged) drops the block, reported: the loader no longer refuses the pair, though the mod runs.
     */
    static String dependencyType(Mod mod, String block, int fml) {
        Matcher type = DEPENDENCY_TYPE.matcher(block), mandatory = DEPENDENCY_MANDATORY.matcher(block);
        boolean typed = type.find(), flagged = mandatory.find();
        if (fml <= 1 && typed && !flagged) {
            String kind = type.group(2).toLowerCase(Locale.ROOT);
            String flag = MANDATORY.get(kind);
            if (flag == null) {
                Matcher id = Pattern.compile("modId\\s*=\\s*\"([^\"]+)\"").matcher(block);
                mod.silent.add("dependency type " + kind + " has no form before NeoForge 20.4, left out: " + (id.find() ? id.group(1) : "?"));
                mod.change("dependency type the release cannot read left out");
                return "";
            }
            mod.change("dependency type written as mandatory");
            return block.substring(0, type.start()) + type.group(1) + "mandatory=" + flag + "\n" + block.substring(type.end());
        }
        if (fml >= 2 && flagged && !typed) {
            mod.change("dependency mandatory written as type");
            return block.substring(0, mandatory.start()) + mandatory.group(1) + "type=\"" + (mandatory.group(2).equals("true") ? "required" : "optional") + "\"\n"
                + block.substring(mandatory.end());
        }
        return block;
    }

    /** The access widener a fabric.mod.json names, or null. */
    static String widenerFile(byte[] fmj) {
        try {
            return Json.object(Json.parse(new String(fmj, StandardCharsets.UTF_8))).get("accessWidener") instanceof String w ? w : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * fabric.mod.json asks for the new release: its "minecraft" dependency becomes the release ported to, and it names the
     * access widener the port wrote when the mod had none. Anything unreadable is left as it is.
     */
    byte[] fabricMetadata(byte[] data, String widener, Set<String> platformNested) {
        Map<String, Object> meta;
        try {
            meta = Json.object(Json.parse(new String(data, StandardCharsets.UTF_8)));
        } catch (RuntimeException unreadable) {
            return data;
        }
        if (meta.get("depends") instanceof Map<?, ?> depends && depends.containsKey("minecraft")) {
            Json.object(depends).put("minecraft", to);
        }
        // A name the old platform answered to and the new one does not is asked of the module that gave it.
        if (meta.get("depends") instanceof Map<?, ?> depends) {
            Map<String, Object> asks = Json.object(depends);
            // A version of the platform (or one of its modules) the mod asks for is numbered as the old release's platform
            // was (Konkrete for 1.20.2 asks Fabric API 0.91.6, while 1.20.3's is 0.91.1): the new release's platform is its own.
            for (String id : List.copyOf(asks.keySet())) {
                if (platformNested.contains(id) || platformIds.contains(id)) {
                    asks.put(id, "*");
                }
            }
            for (var alias : platformAliases.entrySet()) {
                if (asks.remove(alias.getKey()) != null && !alias.getValue().isEmpty()) {
                    asks.put(alias.getValue(), "*");
                }
            }
        }
        if (widener != null && !meta.containsKey("accessWidener")) {
            meta.put("accessWidener", widener);
        }
        return Json.write(meta).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Access widener lines name game members in the old release (Mojang's names, as ModNames reads them); each one is moved
     * to its new name, and a line whose class or member is gone is commented out, as in an access transformer.
     */
    String accessWidener(Mod mod, String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\r?\n", -1)) {
            String body = line.replaceAll("#.*", "").trim();
            String[] p = body.split("\\s+");
            if (p.length >= 3 && (p[0].equals("accessWidener") || p[0].equals("classTweaker")) && p[2].equals("named") && compareVersions(b.version, UNOBFUSCATED) >= 0) {
                // From 26.1 the game runs in Mojang's names under Fabric's "official" namespace, and Fabric Loader refuses any other.
                out.append(p[0]).append(' ').append(p[1]).append(" official\n");
                continue;
            }
            if (p.length < 3 || p[0].equals("accessWidener") || p[0].equals("classTweaker")) {
                out.append(line).append('\n');
                continue;
            }
            String owner = p[2].replace('.', '/');
            String newOwner = cls(owner);
            String moved = null;
            boolean known = a.classes.containsKey(owner);
            if (!known) {
                moved = body; // another mod's class, or one the old release did not have: left as it is
            } else if (!b.classes.containsKey(newOwner)) {
                moved = null;
            } else if (p[1].equals("class")) {
                moved = p[0] + " class " + newOwner;
            } else if (p[1].equals("method") && p.length >= 5) {
                String[] m = route.method(owner, p[3], p[4]);
                GameDiff.Cls holder = Exposure.declaring(b, newOwner, 'm', m[1], m[2]);
                moved = holder == null ? null : p[0] + " method " + newOwner + " " + m[1] + " " + m[2];
            } else if (p[1].equals("field") && p.length >= 4) {
                String[] f = route.field(owner, p[3]);
                GameDiff.Cls holder = Exposure.declaring(b, newOwner, 'f', f[1], null);
                moved = holder == null ? null : p[0] + " field " + newOwner + " " + f[1] + " " + holder.fields.get(f[1]).desc;
            } else {
                moved = body;
            }
            if (moved == null) {
                out.append("# ").append(body).append("  # mmt port: gone in ").append(to).append('\n');
                mod.change("access widener line for a gone member commented out");
            } else {
                out.append(moved.equals(body) ? line : moved).append('\n');
            }
        }
        String s = out.toString();
        return s.endsWith("\n\n") ? s.substring(0, s.length() - 1) : s;
    }

    /** The port's own grants ("public a.b.C [member]") as access widener lines; a field's type comes from the new release. */
    String widenerLines(Set<String> grants) {
        StringBuilder out = new StringBuilder();
        for (String g : grants) {
            String[] p = g.split(" ");
            String owner = p[1].replace('.', '/');
            if (p.length == 2) {
                out.append("accessible class ").append(owner).append('\n');
            } else if (p[2].contains("(")) {
                int paren = p[2].indexOf('(');
                out.append("accessible method ").append(owner).append(' ').append(p[2], 0, paren).append(' ').append(p[2].substring(paren)).append('\n');
            } else {
                GameDiff.Cls holder = Exposure.declaring(b, owner, 'f', p[2], null);
                if (holder != null) {
                    out.append("accessible field ").append(owner).append(' ').append(p[2]).append(' ').append(holder.fields.get(p[2]).desc).append('\n');
                }
            }
        }
        return out.toString();
    }

    /**
     * What an access transformer makes public, in the new release's names, so linking does not count it as closed: a class
     * ("owner"), a member ("owner.name" or "owner.name+desc"), every field ("owner.*") or every method but constructors ("owner.*()").
     * What it makes protected (Fabric's extendable, written protected-f) is kept with a leading "~": a subclass may use it.
     */
    void widen(Set<String> grants, String text) {
        for (String line : text.split("\n")) {
            String[] p = line.replaceAll("#.*", "").trim().split("\\s+");
            boolean open = p[0].startsWith("public");
            if (p.length < 2 || !open && !p[0].startsWith("protected")) {
                continue;
            }
            String mark = open ? "" : "~";
            String owner = p[1].replace('.', '/');
            String newOwner = cls(owner);
            if (p.length == 2) {
                grants.add(mark + newOwner);
                continue;
            }
            int paren = p[2].indexOf('(');
            if (p[2].equals("*") || p[2].equals("*()")) {
                grants.add(mark + newOwner + "." + p[2]);
            } else if (paren < 0) {
                grants.add(mark + newOwner + "." + route.field(owner, p[2])[1]);
            } else {
                String[] moved = route.method(owner, p[2].substring(0, paren), p[2].substring(paren));
                grants.add(mark + newOwner + "." + moved[1] + moved[2]);
            }
        }
    }

    /**
     * Access transformer lines name game members in the old release; each one is moved to its new name. A line whose
     * class or member the new release no longer has opens nothing, so it is commented out: whatever in the mod used
     * that member is reported where it is used.
     */
    String accessTransformer(Mod mod, String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String body = line.replaceAll("#.*", "").trim();
            String[] p = body.split("\\s+");
            if (p.length < 2) {
                out.append(line).append('\n');
                continue;
            }
            String owner = p[1].replace('.', '/');
            String rest = p.length > 2 ? p[2] : null;
            String newOwner = cls(owner);
            String newRest = rest;
            boolean gone = a.classes.containsKey(owner) && !b.classes.containsKey(newOwner);
            if (!gone && rest != null && !rest.startsWith("*") && a.classes.containsKey(owner)) {
                int paren = rest.indexOf('(');
                if (paren < 0) {
                    String[] moved = route.field(owner, rest);
                    gone = Exposure.declaring(b, newOwner, 'f', moved[1], null) == null;
                    newRest = moved[1];
                } else {
                    String name = rest.substring(0, paren), d = rest.substring(paren);
                    String[] moved = route.method(owner, name, d);
                    gone = Exposure.declaring(b, newOwner, 'm', moved[1], moved[2]) == null;
                    newRest = moved[1] + moved[2];
                }
            }
            if (gone) {
                out.append("# ").append(body).append("  # mmt port: gone in ").append(to).append('\n');
                mod.change("access transformer line for a gone member commented out");
                continue;
            }
            String moved = p[0] + " " + newOwner.replace('/', '.') + (newRest == null ? "" : " " + newRest);
            out.append(moved.equals(body) ? line : moved).append('\n');
        }
        return out.substring(0, out.length() - 1);
    }

    static byte[] unsigned(byte[] manifest) throws IOException {
        java.util.jar.Manifest m = new java.util.jar.Manifest(new ByteArrayInputStream(manifest));
        // Per-entry digests belong to the signature that was removed; everything else stays.
        for (var entry : m.getEntries().values()) {
            entry.keySet().removeIf(k -> k.toString().endsWith("-Digest"));
        }
        m.getEntries().values().removeIf(java.util.jar.Attributes::isEmpty);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        m.write(out);
        return out.toByteArray();
    }
}


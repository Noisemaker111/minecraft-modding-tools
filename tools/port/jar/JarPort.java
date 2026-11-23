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

    final LoaderNames loaderNames = new LoaderNames(this);
    final ModMetadata modMetadata = new ModMetadata(this);
    final BundledMods bundledMods = new BundledMods(this);
    final OlderJava olderJava = new OlderJava(this);
    final AccessFiles accessFiles = new AccessFiles(this);
    final EventBuses eventBuses = new EventBuses(this);
    final PortVerifier verifier = new PortVerifier(this);
    final Linking links = new Linking(this);
    final Overrides overrides = new Overrides(this);
    final FinalOverrides finalOverrides = new FinalOverrides(this);
    final AbstractMethods abstractMethods = new AbstractMethods(this);
    final Constructors constructors = new Constructors(this);
    final MixinMembers mixinMembers = new MixinMembers(this);
    final MixinRetargets mixinRetargets = new MixinRetargets(this);
    final MixinMovedCode mixinMovedCode = new MixinMovedCode(this);
    final MixinPoints mixinPoints = new MixinPoints(this);
    final StandinClasses standIn = new StandinClasses(this);
    final NewerCode newerCode = new NewerCode(this);
    final RuleCode ruleCode = new RuleCode(this);
    final Conversions conversions = new Conversions(this);

    final GameDiff.Snap a, b;
    String loader; // "neoforge" or "fabric": the loader ported for, which a rule may name
    final Exposure.Route route;
    final String to, loaderTo;
    final Map<String, String> classMap = new HashMap<>();
    final Map<String, String> inverse = new HashMap<>();
    final Map<String, List<Map<String, Object>>> removals = new HashMap<>(); // owner.name+desc -> rewrite to nothing

    /** One jar's classes (old names), what was changed in it, and what does not link. */
    static final class Mod {
        final Map<String, GameDiff.RawCls> classes = new HashMap<>();
        final Map<String, Integer> maxLocals = new HashMap<>(); // class.method+desc
        final Map<String, String[]> renamedDecl = new HashMap<>(); // class.name+desc (or class.field) -> {name, desc}
        final Map<String, List<FinalOverrides.Copy>> copies = new HashMap<>(); // class -> game code copied into it
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
        final Map<String, RuleCode.Added> added = new LinkedHashMap<>(); // rule replacement + operands -> method added to self
        final Map<String, Boolean> subscribers = new HashMap<>(); // @EventBusSubscriber class -> whether it names its bus
        final Map<String, List<String>> listeners = new HashMap<>(); // class -> event types of its @SubscribeEvent methods (old names)
        final Set<String> toModBus = new HashSet<>(); // subscriber classes whose annotation gets bus = MOD
        final Map<String, List<Object[]>> subscriberValues = new HashMap<>(); // its other annotation values: {name, value} or {name, [desc, enum]...}
        final Map<String, List<EventBuses.Listener>> listenerMethods = new HashMap<>();
        final Map<String, List<EventBuses.Listener>> split = new HashMap<>(); // mixed subscriber -> its mod-bus listeners, moved to a companion
        final Map<String, byte[]> bytes = new HashMap<>(); // class -> its bytes as read, for what a method body reads
        final Map<String, MixinRetargets.Injector> injectors = new HashMap<>(); // mixin class.handler+desc -> its @Inject, @Redirect...
        final Map<String, String> retargets = new HashMap<>(); // class.handler+desc + " " + annotation string -> what is written instead
        final Map<String, String[]> points = new HashMap<>(); // class.handler+desc + " " + an @At's value, target and ordinal -> those written instead
        final Map<String, MixinRetargets.Adapter> adapters = new HashMap<>(); // class.handler+desc -> the handler re-declared for its target's new parameters
        final Set<String> staticized = new HashSet<>(); // class.handler+desc of handlers made static, as their targets now are
        final Map<String, Integer> argIndexes = new HashMap<>(); // class.handler+desc of a @ModifyArg -> the index its argument has in the call's new signature
        final Map<String, List<Overrides.Bridge>> bridges = new HashMap<>(); // class -> methods added with a game method's new signature
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
        String from = null, to = null, out = null, loader = null, report = null, book = null, chain = null;
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
        List<String> stops = new ArrayList<>();
        stops.add(from);
        stops.addAll(via);
        stops.add(to);
        if (from == null || to == null || maps.size() != stops.size() - 1 || out == null || jars.isEmpty()) {
            System.err.println("usage: JarPort --from V [--via V,...] --to V --map route.json (one per step) [--loader neoforge|fabric] [--runtime-names] [--fixes knowledge/fixes.json] --out OUT.jar|DIR [--report R.json] IN.jar ...");
            System.exit(2);
        }
        Path target = Path.of(out);
        boolean folder = jars.size() > 1 || Files.isDirectory(target);
        if (folder) {
            Files.createDirectories(target);
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
        Map<Path, byte[]> bytes = new java.util.concurrent.ConcurrentHashMap<>();
        Map<Path, Map<String, Set<String>>> originals = new java.util.concurrent.ConcurrentHashMap<>();
        Map<Path, List<Map<String, Object>>> nested = new java.util.concurrent.ConcurrentHashMap<>();
        Map<Path, String> errors = new java.util.concurrent.ConcurrentHashMap<>();
        Map<String, Map<String, String>> decided = new java.util.concurrent.ConcurrentHashMap<>();
        // Biggest first, so no big jar starts when the others are done.
        List<Path> order = new ArrayList<>(jars);
        order.sort(Comparator.comparingLong((Path j) -> j.toFile().length()).reversed().thenComparing(Path::toString));
        // Each release is read once: the new release of one step is the old of the next, and the next step's new release
        // is read while this step ports.
        String platform = loader;
        java.util.concurrent.ExecutorService reader = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        java.util.concurrent.Future<GameDiff.Snap> coming = reader.submit(() -> Exposure.snap(stops.get(1), platform));
        GameDiff.Snap was = Exposure.snap(stops.get(0), loader);
        for (int i = 0; i + 1 < stops.size(); i++) {
            int step = i;
            boolean last = i + 2 == stops.size();
            GameDiff.Snap now;
            try {
                now = coming.get();
            } catch (java.util.concurrent.ExecutionException failed) {
                throw failed.getCause() instanceof Exception e ? e : failed;
            }
            if (!last) {
                String after = stops.get(i + 2);
                coming = reader.submit(() -> Exposure.snap(after, platform));
            }
            // What making the porters adds to the new release (the step's stand-ins, libraries carried from the old one) is
            // taken out again after the step, so the next step reads the release as it was read.
            Set<String> read = new HashSet<>(now.classes.keySet());
            // Made one after another: making a porter adds the step's stand-ins and carried library classes to the releases.
            List<JarPort> porters = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                JarPort port = new JarPort(was, now, Path.of(maps.get(i)));
                if (loader != null) {
                    port.loader = loader;
                }
                port.runtimeNames = runtime && last;
                port.between = !last;
                port.loaderNames.originalsDir = originalsDir;
                port.origin = from;
                port.portTo = to;
                port.decided = decided;
                if ("fabric".equals(loader)) {
                    port.modMetadata.platform(Exposure.FabricApi.of(stops.get(i)), Exposure.FabricApi.of(stops.get(i + 1)));
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
                        if (errors.containsKey(jar)) {
                            continue;
                        }
                        String name = jar.getFileName().toString();
                        try {
                            byte[] in = step == 0 ? Files.readAllBytes(jar) : bytes.get(jar);
                            if (step == 0) {
                                originals.put(jar, port.loaderNames.originals(in, name));
                            }
                            List<Map<String, Object>> results = nested.computeIfAbsent(jar, k -> new ArrayList<>());
                            bytes.put(jar, port.port(in, name, results, originals.get(jar), last));
                        } catch (IOException | RuntimeException failed) {
                            errors.put(jar, String.valueOf(failed));
                            bytes.remove(jar);
                        }
                    }
                }));
            }
            for (Thread t : running) {
                t.join();
            }
            now.classes.keySet().retainAll(read);
            Exposure.forget(stops.get(i));
            Object[] gone = GameCode.READ.remove(stops.get(i));
            if (gone != null) {
                synchronized (GameCode.CALLERS) {
                    GameCode.CALLERS.remove(gone[0]);
                }
            }
            was = now;
        }
        reader.shutdown();
        List<Object> rows = new ArrayList<>();
        int linked = 0;
        Map<String, Set<String>> byMods = new HashMap<>();
        for (Path jar : jars) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mod", jar.getFileName().toString());
            if (errors.containsKey(jar)) {
                row.put("error", errors.get(jar));
            } else try {
                byte[] ported = bytes.get(jar);
                Path dest = folder ? target.resolve(jar.getFileName().toString()) : target;
                Files.write(dest, ported);
                // Every step's results: a reference one step could not link stays as it was, in that step's names, and breaks there.
                Set<String> unlinked = new TreeSet<>(), silent = new TreeSet<>(), skipped = new TreeSet<>(), refused = new TreeSet<>(), data = new TreeSet<>();
                boolean judged = false;
                Map<String, Integer> changed = new TreeMap<>();
                for (Map<String, Object> n : nested.getOrDefault(jar, List.of())) {
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
            } catch (IOException | RuntimeException failed) {
                row.put("error", String.valueOf(failed));
            }
            rows.add(row);
            System.out.println(row.get("mod") + ": " + (row.containsKey("error") ? row.get("error")
                : Json.array(row.get("unlinked")).size() + " unlinked, changed " + row.get("changed")));
        }
        System.out.printf("%d of %d jars link against %s%n", linked, jars.size(), to);
        if (report != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("from", from);
            r.put("to", to);
            r.put("loader", loader == null ? "" : loader);
            r.put("linked", linked);
            r.put("mods", rows);
            // What to teach the porter next: each reference that does not link, by how many mods it holds back.
            List<Object> ranked = new ArrayList<>();
            byMods.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, Set<String>> e) -> -e.getValue().size()).thenComparing(Map.Entry::getKey))
                .forEach(e -> ranked.add(Map.of("member", e.getKey(), "mods", e.getValue().size())));
            r.put("unlinkedByMods", ranked);
            Files.writeString(Path.of(report), Json.write(r), StandardCharsets.UTF_8);
        }
    }

    /** A whole jar, nested mods included; each jar's result is added to results. */
    byte[] port(byte[] jar, String name, List<Map<String, Object>> results) throws IOException {
        return port(jar, name, results, loaderNames.originals(jar, name), true);
    }

    /** One step of a port; last: the step into the release asked for, the only one a Fabric jar is handed back from. */
    byte[] port(byte[] jar, String name, List<Map<String, Object>> results, Map<String, Set<String>> originals, boolean last) throws IOException {
        boolean fabric = loader.equals("fabric");
        if (fabric && compareVersions(a.version, UNOBFUSCATED) < 0 && LoaderNames.intermediary(jar)) {
            jar = loaderNames.fabricIn().jar(jar, new ModNames.Counts());
        }
        byte[] out = verifier.fitting(jar, name, results);
        if (last && fabric && compareVersions(b.version, UNOBFUSCATED) < 0 && !results.isEmpty()) {
            // Fabric runs the jar in intermediary: a method whose name the hand-back cannot give is never called.
            @SuppressWarnings("unchecked") List<Object> unlinked = (List<Object>) results.get(results.size() - 1).get("unlinked");
            ModNames.Back back = loaderNames.handBack();
            for (String gap : back != null ? back.gaps(out, originals) : List.<String>of()) {
                unlinked.add("method name lost in the Fabric hand-back: " + gap);
            }
            // Two of a class's methods the hand-back gives one intermediary name is a class the JVM refuses to load
            // (GeckoLib's GeoProjectilesRenderer on 1.19); only the names Fabric runs show it.
            byte[] handed = back == null ? null : back.jar(out, originals);
            for (byte[] c : handed == null ? List.<byte[]>of() : ModNames.Back.classesIn(handed, b.version)) {
                for (String twice : JarEntries.declaredTwice(c)) {
                    unlinked.add("does not verify in Fabric's names: method declared twice: " + twice);
                }
            }
            if (runtimeNames && handed != null) {
                return handed;
            }
        }
        if (last && fabric && runtimeNames && compareVersions(b.version, UNOBFUSCATED) < 0) {
            out = loaderNames.fabricOut().jar(out, originals);
        }
        return out;
    }

    /** The first release Fabric runs in Mojang's names; before it, in that release's intermediary. */
    static final String UNOBFUSCATED = "26.1";

    boolean runtimeNames; // hand a Fabric jar back in the names Fabric runs, not Mojang's
    // A step before the last of a port: its jar is only read back by the next step, so it is written without compressing
    // (compressing every step's jars was a quarter of a long port), and only the last step's jar is the port. Nested jars
    // stay compressed: a later step may keep one as it is (a module the newer platform provides), bytes and all.
    boolean between;

    static final Pattern MOJANG_NAMED = Pattern.compile("net/minecraft/(?!class_\\d)[a-z]");

    /**
     * grants: what every access transformer in the outermost jar and its nested jars opens (null: read it here). The loader
     * applies an access transformer to the game for every mod, so a nested helper may rely on its parent's.
     */
    byte[] port(byte[] jar, String name, List<Map<String, Object>> results, Set<String> grants) throws IOException {
        Map<String, Long> times = new HashMap<>();
        Map<String, byte[]> entries = JarEntries.entries(jar, name, times);
        String renamed = null;
        if (loader.equals("neoforge")) {
            String wanted = modMetadata.metadataFile(), other = wanted.equals(ModMetadata.NEW_TOML) ? ModMetadata.OLD_TOML : ModMetadata.NEW_TOML;
            if (entries.containsKey(other) && !entries.containsKey(wanted)) {
                renamed = wanted.substring(wanted.lastIndexOf('/') + 1);
                entries = Overrides.moved(entries, times, other, wanted);
            }
        }
        Map<String, String> notLoaded = bundledMods.notLoaded(entries, name);
        Set<String> nested = JarEntries.nested(entries);
        nested.removeAll(notLoaded.keySet());
        List<String> copied = newerCode.copyNewer(entries, name);
        boolean outermost = grants == null;
        if (outermost) {
            standIn.typesUsed = false;
            Map<String, List<byte[]>> all = new HashMap<>();
            carried(entries, name, all);
            tree = new Carried(all);
            grants = new HashSet<>();
            for (String text : accessFiles.accessTransformers(entries, name)) {
                accessFiles.widen(grants, text);
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
        mod.canWiden = loader.equals("neoforge") && entries.containsKey(modMetadata.metadataFile()) || fmj != null;
        boolean nativeGame = false; // a class naming the game as this loader's build does
        Map<String, byte[]> jarEntries = entries;
        for (var e : entries.entrySet()) {
            if (e.getKey().endsWith(".class") && !e.getKey().startsWith("META-INF/versions/") && loaderNames.otherLoader(e.getValue())) {
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
        MixinPoints.requires(mod, entries);
        PLANNING.set(mod);
        try {
            plan(mod);
        } finally {
            PLANNING.remove();
        }
        eventBuses.buses(mod);
        for (var e : mod.split.entrySet()) {
            String file = e.getKey() + "$ModBusListeners.class";
            entries.put(file, eventBuses.companion(mod, e.getKey(), e.getValue()));
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
            olderJava.lowerJava(mod, rewritten, entries, name);
        }
        verifier.verify(mod, rewritten, entries);
        standIn.bundle(mod, rewritten, entries, name);
        List<String> atFiles = AccessFiles.atFiles(entries);
        String widener = fmj == null ? null : ModMetadata.widenerFile(fmj);
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
            if (nested.contains(e.getKey()) && modMetadata.platformModule(e.getValue()) instanceof String id) {
                platformNested.add(id);
            }
        }
        // Nested jars first: whether any jar of the tree uses a stand-in type decides what the outermost one carries.
        Map<String, byte[]> portedNested = new HashMap<>();
        Set<String> loadedByItself = JarEntries.selfLoaded(entries, JarEntries.listed(entries));
        for (var e : entries.entrySet()) {
            String n = e.getKey();
            boolean own = loadedByItself.contains(n);
            if (own && !loader.equals("fabric") && compareVersions(a.version, UNOBFUSCATED) < 0 && LoaderNames.intermediary(e.getValue())) {
                // Written in Fabric's intermediary names in a mod for a loader that runs Mojang's: its own code renames it at
                // start. Read in Mojang's names, ported, and handed back in the new release's intermediary.
                byte[] read = loaderNames.fabricIn().jar(e.getValue(), new ModNames.Counts());
                byte[] ported = port(read, name + "!" + n.substring(n.lastIndexOf('/') + 1), results, grants);
                ModNames.Back back = compareVersions(b.version, UNOBFUSCATED) < 0 ? loaderNames.handBack() : null;
                portedNested.put(n, back == null ? ported : back.jar(ported, ModNames.Back.methodNames(e.getValue())));
                mod.change("jar in Fabric's intermediary names its own code renames at start, ported through Mojang's");
                continue;
            }
            String foreignName = own ? JarEntries.notIn(e.getValue(), a.classes::containsKey) : null;
            if (foreignName != null) {
                portedNested.put(n, e.getValue());
                mod.change("jar its own code renames at start left as it came: " + n + " names " + Exposure.dotted(foreignName) + ", which " + a.version + " lacks");
            } else if ((nested.contains(n) || n.startsWith("META-INF/jarjar/") && n.endsWith(".jar")) && modMetadata.platformModule(e.getValue()) == null) {
                portedNested.put(n, port(e.getValue(), name + "!" + n.substring(n.lastIndexOf('/') + 1), results, grants));
            }
        }
        JarEntries.redigest(entries, entries, portedNested);
        String typesPath = null;
        if (outermost && standIn.typesUsed) {
            try {
                typesPath = standIn.carryTypes(entries, fmj != null);
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
                    data = JarEntries.unsigned(data);
                } else if (n.equals(ModMetadata.NEW_TOML) || n.equals(ModMetadata.OLD_TOML)) {
                    data = modMetadata.metadata(mod, new String(data, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                } else if (n.equals("fabric.mod.json")) {
                    data = OlderJava.runsOn(modMetadata.fabricMetadata(data, widener, platformNested), mod.lowered);
                } else if (n.equals("pack.mcmeta")) {
                    data = modMetadata.packMetadata(mod, data);
                } else if (n.equals(widener)) {
                    String text = accessFiles.accessWidener(mod, new String(data, StandardCharsets.UTF_8));
                    if (!mod.grants.isEmpty()) {
                        text += (text.endsWith("\n") ? "" : "\n") + "# Opened by mmt port: closed in " + to + ", used by this mod\n" + accessFiles.widenerLines(mod.grants);
                        mod.change("members opened through the mod's access widener");
                    }
                    data = text.getBytes(StandardCharsets.UTF_8);
                } else if (nested.contains(n) || n.startsWith("META-INF/jarjar/") && n.endsWith(".jar")) {
                    String nestedId = modMetadata.platformModule(data);
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
                    String text = accessFiles.accessTransformer(mod, new String(data, StandardCharsets.UTF_8));
                    if (fmj == null && n.equals(atFiles.get(0)) && !mod.grants.isEmpty()) {
                        text += (text.isEmpty() || text.endsWith("\n") ? "" : "\n") + "# Opened by mmt port: closed in " + to + ", used by this mod\n" + String.join("\n", mod.grants) + "\n";
                        mod.change("members opened through the mod's access transformer");
                    }
                    data = text.getBytes(StandardCharsets.UTF_8);
                }
                if (mod.lowered > 0 && n.endsWith(".json") && !n.equals("fabric.mod.json")) {
                    data = OlderJava.mixinLevel(data, mod.lowered);
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
                out.write(standIn.typesJar());
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

    // The last release of the whole port, which with origin decides the nested jars it carries (notLoaded): a step between
    // them would retarget a module it carries to a release its bundle has no module for. Null: this step's own releases.
    String portTo;
    // A port of several steps: each jar's choice of nested jars by its name as port names it (outer.jar!inner.jar), made
    // at the first step and kept for every step after, where a module a step ported asks for that step's release. Null: chosen each time.
    Map<String, Map<String, String>> decided;

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
                if (desc.equals(EventBuses.SUBSCRIBER)) {
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
                        if (annotation.equals(MixinMembers.SHADOW)) {
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
                        if (annotation.equals(EventBuses.LISTENER) && params.length == 1 && params[0].getSort() == Type.OBJECT) {
                            mod.listeners.computeIfAbsent(rc.name, k -> new ArrayList<>()).add(params[0].getInternalName());
                            EventBuses.Listener l = new EventBuses.Listener(name, desc, access, params[0].getInternalName(), new ArrayList<>());
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
                        if (MixinRetargets.INJECTORS.contains(annotation)) {
                            MixinRetargets.Injector inj = new MixinRetargets.Injector(annotation, new ArrayList<>(), new ArrayList<>(), new int[] { -1 }, new int[] { -1, -1 }, new boolean[1]);
                            mod.injectors.put(rc.name + "." + name + desc, inj);
                            return MixinRetargets.injection(inj, null);
                        }
                        String kind = MixinMembers.ACCESSORS.get(annotation);
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

    // ---- Linking: where the old release declares a member, and whether the new one has it as the JVM sees it.

    String cls(String internal) {
        return classMap.getOrDefault(internal, internal);
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
        for (String n : bundledMods.loadedNested(entries, name)) {
            String inner = name + "!" + n.substring(n.lastIndexOf('/') + 1);
            carried(JarEntries.entries(entries.get(n), inner, new HashMap<>()), inner, out);
        }
    }

    // ---- What changes in the mod's own declarations: overrides of renamed game methods, mixin shadows and accessors.

    void plan(Mod mod) {
        for (GameDiff.RawCls rc : mod.classes.values()) {
            overrides.lambdas(mod, rc);
            List<String> targets = mod.mixinTargets.getOrDefault(rc.name, List.of());
            if (!targets.isEmpty()) {
                for (String t : targets) {
                    if (a.classes.containsKey(t) && !b.classes.containsKey(cls(t))) {
                        mod.unlinked.add("mixin target class gone: " + Exposure.dotted(t) + " (in " + Exposure.dotted(rc.name) + ")");
                    }
                }
                mixinMembers.selfCasts(mod, rc.name, targets);
                for (var f : rc.fields.entrySet()) {
                    // A mixin's own field (often @Unique, or named like the target's) is added to the target, not looked up in it.
                    if (mod.shadowFields.contains(rc.name + "." + f.getKey())) {
                        mixinMembers.shadowField(mod, rc.name, targets, f.getKey(), f.getValue()[0]);
                    }
                }
                for (GameDiff.RawMeth m : rc.methods.values()) {
                    String key = rc.name + "." + m.name + m.desc;
                    if (mod.accessors.containsKey(key)) {
                        mixinMembers.accessor(mod, rc.name, targets, m, mod.accessors.get(key));
                    } else if (!m.name.startsWith("<")) {
                        mixinMembers.shadowMethod(mod, rc.name, targets, m.name, m.desc, m.flags);
                    }
                    MixinRetargets.Injector inj = mod.injectors.get(key);
                    if (inj != null && inj.kind().equals(MixinRetargets.INJECT)) {
                        mixinRetargets.retarget(mod, key, targets, m, inj);
                    } else if (inj != null) {
                        mixinRetargets.retargetOther(mod, key, targets, m, inj);
                    }
                    if (inj != null) {
                        mixinPoints.pointsGone(mod, rc.name, key, targets, inj, m.desc);
                    }
                }
                continue;
            }
            for (GameDiff.RawMeth m : rc.methods.values()) {
                if (!m.name.startsWith("<") && (m.flags & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0) {
                    overrides.override(mod, rc, m);
                }
            }
        }
        for (GameDiff.RawCls rc : mod.classes.values()) {
            if (mod.mixinTargets.getOrDefault(rc.name, List.of()).isEmpty()) {
                abstractMethods.abstracts(mod, rc);
            }
        }
        // A mod class's constructor moves after its mod superclass's has (1.17: a renderer extending the mod's own renderer).
        for (boolean grew = true; grew;) {
            grew = false;
            for (GameDiff.RawCls rc : mod.classes.values()) {
                if (mod.mixinTargets.getOrDefault(rc.name, List.of()).isEmpty()) {
                    for (GameDiff.RawMeth m : List.copyOf(rc.methods.values())) {
                        grew |= m.name.equals("<init>") && constructors.constructor(mod, rc, m);
                    }
                }
            }
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
        mod.bridges.computeIfAbsent(owner, x -> new ArrayList<>()).add(new Overrides.Bridge(m.name, newDesc, m.name, m.desc, from, m.flags, via));
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
            List<Overrides.Bridge> own = mod.bridges.get(new ClassReader(bytes).getClassName());
            if (own == null || own.stream().noneMatch(br -> br.via() != null)) {
                throw failed;
            }
            for (Overrides.Bridge br : own) {
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
                String replaced = superName == null || (access & Opcodes.ACC_INTERFACE) != 0 ? null : links.successor(superName);
                if (replaced != null) {
                    // Mixin wants a mixin to extend its target's superclass, which the new release names itself.
                    GameDiff.Cls target = targets.size() == 1 ? b.classes.get(cls(targets.get(0))) : null;
                    if (target != null && target.sup != null) {
                        replaced = target.sup;
                    }
                    mod.supers.put(name, new String[] { superName, replaced });
                    mod.change("superclass the game removed replaced by the one its own subclasses extend now");
                    if (links.becameInterface(superName) && (interfaces == null || !Arrays.asList(interfaces).contains(superName))) {
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
                if (desc.equals(EventBuses.SUBSCRIBER) && av != null && mod.toModBus.contains(self[0])) {
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
                        return mixinMembers.mixinStrings(mod, targets, super.visitAnnotation(d, visible), null);
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
                MixinRetargets.Adapter adapter = mod.adapters.get(key);
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
                            return d.equals(EventBuses.LISTENER) ? null : super.visitAnnotation(d, visible);
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
                    mv = new Split(JarPort.this, mv, staticized ? new Staticize(code) : code, self[0], name, desc, access, adapter, mod.selfInterface);
                    mod.change("mixin handler re-declared for its target's new parameters");
                }
                return mv == null ? null : new Sites(JarPort.this, mod, mv, locals, targets, callers, key, where);
            }

            @Override
            public void visitEnd() {
                for (FinalOverrides.Copy copy : mod.copies.getOrDefault(self[0], List.of())) {
                    finalOverrides.copyBody(copy, self[0], remap);
                }
                for (Overrides.Bridge br : mod.bridges.getOrDefault(self[0], List.of())) {
                    if (br.via() != null) {
                        overrides.moved(mod, bytes, self[0], br, remap);
                    } else {
                        overrides.bridgeCode(super.visitMethod(br.access(), br.name(), oldDesc(br.desc()), null, null), self[0], mod.selfInterface, br);
                    }
                }
                // An interface's methods are public before Java 9 (fabric-permissions-api's Permissions, nested in many mods).
                int open = mod.selfInterface && mod.selfVersion < Opcodes.V9 ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE;
                for (RuleCode.Added m : mod.added.values()) {
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

    final Retype retype = new Retype(this);

    /**
     * Where each value a method reads from a field goes, as the verifier follows the stack (asm-commons' AnalyzerAdapter).
     * A use is {"R", call, owner, name, desc} for the receiver of the method's call number call, {"A", call, type, i, owner,
     * name, desc, opcode} for its argument i of that type, {"C", type} for a cast, {"N"} for a null test or a pop. Anything else makes the read bad: a
     * store, a return, a jump that carries it, and what a value of another type would answer differently (==, instanceof).
     */
    static final Set<String> OBJECT_METHODS = Set.of("equals(Ljava/lang/Object;)Z", "hashCode()I", "toString()Ljava/lang/String;", "getClass()Ljava/lang/Class;");

    /** Every game class a descriptor or signature names that the new release no longer has (and no rename covers) is reported. */
    void types(Mod mod, String desc, String where) {
        if (desc == null) {
            return;
        }
        Matcher m = CLASS_IN_DESC.matcher(desc);
        while (m.find()) {
            String t = m.group(1);
            if (a.classes.containsKey(t) && !b.classes.containsKey(cls(t)) && links.provided(t) == null) {
                mod.unlinked.add("class gone: " + Exposure.dotted(t) + " (in " + where + ")");
            }
        }
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
            standIn.standins(dirs);
        }
        olderJava.jdkStandins = Downgrade.Standins.of(root.resolve("jdk"));
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
}


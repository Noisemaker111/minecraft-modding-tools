import java.io.*;
import java.lang.classfile.*;
import java.lang.constant.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Replacements for changed or removed game members, learned from how the game ported its own code. In every pair of
 * linked methods where a uses such a member, both bodies are rebuilt as statements of value trees (Decoder) and
 * lined up statement by statement through the links. Where a statement of a differs from its counterpart in b only
 * at a use of the member, what b computes there is the replacement; written over the member's own operands (its
 * receiver $0 and arguments $1..), it is kept as a Java expression (FixExpr, in b's names) when it reads nothing
 * else of the method: only operands, constants, static fields, and calls, fields, constructors, casts and static
 * method references over those. Every use that lines up is a vote, and the game's own uses must agree.
 */
final class GameLearner {

    final LearnOverrides learnOverrides = new LearnOverrides(this);
    final LearnByTypes learnByTypes = new LearnByTypes(this);
    final LearnClassMoves learnClassMoves = new LearnClassMoves(this);
    final LearnSameMeaning learnSameMeaning = new LearnSameMeaning(this);
    final LearnAccessors learnAccessors = new LearnAccessors(this);
    final LearnConstants learnConstants = new LearnConstants(this);
    final LearnHolders learnHolders = new LearnHolders(this);
    final LearnChains learnChains = new LearnChains(this);
    final LearnVotes learnVotes = new LearnVotes(this);
    final LearnCompiles learnCompiles = new LearnCompiles(this);
    final LearnPairing learnPairing = new LearnPairing(this);
    final LearnReading learnReading = new LearnReading(this);
    final LearnTypeMap learnTypeMap = new LearnTypeMap(this);

    /** A replacement for old (a member of a); linked: the member has a counterpart whose signature changed. */
    /**
     * when: null, or "k:NAME" where the reading holds only for a call whose operand $k is the enum constant NAME of its
     * declared type (1.21.5's new ClickEvent(Action.SUGGEST_COMMAND, s) is new ClickEvent.SuggestCommand(s)).
     */
    record Learned(String owner, String name, String desc, boolean field, boolean linked, String expr, int votes, int of, String when) {

        Learned(String owner, String name, String desc, boolean field, boolean linked, String expr, int votes, int of) {
            this(owner, name, desc, field, linked, expr, votes, of, null);
        }

        /** Its line in the route map's "learned" list, which JarPort reads. */
        String json(String step) {
            return "{ \"step\": " + VersionMap.Diff.q(step) + ", \"owner\": " + VersionMap.Diff.q(owner) + ", \"from\": " + VersionMap.Diff.q(name) +
                ", \"desc\": " + VersionMap.Diff.q(desc) + ", \"field\": " + field + ", \"linked\": " + linked + ", \"expr\": " + VersionMap.Diff.q(expr) +
                ", \"votes\": " + votes + ", \"of\": " + of + (when == null ? "" : ", \"when\": " + VersionMap.Diff.q(when)) + " }";
        }
    }

    final GameHop h;
    final Map<String, ClassModel> models = new HashMap<>();
    final Map<GameDiff.Ref, Boolean> brokenRefs = new LinkedHashMap<>();
    final Map<String, Map<GameDiff.Node, Set<String>>> sites = new HashMap<>(); // member key -> use in a -> its readings in b
    final Map<GameDiff.Node, String> aside = new IdentityHashMap<>(); // an unread use -> the call or field b makes there on a value of its own method
    final Map<String, GameDiff.Ref> keys = new HashMap<>();

    GameLearner(GameHop h) {
        this.h = h;
    }

    List<Learned> learn() {
        for (var e : h.mAB.entrySet()) {
            GameDiff.Meth x = e.getKey(), y = e.getValue();
            if (!x.hasCode || !y.hasCode) {
                continue;
            }
            boolean uses = false;
            for (GameDiff.Ref r : x.refs) {
                if (broken(r)) {
                    uses = true;
                    String key = r.owner() + "." + r.name() + (r.kind() == 'm' ? r.desc() : "");
                    keys.putIfAbsent(key, r);
                    sites.computeIfAbsent(key, k -> new IdentityHashMap<>());
                }
            }
            if (uses) {
                GameDiff.Body bx = decode(h.a, x.owner.name, x.name + x.desc, false);
                GameDiff.Body by = decode(h.b, y.owner.name, y.name + y.desc, false);
                learnPairing.pair(x.owner.name, bx, y.owner.name, by, 0);
            }
        }
        List<GameDiff.Node[]> again2 = later;
        later = null;
        for (GameDiff.Node[] d : again2) {
            learnReading.read(d[0], d[1]);
        }
        // With the class map settled, the uses whose new value had a type only it explains are read again.
        learnTypeMap.typeMap();
        List<GameDiff.Node[]> again = deferred;
        deferred = null;
        for (GameDiff.Node[] d : again) {
            learnReading.read(d[0], d[1]);
        }
        List<Learned> out = new ArrayList<>();
        StringBuilder log = LEARN_LOG.isEmpty() ? null : new StringBuilder();
        for (var e : sites.entrySet()) {
            GameDiff.Ref old = keys.get(e.getKey());
            int[] tally = new int[2];
            Map<GameDiff.Node, Set<String>> uses = learnVotes.withoutOtherValues(old, e.getValue());
            int otherValues = e.getValue().size() - uses.size();
            String best = LearnVotes.vote(uses.values(), tally);
            String bestNow = best;
            java.util.function.Predicate<GameDiff.Node> same = u -> bestNow != null && aside.containsKey(u) && bestNow.endsWith("." + aside.get(u));
            if (best != null && uses.keySet().stream().anyMatch(u -> same.test(u) && e.getValue().get(u).isEmpty())) {
                // A use b wrote as the same call on a value only its own method has (1.21.9's keyPressed(KeyEvent) asks
                // event.hasShiftDown() where Screen.hasShiftDown() was; other places ask Minecraft.getInstance()): a
                // mod's call has no such value, but the use says what is called: it counts for the reading.
                Map<GameDiff.Node, Set<String>> kept = new IdentityHashMap<>(e.getValue());
                kept.keySet().removeIf(u -> same.test(u) && kept.get(u).isEmpty());
                String narrowed = LearnVotes.vote(kept.values(), tally);
                if (best.equals(narrowed)) {
                    // Those uses agree on what is called and differ only in on what: each is a vote for the reading.
                    int also = uses.size() - kept.size();
                    tally[0] += also;
                    tally[1] += also;
                    uses = kept;
                } else {
                    best = LearnVotes.vote(uses.values(), tally);
                }
            } else if (uses.keySet().stream().anyMatch(u -> aside.containsKey(u) && e.getValue().get(u).isEmpty())) {
                // The plain vote picked a reading no such use backs (one place lined up with something else): the
                // reading the most of them back, as written in the places that could be read, if it has no choices.
                String pick = null;
                long most = 0;
                boolean tie = false;
                for (String r : uses.values().stream().flatMap(Set::stream).filter(r -> r.indexOf(OPEN) < 0).distinct().toList()) {
                    long backs = uses.keySet().stream().filter(u -> aside.containsKey(u) && e.getValue().get(u).isEmpty() && r.endsWith("." + aside.get(u))).count();
                    if (backs > most) {
                        pick = r;
                        most = backs;
                        tie = false;
                    } else if (backs == most && backs > 0) {
                        tie = true;
                    }
                }
                if (pick != null && !tie) {
                    String r = pick;
                    long read = uses.values().stream().filter(v -> v.contains(r)).count();
                    best = pick;
                    tally[0] = (int) (read + most);
                    tally[1] = uses.size();
                    Map<GameDiff.Node, Set<String>> kept = new IdentityHashMap<>(uses);
                    kept.keySet().removeIf(u -> aside.containsKey(u) && kept.get(u).isEmpty() && r.endsWith("." + aside.get(u)));
                    uses = kept;
                }
            }
            int sameOrInsisting = learnVotes.sameCallUnread(best, uses) + learnVotes.insisting(best, old, uses);
            int abstained = sameOrInsisting + otherValues;
            int top = tally[0], of = tally[1] - sameOrInsisting;
            // A single use agrees alone only where it is the only one: uses that abstained are not evidence for it.
            boolean agreed = top >= 2 && top * 10 >= of * 8 || top == 1 && of == 1 && abstained == 0 && usesAll(best, old, operands(old));
            int ofAll = tally[1] + otherValues;
            boolean plain = top >= 2 && top * 10 >= ofAll * 8 || top == 1 && ofAll == 1 && usesAll(best, old, operands(old));
            if (agreed && !plain && (top < 3 || !learnVotes.keepsOperands(best, old) || declared(old) != null && !learnCompiles.compiles(best, declared(old), old))) {
                // Only uses that abstained carried it over the bar: it needs three uses that agree, and may not also leave an operand out (permute(axis) as
                // axis, PerlinSimplexNoise(random, octaves) without the octaves), unless the old member only tested it,
                // and it must compile for the declared types (Builder.add(attribute, v) where 1.20.5 takes a Holder).
                agreed = false;
            }
            agreed = agreed && LearnVotes.beyondLiterals(best, uses, false, k -> learnVotes.onlyTested(old, k));
            if (!agreed && best != null) {
                // The game wrote different things by what it passed (1.21's new ResourceLocation(s): withDefaultNamespace
                // where s was a path it could see, parse where s came from data). A mod's argument is a value no one
                // can see in advance, so the reading for those is the general one: the uses whose every argument is
                // a call's or a field's result vote alone, and must agree as any vote must and keep every operand.
                Map<GameDiff.Node, Set<String>> opaque = new IdentityHashMap<>();
                uses.forEach((u, r) -> {
                    if (opaque(u)) {
                        // Only readings that pass a value from the operands everywhere they choose one: a fixed value
                        // there (bySeparator(s, ':')) is how one place was written, not what any value needs.
                        Set<String> keep = new LinkedHashSet<>();
                        for (String one : r) {
                            Matcher c = CHOICE.matcher(one);
                            boolean fixed = false;
                            while (c.find()) {
                                fixed |= !c.group(1).contains("$");
                            }
                            if (!fixed) {
                                keep.add(one);
                            }
                        }
                        opaque.put(u, keep);
                    }
                });
                int[] general = new int[2];
                String g = opaque.size() >= 2 && opaque.size() < uses.size() ? LearnVotes.vote(opaque.values(), general) : null;
                if (g != null && general[0] >= 2 && general[0] * 10 >= general[1] * 8 && usesAll(g, old, operands(old))) {
                    best = g;
                    top = general[0];
                    of = general[1];
                    agreed = true;
                }
            }
            if (!agreed && best != null) {
                // The game passed narrower values than the member declares (26.2's new BlockPos(v) had a BlockPos at
                // nearly every use and is written v or v.immutable() there; one use with a MutableBlockPos is new
                // BlockPos(v.getX(), v.getY(), v.getZ())). A mod's argument is only known to be the declared type, so
                // where a use's operand was narrower, only its readings that still compile for the declared types
                // count, and a use with none says nothing about a declared-type value. That holds only once the game
                // shows it: some narrower use's every reading fails to compile for the declared types. Without that
                // (find(o) with a String o, rewritten find(o, this)), an unread use still counts against. Only for a
                // member of one argument: with more, nearly every use passes something narrower for one of them, and
                // what is left to vote is too few places to tell an argument from a value they happened to share.
                String[] declared = declared(old);
                String[] asked = declared != null && declared.length == 2 ? declared : null;
                Map<GameDiff.Node, Set<String>> wide = new IdentityHashMap<>();
                boolean[] specialized = { false };
                if (asked != null) {
                    uses.forEach((u, r) -> {
                        int first = u.op == 'v' || u.op == 'f' ? 0 : 1;
                        boolean exact = true;
                        // The receiver is nearly always narrower (a call on this): only the arguments are asked.
                        for (int i = 1 - first; i < u.kids.length && i + first < asked.length; i++) {
                            exact &= asked[i + first] == null || asked[i + first].equals(u.kids[i].type);
                        }
                        if (exact) {
                            wide.put(u, r);
                            return;
                        }
                        Set<String> keep = new LinkedHashSet<>();
                        for (String one : r) {
                            if (learnCompiles.compiles(one, asked, old)) {
                                keep.add(one);
                            }
                        }
                        specialized[0] |= keep.isEmpty() && !r.isEmpty();
                        if (!keep.isEmpty()) {
                            wide.put(u, keep);
                        }
                    });
                }
                int[] general = new int[2];
                String g = specialized[0] && !wide.isEmpty() ? LearnVotes.vote(wide.values(), general) : null;
                if (g != null && (general[0] >= 2 && general[0] * 10 >= general[1] * 8 || general[0] == 1 && general[1] == 1) && usesAll(g, old, operands(old))
                    && LearnVotes.beyondLiterals(g, wide)) {
                    best = g;
                    top = general[0];
                    of = general[1];
                    agreed = true;
                }
            }
            if (log != null) {
                log.append(e.getKey()).append('\t').append(e.getValue().size()).append('\t').append(of).append('\t').append(top).append('\t')
                    .append(agreed).append('\t').append(best == null ? "" : best).append('\t');
                e.getValue().values().stream().flatMap(Set::stream).distinct().limit(4).forEach(r -> log.append(r.replace(OPEN, '{').replace(OR, '|').replace(CLOSE, '}')).append(" ;; "));
                log.append('\n');
            }
            List<Learned> split = learnConstants.perConstant(old, e.getValue());
            if (split.stream().map(Learned::expr).distinct().count() >= 2) {
                // Most uses passing one constant can outvote the others (1.21.5's new HoverEvent(action, v) is
                // ShowText at 9 of 11 uses, all SHOW_TEXT): where constants read differently, each keeps its own.
                learnConstants.byConstant.addAll(split);
                continue;
            }
            if (!agreed) {
                learnConstants.byConstant.addAll(split);
            }
            if (best == null || !agreed) {
                continue;
            }
            boolean field = old.kind() == 'f';
            // A library member is not one of the game's gone members: it is listed as linked, so it is not counted as one.
            boolean linked = !h.a.classes.containsKey(old.owner())
                || (field ? h.fAB.containsKey(h.a.field(old.owner(), old.name())) : h.mAB.containsKey(h.a.method(old.owner(), old.name(), old.desc())));
            out.add(new Learned(old.owner().replace('/', '.'), old.name(), field ? "" : old.desc(), field, linked, best, top, of));
        }
        learnChains.voteChains(log);
        // Y of a late chain is not dropped on its own: its value went into X's new call, so alone it says nothing.
        out.removeIf(l -> learnChains.chains.stream().anyMatch(c -> c.at() == -2 && c.y().owner().replace('/', '.').equals(l.owner()) && c.y().name().equals(l.name())
            && c.y().desc().equals(l.desc())));
        out.addAll(learnSameMeaning.sameMeaning(out, log));
        out.addAll(learnAccessors.throughAccessor(out));
        out.addAll(learnHolders.throughHolder(out));
        out.addAll(learnHolders.unwrapped(out));
        out.addAll(learnClassMoves.throughClassMap(out));
        out.addAll(learnClassMoves.renamedFamily(out));
        // A chain both of whose members are learned alone is already ported member by member.
        Set<String> alone = new HashSet<>();
        for (Learned l : out) {
            alone.add(l.owner().replace('.', '/') + "." + l.name() + l.desc());
        }
        learnChains.chains.removeIf(c -> alone.contains(c.x().owner() + "." + c.x().name() + (c.x().kind() == 'f' ? "" : c.x().desc()))
            && alone.contains(c.y().owner() + "." + c.y().name() + c.y().desc()));
        out.addAll(learnAccessors.accessors(out));
        out.addAll(learnOverrides.overrides(out));
        // Last, for what nothing of the game's own code answered.
        out.addAll(learnByTypes.byTypes(out));
        // Kept out of the passes above: each holds only where its constant is passed, so none is a member's reading.
        out.addAll(learnConstants.byConstant);
        // A total order: lines of one member (one per constant) come out the same way every run.
        out.sort(Comparator.comparing(Learned::owner).thenComparing(Learned::name).thenComparing(Learned::desc)
            .thenComparing(l -> l.when() == null ? "" : l.when()).thenComparing(Learned::expr));
        if (log != null) {
            try {
                Files.writeString(Path.of(LEARN_LOG + "-" + h.a.version + "-" + h.b.version + ".tsv"), log, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }

    /** -Dgamediff.learnlog=PREFIX: every member's uses lined up, uses read, votes and readings, one file per update. */
    static final String LEARN_LOG = System.getProperty("gamediff.learnlog", "");

    /** A choice inside a reading: a constant b passes that an operand also held, or code two operands share. */
    static final char OPEN = '\u0001', OR = '\u0002', CLOSE = '\u0003';
    static final Pattern CHOICE = Pattern.compile(OPEN + "([^" + CLOSE + "]*)" + CLOSE);
    /** Every operand and constant is a choice, even of one option, so uses that pass a constant and uses that pass code share a shape. */
    static String choice(List<String> options) {
        return options.isEmpty() ? null : OPEN + String.join(String.valueOf(OR), options) + CLOSE;
    }

    /** The old member's operand types by $N in a's names ($0 its receiver, null where there is none); null if unknown. */
    String[] declared(GameDiff.Ref old) {
        if (old.kind() != 'm') {
            return null;
        }
        GameDiff.Meth m = h.a.method(old.owner(), old.name(), old.desc());
        if (m == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        out.add(m.isStatic() || m.special() ? null : "L" + old.owner() + ";");
        for (ClassDesc p : MethodTypeDesc.ofDescriptor(old.desc()).parameterList()) {
            out.add(p.descriptorString());
        }
        return out.toArray(String[]::new);
    }

    /** The old member's operands: its receiver (unless static) and its arguments. */
    int operands(GameDiff.Ref old) {
        if (old.kind() == 'f') {
            GameDiff.Fld f = h.a.field(old.owner(), old.name());
            return f == null || (f.flags & GameDiff.ACC_STATIC) != 0 ? 0 : 1;
        }
        GameDiff.Meth m = h.a.method(old.owner(), old.name(), old.desc());
        m = m != null ? m : h.a.libraryMethod(old.owner(), old.name(), old.desc());
        int n = MethodTypeDesc.ofDescriptor(old.desc()).parameterCount();
        return m == null || m.isStatic() || m.special() ? n : n + 1;
    }

    /** A reading seen once is kept only when it uses every operand and takes no constant: nothing in it is a guess. */
    boolean usesAll(String expr, GameDiff.Ref old, int operands) {
        GameDiff.Meth m = old.kind() == 'm' ? h.a.method(old.owner(), old.name(), old.desc()) : null;
        m = m != null || old.kind() != 'm' ? m : h.a.libraryMethod(old.owner(), old.name(), old.desc());
        int first = old.kind() == 'f' || m != null && !m.isStatic() && !m.special() ? 0 : 1;
        for (int i = first; i < first + operands; i++) {
            if (!Pattern.compile("\\$" + i + "(?!\\d)").matcher(expr).find()) {
                return false;
            }
        }
        return !Pattern.compile("(^|[(, ])(-?\\d|\"|true|false|null)").matcher(expr).find();
    }

    ClassModel model(GameDiff.Snap s, String cls) {
        GameDiff.Origin o = s.origins.get(cls);
        if (o == null) {
            return null;
        }
        return models.computeIfAbsent((s == h.a ? "a:" : "b:") + cls, k -> ClassFile.of().parse(o.bytes()));
    }

    /** One method's body, by its Mojang name + desc, or by its raw name + desc (a lambda of the same class). */
    GameDiff.Body decode(GameDiff.Snap s, String cls, String key, boolean raw) {
        ClassModel cm = model(s, cls);
        if (cm == null) {
            return null;
        }
        GameDiff.Origin o = s.origins.get(cls);
        String self = cm.thisClass().asInternalName();
        for (MethodModel mm : cm.methods()) {
            String rn = mm.methodName().stringValue(), rd = mm.methodType().stringValue();
            String k = raw ? rn + rd : o.names().method(self, rn, rd) + o.names().desc(rd);
            if (k.equals(key)) {
                return new BodyDecoder(o, self).run(mm);
            }
        }
        return null;
    }
    // A changed member's use -> what b computes where it lined up: that value stands for it as an operand elsewhere
    // (1.21: the builder getBuilder() gave is the one begin(mode, format) gives, so builder.end() reads as $0.buildOrThrow()).
    final Map<GameDiff.Node, Set<GameDiff.Node>> lined = new IdentityHashMap<>();

    /**
     * A value made from nothing of the old member's: a call or a new with no operand among its arguments
     * (UniformGenerator.between(0.0F, 1.0F), new ByteBufferBuilder(1536)). Where a reading leaves an operand out and has
     * one, b may have written that operand's own replacement there; a fixed flag in its place (false) is the member's.
     */
    /** A use whose every argument (its receiver apart) is a call's or a field's result: a value its writer could not see. */
    static boolean opaque(GameDiff.Node use) {
        int from = use.op == 'v' || use.op == 'f' ? 1 : 0;
        if (use.kids.length <= from) {
            return false;
        }
        for (int i = from; i < use.kids.length; i++) {
            GameDiff.Node k = use.kids[i].op == 't' && use.kids[i].kids.length > 0 ? use.kids[i].kids[0] : use.kids[i];
            if (k.op != 'v' && k.op != 's' && k.op != 'f' && k.op != 'g') {
                return false;
            }
        }
        return true;
    }

    Map<GameDiff.Cls, GameDiff.Cls> rewritten;

    /** Classes b dropped and the class the game's own code uses in their place: a mod's values of one become the other. */
    Map<GameDiff.Cls, GameDiff.Cls> classRewrites() {
        if (rewritten == null) {
            rewritten = new HashMap<>();
            for (HopRewrites.ClassRewrite r : h.hopRewrites.classRewrites()) {
                rewritten.put(r.from(), r.to());
            }
        }
        return rewritten;
    }

    List<GameDiff.Node[]> deferred = new ArrayList<>(); // uses whose new value fits only once the class map is known
    List<GameDiff.Node[]> later = new ArrayList<>(); // uses with an operand whose new value no statement had shown yet
    boolean weak; // the statement lined up only by standing next to the same anchor: chains only, no vote

    /** Whether a's reference names a member a mod could use that b dropped or gave another signature. */
    boolean broken(GameDiff.Ref r) {
        if (r == null || r.kind() != 'm' && r.kind() != 'f') {
            return false;
        }
        Boolean known = brokenRefs.get(r);
        if (known != null) {
            return known;
        }
        boolean out = false;
        if (r.kind() == 'm') {
            GameDiff.Meth m = h.a.method(r.owner(), r.name(), r.desc());
            if (m != null && (m.flags & GameDiff.ACC_PRIVATE) == 0 && !m.name.equals("<clinit>")) {
                GameDiff.Meth y = h.mAB.get(m);
                out = y == null || y.isStatic() != m.isStatic() || !h.descBA(y.desc).equals(m.desc) || closed(m.flags, y.flags);
            }
        } else {
            GameDiff.Fld f = h.a.field(r.owner(), r.name());
            if (f != null && (f.flags & GameDiff.ACC_PRIVATE) == 0) {
                GameDiff.Fld y = h.fAB.get(f);
                // A field that moved to another class: the route map carries moved methods, not moved fields
                // (1.19.3's Registry.BLOCK, now BuiltInRegistries.BLOCK).
                out = y == null || ((y.flags ^ f.flags) & GameDiff.ACC_STATIC) != 0 || !h.descBA(y.desc).equals(f.desc) || closed(f.flags, y.flags)
                    || y.owner != h.cAB.get(f.owner);
            }
        }
        if (!out && !h.a.classes.containsKey(r.owner()) && h.a.libraries.containsKey(r.owner())) {
            // A library member the new release's copy of that library no longer has (1.21.9's authlib made GameProfile a
            // record: getName() is name()), read from the game's own uses like a game member.
            if (r.kind() == 'm') {
                GameDiff.Meth m = h.a.libraryMethod(r.owner(), r.name(), r.desc());
                out = m != null && (m.flags & (GameDiff.ACC_PUBLIC | 0x0004)) != 0 && !m.name.startsWith("<cl") && h.b.libraries.containsKey(r.owner())
                    && h.b.libraryMethod(r.owner(), r.name(), r.desc()) == null;
            } else {
                GameDiff.Fld f = h.a.libraryField(r.owner(), r.name());
                out = f != null && (f.flags & (GameDiff.ACC_PUBLIC | 0x0004)) != 0 && h.b.libraries.containsKey(r.owner()) && h.b.libraryField(r.owner(), r.name()) == null;
            }
        }
        brokenRefs.put(r, out);
        return out;
    }

    /** A member a mod could reach (public or protected) that b made private or package-private (1.21.2's PartPose.x). */
    static boolean closed(int before, int after) {
        return (before & (GameDiff.ACC_PUBLIC | 0x0004)) != 0 && (after & (GameDiff.ACC_PUBLIC | 0x0004)) == 0;
    }

    static String dotted(String internal) {
        return internal.replace('/', '.');
    }
}

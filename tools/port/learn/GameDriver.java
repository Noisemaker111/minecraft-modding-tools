import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

final class GameDriver implements MapChain.Loader {

    final List<String> versions;
    final String loader;
    final List<String> pinned;
    final Map<String, String> loaderUsed = new LinkedHashMap<>();
    final List<String> loaderNotes = new ArrayList<>();
    final List<String> hops = new ArrayList<>();
    /** Call-site rewrites of every hop (Hop.rewrites), each target carried to the newest release. */
    final List<Switched> rewrites = new ArrayList<>();
    /** Removed classes and the class the game's code uses in their place (Hop.classRewrites). */
    final List<String> classRewrites = new ArrayList<>();
    /** Members the game replaced with a chain on the same receiver (Hop.chains). */
    final List<String> chains = new ArrayList<>();
    /** Methods moved to a class one holder keeps beside the old one (Hop.siblings). */
    final List<String> siblings = new ArrayList<>();
    /** Parameters the game added with one value its own code always passes (Hop.argDefaults). */
    final List<String> argDefaults = new ArrayList<>();
    /** Replacements written as expressions over the old member's operands (Learner), applied like a rule's inClass. */
    final List<Carried> learned = new ArrayList<>();
    /** The chain's first release: each member's {owner, name, desc} by id, and each class's id by name. */
    final Map<String, String[]> firstMembers = new HashMap<>();
    final Map<String, String> firstClasses = new HashMap<>();

    /**
     * The member l replaces as the chain's first release names it, where that release had it under another name or
     * owner with the same operands (each class in the descriptor the same class, by id); null otherwise.
     */
    String[] first(GameHop h, GameLearner.Learned l) {
        String owner = l.owner().replace('.', '/');
        String name = l.name().endsWith("=") ? l.name().substring(0, l.name().length() - 1) : l.name();
        String id, desc;
        if (l.field()) {
            GameDiff.Fld f = h.a.field(owner, name);
            id = f == null ? null : f.id;
            desc = f == null ? null : f.desc;
        } else {
            GameDiff.Meth m = h.a.method(owner, name, l.desc());
            id = m == null ? null : m.id;
            desc = l.desc();
        }
        String[] was = id == null ? null : firstMembers.get(id);
        if (was == null || was[0].equals(owner) && was[1].equals(name) && was[2].equals(desc)) {
            return null;
        }
        Pattern part = Pattern.compile("\\[*(?:L([^;]+);|[ZBCSIJFDV])");
        Matcher x = part.matcher(was[2]), y = part.matcher(desc);
        while (x.find()) {
            if (!y.find() || (x.group(1) == null) != (y.group(1) == null)) {
                return null;
            }
            if (x.group(1) == null ? !x.group().equals(y.group()) : !sameClass(h, x.group(1), y.group(1))) {
                return null;
            }
        }
        return y.find() ? null : was;
    }

    boolean sameClass(GameHop h, String inFirst, String now) {
        GameDiff.Cls c = h.a.classes.get(now);
        String id = firstClasses.get(inFirst);
        return c == null || id == null ? inFirst.equals(now) : id.equals(c.id);
    }

    /** A line of the route map's "learned" list, its expression carried to the newest release as the chain goes on (Carry). */
    static final class Carried {

        final String step;
        final GameLearner.Learned learned; // null: a line that is not carried (a chain of two members)
        final String fixed;
        String expr;
        String[] types; // its operands' types ($0 the receiver) in the newest release's names; null once it stops
        String[] first; // {owner, name, desc} of the member in the chain's first release, where that names it otherwise

        Carried(String step, GameLearner.Learned learned, String fixed, String[] types) {
            this.step = step;
            this.learned = learned;
            this.fixed = fixed;
            this.types = types;
            expr = learned == null ? null : learned.expr();
        }

        void carry(LearnedCarry c) {
            if (learned == null || types == null || expr.isEmpty()) {
                return;
            }
            String next = c.carry(expr, types);
            if (next == null) {
                types = null;
                return;
            }
            expr = next;
            for (int i = 0; i < types.length; i++) {
                types[i] = types[i] == null ? null : LearnedCarry.descAB(c.h, types[i]);
            }
        }

        String json() {
            if (learned == null) {
                return fixed;
            }
            GameLearner.Learned l = learned;
            // Filed under the name a mod of the chain's first release calls (1.21.9 learned packVersion(type) ->
            // $0.packVersion($1).major(); a 1.21.1 mod calls it getPackVersion, renamed at 1.21.5).
            String owner = first == null ? l.owner() : first[0].replace('/', '.');
            String name = first == null ? l.name() : first[1] + (l.name().endsWith("=") ? "=" : "");
            String desc = first == null || l.field() ? l.desc() : first[2];
            String line = new GameLearner.Learned(owner, name, desc, l.field(), l.linked(), expr, l.votes(), l.of(), l.when()).json(step);
            if (first != null) {
                line = line.substring(0, line.length() - 2) + ", \"learnedFor\": " + VersionMap.Diff.q(l.owner() + "." + l.name() + l.desc()) + " }";
            }
            // What the step itself learned, where later updates renamed what it names.
            return expr.equals(l.expr()) ? line : line.substring(0, line.length() - 2) + ", \"learnedAs\": " + VersionMap.Diff.q(l.expr()) + " }";
        }
    }

    /** A call the game's code switched to at one update (Hop.Rewrite), its target followed to the newest release. */
    static final class Switched {

        final String step, owner, from, desc; // strings, not the Meth: an old release is not kept alive
        final boolean isStatic;
        final int votes, total;
        String toOwner, to, newDesc;
        boolean newStatic, stopped;

        Switched(String step, HopRewrites.Rewrite r) {
            this.step = step;
            owner = r.from().owner.name;
            from = r.from().name;
            desc = r.from().desc;
            isStatic = r.from().isStatic();
            votes = r.votes();
            total = r.total();
            if (r.to() != null) {
                toOwner = r.to().owner.name;
                to = r.to().name;
                newDesc = r.to().desc;
                newStatic = r.to().isStatic();
            }
        }

        /** The target in b's names: its counterpart, or the call h's code switched it to; it stays where it is if neither. */
        void carry(LearnedCarry c) {
            if (to == null || stopped) {
                return;
            }
            GameDiff.Meth m = c.h.a.method(toOwner, to, newDesc);
            m = m != null ? m : c.h.a.libraryMethod(toOwner, to, newDesc);
            if (m == null) {
                stopped = true;
                return;
            }
            GameDiff.Meth y = c.learner.broken(new GameDiff.Ref('m', m.owner.name, m.name, m.desc)) ? c.calls.get(m.sig()) : c.h.mAB.getOrDefault(m, m);
            if (y == null || y.isStatic() != m.isStatic()) {
                stopped = true;
                return;
            }
            toOwner = y.owner.name;
            to = y.name;
            newDesc = y.owner.library || y == m ? newDesc : y.desc;
        }

        String json() {
            return "{ \"step\": " + VersionMap.Diff.q(step) +
                ", \"owner\": " + VersionMap.Diff.q(owner.replace('/', '.')) +
                ", \"from\": " + VersionMap.Diff.q(from) +
                ", \"desc\": " + VersionMap.Diff.q(desc) +
                ", \"static\": " + isStatic +
                // A call the game deleted has no replacement: an empty "to".
                ", \"toOwner\": " + VersionMap.Diff.q(to == null ? "" : toOwner.replace('/', '.')) +
                ", \"to\": " + VersionMap.Diff.q(to == null ? "" : to) +
                ", \"newDesc\": " + VersionMap.Diff.q(to == null ? "" : newDesc) +
                ", \"newStatic\": " + (to != null && newStatic) +
                ", \"votes\": " + votes + ", \"of\": " + total + " }";
        }
    }
    /** Calls on a parameter the game retyped, written over the new type (Retyped). */
    final List<String> retyped = new ArrayList<>();
    GameDiff.Snap prev;
    Path prevLoaderJar;
    String prevLoaderVersion;
    int nextClass = 1;
    int nextMethod = 1;
    int nextField = 1;
    String metadata;

    static final ThreadLocal<GameDiff.Snap> LAST = new ThreadLocal<>(); // the release this thread's last chain ended at
    static final ThreadLocal<String> LAST_LOADER = ThreadLocal.withInitial(() -> "");

    GameDriver(List<String> versions, String loader, List<String> pinned) {
        this.versions = versions;
        this.loader = loader;
        this.pinned = pinned;
    }

    List<byte[]> prevPlatform;

    @Override
    public VersionMap.Table load(String version) throws Exception {
        Path jar = null;
        String lv = null;
        if (loader != null && (pinned == null || "fabric".equals(loader))) {
            // The loader as a mod runs on it, the classes the link check reads (Exposure.snap): NeoForge's universal jar
            // with FML and its other net.neoforged libraries, or Fabric Loader with Fabric API's modules, in Mojang
            // names. Its own code uses its own API, so a change to it is learned like the game's.
            Exposure.Platform p = Exposure.platform(loader, version);
            List<byte[]> classes = p.classes();
            if (!classes.isEmpty()) {
                prevPlatform = classes;
                prevLoaderVersion = p.version();
                loaderUsed.put(version, p.read() == null ? loader : p.read());
            } else if (prevPlatform != null) {
                classes = prevPlatform;
                loaderNotes.add(version + ": no " + loader + " release; carried " + prevLoaderVersion);
                loaderUsed.put(version, null);
            } else {
                loaderNotes.add(version + ": no " + loader + " release; loader classes start at a later release");
                loaderUsed.put(version, null);
            }
            // The release that ended the last chain of this process starts this one (a long port's steps): read once.
            GameDiff.Snap last = LAST.get();
            GameDiff.Snap s = last != null && last.version.equals(version) && Objects.equals(last.loaderVersion, p.version()) && LAST_LOADER.get().equals(loader)
                ? last : GameDiff.load(version, classes, p.version());
            LAST.set(s);
            LAST_LOADER.set(loader);
            return next(s, null, p.version());
        }
        if (loader != null) {
            int i = versions.indexOf(version);
            lv = pinned != null && i < pinned.size() && !pinned.get(i).isBlank() ? pinned.get(i) : null;
            if (lv == null) {
                if (metadata == null) {
                    metadata = VersionMap.fetchText(GameDiff.NEOFORGE + "maven-metadata.xml", "neoforge-maven-metadata.xml");
                }
                lv = GameDiff.neoforgeFor(version, metadata);
            }
            if (lv != null) {
                jar = VersionMap.fetch(GameDiff.NEOFORGE + lv + "/neoforge-" + lv + "-universal.jar", "neoforge-" + lv + "-universal.jar");
                loaderUsed.put(version, lv);
            } else if (prevLoaderJar != null) {
                jar = prevLoaderJar;
                loaderNotes.add(version + ": no NeoForge release; carried " + prevLoaderVersion);
                loaderUsed.put(version, null);
                lv = prevLoaderVersion;
            } else {
                loaderNotes.add(version + ": no NeoForge release; loader classes start at a later release");
                loaderUsed.put(version, null);
            }
        }
        GameDiff.Snap s = GameDiff.load(version, jar, lv);
        return next(s, jar, lv);
    }

    VersionMap.Table next(GameDiff.Snap s, Path jar, String lv) throws Exception {
        if (prev == null) {
            for (GameDiff.Cls c : s.classes.values()) {
                fresh(c);
                firstClasses.put(c.name, c.id);
                for (GameDiff.Meth m : c.methods.values()) {
                    firstMembers.put(m.id, new String[] { c.name, m.name, m.desc });
                }
                for (GameDiff.Fld f : c.fields.values()) {
                    firstMembers.put(f.id, new String[] { c.name, f.name, f.desc });
                }
            }
        } else {
            long t0 = System.nanoTime();
            GameHop h = new GameHop(prev, s);
            h.published = GameDiff.Published.read(loader, prev.version, s.version);
            h.run();
            for (GameDiff.Cls c : s.classes.values()) {
                GameDiff.Cls ca = h.cBA.get(c);
                c.id = ca == null ? "class_g" + nextClass++ : ca.id;
                for (GameDiff.Meth m : c.methods.values()) {
                    GameDiff.Meth ma = h.mBA.get(m);
                    m.id = ma == null ? "method_g" + nextMethod++ : ma.id;
                }
                for (GameDiff.Fld f : c.fields.values()) {
                    GameDiff.Fld fa = h.fBA.get(f);
                    f.id = fa == null ? "field_g" + nextField++ : fa.id;
                }
            }
            String line = String.format(
                Locale.ROOT,
                "%s -> %s: linked %d/%d classes, %d/%d methods, %d/%d fields (%.1fs)",
                prev.version,
                s.version,
                h.cAB.size(),
                prev.classes.size(),
                h.mAB.size(),
                prev.methodCount(),
                h.fAB.size(),
                prev.fieldCount(),
                (System.nanoTime() - t0) / 1e9
            );
            System.out.println("  " + line);
            // The map holds no time, so the same releases and tools write the same bytes, and what is cached by them is found again.
            hops.add(line.substring(0, line.lastIndexOf(" (")));
            String step = prev.version + " -> " + s.version;
            List<HopRewrites.Rewrite> found = h.hopRewrites.rewrites();
            for (HopArgDefaults.ArgDefault d : h.hopArgDefaults.argDefaults(found)) {
                argDefaults.add(
                    "{ \"step\": " + VersionMap.Diff.q(prev.version + " -> " + s.version) +
                    ", \"owner\": " + VersionMap.Diff.q(d.from().owner.name.replace('/', '.')) +
                    ", \"from\": " + VersionMap.Diff.q(d.from().name) +
                    ", \"desc\": " + VersionMap.Diff.q(d.from().desc) +
                    ", \"toOwner\": " + VersionMap.Diff.q(d.to().owner.name.replace('/', '.')) +
                    ", \"to\": " + VersionMap.Diff.q(d.to().name) +
                    ", \"newDesc\": " + VersionMap.Diff.q(d.to().desc) +
                    ", \"index\": " + d.index() +
                    ", \"value\": " + VersionMap.Diff.q(d.value()) +
                    ", \"votes\": " + d.votes() + ", \"of\": " + d.of() + " }"
                );
            }
            for (HopChains.Sibling c : h.hopChains.siblings()) {
                siblings.add(
                    "{ \"step\": " + VersionMap.Diff.q(prev.version + " -> " + s.version) +
                    ", \"owner\": " + VersionMap.Diff.q(c.from().owner.name.replace('/', '.')) +
                    ", \"from\": " + VersionMap.Diff.q(c.from().name) +
                    ", \"desc\": " + VersionMap.Diff.q(c.from().desc) +
                    ", \"holder\": " + VersionMap.Diff.q(c.holder()) +
                    ", \"fromField\": " + VersionMap.Diff.q(c.fromField()) +
                    ", \"toField\": " + VersionMap.Diff.q(c.toField()) +
                    ", \"newOwner\": " + VersionMap.Diff.q(c.to().owner.name.replace('/', '.')) +
                    ", \"newDesc\": " + VersionMap.Diff.q(c.to().desc) + " }"
                );
            }
            for (HopChains.Chain c : h.hopChains.chains()) {
                chains.add(
                    "{ \"step\": " + VersionMap.Diff.q(prev.version + " -> " + s.version) +
                    ", \"owner\": " + VersionMap.Diff.q(c.owner()) +
                    ", \"newOwner\": " + VersionMap.Diff.q(c.newOwner()) +
                    ", \"from\": " + VersionMap.Diff.q(c.name()) +
                    ", \"desc\": " + VersionMap.Diff.q(c.desc()) +
                    ", \"field\": " + c.field() +
                    ", \"steps\": " + VersionMap.Diff.q(c.steps()) +
                    ", \"votes\": " + c.votes() + ", \"of\": " + c.of() + " }"
                );
            }
            GameLearner learner = new GameLearner(h);
            List<GameLearner.Learned> fresh = learner.learn();
            fresh.addAll(new SameCallLearner(h, learner).learn(fresh));
            // What earlier updates learned, carried through this one before this one's own is added.
            LearnedCarry carry = new LearnedCarry(h, learner, fresh, found);
            for (Carried c : learned) {
                c.carry(carry);
            }
            for (Switched w : rewrites) {
                w.carry(carry);
            }
            for (HopRewrites.Rewrite r : found) {
                rewrites.add(new Switched(step, r));
            }
            for (GameLearner.Learned l : fresh) {
                Carried c = new Carried(step, l, null, LearnedCarry.operands(h, l));
                c.first = first(h, l);
                learned.add(c);
            }
            for (LearnChains.Chained c : learner.learnChains.chains) {
                learned.add(new Carried(step, null, c.json(step), null));
            }
            for (RetypedLearner.Reading r : new RetypedLearner(h, learner).learn()) {
                retyped.add(r.json(prev.version + " -> " + s.version));
            }
            List<HopRewrites.ClassRewrite> rewrites = new ArrayList<>(h.hopRewrites.classRewrites());
            rewrites.addAll(learner.learnTypeMap.typeRewrites);
            for (HopRewrites.ClassRewrite r : rewrites) {
                classRewrites.add(
                    "{ \"step\": " + VersionMap.Diff.q(prev.version + " -> " + s.version) +
                    ", \"from\": " + VersionMap.Diff.q(r.from().name.replace('/', '.')) +
                    ", \"to\": " + VersionMap.Diff.q(r.to().name.replace('/', '.')) +
                    ", \"votes\": " + r.votes() + ", \"of\": " + r.total() + " }"
                );
            }
        }
        prev = s;
        prevLoaderJar = jar;
        prevLoaderVersion = lv;
        return table(s);
    }

    void fresh(GameDiff.Cls c) {
        c.id = "class_g" + nextClass++;
        for (GameDiff.Meth m : c.methods.values()) {
            m.id = "method_g" + nextMethod++;
        }
        for (GameDiff.Fld f : c.fields.values()) {
            f.id = "field_g" + nextField++;
        }
    }

    static VersionMap.Table table(GameDiff.Snap s) {
        VersionMap.Table t = new VersionMap.Table(s.version);
        Map<String, String> toId = new HashMap<>();
        for (GameDiff.Cls c : s.classes.values()) {
            t.classes.put(c.id, c.name);
            toId.put(c.name, c.id);
        }
        for (GameDiff.Cls c : s.classes.values()) {
            for (GameDiff.Meth m : c.methods.values()) {
                if (m.special()) {
                    continue;
                }
                String ids = VersionMap.Tiny.remapDesc(m.desc, toId);
                String k = VersionMap.Table.key(c.id, m.id, ids);
                t.methods.put(k, new VersionMap.Member(c.name, m.name, m.desc, ids));
                t.methodsByInterName.computeIfAbsent(m.id, x -> new ArrayList<>()).add(k);
            }
            for (GameDiff.Fld f : c.fields.values()) {
                String ids = VersionMap.Tiny.remapDesc(f.desc, toId);
                t.fields.put(VersionMap.Table.key(c.id, f.id, ids), new VersionMap.Member(c.name, f.name, f.desc, ids));
            }
        }
        return t;
    }

    String json(MapChain c) {
        String body = c.json("mojmap");
        int nl = body.indexOf('\n', 2);
        StringBuilder head = new StringBuilder();
        head.append("  \"linked\": \"game-bytecode\",\n");
        head.append(
            "  \"source\": " +
            VersionMap.Diff.q(
                "Mojang client jars and client_mappings from piston-meta (jar names from 26.1); linked release to release by GameDiff from " +
                "the game's own bytecode (names, bodies, references, overrides, field access); no intermediary or yarn"
            ) +
            ",\n"
        );
        head.append("  \"hops\": {");
        for (int i = 0; i < hops.size(); i++) {
            String h = hops.get(i);
            head.append(i == 0 ? "" : ", ").append(VersionMap.Diff.q(h.substring(0, h.indexOf(':')))).append(": ");
            head.append(VersionMap.Diff.q(h.substring(h.indexOf(':') + 2)));
        }
        head.append("},\n");
        // One per line, as the typed fixer reads the other lists (VersionMap.Known.load).
        head.append("  \"rewrites\": [");
        for (int i = 0; i < rewrites.size(); i++) {
            head.append(i == 0 ? "\n    " : ",\n    ").append(rewrites.get(i).json());
        }
        head.append(rewrites.isEmpty() ? "],\n" : "\n  ],\n");
        head.append("  \"argDefaults\": [");
        for (int i = 0; i < argDefaults.size(); i++) {
            head.append(i == 0 ? "\n    " : ",\n    ").append(argDefaults.get(i));
        }
        head.append(argDefaults.isEmpty() ? "],\n" : "\n  ],\n");
        head.append("  \"siblings\": [");
        for (int i = 0; i < siblings.size(); i++) {
            head.append(i == 0 ? "\n    " : ",\n    ").append(siblings.get(i));
        }
        head.append(siblings.isEmpty() ? "],\n" : "\n  ],\n");
        head.append("  \"chains\": [");
        for (int i = 0; i < chains.size(); i++) {
            head.append(i == 0 ? "\n    " : ",\n    ").append(chains.get(i));
        }
        head.append(chains.isEmpty() ? "],\n" : "\n  ],\n");
        head.append("  \"classRewrites\": [");
        for (int i = 0; i < classRewrites.size(); i++) {
            head.append(i == 0 ? "\n    " : ",\n    ").append(classRewrites.get(i));
        }
        head.append(classRewrites.isEmpty() ? "],\n" : "\n  ],\n");
        // Learned in parallel, so in no fixed order: written sorted, so one run's map is every run's.
        List<String> learnedLines = learned.stream().map(l -> l.json()).sorted().toList();
        head.append("  \"learned\": [");
        for (int i = 0; i < learnedLines.size(); i++) {
            head.append(i == 0 ? "\n    " : ",\n    ").append(learnedLines.get(i));
        }
        head.append(learned.isEmpty() ? "],\n" : "\n  ],\n");
        List<String> retypedLines = retyped.stream().sorted().toList();
        head.append("  \"retyped\": [");
        for (int i = 0; i < retypedLines.size(); i++) {
            head.append(i == 0 ? "\n    " : ",\n    ").append(retypedLines.get(i));
        }
        head.append(retyped.isEmpty() ? "],\n" : "\n  ],\n");
        if (loader != null) {
            String source = "fabric".equals(loader) ? Exposure.FabricApi.MAVEN : GameDiff.NEOFORGE;
            head.append("  \"loader\": { \"name\": ").append(VersionMap.Diff.q(loader)).append(", \"source\": ").append(VersionMap.Diff.q(source)).append(", \"versions\": {");
            int i = 0;
            for (var e : loaderUsed.entrySet()) {
                head.append(i++ == 0 ? "" : ", ").append(VersionMap.Diff.q(e.getKey())).append(": ").append(VersionMap.Diff.q(e.getValue()));
            }
            head.append("}, \"notes\": {");
            for (int j = 0; j < loaderNotes.size(); j++) {
                String n = loaderNotes.get(j);
                head.append(j == 0 ? "" : ", ").append(VersionMap.Diff.q(n.substring(0, n.indexOf(':')))).append(": ");
                head.append(VersionMap.Diff.q(n.substring(n.indexOf(':') + 2)));
            }
            head.append("} },\n");
        }
        return body.substring(0, nl + 1) + head + body.substring(nl + 1);
    }
}

import java.io.*;
import java.util.*;

// ------------------------------------------------------------------ a chain of updates

/**
 * One map across several updates. Every class, method and field of the first release is followed
 * through each neighbouring release by its intermediary id, so A -> B -> C resolves straight to the
 * final name, a move follows the member to its new owner, and anything removed on the way is kept
 * with the update that removed it instead of disappearing. The history is in the first release's
 * names (what the source still says) with the name it had when it changed.
 */
final class MapChain {

    record Event(
        String kind,
        String owner,
        String name,
        String desc,
        String what,
        String was,
        String to,
        String step
    ) {}

    interface Loader {
        VersionMap.Table load(String version) throws Exception;
    }

    final VersionMap.Diff diff = new VersionMap.Diff();
    final List<Event> history = new ArrayList<>();
    final List<String> versions;

    MapChain(List<String> versions) {
        this.versions = versions;
    }

    static MapChain of(List<String> versions, Loader loader) throws Exception {
        if (versions.size() < 2) {
            throw new IOException("A chain needs at least two releases");
        }
        MapChain c = new MapChain(versions);
        VersionMap.Table first = loader.load(versions.get(0)), prev = first, last = first;
        Map<String, String> classes = new LinkedHashMap<>(), methods = new LinkedHashMap<>(), fields =
            new LinkedHashMap<>(); // original key -> current key
        Set<String> classGone = new HashSet<>(), methodGone = new HashSet<>(), fieldGone = new HashSet<>();
        boolean seamCrossed = false;
        Map<String, String> seamRemap = new HashMap<>(); // first-lane class names to last-lane ones
        // Forward bridge misses with a known official identity resolve at the tally against the
        // last version's own mappings (the intermediary drops overloads the jars still carry).
        Map<String, String> pendingMethods = new LinkedHashMap<>(), pendingFields = new LinkedHashMap<>(),
            pendingClasses = new LinkedHashMap<>(); // original key -> official identity at the seam
        for (String k : first.classes.keySet()) {
            classes.put(k, k);
        }
        for (var e : first.methods.entrySet()) {
            if (!VersionMap.Diff.noise(e.getValue().name())) {
                methods.put(e.getKey(), e.getKey());
            }
        }
        for (var e : first.fields.entrySet()) {
            if (!VersionMap.Diff.noise(e.getValue().name())) {
                fields.put(e.getKey(), e.getKey());
            }
        }
        for (int i = 1; i < versions.size(); i++) {
            VersionMap.Table cur = loader.load(versions.get(i));
            String step = prev.version + " -> " + cur.version;
            // Crossing the seam changes the key space, so tracked keys are translated through the
            // bridge first (recorded under the bridge step); the update's own changes then read as
            // usual. On the SRG lane a renamed class is linked back up by shared member ids, the
            // index's convention. A translation both renames and moves an element still reads as one
            // bridge event naming its endpoints; the update's own renames read under its own step.
            VersionMap.Bridge bridge = null;
            Map<String, String> links = Map.of(), curMembersByOfficial = Map.of(), curClassesByOfficial = Map.of();
            if (!cur.lane.equals(prev.lane)) {
                bridge = VersionMap.Bridge.at();
                seamCrossed = true;
                seamRemap.putAll(prev.lane.equals("srg") ? bridge.classes : bridge.classesBack);
                if (prev.lane.equals("srg")) {
                    curMembersByOfficial = indexMembersByOfficial(cur);
                    curClassesByOfficial = indexClassesByOfficial(cur);
                }
            } else if (cur.lane.equals("srg")) {
                links = linkSrgClasses(prev, cur);
            }
            boolean srgToInter = bridge != null && prev.lane.equals("srg");
            for (var e : classes.entrySet()) {
                if (classGone.contains(e.getKey())) {
                    continue;
                }
                String was = prev.classes.get(e.getValue()), next = e.getValue(), hop = step;
                if (bridge != null) {
                    hop = VersionMap.BRIDGE_STEP;
                    if (srgToInter) {
                        next = bridge.classes.get(next);
                        String official = next == null ? bridge.classesOfficial.get(e.getValue()) : null;
                        if (next == null) {
                            next = official == null ? null : curClassesByOfficial.get(official);
                        }
                        if (next == null && official != null) {
                            classGone.add(e.getKey());
                            pendingClasses.put(e.getKey(), official);
                            continue;
                        }
                    } else {
                        next = bridge.classesBack.get(next);
                        if (next == null) {
                            String official = prev.classes.get(e.getValue());
                            next = official == null ? null : bridge.classesBackOfficial.get(official);
                        }
                    }
                } else {
                    next = links.getOrDefault(next, next);
                }
                String now = next == null ? null : cur.classes.get(next);
                String orig = VersionMap.Diff.dots(first.classes.get(e.getKey()));
                if (now == null) {
                    classGone.add(e.getKey());
                    c.history.add(new Event("class", null, orig, null, "removed", VersionMap.Diff.dots(was), null, hop));
                    continue;
                }
                if (!next.equals(e.getValue())) {
                    e.setValue(next);
                }
                if (!now.equals(was)) {
                    c.history.add(
                        new Event("class", null, orig, null, "renamed", VersionMap.Diff.dots(was), VersionMap.Diff.dots(now), hop)
                    );
                }
            }
            for (var e : methods.entrySet()) {
                if (methodGone.contains(e.getKey())) {
                    continue;
                }
                VersionMap.Member o = first.methods.get(e.getKey()), mp = prev.methods.get(e.getValue());
                String was = VersionMap.Diff.dots(mp.owner()) + "." + mp.name(), hop = step;
                if (bridge != null) {
                    String next;
                    if (srgToInter) {
                        next = bridge.members.get(e.getValue());
                        String okey = next == null ? bridge.membersOfficial.get(e.getValue()) : null;
                        if (next == null && okey == null) okey = bridge.initForward(e.getValue(), mp.interDesc());
                        if (next == null) {
                            next = okey == null ? null : curMembersByOfficial.get(okey);
                        }
                        if (next == null && okey != null) {
                            methodGone.add(e.getKey());
                            pendingMethods.put(e.getKey(), okey);
                            continue;
                        }
                    } else {
                        next = bridge.membersBack.get(e.getValue());
                        if (next == null) {
                            VersionMap.Member pm = prev.methods.get(e.getValue());
                            String okey = pm.owner() + "\0" + pm.name() + "\0" + pm.desc();
                            next = bridge.membersBackOfficial.get(okey);
                            if (next == null) next = bridge.initBack(e.getValue(), pm.interDesc());
                        }
                    }
                    if (next == null) {
                        methodGone.add(e.getKey());
                        c.history.add(
                            new Event(
                                "method",
                                VersionMap.Diff.dots(o.owner()),
                                o.name(),
                                o.desc(),
                                "removed",
                                was,
                                null,
                                VersionMap.BRIDGE_STEP
                            )
                        );
                        continue;
                    }
                    e.setValue(next);
                    hop = VersionMap.BRIDGE_STEP;
                }
                if (!links.isEmpty()) {
                    String translated = followLink(links, cur.methods::containsKey, e.getValue());
                    if (!translated.equals(e.getValue())) {
                        e.setValue(translated);
                    }
                }
                VersionMap.Member mc = cur.methods.get(e.getValue());
                if (mc == null) {
                    String interName = e.getValue().substring(e.getValue().indexOf('.') + 1);
                    List<String> elsewhere = cur.methodsByInterName.getOrDefault(interName, List.of());
                    if (!elsewhere.isEmpty()) {
                        e.setValue(pickMoveTarget(cur, mp, elsewhere, links, ownerOf(e.getValue())));
                        mc = cur.methods.get(e.getValue());
                        c.history.add(
                            new Event(
                                "method",
                                VersionMap.Diff.dots(o.owner()),
                                o.name(),
                                o.desc(),
                                "moved",
                                was,
                                VersionMap.Diff.dots(mc.owner()) + "." + mc.name(),
                                step
                            )
                        );
                    } else {
                        methodGone.add(e.getKey());
                        c.history.add(
                            new Event(
                                "method",
                                VersionMap.Diff.dots(o.owner()),
                                o.name(),
                                o.desc(),
                                "removed",
                                was,
                                null,
                                step
                            )
                        );
                        continue;
                    }
                } else if (!mc.name().equals(mp.name())) {
                    c.history.add(
                        new Event(
                            "method",
                            VersionMap.Diff.dots(o.owner()),
                            o.name(),
                            o.desc(),
                            "renamed",
                            was,
                            VersionMap.Diff.dots(mc.owner()) + "." + mc.name(),
                            hop
                        )
                    );
                }
                // Descriptors compare across the seam in one lane: the previous side remapped through
                // the bridge's class translation, so only a true signature change reads as one.
                String mpDesc = bridge != null
                    ? VersionMap.Tiny.remapDesc(mp.interDesc(), srgToInter ? bridge.classes : bridge.classesBack)
                    : mp.interDesc();
                if (!mc.interDesc().equals(mpDesc)) {
                    c.history.add(
                        new Event(
                            "method",
                            VersionMap.Diff.dots(o.owner()),
                            o.name(),
                            o.desc(),
                            "signature",
                            was + mp.desc(),
                            VersionMap.Diff.dots(mc.owner()) + "." + mc.name() + mc.desc(),
                            hop
                        )
                    );
                }
            }
            for (var e : fields.entrySet()) {
                if (fieldGone.contains(e.getKey())) {
                    continue;
                }
                VersionMap.Member o = first.fields.get(e.getKey()), fp = prev.fields.get(e.getValue());
                String was = VersionMap.Diff.dots(fp.owner()) + "." + fp.name(), hop = step;
                if (bridge != null) {
                    String next;
                    if (srgToInter) {
                        next = bridge.members.get(e.getValue());
                        String okey = next == null ? bridge.membersOfficial.get(e.getValue()) : null;
                        if (next == null && okey == null) okey = bridge.initForward(e.getValue(), fp.interDesc());
                        if (next == null) {
                            next = okey == null ? null : curMembersByOfficial.get(okey);
                        }
                        if (next == null && okey != null) {
                            fieldGone.add(e.getKey());
                            pendingFields.put(e.getKey(), okey);
                            continue;
                        }
                    } else {
                        next = bridge.membersBack.get(e.getValue());
                        if (next == null) {
                            VersionMap.Member pm = prev.fields.get(e.getValue());
                            String okey = pm.owner() + "\0" + pm.name() + "\0";
                            next = bridge.membersBackOfficial.get(okey);
                            if (next == null) next = bridge.initBack(e.getValue(), pm.interDesc());
                        }
                    }
                    if (next == null) {
                        fieldGone.add(e.getKey());
                        c.history.add(
                            new Event("field", VersionMap.Diff.dots(o.owner()), o.name(), o.desc(), "removed", was, null, VersionMap.BRIDGE_STEP)
                        );
                        continue;
                    }
                    e.setValue(next);
                    hop = VersionMap.BRIDGE_STEP;
                }
                if (!links.isEmpty()) {
                    String translated = followLink(links, cur.fields::containsKey, e.getValue());
                    if (!translated.equals(e.getValue())) {
                        e.setValue(translated);
                    }
                }
                VersionMap.Member fc = cur.fields.get(e.getValue());
                if (fc == null) {
                    fieldGone.add(e.getKey());
                    c.history.add(
                        new Event("field", VersionMap.Diff.dots(o.owner()), o.name(), o.desc(), "removed", was, null, step)
                    );
                } else if (!fc.name().equals(fp.name())) {
                    c.history.add(
                        new Event(
                            "field",
                            VersionMap.Diff.dots(o.owner()),
                            o.name(),
                            o.desc(),
                            "renamed",
                            was,
                            VersionMap.Diff.dots(fc.owner()) + "." + fc.name(),
                            hop
                        )
                    );
                }
            }
            prev = cur;
            last = cur;
        }
        // The final map compares the first release with the last along the tracked keys. Something removed
        // on the way and back by the end (same intermediary id) compiles, so it is not reported as removed.
        VersionMap.Diff d = c.diff;
        for (var e : classes.entrySet()) {
            if (pendingClasses.containsKey(e.getKey())) {
                continue;
            }
            String a = first.classes.get(e.getKey()), b = last.classes.get(e.getValue());
            if (b == null) {
                d.classRemoved.add(a);
            } else if (!b.equals(a)) {
                d.classRenamed.add(new String[] { a, b });
            }
        }
        for (var e : methods.entrySet()) {
            if (pendingMethods.containsKey(e.getKey())) {
                continue;
            }
            VersionMap.Member ma = first.methods.get(e.getKey());
            boolean gone = methodGone.contains(e.getKey());
            VersionMap.Member mb;
            String key;
            if (gone) {
                mb = last.methods.get(e.getKey());
                key = e.getKey();
                if (mb == null) {
                    mb = last.methods.get(e.getValue());
                    key = e.getValue();
                }
            } else {
                mb = last.methods.get(e.getValue());
                key = e.getValue();
            }
            if (mb == null) {
                d.methodRemoved.add(new VersionMap.Change(ma.owner(), ma.name(), null, ma.desc(), null, null));
                continue;
            }
            if (!key.equals(e.getKey())) {
                d.methodMoved.add(new VersionMap.Change(ma.owner(), ma.name(), mb.name(), ma.desc(), mb.desc(), mb.owner()));
            } else if (!mb.name().equals(ma.name())) {
                d.methodRenamed.add(new VersionMap.Change(ma.owner(), ma.name(), mb.name(), ma.desc(), mb.desc(), mb.owner()));
            }
            // Across the seam the first descriptor is remapped through the bridge's class
            // translation before comparing, so only a true signature change reads as one.
            String maSpace = seamCrossed ? VersionMap.Tiny.remapDesc(ma.interDesc(), seamRemap) : ma.interDesc();
            if (!mb.interDesc().equals(maSpace)) {
                d.methodSignature.add(
                    new VersionMap.Change(ma.owner(), ma.name(), mb.name(), ma.desc(), mb.desc(), mb.owner())
                );
            }
        }
        for (var e : fields.entrySet()) {
            if (pendingFields.containsKey(e.getKey())) {
                continue;
            }
            VersionMap.Member fa = first.fields.get(e.getKey()), fb = last.fields.get(e.getValue());
            if (fb == null) {
                d.fieldRemoved.add(new VersionMap.Change(fa.owner(), fa.name(), null, fa.desc(), null, null));
            } else if (!fb.name().equals(fa.name())) {
                d.fieldRenamed.add(new VersionMap.Change(fa.owner(), fa.name(), fb.name(), fa.desc(), fb.desc(), fb.owner()));
            }
        }
        resolvePending(c, d, first, last, pendingMethods, pendingFields, pendingClasses);
        Comparator<VersionMap.Change> byOwner = Comparator.comparing(VersionMap.Change::owner).thenComparing(VersionMap.Change::from);
        for (var l : List.of(
            d.methodRenamed,
            d.methodMoved,
            d.methodSignature,
            d.methodRemoved,
            d.fieldRenamed,
            d.fieldRemoved
        )) {
            l.sort(byOwner);
        }
        d.classRenamed.sort(Comparator.comparing(x -> x[0]));
        Collections.sort(d.classRemoved);
        return c;
    }

    /**
     * Pair renamed classes on the SRG lane by shared member ids. The index measures the same
     * links; port follows them so a renamed class keeps its history.
     */
    static Map<String, String> linkSrgClasses(VersionMap.Table prev, VersionMap.Table cur) {
        Map<String, Set<String>> pIds = new HashMap<>(), cIds = new HashMap<>();
        for (var e : prev.methods.entrySet()) {
            if (!e.getValue().name().equals("<init>")) {
                pIds.computeIfAbsent(ownerOf(e.getKey()), x -> new HashSet<>()).add("m" + idOf(e.getKey()));
            }
        }
        for (var e : prev.fields.entrySet()) {
            pIds.computeIfAbsent(ownerOf(e.getKey()), x -> new HashSet<>()).add("f" + idOf(e.getKey()));
        }
        for (var e : cur.methods.entrySet()) {
            if (!e.getValue().name().equals("<init>")) {
                cIds.computeIfAbsent(ownerOf(e.getKey()), x -> new HashSet<>()).add("m" + idOf(e.getKey()));
            }
        }
        for (var e : cur.fields.entrySet()) {
            cIds.computeIfAbsent(ownerOf(e.getKey()), x -> new HashSet<>()).add("f" + idOf(e.getKey()));
        }
        Set<String> gone = new HashSet<>(prev.classes.keySet()), fresh = new HashSet<>(cur.classes.keySet());
        gone.removeAll(cur.classes.keySet());
        fresh.removeAll(prev.classes.keySet());
        return linkBySharedIds(gone, fresh, pIds, cIds);
    }

    /**
     * Mutual-best Jaccard matches need at least half shared. A class that grew or shrank around
     * the Flattening can fall below that despite one obvious successor, so an unclaimed pair
     * also links when it shares at least three stable member ids, two of them distinctive,
     * with twice every rival's distinctive count (or a strict win with five or more, since
     * abundant evidence needs less margin). Both directions must choose the same pair; a tie on
     * either side links nothing. Shared ids used by more than MAX_ID_USES classes carry no
     * weight: base-class and packet API ids repeat across whole hierarchies, while a rename
     * keeps its own members' ids. Jaccard ties break by distinctive-id count, then by shared
     * name tokens, and an unbreakable tie links nothing, so no link depends on iteration order.
     */
    static final int MAX_ID_USES = 12;

    static Map<String, String> linkBySharedIds(
        Collection<String> gone,
        Collection<String> fresh,
        Map<String, Set<String>> goneIds,
        Map<String, Set<String>> freshIds
    ) {
        Map<String, Integer> idUses = new HashMap<>();
        for (Map<String, Set<String>> ids : List.of(goneIds, freshIds)) {
            for (Set<String> s : ids.values()) {
                for (String k : s) {
                    if (stableSrgMember(k)) {
                        idUses.merge(k, 1, Integer::sum);
                    }
                }
            }
        }
        Map<String, String> best = new HashMap<>(), bestBack = new HashMap<>();
        Map<String, Double> score = new HashMap<>(), scoreBack = new HashMap<>();
        Map<String, Integer> bestRare = new HashMap<>(), bestBackRare = new HashMap<>();
        Map<String, Integer> bestName = new HashMap<>(), bestBackName = new HashMap<>();
        Set<String> deadJ = new HashSet<>(), deadJBack = new HashSet<>();
        Map<String, String> overlapBest = new HashMap<>(), overlapBack = new HashMap<>();
        Map<String, Integer> overlapCount = new HashMap<>(), overlapSecond = new HashMap<>();
        Map<String, Integer> overlapTotal = new HashMap<>();
        Map<String, Integer> overlapBackCount = new HashMap<>(), overlapBackSecond = new HashMap<>();
        Map<String, Integer> stableGone = stableSrgCounts(goneIds), stableFresh = stableSrgCounts(freshIds);
        for (String g : gone) {
            Set<String> gn = goneIds.getOrDefault(g, Set.of());
            if (gn.size() < 4) {
                continue;
            }
            for (String f : fresh) {
                Set<String> fn = freshIds.getOrDefault(f, Set.of());
                if (fn.size() < 4) {
                    continue;
                }
                int common = 0, sharedIds = 0, rareIds = 0;
                for (String n : gn) {
                    if (fn.contains(n)) {
                        common++;
                        if (stableSrgMember(n)) {
                            sharedIds++;
                            if (idUses.getOrDefault(n, 0) <= MAX_ID_USES) {
                                rareIds++;
                            }
                        }
                    }
                }
                double j = (double) common / (gn.size() + fn.size() - common);
                double sg = score.getOrDefault(g, 0.0);
                int sr = bestRare.getOrDefault(g, -1);
                if (j > sg) {
                    score.put(g, j);
                    best.put(g, f);
                    bestRare.put(g, rareIds);
                    bestName.put(g, nameSim(g, f));
                    deadJ.remove(g);
                } else if (j > 0.0 && j == sg) {
                    int ns = nameSim(g, f);
                    int bns = bestName.getOrDefault(g, -1);
                    if (rareIds > sr || (rareIds == sr && ns > bns)) {
                        score.put(g, j);
                        best.put(g, f);
                        bestRare.put(g, rareIds);
                        bestName.put(g, ns);
                        deadJ.remove(g);
                    } else if (rareIds == sr && ns == bns) {
                        deadJ.add(g);
                    }
                }
                double sf = scoreBack.getOrDefault(f, 0.0);
                int sbr = bestBackRare.getOrDefault(f, -1);
                if (j > sf) {
                    scoreBack.put(f, j);
                    bestBack.put(f, g);
                    bestBackRare.put(f, rareIds);
                    bestBackName.put(f, nameSim(g, f));
                    deadJBack.remove(f);
                } else if (j > 0.0 && j == sf) {
                    int sbn = nameSim(g, f);
                    int bbn = bestBackName.getOrDefault(f, -1);
                    if (rareIds > sbr || (rareIds == sbr && sbn > bbn)) {
                        scoreBack.put(f, j);
                        bestBack.put(f, g);
                        bestBackRare.put(f, rareIds);
                        bestBackName.put(f, sbn);
                        deadJBack.remove(f);
                    } else if (rareIds == sbr && sbn == bbn) {
                        deadJBack.add(f);
                    }
                }
                if (stableGone.getOrDefault(g, 0) >= 4 && stableFresh.getOrDefault(f, 0) >= 4) {
                    if (rareIds > overlapCount.getOrDefault(g, 0)) {
                        overlapSecond.put(g, overlapCount.getOrDefault(g, 0));
                        overlapCount.put(g, rareIds);
                        overlapTotal.put(g, sharedIds);
                        overlapBest.put(g, f);
                    } else if (rareIds > overlapSecond.getOrDefault(g, 0)) {
                        overlapSecond.put(g, rareIds);
                    }
                    if (rareIds > overlapBackCount.getOrDefault(f, 0)) {
                        overlapBackSecond.put(f, overlapBackCount.getOrDefault(f, 0));
                        overlapBackCount.put(f, rareIds);
                        overlapBack.put(f, g);
                    } else if (rareIds > overlapBackSecond.getOrDefault(f, 0)) {
                        overlapBackSecond.put(f, rareIds);
                    }
                }
            }
        }
        Map<String, String> links = new HashMap<>();
        Set<String> claimed = new HashSet<>();
        for (var e : best.entrySet()) {
            if (score.get(e.getKey()) >= 0.5 && e.getKey().equals(bestBack.get(e.getValue()))
                && !deadJ.contains(e.getKey()) && !deadJBack.contains(e.getValue())) {
                links.put(e.getKey(), e.getValue());
                claimed.add(e.getValue());
            }
        }
        for (var e : overlapBest.entrySet()) {
            String g = e.getKey(), f = e.getValue();
            int n = overlapCount.getOrDefault(e.getKey(), 0), second = overlapSecond.getOrDefault(e.getKey(), 0);
            int t = overlapTotal.getOrDefault(e.getKey(), 0);
            boolean clearWinner = t >= 3 && n >= 2
                && (second == 0 || n >= 2 * second || (n >= 5 && n > second));
            int backSecond = overlapBackSecond.getOrDefault(f, 0);
            boolean reverseClear = overlapBackCount.getOrDefault(f, 0) > backSecond;
            if (!links.containsKey(g) && !claimed.contains(f) && clearWinner && reverseClear && g.equals(overlapBack.get(f))) {
                links.put(g, f);
                claimed.add(f);
            }
        }
        return links;
    }

    static boolean stableSrgMember(String key) {
        return key.startsWith("mfunc_") || key.startsWith("ffield_");
    }

    /** Shared CamelCase tokens of two classes' simple names, plural-insensitive. */
    static int nameSim(String g, String f) {
        Set<String> gt = tokens(g), ft = tokens(f);
        int common = 0;
        for (String t : gt) {
            if (ft.contains(t)) {
                common++;
            }
        }
        return common;
    }

    static Set<String> tokens(String internal) {
        String simple = internal.substring(internal.lastIndexOf('/') + 1).replace('$', ' ');
        Set<String> out = new HashSet<>();
        for (String part : simple.split("[^A-Za-z0-9]+")) {
            for (String w : part.split("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|(?<=[0-9])(?=[A-Za-z])|(?<=[A-Za-z])(?=[0-9])")) {
                String t = w.toLowerCase(Locale.ROOT);
                if (t.endsWith("s") && t.length() > 3 && !t.endsWith("ss")) {
                    t = t.substring(0, t.length() - 1);
                }
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    static Map<String, Integer> stableSrgCounts(Map<String, Set<String>> ids) {
        Map<String, Integer> counts = new HashMap<>();
        for (var e : ids.entrySet()) {
            int count = 0;
            for (String key : e.getValue()) {
                if (stableSrgMember(key)) {
                    count++;
                }
            }
            counts.put(e.getKey(), count);
        }
        return counts;
    }

    static String ownerOf(String key) {
        return key.substring(0, key.indexOf('.'));
    }

    static String idOf(String key) {
        return key.substring(key.indexOf('.') + 1);
    }

    /**
     * Follow a class rename before looking a member up: the member kept its id, so the linked
     * owner names its key in the next table (descriptors remapped through the step's links).
     * Falls back to the tracked key.
     */
    static String followLink(Map<String, String> links, java.util.function.Predicate<String> present, String key) {
        String owner = ownerOf(key);
        String linked = links.get(owner);
        if (linked == null) {
            return key;
        }
        String id = idOf(key);
        String translated = linked + "." + id;
        if (present.test(translated)) {
            return translated;
        }
        int paren = id.indexOf('(');
        if (paren < 0) {
            return key;
        }
        String remapped = linked + "." + id.substring(0, paren) + VersionMap.Tiny.remapDesc(id.substring(paren), links);
        return present.test(remapped) ? remapped : key;
    }

    /**
     * Pick a move target among same-id members. SRG ids are shared across an override
     * hierarchy, so an id alone can name a dozen methods: prefer the candidate that keeps the
     * member's name, then its descriptor, then one owned by the linked class. Intermediary ids
     * are unique, so modern steps never reach past the first candidate.
     */
    static String pickMoveTarget(VersionMap.Table cur, VersionMap.Member mp, List<String> elsewhere, Map<String, String> links, String oldOwner) {
        if (elsewhere.size() == 1) {
            return elsewhere.get(0);
        }
        for (String k : elsewhere) {
            if (cur.methods.get(k).name().equals(mp.name())) {
                return k;
            }
        }
        for (String k : elsewhere) {
            if (cur.methods.get(k).interDesc().equals(mp.interDesc())) {
                return k;
            }
        }
        String linked = links.get(oldOwner);
        if (linked != null) {
            for (String k : elsewhere) {
                if (ownerOf(k).equals(linked)) {
                    return k;
                }
            }
        }
        return elsewhere.get(0);
    }

    /**
     * The adjacent table's members by official identity, for the bridge's name fallback. Fields
     * carry no descriptor on the SRG lane, so both sides key fields by owner and name alone.
     */
    static Map<String, String> indexMembersByOfficial(VersionMap.Table cur) {
        Map<String, String> index = new HashMap<>();
        for (var e : cur.methods.entrySet()) {
            index.putIfAbsent(e.getValue().owner() + "\0" + e.getValue().name() + "\0" + e.getValue().desc(), e.getKey());
        }
        for (var e : cur.fields.entrySet()) {
            index.putIfAbsent(e.getValue().owner() + "\0" + e.getValue().name() + "\0", e.getKey());
        }
        return index;
    }

    static Map<String, String> indexClassesByOfficial(VersionMap.Table cur) {
        Map<String, String> index = new HashMap<>();
        for (var e : cur.classes.entrySet()) {
            index.putIfAbsent(e.getValue(), e.getKey());
        }
        return index;
    }

    /**
     * Bridge-pending members and classes resolve against the last version's own mappings: an
     * official identity unchanged since the seam maps straight to the final name. Anything still
     * unmapped reads as removed, with the bridge step. Only forward routes pend; a backport
     * resolves eagerly at the bridge, when the SRG table is in hand.
     */
    static void resolvePending(
        MapChain c,
        VersionMap.Diff d,
        VersionMap.Table first,
        VersionMap.Table last,
        Map<String, String> pendingMethods,
        Map<String, String> pendingFields,
        Map<String, String> pendingClasses
    ) throws Exception {
        if (pendingMethods.isEmpty() && pendingFields.isEmpty() && pendingClasses.isEmpty()) {
            return;
        }
        Set<String> lastMembers = Set.of(), lastClasses = Set.of();
        if (!last.lane.equals("srg")) {
            VersionMap.Proguard[] pgs = VersionMap.proguardsFor(last.version);
            lastMembers = VersionMap.officialTriples(pgs);
            lastClasses = VersionMap.officialClasses(pgs);
        }
        for (var e : pendingMethods.entrySet()) {
            VersionMap.Member ma = first.methods.get(e.getKey());
            String[] id = e.getValue().split("\0", -1);
            String was = VersionMap.Diff.dots(ma.owner()) + "." + ma.name();
            if (lastMembers.contains(e.getValue())) {
                String to = VersionMap.Diff.dots(id[0]) + "." + id[1];
                String what = id[0].equals(ma.owner()) ? "renamed" : "moved";
                if (what.equals("moved")) {
                    d.methodMoved.add(new VersionMap.Change(ma.owner(), ma.name(), id[1], ma.desc(), id[2], id[0]));
                } else {
                    d.methodRenamed.add(new VersionMap.Change(ma.owner(), ma.name(), id[1], ma.desc(), id[2], id[0]));
                }
                c.history.add(
                    new Event("method", VersionMap.Diff.dots(ma.owner()), ma.name(), ma.desc(), what, was, to, VersionMap.BRIDGE_STEP)
                );
            } else {
                d.methodRemoved.add(new VersionMap.Change(ma.owner(), ma.name(), null, ma.desc(), null, null));
                c.history.add(
                    new Event("method", VersionMap.Diff.dots(ma.owner()), ma.name(), ma.desc(), "removed", was, null, VersionMap.BRIDGE_STEP)
                );
            }
        }
        for (var e : pendingFields.entrySet()) {
            VersionMap.Member fa = first.fields.get(e.getKey());
            String[] id = e.getValue().split("\0", -1);
            String was = VersionMap.Diff.dots(fa.owner()) + "." + fa.name();
            if (lastMembers.contains(e.getValue())) {
                d.fieldRenamed.add(new VersionMap.Change(fa.owner(), fa.name(), id[1], fa.desc(), id[2], id[0]));
                c.history.add(
                    new Event(
                        "field",
                        VersionMap.Diff.dots(fa.owner()),
                        fa.name(),
                        fa.desc(),
                        "renamed",
                        was,
                        VersionMap.Diff.dots(id[0]) + "." + id[1],
                        VersionMap.BRIDGE_STEP
                    )
                );
            } else {
                d.fieldRemoved.add(new VersionMap.Change(fa.owner(), fa.name(), null, fa.desc(), null, null));
                c.history.add(
                    new Event("field", VersionMap.Diff.dots(fa.owner()), fa.name(), fa.desc(), "removed", was, null, VersionMap.BRIDGE_STEP)
                );
            }
        }
        for (var e : pendingClasses.entrySet()) {
            String a = first.classes.get(e.getKey());
            if (lastClasses.contains(e.getValue())) {
                d.classRenamed.add(new String[] {a, e.getValue()});
                c.history.add(
                    new Event("class", null, VersionMap.Diff.dots(a), null, "renamed", VersionMap.Diff.dots(a), VersionMap.Diff.dots(e.getValue()), VersionMap.BRIDGE_STEP)
                );
            } else {
                d.classRemoved.add(a);
                c.history.add(
                    new Event("class", null, VersionMap.Diff.dots(a), null, "removed", VersionMap.Diff.dots(a), null, VersionMap.BRIDGE_STEP)
                );
            }
        }
    }

    /** The usual map sections, so explain/fix/preflight read it unchanged, plus the chain and its history. */
    String json(String names) {
        String body = diff.json(versions.get(0), versions.get(versions.size() - 1), names);
        StringBuilder sb = new StringBuilder(body.substring(0, body.lastIndexOf("\n}")));
        sb.append(",\n  \"chain\": [");
        for (int i = 0; i < versions.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(VersionMap.Diff.q(versions.get(i)));
        }
        sb.append("],\n  \"history\": [");
        for (int i = 0; i < history.size(); i++) {
            Event e = history.get(i);
            sb
                .append(i == 0 ? "\n" : ",\n")
                .append("    { \"kind\": ")
                .append(VersionMap.Diff.q(e.kind()))
                .append(", \"owner\": ")
                .append(VersionMap.Diff.q(e.owner()))
                .append(", \"name\": ")
                .append(VersionMap.Diff.q(e.name()))
                .append(", \"desc\": ")
                .append(VersionMap.Diff.q(e.desc()))
                .append(", \"what\": ")
                .append(VersionMap.Diff.q(e.what()))
                .append(", \"was\": ")
                .append(VersionMap.Diff.q(e.was()))
                .append(", \"to\": ")
                .append(VersionMap.Diff.q(e.to()))
                .append(", \"step\": ")
                .append(VersionMap.Diff.q(e.step()))
                .append(" }");
        }
        return sb.append("\n  ]\n}\n").toString();
    }
}

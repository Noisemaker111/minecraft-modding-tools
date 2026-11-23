import java.lang.constant.*;
import java.util.*;

/** Which class of the new release each class of the old one became, where no published name says so: by nesting, by signature and by likeness. */
final class HopClasses {

    final GameHop hop;

    HopClasses(GameHop hop) {
        this.hop = hop;
    }

    final Map<GameDiff.Cls, GameDiff.Cls> outerVote = new LinkedHashMap<>();

    // -------------------------------------------------------------- classes

    /** A nested class follows its outer class: Outer$Inner -> NewOuter$Inner; and the reverse vote. */
    void nested() {
        for (GameDiff.Cls ca : hop.a.classes.values()) {
            if (hop.cAB.containsKey(ca)) {
                continue;
            }
            int d = ca.name.lastIndexOf('$');
            if (d < 0) {
                continue;
            }
            GameDiff.Cls oa = hop.a.classes.get(ca.name.substring(0, d));
            GameDiff.Cls ob = oa == null ? null : hop.cAB.get(oa);
            if (ob == null) {
                continue;
            }
            GameDiff.Cls cb = hop.b.classes.get(ob.name + ca.name.substring(d));
            if (cb != null && !hop.cBA.containsKey(cb) && cb.kind() == ca.kind()) {
                hop.link(ca, cb, "nested");
            }
        }
        // Nested classes that moved together vote for their outer classes; the vote is evidence for fuzzyClasses, not a link.
        outerVote.clear();
        Map<GameDiff.Cls, Set<GameDiff.Cls>> votes = new LinkedHashMap<>();
        for (var e : hop.cAB.entrySet()) {
            String o = e.getKey().name;
            String n = e.getValue().name;
            int io = o.lastIndexOf('$');
            int in = n.lastIndexOf('$');
            if (io < 0 || in < 0 || !o.substring(io).equals(n.substring(in))) {
                continue;
            }
            GameDiff.Cls oa = hop.a.classes.get(o.substring(0, io));
            GameDiff.Cls ob = hop.b.classes.get(n.substring(0, in));
            if (oa == null || ob == null || hop.cAB.containsKey(oa) || hop.cBA.containsKey(ob)) {
                continue;
            }
            votes.computeIfAbsent(oa, x -> new HashSet<>()).add(ob);
        }
        Map<GameDiff.Cls, Integer> claimed = new LinkedHashMap<>();
        for (Set<GameDiff.Cls> v : votes.values()) {
            if (v.size() == 1) {
                claimed.merge(v.iterator().next(), 1, Integer::sum);
            }
        }
        for (var e : votes.entrySet()) {
            if (e.getValue().size() != 1) {
                continue;
            }
            GameDiff.Cls ob = e.getValue().iterator().next();
            if (claimed.get(ob) == 1 && ob.kind() == e.getKey().kind()) {
                outerVote.put(e.getKey(), ob);
            }
        }
    }

    static Set<String> memberNames(GameDiff.Cls c) {
        Set<String> s = new HashSet<>();
        for (GameDiff.Meth m : c.methods.values()) {
            if (!m.special()) {
                s.add("m" + m.name);
            }
        }
        for (GameDiff.Fld f : c.fields.values()) {
            s.add("f" + f.name);
        }
        return s;
    }

    static Set<Long> bodies(GameDiff.Cls c) {
        Set<Long> s = new HashSet<>();
        for (GameDiff.Meth m : c.methods.values()) {
            if (!m.special() && m.distinctive()) {
                s.add(m.hash);
            }
        }
        return s;
    }

    record Score(double s, int evidence) {}

    void fuzzyClasses() {
        List<GameDiff.Cls> ga = new ArrayList<>();
        List<GameDiff.Cls> gb = new ArrayList<>();
        for (GameDiff.Cls c : hop.a.classes.values()) {
            if (!hop.cAB.containsKey(c)) {
                ga.add(c);
            }
        }
        for (GameDiff.Cls c : hop.b.classes.values()) {
            if (!hop.cBA.containsKey(c)) {
                gb.add(c);
            }
        }
        if (ga.isEmpty() || gb.isEmpty()) {
            return;
        }
        Map<GameDiff.Cls, Set<String>> names = new LinkedHashMap<>();
        Map<GameDiff.Cls, Set<Long>> hashes = new LinkedHashMap<>();
        Map<String, Integer> df = new HashMap<>();
        for (GameDiff.Cls c : ga) {
            names.put(c, memberNames(c));
            hashes.put(c, bodies(c));
        }
        for (GameDiff.Cls c : gb) {
            // A class whose body moved up into a new abstract parent (Slime -> AbstractCubeMob + Slime)
            // still offers those members through the parent; that keeps Slime -> Slime the best match.
            Set<String> n = memberNames(c);
            Set<Long> h = bodies(c);
            for (GameDiff.Cls p : hop.hopMembers.newSupers(c)) {
                n.addAll(memberNames(p));
                h.addAll(bodies(p));
            }
            names.put(c, n);
            hashes.put(c, h);
        }
        for (GameDiff.Cls c : names.keySet()) {
            for (String n : names.get(c)) {
                df.merge(n, 1, Integer::sum);
            }
        }
        double total = names.size();
        Map<String, Double> w = new HashMap<>();
        for (var e : df.entrySet()) {
            w.put(e.getKey(), Math.log(1 + total / e.getValue()));
        }
        Map<String, List<GameDiff.Cls>> index = new HashMap<>();
        for (GameDiff.Cls c : gb) {
            for (String n : names.get(c)) {
                if (df.get(n) <= 40) {
                    index.computeIfAbsent(n, x -> new ArrayList<>()).add(c);
                }
            }
            for (Long h : hashes.get(c)) {
                index.computeIfAbsent("#" + h, x -> new ArrayList<>()).add(c);
            }
            index.computeIfAbsent("@" + c.simple(), x -> new ArrayList<>()).add(c);
        }
        Map<GameDiff.Cls, Map<GameDiff.Cls, Score>> scores = new LinkedHashMap<>();
        for (GameDiff.Cls ca : ga) {
            Set<GameDiff.Cls> cand = new LinkedHashSet<>();
            for (String n : names.get(ca)) {
                cand.addAll(index.getOrDefault(n, List.of()));
            }
            for (Long h : hashes.get(ca)) {
                cand.addAll(index.getOrDefault("#" + h, List.of()));
            }
            if (outerVote.containsKey(ca)) {
                cand.add(outerVote.get(ca));
            }
            cand.addAll(index.getOrDefault("@" + ca.simple(), List.of()));
            Map<GameDiff.Cls, Score> row = new LinkedHashMap<>();
            for (GameDiff.Cls cb : cand) {
                if (cb.loader != ca.loader || cb.kind() != ca.kind()) {
                    continue;
                }
                Score sc = score(ca, cb, names, hashes, w);
                if (sc != null) {
                    row.put(cb, sc);
                }
            }
            scores.put(ca, row);
        }
        Map<GameDiff.Cls, double[]> bestB = new LinkedHashMap<>(); // b -> best, second
        Map<GameDiff.Cls, GameDiff.Cls> bestBA = new LinkedHashMap<>();
        for (var e : scores.entrySet()) {
            for (var f : e.getValue().entrySet()) {
                double s = f.getValue().s();
                double[] bb = bestB.computeIfAbsent(f.getKey(), x -> new double[] { -1, -1 });
                if (s > bb[0]) {
                    bb[1] = bb[0];
                    bb[0] = s;
                    bestBA.put(f.getKey(), e.getKey());
                } else if (s > bb[1]) {
                    bb[1] = s;
                }
            }
        }
        for (var e : scores.entrySet()) {
            GameDiff.Cls ca = e.getKey();
            GameDiff.Cls best = null;
            double s1 = -1;
            double s2 = -1;
            for (var f : e.getValue().entrySet()) {
                double s = f.getValue().s();
                if (s > s1) {
                    s2 = s1;
                    s1 = s;
                    best = f.getKey();
                } else if (s > s2) {
                    s2 = s;
                }
            }
            if (ca.name.equals(GameDiff.WHY)) {
                System.out.println("    why " + ca.name + ":");
                for (var f : e.getValue().entrySet()) {
                    GameDiff.Cls back = bestBA.get(f.getKey());
                    System.out.printf(
                        "      %.3f evidence %d %s (its best: %s)%n",
                        f.getValue().s(),
                        f.getValue().evidence(),
                        f.getKey().name,
                        back == null ? null : back.name
                    );
                }
            }
            if (best == null || bestBA.get(best) != ca) {
                continue;
            }
            Score sc = e.getValue().get(best);
            double[] bb = bestB.get(best);
            if (sc.s() < 0.5 || s1 - s2 < 0.1 || bb[0] - bb[1] < 0.1) {
                continue;
            }
            boolean sameSimple = ca.simple().equals(best.simple());
            if (sc.evidence() < 3 && !(sameSimple && sc.evidence() >= 1)) {
                continue;
            }
            // A class of the same simple name is still there: the content moved, the class did not.
            boolean namesake = false;
            for (var f : e.getValue().entrySet()) {
                if (!sameSimple && f.getKey().simple().equals(ca.simple()) && f.getValue().s() >= 0.15) {
                    namesake = true;
                }
            }
            if (namesake) {
                continue;
            }
            hop.link(ca, best, "fuzzy");
        }
        // Same simple name, the only one on each side, and nothing to tell them apart (empty classes).
        Map<String, List<GameDiff.Cls>> sa = new HashMap<>();
        Map<String, List<GameDiff.Cls>> sb = new HashMap<>();
        for (GameDiff.Cls c : ga) {
            if (!hop.cAB.containsKey(c)) {
                sa.computeIfAbsent(c.simple(), x -> new ArrayList<>()).add(c);
            }
        }
        for (GameDiff.Cls c : gb) {
            if (!hop.cBA.containsKey(c)) {
                sb.computeIfAbsent(c.simple(), x -> new ArrayList<>()).add(c);
            }
        }
        for (var e : sa.entrySet()) {
            List<GameDiff.Cls> l = sb.get(e.getKey());
            if (e.getValue().size() != 1 || l == null || l.size() != 1) {
                continue;
            }
            GameDiff.Cls ca = e.getValue().get(0);
            GameDiff.Cls cb = l.get(0);
            if (ca.loader != cb.loader || ca.kind() != cb.kind()) {
                continue;
            }
            Score sc = score(ca, cb, names, hashes, w);
            boolean empty = names.get(ca).size() + names.get(cb).size() <= 2;
            if (sc != null && (sc.s() >= 0.2 || (empty && Objects.equals(ca.sup, hop.clsBA(cb.sup == null ? "" : cb.sup))))) {
                hop.link(ca, cb, "simple-name");
            }
        }
    }

    /**
     * A class follows the type the game's own signatures switched to in its place: each linked method's parameters
     * and result and each linked field's type, place by place. 26.3 split GpuTexture, GpuDevice and the other device
     * classes into an API interface (what every caller's signature names) and a backend base class (what holds the
     * old bodies); by body likeness alone the class went to the base class, and every mod call on it broke.
     * Kept with three votes and 80% agreement for a type no other old class is voted to; it takes over a link made by
     * likeness, never one made by name or published by the loader.
     */
    void signatureClasses() {
        Map<GameDiff.Cls, Map<GameDiff.Cls, Integer>> votes = new LinkedHashMap<>();
        for (var e : hop.mAB.entrySet()) {
            MethodTypeDesc da = MethodTypeDesc.ofDescriptor(e.getKey().desc);
            MethodTypeDesc db = MethodTypeDesc.ofDescriptor(e.getValue().desc);
            if (da.parameterCount() == db.parameterCount()) {
                for (int i = 0; i < da.parameterCount(); i++) {
                    signatureVote(votes, da.parameterType(i).descriptorString(), db.parameterType(i).descriptorString());
                }
            }
            signatureVote(votes, da.returnType().descriptorString(), db.returnType().descriptorString());
        }
        for (var e : hop.fAB.entrySet()) {
            signatureVote(votes, e.getKey().desc, e.getValue().desc);
        }
        Map<GameDiff.Cls, GameDiff.Cls> picks = new LinkedHashMap<>();
        Map<GameDiff.Cls, Integer> claimed = new HashMap<>();
        for (var e : votes.entrySet()) {
            GameDiff.Cls best = null;
            int top = 0;
            int of = 0;
            for (var v : e.getValue().entrySet()) {
                of += v.getValue();
                if (v.getValue() > top || v.getValue() == top && best != null && v.getKey().name.compareTo(best.name) < 0) {
                    top = v.getValue();
                    best = v.getKey();
                }
            }
            if (top >= 3 && top * 10 >= of * 8) {
                picks.put(e.getKey(), best);
                claimed.merge(best, 1, Integer::sum);
            }
        }
        for (var e : picks.entrySet()) {
            GameDiff.Cls ca = e.getKey();
            GameDiff.Cls best = e.getValue();
            GameDiff.Cls had = hop.cAB.get(ca);
            // A type several old classes now go through is what they share (1.21.2's AbstractCubeMob for Slime and
            // MagmaCube), not where any one of them went.
            if (best == had || hop.cBA.containsKey(best) || claimed.get(best) > 1) {
                continue;
            }
            if (had != null) {
                // Signatures widened to a parent keep voting for it (1.21's UniformInt to IntProvider): a link made by
                // likeness is taken over only by the class that kept the old one's name, as fuzzyClasses' namesake rule.
                String why = hop.how.get(ca);
                boolean likeness = "fuzzy".equals(why) || "simple-name".equals(why);
                if (!likeness || !best.simple().equals(ca.simple()) || had.simple().equals(ca.simple())) {
                    continue;
                }
                unlink(ca);
            }
            hop.link(ca, best, "signatures");
        }
    }

    /** a's type at one place of a linked member and b's there: a vote when b's is not a's own link already. */
    void signatureVote(Map<GameDiff.Cls, Map<GameDiff.Cls, Integer>> votes, String had, String got) {
        while (had.startsWith("[") && got.startsWith("[")) {
            had = had.substring(1);
            got = got.substring(1);
        }
        if (!had.startsWith("L") || !got.startsWith("L")) {
            return;
        }
        GameDiff.Cls ca = hop.a.classes.get(had.substring(1, had.length() - 1));
        GameDiff.Cls cb = hop.b.classes.get(got.substring(1, got.length() - 1));
        if (ca == null || cb == null || GameDiff.anonymous(ca.name) || ca.loader != cb.loader || !kindFollows(ca, cb)) {
            return;
        }
        GameDiff.Cls linked = hop.cAB.get(ca);
        if (linked == null && hop.cBA.containsKey(cb)) {
            return;
        }
        votes.computeIfAbsent(ca, k -> new HashMap<>()).merge(cb, 1, Integer::sum);
    }

    /** The same kind of class, or a class the release made an interface of (26.3's GpuTexture). */
    static boolean kindFollows(GameDiff.Cls ca, GameDiff.Cls cb) {
        return ca.kind() == cb.kind() || ca.kind() == 0 && cb.kind() == GameDiff.ACC_INTERFACE;
    }

    /** Takes back a class link and the links of its members, so a better class can take them. */
    void unlink(GameDiff.Cls ca) {
        GameDiff.Cls cb = hop.cAB.remove(ca);
        hop.cBA.remove(cb);
        hop.how.remove(ca);
        for (GameDiff.Meth ma : ca.methods.values()) {
            GameDiff.Meth mb = hop.mAB.remove(ma);
            if (mb != null) {
                hop.mBA.remove(mb);
                hop.how.remove(ma);
            }
        }
        for (GameDiff.Fld fa : ca.fields.values()) {
            GameDiff.Fld fb = hop.fAB.remove(fa);
            if (fb != null) {
                hop.fBA.remove(fb);
                hop.how.remove(fa);
            }
        }
    }

    Score score(GameDiff.Cls ca, GameDiff.Cls cb, Map<GameDiff.Cls, Set<String>> names, Map<GameDiff.Cls, Set<Long>> hashes, Map<String, Double> w) {
        Set<String> na = names.get(ca);
        Set<String> nb = names.get(cb);
        double inter = 0;
        double union = 0;
        int common = 0;
        for (String n : na) {
            double x = w.getOrDefault(n, 1.0);
            union += x;
            if (nb.contains(n)) {
                inter += x;
                common++;
            }
        }
        for (String n : nb) {
            if (!na.contains(n)) {
                union += w.getOrDefault(n, 1.0);
            }
        }
        double nameJ = union == 0 ? 0 : inter / union;
        Set<Long> ha = hashes.get(ca);
        Set<Long> hb = hashes.get(cb);
        int hc = 0;
        for (Long h : ha) {
            if (hb.contains(h)) {
                hc++;
            }
        }
        int hu = ha.size() + hb.size() - hc;
        double hashJ = hu == 0 ? 0 : (double) hc / hu;
        double s = 0.75 * Math.max(nameJ, hashJ) + 0.25 * Math.min(nameJ, hashJ);
        String supB = cb.sup == null ? null : hop.clsBA(cb.sup);
        boolean gameSupA = ca.sup != null && hop.a.classes.containsKey(ca.sup);
        boolean gameSupB = cb.sup != null && hop.b.classes.containsKey(cb.sup);
        if (gameSupA && Objects.equals(ca.sup, supB)) {
            s += 0.1;
        } else if (gameSupA && gameSupB) {
            s -= 0.2;
        }
        if (ca.simple().equals(cb.simple())) {
            s += 0.15;
        }
        int evidence = common + hc;
        if (outerVote.get(ca) == cb) {
            s += 0.25;
            evidence += 2;
        }
        return new Score(s, evidence);
    }

    /** An override follows the method it overrides (and the reverse): bytecode overrides share name and descriptor. */
    void hierarchy() {
        for (var e : hop.cAB.entrySet()) {
            GameDiff.Cls ca = e.getKey();
            GameDiff.Cls cb = e.getValue();
            for (GameDiff.Meth ma : ca.methods.values()) {
                if (hop.mAB.containsKey(ma) || ma.special() || ma.isStatic() || (ma.flags & GameDiff.ACC_PRIVATE) != 0) {
                    continue;
                }
                Set<GameDiff.Meth> candidates = new LinkedHashSet<>();
                boolean conflict = false;
                for (GameDiff.Meth rel : related(ca, ma.name + ma.desc)) {
                    GameDiff.Meth rb = hop.mAB.get(rel);
                    if (rb == null) {
                        continue;
                    }
                    GameDiff.Meth cand = cb.methods.get(rb.name + rb.desc);
                    if (cand == null || hop.mBA.containsKey(cand)) {
                        conflict = true;
                        continue;
                    }
                    candidates.add(cand);
                }
                if (candidates.size() == 1 && !conflict) {
                    hop.link(ma, candidates.iterator().next(), "override");
                }
            }
        }
    }

    /** Methods with the same name and descriptor in supertypes and subtypes of c (in a). */
    List<GameDiff.Meth> related(GameDiff.Cls c, String key) {
        List<GameDiff.Meth> out = new ArrayList<>();
        Set<GameDiff.Cls> seen = new LinkedHashSet<>();
        ArrayDeque<GameDiff.Cls> up = new ArrayDeque<>();
        for (String s : GameDiff.supertypes(c)) {
            GameDiff.Cls p = hop.a.classes.get(s);
            if (p != null) {
                up.add(p);
            }
        }
        while (!up.isEmpty()) {
            GameDiff.Cls p = up.poll();
            if (!seen.add(p)) {
                continue;
            }
            GameDiff.Meth m = p.methods.get(key);
            if (m != null) {
                out.add(m);
            }
            for (String s : GameDiff.supertypes(p)) {
                GameDiff.Cls q = hop.a.classes.get(s);
                if (q != null) {
                    up.add(q);
                }
            }
        }
        ArrayDeque<GameDiff.Cls> down = new ArrayDeque<>(c.children);
        while (!down.isEmpty()) {
            GameDiff.Cls p = down.poll();
            if (!seen.add(p)) {
                continue;
            }
            GameDiff.Meth m = p.methods.get(key);
            if (m != null) {
                out.add(m);
            }
            down.addAll(p.children);
        }
        return out;
    }

    /** An identical non-trivial body found exactly once among unlinked methods on each side. */
    void moves() {
        Map<Long, List<GameDiff.Meth>> ha = new HashMap<>();
        Map<Long, List<GameDiff.Meth>> hb = new HashMap<>();
        for (GameDiff.Cls c : hop.a.classes.values()) {
            for (GameDiff.Meth m : c.methods.values()) {
                if (!hop.mAB.containsKey(m) && !m.special() && m.distinctive()) {
                    ha.computeIfAbsent(m.hash, x -> new ArrayList<>()).add(m);
                }
            }
        }
        for (GameDiff.Cls c : hop.b.classes.values()) {
            for (GameDiff.Meth m : c.methods.values()) {
                if (!hop.mBA.containsKey(m) && !m.special() && m.distinctive()) {
                    hb.computeIfAbsent(m.hash, x -> new ArrayList<>()).add(m);
                }
            }
        }
        for (var e : ha.entrySet()) {
            List<GameDiff.Meth> l = hb.get(e.getKey());
            if (e.getValue().size() != 1 || l == null || l.size() != 1) {
                continue;
            }
            GameDiff.Meth ma = e.getValue().get(0);
            GameDiff.Meth mb = l.get(0);
            if (ma.owner.loader != mb.owner.loader) {
                continue;
            }
            hop.link(ma, mb, hop.cAB.get(ma.owner) == mb.owner ? "body-unique" : "moved");
        }
    }
}

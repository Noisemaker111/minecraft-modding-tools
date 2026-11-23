import java.util.*;

/** Which method and field of the new release each one of the old became: by name, by rename, by registered id and by who calls it. */
final class HopMembers {

    final GameHop hop;

    HopMembers(GameHop hop) {
        this.hop = hop;
    }

    static final double RENAME = 0.75;
    static final double RENAME_SIG = 0.9;
    static final double MARGIN = 0.08;
    Map<String, String> idRenames;

    static double similarity(GameDiff.Meth x, GameDiff.Meth y) {
        if (x.hash == y.hash) {
            return 1;
        }
        if (stringsDisagree(x, y)) {
            return 0;
        }
        double ops = GameDiff.jaccard(x.bigrams(), y.bigrams());
        if (x.tokens.length + y.tokens.length == 0) {
            return 0.8 * ops;
        }
        return 0.6 * GameDiff.jaccard(x.tokens, y.tokens) + 0.4 * ops;
    }

    /** Both bodies name string constants (ids, translation keys) and share none: different things. */
    static boolean stringsDisagree(GameDiff.Meth x, GameDiff.Meth y) {
        Set<String> sx = new HashSet<>();
        for (GameDiff.Ref r : x.refs) {
            if (r.kind() == 'k' && r.owner().startsWith("s:")) {
                sx.add(r.owner());
            }
        }
        if (sx.isEmpty()) {
            return false;
        }
        boolean any = false;
        for (GameDiff.Ref r : y.refs) {
            if (r.kind() == 'k' && r.owner().startsWith("s:")) {
                any = true;
                if (sx.contains(r.owner())) {
                    return false;
                }
            }
        }
        return any;
    }

    // -------------------------------------------------------------- members of linked classes

    void members() {
        for (var e : new ArrayList<>(hop.cAB.entrySet())) {
            GameDiff.Cls ca = e.getKey();
            GameDiff.Cls cb = e.getValue();
            Map<String, GameDiff.Meth> byKey = new HashMap<>();
            Map<String, GameDiff.Fld> fieldsB = new HashMap<>();
            List<GameDiff.Cls> owners = new ArrayList<>();
            owners.add(cb);
            owners.addAll(newSupers(cb));
            for (GameDiff.Cls o : owners) {
                for (GameDiff.Meth mb : o.methods.values()) {
                    if (!hop.mBA.containsKey(mb) && (o == cb || !mb.special())) {
                        byKey.putIfAbsent(mb.name + hop.descBA(mb.desc), mb);
                    }
                }
                for (GameDiff.Fld fb : o.fields.values()) {
                    if (!hop.fBA.containsKey(fb)) {
                        fieldsB.putIfAbsent(fb.name, fb);
                    }
                }
            }
            for (GameDiff.Meth ma : ca.methods.values()) {
                if (hop.mAB.containsKey(ma)) {
                    continue;
                }
                GameDiff.Meth mb = byKey.get(ma.name + ma.desc);
                if (mb != null) {
                    hop.link(ma, mb, mb.owner == cb ? "name" : "pulled-up");
                }
            }
            for (GameDiff.Fld fa : ca.fields.values()) {
                GameDiff.Fld fb = fieldsB.get(fa.name);
                if (fb != null && !hop.fAB.containsKey(fa) && fa.desc.equals(hop.descBA(fb.desc))) {
                    hop.link(fa, fb, fb.owner == cb ? "name" : "pulled-up");
                }
            }
        }
    }

    /** Superclasses of c (in b) that no class of a links to: parents new in this release. */
    List<GameDiff.Cls> newSupers(GameDiff.Cls c) {
        List<GameDiff.Cls> out = new ArrayList<>();
        GameDiff.Cls p = c.sup == null ? null : hop.b.classes.get(c.sup);
        while (p != null && !hop.cBA.containsKey(p) && out.size() < 4) {
            out.add(p);
            p = p.sup == null ? null : hop.b.classes.get(p.sup);
        }
        return out;
    }

    /** The only unlinked method of a name on each side of a linked class pair: its signature changed. */
    void sameName() {
        for (var e : hop.cAB.entrySet()) {
            Map<String, List<GameDiff.Meth>> ua = new HashMap<>();
            Map<String, List<GameDiff.Meth>> ub = new HashMap<>();
            for (GameDiff.Meth ma : e.getKey().methods.values()) {
                if (!hop.mAB.containsKey(ma)) {
                    ua.computeIfAbsent(ma.name, x -> new ArrayList<>()).add(ma);
                }
            }
            for (GameDiff.Meth mb : e.getValue().methods.values()) {
                if (!hop.mBA.containsKey(mb)) {
                    ub.computeIfAbsent(mb.name, x -> new ArrayList<>()).add(mb);
                }
            }
            for (var g : ua.entrySet()) {
                List<GameDiff.Meth> l = ub.get(g.getKey());
                if (g.getValue().size() == 1 && l != null && l.size() == 1) {
                    GameDiff.Meth ma = g.getValue().get(0);
                    GameDiff.Meth mb = l.get(0);
                    if (ma.isStatic() == mb.isStatic()) {
                        hop.link(ma, mb, "name-signature");
                    }
                }
            }
            // A field keeps its name when its type changes (after renames of the same type had their chance).
            for (GameDiff.Fld fa : e.getKey().fields.values()) {
                GameDiff.Fld fb = e.getValue().fields.get(fa.name);
                if (fb != null && !hop.fAB.containsKey(fa) && !hop.fBA.containsKey(fb) && ((fa.flags ^ fb.flags) & GameDiff.ACC_STATIC) == 0) {
                    hop.link(fa, fb, "name-type");
                }
            }
        }
    }

    /** Renamed methods in linked classes: same descriptor (or, with sig, any) and a similar body. */
    void renames(boolean sig) {
        for (var e : hop.cAB.entrySet()) {
            GameDiff.Cls ca = e.getKey();
            GameDiff.Cls cb = e.getValue();
            List<GameDiff.Meth> la = new ArrayList<>();
            List<GameDiff.Meth> lb = new ArrayList<>();
            for (GameDiff.Meth m : ca.methods.values()) {
                if (!hop.mAB.containsKey(m) && !m.special() && !m.trivial()) {
                    la.add(m);
                }
            }
            for (GameDiff.Meth m : cb.methods.values()) {
                if (!hop.mBA.containsKey(m) && !m.special() && !m.trivial()) {
                    lb.add(m);
                }
            }
            if (la.isEmpty() || lb.isEmpty()) {
                continue;
            }
            double[][] s = new double[la.size()][lb.size()];
            for (int i = 0; i < la.size(); i++) {
                GameDiff.Meth x = la.get(i);
                for (int j = 0; j < lb.size(); j++) {
                    GameDiff.Meth y = lb.get(j);
                    boolean sameDesc = x.desc.equals(hop.descBA(y.desc));
                    if (x.isStatic() != y.isStatic() || (!sig && !sameDesc)) {
                        s[i][j] = -1;
                        continue;
                    }
                    s[i][j] = similarity(x, y);
                }
            }
            mutual(s, sig ? RENAME_SIG : RENAME, (i, j) -> hop.link(la.get(i), lb.get(j), sig ? "body-signature" : "body"));
        }
    }

    interface Accept {
        void accept(int i, int j);
    }

    static void mutual(double[][] s, double threshold, Accept accept) {
        int n = s.length;
        int m = n == 0 ? 0 : s[0].length;
        int[] bestRow = new int[n];
        double[] firstRow = new double[n];
        double[] secondRow = new double[n];
        int[] bestCol = new int[m];
        double[] firstCol = new double[m];
        double[] secondCol = new double[m];
        Arrays.fill(bestRow, -1);
        Arrays.fill(firstRow, -1);
        Arrays.fill(secondRow, -1);
        Arrays.fill(bestCol, -1);
        Arrays.fill(firstCol, -1);
        Arrays.fill(secondCol, -1);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                double v = s[i][j];
                if (v < 0) {
                    continue;
                }
                if (v > firstRow[i]) {
                    secondRow[i] = firstRow[i];
                    firstRow[i] = v;
                    bestRow[i] = j;
                } else if (v > secondRow[i]) {
                    secondRow[i] = v;
                }
                if (v > firstCol[j]) {
                    secondCol[j] = firstCol[j];
                    firstCol[j] = v;
                    bestCol[j] = i;
                } else if (v > secondCol[j]) {
                    secondCol[j] = v;
                }
            }
        }
        for (int i = 0; i < n; i++) {
            int j = bestRow[i];
            if (j < 0 || bestCol[j] != i || firstRow[i] < threshold) {
                continue;
            }
            if (firstRow[i] - secondRow[i] < MARGIN || firstCol[j] - secondCol[j] < MARGIN) {
                continue;
            }
            accept.accept(i, j);
        }
    }

    /** Renamed fields: same type, read and written by the same linked methods. */
    void fields() {
        for (var e : hop.cAB.entrySet()) {
            GameDiff.Cls ca = e.getKey();
            GameDiff.Cls cb = e.getValue();
            List<GameDiff.Fld> la = new ArrayList<>();
            List<GameDiff.Fld> lb = new ArrayList<>();
            for (GameDiff.Fld f : ca.fields.values()) {
                if (!hop.fAB.containsKey(f)) {
                    la.add(f);
                }
            }
            for (GameDiff.Fld f : cb.fields.values()) {
                if (!hop.fBA.containsKey(f)) {
                    lb.add(f);
                }
            }
            if (la.isEmpty() || lb.isEmpty()) {
                continue;
            }
            registered(la, lb);
            if (la.isEmpty() || lb.isEmpty()) {
                continue;
            }
            // Compile-time constants: same type and value, the only such pair in the class.
            Map<String, List<GameDiff.Fld>> ka = new HashMap<>();
            Map<String, List<GameDiff.Fld>> kb = new HashMap<>();
            for (GameDiff.Fld f : la) {
                if (distinctConstant(f)) {
                    ka.computeIfAbsent(f.desc + "=" + f.constant, x -> new ArrayList<>()).add(f);
                }
            }
            for (GameDiff.Fld f : lb) {
                if (distinctConstant(f)) {
                    kb.computeIfAbsent(hop.descBA(f.desc) + "=" + f.constant, x -> new ArrayList<>()).add(f);
                }
            }
            for (var k : ka.entrySet()) {
                List<GameDiff.Fld> l = kb.get(k.getKey());
                if (k.getValue().size() == 1 && l != null && l.size() == 1 && hop.link(k.getValue().get(0), l.get(0), "constant")) {
                    la.remove(k.getValue().get(0));
                    lb.remove(l.get(0));
                }
            }
            if (la.isEmpty() || lb.isEmpty()) {
                continue;
            }
            double[][] s = new double[la.size()][lb.size()];
            for (int i = 0; i < la.size(); i++) {
                GameDiff.Fld x = la.get(i);
                for (int j = 0; j < lb.size(); j++) {
                    GameDiff.Fld y = lb.get(j);
                    boolean enumConstant = (x.flags & GameDiff.ACC_ENUM) != 0 || (y.flags & GameDiff.ACC_ENUM) != 0;
                    if (enumConstant || ((x.flags ^ y.flags) & GameDiff.ACC_STATIC) != 0 || !x.desc.equals(hop.descBA(y.desc))) {
                        s[i][j] = -1;
                        continue;
                    }
                    s[i][j] = accessorScore(x, y);
                }
            }
            mutual(s, 0.5, (i, j) -> hop.link(la.get(i), lb.get(j), "accessors"));
        }
    }

    /**
     * The id renames the newer release's datafixes add (pairs the older release does not have yet),
     * kept only where an old id maps to one new id and a new id comes from one old id.
     */
    static Map<String, String> idRenames(GameDiff.Snap a, GameDiff.Snap b) {
        Map<String, Set<String>> forward = new HashMap<>();
        Map<String, Set<String>> backward = new HashMap<>();
        for (String p : b.fixPairs) {
            if (a.fixPairs.contains(p)) {
                continue;
            }
            int z = p.indexOf('\0');
            String from = p.substring(0, z);
            String to = p.substring(z + 1);
            if (from.isEmpty() || to.isEmpty() || from.equals(to)) {
                continue;
            }
            forward.computeIfAbsent(from, x -> new HashSet<>()).add(to);
            backward.computeIfAbsent(to, x -> new HashSet<>()).add(from);
        }
        Map<String, String> out = new HashMap<>();
        for (var e : forward.entrySet()) {
            String to = e.getValue().iterator().next();
            if (e.getValue().size() == 1 && backward.get(to).size() == 1) {
                out.put(e.getKey(), to);
            }
        }
        return out;
    }

    /**
     * Static fields registered under ids (the first string loaded before their putstatic in <clinit>): the same id
     * on both sides, or an id the newer release's datafixes rename (doMobLoot -> mob_drops), is the same
     * field. Every id must name one unlinked field on each side, and each field must get one partner.
     */
    void registered(List<GameDiff.Fld> la, List<GameDiff.Fld> lb) {
        Map<String, List<GameDiff.Fld>> ia = new HashMap<>();
        Map<String, List<GameDiff.Fld>> ib = new HashMap<>();
        for (GameDiff.Fld f : la) {
            if (f.regId != null) {
                ia.computeIfAbsent(f.regId, x -> new ArrayList<>()).add(f);
            }
        }
        for (GameDiff.Fld f : lb) {
            if (f.regId != null) {
                ib.computeIfAbsent(f.regId, x -> new ArrayList<>()).add(f);
            }
        }
        if (ia.isEmpty() || ib.isEmpty()) {
            return;
        }
        for (int pass = 0; pass < 2; pass++) {
            Map<GameDiff.Fld, Set<GameDiff.Fld>> ab = new LinkedHashMap<>();
            Map<GameDiff.Fld, Set<GameDiff.Fld>> ba = new LinkedHashMap<>();
            for (var e : ia.entrySet()) {
                String other = pass == 0 ? e.getKey() : idRenames.get(e.getKey());
                List<GameDiff.Fld> l = other == null ? null : ib.get(other);
                if (e.getValue().size() != 1 || l == null || l.size() != 1) {
                    continue;
                }
                GameDiff.Fld x = e.getValue().get(0);
                GameDiff.Fld y = l.get(0);
                if (hop.fAB.containsKey(x) || hop.fBA.containsKey(y) || ((x.flags ^ y.flags) & GameDiff.ACC_STATIC) != 0) {
                    continue;
                }
                ab.computeIfAbsent(x, k -> new HashSet<>()).add(y);
                ba.computeIfAbsent(y, k -> new HashSet<>()).add(x);
            }
            for (var e : ab.entrySet()) {
                GameDiff.Fld y = e.getValue().iterator().next();
                if (e.getValue().size() == 1 && ba.get(y).size() == 1) {
                    hop.link(e.getKey(), y, pass == 0 ? "registered-id" : "datafix-id");
                }
            }
        }
        la.removeIf(hop.fAB::containsKey);
        lb.removeIf(hop.fBA::containsKey);
    }

    /** A constant worth matching on: any string, or a number that is not a small whole number (0, 1, 16...). */
    static boolean distinctConstant(GameDiff.Fld f) {
        if (f.constant == null) {
            return false;
        }
        if (f.desc.equals("Ljava/lang/String;")) {
            return !f.constant.isEmpty();
        }
        try {
            double v = Double.parseDouble(f.constant);
            return v != Math.rint(v) || Math.abs(v) > 64;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Call sites: two linked methods whose bodies line up (same opcodes, or the same references around a
     * gap of equal length) reference an unlinked thing at the same place on each side; that is a vote
     * for linking the two. A link needs the votes on both sides to agree.
     */
    void callers() {
        Map<Object, Map<Object, Integer>> votes = new HashMap<>();
        Map<Object, Integer> totals = new HashMap<>();
        for (var e : hop.mAB.entrySet()) {
            GameDiff.Meth x = e.getKey();
            GameDiff.Meth y = e.getValue();
            if (x.refs.length == 0 || y.refs.length == 0 || !unlinkedRef(x, true) || !unlinkedRef(y, false)) {
                continue;
            }
            long[] ta = new long[x.refs.length];
            long[] tb = new long[y.refs.length];
            for (int i = 0; i < ta.length; i++) {
                ta[i] = GameDiff.fnv(hop.token(x.refs[i], true));
            }
            for (int j = 0; j < tb.length; j++) {
                tb[j] = GameDiff.fnv(hop.token(y.refs[j], false));
            }
            List<int[]> pairs = GameHop.align(ta, tb, x.ops.equals(y.ops));
            for (int[] p : pairs) {
                vote(x.refs[p[0]], y.refs[p[1]], votes, totals);
            }
        }
        for (var e : votes.entrySet()) {
            Object x = e.getKey();
            Object best = null;
            int c1 = 0;
            for (var f : e.getValue().entrySet()) {
                if (f.getValue() > c1) {
                    c1 = f.getValue();
                    best = f.getKey();
                }
            }
            int tx = totals.get(x);
            int ty = totals.get(best);
            if (c1 * 10 < tx * 8 || c1 * 10 < ty * 8) {
                continue;
            }
            if (x instanceof GameDiff.Meth ma && best instanceof GameDiff.Meth mb) {
                boolean sameDesc = ma.desc.equals(hop.descBA(mb.desc));
                boolean sameOwner = hop.cAB.get(ma.owner) == mb.owner;
                if (ma.isStatic() != mb.isStatic() || ma.special() || mb.special()) {
                    continue;
                }
                // A call site that now calls something with another signature is a rewrite, not a rename.
                if (!sameDesc || (!sameOwner && c1 < 3 && !ma.name.equals(mb.name))) {
                    continue;
                }
                hop.link(ma, mb, sameOwner ? "callers" : "callers-moved");
            } else if (x instanceof GameDiff.Fld fa && best instanceof GameDiff.Fld fb) {
                boolean sameDesc = fa.desc.equals(hop.descBA(fb.desc));
                if (((fa.flags ^ fb.flags) & GameDiff.ACC_STATIC) != 0 || !sameDesc) {
                    continue;
                }
                if (c1 < 2 && ((fa.flags & GameDiff.ACC_ENUM) != 0 || accessorScore(fa, fb) < 0.5)) {
                    continue;
                }
                if (hop.cAB.get(fa.owner) != fb.owner && !(fa.name.equals(fb.name) && c1 >= 2)) {
                    continue;
                }
                hop.link(fa, fb, "callers");
            } else if (x instanceof GameDiff.Cls ca && best instanceof GameDiff.Cls cb) {
                if (c1 >= 4 && ca.kind() == cb.kind() && ca.loader == cb.loader) {
                    hop.link(ca, cb, "callers");
                }
            }
        }
    }

    boolean unlinkedRef(GameDiff.Meth m, boolean sideA) {
        for (GameDiff.Ref r : m.refs) {
            if (target(r, sideA) != null) {
                return true;
            }
        }
        return false;
    }

    /** The unlinked class, method or field a reference names, on one side. */
    Object target(GameDiff.Ref r, boolean sideA) {
        GameDiff.Snap s = sideA ? hop.a : hop.b;
        switch (r.kind()) {
            case 'm' -> {
                GameDiff.Meth m = s.method(r.owner(), r.name(), r.desc());
                return m == null || (sideA ? hop.mAB : hop.mBA).containsKey(m) ? null : m;
            }
            case 'f' -> {
                GameDiff.Fld f = s.field(r.owner(), r.name());
                return f == null || (sideA ? hop.fAB : hop.fBA).containsKey(f) ? null : f;
            }
            case 'c' -> {
                GameDiff.Cls c = s.classes.get(r.owner());
                return c == null || (sideA ? hop.cAB : hop.cBA).containsKey(c) ? null : c;
            }
            default -> {
                return null;
            }
        }
    }

    void vote(GameDiff.Ref ra, GameDiff.Ref rb, Map<Object, Map<Object, Integer>> votes, Map<Object, Integer> totals) {
        if (ra.kind() != rb.kind()) {
            return;
        }
        Object x = target(ra, true);
        Object y = target(rb, false);
        if (x != null && y != null) {
            votes.computeIfAbsent(x, k -> new HashMap<>()).merge(y, 1, Integer::sum);
            totals.merge(x, 1, Integer::sum);
            totals.merge(y, 1, Integer::sum);
        }
        if (ra.kind() != 'c' && ra.kind() != 'k') {
            GameDiff.Cls ca = hop.a.classes.get(ra.owner());
            GameDiff.Cls cb = hop.b.classes.get(rb.owner());
            if (ca != null && cb != null && !hop.cAB.containsKey(ca) && !hop.cBA.containsKey(cb)) {
                votes.computeIfAbsent(ca, k -> new HashMap<>()).merge(cb, 1, Integer::sum);
                totals.merge(ca, 1, Integer::sum);
                totals.merge(cb, 1, Integer::sum);
            }
        }
    }

    /**
     * How much the linked methods reading or writing x and y overlap (Jaccard), or -1 when they share no
     * ordinary method: constructors and static initialisers touch every field and prove nothing alone.
     */
    double accessorScore(GameDiff.Fld x, GameDiff.Fld y) {
        Set<GameDiff.Meth> ax = new LinkedHashSet<>();
        for (GameDiff.Meth m : x.accessors) {
            if (hop.mAB.containsKey(m)) {
                ax.add(m);
            }
        }
        int common = 0;
        int plain = 0;
        int by = 0;
        for (GameDiff.Meth m : y.accessors) {
            GameDiff.Meth back = hop.mBA.get(m);
            if (back != null) {
                by++;
                if (ax.contains(back)) {
                    common++;
                    if (!back.special()) {
                        plain++;
                    }
                }
            }
        }
        int union = ax.size() + by - common;
        return plain == 0 ? -1 : (double) common / union;
    }
}

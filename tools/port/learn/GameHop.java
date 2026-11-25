import java.util.*;

final class GameHop {

    final HopClasses hopClasses = new HopClasses(this);
    final HopMembers hopMembers = new HopMembers(this);
    final HopRewrites hopRewrites = new HopRewrites(this);
    final HopChains hopChains = new HopChains(this);
    final HopArgDefaults hopArgDefaults = new HopArgDefaults(this);

    final GameDiff.Snap a;
    final GameDiff.Snap b;
    final Map<GameDiff.Cls, GameDiff.Cls> cAB = new LinkedHashMap<>();
    final Map<GameDiff.Cls, GameDiff.Cls> cBA = new LinkedHashMap<>();
    final Map<GameDiff.Meth, GameDiff.Meth> mAB = new LinkedHashMap<>();
    final Map<GameDiff.Meth, GameDiff.Meth> mBA = new LinkedHashMap<>();
    final Map<GameDiff.Fld, GameDiff.Fld> fAB = new LinkedHashMap<>();
    final Map<GameDiff.Fld, GameDiff.Fld> fBA = new LinkedHashMap<>();
    final Map<Object, String> how = new HashMap<>();
    Map<String, String> descCache = new HashMap<>();
    GameDiff.Published published = GameDiff.Published.NONE; // what the loader published as renamed in this hop

    GameHop(GameDiff.Snap a, GameDiff.Snap b) {
        if (!a.code || !b.code) {
            // Linking and learning read every method's code and what the whole of it tells of each field.
            throw new IllegalArgumentException("a release read without its code (GameDiff.read) cannot be linked: " + (a.code ? b : a).version);
        }
        this.a = a;
        this.b = b;
    }

    int total() {
        return cAB.size() + mAB.size() + fAB.size();
    }

    boolean link(GameDiff.Cls x, GameDiff.Cls y, String why) {
        if (cAB.containsKey(x) || cBA.containsKey(y)) {
            return false;
        }
        cAB.put(x, y);
        cBA.put(y, x);
        how.put(x, why);
        return true;
    }

    boolean link(GameDiff.Meth x, GameDiff.Meth y, String why) {
        if (mAB.containsKey(x) || mBA.containsKey(y)) {
            return false;
        }
        mAB.put(x, y);
        mBA.put(y, x);
        how.put(x, why);
        return true;
    }

    boolean link(GameDiff.Fld x, GameDiff.Fld y, String why) {
        if (fAB.containsKey(x) || fBA.containsKey(y)) {
            return false;
        }
        fAB.put(x, y);
        fBA.put(y, x);
        how.put(x, why);
        return true;
    }

    void run() {
        hopMembers.idRenames = HopMembers.idRenames(a, b);
        for (GameDiff.Cls c : a.classes.values()) {
            for (GameDiff.Meth m : c.methods.values()) {
                tokens(m, true);
            }
        }
        // published first: a rename can hand its old name to another class (Fabric 26.1's FabricServerRecipeManager
        // became FabricRecipeManager, whose old class became FabricRecipeAccess), and the name would claim both ends
        published.classes().forEach((x, y) -> {
            GameDiff.Cls ca = a.classes.get(x), cb = b.classes.get(y);
            if (ca != null && cb != null) {
                link(ca, cb, "published");
            }
        });
        for (GameDiff.Cls ca : a.classes.values()) {
            GameDiff.Cls cb = b.classes.get(ca.name);
            if (cb != null) {
                link(ca, cb, "name");
            }
        }
        for (int round = 0; round < 8; round++) {
            int before = total();
            descCache = new HashMap<>();
            fingerprintB();
            hopClasses.nested();
            hopClasses.signatureClasses();
            hopClasses.fuzzyClasses();
            descCache = new HashMap<>();
            publishedMembers();
            hopMembers.members();
            fingerprintB();
            hopMembers.renames(false);
            hopMembers.sameName();
            hopMembers.fields();
            hopClasses.hierarchy();
            hopMembers.callers();
            descCache = new HashMap<>();
            fingerprintB();
            hopClasses.moves();
            hopMembers.renames(true);
            if (GameDiff.verbose) {
                System.out.printf("    round %d: %d classes, %d methods, %d fields linked%n", round, cAB.size(), mAB.size(), fAB.size());
            }
            if (total() == before) {
                break;
            }
        }
    }

    /**
     * The members the loader listed as renamed, by name only (its list gives no descriptors): each old method is linked
     * to the renamed one whose descriptor reads as its own in the old names, or to the only one of that name.
     */
    void publishedMembers() {
        published.members().forEach((owner, renamed) -> {
            GameDiff.Cls ca = a.classes.get(owner);
            GameDiff.Cls cb = ca == null ? null : cAB.get(ca);
            if (cb == null) {
                return;
            }
            renamed.forEach((from, to) -> {
                GameDiff.Fld fa = ca.fields.get(from), fb = cb.fields.get(to);
                if (fa != null && fb != null) {
                    link(fa, fb, "published");
                }
                List<GameDiff.Meth> olds = ca.methods.values().stream().filter(m -> m.name.equals(from)).toList();
                List<GameDiff.Meth> news = cb.methods.values().stream().filter(m -> m.name.equals(to)).toList();
                for (GameDiff.Meth ma : olds) {
                    GameDiff.Meth pick = news.stream().filter(mb -> descBA(mb.desc).equals(ma.desc)).findFirst()
                        .orElse(olds.size() == 1 && news.size() == 1 ? news.get(0) : null);
                    if (pick != null) {
                        link(ma, pick, "published");
                    }
                }
            });
        });
    }

    // -------------------------------------------------------------- translating b into a's names

    String clsBA(String n) {
        if (n.startsWith("[")) {
            return descBA(n);
        }
        GameDiff.Cls cb = b.classes.get(n);
        GameDiff.Cls ca = cb == null ? null : cBA.get(cb);
        return ca == null ? n : ca.name;
    }

    String descBA(String d) {
        String hit = descCache.get(d);
        if (hit != null) {
            return hit;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < d.length()) {
            char ch = d.charAt(i);
            if (ch == 'L') {
                int semi = d.indexOf(';', i);
                sb.append('L').append(clsBA(d.substring(i + 1, semi))).append(';');
                i = semi + 1;
            } else {
                sb.append(ch);
                i++;
            }
        }
        String out = sb.toString();
        descCache.put(d, out);
        return out;
    }

    String token(GameDiff.Ref r, boolean sideA) {
        switch (r.kind()) {
            case 'k' -> {
                return "k" + r.owner();
            }
            case 'c' -> {
                return "c" + (sideA ? r.owner() : clsBA(r.owner()));
            }
            case 'm' -> {
                if (!sideA) {
                    GameDiff.Meth mb = b.method(r.owner(), r.name(), r.desc());
                    GameDiff.Meth ma = mb == null ? null : mBA.get(mb);
                    if (ma != null) {
                        return "m" + ma.owner.name + "." + ma.name + ma.desc;
                    }
                    return "m" + clsBA(r.owner()) + "." + r.name() + descBA(r.desc());
                }
                return "m" + r.owner() + "." + r.name() + r.desc();
            }
            default -> {
                if (!sideA) {
                    GameDiff.Fld fb = b.field(r.owner(), r.name());
                    GameDiff.Fld fa = fb == null ? null : fBA.get(fb);
                    if (fa != null) {
                        return "f" + fa.owner.name + "." + fa.name;
                    }
                    return "f" + clsBA(r.owner()) + "." + r.name();
                }
                return "f" + r.owner() + "." + r.name();
            }
        }
    }

    /** A method's references as hashed tokens, in order: what two versions of a body are lined up by. */
    long[] hashed(GameDiff.Meth m, boolean sideA) {
        long[] out = new long[m.refs.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = GameDiff.fnv(token(m.refs[i], sideA));
        }
        return out;
    }

    /** The longest-common-subsequence table of two token lists: l[i][j] is its length from i and j on. */
    static int[][] lcs(long[] ta, long[] tb) {
        int n = ta.length;
        int m = tb.length;
        int[][] l = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                l[i][j] = ta[i] == tb[j] ? l[i + 1][j + 1] + 1 : Math.max(l[i + 1][j], l[i][j + 1]);
            }
        }
        return l;
    }

    void tokens(GameDiff.Meth m, boolean sideA) {
        long[] t = new long[m.refs.length];
        long h = GameDiff.fnv(m.ops);
        for (int i = 0; i < t.length; i++) {
            t[i] = GameDiff.fnv(token(m.refs[i], sideA));
            h = h * 31 + t[i];
        }
        Arrays.sort(t);
        m.tokens = t;
        m.hash = h;
    }

    void fingerprintB() {
        for (GameDiff.Cls c : b.classes.values()) {
            for (GameDiff.Meth m : c.methods.values()) {
                if (!mBA.containsKey(m)) {
                    tokens(m, false);
                }
            }
        }
    }

    /**
     * Index pairs of references that line up and differ: positionally when the opcodes are identical,
     * otherwise inside equal-length gaps between the references both sides share (longest common subsequence).
     */
    static List<int[]> align(long[] ta, long[] tb, boolean sameOps) {
        List<int[]> out = new ArrayList<>();
        if (sameOps && ta.length == tb.length) {
            for (int i = 0; i < ta.length; i++) {
                if (ta[i] != tb[i]) {
                    out.add(new int[] { i, i });
                }
            }
            return out;
        }
        int n = ta.length;
        int m = tb.length;
        if ((long) n * m > 250_000) {
            return out;
        }
        int[][] l = lcs(ta, tb);
        int i = 0;
        int j = 0;
        int gi = 0;
        int gj = 0;
        while (i < n && j < m) {
            if (ta[i] == tb[j]) {
                gap(gi, i, gj, j, out);
                i++;
                j++;
                gi = i;
                gj = j;
            } else if (l[i + 1][j] >= l[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        gap(gi, n, gj, m, out);
        return out;
    }

    /** Every stretch the longest common subsequence leaves unmatched on both sides: {i0, i1, j0, j1}, either side possibly empty. */
    static List<int[]> gaps(long[] ta, long[] tb) {
        List<int[]> out = new ArrayList<>();
        int n = ta.length;
        int m = tb.length;
        if ((long) n * m > 250_000) {
            return out;
        }
        int[][] l = lcs(ta, tb);
        int i = 0;
        int j = 0;
        int gi = 0;
        int gj = 0;
        while (i < n && j < m) {
            if (ta[i] == tb[j]) {
                if (i > gi || j > gj) {
                    out.add(new int[] { gi, i, gj, j });
                }
                i++;
                j++;
                gi = i;
                gj = j;
            } else if (l[i + 1][j] >= l[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        if (n > gi || m > gj) {
            out.add(new int[] { gi, n, gj, m });
        }
        return out;
    }

    static void gap(int i0, int i1, int j0, int j1, List<int[]> out) {
        if (i1 - i0 != j1 - j0) {
            return;
        }
        for (int k = 0; k < i1 - i0; k++) {
            out.add(new int[] { i0 + k, j0 + k });
        }
    }
}

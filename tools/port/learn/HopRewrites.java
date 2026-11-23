import java.util.*;

/** Calls the game's own code rewrote between the two releases: the call each old call became, with the evidence for it. */
final class HopRewrites {

    final GameHop hop;

    HopRewrites(GameHop hop) {
        this.hop = hop;
    }

    /** A call the game rewrote: what its own code called in a's release and calls at the same place in b's. */
    record Rewrite(GameDiff.Meth from, GameDiff.Meth to, int votes, int total) {}

    /**
     * Call-site rewrites, read from the game's own code once linking is done. In every pair of linked methods the
     * references are aligned (through the links, so what stayed the same lines up); where a method of a that has no
     * link (removed, or its signature changed) sits opposite a method of b, that is a vote for b's method as its
     * replacement (1.20.2: PacketByteBuf.getVarIntLength -> VarInt.getByteSize). Unlike a link, the replacement may
     * have another owner, descriptor or static-ness: the porter maps the arguments. Kept when at least two call sites
     * agree and 80% of the removed method's aligned call sites name the same replacement.
     */
    List<Rewrite> rewrites() {
        Map<GameDiff.Meth, Map<GameDiff.Meth, Integer>> votes = new LinkedHashMap<>();
        Map<GameDiff.Meth, Integer> totals = new LinkedHashMap<>();
        for (var e : hop.mAB.entrySet()) {
            GameDiff.Meth x = e.getKey();
            GameDiff.Meth y = e.getValue();
            if (x.refs.length == 0 || y.refs.length == 0) {
                continue;
            }
            long[] ta = hop.hashed(x, true);
            long[] tb = hop.hashed(y, false);
            for (int[] p : GameHop.align(ta, tb, x.ops.equals(y.ops))) {
                GameDiff.Ref ra = x.refs[p[0]];
                GameDiff.Ref rb = y.refs[p[1]];
                if (ra.kind() != 'm' || rb.kind() != 'm') {
                    continue;
                }
                GameDiff.Meth ma = hop.a.method(ra.owner(), ra.name(), ra.desc());
                GameDiff.Meth mb = hop.b.method(rb.owner(), rb.name(), rb.desc());
                if (ma == null || mb == null || hop.mAB.containsKey(ma) || ma.special() || mb.special()) {
                    continue;
                }
                votes.computeIfAbsent(ma, k -> new HashMap<>()).merge(mb, 1, Integer::sum);
                totals.merge(ma, 1, Integer::sum);
            }
        }
        // Calls the game deleted: a removed void method whose call sites in linked methods lost the call while what was
        // around it stayed (1.21.2: RenderSystem.applyModelViewMatrix()). Counted against every call site seen.
        Map<GameDiff.Meth, Integer> dropped = new LinkedHashMap<>(), seen = new LinkedHashMap<>();
        for (var e : hop.mAB.entrySet()) {
            GameDiff.Meth x = e.getKey();
            GameDiff.Meth y = e.getValue();
            if (x.refs.length == 0) {
                continue;
            }
            long[] ta = hop.hashed(x, true);
            long[] tb = hop.hashed(y, false);
            Set<Integer> gone = droppedIndices(ta, tb);
            for (int i = 0; i < x.refs.length; i++) {
                GameDiff.Ref ra = x.refs[i];
                if (ra.kind() != 'm' || !ra.desc().endsWith(")V")) {
                    continue;
                }
                GameDiff.Meth ma = hop.a.method(ra.owner(), ra.name(), ra.desc());
                if (ma == null || hop.mAB.containsKey(ma) || ma.special()) {
                    continue;
                }
                seen.merge(ma, 1, Integer::sum);
                if (gone.contains(i)) {
                    dropped.merge(ma, 1, Integer::sum);
                }
            }
        }
        List<Rewrite> out = new ArrayList<>();
        for (var e : dropped.entrySet()) {
            int all = seen.get(e.getKey());
            if (e.getValue() >= 2 && e.getValue() * 10 >= all * 8 && !votes.containsKey(e.getKey())) {
                out.add(new Rewrite(e.getKey(), null, e.getValue(), all));
            }
        }
        for (var e : votes.entrySet()) {
            GameDiff.Meth best = null;
            int c1 = 0;
            for (var f : e.getValue().entrySet()) {
                if (f.getValue() > c1) {
                    c1 = f.getValue();
                    best = f.getKey();
                }
            }
            int total = totals.get(e.getKey());
            // The game's own code may call a private helper in place of what it removed; a mod cannot.
            boolean reachable = (best.flags & GameDiff.ACC_PRIVATE) == 0 || (e.getKey().flags & GameDiff.ACC_PRIVATE) != 0;
            if (reachable && receiverCarries(e.getKey(), best) && (c1 >= 2 && c1 * 10 >= total * 8 || c1 == 1 && total == 1 && typeSafe(e.getKey(), best))) {
                out.add(new Rewrite(e.getKey(), best, c1, total));
            }
        }
        out.sort(Comparator.comparing((Rewrite r) -> r.from().sig()));
        return out;
    }

    /**
     * Removed methods whose old code can go with the mod: every class, method and field the body uses is outside the
     * game (the JDK, a library) or is still in b, linked and public, or is another removed method of the same class
     * that passes the same test. Such a body, copied into the mod as a helper and renamed through the map, still
     * compiles and does what it did. Keyed as Exposure keys them.
     */
    Set<String> portable() {
        Set<String> out = new HashSet<>();
        Map<GameDiff.Meth, Boolean> memo = new LinkedHashMap<>();
        for (GameDiff.Cls c : hop.a.classes.values()) {
            for (GameDiff.Meth m : c.methods.values()) {
                if (!hop.mAB.containsKey(m) && (m.flags & GameDiff.ACC_PUBLIC) != 0 && portable(m, memo, 0)) {
                    out.add(m.owner.name.replace('/', '.') + "." + m.name + m.desc);
                }
            }
        }
        return out;
    }

    boolean portable(GameDiff.Meth m, Map<GameDiff.Meth, Boolean> memo, int depth) {
        Boolean known = memo.get(m);
        if (known != null) {
            return known;
        }
        if (!m.hasCode || depth > 4) {
            return false;
        }
        memo.put(m, false); // a cycle is not portable
        boolean ok = m.isStatic() || publicInB(hop.cAB.get(m.owner));
        for (GameDiff.Ref r : m.refs) {
            if (!ok) {
                break;
            }
            GameDiff.Cls owner = hop.a.classes.get(r.owner());
            if (owner == null) {
                continue; // the JDK or a library
            }
            switch (r.kind()) {
                case 'm' -> {
                    GameDiff.Meth x = null;
                    for (GameDiff.Cls k = owner; k != null && x == null; k = k.sup == null ? null : hop.a.classes.get(k.sup)) {
                        x = k.methods.get(r.name() + r.desc());
                    }
                    if (x == null) {
                        ok = false;
                    } else if (hop.mAB.containsKey(x)) {
                        GameDiff.Meth y = hop.mAB.get(x);
                        ok = (y.flags & GameDiff.ACC_PUBLIC) != 0 && publicInB(y.owner);
                    } else {
                        ok = x.owner == m.owner && portable(x, memo, depth + 1);
                    }
                }
                case 'f' -> {
                    GameDiff.Fld x = null;
                    for (GameDiff.Cls k = owner; k != null && x == null; k = k.sup == null ? null : hop.a.classes.get(k.sup)) {
                        x = k.fields.get(r.name());
                    }
                    GameDiff.Fld y = x == null ? null : hop.fAB.get(x);
                    ok = y != null && (y.flags & GameDiff.ACC_PUBLIC) != 0 && publicInB(y.owner);
                }
                default -> ok = publicInB(hop.cAB.get(owner));
            }
        }
        memo.put(m, ok);
        return ok;
    }

    static boolean publicInB(GameDiff.Cls c) {
        return c != null && (c.flags & GameDiff.ACC_PUBLIC) != 0;
    }

    /**
     * A replacement seen at a single call site is kept only when the types prove the porter can write it: it returns
     * what the old method returned (after the map's renames), each of its parameters is filled by an old argument or
     * the old receiver of the same type, and an instance replacement's receiver is the old receiver or an old argument.
     */
    boolean typeSafe(GameDiff.Meth from, GameDiff.Meth to) {
        if (from.special() || to.special()) {
            return false;
        }
        String oldRet = from.desc.substring(from.desc.indexOf(')') + 1);
        String newRet = hop.descBA(to.desc.substring(to.desc.indexOf(')') + 1));
        if (!oldRet.equals(newRet)) {
            return false;
        }
        List<String> have = new ArrayList<>(MapFix.paramTypes(from.desc));
        if (!from.isStatic()) {
            have.add(from.owner.name.replace('/', '.'));
        }
        if (!to.isStatic()) {
            GameDiff.Cls owner = hop.cBA.get(to.owner);
            String receiver = owner == null ? null : owner.name.replace('/', '.');
            if (receiver == null || !have.remove(receiver)) {
                return false;
            }
        }
        for (String p : MapFix.paramTypes(hop.descBA(to.desc))) {
            if (!have.remove(p)) {
                return false;
            }
        }
        return true;
    }

    /**
     * An instance call the game moved to another instance method keeps its receiver only when the new owner is the old
     * class (as b has it) or related to it: 1.21.2's game replaced player.sendSystemMessage(c) with
     * minecraft.gui.getChat().addMessage(c), a call on another object a mod's receiver cannot stand in for. A call that
     * becomes or stops being static carries its receiver as an argument, which the porter checks by type.
     */
    boolean receiverCarries(GameDiff.Meth from, GameDiff.Meth to) {
        if (to == null || from.isStatic() || to.isStatic()) {
            return true;
        }
        // A class b no longer has goes with it (ItemInteractionResult -> InteractionResult): the mod's variable is
        // retyped by the class rewrite, so its value is the new owner's.
        GameDiff.Cls mapped = hop.cAB.get(from.owner);
        if (mapped == null && !hop.b.classes.containsKey(from.owner.name)) {
            return true;
        }
        String old = mapped == null ? from.owner.name : mapped.name;
        return hop.hopChains.subtypeB(old, to.owner.name) || hop.hopChains.subtypeB(to.owner.name, old);
    }

    static String simpleName(String internal) {
        return internal.substring(Math.max(internal.lastIndexOf('/'), internal.lastIndexOf('$')) + 1);
    }

    /** A class the game stopped using and what its own code uses in its place. */
    record ClassRewrite(GameDiff.Cls from, GameDiff.Cls to, int votes, int total) {}

    /**
     * Removed classes and their replacements, read the same way as method rewrites: where an unlinked class of a is
     * referenced (a type, a cast, a new instance, the owner of a call) and the aligned reference of b names another
     * class, that is a vote (1.21.2: DirectionProperty -> EnumProperty, LootContextParam -> ContextKey). Kept with two
     * agreeing votes and 80% agreement.
     */
    List<ClassRewrite> classRewrites() {
        Map<GameDiff.Cls, Map<GameDiff.Cls, Integer>> votes = new LinkedHashMap<>();
        Map<GameDiff.Cls, Integer> totals = new LinkedHashMap<>();
        for (var e : hop.mAB.entrySet()) {
            GameDiff.Meth x = e.getKey();
            GameDiff.Meth y = e.getValue();
            if (x.refs.length == 0 || y.refs.length == 0) {
                continue;
            }
            long[] ta = hop.hashed(x, true);
            long[] tb = hop.hashed(y, false);
            for (int[] p : GameHop.align(ta, tb, x.ops.equals(y.ops))) {
                GameDiff.Ref ra = x.refs[p[0]];
                GameDiff.Ref rb = y.refs[p[1]];
                if (ra.kind() == 'k' || rb.kind() == 'k') {
                    continue;
                }
                GameDiff.Cls ca = hop.a.classes.get(ra.owner());
                GameDiff.Cls cb = hop.b.classes.get(rb.owner());
                if (ca == null || cb == null || hop.cAB.containsKey(ca) || GameDiff.anonymous(ca.name)) {
                    continue;
                }
                votes.computeIfAbsent(ca, k -> new HashMap<>()).merge(cb, 1, Integer::sum);
                totals.merge(ca, 1, Integer::sum);
            }
        }
        List<ClassRewrite> out = new ArrayList<>();
        for (var e : votes.entrySet()) {
            GameDiff.Cls best = null;
            int c1 = 0;
            for (var f : e.getValue().entrySet()) {
                if (f.getValue() > c1) {
                    c1 = f.getValue();
                    best = f.getKey();
                }
            }
            int total = totals.get(e.getKey());
            boolean moved = c1 == 1 && total == 1 && simpleName(best.name).equals(simpleName(e.getKey().name));
            if ((c1 >= 2 && c1 * 10 >= total * 8 || moved) && best.kind() == e.getKey().kind()) {
                out.add(new ClassRewrite(e.getKey(), best, c1, total));
            }
        }
        out.sort(Comparator.comparing((ClassRewrite r) -> r.from().name));
        return out;
    }

    /** Indices of ta that the longest common subsequence leaves out where tb has nothing in their place (pure deletions). */
    static Set<Integer> droppedIndices(long[] ta, long[] tb) {
        Set<Integer> out = new HashSet<>();
        int n = ta.length;
        int m = tb.length;
        if ((long) n * m > 250_000) {
            return out;
        }
        int[][] l = GameHop.lcs(ta, tb);
        int i = 0;
        int j = 0;
        int gi = 0;
        int gj = 0;
        while (i < n && j < m) {
            if (ta[i] == tb[j]) {
                if (j == gj) {
                    for (int k = gi; k < i; k++) {
                        out.add(k);
                    }
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
        if (j == gj && m == gj) {
            for (int k = gi; k < n; k++) {
                out.add(k);
            }
        }
        return out;
    }
}

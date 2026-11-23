import java.util.*;

/** Members the game put behind a chain of calls, and their siblings that went the same way. */
final class HopChains {

    final GameHop hop;

    HopChains(GameHop hop) {
        this.hop = hop;
    }

    /**
     * What the game's own code says about one member of a that has no link: how many places in a use it, how many of
     * those sit in methods that b still has (linked), how many of those line up with something in b, and the
     * reference of b most of them line up with. Says why the miner gave no replacement: nothing in the game used it,
     * every user was redesigned, or the users disagree.
     */
    record Evidence(int sites, int kept, int aligned, String best, int votes, String template, int templateVotes, int templateSites) {}

    /** The references of b each template string stands for, and the reference of a each evidence key came from. */
    final Map<String, List<GameDiff.Ref>> templateRefs = new HashMap<>();
    final Map<String, GameDiff.Ref> unlinkedMember = new HashMap<>();

    /**
     * A member the game replaced with a chain on the same receiver (1.21.2: input.up -> input.keyPresses.forward()):
     * steps are "f:name" or "m:name" joined by commas; the last takes the old call's arguments.
     */
    record Chain(String owner, String newOwner, String name, String desc, boolean field, String steps, int votes, int of) {}

    /**
     * Templates the game's own code agrees on (two uses, 60%) that are a type-correct chain: the first step is a member
     * of the old owner's class in b, every step is an instance member of what the step before returns, every step but
     * the last is a field or a no-argument method, and the last gives what the old member gave (same field type, or
     * the same parameters and return type) after the map's renames.
     */
    /** A method moved to an unrelated class that one holder keeps beside the old one, each in a public field of its own. */
    record Sibling(GameDiff.Meth from, GameDiff.Meth to, String holder, String fromField, String toField) {}

    /**
     * 26.2 moved LevelRenderer.blockChanged to LevelExtractor, and Minecraft keeps both (levelRenderer, levelExtractor):
     * a call through holder.levelRenderer goes through holder.levelExtractor. Only a holder with exactly one public
     * field of each type in b counts, and only a move between classes neither of which extends the other.
     */
    List<Sibling> siblings() {
        List<Sibling> out = new ArrayList<>();
        for (var e : hop.mAB.entrySet()) {
            GameDiff.Meth a = e.getKey(), m = e.getValue();
            if (a.isStatic() || m.isStatic() || (m.flags & GameDiff.ACC_PUBLIC) == 0) {
                continue;
            }
            GameDiff.Cls mapped = hop.cAB.get(a.owner);
            String oldOwner = mapped == null ? null : mapped.name, newOwner = m.owner.name;
            if (oldOwner == null || oldOwner.equals(newOwner) || subtypeB(oldOwner, newOwner) || subtypeB(newOwner, oldOwner)) {
                continue;
            }
            Sibling found = null;
            int holders = 0;
            for (GameDiff.Cls c : hop.b.classes.values()) {
                String fromField = null, toField = null;
                int fromCount = 0, toCount = 0;
                for (GameDiff.Fld f : c.fields.values()) {
                    if ((f.flags & GameDiff.ACC_PUBLIC) == 0 || (f.flags & GameDiff.ACC_STATIC) != 0) {
                        continue;
                    }
                    if (f.desc.equals("L" + oldOwner + ";")) {
                        fromField = f.name;
                        fromCount++;
                    } else if (f.desc.equals("L" + newOwner + ";")) {
                        toField = f.name;
                        toCount++;
                    }
                }
                if (fromCount == 1 && toCount == 1) {
                    holders++;
                    found = new Sibling(a, m, c.name.replace('/', '.'), fromField, toField);
                }
            }
            if (holders == 1) {
                out.add(found);
            }
        }
        out.sort(Comparator.comparing((Sibling s) -> s.from().owner.name + "." + s.from().name + s.from().desc));
        return out;
    }

    List<Chain> chains() {
        Map<String, Evidence> ev = evidence();
        List<Chain> out = new ArrayList<>();
        for (var e : ev.entrySet()) {
            Evidence x = e.getValue();
            GameDiff.Ref old = unlinkedMember.get(e.getKey());
            boolean agreed = x.templateVotes() >= 2 && x.templateVotes() * 10 >= x.templateSites() * 6 || x.templateSites() == 1;
            if (old == null || x.template() == null || !agreed) {
                continue;
            }
            String steps = chain(old, templateRefs.get(x.template()));
            if (steps == null) {
                continue;
            }
            String key = e.getKey();
            int cut = old.kind() == 'm' ? key.lastIndexOf('.', key.indexOf('(')) : key.lastIndexOf('.');
            String owner = key.substring(0, cut);
            GameDiff.Cls oa = hop.a.classes.get(owner.replace('.', '/'));
            GameDiff.Cls ob = oa == null ? null : hop.cAB.get(oa);
            out.add(new Chain(owner, ob == null ? owner : ob.name.replace('/', '.'), old.name(), old.kind() == 'm' ? old.desc() : "",
                old.kind() != 'm', steps, x.templateVotes(), x.templateSites()));
        }
        out.sort(Comparator.comparing(Chain::owner).thenComparing(Chain::name));
        return out;
    }

    /** The steps of a type-correct chain from old's receiver, or null. */
    String chain(GameDiff.Ref old, List<GameDiff.Ref> refs) {
        if (refs == null || refs.isEmpty() || old.kind() == 'c' || old.kind() == 'k') {
            return null;
        }
        GameDiff.Cls oa = hop.a.classes.get(old.owner());
        GameDiff.Cls cur = oa == null ? null : hop.cAB.get(oa);
        String want;
        if (old.kind() == 'm') {
            GameDiff.Meth m = hop.a.method(old.owner(), old.name(), old.desc());
            if (m == null || m.isStatic() || m.special()) {
                return null;
            }
            want = old.desc();
        } else {
            GameDiff.Fld f = hop.a.field(old.owner(), old.name());
            if (f == null || (f.flags & GameDiff.ACC_STATIC) != 0) {
                return null;
            }
            want = f.desc;
        }
        StringBuilder steps = new StringBuilder();
        for (int i = 0; i < refs.size(); i++) {
            GameDiff.Ref r = refs.get(i);
            boolean last = i == refs.size() - 1;
            GameDiff.Meth stepMethod = r.kind() == 'm' ? hop.b.method(r.owner(), r.name(), r.desc()) : null;
            GameDiff.Fld stepField = r.kind() == 'f' ? hop.b.field(r.owner(), r.name()) : null;
            boolean hidden = stepMethod != null && (stepMethod.flags & GameDiff.ACC_PRIVATE) != 0 || stepField != null && (stepField.flags & GameDiff.ACC_PRIVATE) != 0;
            if (cur == null || hidden || (r.kind() != 'm' && r.kind() != 'f') || r.name().contains("$") || !subtypeB(cur.name, r.owner())) {
                return null;
            }
            String type;
            if (r.kind() == 'm') {
                GameDiff.Meth m = hop.b.method(r.owner(), r.name(), r.desc());
                if (m != null && m.isStatic() || r.name().startsWith("<") || !last && !r.desc().startsWith("()")) {
                    return null;
                }
                String mapped = hop.descBA(r.desc());
                if (last && (old.kind() == 'm' ? !mapped.equals(want) : !mapped.equals("()" + want))) {
                    return null;
                }
                type = r.desc().substring(r.desc().indexOf(')') + 1);
            } else {
                GameDiff.Fld f = hop.b.field(r.owner(), r.name());
                if (f != null && (f.flags & GameDiff.ACC_STATIC) != 0 || last && (old.kind() == 'm' || !hop.descBA(r.desc()).equals(want))) {
                    return null;
                }
                type = r.desc();
            }
            steps.append(i == 0 ? "" : ",").append(r.kind()).append(':').append(r.name());
            cur = type.startsWith("L") ? hop.b.classes.get(type.substring(1, type.length() - 1)) : null;
        }
        return steps.toString();
    }

    /** sub is sup, or extends or implements it, in b. */
    boolean subtypeB(String sub, String sup) {
        Deque<String> todo = new ArrayDeque<>(List.of(sub));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            String c = todo.pop();
            if (c.equals(sup)) {
                return true;
            }
            GameDiff.Cls k = hop.b.classes.get(c);
            if (k == null || !seen.add(c)) {
                continue;
            }
            if (k.sup != null) {
                todo.push(k.sup);
            }
            k.ifaces.forEach(todo::push);
        }
        return false;
    }

    /** Evidence for every unlinked class, method and field of a, keyed as Exposure keys them (dotted owner, name, desc). */
    Map<String, Evidence> evidence() {
        Map<String, int[]> counts = new HashMap<>();
        Map<String, Map<String, Integer>> votes = new HashMap<>();
        Map<String, Map<String, Integer>> templates = new HashMap<>();
        templateRefs.clear();
        unlinkedMember.clear();
        for (GameDiff.Cls c : hop.a.classes.values()) {
            for (GameDiff.Meth x : c.methods.values()) {
                GameDiff.Meth y = hop.mAB.get(x);
                long[] ta = new long[x.refs.length];
                for (int i = 0; i < ta.length; i++) {
                    ta[i] = GameDiff.fnv(hop.token(x.refs[i], true));
                }
                Map<Integer, GameDiff.Ref> opposite = new HashMap<>();
                if (y != null && y.refs.length > 0) {
                    long[] tb = new long[y.refs.length];
                    for (int j = 0; j < tb.length; j++) {
                        tb[j] = GameDiff.fnv(hop.token(y.refs[j], false));
                    }
                    for (int[] p : GameHop.align(ta, tb, x.ops.equals(y.ops))) {
                        opposite.put(p[0], y.refs[p[1]]);
                    }
                    // A stretch the game rewrote into another number of references (a field read that became a call
                    // chain): the new stretch, in b's names, is a vote for a template for every unlinked thing in it.
                    for (int[] g : GameHop.gaps(ta, tb)) {
                        int na = g[1] - g[0], nb = g[3] - g[2];
                        if (na == nb || na == 0 || na > 3 || nb > 4) {
                            continue;
                        }
                        StringBuilder t = new StringBuilder();
                        for (int j = g[2]; j < g[3]; j++) {
                            GameDiff.Ref rb = y.refs[j];
                            t.append(t.length() == 0 ? "" : " ; ").append(rb.kind()).append(' ')
                                .append(rb.kind() == 'm' ? rb.owner() + "." + rb.name() + rb.desc() : rb.kind() == 'c' || rb.kind() == 'k' ? rb.owner() : rb.owner() + "." + rb.name());
                        }
                        String ts = t.toString().replace('/', '.');
                        templateRefs.putIfAbsent(ts, List.of(Arrays.copyOfRange(y.refs, g[2], g[3])));
                        for (int k = g[0]; k < g[1]; k++) {
                            String key = unlinkedKey(x.refs[k]);
                            if (key != null) {
                                templates.computeIfAbsent(key, z -> new HashMap<>()).merge(ts, 1, Integer::sum);
                                unlinkedMember.putIfAbsent(key, x.refs[k]);
                            }
                        }
                    }
                }
                for (int i = 0; i < x.refs.length; i++) {
                    String key = unlinkedKey(x.refs[i]);
                    if (key == null) {
                        continue;
                    }
                    int[] n = counts.computeIfAbsent(key, k -> new int[3]);
                    n[0]++;
                    if (y != null) {
                        n[1]++;
                    }
                    GameDiff.Ref rb = opposite.get(i);
                    if (rb != null) {
                        n[2]++;
                        String to = rb.kind() == 'm' ? rb.owner() + "." + rb.name() + rb.desc() : rb.kind() == 'c' ? rb.owner() : rb.owner() + "." + rb.name();
                        votes.computeIfAbsent(key, k -> new HashMap<>()).merge(to.replace('/', '.'), 1, Integer::sum);
                    }
                }
            }
        }
        Map<String, Evidence> out = new HashMap<>();
        for (var e : counts.entrySet()) {
            String best = null;
            int top = 0;
            for (var v : votes.getOrDefault(e.getKey(), Map.of()).entrySet()) {
                if (v.getValue() > top) {
                    top = v.getValue();
                    best = v.getKey();
                }
            }
            String template = null;
            int tTop = 0, tAll = 0;
            for (var v : templates.getOrDefault(e.getKey(), Map.of()).entrySet()) {
                tAll += v.getValue();
                if (v.getValue() > tTop) {
                    tTop = v.getValue();
                    template = v.getKey();
                }
            }
            int[] n = e.getValue();
            out.put(e.getKey(), new Evidence(n[0], n[1], n[2], best, top, template, tTop, tAll));
        }
        Evidence none = new Evidence(0, 0, 0, null, 0, null, 0, 0);
        for (GameDiff.Cls c : hop.a.classes.values()) {
            if (!hop.cAB.containsKey(c)) {
                out.putIfAbsent(c.name.replace('/', '.'), none);
            }
            for (GameDiff.Meth m : c.methods.values()) {
                if (!hop.mAB.containsKey(m)) {
                    out.putIfAbsent(m.owner.name.replace('/', '.') + "." + m.name + m.desc, none);
                }
            }
            for (GameDiff.Fld f : c.fields.values()) {
                if (!hop.fAB.containsKey(f)) {
                    out.putIfAbsent(f.owner.name.replace('/', '.') + "." + f.name, none);
                }
            }
        }
        return out;
    }

    /** The Exposure key of what a reference names, when that is a class, method or field of a with no link to b. */
    String unlinkedKey(GameDiff.Ref r) {
        if (r.kind() == 'k') {
            return null;
        }
        if (r.kind() == 'c') {
            GameDiff.Cls c = hop.a.classes.get(r.owner());
            return c == null || hop.cAB.containsKey(c) ? null : c.name.replace('/', '.');
        }
        GameDiff.Meth m = null;
        GameDiff.Fld f = null;
        // A reference names the class it was compiled against; the member may be declared by a supertype.
        for (GameDiff.Cls c = hop.a.classes.get(r.owner()); c != null && m == null && f == null; c = c.sup == null ? null : hop.a.classes.get(c.sup)) {
            if (r.kind() == 'm') {
                m = c.methods.get(r.name() + r.desc());
            } else {
                f = c.fields.get(r.name());
            }
        }
        if (m != null) {
            return hop.mAB.containsKey(m) ? null : m.owner.name.replace('/', '.') + "." + m.name + m.desc;
        }
        return f == null || hop.fAB.containsKey(f) ? null : f.owner.name.replace('/', '.') + "." + f.name;
    }
}

import java.lang.constant.*;
import java.util.*;

/** Parameters the game added to a method, with the value its own callers pass for them. */
final class HopArgDefaults {

    final GameHop hop;

    HopArgDefaults(GameHop hop) {
        this.hop = hop;
    }

    /** A parameter the game added to a method, and the one value its own code passes there (a Java expression). */
    record ArgDefault(GameDiff.Meth from, GameDiff.Meth to, int index, String value, int votes, int of) {}

    /**
     * Added arguments with one value, read from the game's own calls: where a linked method gained exactly one
     * parameter, the code b inserted before each of its calls (in methods both releases have, aligned through the
     * links) is the value b passes. A method reference, a static field or a static no-argument call that two calls
     * agree on, at 80%, is the value (1.21.2: GuiGraphics.blitSprite(RenderType::guiTextured, ...)).
     */
    List<ArgDefault> argDefaults(List<HopRewrites.Rewrite> rewrites) {
        Map<List<GameDiff.Meth>, Map<String, Integer>> votes = new HashMap<>();
        Map<List<GameDiff.Meth>, Integer> totals = new HashMap<>();
        for (var e : hop.mAB.entrySet()) {
            GameDiff.Meth x = e.getKey();
            GameDiff.Meth y = e.getValue();
            if (x.refs.length == 0 || y.refs.length == 0) {
                continue;
            }
            long[] ta = hop.hashed(x, true);
            long[] tb = hop.hashed(y, false);
            // What a's code called at each place of b's: the same method linked, or another the game rewrote it to.
            Map<Integer, GameDiff.Ref> before = new HashMap<>();
            for (int[] p : GameHop.align(ta, tb, x.ops.equals(y.ops))) {
                before.put(p[1], x.refs[p[0]]);
            }
            for (int[] g : GameHop.gaps(ta, tb)) {
                if (g[1] != g[0] || g[3] - g[2] < 1 || g[3] - g[2] > 2) {
                    continue; // only code b inserted, one or two references long
                }
                // The first call after it that both sides make, to a method that gained one parameter.
                for (int j = g[3]; j < y.refs.length && j < g[3] + 6; j++) {
                    GameDiff.Ref rb = y.refs[j];
                    if (rb.kind() != 'm') {
                        continue;
                    }
                    GameDiff.Meth mb = hop.b.method(rb.owner(), rb.name(), rb.desc());
                    GameDiff.Meth ma = mb == null ? null : hop.mBA.get(mb);
                    GameDiff.Ref ra = before.get(j);
                    if (ma == null && ra != null && ra.kind() == 'm') {
                        ma = hop.a.method(ra.owner(), ra.name(), ra.desc());
                    }
                    if (ma == null || mb == null || !gainedOne(ma, mb)) {
                        continue;
                    }
                    String value = valueOf(Arrays.copyOfRange(y.refs, g[2], g[3]));
                    List<GameDiff.Meth> pair = List.of(ma, mb);
                    totals.merge(pair, 1, Integer::sum);
                    if (value != null) {
                        votes.computeIfAbsent(pair, k -> new HashMap<>()).merge(value, 1, Integer::sum);
                    }
                    break;
                }
            }
        }
        List<ArgDefault> out = new ArrayList<>();
        for (var e : votes.entrySet()) {
            String best = null;
            int c1 = 0;
            for (var v : e.getValue().entrySet()) {
                if (v.getValue() > c1) {
                    c1 = v.getValue();
                    best = v.getKey();
                }
            }
            int total = totals.get(e.getKey());
            if (c1 >= 2 && c1 * 10 >= total * 8) {
                GameDiff.Meth ma = e.getKey().get(0), mb = e.getKey().get(1);
                out.add(new ArgDefault(ma, mb, addedIndex(ma, mb), best, c1, total));
            }
        }
        out.addAll(passedByNewCalls(rewrites, out));
        out.sort(Comparator.comparing((ArgDefault d) -> d.to().sig()));
        return out;
    }

    /**
     * Added arguments that b's own calls fill with one method reference, counted over every call b makes to the new
     * method. For a method the game changed in more than one way at once, its old and new calls do not line up, so
     * argDefaults() sees no inserted code (1.21.2: GuiGraphics.blit took a Function and float texture coordinates
     * together); its new calls still show the value (RenderType::guiTextured at 38 of 46). The value is the method
     * reference created last before the call in the same statement that makes the added parameter's
     * functional interface; at 80% of all calls, as above.
     */
    List<ArgDefault> passedByNewCalls(List<HopRewrites.Rewrite> rewrites, List<ArgDefault> known) {
        Map<GameDiff.Meth, GameDiff.Meth> pairs = new LinkedHashMap<>();
        for (HopRewrites.Rewrite r : rewrites) {
            if (r.to() != null && gainedOne(r.from(), r.to())) {
                pairs.put(r.to(), r.from());
            }
        }
        for (ArgDefault d : known) {
            pairs.remove(d.to());
        }
        Map<GameDiff.Meth, Map<String, Integer>> votes = new LinkedHashMap<>();
        Map<GameDiff.Meth, Integer> totals = new LinkedHashMap<>();
        for (GameDiff.Cls c : hop.b.classes.values()) {
            for (GameDiff.Meth y : c.methods.values()) {
                for (int j = 0; j < y.refs.length; j++) {
                    GameDiff.Ref call = y.refs[j];
                    GameDiff.Meth mb = call.kind() == 'm' ? hop.b.method(call.owner(), call.name(), call.desc()) : null;
                    if (mb == null || !pairs.containsKey(mb)) {
                        continue;
                    }
                    totals.merge(mb, 1, Integer::sum);
                    String value = null;
                    String sam = samName(MethodTypeDesc.ofDescriptor(mb.desc).parameterList().get(addedIndex(pairs.get(mb), mb)).descriptorString());
                    // Back to the statement before (a call returning nothing ends one); number constants count as references.
                    for (int k = j - 2; k >= Math.max(0, j - 32); k--) {
                        GameDiff.Ref r = y.refs[k];
                        if (r.kind() == 'm' && r.desc() != null && r.desc().endsWith(")V")) {
                            break;
                        }
                        // The reference must make the added parameter's functional interface, not a later argument's.
                        if (r.kind() == 'k' && String.valueOf(r.owner()).equals("indy:" + sam)) {
                            value = valueOf(new GameDiff.Ref[] { r, y.refs[k + 1] });
                            break;
                        }
                    }
                    if (value != null) {
                        votes.computeIfAbsent(mb, x -> new HashMap<>()).merge(value, 1, Integer::sum);
                    }
                }
            }
        }
        List<ArgDefault> out = new ArrayList<>();
        for (var e : votes.entrySet()) {
            var best = e.getValue().entrySet().stream().max(Map.Entry.comparingByValue()).get();
            int total = totals.get(e.getKey());
            GameDiff.Meth ma = pairs.get(e.getKey());
            int index = addedIndex(ma, e.getKey());
            if (index >= 0 && best.getValue() >= 2 && best.getValue() * 10 >= total * 8) {
                out.add(new ArgDefault(ma, e.getKey(), index, best.getKey(), best.getValue(), total));
            }
        }
        return out;
    }

    /** The name of the one abstract method of a functional interface (b's own or the JDK's), or null. */
    String samName(String type) {
        if (!type.startsWith("L")) {
            return null;
        }
        String internal = type.substring(1, type.length() - 1);
        Set<String> names = new HashSet<>();
        GameDiff.Cls c = hop.b.classes.get(internal);
        if (c != null) {
            for (GameDiff.Meth m : c.methods.values()) {
                if ((m.flags & 0x0400) != 0) {
                    names.add(m.name);
                }
            }
        } else if (internal.startsWith("java/")) {
            try {
                for (java.lang.reflect.Method m : Class.forName(internal.replace('/', '.'), false, null).getMethods()) {
                    if (java.lang.reflect.Modifier.isAbstract(m.getModifiers()) && !Set.of("equals", "hashCode", "toString").contains(m.getName())) {
                        names.add(m.getName());
                    }
                }
            } catch (ClassNotFoundException | LinkageError absent) {
                return null;
            }
        }
        return names.size() == 1 ? names.iterator().next() : null;
    }

    /** b's method takes a's parameters (after the map's renames) with exactly one more, inserted anywhere. */
    boolean gainedOne(GameDiff.Meth ma, GameDiff.Meth mb) {
        return addedIndex(ma, mb) >= 0;
    }

    int addedIndex(GameDiff.Meth ma, GameDiff.Meth mb) {
        List<String> before = MapFix.paramTypes(ma.desc), after = MapFix.paramTypes(hop.descBA(mb.desc));
        if (after.size() != before.size() + 1) {
            return -1;
        }
        for (int i = 0; i < after.size(); i++) {
            List<String> rest = new ArrayList<>(after);
            rest.remove(i);
            if (rest.equals(before)) {
                return i;
            }
        }
        return -1;
    }

    /** Java for a value b's code made from these references: Owner::method, Owner.FIELD or Owner.method(). */
    String valueOf(GameDiff.Ref[] refs) {
        String owner;
        if (refs.length == 2 && refs[0].kind() == 'k' && refs[0].owner().startsWith("indy:") && refs[1].kind() == 'm') {
            owner = refs[1].owner();
            return owner.startsWith("net/minecraft/") || owner.startsWith("com/mojang/")
                ? owner.replace('/', '.').replace('$', '.') + "::" + refs[1].name()
                : null;
        }
        if (refs.length != 1) {
            return null;
        }
        GameDiff.Ref r = refs[0];
        owner = r.owner() == null ? "" : r.owner().replace('/', '.').replace('$', '.');
        if (!owner.startsWith("net.minecraft.") && !owner.startsWith("com.mojang.")) {
            return null;
        }
        if (r.kind() == 'f') {
            GameDiff.Fld f = hop.b.field(r.owner(), r.name());
            return f != null && (f.flags & GameDiff.ACC_STATIC) != 0 ? owner + "." + r.name() : null;
        }
        if (r.kind() == 'm' && r.desc().startsWith("()")) {
            GameDiff.Meth m = hop.b.method(r.owner(), r.name(), r.desc());
            return m != null && m.isStatic() ? owner + "." + r.name() + "()" : null;
        }
        return null;
    }
}

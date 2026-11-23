import java.util.*;

/**
 * Two bodies of one method, the old release's (x) and the new one's (y), lined up statement by statement as
 * GameDiff.Learner lines them up: equal statements (through the route map) are anchors, and in the gaps between them
 * a statement of x that uses a hole parameter is matched against each of y's; where it matches everywhere else, y's
 * code at each use of the parameter is reported. same maps old parameter slots to the new slots of the same values.
 */
final class Lining {
    final JarPort port;

    final Map<Integer, Integer> holes, same;
    final Map<GameDiff.Node, Long> hashX = new IdentityHashMap<>(), hashY = new IdentityHashMap<>();
    int budget;

    Lining(JarPort port, Map<Integer, Integer> holes, Map<Integer, Integer> same) {
        this.port = port;
        this.holes = holes;
        this.same = same;
    }

    boolean hole(GameDiff.Node n) {
        return n.op == 'p' && n.value instanceof Integer s && holes.containsKey(s);
    }

    void pair(GameDiff.Body x, GameDiff.Body y, java.util.function.BiConsumer<Integer, GameDiff.Node> found) {
        long[] tx = new long[x.statements.size()], ty = new long[y.statements.size()];
        for (int i = 0; i < tx.length; i++) {
            tx[i] = hash(x.statements.get(i), true);
        }
        for (int j = 0; j < ty.length; j++) {
            ty[j] = hash(y.statements.get(j), false);
        }
        List<int[]> bounds = new ArrayList<>(LearnPairing.matches(tx, ty));
        bounds.add(new int[] { tx.length, ty.length });
        int i0 = 0, j0 = 0;
        for (int[] p : bounds) {
            int na = p[0] - i0, nb = p[1] - j0;
            for (int i = i0; i < p[0] && nb > 0; i++) {
                GameDiff.Node sx = x.statements.get(i);
                if (!holed(sx)) {
                    continue;
                }
                boolean bare = sx.kids.length == 1 && sx.kids[0].kids.length == 0;
                for (int j = j0; j < p[1]; j++) {
                    if (bare && !(na == nb && j - j0 == i - i0 || nb == 1)) {
                        continue;
                    }
                    Map<GameDiff.Node, GameDiff.Node> at = new IdentityHashMap<>();
                    budget = 20_000;
                    if (match(sx, y.statements.get(j), at)) {
                        at.forEach((h, m) -> found.accept((Integer) h.value, m));
                    }
                }
            }
            i0 = p[0] + 1;
            j0 = p[1] + 1;
        }
    }

    boolean holed(GameDiff.Node n) {
        if (hole(n)) {
            return true;
        }
        for (GameDiff.Node k : n.kids) {
            if (holed(k)) {
                return true;
            }
        }
        return false;
    }

    /** A node's own part, in the new release's names: a member by its new name and descriptor. */
    String head(GameDiff.Node n, boolean old) {
        String ref = n.ref == null ? "" : switch (n.ref.kind()) {
            case 'c' -> old ? port.cls(n.ref.owner()) : n.ref.owner();
            case 'f' -> old ? port.route.field(n.ref.owner(), n.ref.name())[1] : n.ref.name();
            case 'm' -> {
                if (!old) {
                    yield n.ref.name() + n.ref.desc();
                }
                String[] moved = port.route.method(n.ref.owner(), n.ref.name(), n.ref.desc());
                yield moved[1] + moved[2];
            }
            default -> n.ref.owner();
        };
        return switch (n.op) {
            case 'p' -> (old ? same.getOrDefault((Integer) n.value, -1) : n.value) + ":" + (old ? port.desc(n.type) : n.type);
            case 'l' -> n.value + ":" + (old ? port.desc(n.type) : n.type);
            case 'k' -> n.type + ":" + n.value;
            case 'x' -> n.type;
            case 'o', 'S', 'y' -> n.value + ref;
            default -> String.valueOf(n.value) + ref;
        };
    }

    long hash(GameDiff.Node n, boolean old) {
        Map<GameDiff.Node, Long> memo = old ? hashX : hashY;
        Long known = memo.get(n);
        if (known != null) {
            return known;
        }
        long h;
        if (old && hole(n) || BodyDecoder.phi(n)) {
            h = 0x9E3779B97F4A7C15L * (System.identityHashCode(n) + 1L) + (old ? 0 : 1);
        } else {
            h = GameDiff.fnv(n.op + head(n, old));
            for (GameDiff.Node k : n.kids) {
                h = h * 1_000_003L + hash(k, old);
            }
        }
        memo.put(n, h);
        return h;
    }

    boolean match(GameDiff.Node x, GameDiff.Node y, Map<GameDiff.Node, GameDiff.Node> at) {
        if (--budget < 0) {
            return false;
        }
        if (hole(x)) {
            GameDiff.Node had = at.putIfAbsent(x, y);
            return had == null || had == y;
        }
        if (BodyDecoder.phi(x) || BodyDecoder.phi(y)) {
            return false;
        }
        if (hash(x, true) == hash(y, false)) {
            return true;
        }
        if (x.op != y.op || x.kids.length != y.kids.length || !head(x, true).equals(head(y, false))) {
            return false;
        }
        for (int i = 0; i < x.kids.length; i++) {
            if (!match(x.kids[i], y.kids[i], at)) {
                return false;
            }
        }
        return true;
    }
}

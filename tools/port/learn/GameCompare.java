import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

// ------------------------------------------------------------------ validation against intermediary

/**
 * Our linking of an obfuscated pair, scored against VersionMap's intermediary diff of the same pair.
 * A claim is correct when it names the same target as intermediary. A wrong claim is a conflict when
 * intermediary links that thing elsewhere (or gives our target to something else); otherwise
 * intermediary has no link there at all and cannot judge it (unlinked). Where intermediary gives a
 * class a new id but carries most of its members to one class, that class is intermediary's answer.
 */
final class GameCompare {

    static final class Tally {

        int claims;
        int truth;
        int correct;
        int found;
        int conflicts;
        final List<String> wrong = new ArrayList<>();
        final List<String> missed = new ArrayList<>();

        void add(boolean mine, boolean truthSays, boolean agree, boolean conflict, String what) {
            if (mine) {
                claims++;
                if (agree) {
                    correct++;
                } else if (conflict) {
                    conflicts++;
                    wrong.add("conflict " + what);
                } else {
                    wrong.add("unlinked " + what);
                }
            }
            if (truthSays) {
                truth++;
                if (agree) {
                    found++;
                } else if (!mine) {
                    missed.add(what);
                }
            }
        }

        String line(String name) {
            return String.format(
                Locale.ROOT,
                "  %-18s claimed %6d  agree %6d  conflict %5d  unlinked %5d  precision %6.2f%% (judged %6.2f%%)  truth %6d  recall %6.2f%%",
                name,
                claims,
                correct,
                conflicts,
                claims - correct - conflicts,
                claims == 0 ? 100.0 : 100.0 * correct / claims,
                correct + conflicts == 0 ? 100.0 : 100.0 * correct / (correct + conflicts),
                truth,
                truth == 0 ? 100.0 : 100.0 * found / truth
            );
        }
    }

    static void run(String va, String vb, boolean nonPrivate, Path report) throws Exception {
        long t0 = System.nanoTime();
        GameDiff.Snap a = GameDiff.load(va, (Path) null, null);
        GameDiff.Snap b = GameDiff.load(vb, (Path) null, null);
        GameHop h = new GameHop(a, b);
        h.run();
        double secs = (System.nanoTime() - t0) / 1e9;
        if (b.classes.containsKey(GameDiff.WHY)) {
            GameDiff.Cls back = h.cBA.get(b.classes.get(GameDiff.WHY));
            System.out.println("    why-b " + GameDiff.WHY + " <- " + (back == null ? null : back.name + " (" + h.how.get(back) + ")"));
        }
        VersionMap.Table ta = VersionMap.Table.load(va, "mojmap");
        VersionMap.Table tb = VersionMap.Table.load(vb, "mojmap");
        Map<String, String> classInter = new HashMap<>();
        ta.classes.forEach((i, n) -> classInter.put(n, i));
        Map<String, String> methodKey = new HashMap<>();
        ta.methods.forEach((k, m) -> methodKey.put(m.owner() + "." + m.name() + m.desc(), k));
        Map<String, String> fieldKey = new HashMap<>();
        ta.fields.forEach((k, m) -> fieldKey.put(m.owner() + "." + m.name(), k));
        // Intermediary's answer for every class and member we read (all of them, for the conflict check).
        Map<GameDiff.Cls, String> classTruth = new LinkedHashMap<>();
        Map<GameDiff.Meth, VersionMap.Member> methodTruth = new LinkedHashMap<>();
        Map<GameDiff.Fld, VersionMap.Member> fieldTruth = new LinkedHashMap<>();
        Set<String> classTaken = new HashSet<>();
        Set<String> methodTaken = new HashSet<>();
        Set<String> fieldTaken = new HashSet<>();
        int adjusted = 0;
        for (GameDiff.Cls ca : a.classes.values()) {
            String inter = classInter.get(ca.name);
            if (inter == null) {
                continue;
            }
            String truth = tb.classes.get(inter);
            if (truth == null) {
                truth = memberVote(ca, tb, methodKey, fieldKey);
                if (truth != null) {
                    adjusted++;
                }
            }
            classTruth.put(ca, truth);
            if (truth != null) {
                classTaken.add(truth);
            }
            for (GameDiff.Meth ma : ca.methods.values()) {
                String k = ma.special() ? null : methodKey.get(ma.sig());
                if (k != null) {
                    VersionMap.Member mt = methodTarget(tb, k);
                    methodTruth.put(ma, mt);
                    if (mt != null) {
                        methodTaken.add(mt.owner() + "." + mt.name() + mt.desc());
                    }
                }
            }
            for (GameDiff.Fld fa : ca.fields.values()) {
                String k = fieldKey.get(ca.name + "." + fa.name);
                if (k != null) {
                    VersionMap.Member ft = tb.fields.get(k);
                    fieldTruth.put(fa, ft);
                    if (ft != null) {
                        fieldTaken.add(ft.owner() + "." + ft.name());
                    }
                }
            }
        }
        Map<String, Tally> t = new LinkedHashMap<>();
        for (String k : List.of(
            "class renames",
            "class removals",
            "method renames",
            "method moves",
            "method signatures",
            "method removals",
            "field renames",
            "field removals"
        )) {
            t.put(k, new Tally());
        }
        for (GameDiff.Cls ca : a.classes.values()) {
            if (!classTruth.containsKey(ca) || (nonPrivate && (ca.flags & GameDiff.ACC_PRIVATE) != 0)) {
                continue;
            }
            String truth = classTruth.get(ca);
            if (truth != null && !b.classes.containsKey(truth)) {
                continue;
            }
            GameDiff.Cls cb = h.cAB.get(ca);
            String mine = cb == null ? null : cb.name;
            String what = ca.name + " -> mine " + mine + " truth " + truth + " (" + h.how.get(ca) + ")";
            boolean agree = Objects.equals(mine, truth);
            boolean conflict = !agree && (truth != null || classTaken.contains(mine));
            t.get("class renames").add(mine != null && !mine.equals(ca.name), truth != null && !truth.equals(ca.name), agree, conflict, what);
            t.get("class removals").add(mine == null, truth == null, agree, conflict, what);
        }
        for (GameDiff.Cls ca : a.classes.values()) {
            if (nonPrivate && (ca.flags & GameDiff.ACC_PRIVATE) != 0) {
                continue;
            }
            String truthOwner = classTruth.get(ca);
            for (GameDiff.Meth ma : ca.methods.values()) {
                if (!methodTruth.containsKey(ma) || VersionMap.Diff.noise(ma.name) || (nonPrivate && (ma.flags & GameDiff.ACC_PRIVATE) != 0)) {
                    continue;
                }
                VersionMap.Member mt = methodTruth.get(ma);
                if (mt != null && b.method(mt.owner(), mt.name(), mt.desc()) == null) {
                    continue;
                }
                GameDiff.Meth mb = h.mAB.get(ma);
                GameDiff.Cls ownerB = h.cAB.get(ca);
                boolean mineMoved = mb != null && mb.owner != ownerB;
                boolean truthMoved = mt != null && !mt.owner().equals(truthOwner);
                String mineT = mb == null ? null : mb.sig();
                String truthT = mt == null ? null : mt.owner() + "." + mt.name() + mt.desc();
                boolean agree = Objects.equals(mineT, truthT);
                boolean conflict = !agree && (truthT != null || methodTaken.contains(mineT));
                String what = ma.sig() + " -> mine " + mineT + " truth " + truthT + " (" + h.how.get(ma) + ")";
                t.get("method renames").add(
                    mb != null && !mineMoved && !mb.name.equals(ma.name),
                    mt != null && !truthMoved && !mt.name().equals(ma.name),
                    agree,
                    conflict,
                    what
                );
                t.get("method moves").add(mineMoved, truthMoved, agree, conflict, what);
                VersionMap.Member ma0 = ta.methods.get(methodKey.get(ma.sig()));
                t.get("method signatures").add(
                    mb != null && !h.descBA(mb.desc).equals(ma.desc),
                    mt != null && !mt.interDesc().equals(ma0.interDesc()),
                    agree,
                    conflict,
                    what
                );
                t.get("method removals").add(mb == null, mt == null, agree, conflict, what);
            }
            for (GameDiff.Fld fa : ca.fields.values()) {
                if (!fieldTruth.containsKey(fa) || VersionMap.Diff.noise(fa.name) || (nonPrivate && (fa.flags & GameDiff.ACC_PRIVATE) != 0)) {
                    continue;
                }
                VersionMap.Member ft = fieldTruth.get(fa);
                if (ft != null && b.field(ft.owner(), ft.name()) == null) {
                    continue;
                }
                GameDiff.Fld fb = h.fAB.get(fa);
                String mineT = fb == null ? null : fb.owner.name + "." + fb.name;
                String truthT = ft == null ? null : ft.owner() + "." + ft.name();
                boolean agree = Objects.equals(mineT, truthT);
                boolean conflict = !agree && (truthT != null || fieldTaken.contains(mineT));
                String what = ca.name + "." + fa.name + " -> mine " + mineT + " truth " + truthT + " (" + h.how.get(fa) + ")";
                t.get("field renames").add(fb != null && !fb.name.equals(fa.name), ft != null && !ft.name().equals(fa.name), agree, conflict, what);
                t.get("field removals").add(fb == null, ft == null, agree, conflict, what);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(
            String.format(
                Locale.ROOT,
                "compare %s -> %s (%s, read + link %.1fs): game bytecode vs Fabric intermediary%n",
                va,
                vb,
                nonPrivate ? "non-private" : "all members",
                secs
            )
        );
        for (var e : t.entrySet()) {
            sb.append(e.getValue().line(e.getKey())).append('\n');
        }
        sb.append(
            "  agree: same target as intermediary; conflict: intermediary links it (or our target) elsewhere;\n" +
            "  unlinked: intermediary has no link to judge by (precision counts these as wrong, judged leaves them out).\n"
        );
        sb.append("  " + adjusted + " classes got a new intermediary id but most members went to one class: that class is the truth\n");
        System.out.print(sb);
        StringBuilder detail = new StringBuilder(sb);
        for (var e : t.entrySet()) {
            for (String w : e.getValue().wrong) {
                detail.append("wrong  ").append(e.getKey()).append(": ").append(w).append('\n');
            }
            for (String w : e.getValue().missed) {
                detail.append("missed ").append(e.getKey()).append(": ").append(w).append('\n');
            }
        }
        if (report != null) {
            if (report.toAbsolutePath().getParent() != null) {
                Files.createDirectories(report.toAbsolutePath().getParent());
            }
            Files.writeString(report, detail, StandardCharsets.UTF_8);
            System.out.println("  every disagreement: " + report);
        }
    }

    static VersionMap.Member methodTarget(VersionMap.Table tb, String k) {
        VersionMap.Member mt = tb.methods.get(k);
        if (mt == null) {
            List<String> el = tb.methodsByInterName.get(k.substring(k.indexOf('.') + 1));
            if (el != null && !el.isEmpty()) {
                mt = tb.methods.get(el.get(0));
            }
        }
        return mt;
    }

    /** The one class most of ca's members went to, by intermediary's own member ids. */
    static String memberVote(GameDiff.Cls ca, VersionMap.Table tb, Map<String, String> mk, Map<String, String> fk) {
        Map<String, Integer> votes = new HashMap<>();
        int members = 0;
        for (GameDiff.Meth m : ca.methods.values()) {
            String k = m.special() ? null : mk.get(m.sig());
            if (k == null || !k.substring(k.indexOf('.') + 1).startsWith("method_")) {
                continue;
            }
            members++;
            VersionMap.Member t = methodTarget(tb, k);
            if (t != null) {
                votes.merge(t.owner(), 1, Integer::sum);
            }
        }
        for (GameDiff.Fld f : ca.fields.values()) {
            String k = fk.get(ca.name + "." + f.name);
            if (k == null) {
                continue;
            }
            members++;
            VersionMap.Member t = tb.fields.get(k);
            if (t != null) {
                votes.merge(t.owner(), 1, Integer::sum);
            }
        }
        for (var e : votes.entrySet()) {
            if (e.getValue() >= 2 && e.getValue() * 10 >= members * 6) {
                return e.getKey();
            }
        }
        return null;
    }
}

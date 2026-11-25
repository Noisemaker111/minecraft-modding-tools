import java.io.*;
import java.util.*;
import java.util.regex.*;
import org.objectweb.asm.*;

/** The check a ported class gets before it is written: verified as the JVM will, and its failures blamed on the reference that caused them. */
final class PortVerifier {

    final JarPort port;

    PortVerifier(JarPort port) {
        this.port = port;
    }

    // ---- Verifying: the ported classes as the JVM's verifier sees them on the new release.

    final Set<String> unfit = new TreeSet<>(); // removed classes (old names) whose route made a class of the jar being ported fail the verifier

    /**
     * Ports a jar, and again without each route the verifier found unfit for it (verify), until none is. A route moves a
     * removed class to the class the game put in its place (GuiComponent to GuiGraphics in 1.20), which is not always one a
     * mod's code can use as the old one: a subclass or a value passed as the old class's supertype fails the verifier.
     * Without the route the old class is a stand-in's where one is written for its update (knowledge/standins), and is
     * otherwise reported gone. A mod whose use of the class verifies keeps the route.
     *
     * A route is also left out where the mod still names members of the removed class that nothing answered through it
     * and a stand-in is written for the class (1.20's GuiGraphics on 1.19.4, which the route moves to the PoseStack it
     * holds): the stand-in is the class for that mod, kept only when the port then leaves fewer references unlinked.
     */
    byte[] fitting(byte[] jar, String name, List<Map<String, Object>> results) throws IOException {
        Set<String> dropped = new TreeSet<>(), answered = new TreeSet<>(), tried = new TreeSet<>();
        byte[] kept = null;
        List<Map<String, Object>> keptResults = null;
        Set<String> keptDropped = null, keptAnswered = null;
        for (int round = 0; ; round++) {
            int before = results.size();
            unfit.clear();
            byte[] out = without(dropped, () -> port.port(jar, name, results, null));
            List<Map<String, Object>> made = new ArrayList<>(results.subList(before, results.size()));
            if (kept != null && unlinked(made) >= unlinked(keptResults)) {
                // The stand-in answered no more than the route did: the port with the route stands.
                results.subList(before, results.size()).clear();
                results.addAll(keptResults);
                out = kept;
                made = keptResults;
                dropped = keptDropped;
                answered = keptAnswered;
                unfit.clear();
            }
            kept = null;
            Set<String> standIn = round == 4 ? Set.of() : unanswered(made, dropped);
            standIn.removeAll(tried);
            if ((dropped.containsAll(unfit) && standIn.isEmpty()) || round == 4) {
                if (!dropped.isEmpty() && !made.isEmpty()) {
                    @SuppressWarnings("unchecked") Map<String, Integer> changed = (Map<String, Integer>) made.get(made.size() - 1).get("changed");
                    for (String old : dropped) {
                        changed.merge((answered.contains(old) ? "class route left out, its stand-in answers the mod's uses: " : "class route left out, the verifier refuses it: ")
                            + Exposure.dotted(old) + " -> " + Exposure.dotted(port.cls(old)), 1, Integer::sum);
                    }
                }
                return out;
            }
            boolean settled = dropped.containsAll(unfit);
            dropped = new TreeSet<>(dropped);
            if (settled) {
                // The verifier is content: try the stand-ins, keeping this port to fall back on.
                kept = out;
                keptResults = made;
                keptDropped = new TreeSet<>(dropped);
                keptAnswered = new TreeSet<>(answered);
                tried.addAll(standIn);
                answered = new TreeSet<>(answered);
                answered.addAll(standIn);
                dropped.addAll(standIn);
            }
            dropped.addAll(unfit);
            results.subList(before, results.size()).clear();
        }
    }

    /** References a port left unlinked, over the jar and the jars it nests. */
    static int unlinked(List<Map<String, Object>> made) {
        int n = 0;
        for (Map<String, Object> r : made) {
            n += r.get("unlinked") == null ? 0 : Json.array(r.get("unlinked")).size();
        }
        return n;
    }

    /** Removed classes still routed whose members the port left unlinked and for which a stand-in is written. */
    Set<String> unanswered(List<Map<String, Object>> made, Set<String> dropped) {
        Set<String> out = new TreeSet<>();
        for (var e : port.classMap.entrySet()) {
            String old = e.getKey();
            if (dropped.contains(old) || e.getValue().startsWith(StandinClasses.STANDIN) || port.b.classes.containsKey(old)
                || !port.standIn.standins.containsKey(StandinClasses.STANDIN + old)) {
                continue;
            }
            // A member of the class, or a member that takes or gives it (SignBlockEntity.getFrontText()L.../SignText;).
            Pattern member = Pattern.compile(Pattern.quote(Exposure.dotted(old)) + "\\.[\\w$<]|" + Pattern.quote("L" + old + ";"));
            for (Map<String, Object> r : made) {
                for (Object u : r.get("unlinked") == null ? List.of() : Json.array(r.get("unlinked"))) {
                    if (member.matcher(u.toString()).find()) {
                        out.add(old);
                    }
                }
            }
        }
        return out;
    }

    interface Porting {
        byte[] run() throws IOException;
    }

    /** Runs a port with the routes of the removed classes dropped left out (a stand-in for one standing in instead). */
    byte[] without(Set<String> dropped, Porting body) throws IOException {
        if (dropped.isEmpty()) {
            return body.run();
        }
        Map<String, String> map = new HashMap<>(port.classMap), back = new HashMap<>(port.inverse), routed = new HashMap<>(port.route.classes), rewritten = new HashMap<>(port.route.classRewrites);
        Map<String, String> supers = new HashMap<>(port.links.successors);
        Map<String, Optional<String[]>> taken = new HashMap<>(port.overrides.takeovers), statics = new HashMap<>(port.overrides.movedStatics);
        Map<String, Optional<GameDiff.Meth>> code = new HashMap<>(port.mixinMovedCode.movedCode);
        for (String old : dropped) {
            String to = port.classMap.remove(old);
            port.route.classes.remove(old);
            port.route.classRewrites.remove(old);
            if (to != null && old.equals(port.inverse.get(to))) {
                port.inverse.remove(to);
                port.classMap.forEach((k, v) -> {
                    if (v.equals(to)) {
                        port.inverse.putIfAbsent(to, k);
                    }
                });
            }
            if (port.standIn.standins.containsKey(StandinClasses.STANDIN + old)) {
                port.classMap.put(old, StandinClasses.STANDIN + old);
                port.inverse.put(StandinClasses.STANDIN + old, old);
            }
        }
        port.links.successors.clear();
        port.overrides.takeovers.clear();
        port.overrides.movedStatics.clear();
        port.mixinMovedCode.movedCode.clear();
        try {
            return body.run();
        } finally {
            port.classMap.clear();
            port.classMap.putAll(map);
            port.inverse.clear();
            port.inverse.putAll(back);
            port.route.classes.clear();
            port.route.classes.putAll(routed);
            port.route.classRewrites.clear();
            port.route.classRewrites.putAll(rewritten);
            port.links.successors.clear();
            port.links.successors.putAll(supers);
            port.overrides.takeovers.clear();
            port.overrides.takeovers.putAll(taken);
            port.overrides.movedStatics.clear();
            port.overrides.movedStatics.putAll(statics);
            port.mixinMovedCode.movedCode.clear();
            port.mixinMovedCode.movedCode.putAll(code);
        }
    }

    static final Pattern TYPE_NAME = Pattern.compile("[\\w$]+(?:/[\\w$]+)+");

    /**
     * Every ported class run through the JVM's verifier against the new release (Exposure.verify), and each failure the port
     * made reported: a method that verified against the old release and fails against the new one throws VerifyError in
     * game though every reference in it links. A failure that names the class a route moved a removed class to marks that
     * route unfit for this jar (fitting), whatever else the jar lacks.
     */
    void verify(JarPort.Mod mod, Map<String, byte[]> ported, Map<String, byte[]> read) {
        // A failure in a jar that already has a reference that does not link is mostly that reference, left as written.
        boolean report = mod.unlinked.isEmpty();
        Map<String, GameDiff.Cls> now = headers(mod, ported, true), was = headers(mod, read, false);
        Map<String, Set<String>> routedTo = new HashMap<>(); // a class of the new release -> removed classes routed to it
        for (var e : port.classMap.entrySet()) {
            if (port.a.classes.containsKey(e.getKey()) && !port.b.classes.containsKey(e.getKey()) && !e.getValue().startsWith(StandinClasses.STANDIN)) {
                routedTo.computeIfAbsent(e.getValue(), k -> new TreeSet<>()).add(e.getKey());
            }
        }
        for (var e : ported.entrySet()) {
            byte[] bytes = e.getValue();
            // Before Java 6's class files the JVM verifies by inference, which the JDK's copy of the verifier does not do.
            if (bytes.length < 8 || ((bytes[6] & 0xFF) << 8 | (bytes[7] & 0xFF)) < 50) {
                continue;
            }
            List<String> errors = Exposure.verify(bytes, n -> known(now, port.b, n));
            if (errors.isEmpty()) {
                continue;
            }
            byte[] before = read.get(e.getKey());
            Set<String> failedBefore = new HashSet<>();
            if (before != null) {
                for (String x : Exposure.verify(before, n -> known(was, port.a, n))) {
                    failedBefore.add(verifiedMethod(x));
                }
            }
            for (String x : errors) {
                if (failedBefore.contains(verifiedMethod(x))) {
                    continue;
                }
                unfit.addAll(blamed(x, routedTo, was));
                if (Sites.WHERE) {
                    System.err.println("verifier: " + x);
                }
                if (report) {
                    mod.unlinked.add("does not verify: " + verifyReason(x));
                }
            }
        }
    }

    static final Pattern NOT_ASSIGNABLE = Pattern.compile("\\(([\\w/$]+) is not assignable from ([\\w/$]+)\\)");

    /**
     * The routes a verifier line blames: where a value is not of the type wanted, the route that made the value's class
     * (TranslatableComponent, now TranslatableContents, passed as a MutableComponent), else the one that made the wanted
     * type; where the line names no types, the routes of the classes it names; and the route of the superclass of the class
     * that fails, which a subclass of a routed class (a mod's own TranslatableComponent) no longer extends as it did.
     */
    Set<String> blamed(String line, Map<String, Set<String>> routedTo, Map<String, GameDiff.Cls> was) {
        Set<String> out = new TreeSet<>();
        Matcher n = NOT_ASSIGNABLE.matcher(line);
        if (n.find()) {
            out.addAll(routedTo.getOrDefault(n.group(2), Set.of()));
            if (out.isEmpty()) {
                out.addAll(routedTo.getOrDefault(n.group(1), Set.of()));
            }
        } else {
            Matcher t = TYPE_NAME.matcher(line);
            while (t.find()) {
                out.addAll(routedTo.getOrDefault(t.group(), Set.of()));
            }
        }
        Matcher m = Exposure.VERIFIED_METHOD.matcher(line);
        GameDiff.Cls own = m.find() ? was.get(m.group(1)) : null;
        if (own != null && own.sup != null && routedTo.values().stream().anyMatch(olds -> olds.contains(own.sup))) {
            out.add(own.sup);
        }
        return out;
    }

    /**
     * The classes of a jar as the verifier's class hierarchy needs them: name, access, superclass, interfaces. A mixin's
     * code runs as its target's, where this is the target: it is read as the target's subclass (with several targets, as
     * any of them), so what the port points at the target ("this.field" moved to the target's renamed field) verifies as
     * it will once merged.
     */
    Map<String, GameDiff.Cls> headers(JarPort.Mod mod, Map<String, byte[]> classes, boolean ported) {
        Map<String, GameDiff.Cls> out = new HashMap<>();
        for (var e : classes.entrySet()) {
            if (!e.getKey().endsWith(".class")) {
                continue;
            }
            try {
                ClassReader r = new ClassReader(e.getValue());
                String sup = r.getSuperName();
                List<String> targets = mod.mixinTargets.getOrDefault(r.getClassName(), List.of());
                if (!targets.isEmpty() && (r.getAccess() & Opcodes.ACC_INTERFACE) == 0) {
                    sup = targets.size() > 1 ? null : ported ? port.cls(targets.get(0)) : targets.get(0);
                }
                out.put(r.getClassName(), new GameDiff.Cls(r.getClassName(), r.getAccess(), sup, List.of(r.getInterfaces()), false));
            } catch (RuntimeException unreadable) {
                // Not a class file the verifier could read either; it reports it.
            }
        }
        return out;
    }

    static GameDiff.Cls known(Map<String, GameDiff.Cls> own, GameDiff.Snap release, String name) {
        GameDiff.Cls c = own.get(name);
        c = c != null ? c : release.classes.get(name);
        return c != null ? c : release.libraries.get(name);
    }

    /** Which method a verifier line is about, "Class::name", so a failure the jar had before the port is told apart. */
    static String verifiedMethod(String line) {
        Matcher m = Exposure.VERIFIED_METHOD.matcher(line);
        return m.find() ? m.group(1) + "::" + m.group(2) : line;
    }

    /** A verifier line as the report shows it: its reason and the types it names, then where (in Class.method). */
    static String verifyReason(String line) {
        String first = line.lines().findFirst().orElse(line).trim();
        Matcher m = Exposure.VERIFIED_METHOD.matcher(first);
        if (!m.find()) {
            return first;
        }
        String reason = first.substring(0, m.start()).trim();
        String rest = first.substring(m.end()).replaceFirst("^[^)]*\\)\\s*(@\\d+)?\\s*", "").trim();
        return reason + (rest.isEmpty() ? "" : ": " + Exposure.dotted(rest)) + " (in " + Exposure.dotted(m.group(1)) + "." + m.group(2) + ")";
    }
}

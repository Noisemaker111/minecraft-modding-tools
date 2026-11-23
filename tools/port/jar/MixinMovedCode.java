import java.util.*;
import java.util.regex.*;
import org.objectweb.asm.*;

/** Mixin targets whose code the game moved to another method: where the injected code went. */
final class MixinMovedCode {

    final JarPort port;

    MixinMovedCode(JarPort port) {
        this.port = port;
    }

    /** Whether most (four in five) of a body's instructions, at least six of them, are also in another's, counted as multisets. */
    static boolean within(String part, String whole) {
        Map<String, Integer> left = new HashMap<>();
        for (String t : GameCode.instructions(whole)) {
            left.merge(t, 1, Integer::sum);
        }
        String[] mine = GameCode.instructions(part);
        int common = 0;
        for (String t : mine) {
            Integer n = left.get(t);
            if (n != null && n > 0) {
                common++;
                left.put(t, n - 1);
            }
        }
        return mine.length >= 6 && common * 5 >= mine.length * 4;
    }

    /** The share of the longer of two bodies' instructions the other has too, counted as multisets. */
    static double likeness(String x, String y) {
        Map<String, Integer> left = new HashMap<>();
        String[] a = GameCode.instructions(x), b = GameCode.instructions(y);
        for (String t : a) {
            left.merge(t, 1, Integer::sum);
        }
        int common = 0;
        for (String t : b) {
            Integer n = left.get(t);
            if (n != null && n > 0) {
                common++;
                left.put(t, n - 1);
            }
        }
        return Math.max(a.length, b.length) == 0 ? 0 : (double) common / Math.max(a.length, b.length);
    }

    final Map<String, Optional<GameDiff.Meth>> movedCode = new HashMap<>();

    /**
     * Where the code of a method the update dropped from its class (old names) went: the one method of the new class whose
     * instructions are mostly the old method's (alike) and were not before, as when an update renames a method and changes
     * its parameters at once. Where several read alike, the one that shares four in five of its instructions while no
     * other does. Null when none, or no clear one, reads alike, or the old body is too short to tell one apart.
     */
    GameDiff.Meth codeMoved(String owner, String name, String desc) {
        return movedCode.computeIfAbsent(owner + "." + name + desc, k -> {
            GameCode oldCode = port.code(port.a), newCode = port.code(port.b);
            GameDiff.Cls now = port.b.classes.get(port.cls(owner));
            Map<String, String> before = oldCode == null ? null : oldCode.bodies(owner, port::cls);
            Map<String, String> after = newCode == null || now == null ? null : newCode.bodies(port.cls(owner), x -> x);
            String was = before == null || after == null ? null : before.get(name + port.desc(desc));
            if (was == null || GameCode.instructions(was).length < 6) {
                return Optional.empty();
            }
            GameDiff.Meth found = null;
            double best = 0, next = 0;
            for (GameDiff.Meth m : now.methods.values()) {
                // A method the class kept counts when it took the dropped one's code in (1.21.2 put _blitToScreen's code
                // into the kept blitToScreen and the new blitAndBlendToScreen): then two read alike and neither is followed.
                String body = after.get(m.name + m.desc), had = before.get(m.name + m.desc);
                if (m.name.startsWith("<") || body == null || !Overrides.alike(was, body) || had != null && Overrides.alike(was, had)) {
                    continue;
                }
                double like = likeness(was, body);
                if (like > best) {
                    next = best;
                    best = like;
                    found = m;
                } else {
                    next = Math.max(next, like);
                }
            }
            // Several read alike: only a clear winner took the code (1.21.2's setShaderTexture(int, ResourceLocation) is
            // _setShaderTexture's body plus one call; setShaderTexture(int, int) shares half of it).
            return next > 0 && (best < 0.8 || next >= 0.8) ? Optional.empty() : Optional.ofNullable(found);
        }).orElse(null);
    }

    /**
     * An injector whose points all left the methods it names, followed to the one method of the class where the code
     * around them went: a private helper the new method calls (1.21.2's Gui.renderPlayerHealth calls the new
     * renderAirBubbles, which reads getMaxAirSupply now), or a method the update added whose instructions are mostly the
     * old method's (1.20.5 split Gui.render into layer methods it registers by reference). Every point must be a call or
     * field use and that method must have them all (as often as the old method had, when an ordinal picks one); the
     * class must name it once (a call or a method reference), so the injector runs as often as before. Or any method of
     * the class the new method calls once, when every way into it in the new release came into the old method before
     * (entered: 1.20.5's Minecraft.disconnect(Screen) hands over to the new disconnect(Screen, boolean), which every old
     * caller of disconnect(Screen) now calls). A helper the old class had must not have had the point already (it did not
     * move there). Handlers that may cancel (cancels) are moved only into a method the new one does nothing but hand over
     * to, where ending it ends the new one. into with each method as one whose parameters changed ('s'),
     * which the handler follows only where Mixin will accept it, or null. A method whose own parameters changed too is
     * followed from its new signature.
     */
    List<MixinMembers.Found> deeper(List<String> targets, MixinRetargets.Injector inj, List<MixinMembers.Found> into, List<MixinMembers.Found> ats, boolean cancels) {
        if (into.isEmpty() || into.stream().anyMatch(f -> f.kind() != 'm' && f.kind() != 's' || f.desc() == null || f.name().startsWith("<"))
            || inj.ats().isEmpty() || inj.ats().stream().anyMatch(at -> !Set.of("INVOKE", "INVOKE_ASSIGN", "FIELD").contains(at[0]))
            || ats.stream().anyMatch(at -> at == null || at.kind() == 0 || at.desc() == null) || port.mixinPoints.pointsKept(targets, inj, into, ats)) {
            return null;
        }
        GameCode code = port.code(port.b);
        List<MixinMembers.Found> out = new ArrayList<>();
        for (int i = 0; i < into.size(); i++) {
            MixinMembers.Found f = into.get(i);
            GameDiff.Meth before = port.mixinMembers.oldMethod(targets, inj.methods().get(i));
            List<String[]> refs = code == null || before == null ? null : code.refs(f.owner(), f.name(), f.desc());
            Map<String, String> bodies = refs == null ? null : code.bodies(f.owner(), x -> x);
            if (bodies == null) {
                return null;
            }
            Map<String, String> was = port.code(port.a) == null ? null : port.code(port.a).bodies(before.owner.name, port::cls);
            String old = was == null ? null : was.get(before.name + port.desc(before.desc));
            Set<String> called = new HashSet<>();
            for (String[] r : refs) {
                if (r[0].equals("m") && r[1].equals(f.owner())) {
                    called.add(r[2] + r[3]);
                }
            }
            GameDiff.Meth found = null;
            for (GameDiff.Meth n : port.b.classes.get(f.owner()).methods.values()) {
                if (n.name.startsWith("<") || n.name.equals(f.name()) && n.desc.equals(f.desc())) {
                    continue;
                }
                // Reached from the new method itself as a private helper, or a method the update added whose code is
                // mostly the old method's (1.20.5 split Gui.render into layers it registers as method references).
                boolean helper = called.contains(n.name + n.desc) && (n.flags & Opcodes.ACC_PRIVATE) != 0;
                boolean part = !helper && old != null && !was.containsKey(n.name + n.desc) && bodies.get(n.name + n.desc) != null
                    && within(bodies.get(n.name + n.desc), old);
                long calls = refs.stream().filter(r -> r[0].equals("m") && r[1].equals(f.owner()) && (r[2] + r[3]).equals(n.name + n.desc)).count();
                boolean entry = calls == 1 && entered(f, before, n);
                if (!helper && !part && !entry || cancels && !(entry && handsOver(f, n))) {
                    continue;
                }
                Matcher use = Pattern.compile(Pattern.quote(":" + f.owner() + "." + n.name + n.desc) + "(?=[\\n:]|$)").matcher(String.join("\n", bodies.values()));
                int uses = 0;
                while (use.find()) {
                    uses++;
                }
                // A helper the old class had too must not have had the point already: then the point did not move there,
                // and the injector would land on other code that was always in it.
                GameDiff.Meth had = helper && was.containsKey(n.name + n.desc) ? port.a.method(before.owner.name, n.name, port.oldDesc(n.desc)) : null;
                had = entry && !helper && was.containsKey(n.name + n.desc) ? port.a.method(before.owner.name, n.name, port.oldDesc(n.desc)) : had;
                boolean all = (uses == 1 || entry) && (!helper && !entry || !was.containsKey(n.name + n.desc) || had != null);
                for (int k = 0; all && k < ats.size(); k++) {
                    all = port.mixinPoints.pointKept(inj.ats().get(k), ats.get(k), new GameDiff.Meth[] { n, before }) && (had == null || hadNone(inj.ats().get(k), ats.get(k), had));
                }
                if (!all) {
                    continue;
                }
                if (found != null) {
                    return null;
                }
                found = n;
            }
            if (found == null) {
                return null;
            }
            String text = inj.methods().get(i);
            String prefix = text.startsWith("L") && text.indexOf(';') > 0 ? "L" + f.owner() + ";" : "";
            out.add(new MixinMembers.Found(text, null, null, 's', f.owner(), found.name, found.desc, prefix + found.name + found.desc));
        }
        return out;
    }

    /**
     * Whether every way into a method n of the new release came into the old method (before) in the old release, so an
     * injector moved from the old method into n runs where it ran: each method whose code calls n (or names it) is the new
     * method f itself, a method the old release had whose code reached the old method (directly or through two calls of
     * the game), or a method new in this release every caller of which is one of these.
     */
    boolean entered(MixinMembers.Found f, GameDiff.Meth before, GameDiff.Meth n) {
        GameCode now = port.code(port.b), then = port.code(port.a);
        Set<String> callers = now == null || then == null ? null : now.callers(n.owner.name, n.name, n.desc);
        String self = f.owner() + "." + f.name() + f.desc();
        if (callers == null || !callers.contains(self)) {
            return false;
        }
        String target = before.owner.name + "." + before.name + before.desc;
        for (String c : callers) {
            if (!c.equals(self) && !cameIn(now, then, c, target, self, 2, new HashSet<>())) {
                return false;
            }
        }
        return true;
    }

    /** One caller of entered: reached the old method in the old release, or is new and only reached from such callers. */
    boolean cameIn(GameCode now, GameCode then, String caller, String target, String self, int depth, Set<String> seen) {
        if (!seen.add(caller) || caller.equals(self)) {
            return caller.equals(self);
        }
        int dot = caller.indexOf('.'), paren = caller.indexOf('(');
        String owner = caller.substring(0, dot), name = caller.substring(dot + 1, paren), desc = caller.substring(paren);
        // A caller whose own signature changed (1.21.11's doWorldLoad took an Optional more in 26.1) is the one method of
        // its name the old class had.
        GameDiff.Cls was = port.a.classes.get(owner);
        List<GameDiff.Meth> same = was == null ? List.of() : was.methods.values().stream().filter(x -> x.name.equals(name)).toList();
        String oldDesc = was != null && then.steps(owner, name, desc) != null ? desc : same.size() == 1 && !name.startsWith("lambda$") ? same.get(0).desc : null;
        if (oldDesc != null) {
            return reaches(then, owner, name, oldDesc, target, 2, new HashSet<>());
        }
        Set<String> up = now.callers(owner, name, desc);
        if (depth == 0 || up == null || up.isEmpty()) {
            return false;
        }
        for (String c : up) {
            if (!cameIn(now, then, c, target, self, depth - 1, seen)) {
                return false;
            }
        }
        return true;
    }

    /** Whether an old-release method's code calls the method target (owner.name+desc), itself or through depth calls of the game. */
    boolean reaches(GameCode then, String owner, String name, String desc, String target, int depth, Set<String> seen) {
        List<String[]> steps = seen.add(owner + "." + name + desc) ? then.steps(owner, name, desc) : null;
        if (steps == null) {
            return false;
        }
        for (String[] x : steps) {
            if ((x[0].equals("m") || x[0].equals("l")) && (x[1] + "." + x[2] + x[3]).equals(target)) {
                return true;
            }
        }
        for (String[] x : steps) {
            if (depth > 0 && (x[0].equals("m") || x[0].equals("l")) && port.a.classes.containsKey(x[1]) && reaches(then, x[1], x[2], x[3], target, depth - 1, seen)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the new method f does nothing but hand over to n (its arguments and constants aside) and return what n gives. */
    boolean handsOver(MixinMembers.Found f, GameDiff.Meth n) {
        List<String[]> steps = port.code(port.b) == null ? null : port.code(port.b).steps(f.owner(), f.name(), f.desc());
        if (steps == null || !Type.getReturnType(f.desc()).equals(Type.getReturnType(n.desc))) {
            return false;
        }
        List<String[]> named = steps.stream().filter(x -> !x[0].equals("c")).toList();
        return named.size() == 1 && named.get(0)[0].equals("m") && (named.get(0)[1] + "." + named.get(0)[2] + named.get(0)[3]).equals(n.owner.name + "." + n.name + n.desc);
    }

    /** Whether an old-release method made no use of an @At's member (named as the @At wrote it); false when unreadable. */
    boolean hadNone(String[] point, MixinMembers.Found at, GameDiff.Meth old) {
        char kind = at.kind() == 's' || at.kind() == 'r' ? 'm' : at.kind();
        Matcher m = MixinMembers.MEMBER.matcher(point[1]);
        return m.matches() && port.mixinPoints.uses(port.code(port.a), port.a, old, kind, m.group(1), m.group(2), m.group(3) != null ? m.group(3) : m.group(4)) == 0;
    }
}

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import org.objectweb.asm.*;

/** A mixin's injection points (@At) in a target the update changed: kept, moved, or reported gone. */
final class MixinPoints {

    final JarPort port;

    MixinPoints(JarPort port) {
        this.port = port;
    }

    /**
     * Each mixin class's config's injectors.defaultRequire (0 when the config gives none, as Mixin takes it), read from
     * every mixin config in the jar: a JSON file naming a package and its mixin classes.
     */
    static final byte[] PACKAGE_KEY = "package".getBytes(StandardCharsets.ISO_8859_1), ESCAPE = "\\u".getBytes(StandardCharsets.ISO_8859_1);

    static void requires(JarPort.Mod mod, Map<String, byte[]> entries) {
        for (var e : entries.entrySet()) {
            // Only a file that can hold the key "package" is parsed: parsing every data file was a tenth of the port's time.
            if (!e.getKey().endsWith(".json") || e.getKey().contains("/lang/")
                || JarEntries.indexOf(e.getValue(), PACKAGE_KEY) < 0 && JarEntries.indexOf(e.getValue(), ESCAPE) < 0) {
                continue;
            }
            Map<String, Object> config;
            try {
                config = Json.object(Json.parse(new String(e.getValue(), StandardCharsets.UTF_8)));
            } catch (RuntimeException notJson) {
                continue;
            }
            Object pkg = config.get("package");
            if (!(pkg instanceof String p)) {
                continue;
            }
            Object injectors = config.get("injectors");
            int require = injectors instanceof Map<?, ?> m && m.get("defaultRequire") instanceof Number n ? n.intValue() : 0;
            for (String list : List.of("mixins", "client", "server")) {
                for (Object c : config.get(list) instanceof List<?> l ? l : List.of()) {
                    if (c instanceof String name) {
                        mod.requires.put((p + "." + name).replace('.', '/'), require);
                        if (config.get("plugin") instanceof String) {
                            mod.pluginPicked.add((p + "." + name).replace('.', '/'));
                        }
                    }
                }
            }
        }
    }

    /**
     * An injector into a method that still links, whose @At names a call or field that method made in the old release and
     * no longer makes in the new one (or not as often as its ordinal needs), finds no place there: Mixin scans 0 targets
     * and, with the require most mods set, stops the game (1.21.2's LoadingOverlay.render stopped calling
     * RenderSystem.blendFunc). Reported for every injector, not only the ones retarget moves.
     */
    static final String MODIFY_VARIABLE = "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;";

    /** How many loads (or stores) of a local of type t a method's own code makes (lambdas apart); -1 when it cannot be read. */
    int locals(GameCode code, GameDiff.Meth m, boolean store, Type t) {
        int[] counts = code == null ? null : code.locals(m.owner.name, m.name, m.desc);
        if (counts == null) {
            return -1;
        }
        int op = t.getOpcode(store ? Opcodes.ISTORE : Opcodes.ILOAD);
        return counts[op] + params(m, t);
    }

    /**
     * The parameters (and receiver) of m that are locals a @ModifyVariable of type t could pick, by the instruction that
     * loads them: Mixin's ordinal counts them with the body's own locals (Yeetus Experimentus' "STORE, ordinal 3" boolean
     * of loadLevel was its fourth boolean after three boolean parameters), so leaving them out let a target that lost
     * them pass.
     */
    static int params(GameDiff.Meth m, Type t) {
        int op = t.getOpcode(Opcodes.ILOAD);
        int n = !m.isStatic() && op == Opcodes.ALOAD ? 1 : 0;
        for (Type p : Type.getArgumentTypes(m.desc)) {
            if (p.getOpcode(Opcodes.ILOAD) == op) {
                n++;
            }
        }
        return n;
    }

    void pointsGone(JarPort.Mod mod, String mixin, String key, List<String> targets, MixinRetargets.Injector inj, String handlerDesc) {
        for (String text : inj.methods()) {
            List<List<GameDiff.Meth>> into = mod.retargets.containsKey(key + " " + text) ? null : selected(targets, text);
            if (into == null) {
                continue;
            }
            List<GameDiff.Meth> before = into.get(0), now = into.get(1);
            for (String[] point : inj.ats()) {
                String gone = null;
                int needed = point[2] == null ? 1 : Integer.parseInt(point[2]) + 1;
                // A @ModifyVariable's ordinal is its own (the nth local of its type); older handlers gave it on the @At.
                String nth = inj.kind().equals(MODIFY_VARIABLE) && inj.ordinal()[0] >= 0 ? Integer.toString(inj.ordinal()[0]) : point[2];
                if (point[0] != null && Set.of("LOAD", "STORE").contains(point[0]) && inj.kind().equals(MODIFY_VARIABLE) && nth != null) {
                    // @ModifyVariable at a load or store of its nth local of its type: the new method must still have
                    // that many of them at all (an upper bound, so a place it reports gone is gone).
                    int wanted = Integer.parseInt(nth) + 1;
                    Type local = Type.getReturnType(handlerDesc);
                    boolean store = point[0].equals("STORE");
                    int had = most(before, m -> locals(port.code(port.a), m, store, local)), has = most(now, m -> locals(port.code(port.b), m, store, local));
                    if (had >= wanted && has >= 0 && has < wanted) {
                        gone = point[0] + " #" + nth + " of a " + local.getClassName() + " local";
                    }
                } else if (point[0] != null && point[1] != null && Set.of("INVOKE", "INVOKE_ASSIGN", "FIELD").contains(point[0])) {
                    MixinMembers.Found at = port.mixinMembers.find(targets, "target", point[1]);
                    Matcher m = MixinMembers.MEMBER.matcher(point[1]);
                    if ((at.kind() != 'm' && at.kind() != 'f') || !m.matches()) {
                        continue;
                    }
                    int had = most(before, x -> uses(port.code(port.a), port.a, x, at.kind(), m.group(1), m.group(2), m.group(3) != null ? m.group(3) : m.group(4)));
                    int has = most(now, x -> uses(port.code(port.b), port.b, x, at.kind(), at.owner(), at.name(), at.desc()));
                    if (had >= needed && has >= 0 && has < needed && !moveAt(mod, key, inj, point, at, m, before, now, false)) {
                        gone = Exposure.dotted(at.owner()) + "." + at.name() + (at.desc() == null ? "" : at.desc());
                    } else if (point[2] != null && had >= needed && has >= needed && had != has) {
                        // Still enough of them, but not as many: the ordinal-th may now be another one.
                        moveAt(mod, key, inj, point, at, m, before, now, true);
                    }
                }
                if (gone != null) {
                    // Mixin stops the game only where the injector requires a place (its require, or its config's
                    // defaultRequire); otherwise the hook is quiet, as an override the game no longer calls is.
                    int require = inj.require()[0] >= 0 ? inj.require()[0] : mod.requires.getOrDefault(mixin, 0);
                    String where = now.size() == 1 ? Exposure.dotted(now.get(0).owner.name) + "." + now.get(0).name + now.get(0).desc : text;
                    gone += " in " + where + " (in " + Exposure.dotted(mixin) + ")";
                    (require > 0 ? mod.unlinked : mod.silent).add((require > 0 ? "mixin injection point gone: " : "mixin injection finds no place: ") + gone);
                }
            }
        }
    }

    /**
     * An @At at a call or field its method no longer makes, moved to what the game's new code has in the same place
     * (GameCode.slot): the same instruction at another ordinal (any injector); another signature of the same call, for an
     * @Inject, whose handler takes a place, not the call; another method of the same class and signature (any injector, as
     * its handler's shape is the call's); a field now read or written through the one method of its class that does it,
     * as a call of that method (any injector: a field's redirect and its accessor's take the same values). The @At must
     * select one method on each side, and where it gives no ordinal the new code must have the new point as often as the
     * old code had the old one. Whether it moved; otherwise the point is reported, and GameCode.slot says what the game did.
     * kept: the ordinal still finds a place, but the method makes the call a different number of times now, so the
     * ordinal-th may be another one; it moves to the one the old one lines up with (any injector), and nothing is reported.
     */
    boolean moveAt(JarPort.Mod mod, String key, MixinRetargets.Injector inj, String[] point, MixinMembers.Found at, Matcher target, List<GameDiff.Meth> before, List<GameDiff.Meth> now, boolean kept) {
        if (before.size() != 1 || now.size() != 1 || point[0].equals("INVOKE_ASSIGN") && at.kind() == 'f') {
            return false;
        }
        GameDiff.Meth was = before.get(0), is = now.get(0);
        int ordinal = point[2] == null ? -1 : Integer.parseInt(point[2]);
        String desc = target.group(3) != null ? target.group(3) : target.group(4);
        GameCode.Slot slot = GameCode.slot(port.code(port.a), port.code(port.b), port::cls, new String[] { was.owner.name, was.name, was.desc },
            new String[] { is.owner.name, is.name, is.desc }, at.kind(), target.group(1), target.group(2), desc, ordinal);
        String[] x = slot.member();
        if (kept) {
            // The ordinal still finds a place; it moves only where the old one lines up with another one now.
            if (slot.game().equals("ordinal") && slot.ordinal() != ordinal) {
                mod.points.put(key + " " + point[0] + " " + point[1] + " " + point[2], new String[] { point[0], point[1], String.valueOf(slot.ordinal()) });
                return true;
            }
            return false;
        }
        boolean inject = inj.kind().equals(MixinRetargets.INJECT);
        boolean moves = switch (slot.game()) {
            case "ordinal" -> ordinal >= 0;
            case "overload" -> inject;
            case "renamed", "accessor" -> true;
            default -> false;
        };
        if (!moves || x == null) {
            JarPort.why(key, "A-" + slot.game() + " " + point[1]);
            return false;
        }
        String value = slot.game().equals("accessor") ? "INVOKE" : point[0];
        // The same instruction keeps the @At's own target, named in the new release as every target is (member).
        String text = slot.game().equals("ordinal") ? point[1] : "L" + x[1] + ";" + x[2] + (x[0].equals("f") ? ":" : "") + x[3];
        if (ordinal < 0) {
            // Without an ordinal the injector takes every place: the new code must have the new point as often.
            int had = uses(port.code(port.a), port.a, was, at.kind(), target.group(1), target.group(2), desc);
            int has = uses(port.code(port.b), port.b, is, x[0].charAt(0), x[1], x[2], x[3], true);
            if (had != has) {
                JarPort.why(key, "A-count " + point[1]);
                return false;
            }
        }
        mod.points.put(key + " " + point[0] + " " + point[1] + " " + point[2], new String[] { value, text, ordinal < 0 ? null : String.valueOf(slot.ordinal()) });
        return true;
    }

    /** The most any of the methods gives (an injector finds its place if one of the methods it selects has it); -1 when one cannot be read. */
    static int most(List<GameDiff.Meth> methods, java.util.function.ToIntFunction<GameDiff.Meth> count) {
        int best = 0;
        for (GameDiff.Meth m : methods) {
            int n = count.applyAsInt(m);
            if (n < 0) {
                return -1;
            }
            best = Math.max(best, n);
        }
        return best;
    }

    /**
     * The methods a mixin "method" string selects in the targets, {old release's, new release's}, or null when it cannot
     * be read or selects nothing on a side. A plain string names one method; a regular expression ("/^(connect|m_1)$/", or
     * "name=/.../ desc=/.../") selects every method of the target classes whose name (and descriptor) it matches, as
     * Mixin selects them, so a name either release has is found.
     */
    List<List<GameDiff.Meth>> selected(List<String> targets, String text) {
        Matcher regex = Pattern.compile("^(?:name=)?/(.+?)/(?:\\s+desc=/(.+)/)?$").matcher(text.trim());
        if (!regex.matches()) {
            MixinMembers.Found into = port.mixinMembers.find(targets, "method", text);
            GameDiff.Meth before = port.mixinMembers.oldMethod(targets, text);
            GameDiff.Meth now = into.kind() == 'm' && into.desc() != null ? port.b.method(into.owner(), into.name(), into.desc()) : null;
            return now == null || before == null ? null : List.of(List.of(before), List.of(now));
        }
        Pattern name, desc;
        try {
            name = Pattern.compile(regex.group(1));
            desc = regex.group(2) == null ? null : Pattern.compile(regex.group(2));
        } catch (PatternSyntaxException unreadable) {
            return null;
        }
        List<GameDiff.Meth> before = new ArrayList<>(), now = new ArrayList<>();
        for (String t : targets) {
            for (var side : List.of(new Object[] { port.a, t, before }, new Object[] { port.b, port.cls(t), now })) {
                GameDiff.Cls c = ((GameDiff.Snap) side[0]).classes.get((String) side[1]);
                for (GameDiff.Meth m : c == null ? List.<GameDiff.Meth>of() : c.methods.values()) {
                    if (name.matcher(m.name).find() && (desc == null || desc.matcher(m.desc).find())) {
                        @SuppressWarnings("unchecked") List<GameDiff.Meth> into = (List<GameDiff.Meth>) side[2];
                        into.add(m);
                    }
                }
            }
        }
        return before.isEmpty() || now.isEmpty() ? null : List.of(before, now);
    }

    /** Whether every @At of an injector still finds its place in the methods it injects into, in the new release. */
    boolean pointsKept(List<String> targets, MixinRetargets.Injector inj, List<MixinMembers.Found> into, List<MixinMembers.Found> ats) {
        List<GameDiff.Meth[]> methods = enclosing(targets, inj, into);
        if (methods == null) {
            JarPort.why(String.valueOf(inj.methods()), "P-enclosing");
            return false;
        }
        for (int i = 0; i < ats.size(); i++) {
            String value = inj.ats().get(i)[0];
            if (Set.of("HEAD", "RETURN", "TAIL").contains(value)) {
                continue;
            }
            MixinMembers.Found at = ats.get(i);
            if (!Set.of("INVOKE", "INVOKE_ASSIGN", "FIELD").contains(value) || at == null || at.kind() == 0 || at.desc() == null) {
                JarPort.why(value + " " + inj.ats().get(i)[1], "P-at " + (at == null ? "none" : at.kind() == 0 ? "gone" : at.desc() == null ? "no-desc" : "kind"));
                return false;
            }
            int k = i;
            if (!methods.stream().allMatch(m -> pointKept(inj.ats().get(k), at, m))) {
                JarPort.why(value + " " + inj.ats().get(i)[1] + " ordinal " + inj.ats().get(i)[2], "P-notin");
                return false;
            }
        }
        return true;
    }

    /**
     * Whether an @At ({value, target, ordinal}; at its target in new names) finds its place in a method injected into
     * ({new, old}): the new method's own instructions use the member, and as often as the old one's did when the @At picks
     * one occurrence by its ordinal. A use inside a lambda does not count: Mixin injects into the method's own code.
     */
    boolean pointKept(String[] point, MixinMembers.Found at, GameDiff.Meth[] method) {
        char kind = at.kind() == 's' || at.kind() == 'r' ? 'm' : at.kind();
        // Mixin matches a call by the owner its instruction names, so a replacement written by the porter counts only those.
        int now = uses(port.code(port.b), port.b, method[0], kind, at.owner(), at.name(), at.desc(), at.kind() == 'r');
        if (now < 1) {
            return false;
        }
        if (point[2] == null) {
            return true;
        }
        Matcher m = MixinMembers.MEMBER.matcher(point[1]);
        if (!m.matches()) {
            return false;
        }
        String owner = m.group(1) != null ? m.group(1) : null;
        int before = uses(port.code(port.a), port.a, method[1], kind, owner, m.group(2), m.group(3) != null ? m.group(3) : m.group(4));
        return before == now && now > Integer.parseInt(point[2]);
    }

    /** The methods an injector's targets name, {new release, old release} each, when all are known on both sides. */
    List<GameDiff.Meth[]> enclosing(List<String> targets, MixinRetargets.Injector inj, List<MixinMembers.Found> into) {
        List<GameDiff.Meth[]> out = new ArrayList<>();
        for (int i = 0; i < into.size(); i++) {
            MixinMembers.Found f = into.get(i);
            GameDiff.Meth now = (f.kind() == 'm' || f.kind() == 's') && f.desc() != null ? port.b.method(f.owner(), f.name(), f.desc()) : null;
            GameDiff.Meth before = port.mixinMembers.oldMethod(targets, inj.methods().get(i));
            if (now == null || before == null) {
                return null;
            }
            out.add(new GameDiff.Meth[] { now, before });
        }
        return out;
    }

    /**
     * How often a method's own instructions (lambdas apart) use a member (owner null: any owner), read from the release's
     * jar; -1 when they cannot be read.
     */
    int uses(GameCode code, GameDiff.Snap s, GameDiff.Meth m, char kind, String owner, String name, String desc) {
        return uses(code, s, m, kind, owner, name, desc, false);
    }

    /** As above; exact counts only instructions naming owner itself, not another class the member resolves to. */
    int uses(GameCode code, GameDiff.Snap s, GameDiff.Meth m, char kind, String owner, String name, String desc, boolean exact) {
        List<String[]> refs = code == null ? null : code.refs(m.owner.name, m.name, m.desc);
        if (refs == null) {
            return -1;
        }
        GameDiff.Cls decl = owner == null ? null : Exposure.declaring(s, owner, kind, name, kind == 'f' ? null : desc);
        int n = 0;
        for (String[] r : refs) {
            if (r[0].charAt(0) != kind || !r[2].equals(name) || desc != null && !desc.equals(r[3])) {
                continue;
            }
            GameDiff.Cls at = Exposure.declaring(s, r[1], kind, r[2], kind == 'f' ? null : r[3]);
            if (owner == null || r[1].equals(owner) || !exact && decl != null && at != null && decl.name.equals(at.name)) {
                n++;
            }
        }
        return n;
    }
}

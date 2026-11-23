import java.util.*;
import org.objectweb.asm.*;

/** A game field or call result the update gave another type: the mod's uses of the value moved to the new type. */
final class RetypedValues {

    final Sites sites;

    RetypedValues(Sites sites) {
        this.sites = sites;
    }

    ReadUses uses;
    boolean readsTried;
    final Map<Integer, String[]> onNewType = new HashMap<>(); // call number -> {opcode, owner, name, desc} on a retyped value

    /**
     * A read of a field the new release has with another type (Level.random: Random, then RandomSource), read as its new
     * type where every use of the value still fits it (the calls made on it are ones the new type has, the calls it is
     * passed to take the new type), or else read and turned into the old type as the game's own code gets what a holder
     * of the new type holds (SoundEvents' sounds become holders: holder.value()).
     */
    boolean retyped(int opcode, String owner, String name, String desc, JarPort.Decl decl) {
        // Only what the update changed: a read of another type than the old release's never linked and stays as written.
        GameDiff.Cls had = Exposure.declaring(sites.port.a, decl.owner(), 'f', name, null);
        if (had == null || !had.fields.get(name).desc.equals(desc)) {
            return false;
        }
        String[] now = sites.port.links.fieldNow(sites.mod, sites.callers, owner, name, decl);
        String want = sites.port.desc(desc);
        if (now == null || now[2].equals(want) || !now[2].startsWith("L")) {
            return false;
        }
        Type type = Type.getType(now[2]);
        if (!readsTried) {
            readsTried = true;
            int dot = sites.key.indexOf('.'), paren = sites.key.indexOf('(');
            uses = paren < 0 ? null : ReadUses.of(sites.mod.bytes.get(sites.key.substring(0, dot)), sites.key.substring(dot + 1, paren), sites.key.substring(paren));
        }
        List<ReadUses.Read> reads = uses == null ? null : uses.reads;
        ReadUses.Read read = reads == null || sites.fieldsSeen >= reads.size() ? null : reads.get(sites.fieldsSeen);
        Map<Integer, String[]> calls = read == null || read.bad || read.uses.isEmpty() ? null : onNewType(read, type);
        String[] steps = calls != null ? null : sites.port.links.unwrap(now[2], want);
        JarPort.why(sites.key, "F " + name + " " + want + " -> " + now[2] + (read == null ? " no-read" : " bad=" + read.bad + " " + read.uses.stream().map(Arrays::toString).toList())
            + " calls=" + calls + " steps=" + (steps == null ? null : Arrays.toString(steps)));
        if (calls == null && steps == null) {
            return false;
        }
        sites.emitFieldInsn(opcode, sites.port.inverse.getOrDefault(now[0], now[0]), now[1], sites.port.oldDesc(now[2]));
        if (calls != null) {
            onNewType.putAll(calls);
            sites.mod.change("field read as its new type");
            return true;
        }
        unwrapping(type, steps);
        sites.mod.change("field read through what the game's code reads from its new type");
        return true;
    }

    /** Writes what the game's code reads from a value of type held to get the one it holds (see unwrap). */
    void unwrapping(Type held, String[] steps) {
        boolean itf = sites.port.links.isInterface(sites.mod, held.getInternalName());
        sites.emitMethodInsn(itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, sites.port.inverse.getOrDefault(held.getInternalName(), held.getInternalName()),
            steps[0].substring(0, steps[0].indexOf('(')), sites.port.oldDesc(steps[0].substring(steps[0].indexOf('('))), itf);
        String cast = steps[1];
        sites.emitTypeInsn(Opcodes.CHECKCAST, sites.port.inverse.getOrDefault(cast, cast));
        if (steps.length > 2) {
            sites.emitMethodInsn(Opcodes.INVOKEVIRTUAL, cast, steps[2].substring(0, steps[2].indexOf('(')), steps[2].substring(steps[2].indexOf('(')), false);
        }
    }

    /** The exact JVM conversion from a call's new primitive result to the one the code used (1.21.6's Window.getGuiScale() int, read as the double it was). */
    static final Map<String, Integer> WIDEN = Map.of("ID", Opcodes.I2D, "IJ", Opcodes.I2L, "FD", Opcodes.F2D, "BI", Opcodes.NOP, "SI", Opcodes.NOP, "CI", Opcodes.NOP);

    /** Types whose value may hold nothing (an empty Optional, a failed DataResult): reading the value out could throw where the old call answered. */
    static final Set<String> MAYBE = Set.of("java/util/Optional", "com/mojang/serialization/DataResult", "java/util/OptionalInt", "java/util/OptionalLong", "java/util/OptionalDouble");

    /**
     * A call whose method the update gave another result type, its arguments still fitting (1.19's getRandom: Random,
     * then RandomSource; 1.21.6's getGuiScale: double, then int). The new result is taken as it is where every use
     * of it means the same of its type (ReadUses, as a retyped field read), widened where a primitive converts exactly,
     * or turned into the old type as the game's own code gets what a holder of it holds. Not a result that may hold
     * nothing (Optional, DataResult): the old call answered there.
     */
    boolean resultConverted(int opcode, String owner, String declOwner, String name, String desc) {
        if (sites.moved != null || opcode == Opcodes.INVOKESPECIAL) {
            return false;
        }
        boolean isStatic = opcode == Opcodes.INVOKESTATIC;
        String[] t = sites.port.route.method(declOwner, name, desc);
        Type[] before = Type.getArgumentTypes(sites.port.desc(desc)), after = Type.getArgumentTypes(t[2]);
        Type was = Type.getReturnType(sites.port.desc(desc)), now = Type.getReturnType(t[2]);
        if (before.length != after.length || was.equals(now) || was.getSort() == Type.VOID || now.getSort() == Type.VOID || !isStatic && !sites.isA(sites.port.cls(owner), t[0])
            || now.getSort() == Type.OBJECT && MAYBE.contains(now.getInternalName())) {
            // A result that may hold nothing is never null itself: even a null test of it would answer otherwise.
            return false;
        }
        for (int i = 0; i < before.length; i++) {
            if (!sites.port.links.usable(before[i], after[i])) {
                return false;
            }
        }
        GameDiff.Cls holder = Exposure.declaring(sites.port.b, t[0], 'm', t[1], t[2]);
        GameDiff.Meth m = holder == null ? null : holder.methods.get(t[1] + t[2]);
        if (m == null || m.isStatic() != isStatic || !sites.port.links.linksNew(sites.mod, sites.callers, t[0], 'm', t[1], t[2])) {
            return false;
        }
        boolean objects = now.getSort() == Type.OBJECT && (was.getSort() == Type.OBJECT || was.getSort() == Type.ARRAY);
        Integer widen = WIDEN.get(now.getDescriptor() + was.getDescriptor());
        Map<Integer, String[]> calls = null;
        String[] steps = null;
        if (objects) {
            if (!readsTried) {
                readsTried = true;
                int dot = sites.key.indexOf('.'), paren = sites.key.indexOf('(');
                uses = paren < 0 ? null : ReadUses.of(sites.mod.bytes.get(sites.key.substring(0, dot)), sites.key.substring(dot + 1, paren), sites.key.substring(paren));
            }
            ReadUses.Read read = uses == null ? null : uses.results.get(sites.callsSeen);
            calls = read == null || read.bad || read.uses.isEmpty() ? null : onNewType(read, now);
            steps = calls != null ? null : sites.port.links.unwrap(now.getDescriptor(), was.getDescriptor());
        }
        if (calls == null && steps == null && widen == null) {
            return false;
        }
        GameDiff.Cls named = sites.port.b.classes.getOrDefault(t[0], holder);
        boolean itf = (named.flags & Opcodes.ACC_INTERFACE) != 0;
        sites.emitMethodInsn(isStatic ? Opcodes.INVOKESTATIC : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, sites.port.inverse.getOrDefault(t[0], t[0]), t[1], sites.port.oldDesc(t[2]), itf);
        if (widen != null) {
            if (widen != Opcodes.NOP) {
                sites.emitInsn(widen);
            }
            sites.mod.change("call whose result type changed, widened to the old one");
        } else if (calls != null) {
            onNewType.putAll(calls);
            sites.mod.change("call whose result type changed, its result used as the new type");
        } else {
            unwrapping(now, steps);
            sites.mod.change("call whose result type changed, read through what the game's code reads from it");
        }
        return true;
    }

    /**
     * For a read whose every use means the same of a value of type: the calls made on it, each as the new type has it;
     * else null. Passed where any object goes (a map key, equals) or asked what only Object answers, a value of
     * another type would answer otherwise, so it does not fit.
     */
    Map<Integer, String[]> onNewType(ReadUses.Read read, Type type) {
        Map<Integer, String[]> out = new HashMap<>();
        for (String[] use : read.uses) {
            switch (use[0]) {
                case "N" -> { }
                case "A" -> {
                    if (use[2].equals("Ljava/lang/Object;")) {
                        return null;
                    }
                    if (!sites.port.links.usable(type, Type.getType(sites.port.desc(use[2])))) {
                        // A game method whose parameter changed with the field (Level.random and randomTick's random):
                        // the call takes its new signature, the value already being the new type.
                        String[] call = movedWith(type, use);
                        if (call == null || !Arrays.equals(out.getOrDefault(Integer.parseInt(use[1]), call), call)) {
                            return null;
                        }
                        out.put(Integer.parseInt(use[1]), call);
                    }
                }
                case "C" -> {
                    if (!sites.port.links.usable(type, Type.getType(sites.port.desc(use[1].startsWith("[") ? use[1] : "L" + use[1] + ";")))) {
                        return null;
                    }
                }
                case "R" -> {
                    String[] call = JarPort.OBJECT_METHODS.contains(use[3] + use[4]) ? null : onType(type.getInternalName(), use[2], use[3], use[4]);
                    if (call == null) {
                        return null;
                    }
                    out.put(Integer.parseInt(use[1]), call);
                }
                default -> {
                    return null;
                }
            }
        }
        return out;
    }

    /**
     * {opcode, owner, name, desc} (output names) of a game call a retyped value is passed to (an "A" use), as the new
     * release has it, when its new parameter there takes the value and every other argument and the result still fit;
     * or null.
     */
    String[] movedWith(Type type, String[] use) {
        String owner = use[4], name = use[5], desc = use[6];
        int i = Integer.parseInt(use[3]), opcode = Integer.parseInt(use[7]);
        GameDiff.Cls was = Exposure.declaring(sites.port.a, owner, 'm', name, desc);
        if (was == null || opcode == Opcodes.INVOKESPECIAL) {
            return null;
        }
        String[] moved = sites.port.route.method(was.name, name, desc);
        Type[] before = Type.getArgumentTypes(sites.port.desc(desc)), after = Type.getArgumentTypes(moved[2]);
        if (before.length != after.length || after[i].getDescriptor().equals("Ljava/lang/Object;") || !sites.port.links.usable(type, after[i])
            || !sites.port.links.usable(Type.getReturnType(moved[2]), Type.getReturnType(sites.port.desc(desc)))) {
            return null;
        }
        for (int k = 0; k < before.length; k++) {
            if (k != i && !sites.port.links.usable(before[k], after[k])) {
                return null;
            }
        }
        String site = opcode == Opcodes.INVOKESTATIC ? moved[0] : sites.port.cls(owner);
        if (!sites.port.links.linksNew(sites.mod, sites.callers, site, 'm', moved[1], moved[2])) {
            return null;
        }
        GameDiff.Cls self = sites.port.b.classes.get(site);
        boolean itf = self != null && (self.flags & Opcodes.ACC_INTERFACE) != 0;
        int op = opcode == Opcodes.INVOKESTATIC ? opcode : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
        return new String[] { String.valueOf(op), sites.port.inverse.getOrDefault(site, site), moved[1], sites.port.oldDesc(moved[2]) };
    }

    /** {opcode, owner, name, desc} (output names) of a call written on owner (old names) as type has it, public and the same; or null. */
    String[] onType(String type, String owner, String name, String desc) {
        GameDiff.Cls was = Exposure.declaring(sites.port.a, owner, 'm', name, desc);
        String[] moved = was == null ? new String[] { owner, name, sites.port.desc(desc) } : sites.port.route.method(was.name, name, desc);
        GameDiff.Cls holder = Exposure.declaring(sites.port.b, type, 'm', moved[1], moved[2]);
        GameDiff.Meth m = holder == null ? null : holder.methods.get(moved[1] + moved[2]);
        if (m == null || m.isStatic() || (m.flags & Opcodes.ACC_PUBLIC) == 0 || !Type.getReturnType(moved[2]).equals(Type.getReturnType(sites.port.desc(desc)))) {
            return null;
        }
        // The arguments on the stack are the old call's: each must still fit (the receiver alone changes here).
        Type[] before = Type.getArgumentTypes(sites.port.desc(desc)), after = Type.getArgumentTypes(moved[2]);
        if (before.length != after.length) {
            return null;
        }
        for (int k = 0; k < before.length; k++) {
            if (!sites.port.links.usable(before[k], after[k])) {
                return null;
            }
        }
        boolean itf = sites.port.links.isInterface(sites.mod, type);
        return new String[] { String.valueOf(itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL), sites.port.inverse.getOrDefault(type, type), moved[1], sites.port.oldDesc(moved[2]) };
    }
}

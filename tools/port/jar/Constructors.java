import java.util.*;
import org.objectweb.asm.*;

/** A mod's constructor that hands on to a game constructor the update reshaped. */
final class Constructors {

    final JarPort port;

    Constructors(JarPort port) {
        this.port = port;
    }

    /**
     * A mod constructor whose super(...) or this(...) goes to a constructor that now takes another type in a place, one
     * the old value is read from (1.17's EntityRenderer(EntityRenderDispatcher) became EntityRenderer(EntityRendererProvider
     * .Context), whose getEntityRenderDispatcher() is the dispatcher): it moves whole to take the new type in place of its
     * own parameter of the old one, as the game's own subclasses' constructors did, the old value worked out first as the
     * game's code reads it (Retype). Its super call is then given the new value (Sites.given), and so is each call that
     * makes the class where that value is at hand (a moved lambda or override); a call that has none keeps the old
     * constructor, whose super call is reported. A mod class's constructor follows its mod superclass's that moved. True
     * when a constructor moved.
     */
    boolean constructor(JarPort.Mod mod, GameDiff.RawCls rc, GameDiff.RawMeth m) {
        byte[] bytes = mod.bytes.get(rc.name);
        if (bytes == null || (new ClassReader(bytes).readShort(6) & 0xFFFF) < Opcodes.V1_6
            || mod.bridges.getOrDefault(rc.name, List.of()).stream().anyMatch(br -> br.target().equals(m.name) && br.targetDesc().equals(m.desc))) {
            return false;
        }
        String[] call = delegate(bytes, m.desc);
        if (call == null) {
            return false;
        }
        String owner = call[0], desc = call[1];
        Type[] was = Type.getArgumentTypes(port.desc(desc));
        String newCall;
        String[][] via = new String[was.length][];
        if (mod.classes.containsKey(owner)) {
            Overrides.Bridge parent = mod.bridges.getOrDefault(owner, List.of()).stream()
                .filter(br -> br.via() != null && br.target().equals("<init>") && br.targetDesc().equals(desc)).findFirst().orElse(null);
            if (parent == null) {
                return false;
            }
            newCall = parent.desc();
            for (int i = 0; i < was.length; i++) {
                if (parent.via()[i] != null) {
                    String[] steps = new String[parent.via()[i].length + 1];
                    steps[0] = String.valueOf(parent.from()[i]);
                    System.arraycopy(parent.via()[i], 0, steps, 1, parent.via()[i].length);
                    via[i] = steps;
                }
            }
        } else {
            newCall = retypedConstructor(owner, desc);
            if (newCall == null) {
                return false;
            }
            Type[] now = Type.getArgumentTypes(newCall);
            for (int i = 0; i < was.length; i++) {
                if (now[i].equals(was[i])) {
                    continue;
                }
                String[] steps = reading(owner, was[i], now[i], 0);
                if (steps == null || !sameShape(owner, was[i], now[i])) {
                    JarPort.why(rc.name + ".<init>" + m.desc, "C super " + owner + ".<init>" + desc + " -> " + newCall + " reading=" + (steps == null ? null : String.join(" ", steps))
                        + " shape=" + sameShape(owner, was[i], now[i]));
                    return false;
                }
                via[i] = new String[steps.length + 1];
                via[i][0] = String.valueOf(i);
                System.arraycopy(steps, 0, via[i], 1, steps.length);
            }
        }
        // Each place that changed type is filled from the constructor's one parameter of the old type, which becomes the new.
        Type[] own = Type.getArgumentTypes(port.desc(m.desc)), params = own.clone(), newTypes = Type.getArgumentTypes(newCall);
        int[] from = new int[own.length];
        String[][] steps = new String[own.length][];
        boolean any = false;
        for (int k = 0; k < own.length; k++) {
            from[k] = k;
        }
        for (int i = 0; i < was.length; i++) {
            if (via[i] == null) {
                continue;
            }
            int p = -1;
            for (int k = 0; k < own.length; k++) {
                if (own[k].equals(was[i])) {
                    p = p == -1 ? k : -2;
                }
            }
            if (p < 0 || steps[p] != null) {
                return false;
            }
            params[p] = newTypes[Integer.parseInt(via[i][0])];
            steps[p] = Arrays.copyOfRange(via[i], 1, via[i].length);
            any = true;
        }
        String newDesc = Type.getMethodDescriptor(Type.VOID_TYPE, params);
        if (!any || rc.methods.containsKey("<init>" + port.oldDesc(newDesc))) {
            return false;
        }
        mod.bridges.computeIfAbsent(rc.name, k -> new ArrayList<>()).add(new Overrides.Bridge("<init>", newDesc, "<init>", m.desc, from, m.flags, steps));
        mod.change("constructor moved to what its superclass's constructor takes now");
        return true;
    }

    /**
     * The new release's constructor (new names) a game class's old constructor (old names) became when the update only
     * changed the types of some of its parameters: the route map's, else the class's one constructor with as many
     * parameters whose other parameters are the old ones in place. Null when the old one still links, or none or several fit.
     */
    String retypedConstructor(String owner, String desc) {
        GameDiff.Cls holder = port.b.classes.get(port.cls(owner));
        Type[] was = Type.getArgumentTypes(port.desc(desc));
        if (!port.a.classes.containsKey(owner) || holder == null || holder.methods.containsKey("<init>" + port.desc(desc))) {
            return null;
        }
        String[] moved = port.route.method(owner, "<init>", desc);
        if (moved[0].equals(holder.name) && holder.methods.containsKey("<init>" + moved[2])) {
            return Type.getArgumentTypes(moved[2]).length == was.length ? moved[2] : null;
        }
        String found = null;
        for (GameDiff.Meth k : holder.methods.values()) {
            Type[] t = Type.getArgumentTypes(k.desc);
            if (!k.name.equals("<init>") || (k.flags & Opcodes.ACC_PRIVATE) != 0 || t.length != was.length) {
                continue;
            }
            boolean fits = true;
            for (int i = 0; i < t.length && fits; i++) {
                // A type in another place, or a primitive in place of another, is a reshaped constructor, not a retyped one.
                fits = t[i].equals(was[i]) || t[i].getSort() == Type.OBJECT && was[i].getSort() == Type.OBJECT && !Arrays.asList(was).contains(t[i]);
            }
            if (fits) {
                if (found != null) {
                    return null;
                }
                found = k.desc;
            }
        }
        return found;
    }

    /**
     * How the new release reads a value of type old from the value of type now that a game constructor takes in its place
     * (steps as Retype gives them, new names): what the class's own two constructor bodies show, else its game
     * superclass's, which it hands the value to (MobRenderer passes the context on; EntityRenderer reads
     * context.getEntityRenderDispatcher() where it stored the dispatcher).
     */
    String[] reading(String owner, Type old, Type now, int depth) {
        GameDiff.Cls c = port.a.classes.get(owner);
        if (c == null || depth > 6) {
            return null;
        }
        for (GameDiff.Meth k : c.methods.values()) {
            Type[] was = Type.getArgumentTypes(port.desc(k.desc));
            if (!k.name.equals("<init>") || Arrays.stream(was).filter(old::equals).count() != 1) {
                continue;
            }
            String newDesc = retypedConstructor(owner, k.desc);
            if (newDesc == null) {
                continue;
            }
            Type[] t = Type.getArgumentTypes(newDesc);
            int i = Arrays.asList(was).indexOf(old);
            if (!t[i].equals(now)) {
                continue;
            }
            String[][] found = port.retype.conversions(owner, "<init>", k.desc, new String[] { port.cls(owner), "<init>", newDesc }, Overrides.pair(t, was));
            if (found != null && found[i] != null && Integer.parseInt(found[i][0]) == i) {
                return Arrays.copyOfRange(found[i], 1, found[i].length);
            }
        }
        return c.sup == null ? null : reading(c.sup, old, now, depth + 1);
    }

    /**
     * Whether the game's own subclasses of owner moved their constructors the same way: most of those that took the old
     * type take the new one now (1.17's renderers, each EntityRenderDispatcher parameter an EntityRendererProvider.Context).
     */
    boolean sameShape(String owner, Type old, Type now) {
        int yes = 0, total = 0;
        for (GameDiff.Cls c : port.a.classes.under(owner)) {
            if (!owner.equals(c.sup) || c.methods.values().stream().noneMatch(k -> takes(k, old, true))) {
                continue;
            }
            total++;
            GameDiff.Cls after = port.b.classes.get(port.cls(c.name));
            if (after != null && after.methods.values().stream().anyMatch(k -> takes(k, now, false)) && after.methods.values().stream().noneMatch(k -> takes(k, old, false))) {
                yes++;
            }
        }
        return yes >= 2 && yes * 10 >= total * 8;
    }

    /** Whether k is a constructor with a parameter of type t (new names; k's in old names when old). */
    boolean takes(GameDiff.Meth k, Type t, boolean old) {
        return k.name.equals("<init>") && Arrays.stream(Type.getArgumentTypes(old ? port.desc(k.desc) : k.desc)).anyMatch(t::equals);
    }

    /** The class and descriptor of the super(...) or this(...) call a constructor's body makes, or null. */
    static String[] delegate(byte[] bytes, String desc) {
        String[][] out = new String[1][];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            String self, sup;

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                self = name;
                sup = superName;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String d, String signature, String[] exceptions) {
                return !name.equals("<init>") || !d.equals(desc) ? null : new MethodVisitor(Opcodes.ASM9) {
                    int news;

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        news += opcode == Opcodes.NEW ? 1 : 0;
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String d, boolean itf) {
                        if (opcode != Opcodes.INVOKESPECIAL || !name.equals("<init>") || out[0] != null) {
                            return;
                        }
                        if (news > 0) {
                            news--;
                        } else if (owner.equals(sup) || owner.equals(self)) {
                            out[0] = new String[] { owner, d };
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return out[0];
    }
}

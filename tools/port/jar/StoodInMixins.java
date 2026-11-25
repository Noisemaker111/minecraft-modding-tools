import java.util.*;
import org.objectweb.asm.*;

/**
 * A mixin into a class the older game lacks and a stand-in answers (1.20's GuiGraphics on 1.19.4), moved to the game
 * method where the older game does the same work. Code injected into the stand-in would run only where mods call it,
 * never where the game draws (Patchouli's hook on 1.20's GuiGraphics.renderTooltip: 1.19.4 draws an item's tooltip in
 * Screen.renderTooltip(PoseStack, ItemStack, int, int)). That method is the one the stand-in's own answer calls with the
 * value it holds (its PoseStack) in front of the same arguments, less those the method's object holds itself (the
 * screen's Font). The handler takes the game method's parameters; what it took is worked out from them, and where its
 * body used its own object as the stood-in class, it gets the stand-in made from that value (from(T)).
 */
final class StoodInMixins {
    final JarPort port;

    StoodInMixins(JarPort port) {
        this.port = port;
    }

    static final String MODIFY_VARIABLE = "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;";
    static final Set<String> ENDS = Set.of("HEAD", "RETURN", "TAIL");

    /** The game method a stand-in's answer for an old method does its work in, with where each old parameter comes from. */
    record Work(GameDiff.Meth game, int[] from, String[][] via) {}

    /**
     * Moves a mixin whose one target is the stood-in class t to the game class where the older game does the work of
     * every method it injects into; false (nothing changed) where any injector, member or use of its object cannot move.
     */
    boolean move(JarPort.Mod mod, GameDiff.RawCls rc, String t) {
        String held = held(t);
        if (held == null) {
            return why(rc.name, "no from(T)");
        }
        for (String[] f : rc.fields.values()) {
            if ((Integer.parseInt(f[1]) & Opcodes.ACC_STATIC) == 0) {
                return why(rc.name, "a field of its own object");
            }
        }
        String owner = null;
        Map<String, String> retargets = new HashMap<>();
        Map<String, MixinRetargets.Adapter> adapters = new HashMap<>();
        for (GameDiff.RawMeth m : rc.methods.values()) {
            String key = rc.name + "." + m.name + m.desc;
            MixinRetargets.Injector inj = mod.injectors.get(key);
            if (inj == null) {
                if ((m.flags & Opcodes.ACC_STATIC) == 0 && !m.name.equals("<init>")) {
                    return why(key, "an instance method that is no handler (a shadow or helper)");
                }
                continue;
            }
            if ((m.flags & Opcodes.ACC_STATIC) != 0 || !usesSelfAs(mod, rc.name, m, t)) {
                return why(key, "its object used as something else than " + t);
            }
            boolean inject = inj.kind().equals(MixinRetargets.INJECT);
            if (!inject && !inj.kind().equals(MODIFY_VARIABLE)) {
                return why(key, "injector " + inj.kind());
            }
            for (String[] at : inj.ats()) {
                if (!ENDS.contains(at[0]) || !inject && !at[0].equals("HEAD")) {
                    return why(key, "point " + at[0]);
                }
            }
            for (String text : inj.methods()) {
                GameDiff.Meth before = port.mixinMembers.oldMethod(List.of(t), text);
                Work w = before == null ? null : work(t, before, held);
                if (w == null) {
                    return why(key, "no game method does the work of " + text);
                }
                if (owner != null && !owner.equals(w.game().owner.name)) {
                    return why(key, "work in two classes");
                }
                owner = w.game().owner.name;
                retargets.put(key + " " + text, "L" + owner + ";" + w.game().name + w.game().desc);
                Type[] params = Type.getArgumentTypes(m.desc);
                int captured = Type.getArgumentTypes(before.desc).length;
                if (inject) {
                    if (inj.methods().size() != 1 || params.length < captured + 1 || !MixinRetargets.CALLBACKS.contains(params[captured].getInternalName())) {
                        return why(key, "not the target's arguments then its CallbackInfo");
                    }
                    Type[] now = Type.getArgumentTypes(w.game().desc);
                    Type[] desc = Arrays.copyOf(now, now.length + params.length - captured);
                    for (int i = captured; i < params.length; i++) {
                        desc[now.length + i - captured] = Type.getType(port.desc(params[i].getDescriptor()));
                    }
                    String[] self = { held(t, now[0].getDescriptor()) };
                    if (self[0] == null) {
                        return why(key, "no from(T) for " + now[0]);
                    }
                    adapters.put(key, new MixinRetargets.Adapter(Type.getMethodDescriptor(Type.VOID_TYPE, desc), w.from(), captured, w.via(), 0, self, "L" + t + ";"));
                } else if (inj.ordinal()[1] >= 0 || inj.ordinal()[2] >= 0) {
                    return why(key, "a variable named by its slot or name, which the dropped parameters move");
                } else if (!sameOrder(Type.getReturnType(m.desc), Type.getArgumentTypes(port.desc(before.desc)), w) || params.length != 1 || !usesSelfAs(mod, rc.name, m, null)) {
                    return why(key, "a variable whose place among its type's changes");
                }
            }
        }
        if (owner == null) {
            return why(rc.name, "nothing injected");
        }
        mod.retargets.putAll(retargets);
        mod.adapters.putAll(adapters);
        mod.movedMixins.put(rc.name, owner);
        mod.mixinTargets.put(rc.name, List.of(port.inverse.getOrDefault(owner, owner)));
        mod.change("mixin into a class only a stand-in has moved to where the older game does its work");
        return true;
    }

    boolean why(String key, String reason) {
        JarPort.why(key, "T " + reason);
        return false;
    }

    /** The type (new names) whose value the stood-in class's from(T) wraps; null when it has none. */
    String held(String t) {
        byte[] bytes = port.standIn.standins.get(StandinClasses.STANDIN + t);
        if (bytes == null) {
            return null;
        }
        String[] out = { null };
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if ((access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC) && StandinClasses.conversion(StandinClasses.STANDIN + t, desc, name)) {
                    out[0] = Type.getArgumentTypes(desc)[0].getInternalName();
                }
                return null;
            }
        }, ClassReader.SKIP_CODE);
        return out[0];
    }

    String held(String t, String got) {
        return port.standIn.from(t, got);
    }

    /**
     * The one game method the stand-in's answer for before (an old method of t) reaches that takes the held value first,
     * then before's parameters in their order less those its own object holds in a field of their type (the only such
     * field of its class and those above it); its result is before's.
     */
    Work work(String t, GameDiff.Meth before, String held) {
        Type[] was = Type.getArgumentTypes(port.desc(before.desc));
        Type result = Type.getReturnType(port.desc(before.desc));
        Work found = null;
        for (String call : port.standIn.reaches(t, before.name, before.desc)) {
            int dot = call.indexOf('.'), paren = call.indexOf('(');
            GameDiff.Meth game = port.b.method(call.substring(0, dot), call.substring(dot + 1, paren), call.substring(paren));
            if (game == null || game.isStatic() || !Type.getReturnType(game.desc).equals(result)) {
                continue;
            }
            Type[] now = Type.getArgumentTypes(game.desc);
            if (now.length == 0 || !now[0].getInternalName().equals(held)) {
                continue;
            }
            int[] from = new int[was.length];
            String[][] via = new String[was.length][];
            int j = 1;
            boolean fits = true;
            for (int i = 0; i < was.length && fits; i++) {
                if (j < now.length && now[j].equals(was[i])) {
                    from[i] = j++;
                    continue;
                }
                String field = was[i].getSort() == Type.OBJECT ? field(game.owner.name, was[i].getDescriptor()) : null;
                fits = field != null;
                from[i] = MixinRetargets.THIS;
                via[i] = new String[] { field };
            }
            if (!fits || j != now.length) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = new Work(game, from, via);
        }
        return found;
    }

    /** The one instance field of type desc of a game class or a class above it, as a conversion step; null if none or several. */
    String field(String cls, String desc) {
        String out = null;
        for (String c = cls; c != null && port.b.classes.containsKey(c); c = port.b.classes.get(c).sup) {
            for (GameDiff.Fld f : port.b.classes.get(c).fields.values()) {
                if ((f.flags & Opcodes.ACC_STATIC) == 0 && f.desc.equals(desc)) {
                    if (out != null) {
                        return null;
                    }
                    out = "f:" + c + "." + f.name + ":" + desc;
                }
            }
        }
        return out;
    }

    /** Whether the old parameters of a type keep their order among the game method's of that type, none added or dropped. */
    static boolean sameOrder(Type type, Type[] was, Work w) {
        Type[] now = Type.getArgumentTypes(w.game().desc);
        List<Integer> places = new ArrayList<>();
        for (int i = 0; i < was.length; i++) {
            if (was[i].equals(type)) {
                if (w.from()[i] < 0) {
                    return false;
                }
                places.add(w.from()[i]);
            }
        }
        List<Integer> ofType = new ArrayList<>();
        for (int i = 0; i < now.length; i++) {
            if (now[i].equals(type)) {
                ofType.add(i);
            }
        }
        return places.equals(ofType);
    }

    /** Whether a handler loads its own object only to cast it to the stood-in class t (null: never), so it can be handed one. */
    boolean usesSelfAs(JarPort.Mod mod, String mixin, GameDiff.RawMeth m, String t) {
        byte[] bytes = mod.bytes.get(mixin);
        if (bytes == null) {
            return false;
        }
        boolean[] ok = { true };
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (!name.equals(m.name) || !desc.equals(m.desc)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    boolean self;

                    void other() {
                        ok[0] &= !self;
                        self = false;
                    }

                    @Override
                    public void visitVarInsn(int opcode, int var) {
                        other();
                        self = opcode == Opcodes.ALOAD && var == 0;
                        ok[0] &= var != 0 || opcode == Opcodes.ALOAD;
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        ok[0] &= !self || opcode == Opcodes.CHECKCAST && type.equals(t) && t != null;
                        self = false;
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        other();
                    }

                    @Override
                    public void visitIntInsn(int opcode, int operand) {
                        other();
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        other();
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                        other();
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... bsmArgs) {
                        other();
                    }

                    @Override
                    public void visitJumpInsn(int opcode, Label label) {
                        other();
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        other();
                    }

                    @Override
                    public void visitIincInsn(int var, int increment) {
                        other();
                    }

                    @Override
                    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
                        other();
                    }

                    @Override
                    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
                        other();
                    }

                    @Override
                    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
                        other();
                    }

                    @Override
                    public void visitEnd() {
                        other();
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return ok[0];
    }
}

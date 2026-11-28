import java.util.*;
import java.util.regex.*;
import org.objectweb.asm.*;

/** The game members a mixin names in strings (shadows, accessors, method and @At targets): found in the old release and written for the new one. */
final class MixinMembers {

    final JarPort port;

    MixinMembers(JarPort port) {
        this.port = port;
    }

    static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";

    /**
     * A mixin's code that casts its own this ((Access) (Object) this) relies on each target being that type, or, when the
     * type is another of the mod's mixins (an accessor), one of that mixin's targets. A target the new release no longer
     * makes one fails the cast when the code runs.
     */
    void selfCasts(JarPort.Mod mod, String mixin, List<String> targets) {
        byte[] bytes = mod.bytes.get(mixin);
        if (bytes == null) {
            return;
        }
        Set<String> casts = new TreeSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return (access & Opcodes.ACC_STATIC) != 0 ? null : new SelfCast(casts);
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (String cast : casts) {
            List<String> wanted = cast.equals(mixin) ? List.of()
                : mod.mixinTargets.containsKey(cast) ? mod.mixinTargets.get(cast) : port.a.classes.containsKey(cast) ? List.of(cast) : List.of();
            for (String t : targets) {
                if (!wanted.isEmpty() && port.b.classes.containsKey(port.cls(t)) && wanted.stream().anyMatch(w -> Linking.isA(port.a, t, w))
                    && wanted.stream().noneMatch(w -> Linking.isA(port.b, port.cls(t), port.cls(w)))) {
                    mod.unlinked.add("mixin target no longer a " + wanted.stream().map(Exposure::dotted).collect(java.util.stream.Collectors.joining(" or ")) + ": "
                        + Exposure.dotted(t) + " (in " + Exposure.dotted(mixin) + ")");
                }
            }
        }
    }

    /** Collects the types this (local 0) is cast to straight after it is loaded. */
    static final class SelfCast extends MethodVisitor {
        final Set<String> casts;
        boolean self;

        SelfCast(Set<String> casts) {
            super(Opcodes.ASM9);
            this.casts = casts;
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            self = opcode == Opcodes.ALOAD && var == 0;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (self && opcode == Opcodes.CHECKCAST) {
                casts.add(type);
            }
            self = false;
        }

        @Override
        public void visitInsn(int opcode) {
            self = false;
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            self = false;
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            self = false;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            self = false;
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            self = false;
        }

        @Override
        public void visitLdcInsn(Object value) {
            self = false;
        }
    }

    /** An @Accessor or @Invoker names a target member (its value, or the name Mixin infers); a renamed one is written into the value. */
    void accessor(JarPort.Mod mod, String mixin, List<String> targets, GameDiff.RawMeth m, String[] spec) {
        boolean invoker = spec[0].equals("Invoker");
        String inferred = m.name.replaceFirst(invoker ? "^(call|invoke|new|create)" : "^(get|is|set)", "");
        if (!inferred.isEmpty()) {
            inferred = Character.toLowerCase(inferred.charAt(0)) + inferred.substring(1);
        }
        String name = spec[1] != null ? spec[1] : inferred;
        if (name.equals("<init>") || invoker && spec[1] == null && m.name.matches("^(new|create).*")) {
            return; // a constructor invoker: its descriptor follows the class, which renames already cover
        }
        String key = mixin + "." + m.name + m.desc;
        Type[] args = Type.getArgumentTypes(m.desc);
        String fieldDesc = Type.getReturnType(m.desc) == Type.VOID_TYPE ? (args.length > 0 ? args[args.length - 1].getDescriptor() : null) : Type.getReturnType(m.desc).getDescriptor();
        for (String t : targets) {
            if (!port.a.classes.containsKey(t)) {
                continue;
            }
            if (!invoker) {
                List<String> names = spec[1] != null ? List.of(name) : List.of(name, name.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT));
                for (String n : names) {
                    GameDiff.Cls decl = Exposure.declaring(port.a, t, 'f', n, null);
                    if (decl == null) {
                        continue;
                    }
                    String[] moved = port.route.field(decl.name, n);
                    GameDiff.Cls now = Exposure.declaring(port.b, port.cls(t), 'f', moved[1], null);
                    if (now == null || fieldDesc != null && !now.fields.get(moved[1]).desc.equals(port.desc(fieldDesc))) {
                        mod.unlinked.add("mixin accessor target gone: " + Exposure.dotted(t) + "." + n + " (in " + Exposure.dotted(mixin) + ")");
                    } else if (!moved[1].equals(n)) {
                        mod.accessorTargets.put(key, moved[1]);
                        mod.change("mixin accessor renamed");
                    }
                    return;
                }
                continue;
            }
            GameDiff.Cls decl = Exposure.declaring(port.a, t, 'm', name, m.desc);
            if (decl == null) {
                continue;
            }
            String[] moved = port.route.method(decl.name, name, m.desc);
            String mapped = port.desc(m.desc);
            if (Exposure.declaring(port.b, port.cls(t), 'm', name, mapped) != null) {
                return;
            }
            if (moved[2].equals(mapped) && Exposure.declaring(port.b, port.cls(t), 'm', moved[1], moved[2]) != null) {
                mod.accessorTargets.put(key, moved[1]);
                mod.change("mixin invoker renamed");
            } else {
                mod.unlinked.add("mixin invoker target gone: " + Exposure.dotted(t) + "." + name + m.desc + " (in " + Exposure.dotted(mixin) + ")");
            }
            return;
        }
    }

    /** A mixin's @Shadow field named as its target's field is now named; other fields keep their names. */
    String shadowField(JarPort.Mod mod, String mixin, List<String> targets, String name, String desc) {
        for (String t : targets) {
            if (!port.a.classes.containsKey(t)) {
                continue;
            }
            GameDiff.Cls decl = Exposure.declaring(port.a, t, 'f', name, null);
            if (decl == null) {
                continue;
            }
            // A shadow of another type than the old release's field shadows something the snapshot doesn't hold (a field the
            // loader patches, as NeoForge retypes ParticleEngine.providers): the update did nothing to it.
            if (!decl.fields.get(name).desc.equals(desc)) {
                return name;
            }
            String[] moved = port.route.field(decl.name, name);
            String newDesc = port.desc(desc);
            GameDiff.Cls now = Exposure.declaring(port.b, port.cls(t), 'f', moved[1], null);
            if (now != null && now.fields.get(moved[1]).desc.equals(newDesc)) {
                if (!moved[1].equals(name)) {
                    mod.renamedDecl.put(mixin + "." + name, new String[] { moved[1], newDesc });
                    mod.change("mixin shadow renamed");
                }
                return moved[1];
            }
            // The shadow is the target's field under the mixin's name: each use is ported as a use of that field (what the
            // game's own code now reads instead, or a report naming the field), and the declaration goes, since Mixin
            // refuses a shadow its target no longer has.
            mod.shadowTargets.put(mixin + "." + name, t);
            mod.change("mixin shadow of a field the update broke ported as the target's field");
            return name;
        }
        return name;
    }

    /** A mixin method that shadows a target method (@Shadow, or an abstract one) takes the target's new name. */
    String[] shadowMethod(JarPort.Mod mod, String mixin, List<String> targets, String name, String desc, int access) {
        String bare = name.startsWith("shadow$") ? name.substring(7) : name;
        for (String t : targets) {
            if (!port.a.classes.containsKey(t)) {
                continue;
            }
            GameDiff.Cls decl = Exposure.declaring(port.a, t, 'm', bare, desc);
            if (decl == null) {
                continue;
            }
            String[] moved = port.route.method(decl.name, bare, desc);
            String mapped = port.desc(desc);
            if (Exposure.declaring(port.b, port.cls(t), 'm', bare, mapped) != null) {
                return null;
            }
            if (Exposure.declaring(port.b, port.cls(t), 'm', moved[1], moved[2]) != null && moved[2].equals(mapped)) {
                String prefix = name.substring(0, name.length() - bare.length());
                mod.renamedDecl.put(mixin + "." + name + desc, new String[] { prefix + moved[1], moved[2] });
                mod.change("mixin method renamed");
                return new String[] { prefix + moved[1] };
            }
            if ((access & Opcodes.ACC_ABSTRACT) != 0 || (access & Opcodes.ACC_PRIVATE) == 0) {
                mod.unlinked.add("mixin method gone: " + Exposure.dotted(t) + "." + bare + desc + " (in " + Exposure.dotted(mixin) + ")");
            }
            return null;
        }
        return null;
    }

    static final Set<String> MEMBER_KEYS = Set.of("method", "target", "value");
    static final Map<String, String> ACCESSORS = Map.of(
        "Lorg/spongepowered/asm/mixin/gen/Accessor;", "Accessor",
        "Lorg/spongepowered/asm/mixin/gen/Invoker;", "Invoker"
    );

    /** Mixin annotation strings that name target members ("method", @At "target"): moved to the new names when they link, else reported. */
    AnnotationVisitor mixinStrings(JarPort.Mod mod, List<String> targets, AnnotationVisitor av, String handler) {
        if (av == null || targets.isEmpty()) {
            return av;
        }
        return new AnnotationVisitor(Opcodes.ASM9, av) {
            @Override
            public void visit(String name, Object value) {
                if ("index".equals(name) && value instanceof Integer && handler != null && mod.argIndexes.containsKey(handler)) {
                    super.visit(name, mod.argIndexes.get(handler)); // a @ModifyArg's argument, where the call's new signature has it
                    return;
                }
                super.visit(name, value instanceof String s && name != null && MEMBER_KEYS.contains(name) ? member(mod, targets, name, s, handler) : value);
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                AnnotationVisitor inner = super.visitArray(name);
                if (name == null || !MEMBER_KEYS.contains(name)) {
                    return mixinStrings(mod, targets, inner, handler);
                }
                return new AnnotationVisitor(Opcodes.ASM9, inner) {
                    @Override
                    public void visit(String n, Object value) {
                        super.visit(n, value instanceof String s ? member(mod, targets, name, s, handler) : value);
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String n, String d) {
                        return at(mod, targets, super.visitAnnotation(n, d), d, handler);
                    }
                };
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String desc) {
                return at(mod, targets, super.visitAnnotation(name, desc), desc, handler);
            }
        };
    }

    /**
     * A nested annotation of a handler's: an @At whose place moved (Mod.points, see moveAt) is written with its new
     * value, target and ordinal, read whole first as its elements come in any order; anything else as mixinStrings writes it.
     */
    AnnotationVisitor at(JarPort.Mod mod, List<String> targets, AnnotationVisitor av, String desc, String handler) {
        if (av == null || !MixinRetargets.AT.equals(desc) || handler == null || mod.points.keySet().stream().noneMatch(k -> k.startsWith(handler + " "))) {
            return mixinStrings(mod, targets, av, handler);
        }
        AnnotationVisitor rest = mixinStrings(mod, targets, av, handler);
        String[] read = new String[3];
        return new AnnotationVisitor(Opcodes.ASM9, rest) {
            @Override
            public void visit(String name, Object value) {
                if ("value".equals(name) && value instanceof String v) {
                    read[0] = v;
                } else if ("target".equals(name) && value instanceof String v) {
                    read[1] = v;
                } else if ("ordinal".equals(name) && value instanceof Integer i) {
                    read[2] = String.valueOf(i);
                } else {
                    super.visit(name, value);
                }
            }

            @Override
            public void visitEnd() {
                String[] moved = mod.points.get(handler + " " + read[0] + " " + read[1] + " " + read[2]);
                if (moved != null) {
                    mod.change("mixin injection point moved where the game's new code has it");
                }
                String[] out = moved != null ? moved : read;
                // What did not change is written as mixinStrings writes it.
                if (out[0] != null) {
                    av.visit("value", out[0].equals(read[0]) ? member(mod, targets, "value", out[0], handler) : out[0]);
                }
                if (out[1] != null) {
                    av.visit("target", out[1].equals(read[1]) ? member(mod, targets, "target", out[1], handler) : out[1]);
                }
                if (out[2] != null) {
                    av.visit("ordinal", Integer.parseInt(out[2]));
                }
                super.visitEnd();
            }
        };
    }

    static final Pattern REFERENCE = Pattern.compile("([\\w.$]+)::(\\w+)");
    static final Pattern STATIC_CALL = Pattern.compile("([\\w.$]+)\\.(\\w+)\\(\\)");
    static final Pattern STATIC_FIELD = Pattern.compile("([\\w.$]+)\\.(\\w+)");
    static final Pattern MEMBER = Pattern.compile("(?:L([^;]+);)?([\\w$<>]+)(?:(\\(.*)|:(.+))?");

    /**
     * One member string of a mixin annotation in the new release's names, or as written when it cannot be read or does
     * not link; handler is the class.method+desc whose annotation it is in, where a retarget may apply.
     */
    String member(JarPort.Mod mod, List<String> targets, String key, String text, String handler) {
        String moved = handler == null ? null : mod.retargets.get(handler + " " + text);
        if (moved != null) {
            mod.change("mixin injection moved to the target's new signature");
            return moved;
        }
        Matcher ctor = key.equals("method") && handler != null && mod.injectors.containsKey(handler) ? MEMBER.matcher(text) : null;
        if (ctor != null && ctor.matches() && "<init>".equals(ctor.group(2))) {
            String changed = constructorChanged(ctor.group(1) != null ? List.of(ctor.group(1)) : targets, ctor.group(3), handler.substring(handler.indexOf('(')));
            if (changed != null) {
                // By name alone Mixin refuses a handler whose arguments no constructor takes. One named by its old
                // descriptor is only not found: a break where the injector requires a place and none of its other
                // selectors gives one (ferritecore names three constructors of one class; packet-fixer requires none).
                MixinRetargets.Injector inj = mod.injectors.get(handler);
                String mixin = handler.substring(0, handler.indexOf('.'));
                int require = inj.require()[0] >= 0 ? inj.require()[0] : mod.requires.getOrDefault(mixin, 0);
                boolean elsewhere = inj.methods().stream().filter(s -> !s.equals(text)).anyMatch(s -> {
                    Matcher other = MEMBER.matcher(s);
                    return !other.matches() || !"<init>".equals(other.group(2))
                        || constructorChanged(other.group(1) != null ? List.of(other.group(1)) : targets, other.group(3), handler.substring(handler.indexOf('('))) == null;
                });
                if (ctor.group(3) != null && (require == 0 || elsewhere)) {
                    mod.silent.add(changed.replace("mixin target changed signature", "mixin injection finds no place"));
                } else {
                    mod.unlinked.add(changed);
                }
                return text;
            }
        }
        Found f = find(targets, key, text);
        if (f.report() != null && f.report().contains(".lambda$") && handler != null && mod.injectors.containsKey(handler)) {
            // A lambda the new release no longer has stops the game only where the injector requires its place.
            MixinRetargets.Injector inj = mod.injectors.get(handler);
            String mixin = handler.substring(0, handler.indexOf('.'));
            int require = inj.require()[0] >= 0 ? inj.require()[0] : mod.requires.getOrDefault(mixin, 0);
            if (require == 0) {
                mod.silent.add(f.report().replace("mixin target gone", "mixin injection finds no place"));
                return f.text();
            }
        }
        if (f.report() != null) {
            mod.unlinked.add(f.report());
        }
        if (f.change() != null) {
            mod.change(f.change());
        }
        return f.text();
    }

    /**
     * What a mixin annotation string names in the new release. kind: 'f' or 'm' when it links (owner, name and desc in
     * new names; a field's desc is null when the string gives none), 's' when the method only took other parameters
     * (desc its new descriptor, null when that is not known; changed the string naming it), 0 otherwise. text is what
     * is written when nothing is moved, report what is reported then.
     */
    record Found(String text, String report, String change, char kind, String owner, String name, String desc, String changed) {}

    /**
     * A mixin selector naming one of the game's lambdas (lambda$charTyped$7), which javac numbers anew in each release
     * and the snapshot leaves out: read from the game's own classes. A name and descriptor the new class still has are
     * kept, as Mixin finds them; one it lacks follows the one new lambda whose code reads alike, or is reported gone.
     * Null when the game's code cannot be read.
     */
    Found lambdaTarget(List<String> owners, boolean named, String name, String mdesc, String text) {
        GameCode oldCode = port.code(port.a), newCode = port.code(port.b);
        if (oldCode == null || newCode == null) {
            return null;
        }
        for (String o : owners) {
            Map<String, String> before = port.a.classes.containsKey(o) ? oldCode.bodies(o, port::cls) : null;
            Map<String, String> now = before == null ? null : newCode.bodies(port.cls(o), x -> x);
            if (now == null) {
                continue;
            }
            for (var e : before.entrySet()) {
                String k = e.getKey();
                if (!k.startsWith(name + "(") || mdesc != null && !k.equals(name + port.desc(mdesc))) {
                    continue;
                }
                String d = k.substring(name.length());
                if (now.containsKey(k)) {
                    return new Found(text, null, null, 'm', port.cls(o), name, d, null); // Mixin finds it by name and descriptor
                }
                List<String> same = now.entrySet().stream().filter(n -> n.getKey().startsWith("lambda$") && n.getKey().endsWith(d)
                    && n.getKey().indexOf('(') == n.getKey().length() - d.length() && n.getValue().equals(e.getValue())).map(Map.Entry::getKey).toList();
                if (same.size() == 1) {
                    String moved = same.get(0).substring(0, same.get(0).length() - d.length());
                    return new Found((named ? "L" + port.cls(o) + ";" : "") + moved + (mdesc == null ? "" : d), null, "mixin target renamed", 'm', port.cls(o), moved, d, null);
                }
                return new Found(text, "mixin target gone: " + Exposure.dotted(o) + "." + name + d, null, (char) 0, null, null, null, null);
            }
        }
        return null;
    }

    /**
     * An injector into a target's constructor that the update reshaped: named with its old descriptor, which the new class
     * lacks, or by name alone with a handler that takes the old constructor's arguments before its CallbackInfo, which
     * no constructor of the new class takes (Mixin then refuses the handler's descriptor). The report, or null.
     */
    String constructorChanged(List<String> owners, String mdesc, String handlerDesc) {
        Type[] params = Type.getArgumentTypes(handlerDesc);
        int ci = MixinRetargets.callbackAt(params);
        List<Type> captured = ci > 0 ? Arrays.stream(params, 0, ci).map(t -> Type.getType(port.desc(t.getDescriptor()))).toList() : List.of();
        for (String o : owners) {
            GameDiff.Cls was = port.a.classes.get(o), now = port.b.classes.get(port.cls(o));
            if (was == null || now == null) {
                continue;
            }
            for (GameDiff.Meth k : was.methods.values()) {
                if (!k.name.equals("<init>") || (mdesc != null ? !k.desc.equals(mdesc) : captured.isEmpty() || !takesExactly(port.desc(k.desc), captured))) {
                    continue;
                }
                boolean kept = mdesc != null ? now.methods.containsKey("<init>" + port.desc(mdesc))
                    : now.methods.values().stream().anyMatch(n -> n.name.equals("<init>") && takesExactly(n.desc, captured));
                if (!kept) {
                    return "mixin target changed signature: " + Exposure.dotted(o) + ".<init>" + k.desc;
                }
            }
        }
        return null;
    }

    /**
     * A constructor the update gave other parameters (1.20's ServerLevel took a RandomSequences 1.19.4's does not), named
     * with its old descriptor: followed as a method whose parameters changed ('s') to the new class's constructor the
     * route map gives, or else to its one constructor whose parameters are the old ones with one or two added or dropped,
     * in their order (one at least on both sides: a class's no-argument constructor is no reshaped one). Null where the new
     * class keeps the old descriptor, or no one constructor is found.
     */
    Found constructorMoved(List<String> owners, boolean named, String mdesc, String text) {
        for (String o : owners) {
            GameDiff.Cls was = port.a.classes.get(o), now = port.b.classes.get(port.cls(o));
            if (was == null || now == null || !was.methods.containsKey("<init>" + mdesc) || now.methods.containsKey("<init>" + port.desc(mdesc))) {
                continue;
            }
            String[] moved = port.route.method(o, "<init>", mdesc);
            GameDiff.Meth found = moved[1].equals("<init>") ? now.methods.get("<init>" + moved[2]) : null;
            if (found == null) {
                Type[] old = Type.getArgumentTypes(port.desc(mdesc));
                List<GameDiff.Meth> fits = now.methods.values().stream().filter(k -> k.name.equals("<init>")).filter(k -> {
                    Type[] args = Type.getArgumentTypes(k.desc);
                    return Math.min(old.length, args.length) > 0 && Math.abs(old.length - args.length) <= 2 && (inOrder(old, args) || inOrder(args, old));
                }).toList();
                found = fits.size() == 1 ? fits.get(0) : null;
            }
            if (found == null) {
                return null;
            }
            String report = "mixin target changed signature: " + Exposure.dotted(o) + ".<init>" + mdesc;
            return new Found(text, report, null, 's', port.cls(o), "<init>", found.desc, (named ? "L" + port.cls(o) + ";" : "") + "<init>" + found.desc);
        }
        return null;
    }

    /** Whether every type of few stands in many in their order. */
    static boolean inOrder(Type[] few, Type[] many) {
        int j = 0;
        for (int i = 0; i < many.length && j < few.length; i++) {
            j += many[i].equals(few[j]) ? 1 : 0;
        }
        return j == few.length;
    }

    /** Whether a constructor takes exactly the arguments a handler captures before its CallbackInfo, as Mixin wants. */
    static boolean takesExactly(String desc, List<Type> first) {
        return Arrays.asList(Type.getArgumentTypes(desc)).equals(first);
    }

    static Found keep(String text) {
        return new Found(text, null, null, (char) 0, null, null, null, null);
    }

    Found find(List<String> targets, String key, String text) {
        if (text.contains("*") || text.contains(" ") || text.isEmpty() || key.equals("value") && !text.contains("(") && !text.contains(";")) {
            return keep(text); // wildcards, @At("HEAD") and friends
        }
        Matcher m = MEMBER.matcher(text);
        if (!m.matches()) {
            return keep(text);
        }
        String owner = m.group(1), name = m.group(2), mdesc = m.group(3), fdesc = m.group(4);
        if (key.equals("method") && name.startsWith("lambda$")) {
            Found lambda = lambdaTarget(owner != null ? List.of(owner) : targets, owner != null, name, mdesc, text);
            if (lambda != null) {
                return lambda;
            }
        }
        if (name.equals("<init>") || name.equals("<clinit>")) {
            Found reshaped = mdesc == null || name.equals("<clinit>") ? null : constructorMoved(owner != null ? List.of(owner) : targets, owner != null, mdesc, text);
            if (reshaped != null) {
                return reshaped;
            }
            return mdesc == null ? keep(text) : new Found((owner == null ? "" : "L" + port.cls(owner) + ";") + name + port.desc(mdesc), null, null, 'm', owner == null ? null : port.cls(owner), name, port.desc(mdesc), null);
        }
        if (owner != null && !port.a.classes.containsKey(owner) && !port.b.classes.containsKey(owner) && mdesc != null) {
            return new Found(text, null, null, 'm', owner, name, mdesc, null); // the JDK's or a library's: the same in both
        }
        List<String> owners = owner != null ? List.of(owner) : targets;
        for (String o : owners) {
            if (!port.a.classes.containsKey(o)) {
                continue;
            }
            if (fdesc != null || (mdesc == null && Exposure.declaring(port.a, o, 'f', name, null) != null && owner != null)) {
                GameDiff.Cls decl = Exposure.declaring(port.a, o, 'f', name, null);
                if (decl == null) {
                    continue;
                }
                String[] moved = port.route.field(decl.name, name);
                if (Exposure.declaring(port.b, port.cls(o), 'f', moved[1], null) == null) {
                    return new Found(text, "mixin target gone: " + Exposure.dotted(o) + "." + name, null, (char) 0, null, null, null, null);
                }
                return new Found((owner == null ? "" : "L" + port.cls(o) + ";") + moved[1] + (fdesc == null ? "" : ":" + port.desc(fdesc)), null,
                    moved[1].equals(name) ? null : "mixin target renamed", 'f', port.cls(o), moved[1], fdesc == null ? null : port.desc(fdesc), null);
            }
            List<String> descs = new ArrayList<>();
            GameDiff.Cls holder = port.a.classes.get(o);
            for (GameDiff.Cls c = holder; c != null && descs.isEmpty(); c = c.sup == null ? null : port.a.classes.get(c.sup)) {
                for (GameDiff.Meth gm : c.methods.values()) {
                    if (gm.name.equals(name) && (mdesc == null || gm.desc.equals(mdesc))) {
                        descs.add(gm.desc);
                    }
                }
            }
            if (descs.isEmpty()) {
                // A descriptor the old game jar lacks (the loader patched that method's parameters, as NeoForge 1.20.4 did
                // WorldOpenFlows.loadLevel): the string is kept as written, so Mixin finds it only if the new class has a
                // method of that name at all.
                if (mdesc != null && !named(port.a, o, name).isEmpty() && named(port.b, port.cls(o), name).isEmpty()) {
                    return new Found(text, "mixin target gone: " + Exposure.dotted(o) + "." + name + mdesc, null, (char) 0, null, null, null, null);
                }
                continue;
            }
            String d = descs.get(0);
            GameDiff.Cls decl = Exposure.declaring(port.a, o, 'm', name, d);
            String[] moved = port.route.method(decl == null ? o : decl.name, name, d);
            String mapped = port.desc(d);
            if (Exposure.declaring(port.b, port.cls(o), 'm', name, mapped) != null) {
                return new Found((owner == null ? "" : "L" + port.cls(o) + ";") + name + (mdesc == null ? "" : mapped), null, null, 'm', port.cls(o), name, mapped, null);
            }
            if (Exposure.declaring(port.b, port.cls(o), 'm', moved[1], moved[2]) != null && moved[2].equals(mapped)) {
                return new Found((owner == null ? "" : "L" + port.cls(o) + ";") + moved[1] + (mdesc == null ? "" : moved[2]), null, "mixin target renamed", 'm', port.cls(o), moved[1], moved[2], null);
            }
            List<GameDiff.Meth> sameName = named(port.b, port.cls(o), moved[1]);
            String report = "mixin target " + (sameName.isEmpty() ? "gone" : "changed signature") + ": " + Exposure.dotted(o) + "." + name + d;
            if (sameName.isEmpty()) {
                GameDiff.Meth went = key.equals("method") && decl != null && decl.name.equals(o) ? port.mixinMovedCode.codeMoved(o, name, d) : null;
                if (went != null) {
                    // Followed as a method whose parameters changed: the handler moves only where Mixin will accept it there.
                    return new Found(text, report, null, 's', port.cls(o), went.name, went.desc, (owner == null ? "" : "L" + port.cls(o) + ";") + went.name + went.desc);
                }
                return new Found(text, report, null, (char) 0, null, null, null, null);
            }
            // Only its parameters changed: the route map's new signature, or the one method of that name on both sides.
            GameDiff.Meth now = !moved[2].equals(mapped) && Exposure.declaring(port.b, port.cls(o), 'm', moved[1], moved[2]) != null
                ? Exposure.declaring(port.b, port.cls(o), 'm', moved[1], moved[2]).methods.get(moved[1] + moved[2])
                : sameName.size() == 1 && named(port.a, o, name).size() == 1 ? sameName.get(0) : alike(sameName, d);
            String prefix = owner == null ? "" : "L" + port.cls(o) + ";";
            return now == null
                ? new Found(text, report, null, 's', port.cls(o), moved[1], null, null)
                : new Found(text, report, null, 's', port.cls(o), now.name, now.desc, prefix + now.name + (mdesc == null ? "" : now.desc));
        }
        return keep(text);
    }

    /**
     * Of a name's several methods in the new release, the one that takes what the old method d (old names) took, in its
     * order, but where the old type is a class the new release does not have (1.20's drawBar(GuiGraphics, int, int,
     * BossEvent) and drawBar(GuiGraphics, int, int, BossEvent, int, int) are 1.19.4's two drawBar(PoseStack, ...)); null
     * unless exactly one does.
     */
    GameDiff.Meth alike(List<GameDiff.Meth> sameName, String d) {
        Type[] was = Type.getArgumentTypes(d);
        GameDiff.Meth out = null;
        for (GameDiff.Meth n : sameName) {
            Type[] now = Type.getArgumentTypes(n.desc);
            boolean fits = now.length == was.length && Type.getReturnType(port.desc(d)).equals(Type.getReturnType(n.desc));
            for (int i = 0; i < was.length && fits; i++) {
                fits = Type.getType(port.desc(was[i].getDescriptor())).equals(now[i])
                    || was[i].getSort() == Type.OBJECT && port.a.classes.containsKey(was[i].getInternalName()) && !port.b.classes.containsKey(was[i].getInternalName());
            }
            if (fits) {
                if (out != null) {
                    return null;
                }
                out = n;
            }
        }
        return out;
    }

    /** Every method of that name a call on owner reaches through its superclasses in one release. */
    static List<GameDiff.Meth> named(GameDiff.Snap s, String owner, String name) {
        List<GameDiff.Meth> out = new ArrayList<>();
        for (GameDiff.Cls c = s.classes.get(owner); c != null; c = c.sup == null ? null : s.classes.get(c.sup)) {
            for (GameDiff.Meth gm : c.methods.values()) {
                if (gm.name.equals(name) && out.stream().noneMatch(x -> x.desc.equals(gm.desc))) {
                    out.add(gm);
                }
            }
        }
        return out;
    }

    /** The old release's method a mixin "method" string names in one of the targets, or null. */
    GameDiff.Meth oldMethod(List<String> targets, String text) {
        Matcher m = MEMBER.matcher(text);
        if (!m.matches() || m.group(4) != null) {
            return null;
        }
        for (String o : m.group(1) != null ? List.of(m.group(1)) : targets) {
            List<GameDiff.Meth> found = named(port.a, o, m.group(2)).stream().filter(x -> m.group(3) == null || x.desc.equals(m.group(3))).toList();
            if (!found.isEmpty()) {
                return found.get(0);
            }
        }
        return null;
    }
}

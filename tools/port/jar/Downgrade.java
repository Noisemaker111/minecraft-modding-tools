import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;

/**
 * Downgrade - carry a mod's compiled classes to a release that runs an older Java than they were built for.
 *
 * The release's JVM refuses a class file newer than itself before reading any of it, so each class is written in the
 * release's class-file version, and what that JVM cannot run is rewritten the way javac writes it for that Java:
 *   string concatenation (Java 9's StringConcatFactory)   -> a StringBuilder chain in a helper of the class
 *   records (Java 16: Record, ObjectMethods)               -> plain classes with equals/hashCode/toString written out,
 *                                                             their component names kept for reflection (RECORD_COMPONENTS)
 *   nestmates (Java 11: NestHost/NestMembers)              -> the private members another class of the nest uses opened
 *                                                             to the package, a class's calls to its own private methods
 *                                                             made with invokespecial
 *   pattern switches (Java 21: typeSwitch, enumSwitch)     -> a helper that tests the labels in order (classes, arrays,
 *                                                             strings, integers, enum constants)
 *   JDK interfaces a newer Java inserted (SequencedMap)    -> the interface above them the older Java has (inserted)
 *   sealed classes (Java 17: PermittedSubclasses)          -> the attribute left out
 *   MatchException (Java 21)                               -> RuntimeException, which it extends
 * Each is done only where the release's Java lacks it. Every reference to the JDK is then linked against the JDK the
 * release runs, as the JDK's own record of each release's API (lib/ct.sym, what javac --release reads) declares it. A
 * method that only gained a narrower return type (ByteBuffer.flip() returning ByteBuffer from Java 9) is called as the
 * older Java declares it and cast; one the older Java lacks is redirected to a stand-in (knowledge/standins/jdk/: the
 * method written from the JDK's documented contract, carried in the mod under a package of its own), and a static field
 * it lacks (ProcessBuilder.Redirect.DISCARD) to the stand-in's method of the field's name; anything else is reported as a
 * break, never guessed.
 */
final class Downgrade {

    /** The field a record lowered below Java 16 keeps its component names in; knowledge/standins/jdk/java/lang/Class.java reads it. */
    static final String RECORD_COMPONENTS = "mmt$recordComponents";

    final int target; // the release's Java (8, 16, 17, 21 ...)
    final int major; // its class-file version (52 for Java 8)
    final Api declared; // what the release's Java declares
    final Standins standins;

    Downgrade(int target, Standins standins) {
        this.target = target;
        this.major = target + 44;
        this.declared = Api.of(target);
        this.standins = standins;
    }

    static int major(byte[] bytes) {
        return bytes.length < 8 ? 0 : (bytes[6] & 0xFF) << 8 | (bytes[7] & 0xFF);
    }

    /**
     * One jar's classes (entry name -> bytes), lowered in place. breaks gets what the release's Java cannot run, changed
     * counts what was rewritten, and the stand-ins the classes now call are returned (entry name -> bytes) under home, a
     * package of the jar's own. Classes the release's Java already reads are left as they are.
     */
    Map<String, byte[]> lower(Map<String, byte[]> classes, String home, Set<String> breaks, Map<String, Integer> changed) {
        Map<String, Info> infos = new HashMap<>();
        boolean any = false;
        for (var e : classes.entrySet()) {
            Info info = Info.read(e.getValue());
            if (info != null) {
                infos.put(info.name, info);
                any |= info.major > major;
            }
        }
        if (!any) {
            return Map.of();
        }
        // Java 11 lets a class reach the private members of the classes of its nest (an inner class its outer's private
        // field); the older JVM checks access as Java 8's javac wrote it, so each member another class reaches is opened
        // to the package, where every class of a nest is.
        Set<String> widen = new HashSet<>();
        if (target < 11) {
            for (byte[] bytes : classes.values()) {
                Info from = Info.read(bytes);
                if (from != null && from.major >= 55) {
                    nestReach(bytes, from.name, infos, widen);
                }
            }
        }
        Set<String> used = new TreeSet<>();
        Map<String, String> lacked = standins.classesLacked(declared, home);
        for (var e : classes.entrySet()) {
            Info info = infos.get(Info.name(e.getValue()));
            if (info == null || info.major <= major) {
                continue;
            }
            e.setValue(lowerClass(e.getValue(), info, widen, lacked, home, used, breaks, changed));
            changed.merge("classes lowered to Java " + target, 1, Integer::sum);
        }
        return standins.carry(used, home);
    }

    /** The private members of other classes of the jar that one class reaches: what Java 11's nestmates allow. */
    static void nestReach(byte[] bytes, String self, Map<String, Info> infos, Set<String> widen) {
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    void reach(String owner, String member) {
                        Info o = infos.get(owner);
                        if (!owner.equals(self) && o != null && o.privates.contains(member)) {
                            widen.add(owner + "." + member);
                        }
                    }

                    void handle(Object h) {
                        if (h instanceof Handle x) {
                            reach(x.getOwner(), x.getTag() <= Opcodes.H_PUTSTATIC ? x.getName() + ":" + x.getDesc() : x.getName() + x.getDesc());
                        }
                    }

                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        reach(owner, n + d);
                    }

                    @Override
                    public void visitFieldInsn(int op, String owner, String n, String d) {
                        reach(owner, n + ":" + d);
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        handle(value);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
                        handle(bsm);
                        for (Object a : args) {
                            handle(a);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    /** What lowering needs to know of a class of the jar before rewriting any: its name, version, kind and private members. */
    record Info(String name, int major, boolean itf, Set<String> privates) {

        static String name(byte[] bytes) {
            try {
                return new ClassReader(bytes).getClassName();
            } catch (RuntimeException unreadable) {
                return null;
            }
        }

        static Info read(byte[] bytes) {
            ClassReader r;
            try {
                r = new ClassReader(bytes);
            } catch (RuntimeException unreadable) {
                return null; // ASM cannot read it, nor lower it: left as it came
            }
            Set<String> privates = new HashSet<>();
            r.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                    if ((access & Opcodes.ACC_PRIVATE) != 0) {
                        privates.add(name + ":" + desc);
                    }
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                    if ((access & Opcodes.ACC_PRIVATE) != 0) {
                        privates.add(name + desc);
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return new Info(r.getClassName(), Downgrade.major(bytes), (r.getAccess() & Opcodes.ACC_INTERFACE) != 0, privates);
        }
    }

    byte[] lowerClass(byte[] bytes, Info info, Set<String> widen, Map<String, String> lacked, String home, Set<String> used, Set<String> breaks, Map<String, Integer> changed) {
        Map<String, String> types = new HashMap<>(lacked);
        if (target < 16) {
            types.put("java/lang/Record", "java/lang/Object");
        }
        if (target < 21) {
            types.put("java/lang/MatchException", "java/lang/RuntimeException");
        }
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        Lowering lowering = new Lowering(w, info, widen, home, used, breaks, changed);
        Set<String> named = new TreeSet<>();
        new ClassReader(bytes).accept(new ClassRemapper(lowering, new org.objectweb.asm.commons.Remapper(Opcodes.ASM9) {
            @Override
            public String map(String name) {
                String to = types.get(name);
                if (to == null && jdk(name) && declared.cls(name) == null) {
                    to = inserted(name);
                    if (to != null) {
                        types.put(name, to);
                        changed.merge("JDK interfaces Java " + target + " lacks named as the interface they extend", 1, Integer::sum);
                    }
                }
                if (to == null) {
                    return name;
                }
                if (lacked.containsKey(name)) {
                    named.add(name);
                }
                return to;
            }
        }), 0);
        for (String n : named) {
            used.add(Standins.PREFIX + n);
        }
        if (!named.isEmpty()) {
            changed.merge("JDK classes Java " + target + " lacks named as their stand-ins", 1, Integer::sum);
        }
        return w.toByteArray();
    }

    /**
     * The interface the release's Java has that a newer Java inserted a JDK interface below, when the newer one only adds
     * methods to it: Java 21's SequencedMap between Map and LinkedHashMap, SequencedCollection below Collection,
     * SequencedSet below Set. A value of the newer type is one of the older wherever it came from (the release's own
     * LinkedHashMap, a game field that is a Map there), so the class names the older type, and a call to a method only
     * the newer one declares is linked against the older as any other JDK call (a stand-in, or a break). It is the one
     * interface of those above it the release's Java has that every other one is above (Set for SequencedSet, beside
     * Collection); null when there is none (a class, or an interface with nothing but Object above it there).
     */
    String inserted(String name) {
        Api now = Api.of(Runtime.version().feature());
        Api.Cls cls = now.cls(name);
        if (cls == null || !cls.itf()) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (Api.Cls c : now.above(name)) {
            if (!c.name().equals(name) && !c.name().equals("java/lang/Object") && declared.cls(c.name()) != null) {
                kept.add(c.name());
            }
        }
        for (String c : kept) {
            Set<String> over = new HashSet<>();
            for (Api.Cls a : now.above(c)) {
                over.add(a.name());
            }
            if (over.containsAll(kept)) {
                return c;
            }
        }
        return null;
    }

    static String dotted(String internal) {
        return internal.replace('/', '.');
    }

    static boolean jdk(String internal) {
        return internal.startsWith("java/");
    }

    /** Rewrites one class for the older Java and checks what it asks of the JDK. */
    final class Lowering extends ClassVisitor {

        final Info info;
        final Set<String> widen, used, breaks;
        final Map<String, Integer> changed;
        final List<java.util.function.Consumer<ClassVisitor>> helpers = new ArrayList<>();
        final Map<String, String> made = new HashMap<>(); // what a helper does -> its name, so one is written once
        final String tag, home;
        List<String> components; // a record's component names, in order, when the release's Java has no records

        Lowering(ClassVisitor next, Info info, Set<String> widen, String home, Set<String> used, Set<String> breaks, Map<String, Integer> changed) {
            super(Opcodes.ASM9, next);
            this.info = info;
            this.home = home;
            this.widen = widen;
            this.used = used;
            this.breaks = breaks;
            this.changed = changed;
            // Helpers are named for their class: Mixin merges a mixin's methods into its target, beside other mixins' helpers.
            this.tag = Integer.toHexString(info.name.hashCode() & 0x7fffffff);
        }

        void change(String kind) {
            changed.merge(kind, 1, Integer::sum);
        }

        void lacks(String what) {
            breaks.add("not in Java " + target + ": " + what + " (in " + dotted(info.name) + ")");
        }

        void needClass(String internal) {
            if (jdk(internal) && declared.cls(internal) == null) {
                lacks("class " + dotted(internal));
            }
        }

        void needType(Type t) {
            while (t.getSort() == Type.ARRAY) {
                t = t.getElementType();
            }
            if (t.getSort() == Type.OBJECT) {
                needClass(t.getInternalName());
            }
        }

        void needDesc(String desc) {
            if (desc.startsWith("(")) {
                for (Type t : Type.getArgumentTypes(desc)) {
                    needType(t);
                }
                needType(Type.getReturnType(desc));
            } else {
                needType(Type.getType(desc));
            }
        }

        @Override
        public void visit(int version, int access, String name, String sig, String sup, String[] ifaces) {
            if (sup != null) {
                needClass(sup);
            }
            for (String i : ifaces == null ? new String[0] : ifaces) {
                needClass(i);
            }
            // A class implementing an interface named as the one it extends (inserted) and that one too lists it once:
            // the JVM refuses a class naming one interface twice.
            String[] distinct = ifaces == null ? null : new LinkedHashSet<>(List.of(ifaces)).toArray(String[]::new);
            if (target < 16 && (access & Opcodes.ACC_RECORD) != 0) {
                components = new ArrayList<>();
            }
            super.visit(major, access & ~Opcodes.ACC_RECORD, name, sig, sup, distinct);
        }

        @Override
        public void visitNestHost(String host) {
            if (target >= 11) {
                super.visitNestHost(host);
            }
        }

        @Override
        public void visitNestMember(String member) {
            if (target >= 11) {
                super.visitNestMember(member);
            }
        }

        @Override
        public void visitPermittedSubclass(String sub) {
            if (target >= 17) {
                super.visitPermittedSubclass(sub);
            }
        }

        @Override
        public RecordComponentVisitor visitRecordComponent(String name, String desc, String sig) {
            if (target >= 16) {
                return super.visitRecordComponent(name, desc, sig);
            }
            components.add(name);
            return null;
        }

        int opened(int access, String member) {
            if ((access & Opcodes.ACC_PRIVATE) == 0 || !widen.contains(info.name + "." + member)) {
                return access;
            }
            change("private members opened to their nest (Java 11 nestmates)");
            // An interface's members are public or private; a class's opened member stays in its package, as the nest is.
            return access & ~Opcodes.ACC_PRIVATE | (info.itf ? Opcodes.ACC_PUBLIC : 0);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
            needDesc(desc);
            return super.visitField(opened(access, name + ":" + desc), name, desc, sig, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
            needDesc(desc);
            return new Code(super.visitMethod(opened(access, name + desc), name, desc, sig, ex));
        }

        @Override
        public void visitEnd() {
            if (components != null) {
                // A record on a Java without records is a plain class; what reflection asks of it as a record
                // (Class.isRecord, getRecordComponents: config and network serializers) the stand-ins read from here.
                cv.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, RECORD_COMPONENTS, "Ljava/lang/String;", null,
                    String.join(";", components)).visitEnd();
                change("record components kept for reflection (Java 16 records)");
            }
            for (int i = 0; i < helpers.size(); i++) {
                helpers.get(i).accept(cv);
            }
            super.visitEnd();
        }

        /** A helper method of this class, written at its end; the same helper asked twice is one method. */
        String helper(String kind, String key, String desc, java.util.function.Consumer<MethodVisitor> body) {
            return made.computeIfAbsent(kind + "|" + key + "|" + desc, k -> {
                String name = "mmt$" + kind + "$" + tag + "$" + made.size();
                helpers.add(v -> {
                    MethodVisitor m = v.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, name, desc, null, null);
                    m.visitCode();
                    body.accept(m);
                    m.visitMaxs(0, 0);
                    m.visitEnd();
                });
                return name;
            });
        }

        /** A member of a JDK class the release's Java lacks, named as its stand-in now: a break unless the stand-in answers it. */
        void standinMember(String owner, String member) {
            if (!owner.startsWith(home) || !standins.classes.containsKey(Standins.PREFIX + owner.substring(home.length()))) {
                return;
            }
            if (!standins.answers(Standins.PREFIX + owner.substring(home.length()), inStandin(member), declared)) {
                lacks(dotted(owner.substring(home.length())) + "." + inJdk(member));
            }
        }

        /** A descriptor in the stand-ins' own names, as they were compiled. */
        String inStandin(String desc) {
            return desc.replace("L" + home, "L" + Standins.PREFIX);
        }

        /** A descriptor in the names of the stand-ins this jar carries. */
        String inHome(String desc) {
            return desc.replace("L" + Standins.PREFIX + "java/", "L" + home + "java/");
        }

        /** A descriptor in the JDK's names, as a break names it. */
        String inJdk(String desc) {
            return desc.replace("L" + home, "L");
        }

        boolean stillPrivate(String member) {
            return info.privates.contains(member) && !widen.contains(info.name + "." + member);
        }

        final class Code extends MethodVisitor {

            Code(MethodVisitor next) {
                super(Opcodes.ASM9, next);
            }

            // The objects made (new) and not yet constructed, innermost first, as javac constructs them: class, and whether
            // the next instruction duplicated it (javac's new X; dup), which a constructor stand-in needs.
            final Deque<String[]> news = new ArrayDeque<>();
            String[] fresh; // the new just visited, until the next instruction

            void next(int op) {
                if (fresh != null && op == Opcodes.DUP) {
                    fresh[1] = "dup";
                }
                fresh = null;
            }

            @Override
            public void visitInsn(int op) {
                next(op);
                super.visitInsn(op);
            }

            @Override
            public void visitIntInsn(int op, int operand) {
                next(op);
                super.visitIntInsn(op, operand);
            }

            @Override
            public void visitVarInsn(int op, int var) {
                next(op);
                super.visitVarInsn(op, var);
            }

            @Override
            public void visitJumpInsn(int op, Label label) {
                next(op);
                super.visitJumpInsn(op, label);
            }

            @Override
            public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                if (target < 11 && owner.equals(info.name) && (op == Opcodes.INVOKEVIRTUAL || op == Opcodes.INVOKEINTERFACE) && stillPrivate(name + desc)) {
                    // Java 11's javac calls a class's own private methods virtually; before, only invokespecial reaches them.
                    op = Opcodes.INVOKESPECIAL;
                    change("calls to own private methods made with invokespecial (Java 11 nestmates)");
                }
                next(op);
                standinMember(owner, name + desc);
                // A constructor call on an object this method made (new) and duplicated, not a constructor's super(...) or this(...).
                boolean made = false;
                if (op == Opcodes.INVOKESPECIAL && name.equals("<init>") && !news.isEmpty() && news.peek()[0].equals(owner)) {
                    made = news.pop()[1] != null;
                }
                if (owner.startsWith("[") || !jdk(owner)) {
                    super.visitMethodInsn(op, owner, name, desc, itf);
                    return;
                }
                if (declared.cls(owner) != null && declared.method(owner, name, desc)) {
                    super.visitMethodInsn(op, owner, name, desc, itf);
                    return;
                }
                String init = desc.substring(0, desc.indexOf(')') + 1) + "L" + owner + ";";
                if (made && declared.cls(owner) != null && standins.methods.contains(Standins.PREFIX + owner + ".init" + inStandin(init))) {
                    // The made object is left unconstructed and dropped: the stand-in makes the one the code goes on with.
                    used.add(Standins.PREFIX + owner);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, home + owner, "init", inHome(init), false);
                    super.visitInsn(Opcodes.DUP_X2);
                    super.visitInsn(Opcodes.POP);
                    super.visitInsn(Opcodes.POP);
                    super.visitInsn(Opcodes.POP);
                    change("JDK constructors Java " + target + " lacks called through stand-ins");
                    return;
                }
                String narrower = declared.cls(owner) == null || op == Opcodes.INVOKESTATIC ? null : declared.wider(owner, name, desc);
                if (narrower != null) {
                    // Java 9 gave ByteBuffer.flip() a ByteBuffer return; Java 8 has Buffer's, the same method returning Buffer.
                    super.visitMethodInsn(op, owner, name, narrower, itf);
                    super.visitTypeInsn(Opcodes.CHECKCAST, Type.getReturnType(desc).getInternalName());
                    change("JDK calls whose return type narrowed later called as Java " + target + " declares them");
                    return;
                }
                String[] standin = standins.find(op, owner, name, inStandin(desc));
                if (standin != null) {
                    used.add(standin[0]);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, home + standin[0].substring(Standins.PREFIX.length()), standin[1], inHome(standin[2]), false);
                    change("JDK methods Java " + target + " lacks called through stand-ins");
                    return;
                }
                lacks(declared.cls(owner) == null ? "class " + dotted(owner) : dotted(owner) + "." + name + desc);
                super.visitMethodInsn(op, owner, name, desc, itf);
            }

            @Override
            public void visitFieldInsn(int op, String owner, String name, String desc) {
                next(op);
                standinMember(owner, name + ":" + desc);
                String getter = "()" + desc;
                if (op == Opcodes.GETSTATIC && jdk(owner) && declared.cls(owner) != null && !declared.field(owner, name, desc)
                    && standins.methods.contains(Standins.PREFIX + owner + "." + name + inStandin(getter))) {
                    // A constant a later Java added (ProcessBuilder.Redirect.DISCARD): its stand-in is a method of its name.
                    used.add(Standins.PREFIX + owner);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, home + owner, name, inHome(getter), false);
                    change("JDK constants Java " + target + " lacks read through stand-ins");
                    return;
                }
                if (jdk(owner) && (declared.cls(owner) == null || !declared.field(owner, name, desc))) {
                    lacks(declared.cls(owner) == null ? "class " + dotted(owner) : dotted(owner) + "." + name + ":" + desc);
                }
                super.visitFieldInsn(op, owner, name, desc);
            }

            @Override
            public void visitTypeInsn(int op, String type) {
                next(op);
                if (op == Opcodes.NEW) {
                    fresh = new String[] { type, null };
                    news.push(fresh);
                }
                needType(type.startsWith("[") ? Type.getType(type) : Type.getObjectType(type));
                super.visitTypeInsn(op, type);
            }

            @Override
            public void visitMultiANewArrayInsn(String desc, int dims) {
                needType(Type.getType(desc));
                super.visitMultiANewArrayInsn(desc, dims);
            }

            @Override
            public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                if (type != null) {
                    needClass(type);
                }
                super.visitTryCatchBlock(start, end, handler, type);
            }

            @Override
            public void visitLdcInsn(Object value) {
                next(Opcodes.LDC);
                if (value instanceof Type t) {
                    needType(t);
                } else if (value instanceof Handle h) {
                    value = handle(h);
                } else if (value instanceof ConstantDynamic c && target < 11) {
                    lacks("dynamic constant " + c.getName() + " from " + dotted(c.getBootstrapMethod().getOwner()) + "." + c.getBootstrapMethod().getName());
                }
                super.visitLdcInsn(value);
            }

            /** A method handle constant: one to a private method of this class is made as Java 8's javac makes it. */
            Handle handle(Handle h) {
                standinMember(h.getOwner(), h.getTag() <= Opcodes.H_PUTSTATIC ? h.getName() + ":" + h.getDesc() : h.getName() + h.getDesc());
                if (jdk(h.getOwner()) && h.getTag() >= Opcodes.H_INVOKEVIRTUAL && !declared.method(h.getOwner(), h.getName(), h.getDesc())) {
                    // A method reference (String::isBlank) to a stand-in: LambdaMetafactory passes the receiver as its first argument.
                    int op = h.getTag() == Opcodes.H_INVOKESTATIC ? Opcodes.INVOKESTATIC : h.getTag() == Opcodes.H_INVOKEVIRTUAL ? Opcodes.INVOKEVIRTUAL
                        : h.getTag() == Opcodes.H_INVOKEINTERFACE ? Opcodes.INVOKEINTERFACE : -1;
                    String[] standin = op < 0 || declared.cls(h.getOwner()) == null ? null : standins.find(op, h.getOwner(), h.getName(), inStandin(h.getDesc()));
                    if (standin != null) {
                        used.add(standin[0]);
                        change("JDK methods Java " + target + " lacks called through stand-ins");
                        return new Handle(Opcodes.H_INVOKESTATIC, home + standin[0].substring(Standins.PREFIX.length()), standin[1], inHome(standin[2]), false);
                    }
                    lacks(dotted(h.getOwner()) + "." + h.getName() + h.getDesc());
                }
                if (target < 11 && h.getOwner().equals(info.name) && (h.getTag() == Opcodes.H_INVOKEVIRTUAL || h.getTag() == Opcodes.H_INVOKEINTERFACE)
                    && stillPrivate(h.getName() + h.getDesc())) {
                    change("calls to own private methods made with invokespecial (Java 11 nestmates)");
                    return new Handle(Opcodes.H_INVOKESPECIAL, h.getOwner(), h.getName(), h.getDesc(), h.isInterface());
                }
                return h;
            }

            @Override
            public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) {
                next(Opcodes.INVOKEDYNAMIC);
                String factory = bsm.getOwner() + "." + bsm.getName();
                if (target < 9 && factory.startsWith("java/lang/invoke/StringConcatFactory.makeConcat")) {
                    String recipe = factory.endsWith("WithConstants") ? (String) args[0] : "\u0001".repeat(Type.getArgumentTypes(desc).length);
                    Object[] constants = factory.endsWith("WithConstants") ? Arrays.copyOfRange(args, 1, args.length) : new Object[0];
                    String helper = helper("concat", recipe + "|" + Arrays.deepToString(constants), desc, m -> concat(m, recipe, constants, desc));
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, info.name, helper, desc, info.itf);
                    change("string concatenations written with StringBuilder (Java 9's StringConcatFactory)");
                    return;
                }
                if (target < 16 && factory.equals("java/lang/runtime/ObjectMethods.bootstrap")) {
                    String helper = recordMethod(name, desc, args);
                    if (helper != null) {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, info.name, helper, desc, info.itf);
                        change("record equals, hashCode and toString written out (Java 16 records)");
                        return;
                    }
                    lacks("record method " + name + " of another class");
                }
                if (target < 21 && (factory.equals("java/lang/runtime/SwitchBootstraps.typeSwitch") || factory.equals("java/lang/runtime/SwitchBootstraps.enumSwitch"))) {
                    String helper = switchHelper(bsm.getName(), desc, args);
                    if (helper != null) {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, info.name, helper, desc, info.itf);
                        change("pattern switches written as tests in order (Java 21 SwitchBootstraps)");
                        return;
                    }
                    lacks("a switch label this porter cannot write for Java " + target + " (" + bsm.getName() + ")");
                }
                if (jdk(bsm.getOwner()) && !declared.method(bsm.getOwner(), bsm.getName(), bsm.getDesc())) {
                    lacks(dotted(bsm.getOwner()) + "." + bsm.getName() + bsm.getDesc());
                }
                Object[] kept = args.clone();
                for (int i = 0; i < kept.length; i++) {
                    if (kept[i] instanceof Handle h) {
                        kept[i] = handle(h);
                    } else if (kept[i] instanceof Type t && t.getSort() != Type.METHOD) {
                        needType(t);
                    }
                }
                super.visitInvokeDynamicInsn(name, desc, bsm, kept);
            }
        }

        /** The body of a string concatenation: its recipe's text and arguments appended in order (\1 an argument, \2 a constant). */
        void concat(MethodVisitor m, String recipe, Object[] constants, String desc) {
            Type[] args = Type.getArgumentTypes(desc);
            m.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
            m.visitInsn(Opcodes.DUP);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
            StringBuilder text = new StringBuilder();
            int arg = 0, slot = 0, constant = 0;
            for (int i = 0; i <= recipe.length(); i++) {
                char c = i < recipe.length() ? recipe.charAt(i) : 0;
                if (i < recipe.length() && c != 1 && c != 2) {
                    text.append(c);
                    continue;
                }
                if (text.length() > 0) {
                    m.visitLdcInsn(text.toString());
                    append(m, Type.getType(String.class));
                    text.setLength(0);
                }
                if (i == recipe.length()) {
                    break;
                }
                if (c == 1) {
                    Type t = args[arg++];
                    m.visitVarInsn(t.getOpcode(Opcodes.ILOAD), slot);
                    slot += t.getSize();
                    append(m, t);
                } else {
                    Object k = constants[constant++];
                    m.visitLdcInsn(k);
                    append(m, k instanceof String ? Type.getType(String.class) : k instanceof Integer ? Type.INT_TYPE : k instanceof Long ? Type.LONG_TYPE
                        : k instanceof Float ? Type.FLOAT_TYPE : k instanceof Double ? Type.DOUBLE_TYPE : Type.getType(Object.class));
                }
            }
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
            m.visitInsn(Opcodes.ARETURN);
        }

        /** StringBuilder.append of a value of type t, as String.valueOf would write it (what StringConcatFactory does). */
        static void append(MethodVisitor m, Type t) {
            String arg = switch (t.getSort()) {
                case Type.BOOLEAN -> "Z";
                case Type.CHAR -> "C";
                case Type.BYTE, Type.SHORT, Type.INT -> "I";
                case Type.LONG -> "J";
                case Type.FLOAT -> "F";
                case Type.DOUBLE -> "D";
                default -> t.getDescriptor().equals("Ljava/lang/String;") ? "Ljava/lang/String;" : "Ljava/lang/Object;";
            };
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(" + arg + ")Ljava/lang/StringBuilder;", false);
        }

        /**
         * A record's equals, hashCode or toString as java.lang.runtime.ObjectMethods writes them: toString "Name[a=1, b=2]"
         * from the class's simple name, hashCode 31 * h + each component's hash (0 to start), equals the same object, or
         * one of this class with every component equal (floats and doubles by compare, objects by Objects.equals). Null when
         * the record is another class, whose fields this one cannot read.
         */
        String recordMethod(String name, String desc, Object[] args) {
            if (args.length < 2 || !(args[0] instanceof Type rec) || !rec.getInternalName().equals(info.name)) {
                return null;
            }
            List<Handle> getters = new ArrayList<>();
            for (int i = 2; i < args.length; i++) {
                if (!(args[i] instanceof Handle h) || h.getTag() != Opcodes.H_GETFIELD) {
                    return null;
                }
                getters.add(h);
            }
            String[] names = ((String) args[1]).isEmpty() ? new String[0] : ((String) args[1]).split(";");
            String self = info.name;
            return switch (name) {
                case "toString" -> helper("record", "toString", desc, m -> {
                    m.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                    m.visitInsn(Opcodes.DUP);
                    m.visitLdcInsn(Type.getObjectType(self));
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getSimpleName", "()Ljava/lang/String;", false);
                    m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
                    for (int i = 0; i < getters.size(); i++) {
                        m.visitLdcInsn((i == 0 ? "[" : ", ") + names[i] + "=");
                        append(m, Type.getType(String.class));
                        m.visitVarInsn(Opcodes.ALOAD, 0);
                        m.visitFieldInsn(Opcodes.GETFIELD, self, getters.get(i).getName(), getters.get(i).getDesc());
                        append(m, Type.getType(getters.get(i).getDesc()));
                    }
                    m.visitLdcInsn(getters.isEmpty() ? "[]" : "]");
                    append(m, Type.getType(String.class));
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
                    m.visitInsn(Opcodes.ARETURN);
                });
                case "hashCode" -> helper("record", "hashCode", desc, m -> {
                    m.visitInsn(Opcodes.ICONST_0);
                    for (Handle g : getters) {
                        m.visitIntInsn(Opcodes.BIPUSH, 31);
                        m.visitInsn(Opcodes.IMUL);
                        m.visitVarInsn(Opcodes.ALOAD, 0);
                        m.visitFieldInsn(Opcodes.GETFIELD, self, g.getName(), g.getDesc());
                        Type t = Type.getType(g.getDesc());
                        if (t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY) {
                            m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "hashCode", "(Ljava/lang/Object;)I", false);
                        } else {
                            m.visitMethodInsn(Opcodes.INVOKESTATIC, box(t), "hashCode", "(" + t.getDescriptor() + ")I", false);
                        }
                        m.visitInsn(Opcodes.IADD);
                    }
                    m.visitInsn(Opcodes.IRETURN);
                });
                case "equals" -> helper("record", "equals", desc, m -> {
                    Label notSame = new Label(), ofThis = new Label(), differ = new Label();
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitVarInsn(Opcodes.ALOAD, 1);
                    m.visitJumpInsn(Opcodes.IF_ACMPNE, notSame);
                    m.visitInsn(Opcodes.ICONST_1);
                    m.visitInsn(Opcodes.IRETURN);
                    m.visitLabel(notSame);
                    m.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                    m.visitVarInsn(Opcodes.ALOAD, 1);
                    m.visitTypeInsn(Opcodes.INSTANCEOF, self);
                    m.visitJumpInsn(Opcodes.IFNE, ofThis);
                    m.visitInsn(Opcodes.ICONST_0);
                    m.visitInsn(Opcodes.IRETURN);
                    m.visitLabel(ofThis);
                    m.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                    for (Handle g : getters) {
                        Type t = Type.getType(g.getDesc());
                        m.visitVarInsn(Opcodes.ALOAD, 0);
                        m.visitFieldInsn(Opcodes.GETFIELD, self, g.getName(), g.getDesc());
                        m.visitVarInsn(Opcodes.ALOAD, 1);
                        m.visitTypeInsn(Opcodes.CHECKCAST, self);
                        m.visitFieldInsn(Opcodes.GETFIELD, self, g.getName(), g.getDesc());
                        switch (t.getSort()) {
                            case Type.OBJECT, Type.ARRAY -> {
                                m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                                m.visitJumpInsn(Opcodes.IFEQ, differ);
                            }
                            case Type.FLOAT, Type.DOUBLE -> {
                                m.visitMethodInsn(Opcodes.INVOKESTATIC, box(t), "compare", "(" + t.getDescriptor() + t.getDescriptor() + ")I", false);
                                m.visitJumpInsn(Opcodes.IFNE, differ);
                            }
                            case Type.LONG -> {
                                m.visitInsn(Opcodes.LCMP);
                                m.visitJumpInsn(Opcodes.IFNE, differ);
                            }
                            default -> m.visitJumpInsn(Opcodes.IF_ICMPNE, differ);
                        }
                    }
                    m.visitInsn(Opcodes.ICONST_1);
                    m.visitInsn(Opcodes.IRETURN);
                    m.visitLabel(differ);
                    m.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                    m.visitInsn(Opcodes.ICONST_0);
                    m.visitInsn(Opcodes.IRETURN);
                });
                default -> null;
            };
        }

        /**
         * A pattern switch's label lookup as SwitchBootstraps.typeSwitch and enumSwitch answer it: -1 for null, else the
         * first label from the restart index that matches (a class by instanceof, a String by equals, an Integer against a
         * Number's intValue or a Character, an enum constant by identity or name), else the number of labels. Null when a
         * label is of a kind they do not define.
         */
        String switchHelper(String kind, String desc, Object[] labels) {
            List<java.util.function.BiConsumer<MethodVisitor, Label>> tests = new ArrayList<>();
            Type selector = Type.getArgumentTypes(desc)[0];
            for (Object label : labels) {
                // A class or an array type (case double[] d): instanceof takes either, an array by its descriptor.
                if (label instanceof Type t && (t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY)) {
                    needType(t);
                    tests.add((m, miss) -> {
                        m.visitVarInsn(Opcodes.ALOAD, 0);
                        m.visitTypeInsn(Opcodes.INSTANCEOF, t.getInternalName());
                        m.visitJumpInsn(Opcodes.IFEQ, miss);
                    });
                } else if (label instanceof String s && kind.equals("enumSwitch")) {
                    tests.add((m, miss) -> {
                        m.visitVarInsn(Opcodes.ALOAD, 0);
                        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Enum", "name", "()Ljava/lang/String;", false);
                        m.visitLdcInsn(s);
                        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
                        m.visitJumpInsn(Opcodes.IFEQ, miss);
                    });
                } else if (label instanceof String s) {
                    tests.add((m, miss) -> {
                        m.visitLdcInsn(s);
                        m.visitVarInsn(Opcodes.ALOAD, 0);
                        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
                        m.visitJumpInsn(Opcodes.IFEQ, miss);
                    });
                } else if (label instanceof Integer n) {
                    tests.add((m, miss) -> {
                        Label character = new Label();
                        m.visitVarInsn(Opcodes.ALOAD, 0);
                        m.visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/Number");
                        m.visitJumpInsn(Opcodes.IFEQ, character);
                        m.visitVarInsn(Opcodes.ALOAD, 0);
                        m.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
                        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);
                        m.visitLdcInsn(n);
                        m.visitJumpInsn(Opcodes.IF_ICMPNE, miss);
                        Label hit = new Label();
                        m.visitJumpInsn(Opcodes.GOTO, hit);
                        m.visitLabel(character);
                        m.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                        m.visitVarInsn(Opcodes.ALOAD, 0);
                        m.visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/Character");
                        m.visitJumpInsn(Opcodes.IFEQ, miss);
                        m.visitVarInsn(Opcodes.ALOAD, 0);
                        m.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Character");
                        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false);
                        m.visitLdcInsn(n);
                        m.visitJumpInsn(Opcodes.IF_ICMPNE, miss);
                        m.visitLabel(hit);
                        m.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                    });
                } else if (enumConstant(label) instanceof String[] constant) {
                    tests.add((m, miss) -> {
                        m.visitFieldInsn(Opcodes.GETSTATIC, constant[0], constant[1], "L" + constant[0] + ";");
                        m.visitVarInsn(Opcodes.ALOAD, 0);
                        m.visitJumpInsn(Opcodes.IF_ACMPNE, miss);
                    });
                } else {
                    return null;
                }
            }
            return helper("switch", kind + Arrays.deepToString(labels), desc, m -> {
                Label notNull = new Label();
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitJumpInsn(Opcodes.IFNONNULL, notNull);
                m.visitInsn(Opcodes.ICONST_M1);
                m.visitInsn(Opcodes.IRETURN);
                m.visitLabel(notNull);
                m.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                for (int i = 0; i < tests.size(); i++) {
                    Label miss = new Label();
                    m.visitVarInsn(Opcodes.ILOAD, selector.getSize());
                    m.visitLdcInsn(i);
                    m.visitJumpInsn(Opcodes.IF_ICMPGT, miss);
                    tests.get(i).accept(m, miss);
                    m.visitLdcInsn(i);
                    m.visitInsn(Opcodes.IRETURN);
                    m.visitLabel(miss);
                    m.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                }
                m.visitLdcInsn(tests.size());
                m.visitInsn(Opcodes.IRETURN);
            });
        }
    }

    /**
     * {enum class, constant} of a label javac 21 writes as a dynamic constant: Enum.EnumDesc.of(ClassDesc.of("a.b.E"), "NAME"),
     * made by ConstantBootstraps.invoke. Null for anything else.
     */
    static String[] enumConstant(Object label) {
        if (!(label instanceof ConstantDynamic c) || !c.getBootstrapMethod().getOwner().equals("java/lang/invoke/ConstantBootstraps")
            || c.getBootstrapMethodArgumentCount() != 3 || !(c.getBootstrapMethodArgument(0) instanceof Handle of)
            || !of.getOwner().equals("java/lang/Enum$EnumDesc") || !(c.getBootstrapMethodArgument(2) instanceof String constant)
            || !(c.getBootstrapMethodArgument(1) instanceof ConstantDynamic cls) || cls.getBootstrapMethodArgumentCount() != 2
            || !(cls.getBootstrapMethodArgument(1) instanceof String binary)) {
            return null;
        }
        return new String[] { binary.replace('.', '/'), constant };
    }

    static String box(Type t) {
        return switch (t.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.CHAR -> "java/lang/Character";
            case Type.BYTE -> "java/lang/Byte";
            case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer";
            case Type.LONG -> "java/lang/Long";
            case Type.FLOAT -> "java/lang/Float";
            default -> "java/lang/Double";
        };
    }

    /**
     * What one Java release's JDK declares, read from the running JDK's lib/ct.sym (the API of every release it can compile
     * for with --release) or, for the running release itself, from its own classes.
     */
    static final class Api {

        record Cls(String name, boolean itf, String sup, String[] ifaces, Map<String, Integer> methods, Set<String> fields) {}

        static final Map<Integer, Api> APIS = new ConcurrentHashMap<>();
        static final Path CT_SYM = Path.of(System.getProperty("java.home"), "lib", "ct.sym");

        final int release;
        final Map<String, String> entries = new HashMap<>(); // class -> its ct.sym entry
        final Map<String, Optional<Cls>> read = new ConcurrentHashMap<>();

        static Api of(int release) {
            return APIS.computeIfAbsent(release, Api::new);
        }

        Api(int release) {
            this.release = release;
            if (release >= Runtime.version().feature()) {
                return;
            }
            char letter = Character.toUpperCase(Character.forDigit(release, 36));
            try (ZipFile z = new ZipFile(CT_SYM.toFile())) {
                for (var it = z.entries(); it.hasMoreElements(); ) {
                    String n = it.nextElement().getName();
                    int slash = n.indexOf('/');
                    if (slash < 0 || !n.endsWith(".sig") || n.substring(0, slash).indexOf(letter) < 0) {
                        continue;
                    }
                    String rest = n.substring(slash + 1);
                    if (rest.indexOf('/') > 0 && rest.substring(0, rest.indexOf('/')).contains(".")) {
                        rest = rest.substring(rest.indexOf('/') + 1); // the module's name
                    }
                    if (rest.startsWith("java/")) {
                        entries.put(rest.substring(0, rest.length() - 4), n);
                    }
                }
            } catch (IOException unreadable) {
                throw new UncheckedIOException("the JDK's record of Java " + release + "'s API (" + CT_SYM + ") cannot be read", unreadable);
            }
            if (entries.isEmpty()) {
                throw new IllegalStateException("this JDK (" + Runtime.version() + ") holds no record of Java " + release + "'s API in " + CT_SYM);
            }
        }

        Cls cls(String name) {
            return read.computeIfAbsent(name, n -> Optional.ofNullable(load(n))).orElse(null);
        }

        Cls load(String name) {
            byte[] bytes;
            try {
                if (release >= Runtime.version().feature()) {
                    try (InputStream in = ClassLoader.getSystemResourceAsStream(name + ".class")) {
                        if (in == null) {
                            return null;
                        }
                        bytes = in.readAllBytes();
                    }
                } else {
                    String entry = entries.get(name);
                    if (entry == null) {
                        return null;
                    }
                    try (ZipFile z = new ZipFile(CT_SYM.toFile())) {
                        bytes = z.getInputStream(z.getEntry(entry)).readAllBytes();
                    }
                }
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
            return parse(name, bytes);
        }

        static Cls parse(String name, byte[] bytes) {
            Map<String, Integer> methods = new HashMap<>();
            Set<String> fields = new HashSet<>();
            ClassReader r = new ClassReader(bytes);
            r.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public FieldVisitor visitField(int access, String n, String d, String sig, Object value) {
                    fields.add(n + ":" + d);
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String sig, String[] ex) {
                    methods.put(n + d, access);
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return new Cls(name, (r.getAccess() & Opcodes.ACC_INTERFACE) != 0, r.getSuperName(), r.getInterfaces(), methods, fields);
        }

        /** The classes a reference on owner is looked up in, as the JVM resolves it: the class, its superclasses, its interfaces. */
        List<Cls> above(String owner) {
            List<Cls> out = new ArrayList<>();
            Deque<String> todo = new ArrayDeque<>(List.of(owner));
            Set<String> seen = new HashSet<>();
            while (!todo.isEmpty()) {
                String n = todo.poll();
                Cls c = seen.add(n) ? cls(n) : null;
                if (c != null) {
                    out.add(c);
                    if (c.sup != null) {
                        todo.add(c.sup);
                    }
                    todo.addAll(List.of(c.ifaces));
                }
            }
            if (out.isEmpty() || !out.get(out.size() - 1).name.equals("java/lang/Object")) {
                Cls object = cls("java/lang/Object");
                if (object != null && !out.contains(object)) {
                    out.add(object); // an interface's methods resolve to Object's too
                }
            }
            return out;
        }

        boolean method(String owner, String name, String desc) {
            if (owner.startsWith("[")) {
                return true;
            }
            for (Cls c : above(owner)) {
                if (c.methods.containsKey(name + desc)) {
                    return true;
                }
                // MethodHandle.invoke and VarHandle's accessors are signature polymorphic: any descriptor links.
                for (var m : c.methods.entrySet()) {
                    if (m.getKey().startsWith(name + "(") && (m.getValue() & (Opcodes.ACC_NATIVE | Opcodes.ACC_VARARGS)) == (Opcodes.ACC_NATIVE | Opcodes.ACC_VARARGS)
                        && (c.name.equals("java/lang/invoke/MethodHandle") || c.name.equals("java/lang/invoke/VarHandle"))) {
                        return true;
                    }
                }
            }
            return false;
        }

        boolean field(String owner, String name, String desc) {
            for (Cls c : above(owner)) {
                if (c.fields.contains(name + ":" + desc)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The descriptor this release declares a method by when a later Java only narrowed its return type (an override
         * returning a subtype: Java 9's ByteBuffer.flip() returning ByteBuffer, where Java 8 has Buffer.flip() returning
         * Buffer): the same name and parameters, a return type the wanted one is a subtype of. Null when there is none.
         */
        String wider(String owner, String name, String desc) {
            Type ret = Type.getReturnType(desc);
            if (ret.getSort() != Type.OBJECT) {
                return null;
            }
            String params = desc.substring(0, desc.indexOf(')') + 1);
            for (Cls c : above(owner)) {
                for (String m : c.methods.keySet()) {
                    if (m.startsWith(name + params) && m.length() > (name + params).length() && Type.getReturnType(m.substring(name.length())).getSort() == Type.OBJECT) {
                        String has = Type.getReturnType(m.substring(name.length())).getInternalName();
                        if (above(ret.getInternalName()).stream().anyMatch(s -> s.name.equals(has))) {
                            return params + "L" + has + ";";
                        }
                    }
                }
            }
            return null;
        }
    }

    /**
     * Stand-ins for the JDK an older Java lacks (knowledge/standins/jdk/), compiled for Java 8 so every release can run
     * them, each in mmt.standin + the JDK class's name. For a class the older Java has, public static methods:
     *   R name(Owner self, A...)   the instance method Owner.name(A...)R   (also found for a call on a subtype of Owner)
     *   R name(A...)               the static method Owner.name(A...)R
 *   T name()                   also the static field Owner.name of type T, when Owner has no such field
     *   Owner init(A...)           the constructor new Owner(A...)
     * For a class the older Java lacks (java.util.HexFormat on Java 8), the class itself, of the same kind (class,
     * interface, enum) with the members it answers: the mod names it in place of the JDK's.
     */
    static final class Standins {

        static final String PREFIX = "mmt/standin/";
        static final Map<String, Standins> COMPILED = new ConcurrentHashMap<>();
        static final Standins NONE = new Standins(Map.of());

        final Map<String, byte[]> classes; // mmt/standin/java/... -> bytes
        final Set<String> methods = new HashSet<>(); // class.name+desc of each public static method
        final Map<String, Api.Cls> parsed = new ConcurrentHashMap<>();

        Api.Cls cls(String name) {
            byte[] bytes = classes.get(name);
            return bytes == null ? null : parsed.computeIfAbsent(name, n -> Api.parse(n, bytes));
        }

        /** The JDK classes the release's Java lacks that a stand-in is written as: JDK name -> where the jar carries it. */
        Map<String, String> classesLacked(Api declared, String home) {
            Map<String, String> out = new HashMap<>();
            for (var e : classes.entrySet()) {
                String jdk = e.getKey().substring(PREFIX.length());
                if ((new ClassReader(e.getValue()).getAccess() & Opcodes.ACC_PUBLIC) != 0 && declared.cls(jdk) == null) {
                    out.put(jdk, home + jdk);
                }
            }
            return out;
        }

        /**
         * Whether a stand-in class answers a member (name+desc, or name:desc for a field, in stand-in names): declared on
         * it or on a type above it, a stand-in's or the release's JDK's.
         */
        boolean answers(String cls, String member, Api declared) {
            Deque<String> todo = new ArrayDeque<>(List.of(cls));
            Set<String> seen = new HashSet<>();
            boolean field = !member.contains("(");
            while (!todo.isEmpty()) {
                String n = todo.poll();
                if (!seen.add(n)) {
                    continue;
                }
                Api.Cls c = cls(n);
                if (c == null) {
                    int colon = member.indexOf(':'), paren = member.indexOf('(');
                    if (field ? declared.field(n, member.substring(0, colon), member.substring(colon + 1))
                        : declared.method(n, member.substring(0, paren), member.substring(paren))) {
                        return true;
                    }
                    continue;
                }
                if (field ? c.fields().contains(member) : c.methods().containsKey(member)) {
                    return true;
                }
                if (c.sup() != null) {
                    todo.add(c.sup());
                }
                todo.addAll(List.of(c.ifaces()));
            }
            return false;
        }

        Standins(Map<String, byte[]> classes) {
            this.classes = classes;
            for (var e : classes.entrySet()) {
                new ClassReader(e.getValue()).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String n, String d, String sig, String[] ex) {
                        if ((access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC) && (access & Opcodes.ACC_SYNTHETIC) == 0) {
                            methods.add(e.getKey() + "." + n + d);
                        }
                        return null;
                    }
                }, ClassReader.SKIP_CODE);
            }
        }

        /** The stand-ins under dir, compiled once per content of their sources (kept in ~/.mmt/jar-port/jdk-standins/). */
        static Standins of(Path dir) throws IOException {
            if (dir == null || !Files.isDirectory(dir)) {
                return NONE;
            }
            List<Path> sources;
            try (var walk = Files.walk(dir)) {
                sources = walk.filter(f -> f.toString().endsWith(".java")).sorted().toList();
            }
            java.security.MessageDigest sha;
            try {
                sha = java.security.MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException(e);
            }
            for (Path f : sources) {
                sha.update(dir.relativize(f).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                sha.update(Files.readAllBytes(f));
            }
            String key = HexFormat.of().formatHex(sha.digest()).substring(0, 24);
            Standins known = COMPILED.get(key);
            if (known != null) {
                return known;
            }
            Path kept = Path.of(System.getProperty("user.home"), ".mmt", "jar-port", "jdk-standins", key);
            if (!Files.isDirectory(kept)) {
                // Made beside where it is kept: a directory moves only within one mount, and /tmp is often another.
                Files.createDirectories(kept.getParent());
                Path work = Files.createTempDirectory(kept.getParent(), key + ".part");
                String errors = StandinClasses.compile("JDK stand-ins", List.of("--release", "8", "-proc:none", "-nowarn", "-encoding", "UTF-8", "-d", work.toString()), sources);
                if (errors != null) {
                    throw new IOException("JDK stand-ins in " + dir + " do not compile for Java 8:\n" + errors);
                }
                try {
                    Files.move(work, kept);
                } catch (IOException another) {
                    // another process kept the same classes first
                }
            }
            Map<String, byte[]> classes = StandinClasses.readClasses(kept);
            for (String name : classes.keySet()) {
                if (!name.startsWith(PREFIX + "java/")) {
                    throw new IOException("JDK stand-in " + name + " is not in a package under mmt.standin.java");
                }
            }
            Standins s = new Standins(classes);
            Standins first = COMPILED.putIfAbsent(key, s);
            return first == null ? s : first;
        }

        /** {class, name, desc} of the stand-in for a call, or null: on the owner's class or, for an instance call, a supertype's. */
        String[] find(int op, String owner, String name, String desc) {
            if (methods.isEmpty()) {
                return null;
            }
            if (op == Opcodes.INVOKESTATIC) {
                return methods.contains(PREFIX + owner + "." + name + desc) ? new String[] { PREFIX + owner, name, desc } : null;
            }
            if (op == Opcodes.INVOKESPECIAL) {
                return null;
            }
            // Looked up through the running JDK's classes: the type that declares the method, which the call names by a subtype.
            for (Api.Cls c : Api.of(Runtime.version().feature()).above(owner)) {
                String self = "(L" + c.name() + ";" + desc.substring(1);
                if (methods.contains(PREFIX + c.name() + "." + name + self)) {
                    return new String[] { PREFIX + c.name(), name, self };
                }
            }
            return null;
        }

        /** The stand-ins named and every one they use, moved under home (entry name -> bytes). */
        Map<String, byte[]> carry(Set<String> used, String home) {
            if (used.isEmpty()) {
                return Map.of();
            }
            Set<String> all = new TreeSet<>();
            Deque<String> todo = new ArrayDeque<>(used);
            while (!todo.isEmpty()) {
                String n = todo.pop();
                if (!all.add(n) || !classes.containsKey(n)) {
                    continue;
                }
                // Written out so the remapper sees method bodies too: a stand-in may name another only in its code (throw new X).
                new ClassReader(classes.get(n)).accept(new ClassRemapper(new ClassWriter(0), new org.objectweb.asm.commons.Remapper(Opcodes.ASM9) {
                    @Override
                    public String map(String name) {
                        if (classes.containsKey(name)) {
                            todo.push(name);
                        }
                        return name;
                    }
                }), 0);
            }
            org.objectweb.asm.commons.Remapper move = new org.objectweb.asm.commons.Remapper(Opcodes.ASM9) {
                @Override
                public String map(String name) {
                    return classes.containsKey(name) ? home + name.substring(PREFIX.length()) : name;
                }
            };
            Map<String, byte[]> out = new TreeMap<>();
            for (String n : all) {
                if (classes.containsKey(n)) {
                    out.put(move.map(n) + ".class", StandinClasses.relocate(classes.get(n), move));
                }
            }
            return out;
        }
    }

    /**
     * Measures what lowering leaves: every jar given (nested jars too) lowered to one Java, and each break by the jars it
     * holds. java Downgrade JAVA STANDINS_DIR JAR...
     */
    public static void main(String[] args) throws Exception {
        Downgrade d = new Downgrade(Integer.parseInt(args[0]), Standins.of(Path.of(args[1])));
        Map<String, Set<String>> by = new TreeMap<>();
        Map<String, Integer> changed = new TreeMap<>();
        int clean = 0, jars = 0;
        for (int i = 2; i < args.length; i++) {
            Set<String> breaks = new TreeSet<>();
            d.measure(Files.readAllBytes(Path.of(args[i])), breaks, changed);
            jars++;
            clean += breaks.isEmpty() ? 1 : 0;
            for (String b : breaks) {
                by.computeIfAbsent(b.replaceAll(" \\(in [^)]*\\)$", ""), k -> new TreeSet<>()).add(Path.of(args[i]).getFileName().toString());
            }
        }
        by.entrySet().stream().sorted(Comparator.comparing((Map.Entry<String, Set<String>> e) -> -e.getValue().size()).thenComparing(Map.Entry::getKey))
            .forEach(e -> System.out.println(e.getValue().size() + "\t" + e.getKey() + "\t" + String.join(" ", e.getValue()).substring(0, Math.min(200, String.join(" ", e.getValue()).length()))));
        System.out.println(changed);
        System.out.println(clean + " of " + jars + " jars run on Java " + d.target + " after lowering");
    }

    void measure(byte[] jar, Set<String> breaks, Map<String, Integer> changed) throws IOException {
        Map<String, byte[]> classes = new HashMap<>();
        Map<String, byte[]> nested = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (e.getName().endsWith(".class") && !e.getName().startsWith("META-INF/versions/") && !e.getName().endsWith("module-info.class")) {
                    classes.put(e.getName(), z.readAllBytes());
                } else if (e.getName().endsWith(".jar")) {
                    nested.put(e.getName(), z.readAllBytes());
                }
            }
        }
        lower(classes, "mmt/standin/mtest/", breaks, changed);
        for (byte[] n : nested.values()) {
            measure(n, breaks, changed);
        }
    }
}

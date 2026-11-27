import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.objectweb.asm.*;

/**
 * One release's own code: a method's instructions in order, in Mojang names, without its lambdas. bytes gives a
 * class file by its name in the jar; pg maps the jar's names to Mojang's (null when they are Mojang's).
 */
final class GameCode {
    final java.util.function.Function<String, byte[]> bytes;
    final GameDiff.Names names;
    final Map<String, String> toJar = new HashMap<>();
    final Map<String, Optional<GameDiff.RawCls>> parsed = new HashMap<>();
    final Map<String, byte[]> all; // every class by its jar name, when the whole jar was read

    GameCode(java.util.function.Function<String, byte[]> bytes, VersionMap.Proguard pg) {
        this(bytes, pg, null);
    }

    GameCode(java.util.function.Function<String, byte[]> bytes, VersionMap.Proguard pg, Map<String, byte[]> all) {
        this.bytes = bytes;
        this.all = all;
        names = new GameDiff.Names(pg);
        if (pg != null) {
            pg.classes.forEach((jar, named) -> toJar.put(named, jar));
        }
    }

    static GameCode of(String version) throws Exception {
        Object[] read = READ.get(version);
        if (read == null) {
            READ.putIfAbsent(version, read(version));
            read = READ.get(version);
        }
        @SuppressWarnings("unchecked") Map<String, byte[]> all = (Map<String, byte[]>) read[0];
        return new GameCode(all::get, (VersionMap.Proguard) read[1], all);
    }

    /**
     * A release's classes by name and its mappings, read once per process and only read after: each worker's porter
     * of each step asks for both releases' code, and reading the mappings again was a tenth of a long port.
     */
    static final Map<String, Object[]> READ = new java.util.concurrent.ConcurrentHashMap<>();

    static Object[] read(String version) throws Exception {
        GameDiff.Client client = GameDiff.client(version);
        Map<String, byte[]> all = new HashMap<>();
        for (byte[] c : client.classes()) {
            all.put(new ClassReader(c).getClassName(), c);
        }
        return new Object[] { all, client.names() };
    }

    /** A box's primitive and the call that unboxes it. */
    static final Map<String, String[]> UNBOX = Map.of("java/lang/Integer", new String[] { "I", "intValue()I" },
        "java/lang/Boolean", new String[] { "Z", "booleanValue()Z" }, "java/lang/Double", new String[] { "D", "doubleValue()D" },
        "java/lang/Float", new String[] { "F", "floatValue()F" }, "java/lang/Long", new String[] { "J", "longValue()J" },
        "java/lang/Short", new String[] { "S", "shortValue()S" }, "java/lang/Byte", new String[] { "B", "byteValue()B" },
        "java/lang/Character", new String[] { "C", "charValue()C" });

    Map<String, Map<String, Map<String, Integer>>> unwraps;

    /**
     * How the game's code gets the value a holder holds: a no-argument call with an erased result, cast right after
     * (holder.value(), option.get()), then unboxed where a primitive is wanted. By the type it gives (a descriptor),
     * then the type the call is made on, then "name()desc cast [unbox]", how often. Mojang names.
     */
    Map<String, Map<String, Map<String, Integer>>> unwraps() {
        if (unwraps != null) {
            return unwraps;
        }
        unwraps = new HashMap<>();
        for (var e : all == null ? Map.<String, byte[]>of().entrySet() : all.entrySet()) {
            String jarOwner = e.getKey();
            new ClassReader(e.getValue()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        String owner, call, cast; // the last erased call (its owner and step), and it with its cast

                        void add(String type, String steps) {
                            unwraps.computeIfAbsent(type, k -> new HashMap<>()).computeIfAbsent(owner, k -> new HashMap<>()).merge(steps, 1, Integer::sum);
                        }

                        void reset() {
                            owner = call = cast = null;
                        }

                        @Override
                        public void visitMethodInsn(int op, String o, String m, String md, boolean itf) {
                            String[] box = UNBOX.get(o);
                            if (cast != null && box != null && (m + md).equals(box[1])) {
                                add(box[0], cast + " " + box[1]);
                            }
                            reset();
                            if ((op == Opcodes.INVOKEVIRTUAL || op == Opcodes.INVOKEINTERFACE) && md.equals("()Ljava/lang/Object;")) {
                                owner = names.cls(o);
                                call = names.method(o, m, md) + md;
                            }
                        }

                        @Override
                        public void visitTypeInsn(int op, String t) {
                            if (op == Opcodes.CHECKCAST && call != null && !t.startsWith("[")) {
                                String to = names.cls(t);
                                cast = call + " " + to;
                                add("L" + to + ";", cast);
                                call = null;
                                return;
                            }
                            reset();
                        }

                        @Override
                        public void visitInsn(int op) {
                            reset();
                        }

                        @Override
                        public void visitIntInsn(int op, int v) {
                            reset();
                        }

                        @Override
                        public void visitVarInsn(int op, int v) {
                            reset();
                        }

                        @Override
                        public void visitFieldInsn(int op, String o, String f, String fd) {
                            reset();
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String n2, String d2, Handle bsm, Object... args) {
                            reset();
                        }

                        @Override
                        public void visitJumpInsn(int op, Label l) {
                            reset();
                        }

                        @Override
                        public void visitLdcInsn(Object v) {
                            reset();
                        }

                        @Override
                        public void visitLabel(Label l) {
                            reset();
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return unwraps;
    }

    GameDiff.RawCls raw(String jarName) {
        return parsed.computeIfAbsent(jarName, n -> Optional.ofNullable(bytes.apply(n)).map(GameDiff::parse)).orElse(null);
    }

    /**
     * A class's declarations (its supertypes and what it declares), read without its code unless raw read it already:
     * finding where a member is declared asks them of every class the game's code names, and reading each one's code for
     * it was a fifth of a small mod's port.
     */
    GameDiff.RawCls declared(String jarName) {
        Optional<GameDiff.RawCls> whole = parsed.get(jarName);
        if (whole != null) {
            return whole.orElse(null);
        }
        return declared.computeIfAbsent(jarName, n -> Optional.ofNullable(bytes.apply(n)).map(b -> GameDiff.parse(b, false))).orElse(null);
    }

    final Map<String, Optional<GameDiff.RawCls>> declared = new HashMap<>();

    /**
     * Every method of a class (Mojang names), synthetic ones too (a lambda's lambda$name$N), as name+desc -> its code as
     * text: one instruction a line with what it names, class names passed through rename (an older release's into the
     * newer one's), so two releases' copies of one lambda read alike. A line break is the only boundary: descriptors
     * hold ';', and constants have their line breaks escaped. Null when the class is not in the jar.
     */
    Map<String, String> bodies(String owner, java.util.function.UnaryOperator<String> rename) {
        String jarOwner = toJar.getOrDefault(owner, owner);
        byte[] file = bytes.apply(jarOwner);
        if (file == null) {
            return null;
        }
        java.util.function.UnaryOperator<String> type = t -> rename.apply(names.cls(t));
        java.util.function.UnaryOperator<String> desc = d -> mapDesc(names.desc(d), null, rename);
        Map<String, String> out = new LinkedHashMap<>();
        new ClassReader(file).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                String key = names.method(jarOwner, n, d) + desc.apply(d);
                StringBuilder sb = new StringBuilder();
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitInsn(int op) {
                        sb.append(op).append('\n');
                    }

                    @Override
                    public void visitIntInsn(int op, int v) {
                        sb.append(op).append(':').append(v).append('\n');
                    }

                    @Override
                    public void visitVarInsn(int op, int v) {
                        sb.append(op).append(':').append(v).append('\n');
                    }

                    @Override
                    public void visitTypeInsn(int op, String t) {
                        sb.append(op).append(':').append(type.apply(t)).append('\n');
                    }

                    @Override
                    public void visitFieldInsn(int op, String o, String f, String fd) {
                        sb.append(op).append(':').append(type.apply(o)).append('.').append(names.field(o, f)).append(desc.apply(fd)).append('\n');
                    }

                    @Override
                    public void visitMethodInsn(int op, String o, String m, String md, boolean itf) {
                        sb.append(op).append(':').append(type.apply(o)).append('.').append(names.method(o, m, md)).append(desc.apply(md)).append('\n');
                    }

                    @Override
                    public void visitLdcInsn(Object v) {
                        sb.append("ldc:").append(v instanceof Type t ? desc.apply(t.getDescriptor()) : line(v)).append('\n');
                    }

                    @Override
                    public void visitJumpInsn(int op, Label l) {
                        sb.append(op).append('\n');
                    }

                    @Override
                    public void visitIincInsn(int v, int inc) {
                        sb.append("iinc:").append(v).append(':').append(inc).append('\n');
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String n2, String d2, Handle bsm, Object... args) {
                        sb.append("indy:").append(n2).append(desc.apply(d2)).append(':').append(handle(bsm));
                        for (Object arg : args) {
                            sb.append(':').append(arg instanceof Handle h ? handle(h) : arg instanceof Type t ? desc.apply(t.getDescriptor()) : line(arg));
                        }
                        sb.append('\n');
                    }

                    // A nested lambda is named by its enclosing method, not its number, which a release may change.
                    String handle(Handle h) {
                        return type.apply(h.getOwner()) + "." + names.method(h.getOwner(), h.getName(), h.getDesc()).replaceAll("^(lambda\\$.*\\$)\\d+$", "$1")
                            + desc.apply(h.getDesc());
                    }

                    @Override
                    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
                        sb.append("switch:").append(min).append(':').append(max).append('\n');
                    }

                    @Override
                    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
                        sb.append("switch:").append(Arrays.toString(keys)).append('\n');
                    }

                    @Override
                    public void visitMultiANewArrayInsn(String d2, int dims) {
                        sb.append("multianewarray:").append(desc.apply(d2)).append(':').append(dims).append('\n');
                    }

                    @Override
                    public void visitEnd() {
                        out.put(key, sb.toString());
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return out;
    }

    /**
     * The store instructions (ISTORE, ASTORE, ...) a method uses for its own locals, beyond its parameters; null when
     * it cannot be read.
     */
    /** A constant as it reads on one line of a body. */
    static String line(Object v) {
        return String.valueOf(v).replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    /** A body's instructions, as bodies writes them. */
    static String[] instructions(String body) {
        return body.isEmpty() ? new String[0] : body.split("\n");
    }

    /**
     * Reads one method of this release's jar (this release's names): code is given its access and its first local past
     * the arguments, and gives the visitor that reads it. False when the method is not in the jar.
     */
    boolean read(String owner, String name, String desc, int flags, java.util.function.BiFunction<Integer, Integer, MethodVisitor> code) {
        String jarOwner = toJar.getOrDefault(owner, owner);
        byte[] file = bytes.apply(jarOwner);
        if (file == null) {
            return false;
        }
        boolean[] found = { false };
        new ClassReader(file).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                if (found[0] || !names.method(jarOwner, n, d).equals(name) || !names.desc(d).equals(desc)) {
                    return null;
                }
                found[0] = true;
                return code.apply(access, (Type.getArgumentsAndReturnSizes(d) >> 2) - ((access & Opcodes.ACC_STATIC) != 0 ? 1 : 0));
            }
        }, flags);
        return found[0];
    }

    Set<Integer> localStores(String owner, String name, String desc) {
        Set<Integer> out = new HashSet<>();
        boolean seen = read(owner, name, desc, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES, (access, first) -> new MethodVisitor(Opcodes.ASM9) {
            @Override
            public void visitVarInsn(int op, int v) {
                if (op >= Opcodes.ISTORE && op <= Opcodes.ASTORE && v >= first) {
                    out.add(op);
                }
            }
        });
        return seen ? out : null;
    }

    /**
     * The locals Mixin hands a handler that captures them (LocalCapture, or MixinExtras' @Local) at each place an @At
     * finds in a method (this release's names): from the first slot after the arguments, each live local's type in slot
     * order, a long or double once. Where the method has a local variable table (Mojang's jars keep one, with names
     * $$0...) Mixin takes the locals whose range holds the place, with their declared types; without one, it reads
     * them from the frames and the stores after the last one, as AnalyzerAdapter does, an int-like local as I. Places:
     * value HEAD, RETURN or TAIL, or a call (INVOKE, INVOKE_ASSIGN after its result is stored) or field (FIELD) whose
     * owner, name and descriptor are at (Mixin matches the owner its instruction names), the ordinal-th one or every
     * one. Null when the method is not in the jar, a place is of another kind, or a local's type is unknown; an empty
     * list when the @At finds no place.
     */
    List<List<String>> localsAt(String owner, String name, String desc, String value, String[] at, String ordinal) {
        String jarOwner = toJar.getOrDefault(owner, owner);
        byte[] file = bytes.apply(jarOwner);
        if (file == null || !Set.of("HEAD", "RETURN", "TAIL", "INVOKE", "INVOKE_ASSIGN", "FIELD").contains(value)
            || !Set.of("HEAD", "RETURN", "TAIL").contains(value) && at == null) {
            return null;
        }
        List<List<String>> out = new ArrayList<>();
        boolean[] seen = { false }, unknown = { false };
        new ClassReader(file).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                if (seen[0] || !(names.method(jarOwner, n, d) + names.desc(d)).equals(name + desc)) {
                    return null;
                }
                seen[0] = true;
                int first = (Type.getArgumentsAndReturnSizes(d) >> 2) - ((access & Opcodes.ACC_STATIC) != 0 ? 1 : 0);
                org.objectweb.asm.commons.AnalyzerAdapter frames = new org.objectweb.asm.commons.AnalyzerAdapter(jarOwner, access, n, d, null);
                // Each place: the instruction it is at (its index), and the locals the frames give there.
                List<Object[]> places = new ArrayList<>(), returns = new ArrayList<>();
                Map<Label, Integer> labels = new HashMap<>();
                List<Object[]> table = new ArrayList<>(); // slot, descriptor, start, end
                return new MethodVisitor(Opcodes.ASM9, frames) {
                    int index, count; // the instruction about to be read; the @At's matches so far
                    boolean assign; // an INVOKE_ASSIGN place: taken after the store that follows the call, or after the call

                    Object[] place() {
                        List<String> types = new ArrayList<>();
                        List<Object> locals = frames.locals;
                        if (locals == null) {
                            return new Object[] { index, null };
                        }
                        for (int i = first; i < locals.size(); i++) {
                            Object v = locals.get(i);
                            if (v == Opcodes.TOP || v instanceof Label || v == Opcodes.UNINITIALIZED_THIS) {
                                continue;
                            }
                            types.add(v == Opcodes.NULL ? null : v == Opcodes.INTEGER ? "I" : v == Opcodes.FLOAT ? "F" : v == Opcodes.LONG ? "J"
                                : v == Opcodes.DOUBLE ? "D" : names.desc(((String) v).startsWith("[") ? (String) v : "L" + v + ";"));
                        }
                        return new Object[] { index, types };
                    }

                    /** Before each instruction: an INVOKE_ASSIGN place waiting for its store is taken here, unless this is it. */
                    void next(boolean store) {
                        if (assign && !store) {
                            assign = false;
                            places.add(place());
                        }
                    }

                    void done() {
                        index++;
                    }

                    boolean picked() {
                        return ordinal == null || count++ == Integer.parseInt(ordinal);
                    }

                    @Override
                    public void visitCode() {
                        super.visitCode();
                        if (value.equals("HEAD")) {
                            places.add(place());
                        }
                    }

                    @Override
                    public void visitInsn(int op) {
                        next(false);
                        if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN && (value.equals("RETURN") || value.equals("TAIL"))) {
                            returns.add(place());
                        }
                        super.visitInsn(op);
                        done();
                    }

                    @Override
                    public void visitVarInsn(int op, int v) {
                        next(op >= Opcodes.ISTORE && op <= Opcodes.ASTORE);
                        super.visitVarInsn(op, v);
                        done();
                    }

                    @Override
                    public void visitMethodInsn(int op, String o, String m, String md, boolean itf) {
                        next(false);
                        boolean match = value.startsWith("INVOKE") && names.cls(o).equals(at[0]) && names.method(o, m, md).equals(at[1]) && names.desc(md).equals(at[2]) && picked();
                        if (match && value.equals("INVOKE")) {
                            places.add(place());
                        }
                        super.visitMethodInsn(op, o, m, md, itf);
                        done();
                        assign = match && value.equals("INVOKE_ASSIGN");
                    }

                    @Override
                    public void visitFieldInsn(int op, String o, String f, String fd) {
                        next(false);
                        if (value.equals("FIELD") && names.cls(o).equals(at[0]) && names.field(o, f).equals(at[1]) && (at[2] == null || names.desc(fd).equals(at[2])) && picked()) {
                            places.add(place());
                        }
                        super.visitFieldInsn(op, o, f, fd);
                        done();
                    }

                    @Override
                    public void visitTypeInsn(int op, String t) {
                        next(false);
                        super.visitTypeInsn(op, t);
                        done();
                    }

                    @Override
                    public void visitIntInsn(int op, int v) {
                        next(false);
                        super.visitIntInsn(op, v);
                        done();
                    }

                    @Override
                    public void visitJumpInsn(int op, Label l) {
                        next(false);
                        super.visitJumpInsn(op, l);
                        done();
                    }

                    @Override
                    public void visitLdcInsn(Object v) {
                        next(false);
                        super.visitLdcInsn(v);
                        done();
                    }

                    @Override
                    public void visitIincInsn(int v, int inc) {
                        next(false);
                        super.visitIincInsn(v, inc);
                        done();
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String n2, String d2, Handle bsm, Object... args) {
                        next(false);
                        super.visitInvokeDynamicInsn(n2, d2, bsm, args);
                        done();
                    }

                    @Override
                    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... ls) {
                        next(false);
                        super.visitTableSwitchInsn(min, max, dflt, ls);
                        done();
                    }

                    @Override
                    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] ls) {
                        next(false);
                        super.visitLookupSwitchInsn(dflt, keys, ls);
                        done();
                    }

                    @Override
                    public void visitMultiANewArrayInsn(String d2, int dims) {
                        next(false);
                        super.visitMultiANewArrayInsn(d2, dims);
                        done();
                    }

                    @Override
                    public void visitLabel(Label l) {
                        next(false);
                        labels.put(l, index);
                        super.visitLabel(l);
                    }

                    @Override
                    public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
                        next(false);
                        super.visitFrame(type, numLocal, local, numStack, stack);
                    }

                    @Override
                    public void visitLocalVariable(String ln, String ld, String ls, Label start, Label end, int slot) {
                        table.add(new Object[] { slot, ld, start, end });
                    }

                    @Override
                    public void visitEnd() {
                        next(false);
                        if (value.equals("RETURN")) {
                            places.addAll(returns);
                        } else if (value.equals("TAIL") && !returns.isEmpty()) {
                            places.add(returns.get(returns.size() - 1));
                        }
                        for (Object[] p : places) {
                            int i = (Integer) p[0];
                            @SuppressWarnings("unchecked") List<String> types = (List<String>) p[1];
                            if (!table.isEmpty()) {
                                types = table.stream().filter(t -> (Integer) t[0] >= first && labels.getOrDefault((Label) t[2], Integer.MAX_VALUE) <= i
                                        && i < labels.getOrDefault((Label) t[3], -1))
                                    .sorted(Comparator.comparingInt(t -> (Integer) t[0])).map(t -> names.desc((String) t[1])).toList();
                            }
                            if (types == null || types.contains(null)) {
                                unknown[0] = true;
                            } else {
                                out.add(types);
                            }
                        }
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return !seen[0] || unknown[0] ? null : out;
    }

    /** A descriptor with each class name passed through rename. */
    static String mapDesc(String d, Object unused, java.util.function.UnaryOperator<String> rename) {
        return Exposure.renameClasses(d, true, rename);
    }

    /** The class (jar names) that declares a member a reference names, or the owner when none of its classes does. */
    String declarer(String owner, String name, String desc, boolean field) {
        Deque<String> queue = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String c = queue.poll();
            GameDiff.RawCls rc = seen.add(c) ? declared(c) : null;
            if (rc == null) {
                continue;
            }
            if (field ? rc.fields.containsKey(name) : rc.methods.containsKey(name + desc)) {
                return c;
            }
            if (rc.sup != null) {
                queue.add(rc.sup);
            }
            queue.addAll(rc.ifaces);
        }
        return owner;
    }

    /**
     * A method's own locals past its arguments as Mixin's STORE and LOAD points and @ModifyVariable read them: each
     * local the table declares ("slot desc", this release's names, in table order), then each load or store (load true)
     * or increment, in code order ("opcode slot"). Two methods giving the same have the same locals written (or read) in
     * the same order, whatever else their code does. Null when the method is not in the jar.
     */
    List<String> localUses(String owner, String name, String desc, boolean load) {
        List<String> table = new ArrayList<>(), uses = new ArrayList<>();
        boolean found = read(owner, name, desc, ClassReader.SKIP_FRAMES, (access, first) -> new MethodVisitor(Opcodes.ASM9) {
            @Override
            public void visitVarInsn(int opcode, int var) {
                if (var >= first && (opcode >= Opcodes.ISTORE) != load && opcode != Opcodes.RET) {
                    uses.add(opcode + " " + var);
                }
            }

            @Override
            public void visitIincInsn(int var, int increment) {
                if (var >= first) {
                    uses.add(Opcodes.IINC + " " + var);
                }
            }

            @Override
            public void visitLocalVariable(String ln, String ld, String signature, Label start, Label end, int index) {
                if (index >= first) {
                    table.add(index + " " + names.desc(ld));
                }
            }
        });
        if (!found) {
            return null;
        }
        table.addAll(uses);
        return table;
    }

    /** A method's own loads and stores of locals, counted by opcode (ILOAD..ALOAD, ISTORE..ASTORE); null when it is not in the jar. */
    int[] locals(String owner, String name, String desc) {
        int[] out = new int[256];
        boolean found = read(owner, name, desc, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES, (access, first) -> new MethodVisitor(Opcodes.ASM9) {
            @Override
            public void visitVarInsn(int opcode, int var) {
                out[opcode]++;
            }
        });
        return found ? out : null;
    }

    /** {kind, owner, name, desc} of each call and field access in a method's own code; null when it is not in the jar. */
    /**
     * A method's own instructions that name something (this release's Mojang names, lambdas apart), in order: {"m", owner,
     * name, desc} a call, {"f" or "p", ...} a field read or written, {"c", "", constant, ""} a constant, {"n", type, "", ""} a
     * new object, {"l", owner, name, desc} a method a lambda or method reference names. Calls and fields are named as
     * refs names them (by the class that declares them). Null when the method is not in the jar.
     */
    List<String[]> steps(String owner, String name, String desc) {
        String key = owner + "." + name + desc;
        if (stepsRead.containsKey(key)) {
            return stepsRead.get(key);
        }
        List<String[]> out = new ArrayList<>();
        boolean found = read(owner, name, desc, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES, (access, first) -> new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(int op, String o, String mn, String md, boolean itf) {
                    out.add(new String[] { "m", names.cls(o), names.method(declarer(o, mn, md, false), mn, md), names.desc(md) });
                }

                @Override
                public void visitFieldInsn(int op, String o, String fn, String fd) {
                    boolean read = op == Opcodes.GETFIELD || op == Opcodes.GETSTATIC;
                    out.add(new String[] { read ? "f" : "p", names.cls(o), names.field(declarer(o, fn, fd, true), fn), names.desc(fd) });
                }

                @Override
                public void visitLdcInsn(Object v) {
                    out.add(new String[] { "c", "", v instanceof Type t ? names.desc(t.getDescriptor()) : line(v), "" });
                }

                @Override
                public void visitTypeInsn(int op, String t) {
                    if (op == Opcodes.NEW) {
                        out.add(new String[] { "n", names.cls(t), "", "" });
                    }
                }

                @Override
                public void visitInvokeDynamicInsn(String n2, String d2, Handle bsm, Object... args) {
                    for (Object arg : args) {
                        if (arg instanceof Handle h) {
                            out.add(new String[] { "l", names.cls(h.getOwner()), names.method(h.getOwner(), h.getName(), h.getDesc()), names.desc(h.getDesc()) });
                        }
                    }
                }
            });
        List<String[]> result = found ? out : null;
        stepsRead.put(key, result);
        return result;
    }

    final Map<String, List<String[]>> stepsRead = new HashMap<>();
    Map<String, Set<String>> callers;

    /**
     * The methods (owner.name+desc, Mojang names) of the whole release whose own code calls or names (a method
     * reference) each method; read once, and only where the whole jar was read.
     */
    Set<String> callers(String owner, String name, String desc) {
        if (all == null) {
            return null;
        }
        whole();
        return callers.getOrDefault(owner + "." + name + desc, Set.of());
    }

    /** Each release's callers index, by the classes it was read from: every worker's porter reads the same release. */
    static final Map<Map<String, byte[]>, Map<String, Set<String>>> CALLERS = new IdentityHashMap<>();

    /** What makes a lambda or method reference: the method it runs and how many values it captures (Mojang names). */
    record Made(String owner, String name, String desc, int tag, int captures, String in) {}

    Map<String, List<Made>> made;

    /**
     * Every lambda and method reference the whole release makes for one interface method (owner.name+desc, its erased
     * descriptor): what each runs. Read once per release, and only where the whole jar was read.
     */
    List<Made> made(String owner, String name, String desc) {
        if (all == null) {
            return null;
        }
        whole();
        return made.getOrDefault(owner + "." + name + desc, List.of());
    }

    static final Map<Map<String, byte[]>, Map<String, List<Made>>> MADE = new IdentityHashMap<>();

    /**
     * Reads the whole release's code once for both of what is asked of all of it, the callers of each method and what
     * makes each lambda: a port that follows a value through the game asks both, and each read every class for itself.
     */
    void whole() {
        if (callers == null || made == null) {
            synchronized (CALLERS) {
                callers = CALLERS.get(all);
            }
            synchronized (MADE) {
                made = MADE.get(all);
            }
        }
        if (callers != null && made != null) {
            return;
        }
        Map<String, Set<String>> calling = new HashMap<>();
        Map<String, List<Made>> making = new HashMap<>();
        Map<String, String> called = new HashMap<>(); // a call as written -> the method it calls: the game writes each many times
        for (var e : all.entrySet()) {
            String jarOwner = e.getKey();
            String self = names.cls(jarOwner);
            new ClassReader(e.getValue()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                    String me = self + "." + names.method(jarOwner, n, d) + names.desc(d);
                    return new MethodVisitor(Opcodes.ASM9) {
                        void add(String o, String mn, String md) {
                            String callee = called.computeIfAbsent(o + "." + mn + md, k -> {
                                String declares = declarer(o, mn, md, false);
                                return names.cls(declares) + "." + names.method(declares, mn, md) + names.desc(md);
                            });
                            calling.computeIfAbsent(callee, k -> new HashSet<>()).add(me);
                        }

                        @Override
                        public void visitMethodInsn(int op, String o, String mn, String md, boolean itf) {
                            add(o, mn, md);
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String sam, String d2, Handle bsm, Object... args) {
                            for (Object arg : args) {
                                if (arg instanceof Handle h) {
                                    add(h.getOwner(), h.getName(), h.getDesc());
                                }
                            }
                            if (!bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") || args.length < 2
                                || !(args[0] instanceof Type erased) || !(args[1] instanceof Handle h)) {
                                return;
                            }
                            String iface = Type.getReturnType(d2).getInternalName();
                            String key = names.cls(iface) + "." + names.method(declarer(iface, sam, erased.getDescriptor(), false), sam, erased.getDescriptor())
                                + names.desc(erased.getDescriptor());
                            String o = declarer(h.getOwner(), h.getName(), h.getDesc(), false);
                            making.computeIfAbsent(key, k -> new ArrayList<>()).add(new Made(names.cls(o), names.method(o, h.getName(), h.getDesc()),
                                names.desc(h.getDesc()), h.getTag(), Type.getArgumentTypes(d2).length, me));
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        callers = calling;
        made = making;
        synchronized (CALLERS) {
            CALLERS.put(all, calling);
        }
        synchronized (MADE) {
            MADE.put(all, making);
        }
    }

    /**
     * What a game method's new code has where its old code had an injection point. game says what the game did:
     * "ordinal" (the same instruction, now the ordinal-th of its kind), "overload" (a call of the same method with other
     * parameters), "renamed" (a call of another method of the same class and signature), "accessor" (a field now read
     * or written through the one method of its class that does it), "helper" (moved into a method the new code calls,
     * of the same class or not, in member), "dropped" (nothing in its place) or "not read". member is {kind ('m' or
     * 'f'), owner, name, desc} of what is there now (new names). The old and new code are lined up by what they name
     * (the longest run in common, old class names renamed into new ones): the point's place is the gap between the
     * named instructions around it that line up.
     */
    record Slot(String game, String detail, String[] member, int ordinal) {}

    static final Slot NOT_READ = new Slot("not read", null, null, -1);

    static Slot slot(GameCode before, GameCode after, java.util.function.UnaryOperator<String> rename, String[] oldMethod, String[] newMethod,
                     char kind, String owner, String name, String desc, int ordinal) {
        return slot(before, after, rename, oldMethod, newMethod, kind, owner, name, desc, ordinal, Set.of());
    }

    /** As slot, where answers are the calls ("owner.name desc", new names) a stand-in for the point's member makes. */
    static Slot slot(GameCode before, GameCode after, java.util.function.UnaryOperator<String> rename, String[] oldMethod, String[] newMethod,
                     char kind, String owner, String name, String desc, int ordinal, Set<String> answers) {
        List<String[]> old = before == null ? null : before.steps(oldMethod[0], oldMethod[1], oldMethod[2]);
        List<String[]> now = after == null ? null : after.steps(newMethod[0], newMethod[1], newMethod[2]);
        if (old == null || now == null || (long) old.size() * now.size() > 4_000_000L) {
            return NOT_READ;
        }
        List<String[]> was = old.stream().map(x -> new String[] { x[0], x[0].equals("c") ? "" : rename.apply(x[1]), x[2], x[0].equals("c") ? "" : mapDesc(x[3], null, rename) }).toList();
        // The point's instructions in the old code: by name and descriptor, and by owner where one names it.
        // Mixin matches the owner an instruction names; where none names the @At's owner, one the member resolves to counts.
        List<Integer> at = new ArrayList<>(), named = new ArrayList<>();
        for (int i = 0; i < old.size(); i++) {
            String[] x = old.get(i);
            if ((kind == 'm' ? x[0].equals("m") : x[0].equals("f") || x[0].equals("p")) && x[2].equals(name) && (desc == null || x[3].equals(desc))) {
                at.add(i);
                if (x[1].equals(owner)) {
                    named.add(i);
                }
            }
        }
        at = named.isEmpty() ? at : named;
        if (at.isEmpty() || ordinal >= at.size()) {
            return NOT_READ;
        }
        int[] map = align(was, now);
        List<Integer> points = ordinal >= 0 ? List.of(at.get(ordinal)) : at;
        Slot found = null;
        for (int p : points) {
            Slot one = place(after, was, now, map, p, oldMethod, newMethod, answers);
            if (found == null || found.game().equals(one.game()) && Arrays.equals(found.member(), one.member())) {
                found = found == null || one.ordinal() < found.ordinal() || found.ordinal() < 0 ? one : found;
            } else {
                return new Slot("mixed", found.game() + ", " + one.game(), null, -1);
            }
        }
        return found;
    }

    /** The longest run of named instructions two bodies have in common: for each old one, the new one it lines up with, or -1. */
    static int[] align(List<String[]> old, List<String[]> now) {
        int a = old.size(), b = now.size();
        String[] x = old.stream().map(GameCode::named).toArray(String[]::new), y = now.stream().map(GameCode::named).toArray(String[]::new);
        int[][] dp = new int[a + 1][b + 1];
        for (int i = a - 1; i >= 0; i--) {
            for (int j = b - 1; j >= 0; j--) {
                dp[i][j] = x[i].equals(y[j]) ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        int[] map = new int[a];
        Arrays.fill(map, -1);
        int i = 0, j = 0;
        while (i < a && j < b) {
            if (x[i].equals(y[j])) {
                map[i++] = j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return map;
    }

    /** A named instruction as it lines up: a lambda by the method it is in, as javac numbers lambdas anew in each release. */
    static String named(String[] x) {
        return x[0] + " " + x[1] + "." + (x[0].equals("l") ? x[2].replaceAll("\\$\\d+$", "") : x[2]) + x[3];
    }

    static Slot place(GameCode after, List<String[]> was, List<String[]> now, int[] map, int p, String[] oldMethod, String[] newMethod, Set<String> answers) {
        String[] point = was.get(p);
        char kind = point[0].equals("m") ? 'm' : 'f';
        if (map[p] >= 0) {
            // Still there: the ordinal-th of the instructions that name it now.
            int n = 0;
            for (int j = 0; j < map[p]; j++) {
                n += named(now.get(j)).equals(named(point)) ? 1 : 0;
            }
            return new Slot("ordinal", "now its #" + n, new String[] { String.valueOf(kind), point[1], point[2], point[3] }, n);
        }
        int lo = -1, hi = now.size();
        for (int i = p - 1; i >= 0; i--) {
            if (map[i] >= 0) {
                lo = map[i];
                break;
            }
        }
        for (int i = p + 1; i < was.size(); i++) {
            if (map[i] >= 0) {
                hi = map[i];
                break;
            }
        }
        List<String[]> gap = now.subList(lo + 1, Math.max(lo + 1, hi));
        String self = newMethod[0] + "." + newMethod[1] + newMethod[2];
        if (kind == 'f') {
            // A field the new code reaches through a method of its class that reads (or writes) it, ahead of
            // any other method that has it: that method is the field's own way in now (1.21.5's Inventory.setSelectedSlot).
            boolean read = point[0].equals("f");
            String wanted = read ? "()" + point[3] : "(" + point[3] + ")V";
            for (String[] x : gap) {
                if (x[0].equals("m") && x[1].equals(point[1]) && x[3].equals(wanted)) {
                    List<String[]> inner = after.steps(x[1], x[2], x[3]);
                    if (inner != null && inner.stream().anyMatch(y -> y[0].equals(point[0]) && y[2].equals(point[2]))) {
                        return found(now, x, "accessor", 'm');
                    }
                }
            }
        }
        // Another signature of the same call in its place (often one that hands over to the old one, as 1.21.4's
        // GuiGraphics.drawString(font, text, x, y, color) does to the one taking a shadow flag).
        for (String[] x : gap) {
            if (x[0].equals(point[0]) && x[1].equals(point[1]) && x[2].equals(point[2]) && !x[3].equals(point[3])) {
                return found(now, x, "overload", kind);
            }
        }
        // A call of a method whose code has the point: it moved there (Gui.renderPlayerHealth -> renderAirBubbles).
        for (List<String[]> where : List.of(gap, now)) {
            for (String[] x : where) {
                if (!x[0].equals("m") && !x[0].equals("l") || (x[1] + "." + x[2] + x[3]).equals(self)) {
                    continue;
                }
                List<String[]> inner = after.steps(x[1], x[2], x[3]);
                if (inner != null && inner.stream().anyMatch(y -> y[0].equals(point[0]) && y[2].equals(point[2]) && y[3].equals(point[3]))) {
                    int n = 0;
                    for (String[] y : now) {
                        n += named(y).equals(named(x)) ? 1 : 0;
                    }
                    String detail = (x[1].equals(newMethod[0]) ? "" : "another class: ") + Exposure.dotted(x[1]) + "." + x[2] + x[3] + (where == gap ? "" : ", elsewhere in the method")
                        + (n > 1 ? ", called " + n + " times" : "");
                    return new Slot("helper", detail, new String[] { "m", x[1], x[2], x[3] }, -1);
                }
            }
        }
        if (kind == 'm') {
            // The one call in its place of another method of the class with its signature.
            List<String[]> like = gap.stream().filter(x -> x[0].equals("m") && x[1].equals(point[1]) && x[3].equals(point[3]) && !x[2].equals(point[2])).toList();
            if (like.size() == 1) {
                return found(now, like.get(0), "renamed", 'm');
            }
        }
        if (kind == 'm') {
            // The one call of the same name in its place on another class, taking the point's arguments in order and
            // others beside: the call made through what the old receiver stood for (1.20's graphics.drawString(font, ...)
            // is 1.19.4's GuiComponent.drawString(pose, font, ...), the GuiGraphics an object around that PoseStack).
            // Several such calls in a row pair up in order when the old code has as many of the point there.
            List<String[]> same = gap.stream().filter(x -> x[0].equals("m") && x[2].equals(point[2]) && !x[1].equals(point[1]) && within(point[3], x[3])).toList();
            int from = p, to = p;
            while (from > 0 && map[from - 1] < 0) {
                from--;
            }
            while (to < was.size() - 1 && map[to + 1] < 0) {
                to++;
            }
            List<Integer> olds = new ArrayList<>();
            for (int i = from; i <= to; i++) {
                if (named(was.get(i)).equals(named(point))) {
                    olds.add(i);
                }
            }
            if (!same.isEmpty() && same.size() == olds.size()) {
                return found(now, same.get(olds.indexOf(p)), "same name", 'm');
            }
            // Else the one call of that name in its place, whatever it takes (1.20's graphics.renderTooltip(font, lines,
            // image, x, y) is 1.19.4's renderTooltip(pose, stack, x, y)): a place, for an injector that takes only one.
            List<String[]> named = gap.stream().filter(x -> x[0].equals("m") && x[2].equals(point[2]) && !(x[1].equals(point[1]) && x[3].equals(point[3]))).toList();
            if (named.size() == 1 && olds.size() == 1) {
                return found(now, named.get(0), "named", 'm');
            }
            // A call a stand-in answers (a member of a class the older release lacks, on a port back) is, in the older
            // game's code, the call the stand-in makes for it: 1.20's graphics.drawString(font, text, x, y, color) is
            // 1.19.4's font.drawShadow(pose, text, x, y, color), as the GuiGraphics stand-in writes it. Of the calls it
            // can make, those in its place pair up in order, or those giving what the point gives when that is fewer.
            List<String[]> answered = gap.stream().filter(x -> x[0].equals("m") && answers.contains(x[1] + "." + x[2] + x[3])).toList();
            if (answered.size() != olds.size()) {
                String gives = Type.getReturnType(point[3]).getDescriptor();
                answered = answered.stream().filter(x -> Type.getReturnType(x[3]).getDescriptor().equals(gives)).toList();
            }
            if (!answered.isEmpty() && answered.size() == olds.size()) {
                return found(now, answered.get(olds.indexOf(p)), "stand-in", 'm');
            }
        }
        return new Slot("dropped", gap.isEmpty() ? "nothing in its place" : gap.size() + " other instructions in its place", null, -1);
    }

    /** Whether the arguments of descriptor a stand in b's in their order, b taking at least one more. */
    static boolean within(String a, String b) {
        Type[] x = Type.getArgumentTypes(a), y = Type.getArgumentTypes(b);
        int j = 0;
        for (int i = 0; i < y.length && j < x.length; i++) {
            j += y[i].equals(x[j]) ? 1 : 0;
        }
        return j == x.length && y.length > x.length;
    }

    /** A slot holding x, with its ordinal among the instructions of the new code that name the same. */
    static Slot found(List<String[]> now, String[] x, String game, char kind) {
        int n = 0;
        for (String[] y : now) {
            if (y == x) {
                break;
            }
            n += named(y).equals(named(x)) ? 1 : 0;
        }
        return new Slot(game, Exposure.dotted(x[1]) + "." + x[2] + x[3], new String[] { String.valueOf(kind), x[1], x[2], x[3] }, n);
    }

    List<String[]> refs(String owner, String name, String desc) {
        List<String[]> out = new ArrayList<>();
        boolean found = read(owner, name, desc, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES, (access, first) -> new MethodVisitor(Opcodes.ASM9) {
            @Override
            public void visitMethodInsn(int opcode, String o, String mn, String md, boolean itf) {
                out.add(new String[] { "m", names.cls(o), names.method(declarer(o, mn, md, false), mn, md), names.desc(md) });
            }

            @Override
            public void visitFieldInsn(int opcode, String o, String fn, String fd) {
                out.add(new String[] { "f", names.cls(o), names.field(declarer(o, fn, fd, true), fn), names.desc(fd) });
            }
        });
        return found ? out : null;
    }
}

import java.util.*;
import org.objectweb.asm.*;

/** One jar's copies of the newer game's methods (copyNewer): which methods can be copied, the copies, and the calls moved to them. */
final class Copier {
    final JarPort port;

    final GameCode code;
    final Map<String, GameDiff.RawCls> own;
    final String home;
    final Map<String, String[]> copies = new LinkedHashMap<>(); // decl.name+desc (a's names) -> {copy class, copy name, copy desc}
    final Set<String> refused = new HashSet<>();
    final Set<String> called = new TreeSet<>(); // the copied methods the jar's own classes call
    final Map<String, Set<String>> names = new HashMap<>(); // copy class -> name+desc taken

    Copier(JarPort port, GameCode code, Map<String, GameDiff.RawCls> own, String home) {
        this.port = port;
        this.code = code;
        this.own = own;
        this.home = home;
    }

    /** One of the jar's classes with its calls (and method references) to the newer game's methods pointed at their copies, or null when it calls none. */
    byte[] redirect(byte[] bytes) {
        boolean[] moved = { false };
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, signature, exceptions)) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        String[] to = target(op, owner, n, d);
                        if (to != null) {
                            moved[0] = true;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, to[0], to[1], to[2], false);
                            return;
                        }
                        super.visitMethodInsn(op, owner, n, d, itf);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
                        Object[] now = args.clone();
                        for (int i = 0; i < now.length; i++) {
                            if (now[i] instanceof Handle h && h.getTag() >= Opcodes.H_INVOKEVIRTUAL && !h.getName().startsWith("<")) {
                                String[] to = target(h.getTag() == Opcodes.H_INVOKESTATIC ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL, h.getOwner(), h.getName(), h.getDesc());
                                if (to != null) {
                                    moved[0] = true;
                                    now[i] = new Handle(Opcodes.H_INVOKESTATIC, to[0], to[1], to[2], false);
                                }
                            }
                        }
                        super.visitInvokeDynamicInsn(n, d, bsm, now);
                    }
                };
            }
        }, 0);
        return moved[0] ? writer.toByteArray() : null;
    }

    /**
     * The copy a call from the jar goes to ({class, name, desc}), made here when it can be; null when the call stays.
     * A super call (op INVOKESPECIAL) runs the game's code whatever the jar's class overrides.
     */
    String[] target(int op, String owner, String name, String desc) {
        if (name.startsWith("<") || owner.startsWith("[")) {
            return null;
        }
        // A call on one of the jar's classes reaches the game's method through the game class it extends.
        String game = owner;
        Set<String> seen = new HashSet<>();
        while (own.containsKey(game) && seen.add(game)) {
            GameDiff.RawCls rc = own.get(game);
            if (rc.methods.containsKey(name + desc) && op != Opcodes.INVOKESPECIAL || rc.sup == null) {
                return null; // the jar's own method, or one it overrides
            }
            game = rc.sup;
        }
        GameDiff.Cls decl = Exposure.declaring(port.a, game, 'm', name, desc);
        if (decl == null || decl.library || decl.loader || !GameDiff.gameClass(decl.name) || port.newerCode.handled(game, name, desc) || port.newerCode.handled(decl.name, name, desc)
            || Exposure.declaring(port.b, port.cls(game), 'm', name, port.desc(desc)) != null || Exposure.declaring(port.b, port.cls(decl.name), 'm', name, port.desc(desc)) != null) {
            return null;
        }
        for (GameDiff.RawCls rc : op == Opcodes.INVOKESPECIAL || op == Opcodes.INVOKESTATIC ? List.<GameDiff.RawCls>of() : own.values()) {
            if (rc.methods.containsKey(name + desc) && (rc.flags & Opcodes.ACC_INTERFACE) == 0 && reaches(rc.name, decl.name)) {
                return null; // a class of the jar overrides it: a call on the game type may run that override
            }
        }
        String key = decl.name + "." + name + desc;
        // A method copied on the way to one that cannot be (a cycle back to it) goes with it.
        Set<String> before = new HashSet<>(copies.keySet());
        if (!copyable(decl.name, name, desc, new LinkedHashSet<>())) {
            copies.keySet().retainAll(before);
            return null;
        }
        called.add(key);
        return copies.get(key);
    }

    /** Whether c (the jar's or a's class) is decl or extends it. */
    boolean reaches(String c, String decl) {
        Deque<String> todo = new ArrayDeque<>(List.of(c));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            String x = todo.pop();
            if (x.equals(decl)) {
                return true;
            }
            if (!seen.add(x)) {
                continue;
            }
            GameDiff.RawCls rc = own.get(x);
            GameDiff.Cls gc = rc == null ? port.a.classes.get(x) : null;
            String sup = rc != null ? rc.sup : gc != null ? gc.sup : null;
            if (sup != null) {
                todo.push(sup);
            }
            todo.addAll(rc != null ? rc.ifaces : gc != null ? gc.ifaces : List.of());
        }
        return false;
    }

    /**
     * Whether a's method decl.name+desc (a's names) can be copied: it has code, and every class, field and method it
     * uses is in b, is answered by the port (a route, rule or stand-in, which ports the copy as the jar's code), or
     * is another method of a that can be copied. Each copyable method gets its copy's place in copies.
     */
    boolean copyable(String decl, String name, String desc, Set<String> closure) {
        String key = decl + "." + name + desc;
        if (copies.containsKey(key) || closure.contains(key)) {
            return true;
        }
        if (refused.contains(key)) {
            return false;
        }
        String jar = code.toJar.getOrDefault(decl, decl);
        byte[] bytes = code.bytes.apply(jar);
        if (bytes == null) {
            refused.add(key);
            return false;
        }
        for (Type t : Type.getArgumentTypes(desc)) {
            if (!present(t)) {
                refused.add(key);
                return false;
            }
        }
        if (!present(Type.getReturnType(desc))) {
            refused.add(key);
            return false;
        }
        closure.add(key);
        List<String[]> calls = new ArrayList<>(); // {Mojang owner, name, desc} of a's methods it calls that b lacks
        boolean[] ok = { false };
        boolean[] instance = { false };
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                if (!(code.names.method(jar, n, d) + code.names.desc(d)).equals(name + desc) || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    return null;
                }
                ok[0] = true;
                instance[0] = (access & Opcodes.ACC_STATIC) == 0;
                return new MethodVisitor(Opcodes.ASM9) {
                    void method(int op, String o, String n2, String d2) {
                        if (!ok[0] || o.startsWith("[")) {
                            return;
                        }
                        String declObf = code.declarer(o, n2, d2, false);
                        String mo = code.names.cls(o), md = code.names.desc(d2);
                        String mn = n2.startsWith("<") ? n2 : code.names.method(declObf, n2, d2);
                        String md0 = code.names.cls(declObf);
                        if (!GameDiff.gameClass(mo)) {
                            GameDiff.Cls lib = port.a.classes.get(mo);
                            ok[0] &= lib == null || Exposure.declaring(port.b, mo, 'm', mn, md) != null;
                            return;
                        }
                        if (op == Opcodes.INVOKESPECIAL && !mn.equals("<init>") && !md0.equals(decl)) {
                            ok[0] = false; // a super call, which only the class itself can make
                            return;
                        }
                        if (Exposure.declaring(port.b, port.cls(mo), 'm', mn, port.desc(md)) != null || port.newerCode.handled(mo, mn, md) || port.newerCode.handled(md0, mn, md)) {
                            return;
                        }
                        if (mn.startsWith("<")) {
                            ok[0] = false; // a constructor b does not have
                            return;
                        }
                        calls.add(new String[] { md0, mn, md });
                    }

                    @Override
                    public void visitMethodInsn(int op, String o, String n2, String d2, boolean itf) {
                        method(op, o, n2, d2);
                    }

                    @Override
                    public void visitFieldInsn(int op, String o, String n2, String d2) {
                        String declObf = code.declarer(o, n2, d2, true);
                        String mo = code.names.cls(declObf), mn = code.names.field(declObf, n2), md = code.names.desc(d2);
                        if (!GameDiff.gameClass(mo)) {
                            ok[0] &= port.a.classes.get(mo) == null || Exposure.declaring(port.b, mo, 'f', mn, null) != null;
                            return;
                        }
                        GameDiff.Cls holder = Exposure.declaring(port.b, port.cls(mo), 'f', mn, null);
                        ok[0] &= holder != null && holder.fields.get(mn).desc.equals(port.desc(md)) || port.newerCode.handled(mo, mn, "");
                    }

                    @Override
                    public void visitTypeInsn(int op, String t) {
                        ok[0] &= present(Type.getObjectType(code.names.cls(t)));
                    }

                    @Override
                    public void visitMultiANewArrayInsn(String d2, int dims) {
                        ok[0] &= present(Type.getType(code.names.desc(d2)));
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof Type t) {
                            ok[0] &= t.getSort() == Type.METHOD ? describedPresent(code.names.desc(t.getDescriptor())) : present(Type.getType(code.names.desc(t.getDescriptor())));
                        } else if (value instanceof Handle || value instanceof ConstantDynamic) {
                            ok[0] = false;
                        }
                    }

                    @Override
                    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                        ok[0] &= type == null || present(Type.getObjectType(code.names.cls(type)));
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String n2, String d2, Handle bsm, Object... args) {
                        ok[0] &= bsm.getOwner().startsWith("java/") && describedPresent(code.names.desc(d2));
                        for (Object arg : args) {
                            if (arg instanceof Handle h) {
                                int op = switch (h.getTag()) {
                                    case Opcodes.H_INVOKESTATIC -> Opcodes.INVOKESTATIC;
                                    case Opcodes.H_INVOKESPECIAL -> Opcodes.INVOKESPECIAL;
                                    case Opcodes.H_NEWINVOKESPECIAL -> Opcodes.INVOKESPECIAL;
                                    case Opcodes.H_INVOKEINTERFACE -> Opcodes.INVOKEINTERFACE;
                                    case Opcodes.H_INVOKEVIRTUAL -> Opcodes.INVOKEVIRTUAL;
                                    default -> -1;
                                };
                                if (op < 0) {
                                    ok[0] = false;
                                } else {
                                    method(op, h.getOwner(), h.getName(), h.getDesc());
                                }
                            } else if (arg instanceof Type t) {
                                ok[0] &= t.getSort() == Type.METHOD ? describedPresent(code.names.desc(t.getDescriptor())) : present(Type.getType(code.names.desc(t.getDescriptor())));
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        boolean all = ok[0];
        for (String[] c : calls) {
            all = all && port.a.classes.containsKey(c[0]) && !port.a.classes.get(c[0]).library && copyable(c[0], c[1], c[2], closure);
        }
        if (!all) {
            closure.remove(key);
            refused.add(key);
            return false;
        }
        String place = home + decl;
        String copyDesc = instance[0] ? "(L" + decl + ";" + desc.substring(1) : desc;
        Set<String> taken = names.computeIfAbsent(place, k -> new HashSet<>());
        String copyName = name.replaceAll("[^\\w$]", "_");
        for (int i = 1; !taken.add(copyName + copyDesc); i++) {
            copyName = name.replaceAll("[^\\w$]", "_") + "$" + i;
        }
        copies.put(key, new String[] { place, copyName, copyDesc });
        return true;
    }

    /** A type a copy names: the JDK's, or a class b has (under the route's name). */
    boolean present(Type t) {
        if (t.getSort() == Type.ARRAY) {
            t = t.getElementType();
        }
        if (t.getSort() != Type.OBJECT) {
            return true;
        }
        String n = t.getInternalName();
        return n.startsWith("java/") || port.b.classes.containsKey(port.cls(n)) || !GameDiff.gameClass(n) && !port.a.classes.containsKey(n);
    }

    boolean describedPresent(String methodDesc) {
        for (Type t : Type.getArgumentTypes(methodDesc)) {
            if (!present(t)) {
                return false;
            }
        }
        return present(Type.getReturnType(methodDesc));
    }

    /**
     * The copies' classes, in a's names: each copied method public and static, an instance method's receiver its first
     * parameter, its code as a's with calls among the copies pointed at each other and a private method of b's (the
     * same class's own call) made an ordinary call, which the port then opens.
     */
    Map<String, byte[]> write() {
        Map<String, ClassWriter> classes = new TreeMap<>();
        for (var e : copies.entrySet()) {
            String key = e.getKey();
            String[] to = e.getValue();
            int dot = key.indexOf('.');
            String decl = key.substring(0, dot), member = key.substring(dot + 1);
            String jar = code.toJar.getOrDefault(decl, decl);
            byte[] bytes = code.bytes.apply(jar);
            ClassWriter cw = classes.computeIfAbsent(to[0], place -> {
                ClassWriter w = new ClassWriter(0);
                w.visit(new ClassReader(bytes).readUnsignedShort(6), Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC, place, null, "java/lang/Object", null);
                return w;
            });
            org.objectweb.asm.commons.Remapper mojang = new org.objectweb.asm.commons.Remapper(Opcodes.ASM9) {
                @Override
                public String map(String internal) {
                    return code.names.cls(internal);
                }

                @Override
                public String mapMethodName(String owner, String n, String d) {
                    String declObf = n.startsWith("<") || owner.startsWith("[") ? null : code.declarer(owner, n, d, false);
                    return declObf == null ? n : code.names.method(declObf, n, d);
                }

                @Override
                public String mapFieldName(String owner, String n, String d) {
                    return code.names.field(code.declarer(owner, n, d, true), n);
                }

                @Override
                public String mapInvokeDynamicMethodName(String n, String d) {
                    return n; // the functional interface's method: the JDK's or the game's, whose names a's mappings give below
                }
            };
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                    if (!(code.names.method(jar, n, d) + code.names.desc(d)).equals(member)) {
                        return null;
                    }
                    MethodVisitor out = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, to[1], to[2], null, null);
                    return new org.objectweb.asm.commons.MethodRemapper(new MethodVisitor(Opcodes.ASM9, out) {
                        @Override
                        public void visitMethodInsn(int op, String owner, String n2, String d2, boolean itf) {
                            String[] copy = copied(owner, n2, d2);
                            if (copy != null) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, copy[0], copy[1], copy[2], false);
                            } else if (op == Opcodes.INVOKESPECIAL && !n2.equals("<init>")) {
                                super.visitMethodInsn(itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, owner, n2, d2, itf);
                            } else {
                                super.visitMethodInsn(op, owner, n2, d2, itf);
                            }
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String n2, String d2, Handle bsm, Object... args) {
                            Object[] now = args.clone();
                            for (int i = 0; i < now.length; i++) {
                                if (now[i] instanceof Handle h && !h.getName().startsWith("<")) {
                                    String[] copy = copied(h.getOwner(), h.getName(), h.getDesc());
                                    if (copy != null) {
                                        now[i] = new Handle(Opcodes.H_INVOKESTATIC, copy[0], copy[1], copy[2], false);
                                    } else if (h.getTag() == Opcodes.H_INVOKESPECIAL) {
                                        now[i] = new Handle(h.isInterface() ? Opcodes.H_INVOKEINTERFACE : Opcodes.H_INVOKEVIRTUAL, h.getOwner(), h.getName(), h.getDesc(), h.isInterface());
                                    }
                                }
                            }
                            // The functional interface's method name: a's mapping of it on the interface the call site makes.
                            Type made = Type.getReturnType(d2);
                            String sam = n2;
                            if (bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") && now.length > 0 && now[0] instanceof Type erased) {
                                String iface = code.toJar.getOrDefault(made.getInternalName(), made.getInternalName());
                                String obfDesc = mapDescToJar(erased.getDescriptor());
                                String named = code.names.method(code.declarer(iface, n2, obfDesc, false), n2, obfDesc);
                                sam = named;
                            }
                            super.visitInvokeDynamicInsn(sam, d2, bsm, now);
                        }
                    }, mojang);
                }
            }, 0);
        }
        Map<String, byte[]> out = new LinkedHashMap<>();
        classes.forEach((place, w) -> {
            w.visitEnd();
            out.put(place + ".class", w.toByteArray());
        });
        return out;
    }

    /** A descriptor in a's Mojang names written in its jar's names, for asking the mappings about it. */
    String mapDescToJar(String d) {
        return JarPort.mapDesc(d, code.toJar);
    }

    /** The copy of a's method owner.name+desc (Mojang names), or null when it is not copied. */
    String[] copied(String owner, String name, String desc) {
        if (owner.startsWith("[")) {
            return null;
        }
        // A lambda's body is no member the snapshot keeps: it is copied under its own class.
        String[] own = copies.get(owner + "." + name + desc);
        GameDiff.Cls decl = own != null ? null : Exposure.declaring(port.a, owner, 'm', name, desc);
        return own != null ? own : decl == null ? null : copies.get(decl.name + "." + name + desc);
    }
}

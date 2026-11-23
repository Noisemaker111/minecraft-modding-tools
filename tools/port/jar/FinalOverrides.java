import java.util.*;
import java.util.regex.*;
import org.objectweb.asm.*;

/** A mod's override of a game method the update made final: its body moved under the hook the game now calls. */
final class FinalOverrides {

    final JarPort port;

    FinalOverrides(JarPort port) {
        this.port = port;
    }

    /** A game class's method (new names) copied into a mod class as a method named as, with access; no source: an empty one. */
    record Copy(String source, String name, String desc, String as, int access) {}

    /**
     * {class, name, desc} (new names) of the overridable method a mod method's game method hands its call to, where the
     * update made that game method final (1.20.3's AbstractWidget.render calls renderWidget with its own arguments;
     * 1.21.11's AbstractButton.renderWidget calls renderContents): the one method of the same signature on this that the
     * final one's body calls. Null when the game method is not newly final or calls no such one.
     */
    String[] finalHook(JarPort.Mod mod, GameDiff.RawCls rc, GameDiff.RawMeth m, List<String> supers) {
        String mapped = port.desc(m.desc);
        GameDiff.Cls fin = null;
        for (String s : supers) {
            GameDiff.Cls k = Exposure.declaring(port.b, s, 'm', m.name, mapped);
            if (k != null) {
                fin = (k.methods.get(m.name + mapped).flags & Opcodes.ACC_FINAL) != 0 ? k : null;
                break;
            }
        }
        GameCode code = port.code(port.b);
        if (fin == null || code == null || finalBefore(mod, rc, m)) {
            return null;
        }
        Map<String, String> bodies = code.bodies(fin.name, x -> x);
        String body = bodies == null ? null : bodies.get(m.name + mapped);
        if (body == null) {
            return null;
        }
        Set<String> called = new HashSet<>();
        Matcher calls = Pattern.compile("(?m)^18[25]:([^\\n.]+)\\.([^\\n.(]+)" + Pattern.quote(mapped) + "$").matcher(body);
        while (calls.find()) {
            if (!calls.group(2).equals(m.name) && Linking.subtype(port.b, fin.name, calls.group(1))) {
                called.add(calls.group(2));
            }
        }
        if (called.size() != 1) {
            return null;
        }
        String h = called.iterator().next();
        GameDiff.Cls holder = Exposure.declaring(port.b, fin.name, 'm', h, mapped);
        GameDiff.Meth hook = holder == null ? null : holder.methods.get(h + mapped);
        return hook == null || (hook.flags & (Opcodes.ACC_FINAL | Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0 ? null : new String[] { fin.name, h, mapped };
    }

    /** Whether the game method a mod method overrides was final in the old release too (opened for the mod, then). */
    boolean finalBefore(JarPort.Mod mod, GameDiff.RawCls rc, GameDiff.RawMeth m) {
        for (GameDiff.RawCls c = rc; c != null; c = c.sup == null ? null : mod.classes.get(c.sup)) {
            List<String> parents = new ArrayList<>(c.ifaces);
            if (c.sup != null) {
                parents.add(c.sup);
            }
            for (String p : parents) {
                GameDiff.Cls k = mod.classes.containsKey(p) ? null : Exposure.declaring(port.a, p, 'm', m.name, m.desc);
                if (k != null && (k.methods.get(m.name + m.desc).flags & Opcodes.ACC_FINAL) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A mod method overriding a method the update made final moves to the hook the final one calls, as its code ran
     * around the old method's: the final method still does the rest (the visibility test, the hover, the tooltip).
     * The mod's super call to the old method becomes one to the hook the game class above implements; where the class
     * writes the hook itself, that method keeps its body under another name and the super call goes there, as the old
     * method called it. Not where the code calls the method on its own object again (the final one would call it back),
     * or where nothing above implements the hook it would call.
     */
    boolean moveToHook(JarPort.Mod mod, GameDiff.RawCls rc, GameDiff.RawMeth m, String[] hook, List<String> supers) {
        String h = hook[1], hd = hook[2];
        if (rc.methods.containsKey(h + "$mmt" + port.oldDesc(hd)) || callsOnSelf(mod, rc.name, m)) {
            JarPort.why(rc.name + "." + m.name, "H calls itself on its object");
            return false;
        }
        boolean writes = rc.methods.containsKey(h + port.oldDesc(hd));
        // A mod subclass writing the hook itself, without the old method, would take the final method's call before the
        // moved code (before, the old method ran first and reached its hook through its super call).
        for (GameDiff.RawCls sub : mod.classes.values()) {
            if (sub != rc && Linking.subtype(mod, sub.name, rc.name) && sub.methods.containsKey(h + port.oldDesc(hd)) && !sub.methods.containsKey(m.name + m.desc)) {
                JarPort.why(rc.name + "." + m.name, "H a subclass writes the hook: " + sub.name);
                return false;
            }
        }
        if (!writes && callsSuperDirectly(mod, rc.name, m)) {
            GameDiff.Cls impl = null;
            for (String s : supers) {
                impl = Exposure.declaring(port.b, s, 'm', h, hd);
                if (impl != null) {
                    break;
                }
            }
            Copy copy = null;
            if (impl == null || (impl.methods.get(h + hd).flags & Opcodes.ACC_ABSTRACT) != 0) {
                // The old method's own code went into parts the game's subclasses call (1.21.11: Button.Plain's
                // renderContents calls renderDefaultSprite and renderDefaultLabel): the super call runs that code.
                copy = supers.isEmpty() ? null : defaultBody(supers.get(0), m.name, m.desc, h, hd, rc.name);
                if (copy == null) {
                    JarPort.why(rc.name + "." + m.name, "H no hook above to call: " + (impl == null ? null : impl.name) + " supers=" + supers);
                    return false;
                }
                mod.copies.computeIfAbsent(rc.name, x -> new ArrayList<>()).add(copy);
                mod.superHook.put(rc.name + "." + m.name + m.desc, "copy:" + copy.as());
            }
            if (copy != null) {
                mod.renamedDecl.put(rc.name + "." + m.name + m.desc, new String[] { h, hd });
                mod.change("override of a method the update made final moved to the hook it calls, its super call to the code the game split from it");
                return true;
            }
        }
        if (writes) {
            mod.renamedDecl.put(rc.name + "." + h + port.oldDesc(hd), new String[] { h + "$mmt", hd });
        }
        mod.superHook.put(rc.name + "." + m.name + m.desc, writes ? "own:" + h + "$mmt" : h);
        mod.renamedDecl.put(rc.name + "." + m.name + m.desc, new String[] { h, hd });
        mod.change("override of a method the update made final moved to the hook it calls");
        return true;
    }

    /**
     * The game code a super call to an old method k inherited runs in the new release, where the update split that
     * method's code into parts: the hook h as written by k's game subclasses that relied on the old method as it was
     * (they did not override it in the old release; 1.21.11's new Button.Plain), when they all write the same code
     * and it uses only what the mod class inherits too. Null otherwise.
     */
    Copy defaultBody(String k, String o, String od, String h, String hd, String modClass) {
        GameCode code = port.code(port.b);
        if (code == null) {
            return null;
        }
        Set<String> seen = new HashSet<>();
        String chosen = null;
        for (GameDiff.Cls sub : port.links.declaring(port.b, h + hd)) {
            GameDiff.Meth own = sub.methods.get(h + hd);
            GameDiff.Cls old = port.a.classes.get(port.inverse.getOrDefault(sub.name, sub.name));
            if (own == null || (own.flags & Opcodes.ACC_ABSTRACT) != 0 || (sub.flags & Opcodes.ACC_ABSTRACT) != 0 || sub.name.equals(k) || !Linking.subtype(port.b, sub.name, k)
                || old != null && old.methods.containsKey(o + od)) {
                continue;
            }
            Map<String, String> bodies = code.bodies(sub.name, x -> x);
            String text = bodies == null ? null : bodies.get(h + hd);
            if (text != null && copyable(code, sub.name, h, hd, k)) {
                seen.add(text);
                chosen = sub.name;
            }
        }
        return seen.size() == 1 ? new Copy(chosen, h, hd, h + "$default", Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC) : null;
    }

    /**
     * Whether a game class's method uses only what a mod class under k can use too: members it inherits from k and
     * above (not private), and other classes' public members. No new instances or casts of the class itself.
     */
    boolean copyable(GameCode code, String source, String h, String hd, String k) {
        String jar = code.toJar.getOrDefault(source, source);
        byte[] bytes = code.bytes.apply(jar);
        if (bytes == null) {
            return false;
        }
        boolean[] ok = { false };
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                if (!(code.names.method(jar, n, d) + code.names.desc(d)).equals(h + hd)) {
                    return null;
                }
                ok[0] = true;
                return new MethodVisitor(Opcodes.ASM9) {
                    void member(String owner, String name, String desc, boolean field) {
                        String declObf = code.declarer(owner, name, desc, field);
                        String decl = declObf == null ? null : code.names.cls(declObf);
                        String mapped = declObf == null ? name : field ? code.names.field(declObf, name) : code.names.method(declObf, name, desc);
                        GameDiff.Cls dc = decl == null ? null : port.b.classes.get(decl);
                        if (dc == null) {
                            ok[0] &= !code.names.cls(owner).equals(source); // a JDK member, not one of the class's own
                            return;
                        }
                        int flags = field ? (dc.fields.containsKey(mapped) ? dc.fields.get(mapped).flags : Opcodes.ACC_PRIVATE)
                            : dc.methods.entrySet().stream().filter(e -> e.getKey().startsWith(mapped + "(") && e.getValue().desc.equals(code.names.desc(desc)))
                                .map(e -> e.getValue().flags).findFirst().orElse(Opcodes.ACC_PRIVATE);
                        boolean inherited = Linking.subtype(port.b, k, decl) && (flags & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0; // a mod class is in another package
                        boolean open = (flags & Opcodes.ACC_PUBLIC) != 0 && !code.names.cls(owner).equals(source);
                        ok[0] &= inherited || open;
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                        member(owner, name, desc, true);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        member(owner, name, desc, false);
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        ok[0] &= !code.names.cls(type).equals(source);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) {
                        ok[0] = false;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return ok[0];
    }

    /** Writes a copy of a game class's method into the mod class being written, its own class read as the mod's. */
    void copyBody(Copy copy, String modClass, ClassVisitor out) {
        if (copy.source() == null) {
            String d = port.oldDesc(copy.desc());
            MethodVisitor mv = out.visitMethod(copy.access(), copy.as(), d, null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 1 + Arrays.stream(Type.getArgumentTypes(d)).mapToInt(Type::getSize).sum());
            mv.visitEnd();
            return;
        }
        GameCode code = port.code(port.b);
        String jar = code.toJar.getOrDefault(copy.source(), copy.source());
        org.objectweb.asm.commons.Remapper names = new org.objectweb.asm.commons.Remapper(Opcodes.ASM9) {
            @Override
            public String map(String internal) {
                String n = code.names.cls(internal);
                return n.equals(copy.source()) ? modClass : port.inverse.getOrDefault(n, n);
            }

            @Override
            public String mapMethodName(String owner, String name, String desc) {
                String decl = name.startsWith("<") ? null : code.declarer(owner, name, desc, false);
                return decl == null ? name : code.names.method(decl, name, desc);
            }

            @Override
            public String mapFieldName(String owner, String name, String desc) {
                String decl = code.declarer(owner, name, desc, true);
                return decl == null ? name : code.names.field(decl, name);
            }
        };
        new ClassReader(code.bytes.apply(jar)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                if (!(code.names.method(jar, n, d) + code.names.desc(d)).equals(copy.name() + copy.desc())) {
                    return null;
                }
                MethodVisitor mv = out.visitMethod(copy.access(), copy.as(), names.mapMethodDesc(d), null, null);
                return mv == null ? null : new org.objectweb.asm.commons.MethodRemapper(mv, names);
            }
        }, 0);
    }

    /**
     * Whether any method of a mod class makes a super call that reaches the game's method of a mod method's name and
     * descriptor (the redirect to the hook applies to the whole class: a helper's super.renderWidget too), through mod
     * parents that only inherit it.
     */
    boolean callsSuperDirectly(JarPort.Mod mod, String owner, GameDiff.RawMeth m) {
        byte[] bytes = mod.bytes.get(owner);
        boolean[] calls = { false };
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String o, String n, String d, boolean itf) {
                        calls[0] |= opcode == Opcodes.INVOKESPECIAL && n.equals(m.name) && d.equals(m.desc) && port.links.gameDeclared(mod, o, n, d);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return calls[0];
    }

    /**
     * Whether a mod method, or a method of its class it calls on its own object, calls the method of its own name and
     * descriptor on its own object other than as a super call (this.render(...)): the final one would call it back.
     */
    boolean callsOnSelf(JarPort.Mod mod, String owner, GameDiff.RawMeth m) {
        byte[] bytes = mod.bytes.get(owner);
        if (bytes == null) {
            return true;
        }
        Deque<String> todo = new ArrayDeque<>(List.of(m.name + m.desc));
        Set<String> seen = new HashSet<>();
        boolean[] found = { false };
        while (!todo.isEmpty() && !found[0]) {
            String at = todo.poll();
            if (!seen.add(at)) {
                continue;
            }
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    if (!(name + desc).equals(at) || (access & Opcodes.ACC_STATIC) != 0) {
                        return null;
                    }
                    Object self = new Object();
                    return new org.objectweb.asm.commons.AnalyzerAdapter(Opcodes.ASM9, owner, access, name, desc, null) {
                        @Override
                        public void visitVarInsn(int opcode, int var) {
                            super.visitVarInsn(opcode, var);
                            if (opcode == Opcodes.ALOAD && var == 0 && stack != null) {
                                stack.set(stack.size() - 1, self);
                            }
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String o, String n, String d, boolean itf) {
                            int depth = Arrays.stream(Type.getArgumentTypes(d)).mapToInt(Type::getSize).sum();
                            boolean onSelf = opcode != Opcodes.INVOKESTATIC && stack != null && stack.size() > depth && stack.get(stack.size() - 1 - depth) == self;
                            if (onSelf && opcode != Opcodes.INVOKESPECIAL && n.equals(m.name) && d.equals(m.desc)) {
                                found[0] = true;
                            } else if (onSelf && o.equals(owner) && !n.startsWith("<")) {
                                todo.add(n + d);
                            }
                            super.visitMethodInsn(opcode, o, n, d, itf);
                        }
                    };
                }
            }, ClassReader.EXPAND_FRAMES);
        }
        return found[0];
    }
}

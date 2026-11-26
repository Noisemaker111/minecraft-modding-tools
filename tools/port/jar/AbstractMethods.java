import java.util.*;
import java.util.regex.*;
import org.objectweb.asm.*;

/** Abstract methods the update added to a class a mod extends: filled in as the game's own subclasses fill them. */
final class AbstractMethods {

    final JarPort port;

    AbstractMethods(JarPort port) {
        this.port = port;
    }

    /** A library's abstract method the old release's copy of that library had too: a mod that skips it skipped it before the port. */
    boolean alreadyAbstract(GameDiff.Cls k, GameDiff.Meth m) {
        GameDiff.Cls old = k.library ? port.a.classes.get(k.name) : null;
        GameDiff.Meth before = old == null ? null : old.methods.get(m.name + m.desc);
        return before != null && (before.flags & Opcodes.ACC_ABSTRACT) != 0;
    }

    /**
     * A concrete mod class must implement every abstract method of its game supertypes in the new release (1.21.2 added
     * Recipe.placementInfo()); one it does not is reported, never filled in.
     */
    void abstracts(JarPort.Mod mod, GameDiff.RawCls rc) {
        finals(mod, rc);
        if ((rc.flags & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) != 0) {
            return;
        }
        Set<String> written = new HashSet<>();
        Deque<String> game = new ArrayDeque<>();
        for (GameDiff.RawCls c = rc; c != null; c = c.sup == null ? null : mod.classes.get(c.sup)) {
            for (GameDiff.RawMeth m : c.methods.values()) {
                String[] renamed = mod.renamedDecl.get(c.name + "." + m.name + m.desc);
                written.add(renamed == null ? m.name + port.desc(m.desc) : renamed[0] + renamed[1]);
            }
            mod.bridges.getOrDefault(c.name, List.of()).forEach(br -> written.add(br.name() + br.desc()));
            mod.copies.getOrDefault(c.name, List.of()).stream().filter(cp -> cp.as().equals(cp.name())).forEach(cp -> written.add(cp.name() + cp.desc()));
            if (c.sup != null && !mod.classes.containsKey(c.sup)) {
                game.add(port.cls(c.sup));
            }
            for (String i : c.ifaces) {
                game.add(mod.classes.containsKey(i) ? i : port.cls(i));
            }
        }
        // A mod interface's own default methods count as written; its game parents are searched too.
        Set<String> seen = new HashSet<>();
        List<GameDiff.Cls> supers = new ArrayList<>();
        while (!game.isEmpty()) {
            String c = game.poll();
            if (!seen.add(c)) {
                continue;
            }
            GameDiff.RawCls own = mod.classes.get(c);
            if (own != null) {
                for (GameDiff.RawMeth m : own.methods.values()) {
                    if ((m.flags & Opcodes.ACC_ABSTRACT) == 0) {
                        String[] renamed = mod.renamedDecl.get(own.name + "." + m.name + m.desc);
                        written.add(renamed == null ? m.name + port.desc(m.desc) : renamed[0] + renamed[1]);
                    }
                }
                mod.bridges.getOrDefault(own.name, List.of()).forEach(br -> written.add(br.name() + br.desc()));
                own.ifaces.forEach(i -> game.add(mod.classes.containsKey(i) ? i : port.cls(i)));
                continue;
            }
            GameDiff.Cls k = port.b.classes.get(c);
            if (k == null) {
                continue;
            }
            supers.add(k);
            if (k.sup != null) {
                game.add(k.sup);
            }
            game.addAll(k.ifaces);
        }
        for (GameDiff.Cls k : supers) {
            for (GameDiff.Meth m : k.methods.values()) {
                if (m.name.startsWith("<")) {
                    continue;
                }
                written.add((m.flags & Opcodes.ACC_ABSTRACT) == 0 ? m.name + m.desc : "");
            }
        }
        Set<String> before = unwrittenBefore(mod, rc);
        for (GameDiff.Cls k : supers) {
            for (GameDiff.Meth m : k.methods.values()) {
                if ((m.flags & Opcodes.ACC_ABSTRACT) != 0 && !written.contains(m.name + m.desc) && !Linking.OBJECT.contains(m.name + m.desc) && !generic(supers, m)
                    && !alreadyAbstract(k, m) && !before.contains(m.name + port.oldDesc(m.desc))) {
                    FinalOverrides.Copy fill = gameDefault(mod, rc, k, m, supers);
                    if (fill != null) {
                        mod.copies.computeIfAbsent(rc.name, x -> new ArrayList<>()).add(fill);
                        written.add(m.name + m.desc);
                        mod.change(fill.source() == null ? "abstract method the update added written empty, as a game subclass writes it: the old release never asked the class" : "abstract method the update added written as the game's own subclasses write it");
                        continue;
                    }
                    mod.unlinked.add("abstract method not written: " + Exposure.dotted(k.name) + "." + m.name + m.desc + " (in " + Exposure.dotted(rc.name) + ")");
                }
            }
        }
    }

    /**
     * The body a mod class gets for an abstract method n of game class k that the update added and the class does not
     * write: the one most of the game's own subclasses of k write for it, of those whose body a mod class could carry
     * (1.17's widgets narrate through AbstractWidget's defaultButtonNarrationText, 1.19.4's key list entries refresh
     * nothing). Where the game's subclasses moved an old method into n instead, a class that relied on the old method's
     * default gets what it became (defaultBody). None where n takes over work: where a method the class inherited in the
     * old release now hands its work to n on the object (1.17's LevelHeightAccessor.getMaxBuildHeight asks getHeight),
     * or where the class writes a method the game no longer calls, whose code n may be meant to hold.
     */
    FinalOverrides.Copy gameDefault(JarPort.Mod mod, GameDiff.RawCls rc, GameDiff.Cls k, GameDiff.Meth n, List<GameDiff.Cls> supers) {
        GameCode code = port.code(port.b);
        // Only from the game's own code: a loader's or a library's type, or one whose code is not there to read, gets nothing.
        if (code == null || k.loader || k.library || code.bytes.apply(code.toJar.getOrDefault(k.name, k.name)) == null) {
            return null;
        }
        for (GameDiff.RawCls c = rc; c != null; c = c.sup == null ? null : mod.classes.get(c.sup)) {
            String in = " (in " + Exposure.dotted(c.name) + ")";
            if (mod.silent.stream().anyMatch(s -> s.endsWith(in))) {
                JarPort.why(rc.name + "." + n.name + n.desc, "D writes a method the game no longer calls");
                return null;
            }
        }
        // A method of that name the old class had makes n another form of it (1.21.11's ItemModelOutput.accept took a
        // third argument), which the class may write in its old form: a bridge's work, not a default's.
        GameDiff.Cls before = port.a.classes.get(port.inverse.getOrDefault(k.name, k.name));
        if (before != null && before.methods.values().stream().anyMatch(om -> om.name.equals(n.name))) {
            JarPort.why(rc.name + "." + n.name + n.desc, "D the old release had it in another form");
            return null;
        }
        int access = n.flags & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED);
        String[] was = port.overrides.takeover(k.name, n);
        if (was != null) {
            for (GameDiff.RawCls c = rc; c != null; c = c.sup == null ? null : mod.classes.get(c.sup)) {
                if (c.methods.containsKey(was[0] + was[1])) {
                    return null;
                }
            }
            FinalOverrides.Copy old = port.finalOverrides.defaultBody(k.name, was[0], was[1], n.name, n.desc, rc.name);
            if (old == null) {
                JarPort.why(rc.name + "." + n.name + n.desc, "D the game moved " + was[0] + was[1] + " into it, and its default became no one body");
                return null;
            }
            return new FinalOverrides.Copy(old.source(), n.name, n.desc, n.name, access);
        }
        for (GameDiff.Cls s : supers) {
            if (s.loader || s.library) {
                continue;
            }
            for (String d : Objects.requireNonNullElse(handsTo(code, s.name, n.name, n.desc), Set.<String>of())) {
                JarPort.Decl had = port.links.declaringOld(mod, rc.name, 'm', d.substring(0, d.indexOf('(')), port.oldDesc(d.substring(d.indexOf('('))));
                if (had != null && had.game()) {
                    JarPort.why(rc.name + "." + n.name + n.desc, "D " + s.name + "." + d + ", which the class had, hands its work to it");
                    return null;
                }
            }
        }
        String took = tookWork(k.name, n);
        if (took != null) {
            JarPort.why(rc.name + "." + n.name + n.desc, "D " + took + " calls it where its old code did more");
            return null;
        }
        List<String> parents = port.overrides.gameParents(mod, rc).stream().filter(p -> Linking.subtype(port.b, p, k.name)).toList();
        Map<String, Integer> count = new HashMap<>();
        Map<String, String> source = new HashMap<>();
        for (GameDiff.Cls w : port.links.declaring(port.b, n.name + n.desc, k.name)) {
            GameDiff.Meth own = w.methods.get(n.name + n.desc);
            if (own == null || (own.flags & Opcodes.ACC_ABSTRACT) != 0 || w.name.equals(k.name) || w.loader || !Linking.subtype(port.b, w.name, k.name)) {
                continue;
            }
            Map<String, String> bodies = code.bodies(w.name, x -> x);
            String text = bodies == null ? null : bodies.get(n.name + n.desc);
            if (text != null && parents.stream().anyMatch(p -> port.finalOverrides.copyable(code, w.name, n.name, n.desc, p))) {
                String same = text.replace(w.name + ".", "this.");
                count.merge(same, 1, Integer::sum);
                source.putIfAbsent(same, w.name);
            }
        }
        List<Map.Entry<String, Integer>> best = count.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(2).toList();
        if (best.isEmpty() || best.get(0).getValue() < 2 || best.size() > 1 && best.get(1).getValue().equals(best.get(0).getValue())) {
            // Nothing the class had hands its work to n and the game did not move anything into it, and one of the game's
            // own subclasses does nothing there (1.19.4's KeyBindsList.CategoryEntry.refreshEntry): the old release never
            // asked the class this, and doing nothing is what it did there before. Without that, doing nothing may drop
            // what the game now hands the class (a SubmitNodeCollector's submit calls).
            if (count.containsKey(EMPTY)) {
                if (JarPort.WHY) {
                    System.err.println("WHY D0 " + count.values() + " | " + rc.name + "." + n.name + n.desc);
                }
                return new FinalOverrides.Copy(null, n.name, n.desc, n.name, access);
            }
            JarPort.why(rc.name + "." + n.name + n.desc, "D no body most of the game's subclasses a mod class could carry write: " + count.values());
            return null;
        }
        if (JarPort.WHY) {
            System.err.println("WHY D+ " + source.get(best.get(0).getKey()) + " " + count.values() + " | " + rc.name + "." + n.name + n.desc);
        }
        return new FinalOverrides.Copy(source.get(best.get(0).getKey()), n.name, n.desc, n.name, access);
    }

    /** A method body that only returns, as GameCode.bodies writes it. */
    static final String EMPTY = Opcodes.RETURN + "\n";

    final Map<String, Optional<String>> tookWork = new HashMap<>();

    /**
     * A game method (class.name+desc, new names) that calls k's method n, was in the old release too (as it is, or
     * under the name the route map follows), and no longer makes a call its old code made: n may do that work now (1.17's
     * ProgramManager linked a shader's two programs itself; now it calls Shader.attachToProgram). Null when every
     * caller is new, or calls n beside all it called before.
     */
    String tookWork(String k, GameDiff.Meth n) {
        return tookWork.computeIfAbsent(k + "." + n.name + n.desc, key -> {
            for (GameDiff.Cls c : port.b.coded()) {
                for (GameDiff.Meth d : c.methods.values()) {
                    boolean calls = false;
                    Set<String> now = new HashSet<>();
                    for (GameDiff.Ref r : d.refs) {
                        if (r.kind() == 'm') {
                            now.add(r.name() + r.desc());
                            calls |= r.name().equals(n.name) && r.desc().equals(n.desc) && (Linking.subtype(port.b, r.owner(), k) || Linking.subtype(port.b, k, r.owner()));
                        }
                    }
                    GameDiff.Meth was = calls ? port.mixinRetargets.kept(d) : null;
                    if (was == null) {
                        continue;
                    }
                    for (GameDiff.Ref r : was.references()) {
                        if (r.kind() == 'm' && !r.name().startsWith("<")) {
                            String[] to = port.route.method(r.owner(), r.name(), r.desc());
                            if (!now.contains(to[1] + to[2])) {
                                return Optional.of(c.name + "." + d.name + d.desc);
                            }
                        }
                    }
                }
            }
            return Optional.<String>empty();
        }).orElse(null);
    }

    /**
     * The methods (new names) of a game class whose code calls name+desc on its own object; null when its code is not
     * the game's (a loader class).
     */
    Set<String> handsTo(GameCode code, String cls, String name, String desc) {
        String jar = code.toJar.getOrDefault(cls, cls);
        byte[] bytes = code.bytes.apply(jar);
        if (bytes == null) {
            return null;
        }
        Set<String> out = new HashSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                if (n.startsWith("<") || (access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) {
                    return null;
                }
                String key = code.names.method(jar, n, d) + code.names.desc(d);
                Object self = new Object();
                return new org.objectweb.asm.commons.AnalyzerAdapter(Opcodes.ASM9, jar, access, n, d, null) {
                    @Override
                    public void visitVarInsn(int opcode, int var) {
                        super.visitVarInsn(opcode, var);
                        if (opcode == Opcodes.ALOAD && var == 0 && stack != null) {
                            stack.set(stack.size() - 1, self);
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String o, String mn, String md, boolean itf) {
                        int depth = Arrays.stream(Type.getArgumentTypes(md)).mapToInt(Type::getSize).sum();
                        if (opcode != Opcodes.INVOKESTATIC && stack != null && stack.size() > depth && stack.get(stack.size() - 1 - depth) == self) {
                            String decl = o.startsWith("[") ? null : code.declarer(o, mn, md, false);
                            if (((decl == null ? mn : code.names.method(decl, mn, md)) + code.names.desc(md)).equals(name + desc)) {
                                out.add(key);
                            }
                        }
                        super.visitMethodInsn(opcode, o, mn, md, itf);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return out;
    }

    /**
     * A method of a mod class still overriding one the new release made final (1.21.11's AbstractButton.renderWidget) is
     * reported: the JVM refuses the class. One the old release had final too was overridden all the same (a mod's access
     * widener or the loader's transformer opens it), and that stays as it was.
     */
    void finals(JarPort.Mod mod, GameDiff.RawCls rc) {
        List<String> supers = port.overrides.gameParents(mod, rc);
        List<String> was = new ArrayList<>();
        for (GameDiff.RawCls c = rc; c != null; c = c.sup == null ? null : mod.classes.get(c.sup)) {
            if (c.sup != null && !mod.classes.containsKey(c.sup)) {
                was.add(c.sup);
            }
            c.ifaces.stream().filter(i -> !mod.classes.containsKey(i)).forEach(was::add);
        }
        for (GameDiff.RawMeth m : rc.methods.values()) {
            if (m.name.startsWith("<") || (m.flags & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0 || mod.renamedDecl.containsKey(rc.name + "." + m.name + m.desc)) {
                continue;
            }
            String mapped = port.desc(m.desc);
            boolean finalBefore = was.stream().map(s -> Exposure.declaring(port.a, s, 'm', m.name, m.desc))
                .anyMatch(k -> k != null && (k.methods.get(m.name + m.desc).flags & Opcodes.ACC_FINAL) != 0);
            for (String s : finalBefore ? List.<String>of() : supers) {
                GameDiff.Cls k = Exposure.declaring(port.b, s, 'm', m.name, mapped);
                GameDiff.Meth over = k == null ? null : k.methods.get(m.name + mapped);
                if (over != null && (over.flags & Opcodes.ACC_FINAL) != 0 && (over.flags & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0) {
                    mod.unlinked.add("final method overridden: " + Exposure.dotted(k.name) + "." + m.name + mapped + " (in " + Exposure.dotted(rc.name) + ")");
                    break;
                }
            }
        }
    }

    /**
     * The abstract methods (name + old descriptor) a mod class already left unwritten against the old release: its game
     * supertypes there had them abstract and neither the mod nor they wrote one. The class loaded all the same, so the
     * loader supplies them (NeoForge patches BakedModel.getTransforms() into a default the game's own jar does not have);
     * the port did not take them away.
     */
    Set<String> unwrittenBefore(JarPort.Mod mod, GameDiff.RawCls rc) {
        Set<String> written = new HashSet<>(), abstracts = new HashSet<>(), seen = new HashSet<>(), unread = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(rc.name));
        while (!todo.isEmpty()) {
            String c = todo.poll();
            if (!seen.add(c)) {
                continue;
            }
            GameDiff.RawCls own = mod.classes.get(c);
            if (own != null) {
                for (GameDiff.RawMeth m : own.methods.values()) {
                    ((m.flags & Opcodes.ACC_ABSTRACT) == 0 ? written : abstracts).add(m.name + m.desc);
                    if (UNMAPPED.matcher(m.name).matches()) {
                        unread.add(m.desc);
                    }
                }
                if (own.sup != null) {
                    todo.add(own.sup);
                }
                todo.addAll(own.ifaces);
                continue;
            }
            GameDiff.Cls k = port.a.classes.get(c);
            if (k == null) {
                continue;
            }
            for (GameDiff.Meth m : k.methods.values()) {
                ((m.flags & Opcodes.ACC_ABSTRACT) == 0 ? written : abstracts).add(m.name + m.desc);
            }
            todo.addAll(GameDiff.supertypes(k));
        }
        abstracts.removeAll(written);
        // A method the class writes under an id its names were never read through (m_6055_ for getName, in a jar built
        // for another release's mappings) is that one, unread: the class did not load before either, so nothing is excused.
        abstracts.removeIf(k -> unread.contains(k.substring(k.indexOf('('))));
        return abstracts;
    }

    static final Pattern UNMAPPED = Pattern.compile("(?:m_\\d+_|method_\\d+|func_\\d+_[a-z]?)");

    /**
     * Whether a game class among supers implements an abstract method through a generic parameter: a concrete method of
     * that name whose parameters are narrower (ShapedRecipe.matches(CraftingInput, Level) for Recipe.matches(RecipeInput,
     * Level)). javac wrote the bridge with the erased signature into that class, and the snapshot leaves bridges out.
     */
    boolean generic(List<GameDiff.Cls> supers, GameDiff.Meth abs) {
        Type[] want = Type.getArgumentTypes(abs.desc);
        for (GameDiff.Cls k : supers) {
            for (GameDiff.Meth m : k.methods.values()) {
                if (m.name.equals(abs.name) && (m.flags & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC)) == 0 && narrows(want, m.desc, abs.desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Parameters each the same as or narrower than want, and a result that serves as abs's. */
    boolean narrows(Type[] want, String desc, String abs) {
        Type[] got = Type.getArgumentTypes(desc);
        if (got.length != want.length || !port.links.usable(Type.getReturnType(desc), Type.getReturnType(abs))) {
            return false;
        }
        for (int i = 0; i < got.length; i++) {
            if (!port.links.usable(got[i], want[i])) {
                return false;
            }
        }
        return true;
    }
}

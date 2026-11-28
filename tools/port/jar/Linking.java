import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;

/** Whether a reference links in the new release, as the JVM links it: where the old release declares it, where it went, and whether the mod may reach it. */
final class Linking {

    final JarPort port;

    Linking(JarPort port) {
        this.port = port;
    }

    /** A member of a library class that both releases declare with the same type and access. */
    boolean unchangedLibrary(char kind, String owner, String name, String desc) {
        GameDiff.Cls old = port.a.classes.get(owner), now = port.b.classes.get(owner);
        if (old == null || now == null || !old.library) {
            return false;
        }
        if (kind == 'f' || kind == 'r') {
            GameDiff.Fld x = old.fields.get(name), y = now.fields.get(name);
            return x != null && y != null && x.desc.equals(y.desc) && x.flags == y.flags;
        }
        GameDiff.Meth x = old.methods.get(name + desc), y = now.methods.get(name + desc);
        return x != null && y != null && x.flags == y.flags;
    }

    /** Every id a jar and the jars nested in it answer to (its own and what it provides), each with the module that answers. */
    static Map<String, String> provided(byte[] jar) throws IOException {
        Map<String, String> out = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName();
                if (n.equals("fabric.mod.json")) {
                    Map<String, Object> meta;
                    try {
                        meta = Json.object(Json.parse(new String(z.readAllBytes(), StandardCharsets.UTF_8)));
                    } catch (RuntimeException unreadable) {
                        continue;
                    }
                    if (meta.get("id") instanceof String id) {
                        out.putIfAbsent(id, id);
                        for (Object alias : meta.get("provides") instanceof List<?> l ? l : List.of()) {
                            out.putIfAbsent(String.valueOf(alias), id);
                        }
                    }
                } else if (n.startsWith("META-INF/jars/") && n.endsWith(".jar")) {
                    provided(z.readAllBytes()).forEach(out::putIfAbsent);
                }
            }
        }
        return out;
    }

    final Map<String, String> successors = new HashMap<>(); // game superclass (old name) -> successor(), "" for none

    /**
     * What a class that extended the game's class s extends in the new release, decided the way the game decided for its
     * own subclasses of s: the class most of them extend now (Gui, AbstractWidget and the rest of GuiComponent's extend
     * Object in 1.20, though the route map follows GuiComponent's members to GuiGraphics). Null when s is still there under
     * its name, or when its subclasses extend what s became (a rename). A class still there whose stand-in is written as
     * the old class on top of the new one (extends it, with the old constructors) is that stand-in: a mod's subclass
     * keeps its old super(...) and the members the new class kept (1.20.1's ImageButton, drawn from a texture sheet,
     * which 1.20.2 draws from sprites with no constructor taking the sheet).
     */
    String successor(String s) {
        String found = successors.computeIfAbsent(s, k -> {
            GameDiff.Cls old = port.a.classes.get(k);
            String over = StandinClasses.STANDIN + k;
            GameDiff.Cls written = port.b.classes.get(over);
            if (old != null && written != null && k.equals(written.sup) && port.b.classes.containsKey(k) && StandinClasses.extendable(port.standIn.standins.get(over))) {
                return over;
            }
            if (old == null || port.b.classes.containsKey(k) && !becameInterface(k)) {
                return "";
            }
            // The old class written on the new release: a mod's subclasses extend it, though the route moves the class's
            // values elsewhere (1.19.4's GuiComponent, whose drawing went to GuiGraphics, an object the game passes in).
            String standin = StandinClasses.STANDIN + k;
            if (port.standIn.standins.containsKey(standin) && StandinClasses.extendable(port.standIn.standins.get(standin))) {
                return standin.equals(port.classMap.get(k)) ? "" : standin;
            }
            Map<String, Integer> votes = new TreeMap<>();
            for (GameDiff.Cls x : port.a.classes.under(k)) {
                GameDiff.Cls now = k.equals(x.sup) ? port.b.classes.get(port.cls(x.name)) : null;
                if (now != null && now.sup != null) {
                    votes.merge(now.sup, 1, Integer::sum);
                }
            }
            String moved = votes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            if (moved == null) {
                if (becameInterface(k)) {
                    return "java/lang/Object";
                }
                if (port.b.classes.containsKey(port.cls(k))) {
                    return "";
                }
                String up = old.sup == null ? null : port.cls(old.sup);
                moved = up != null && (port.b.classes.containsKey(up) || up.startsWith("java/")) ? up : "java/lang/Object";
            }
            return moved.equals(port.cls(k)) ? "" : moved;
        });
        return found.isEmpty() ? null : found;
    }

    /**
     * Whether a game class became an interface (1.17's BlockEntityRenderer): what extended it extends what the game's own
     * subclasses extend now, and implements it.
     */
    boolean becameInterface(String s) {
        GameDiff.Cls old = port.a.classes.get(s), now = port.b.classes.get(port.cls(s));
        return old != null && now != null && (old.flags & Opcodes.ACC_INTERFACE) == 0 && (now.flags & Opcodes.ACC_INTERFACE) != 0;
    }

    /** A mod class's superclass in the new release (new names): what replaces one the game removed, or its new name. */
    String superOf(GameDiff.RawCls own) {
        if (own.sup == null) {
            return null;
        }
        String replaced = successor(own.sup);
        return replaced != null ? replaced : port.cls(own.sup);
    }

    /**
     * A class the jar carries in one of its own nested jars, which the new release lacks and whose package the new release
     * no longer has (a module the platform stopped shipping, so the bundled copy is what runs): it is the mod's, not
     * gone. A class whose package the new release still has is left to the game: the platform's newer copy runs.
     */
    GameDiff.RawCls provided(String c) {
        GameDiff.RawCls own = port.tree.get(c);
        if (own == null || port.b.classes.containsKey(port.cls(c))) {
            return null;
        }
        return packagesB().contains(pkg(c)) ? null : own;
    }

    Set<String> packagesB;

    Set<String> packagesB() {
        if (packagesB == null) {
            packagesB = new HashSet<>();
            port.b.classes.keySet().forEach(k -> packagesB.add(pkg(k)));
        }
        return packagesB;
    }

    /** The class that declares a member reached through owner in the old release: the mod's own classes first, then the game. */
    JarPort.Decl declaringOld(JarPort.Mod mod, String owner, char kind, String name, String desc) {
        Deque<String> queue = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String c = queue.poll();
            if (!seen.add(c)) {
                continue;
            }
            GameDiff.RawCls own = mod.classes.get(c);
            if (own == null && provided(c) != null) {
                // A class another of the mod's jars carries: its own members are the mod's; what it inherits is left as
                // written, as for any library class this jar does not hold.
                GameDiff.RawCls carried = provided(c);
                return kind == 'f' ? carried.fields.containsKey(name) ? new JarPort.Decl(c, false) : null
                    : carried.methods.containsKey(name + desc) ? new JarPort.Decl(c, false) : null;
            }
            if (own != null) {
                if (kind == 'f' ? own.fields.containsKey(name) : own.methods.containsKey(name + desc)) {
                    return new JarPort.Decl(c, false);
                }
                if (own.sup != null) {
                    queue.add(own.sup);
                }
                queue.addAll(own.ifaces);
                continue;
            }
            GameDiff.Cls game = Exposure.declaring(port.a, c, kind, name, desc);
            if (game != null) {
                return new JarPort.Decl(game.name, true);
            }
        }
        return null;
    }

    /**
     * Whether the new release (with the mod's own classes, renamed) resolves the member from owner (new names), and one of
     * callers (the class the code runs in; a mixin's code runs in each of its targets) may use it, as the JVM checks.
     */
    boolean linksNew(JarPort.Mod mod, List<String> callers, String owner, char kind, String name, String desc) {
        if (!classOpen(mod, callers, owner)) {
            return false;
        }
        Deque<String> queue = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String c = queue.poll();
            if (!seen.add(c)) {
                continue;
            }
            GameDiff.RawCls own = mod.classes.get(c);
            if (own != null) {
                String[] renamed = mod.renamedDecl.get(c + "." + name + (kind == 'f' ? "" : desc));
                if (renamed == null) {
                    if (kind == 'f' ? own.fields.containsKey(name) : own.methods.containsKey(name + port.oldDesc(desc))) {
                        return true;
                    }
                }
                for (var r : mod.renamedDecl.entrySet()) {
                    if (r.getKey().startsWith(c + ".") && r.getValue()[0].equals(name) && (kind == 'f' || desc.equals(r.getValue()[1]))) {
                        return true;
                    }
                }
                if (kind == 'm' && name.startsWith("<")) {
                    return false; // a constructor is the class's own
                }
                if (own.sup != null) {
                    queue.add(port.cls(own.sup));
                }
                for (String i : own.ifaces) {
                    queue.add(port.cls(i));
                }
                continue;
            }
            GameDiff.Cls holder = Exposure.declaring(port.b, c, kind, name, kind == 'f' ? null : desc);
            if (holder != null) {
                int flags = kind == 'f' ? holder.fields.get(name).flags : holder.methods.get(name + desc).flags;
                return memberOpen(mod, callers, holder.name, kind == 'f' ? name : name + desc, flags);
            }
            if (c.startsWith("java/") && kind == 'm') {
                return true; // Object's methods reached through an interface
            }
        }
        return false;
    }

    /** A game class of the new release that callers may name: public, or in their package. */
    boolean classOpen(JarPort.Mod mod, List<String> callers, String owner) {
        GameDiff.Cls c = port.b.classes.get(owner);
        if (c == null || (c.flags & Opcodes.ACC_PUBLIC) != 0 || mod.widened.contains(owner)) {
            return true;
        }
        return callers.stream().anyMatch(caller -> pkg(caller).equals(pkg(owner)));
    }

    /** Whether one of callers may use a member with these flags declared in holder (new names), after the mod's access transformer. */
    boolean memberOpen(JarPort.Mod mod, List<String> callers, String holder, String member, int flags) {
        boolean method = member.contains("(");
        String every = holder + (method ? ".*()" : ".*");
        if ((flags & Opcodes.ACC_PUBLIC) != 0 || mod.widened.contains(holder + "." + member) || mod.widened.contains(every) && !member.startsWith("<init>")) {
            return true;
        }
        if (mod.widened.contains("~" + holder + "." + member) || mod.widened.contains("~" + every) && !member.startsWith("<init>")) {
            flags = flags & ~Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED;
        }
        for (String caller : callers) {
            if (caller.equals(holder)) {
                return true;
            }
            if ((flags & Opcodes.ACC_PRIVATE) != 0) {
                continue;
            }
            if (pkg(caller).equals(pkg(holder)) || (flags & Opcodes.ACC_PROTECTED) != 0 && subclass(mod, caller, holder)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A game member reached from owner (new names) that the callers may not use: when the loader reads the mod's access
     * transformer, the member, and its class when that is closed too, is opened there and counts as open from here on.
     * The loader's own classes are left as they are. Returns whether it was opened.
     */
    boolean opened(JarPort.Mod mod, List<String> callers, String owner, char kind, String name, String desc) {
        if (!mod.canWiden) {
            return false;
        }
        String c = owner;
        while (c != null && mod.classes.containsKey(c)) {
            GameDiff.RawCls own = mod.classes.get(c);
            if (kind == 'f' ? own.fields.containsKey(name) : own.methods.containsKey(name + port.oldDesc(desc))) {
                return false;
            }
            c = own.sup == null ? null : port.cls(own.sup);
        }
        GameDiff.Cls holder = c == null ? null : Exposure.declaring(port.b, c, kind, name, desc);
        if (holder == null || holder.loader) {
            return false;
        }
        String member = kind == 'f' ? name : name + desc;
        boolean classOk = classOpen(mod, callers, owner);
        boolean memberOk = memberOpen(mod, callers, holder.name, member, kind == 'f' ? holder.fields.get(name).flags : holder.methods.get(member).flags);
        GameDiff.Cls site = port.b.classes.get(owner);
        if (classOk && memberOk || !classOk && (site == null || site.loader)) {
            return false;
        }
        if (!classOk) {
            mod.grants.add("public " + Exposure.dotted(owner));
            mod.widened.add(owner);
        }
        if (!memberOk) {
            mod.grants.add("public " + Exposure.dotted(holder.name) + " " + member);
            mod.widened.add(holder.name + "." + member);
        }
        return true;
    }

    static String pkg(String internal) {
        int slash = internal.lastIndexOf('/');
        return slash < 0 ? "" : internal.substring(0, slash);
    }

    /** Whether c (new names) extends holder, through the mod's own classes and the new release. */
    boolean subclass(JarPort.Mod mod, String c, String holder) {
        for (String k = c; k != null; ) {
            if (k.equals(holder)) {
                return true;
            }
            GameDiff.RawCls own = mod.classes.get(k);
            if (own != null) {
                k = superOf(own);
                continue;
            }
            GameDiff.Cls game = port.b.classes.get(k);
            k = game == null ? null : game.sup;
        }
        return false;
    }

    /** Whether c (new names) is target or extends or implements it, through the mod's own classes and the new release. */
    boolean isA(JarPort.Mod mod, String c, String target) {
        Deque<String> queue = new ArrayDeque<>(List.of(c));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String k = queue.poll();
            if (k.equals(target)) {
                return true;
            }
            if (!seen.add(k)) {
                continue;
            }
            GameDiff.RawCls own = mod.classes.get(k);
            if (own != null) {
                if (own.sup != null) {
                    queue.add(superOf(own));
                }
                own.ifaces.forEach(i -> queue.add(port.cls(i)));
                continue;
            }
            GameDiff.Cls game = port.b.classes.get(k);
            if (game != null) {
                if (game.sup != null) {
                    queue.add(game.sup);
                }
                queue.addAll(game.ifaces);
                continue;
            }
            Class<?> jdk = jdk(k);
            if (jdk != null) {
                if (jdk.getSuperclass() != null) {
                    queue.add(Type.getInternalName(jdk.getSuperclass()));
                }
                for (Class<?> i : jdk.getInterfaces()) {
                    queue.add(Type.getInternalName(i));
                }
            }
        }
        return false;
    }

    /** {name, descriptor} of the one abstract method of a functional interface (the game's or the JDK's), or null. */
    String[] functional(String internal) {
        List<String[]> found = new ArrayList<>();
        GameDiff.Cls game = port.b.classes.get(internal);
        if (game != null) {
            Deque<GameDiff.Cls> queue = new ArrayDeque<>(List.of(game));
            Set<String> defaults = new HashSet<>();
            while (!queue.isEmpty()) {
                GameDiff.Cls k = queue.poll();
                for (GameDiff.Meth m : k.methods.values()) {
                    if ((m.flags & Opcodes.ACC_ABSTRACT) != 0 && !OBJECT.contains(m.name + m.desc) && !defaults.contains(m.name + m.desc)) {
                        found.add(new String[] { m.name, m.desc });
                    } else if ((m.flags & Opcodes.ACC_STATIC) == 0) {
                        defaults.add(m.name + m.desc);
                    }
                }
                k.ifaces.stream().map(port.b.classes::get).filter(Objects::nonNull).forEach(queue::add);
            }
        } else if (internal.startsWith("java/")) {
            try {
                Class<?> type = Class.forName(internal.replace('/', '.'), false, null);
                for (java.lang.reflect.Method m : type.getMethods()) {
                    if (java.lang.reflect.Modifier.isAbstract(m.getModifiers()) && !OBJECT.contains(m.getName() + Type.getMethodDescriptor(m))) {
                        found.add(new String[] { m.getName(), Type.getMethodDescriptor(m) });
                    }
                }
            } catch (ClassNotFoundException | LinkageError notJdk) {
                return null;
            }
        }
        return found.stream().map(f -> f[0] + f[1]).distinct().count() == 1 ? found.get(0) : null;
    }

    record FieldHit(String desc, boolean open) {}

    /** {owner, name, desc} in new names of the field a read now resolves to, open to the mod, whatever its type; or null. */
    String[] fieldNow(JarPort.Mod mod, List<String> callers, String siteOwner, String name, JarPort.Decl decl) {
        String[] moved = port.route.field(decl.owner(), name);
        for (String[] t : List.of(new String[] { port.cls(siteOwner), name }, new String[] { port.cls(siteOwner), moved[1] }, new String[] { moved[0], moved[1] })) {
            FieldHit found = fieldNew(mod, callers, t[0], t[1]);
            if (found != null && (found.open() || t[1].equals(moved[1]) && opened(mod, callers, t[0], 'f', t[1], null))) {
                return new String[] { t[0], t[1], found.desc() };
            }
            if (found != null) {
                return null;
            }
        }
        return null;
    }

    /**
     * The steps that give a value of type had (a descriptor, new names) from one of type got, as the game's own code gets
     * the value a holder of got holds (holder.value() cast to it, option.get() unboxed), agreed over the game's uses; or null.
     * Each step is "name()desc" (a call on got), a class to cast to, or a box's "xValue()X".
     */
    String[] unwrap(String got, String had) {
        GameCode code = port.code(port.b);
        if (code == null || !got.startsWith("L") || got.equals(had)) {
            return null;
        }
        String held = Type.getType(got).getInternalName();
        Map<String, Integer> votes = new HashMap<>();
        boolean jdk = false;
        for (var e : code.unwraps().getOrDefault(had, Map.of()).entrySet()) {
            if (subtype(port.b, held, e.getKey())) {
                e.getValue().forEach((steps, n) -> votes.merge(steps, n, Integer::sum));
                jdk |= e.getKey().startsWith("java/");
            }
        }
        String best = Retype.agreed(votes);
        if (best == null) {
            return null;
        }
        String[] steps = best.split(" ");
        GameDiff.Cls holder = Exposure.declaring(port.b, held, 'm', steps[0].substring(0, steps[0].indexOf('(')), steps[0].substring(steps[0].indexOf('(')));
        GameDiff.Meth m = holder == null ? null : holder.methods.get(steps[0]);
        return m != null && (m.flags & Opcodes.ACC_PUBLIC) != 0 && !m.isStatic() || holder == null && jdk ? steps : null;
    }

    /** The field the new release resolves from owner (new names), through the mod's own classes, or null. */
    FieldHit fieldNew(JarPort.Mod mod, List<String> callers, String owner, String name) {
        for (String c = owner; c != null; ) {
            GameDiff.RawCls own = mod.classes.get(c);
            if (own == null) {
                GameDiff.Cls holder = Exposure.declaring(port.b, c, 'f', name, null);
                if (holder == null) {
                    return null;
                }
                GameDiff.Fld f = holder.fields.get(name);
                return new FieldHit(f.desc, classOpen(mod, callers, owner) && memberOpen(mod, callers, holder.name, name, f.flags));
            }
            if (own.fields.containsKey(name)) {
                return new FieldHit(port.desc(own.fields.get(name)[0]), true);
            }
            c = own.sup == null ? null : port.cls(own.sup);
        }
        return null;
    }

    /**
     * {owner, name, desc} in new names that a reference to a game member becomes, or null when nothing in the new release
     * links. A new descriptor is taken only when the arguments already on the stack still fit it: the same types after class
     * renames, or parameters that became supertypes, with the same return or one that serves where the old one was used
     * (a subtype; a field read may also now hold a subtype). Anything else (an added, removed or narrowed
     * parameter, another return type) does not link here; dropped arguments are handled at the call site.
     */
    /**
     * t, or null where the game made the method static or an instance one: the old call's instruction no longer fits and
     * the JVM refuses it (IncompatibleClassChangeError: NeoForge 1.21.9's FMLLoader.getLoadingModList() is an instance
     * method of FMLLoader.getCurrent()), so the call is not linked; a replacement learned for it may still take its place.
     */
    String[] sameKind(JarPort.Decl decl, String name, String desc, String[] t) {
        GameDiff.Cls was = Exposure.declaring(port.a, decl.owner(), 'm', name, desc), now = Exposure.declaring(port.b, t[0], 'm', t[1], t[2]);
        GameDiff.Meth x = was == null ? null : was.methods.get(name + desc), y = now == null ? null : now.methods.get(t[1] + t[2]);
        return x != null && y != null && x.isStatic() != y.isStatic() ? null : t;
    }

    String[] target(JarPort.Mod mod, List<String> callers, String siteOwner, char kind, String name, String desc, JarPort.Decl decl) {
        String newSite = port.cls(siteOwner);
        if (unchangedLibrary(kind, decl.owner(), name, desc)) {
            // A library member both releases have as it was: whatever lets the mod use it (its own newer copy of the
            // library, say) still does; only what changed inside a library is the port's.
            return new String[] { newSite, name, port.desc(desc) };
        }
        if (kind == 'f' || kind == 'r') {
            String[] moved = port.route.field(decl.owner(), name);
            String fieldDesc = port.desc(desc);
            for (String[] t : List.of(new String[] { newSite, name }, new String[] { newSite, moved[1] }, new String[] { moved[0], moved[1] })) {
                FieldHit found = fieldNew(mod, callers, t[0], t[1]);
                // A field read ('r') may now hold a subtype (1.21.2: InteractionResult.SUCCESS is an InteractionResult$Success):
                // the value read still serves where the old type was used. A write must match exactly.
                boolean fits = found != null && (found.desc().equals(fieldDesc) || kind == 'r' && usable(Type.getType(found.desc()), Type.getType(fieldDesc)));
                if (fits && (found.open() || t[1].equals(moved[1]) && opened(mod, callers, t[0], 'f', t[1], null))) {
                    return new String[] { t[0], t[1], found.desc() };
                }
                if (found != null) {
                    return null; // there with another type, or closed to the mod: the access fails to link
                }
            }
            return null;
        }
        String[] moved = port.route.method(decl.owner(), name, desc);
        String mapped = port.desc(desc);
        if (linksNew(mod, callers, newSite, 'm', name, mapped)) {
            return sameKind(decl, name, desc, new String[] { newSite, name, mapped });
        }
        // The same method, closed to the mod now: opened through the mod's access transformer.
        if (moved[1].equals(name) && moved[2].equals(mapped) && opened(mod, callers, newSite, 'm', name, mapped)) {
            return sameKind(decl, name, desc, new String[] { newSite, name, mapped });
        }
        if (moved[2].equals(mapped) || widens(mapped, moved[2])) {
            if (linksNew(mod, callers, newSite, 'm', moved[1], moved[2])) {
                return sameKind(decl, name, desc, new String[] { newSite, moved[1], moved[2] });
            }
            if (linksNew(mod, callers, moved[0], 'm', moved[1], moved[2])) {
                return sameKind(decl, name, desc, moved);
            }
        }
        // Parameters that became supertypes (1.21.2: NonNullList -> List) with the same return: the old arguments still fit.
        GameDiff.Cls holder = port.b.classes.get(newSite);
        for (GameDiff.Cls c = holder; c != null; c = c.sup == null ? null : port.b.classes.get(c.sup)) {
            for (GameDiff.Meth m : c.methods.values()) {
                if (m.name.equals(moved[1]) && widens(mapped, m.desc) && linksNew(mod, callers, newSite, 'm', m.name, m.desc)) {
                    return sameKind(decl, name, desc, new String[] { newSite, m.name, m.desc });
                }
            }
        }
        return null;
    }

    /**
     * Whether a call written for from (new names) still works against to: the same number of arguments, each old one
     * acceptable where the new one is asked (a supertype now), and a result the caller can use as the old one (a subtype now).
     */
    boolean widens(String from, String to) {
        Type[] before = Type.getArgumentTypes(from), after = Type.getArgumentTypes(to);
        if (before.length != after.length || !usable(Type.getReturnType(to), Type.getReturnType(from))) {
            return false;
        }
        for (int i = 0; i < before.length; i++) {
            if (!usable(before[i], after[i])) {
                return false;
            }
        }
        return true;
    }

    /** A value of type value can be used where type wanted is asked, without a cast. */
    boolean usable(Type value, Type wanted) {
        if (value.equals(wanted)) {
            return true;
        }
        if (value.getSort() != Type.OBJECT || wanted.getSort() != Type.OBJECT) {
            return false;
        }
        return Exposure.assignable(port.b, value.getClassName(), wanted.getClassName());
    }

    /** Whether c is target or extends or implements it, through one release's classes and libraries. */
    static boolean isA(GameDiff.Snap s, String c, String target) {
        Deque<String> queue = new ArrayDeque<>(List.of(c));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String k = queue.poll();
            if (k.equals(target)) {
                return true;
            }
            GameDiff.Cls game = s.classes.getOrDefault(k, s.libraries.get(k));
            if (game != null && seen.add(k)) {
                queue.addAll(GameDiff.supertypes(game));
            }
        }
        return false;
    }

    /** Whether mod class c is sup or extends it through the mod's own classes. */
    static boolean subtype(JarPort.Mod mod, String c, String sup) {
        for (GameDiff.RawCls k = mod.classes.get(c); k != null; k = k.sup == null ? null : mod.classes.get(k.sup)) {
            if (k.name.equals(sup)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a call names a method the game declares, reached through mod classes that only inherit it. */
    boolean gameDeclared(JarPort.Mod mod, String owner, String name, String desc) {
        JarPort.Decl decl = owner.startsWith("[") ? null : declaringOld(mod, owner, 'm', name, desc);
        return decl != null && decl.game();
    }
    final Map<GameDiff.Snap, Map<String, List<GameDiff.Cls>>> declaringBy = new IdentityHashMap<>(); // release -> name+desc -> its classes declaring it

    /**
     * A release's classes that declare name+desc, in the order its classes map lists them, so a search over them answers as
     * one over every class did. Searching every class at each unlinked call was a third of the port's time (JFR, 150 mods).
     */
    List<GameDiff.Cls> declaring(GameDiff.Snap release, String nameDesc) {
        return declaringBy.computeIfAbsent(release, r -> declaringIn(r.classes.values())).getOrDefault(nameDesc, List.of());
    }

    /**
     * Those of them that can be under (it, or a class that extends it): the release's own classes alone where under is one
     * of them (GameDiff.Classes.under), so asking which game classes override a game method reads no library. Listing
     * every library's classes read 27,000 of them for a mod that names a few dozen.
     */
    List<GameDiff.Cls> declaring(GameDiff.Snap release, String nameDesc, String under) {
        if (!release.classes.own(under)) {
            return declaring(release, nameDesc);
        }
        return declaringOwnBy.computeIfAbsent(release, r -> declaringIn(r.classes.own())).getOrDefault(nameDesc, List.of());
    }

    /** Those of them whose names start with packages: the release's own classes alone where no library has a class there. */
    List<GameDiff.Cls> declaringIn(GameDiff.Snap release, String nameDesc, String packages) {
        if (libraryIn.computeIfAbsent(release, r -> new HashMap<>()).computeIfAbsent(packages, release.classes::libraryIn)) {
            return declaring(release, nameDesc);
        }
        return declaringOwnBy.computeIfAbsent(release, r -> declaringIn(r.classes.own())).getOrDefault(nameDesc, List.of());
    }

    final Map<GameDiff.Snap, Map<String, List<GameDiff.Cls>>> declaringOwnBy = new IdentityHashMap<>();
    final Map<GameDiff.Snap, Map<String, Boolean>> libraryIn = new IdentityHashMap<>();

    static Map<String, List<GameDiff.Cls>> declaringIn(Collection<GameDiff.Cls> classes) {
        Map<String, List<GameDiff.Cls>> index = new HashMap<>();
        for (GameDiff.Cls c : classes) {
            for (String m : c.methods.keySet()) {
                index.computeIfAbsent(m, k -> new ArrayList<>()).add(c);
            }
        }
        return index;
    }

    static final Set<String> OBJECT = Set.of("equals(Ljava/lang/Object;)Z", "hashCode()I", "toString()Ljava/lang/String;");

    /** Whether class c is sup or extends or implements it in one release. */
    static boolean subtype(GameDiff.Snap s, String c, String sup) {
        Deque<String> todo = new ArrayDeque<>(List.of(c));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            String k = todo.poll();
            if (k.equals(sup)) {
                return true;
            }
            GameDiff.Cls held = seen.add(k) ? s.classes.get(k) : null;
            if (held != null) {
                if (held.sup != null) {
                    todo.add(held.sup);
                }
                todo.addAll(held.ifaces);
            }
        }
        return false;
    }

    /** A JDK class by internal name, or null. Only java.* is looked up: the game's own classes come from the snapshot. */
    static Class<?> jdk(String internal) {
        if (!internal.startsWith("java/")) {
            return null;
        }
        try {
            return Class.forName(internal.replace('/', '.'), false, null);
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }

    /** Whether a type exists for code running on the new release: the new release or the mod has it, or the old release never did (a library's). */
    boolean present(JarPort.Mod mod, Type t) {
        Type e = t.getSort() == Type.ARRAY ? t.getElementType() : t;
        if (e.getSort() != Type.OBJECT) {
            return true;
        }
        String n = e.getInternalName();
        return port.b.classes.containsKey(n) || mod.classes.containsKey(n) || !port.a.classes.containsKey(n);
    }

    boolean isInterface(JarPort.Mod mod, String internal) {
        GameDiff.Cls game = port.b.classes.get(internal);
        if (game != null) {
            return (game.flags & Opcodes.ACC_INTERFACE) != 0;
        }
        GameDiff.RawCls own = mod.classes.get(internal);
        if (own != null) {
            return (own.flags & Opcodes.ACC_INTERFACE) != 0;
        }
        Class<?> k = jdk(internal);
        return k != null && k.isInterface();
    }

    /** A method or constructor as a call names it (owner is the class the instruction names), and whether the caller may use it. */
    record Member(String owner, String name, String desc, boolean isStatic, boolean itf, boolean open) {}

    /** The pieces of an invokedynamic making a functional interface wanted from a static method owner::name, or null. */
    Object[] lambda(JarPort.Mod mod, List<String> callers, String owner, String name, Type wanted) {
        String[] sam = wanted.getSort() == Type.OBJECT ? functional(wanted.getInternalName()) : null;
        GameDiff.Cls holder = port.b.classes.get(owner);
        if (sam == null || holder == null) {
            return null;
        }
        int arity = Type.getArgumentTypes(sam[1]).length;
        List<GameDiff.Meth> impls = holder.methods.values().stream()
            .filter(m -> m.name.equals(name) && (m.flags & Opcodes.ACC_STATIC) != 0 && Type.getArgumentTypes(m.desc).length == arity)
            .toList();
        if (impls.size() != 1 || !memberOpen(mod, callers, owner, impls.get(0).name + impls.get(0).desc, impls.get(0).flags)) {
            return null;
        }
        GameDiff.Meth impl = impls.get(0);
        Handle factory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
        Handle target = new Handle(Opcodes.H_INVOKESTATIC, port.inverse.getOrDefault(owner, owner), impl.name, port.oldDesc(impl.desc), (holder.flags & Opcodes.ACC_INTERFACE) != 0);
        return new Object[] { sam[0], "()" + port.oldDesc(wanted.getDescriptor()), factory, new Object[] { Type.getType(sam[1]), target, Type.getType(port.oldDesc(impl.desc)) } };
    }
}

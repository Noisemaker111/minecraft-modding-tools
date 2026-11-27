import java.io.*;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;

/** Stand-in classes for game classes an update rewrote (knowledge/standins): compiled, bundled into the ported jar, and opened to it. */
final class StandinClasses {

    final JarPort port;

    StandinClasses(JarPort port) {
        this.port = port;
    }

    // ---- Stand-ins: the old API written on top of the new game, bundled into the ported jar.

    /** The package every stand-in is written in; the bundled copy is moved under one of the jar's own (bundle). */
    static final String STANDIN = "mmt/standin/";

    final Map<String, byte[]> standins = new HashMap<>(); // stand-in class (internal, unrelocated) -> its bytes

    /**
     * Reads the stand-ins written for this update (knowledge/standins/FROM-TO/): Java sources, one class per old game
     * class, in the package mmt.standin + the old class's package. Where the game's own code has no answer for an old
     * member (a system the update rewrote), a stand-in implements it on the new game, written from both releases' code.
     *
     * Each is compiled here against the new release as the porter reads it (stubs made from the snapshot, so no game jar
     * in Mojang names is needed), and its public static methods become port rules for the old class's members:
     *   R name(Owner self, A...)   the old instance method Owner.name(A...)R      (a field: T name(Owner self), void name(Owner, T))
     *   R name(A...)               the old static method, or static field read
     *   Owner init(A...)           the old constructor Owner(A...)
     * A stand-in whose old class the new release no longer has stands in for the class itself: references to the class
     * are moved to it. A port that uses a stand-in carries its classes (bundle), so a later update ports them as mod code.
     */
    void standins(List<Path> dirs) throws IOException {
        Map<String, byte[]> compiled = compileStandins(dirs, port.b);
        compiled.replaceAll((name, bytes) -> openWidened(bytes, port.a.classes.get(name.substring(STANDIN.length()))));
        standins.putAll(compiled);
        for (var e : compiled.entrySet()) {
            GameDiff.Cls c = standinCls(e.getValue());
            port.b.classes.put(c.name, c);
        }
        for (var e : compiled.entrySet()) {
            String name = e.getKey();
            String old = name.substring(STANDIN.length());
            if (port.a.classes.containsKey(old) && !port.b.classes.containsKey(port.classMap.getOrDefault(old, old))) {
                // The class is gone with nothing the route moves it to. A stand-in written as static methods on one
                // new type, which the game's own classes that were the old one now are, says the class became that
                // type (1.21.11's PermissionSource, on PermissionSetSupplier); otherwise the stand-in is the class.
                // Settled before any member is matched, so a stand-in method taking another gone class matches in any order.
                String became = becameType(old, e.getValue());
                String to = became != null ? became : name;
                port.classMap.put(old, to);
                port.inverse.putIfAbsent(to, old);
            }
        }
        for (var e : compiled.entrySet()) {
            String name = e.getKey();
            if (name.contains("$") && compiled.containsKey(name.substring(0, name.lastIndexOf('$'))) && !port.a.classes.containsKey(name.substring(STANDIN.length()))) {
                continue; // a stand-in's own nested or lambda class
            }
            String old = name.substring(STANDIN.length());
            GameDiff.Cls was = port.a.classes.get(old);
            if (was == null) {
                continue;
            }
            boolean gone = name.equals(port.classMap.get(old));
            ClassModel cm = ClassFile.of().parse(e.getValue());
            for (MethodModel mm : cm.methods()) {
                int flags = mm.flags().flagsMask();
                String n = mm.methodName().stringValue();
                if ((flags & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) != (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC) || (flags & Opcodes.ACC_SYNTHETIC) != 0) {
                    continue;
                }
                if (n.equals("isInstance") && mm.methodType().stringValue().equals("(Ljava/lang/Object;)Z")) {
                    // x instanceof Old: what the old class meant, asked of the new game (an item that was its own class is one item now).
                    port.fixes.computeIfAbsent(old, k -> new ArrayList<>()).add(0, new JarPort.Fix("standin " + Exposure.dotted(old) + " instanceof", Pattern.compile("instanceof"), null,
                        Exposure.dotted(name) + ".isInstance($1)"));
                    continue;
                }
                if (gone || conversion(name, mm.methodType().stringValue(), n)) {
                    continue; // the class's own members, which references reach through the class map; from(T), an override's argument
                }
                // Matched in the new release's names: each old member's descriptor carried forward against the stand-in's.
                // (Carrying the stand-in's back is ambiguous where an old class now goes by a name another old class had.)
                String desc = mm.methodType().stringValue();
                Type[] params = Type.getArgumentTypes(desc);
                Type ret = Type.getReturnType(desc);
                String self = params.length > 0 && params[0].getSort() == Type.OBJECT ? params[0].getInternalName() : null;
                String now = port.classMap.getOrDefault(old, old);
                boolean onOld = self != null && (self.equals(now) || port.links.isA(new JarPort.Mod(), now, self));
                String rest = onOld ? "(" + String.join("", Arrays.stream(params).skip(1).map(Type::getDescriptor).toList()) + ")" : null;
                String all = "(" + String.join("", Arrays.stream(params).map(Type::getDescriptor).toList()) + ")";
                String target = Exposure.dotted(name) + "." + n;
                StringBuilder args = new StringBuilder();
                String member = null, matched = null, found;
                boolean receiver = false;
                if (n.equals("init") && ret.getSort() == Type.OBJECT && (found = oldMember(old, "<init>", false, all + "V")) != null) {
                    member = "<init>";
                    matched = found.substring(0, found.indexOf(')') + 1);
                } else if (onOld && (found = oldMember(old, n, false, rest + ret.getDescriptor())) != null) {
                    member = n;
                    matched = found.substring(0, found.indexOf(')') + 1);
                    receiver = true;
                } else if ((found = oldMember(old, n, true, desc)) != null) {
                    member = n;
                    matched = found.substring(0, found.indexOf(')') + 1);
                } else if (onOld && params.length == 1 && oldField(was, n, ret.getDescriptor())) {
                    member = n;
                    receiver = true;
                } else if (onOld && params.length == 2 && ret.getSort() == Type.VOID && oldField(was, n, params[1].getDescriptor())) {
                    member = n + "=";
                    receiver = true;
                } else if (params.length == 0 && oldField(was, n, ret.getDescriptor())) {
                    member = n;
                } else if (params.length == 1 && ret.getSort() == Type.VOID && oldField(was, n, params[0].getDescriptor())) {
                    member = n + "=";
                }
                if (member == null) {
                    List<String> carried = new ArrayList<>();
                    for (GameDiff.Meth m : was.methods.values()) {
                        if (m.name.equals(n)) {
                            carried.add(port.desc(m.desc));
                        }
                    }
                    throw new IllegalArgumentException("stand-in " + Exposure.dotted(name) + "." + n + desc + " answers no member of " + Exposure.dotted(old) + " in " + port.a.version
                        + (carried.isEmpty() ? "" : " (its " + n + " carried into " + port.b.version + "'s names: " + String.join(", ", carried) + ")"));
                }
                for (int i = receiver ? 0 : 1; i < (receiver ? params.length : params.length + 1); i++) {
                    args.append(args.isEmpty() ? "" : ", ").append('$').append(i);
                }
                JarPort.Fix answer = new JarPort.Fix("standin " + Exposure.dotted(old) + "." + member, Pattern.compile(Pattern.quote(member)), matched, target + "(" + args + ")");
                port.fixes.computeIfAbsent(old, k -> new ArrayList<>()).add(0, answer);
                if (receiver && matched != null && !member.equals("<init>")) {
                    port.superFixes.computeIfAbsent(old, k -> new ArrayList<>()).add(0, answer);
                }
            }
        }
    }

    /**
     * Whether a stand-in's public static method is from(T): the stand-in made from a value of a type of the new release,
     * which is what an override of a game method that now takes T is given where the old method took the stand-in's class
     * (1.20's GuiGraphics, from the PoseStack 1.19.4's Screen.render passes). It answers no old member.
     */
    static boolean conversion(String standin, String desc, String name) {
        Type[] params = Type.getArgumentTypes(desc);
        return name.equals("from") && params.length == 1 && params[0].getSort() == Type.OBJECT && Type.getReturnType(desc).getInternalName().equals(standin);
    }

    /** The from(T) of the stand-in for a class (old name), for a value of type got (new names), as a conversion step; or null. */
    String from(String old, String got) {
        byte[] bytes = standins.get(STANDIN + old);
        if (bytes == null) {
            return null;
        }
        for (MethodModel mm : ClassFile.of().parse(bytes).methods()) {
            String desc = mm.methodType().stringValue();
            if ((mm.flags().flagsMask() & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
                && conversion(STANDIN + old, desc, mm.methodName().stringValue()) && Type.getArgumentTypes(desc)[0].getDescriptor().equals(got)) {
                return "s:" + STANDIN + old + ".from" + desc;
            }
        }
        return null;
    }

    /**
     * The new type a gone class's stand-in says the class became, or null: the stand-in is a holder (final, no constructor
     * a mod can call, no public instance method) whose every public static method but isInstance takes that type first,
     * and every game class that was the old class in the old release and is still there is that type in the new one (at
     * least one). A holder on a type the game's classes did not move to answers no reference to the class itself.
     * Its init methods (the old constructors) return that type instead: where no game class was the old one, a holder
     * whose constructors make the new type says the class became it (1.20.1's ClientboundCustomPayloadPacket, a final
     * class 1.20.2 replaced with the one in network.protocol.common).
     */
    String becameType(String old, byte[] bytes) {
        ClassModel cm = ClassFile.of().parse(bytes);
        if ((cm.flags().flagsMask() & Opcodes.ACC_FINAL) == 0) {
            return null;
        }
        String type = null;
        boolean makes = false;
        for (MethodModel mm : cm.methods()) {
            int flags = mm.flags().flagsMask();
            String n = mm.methodName().stringValue();
            if ((flags & Opcodes.ACC_PUBLIC) == 0 && !n.equals("<init>") || (flags & Opcodes.ACC_SYNTHETIC) != 0 || n.equals("<clinit>")) {
                continue;
            }
            if (n.equals("<init>")) {
                if ((flags & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0) {
                    return null;
                }
                continue;
            }
            if ((flags & Opcodes.ACC_STATIC) == 0) {
                return null;
            }
            if (n.equals("isInstance")) {
                continue;
            }
            Type ret = Type.getReturnType(mm.methodType().stringValue());
            if (n.equals("init") && ret.getSort() == Type.OBJECT) {
                if (type != null && !type.equals(ret.getInternalName())) {
                    return null;
                }
                type = ret.getInternalName();
                makes = true;
                continue;
            }
            Type[] params = Type.getArgumentTypes(mm.methodType().stringValue());
            if (params.length == 0 || params[0].getSort() != Type.OBJECT || type != null && !type.equals(params[0].getInternalName())) {
                return null;
            }
            type = params[0].getInternalName();
        }
        if (type == null || type.startsWith(STANDIN) || !port.b.classes.containsKey(type)) {
            return null;
        }
        boolean moved = makes;
        for (GameDiff.Cls c : port.a.classes.own()) {
            if (c.library || c.name.equals(old) || !port.b.classes.containsKey(c.name) || !Linking.isA(port.a, c.name, old)) {
                continue;
            }
            if (!Linking.isA(port.b, c.name, type)) {
                return null;
            }
            moved = true;
        }
        return moved ? type : null;
    }

    /**
     * The game calls ("owner.name desc", new names) a stand-in's answer for an old method (old names) can make, following
     * the stand-in's calls into its own classes; empty when no stand-in answers it. The answer is the class's own method
     * where the stand-in is the class, else its static method taking the old receiver first.
     */
    Set<String> reaches(String owner, String name, String desc) {
        byte[] bytes = standins.get(STANDIN + owner);
        if (bytes == null) {
            return Set.of();
        }
        String now = port.desc(desc);
        String args = now.substring(1, now.indexOf(')'));
        Set<String> out = new TreeSet<>(), seen = new HashSet<>();
        for (MethodModel mm : ClassFile.of().parse(bytes).methods()) {
            String d = mm.methodType().stringValue();
            boolean isStatic = (mm.flags().flagsMask() & Opcodes.ACC_STATIC) != 0;
            Type[] params = Type.getArgumentTypes(d);
            boolean onSelf = isStatic && params.length > 0 && d.substring(1 + params[0].getDescriptor().length()).equals(args + ")" + Type.getReturnType(now).getDescriptor());
            if (mm.methodName().stringValue().equals(name) && (d.equals(now) || onSelf)) {
                calls(STANDIN + owner, name, d, out, seen);
            }
        }
        return out;
    }

    private void calls(String cls, String name, String desc, Set<String> out, Set<String> seen) {
        byte[] bytes = standins.get(cls);
        if (bytes == null || !seen.add(cls + "." + name + desc)) {
            return;
        }
        List<String[]> made = new ArrayList<>();
        boolean[] declared = { false };
        String[] sup = { null };
        new org.objectweb.asm.ClassReader(bytes).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String n, String signature, String superName, String[] interfaces) {
                sup[0] = superName;
            }

            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                if (!n.equals(name) || !d.equals(desc)) {
                    return null;
                }
                declared[0] = true;
                return new org.objectweb.asm.MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String o, String mn, String md, boolean itf) {
                        made.add(new String[] { o, mn, md });
                    }
                };
            }
        }, org.objectweb.asm.ClassReader.SKIP_FRAMES);
        // A stand-in class extending a game class (GuiGraphics' Tooltips, a Screen) calls what it inherits on itself.
        GameDiff.Cls inherited = declared[0] || sup[0] == null || !port.b.classes.containsKey(sup[0]) ? null : Exposure.declaring(port.b, sup[0], 'm', name, desc);
        if (inherited != null) {
            out.add(inherited.name + "." + name + desc);
        }
        for (String[] m : made) {
            if (m[0].startsWith(STANDIN)) {
                calls(m[0], m[1], m[2], out, seen);
            } else if (port.b.classes.containsKey(m[0])) {
                out.add(m[0] + "." + m[1] + m[2]);
            }
        }
    }

    /**
     * The class map as a port that leaves the route out for every class the older release lacks by its own name and a
     * stand-in is written for (PortVerifier.fitting): a stand-in's methods name those stand-ins (1.20's
     * SignBlockEntity.getFrontText gives the SignText stand-in, though the route reads SignText as SignBlockEntity).
     */
    Map<String, String> standing() {
        Map<String, String> out = new HashMap<>(port.classMap);
        for (String name : standins.keySet()) {
            String old = name.substring(STANDIN.length());
            if (!old.contains("$") && port.a.classes.containsKey(old) && !port.b.classes.containsKey(old)) {
                out.put(old, name);
            }
        }
        return out;
    }

    /**
     * The old release's descriptor of a method of an old class (or one it inherits) whose descriptor, carried into the new
     * release's names, is newDesc; null if none. A constructor is looked up on the class itself.
     */
    String oldMember(String old, String name, boolean isStatic, String newDesc) {
        for (GameDiff.Cls c = port.a.classes.get(old); c != null; c = name.equals("<init>") || c.sup == null ? null : port.a.classes.get(c.sup)) {
            for (GameDiff.Meth m : c.methods.values()) {
                if (m.name.equals(name) && ((m.flags & Opcodes.ACC_STATIC) != 0) == isStatic && (port.desc(m.desc).equals(newDesc) || JarPort.mapDesc(m.desc, standing()).equals(newDesc))) {
                    return m.desc;
                }
            }
        }
        return null;
    }

    /** Whether the old class has a field of that name whose type, carried into the new release's names, is newType. */
    boolean oldField(GameDiff.Cls was, String name, String newType) {
        GameDiff.Fld f = was.fields.get(name);
        // Read as the route reads its type, or as the stand-ins do (1.20's AbstractSignEditScreen.text, a SignText).
        return f != null && (port.desc(f.desc).equals(newType) || JarPort.mapDesc(f.desc, standing()).equals(newType));
    }

    /**
     * Whether a stand-in is a class a mod can extend: not final, not an interface, with a constructor a subclass can call
     * (the old class written on the new release, as 1.19.4's abstract GuiComponent). A mod's subclasses of the old class
     * extend it (successor), wherever the route moves the old class's values; a holder of static methods (a final class
     * with a private constructor) answers members only.
     */
    static boolean extendable(byte[] bytes) {
        ClassModel cm = ClassFile.of().parse(bytes);
        int flags = cm.flags().flagsMask();
        return (flags & (Opcodes.ACC_FINAL | Opcodes.ACC_INTERFACE)) == 0 && (flags & Opcodes.ACC_PUBLIC) != 0
            && cm.methods().stream().anyMatch(m -> m.methodName().stringValue().equals("<init>") && (m.flags().flagsMask() & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0);
    }

    /** A compiled stand-in as the link check reads it: declarations, marked as not the game's so nothing in it is ported. */
    static GameDiff.Cls standinCls(byte[] bytes) {
        ClassModel cm = ClassFile.of().parse(bytes);
        List<String> ifs = new ArrayList<>();
        cm.interfaces().forEach(i -> ifs.add(i.asInternalName()));
        GameDiff.Cls c = new GameDiff.Cls(cm.thisClass().asInternalName(), cm.flags().flagsMask(), cm.superclass().map(x -> x.asInternalName()).orElse(null), ifs, true);
        c.library = true;
        cm.fields().forEach(f -> c.fields.put(f.fieldName().stringValue(), new GameDiff.Fld(c, f.fieldName().stringValue(), f.fieldType().stringValue(), f.flags().flagsMask())));
        for (MethodModel mm : cm.methods()) {
            GameDiff.Meth m = new GameDiff.Meth(c, mm.methodName().stringValue(), mm.methodType().stringValue(), mm.flags().flagsMask());
            c.methods.put(m.name + m.desc, m);
        }
        return c;
    }

    /**
     * Stand-in sources compiled by this JDK's own compiler, with the options that say for which Java and against what:
     * null when they compiled, else what the compiler said. what names them in the message a Java with no compiler gets.
     */
    static String compile(String what, List<String> options, List<Path> sources) throws IOException {
        javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new IOException(what + " need a JDK's compiler (javax.tools), and this Java has none");
        }
        List<String> command = new ArrayList<>(options);
        sources.forEach(f -> command.add(f.toString()));
        StringWriter errors = new StringWriter();
        int exit = javac.run(null, new PrintStream(OutputStream.nullOutputStream()), new PrintStream(new OutputStream() {
            public void write(int x) {
                errors.write(x);
            }
        }, true), command.toArray(String[]::new));
        return exit == 0 ? null : errors.toString();
    }

    /** The stand-in sources under dir compiled against stubs of the release (its classes' declarations, no code). */
    static Map<String, byte[]> compileStandins(List<Path> dirs, GameDiff.Snap release) throws IOException {
        List<Path> sources = new ArrayList<>();
        for (Path dir : dirs) {
            try (var walk = Files.walk(dir)) {
                walk.filter(f -> f.toString().endsWith(".java")).sorted().forEach(sources::add);
            }
        }
        Path dir = dirs.get(0).getParent();
        // Kept by what they are compiled from (the sources and the release read), so the processes of one sweep compile once.
        java.security.MessageDigest sha;
        try {
            sha = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        sha.update((release.version + "|" + release.loaderVersion + "|" + release.classes.size() + "|java " + standinJava(release.version)).getBytes(StandardCharsets.UTF_8));
        for (Path f : sources) {
            sha.update(dir.relativize(f).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
            sha.update(Files.readAllBytes(f));
        }
        Path kept = Path.of(System.getProperty("user.home"), ".mmt", "jar-port", "standins", HexFormat.of().formatHex(sha.digest()).substring(0, 24));
        if (Files.isDirectory(kept)) {
            return readClasses(kept);
        }
        Path work = Files.createTempDirectory("mmt-standins");
        try {
            Path stubs = work.resolve("stubs.jar"), out = Files.createDirectories(work.resolve("classes"));
            writeStubs(release, stubs);
            String errors = compile("stand-ins", List.of("--release", String.valueOf(standinJava(release.version)), "-proc:none", "-nowarn", "-encoding", "UTF-8", "-cp", stubs.toString(), "-d", out.toString()), sources);
            if (errors != null) {
                throw new IOException("stand-ins in " + dir + " do not compile against " + release.version + ":\n" + errors);
            }
            Map<String, byte[]> classes = readClasses(out);
            for (String name : classes.keySet()) {
                if (!name.startsWith(STANDIN)) {
                    throw new IOException("stand-in " + name + " is not in the package " + STANDIN.replace('/', '.'));
                }
            }
            Files.createDirectories(kept.getParent());
            try {
                Files.move(out, kept);
            } catch (IOException another) {
                // Another process kept the same classes first.
            }
            return classes;
        } finally {
            try (var walk = Files.walk(work)) {
                walk.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        }
    }

    /**
     * The Java a stand-in is compiled for: the one the new release runs on (Java 8 before 1.17, 16 for 1.17, 17 from 1.18,
     * 21 from 1.20.5, as Mojang's version files name), at most 21, which every later release runs too.
     */
    static int standinJava(String release) {
        if (JarPort.compareVersions(release, "1.20.5") >= 0) {
            return 21;
        }
        if (JarPort.compareVersions(release, "1.18") >= 0) {
            return 17;
        }
        return JarPort.compareVersions(release, "1.17") >= 0 ? 16 : 8;
    }

    static Map<String, byte[]> readClasses(Path root) throws IOException {
        Map<String, byte[]> classes = new TreeMap<>();
        try (var walk = Files.walk(root)) {
            for (Path f : walk.filter(x -> x.toString().endsWith(".class")).toList()) {
                String name = root.relativize(f).toString().replace('\\', '/');
                classes.put(name.substring(0, name.length() - 6), Files.readAllBytes(f));
            }
        }
        return classes;
    }

    /**
     * A jar javac can compile against in place of the release: every class's declarations (flags, supertypes, fields,
     * methods) with no code and no generic signatures, and the nesting javac needs to read Outer.Inner.
     */
    static void writeStubs(GameDiff.Snap release, Path jar) throws IOException {
        Map<String, List<String>> nestedIn = new HashMap<>();
        for (String name : release.classes.keySet()) {
            int at = name.lastIndexOf('$');
            if (at > 0 && release.classes.containsKey(name.substring(0, at)) && !Character.isDigit(name.charAt(at + 1))) {
                nestedIn.computeIfAbsent(name.substring(0, at), k -> new ArrayList<>()).add(name);
            }
        }
        // Through a buffer, and stored: a zip writes each entry's header a few bytes at a time, and straight to the file the
        // 37,000 stubs took 14 s to write for a compile of 0.7 s; javac reads the jar once and it is deleted.
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(jar), 1 << 16))) {
            out.setLevel(java.util.zip.Deflater.NO_COMPRESSION);
            for (GameDiff.Cls c : release.classes.values()) {
                if (c.name.startsWith("java/") || c.name.startsWith("javax/") || c.name.startsWith("jdk/") || c.name.startsWith("sun/")) {
                    continue;
                }
                ClassWriter w = new ClassWriter(0);
                // Everything is public here: a stand-in may use what the game keeps closed, which the port then opens
                // through the mod's access transformer (bundle).
                int flags = c.flags & ~(Opcodes.ACC_SUPER | 0x10000 | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED) | Opcodes.ACC_PUBLIC;
                w.visit(Opcodes.V21, flags, c.name, null, c.sup == null && !c.name.equals("java/lang/Object") ? "java/lang/Object" : c.sup,
                    c.ifaces.toArray(String[]::new));
                List<String> inner = new ArrayList<>(nestedIn.getOrDefault(c.name, List.of()));
                int at = c.name.lastIndexOf('$');
                if (at > 0 && nestedIn.getOrDefault(c.name.substring(0, at), List.of()).contains(c.name)) {
                    inner.add(c.name);
                }
                for (String n : inner) {
                    GameDiff.Cls ic = release.classes.get(n);
                    String outer = n.substring(0, n.lastIndexOf('$'));
                    boolean takesOuter = ic.methods.values().stream().anyMatch(m -> m.name.equals("<init>") && m.desc.startsWith("(L" + outer + ";"));
                    int innerFlags = ic.flags & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_FINAL | Opcodes.ACC_INTERFACE
                        | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ENUM | Opcodes.ACC_ANNOTATION);
                    w.visitInnerClass(n, outer, n.substring(n.lastIndexOf('$') + 1), open(innerFlags) | (takesOuter && (ic.flags & Opcodes.ACC_INTERFACE) == 0 ? 0 : Opcodes.ACC_STATIC));
                }
                for (GameDiff.Fld f : c.fields.values()) {
                    w.visitField(open(f.flags), f.name, f.desc, null, null).visitEnd();
                }
                for (GameDiff.Meth m : c.methods.values()) {
                    w.visitMethod(open(m.flags), m.name, m.desc, null, null).visitEnd();
                }
                w.visitEnd();
                out.putNextEntry(new ZipEntry(c.name + ".class"));
                out.write(w.toByteArray());
                out.closeEntry();
            }
        }
    }

    /**
     * A stand-in with every member its old class declares opened: mods widen the game's closed members (JEI calls 1.20's
     * private GuiGraphics.renderTooltipInternal through its access widener), and a call moved to the stand-in finds it
     * still private there, which the JVM refuses. The stand-in mirrors the old class's access, so it is opened here, once.
     */
    static byte[] openWidened(byte[] bytes, GameDiff.Cls old) {
        if (old == null) {
            return bytes;
        }
        ClassWriter w = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, w) {
            @Override
            public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                return super.visitField(old.fields.containsKey(name) ? open(access) : access, name, desc, sig, value);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                // Not a constructor: a holder of static methods keeps its own closed, which says it answers members only.
                boolean widenable = !name.equals("<init>") && old.methods.containsKey(name + desc.replace("L" + STANDIN, "L"));
                return super.visitMethod(widenable ? open(access) : access, name, desc, sig, ex);
            }
        }, 0);
        return w.toByteArray();
    }

    static int open(int flags) {
        return flags & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED) | Opcodes.ACC_PUBLIC;
    }

    /**
     * The game members the stand-ins use that the game keeps closed: opened through the mod's access transformer when the
     * loader reads it, else each is reported, since the stand-in would fail where it reaches the member.
     */
    void openFor(JarPort.Mod mod, Collection<String> used) {
        for (String name : used) {
            new ClassReader(standins.get(name)).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String sig, String[] ex) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int op, String owner, String mn, String md, boolean itf) {
                            need(owner, 'm', mn, md);
                        }

                        @Override
                        public void visitFieldInsn(int op, String owner, String fn, String fd) {
                            need(owner, 'f', fn, fd);
                        }
                    };
                }

                void need(String owner, char kind, String member, String desc) {
                    // A stand-in class extending a game class (GuiGraphics' Tooltips, a Screen) reaches what it inherits,
                    // where what is protected is open to it.
                    boolean sub = owner.startsWith(STANDIN);
                    for (int up = 0; owner != null && owner.startsWith(STANDIN) && up < 8; up++) {
                        byte[] own = standins.get(owner);
                        owner = own == null ? null : new ClassReader(own).getSuperName();
                    }
                    GameDiff.Cls site = owner == null ? null : port.b.classes.get(owner);
                    GameDiff.Cls holder = site == null || site.library ? null : Exposure.declaring(port.b, owner, kind, member, kind == 'f' ? null : desc);
                    if (holder == null || holder.library) {
                        return;
                    }
                    int flags = kind == 'f' ? holder.fields.get(member).flags : holder.methods.get(member + desc).flags;
                    boolean classOpen = (site.flags & Opcodes.ACC_PUBLIC) != 0, memberOpen = (flags & (sub ? Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED : Opcodes.ACC_PUBLIC)) != 0;
                    if (classOpen && memberOpen) {
                        return;
                    }
                    String line = Exposure.dotted(holder.name) + " " + member + (kind == 'f' ? "" : desc);
                    if (!mod.canWiden || holder.loader) {
                        mod.unlinked.add("stand-in uses a closed member this mod cannot open: " + line + " (in " + Exposure.dotted(name) + ")");
                        return;
                    }
                    if (!classOpen) {
                        mod.grants.add("public " + Exposure.dotted(owner));
                    }
                    if (!memberOpen) {
                        mod.grants.add("public " + line);
                    }
                }
            }, ClassReader.SKIP_DEBUG);
        }
    }

    /**
     * The stand-ins a ported jar's classes call, carried in the jar under a package of its own (mmt.standin.mID, ID from the
     * jar's whole name): two mods carrying one stand-in never put two copies of a package on the game. Adds them to entries and
     * points the classes that use them there; nothing when none is used.
     */
    void bundle(JarPort.Mod mod, Map<String, byte[]> rewritten, Map<String, byte[]> entries, String jarName) {
        if (standins.isEmpty()) {
            return;
        }
        Pattern ref = Pattern.compile(Pattern.quote(STANDIN) + "[\\w/$]+");
        Set<String> used = new TreeSet<>();
        Deque<byte[]> todo = new ArrayDeque<>(rewritten.values());
        while (!todo.isEmpty()) {
            Matcher m = ref.matcher(new String(todo.pop(), StandardCharsets.ISO_8859_1));
            while (m.find()) {
                for (String name : standins.keySet()) {
                    if ((name.equals(m.group()) || name.startsWith(m.group() + "$")) && used.add(name)) {
                        todo.push(standins.get(name));
                    }
                }
            }
        }
        if (used.isEmpty()) {
            return;
        }
        openFor(mod, used);
        Set<String> types = standinTypes();
        String home = standinHome(jarName, port.a.version, port.b.version);
        // Only this update's stand-ins move: an earlier port's, carried in the jar already, are the mod's own classes now.
        // A stand-in type goes where every jar's goes, so a mod's call and its nested library's declaration name one type.
        org.objectweb.asm.commons.Remapper move = new org.objectweb.asm.commons.Remapper(Opcodes.ASM9) {
            @Override
            public String map(String name) {
                return types.contains(name) ? typesHome() + name.substring(STANDIN.length())
                    : standins.containsKey(name) ? home + name.substring(STANDIN.length()) : name;
            }
        };
        for (var e : rewritten.entrySet()) {
            if (new String(e.getValue(), StandardCharsets.ISO_8859_1).contains(STANDIN)) {
                e.setValue(relocate(e.getValue(), move));
            }
        }
        for (String name : used) {
            if (types.contains(name)) {
                typesUsed = true;
                continue;
            }
            entries.put(move.map(name) + ".class", relocate(standins.get(name), move));
            rewritten.put(move.map(name) + ".class", relocate(standins.get(name), move));
        }
        mod.change("stand-ins carried: " + used.stream().filter(n -> !n.matches(".*\\$\\d+$")).map(n -> Exposure.dotted(n.substring(STANDIN.length()))).toList());
    }

    /**
     * The package a jar carries one update's stand-ins under, from the jar's whole name and the update: a jar nested in a
     * mod ("MOD.jar!INNER.jar") is a module of its own on NeoForge, which refuses one package in two modules, and a port
     * across several updates carries each one's stand-in for a class (Screen in 1.20 and again in 1.21.11) side by side.
     */
    static String standinHome(String jarName, String from, String to) {
        return STANDIN + "m" + Integer.toHexString((jarName + "|" + from + "-" + to).hashCode() & 0x7fffffff) + "/";
    }

    boolean typesUsed; // a jar of the tree being ported uses a stand-in type: its outermost jar carries typesJar

    /**
     * This update's stand-ins for classes it dropped, which stand in for the class itself, with their nested classes and
     * every stand-in they use. Mods name these types in their own methods and fields, and so do the libraries nested in
     * them, so all of them name one copy: a jar of its own (typesJar) the loader loads once, whichever mods carry it.
     * Its contents are the same for every mod of the update, since the loader keeps one jar of that id: a class the
     * newer game has and the older lacks by its own name is one, even where a mod's port routes it to another class
     * (GuiGraphics, which the route maps to GuiComponent where PortVerifier.fitting keeps the route). A Fabric pack of
     * ports that each carried only what their own route used ran the copy without GuiGraphics, and Puzzle failed.
     */
    Set<String> standinTypes() {
        if (types == null) {
            types = new TreeSet<>();
            Deque<String> todo = new ArrayDeque<>();
            for (String name : standins.keySet()) {
                String outer = name.contains("$") ? name.substring(0, name.indexOf('$')) : name;
                String old = outer.substring(STANDIN.length());
                boolean dropped = port.a.classes.containsKey(old) && !port.b.classes.containsKey(old) && becameType(old, standins.get(outer)) == null;
                if (port.classMap.containsValue(name) || port.classMap.containsValue(outer) || dropped) {
                    todo.push(name);
                }
            }
            Pattern ref = Pattern.compile(Pattern.quote(STANDIN) + "[\\w/$]+");
            while (!todo.isEmpty()) {
                String name = todo.pop();
                if (!types.add(name)) {
                    continue;
                }
                Matcher m = ref.matcher(new String(standins.get(name), StandardCharsets.ISO_8859_1));
                while (m.find()) {
                    for (String other : standins.keySet()) {
                        if (other.equals(m.group()) || other.startsWith(m.group() + "$")) {
                            todo.push(other);
                        }
                    }
                }
            }
        }
        return types;
    }

    Set<String> types;

    /** The package of this update's stand-in types, the same in every jar (only its jar of types holds it). */
    String typesHome() {
        return STANDIN + "u" + typesTag() + "/";
    }

    String typesTag() {
        return Integer.toHexString((port.a.version + "-" + port.b.version).hashCode() & 0x7fffffff);
    }

    /**
     * Nests the update's jar of stand-in types in the outermost jar, as the loader's jar-in-jar does for any library
     * (META-INF/jarjar/ and its metadata.json on NeoForge, META-INF/jars/ and fabric.mod.json's "jars" on Fabric): the
     * loader loads one copy of a nested library however many mods carry it, so the types are one module on NeoForge
     * and one class each on Fabric. Returns where the jar goes; the metadata is written into entries.
     */
    String carryTypes(Map<String, byte[]> entries, boolean fabric) {
        String file = "mmt-standin-types-" + typesTag() + ".jar";
        if (fabric) {
            String path = "META-INF/jars/" + file;
            Map<String, Object> meta = Json.object(Json.parse(new String(entries.get("fabric.mod.json"), StandardCharsets.UTF_8)));
            List<Object> jars = new ArrayList<>(meta.get("jars") instanceof List<?> l ? l : List.of());
            jars.add(new LinkedHashMap<>(Map.of("file", path)));
            meta.put("jars", jars);
            entries.put("fabric.mod.json", Json.write(meta).getBytes(StandardCharsets.UTF_8));
            return path;
        }
        String path = "META-INF/jarjar/" + file;
        byte[] list = entries.get("META-INF/jarjar/metadata.json");
        Map<String, Object> meta = list == null ? new LinkedHashMap<>() : Json.object(Json.parse(new String(list, StandardCharsets.UTF_8)));
        List<Object> jars = new ArrayList<>(meta.get("jars") instanceof List<?> l ? l : List.of());
        Map<String, Object> jar = new LinkedHashMap<>();
        // Keys put one by one: Map.of's order changes from one JVM to the next, and the same port must write the same jar.
        Map<String, Object> identifier = new LinkedHashMap<>();
        identifier.put("group", "mmt.standin");
        identifier.put("artifact", "types-" + typesTag());
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("range", "[1,)");
        version.put("artifactVersion", "1");
        jar.put("identifier", identifier);
        jar.put("version", version);
        jar.put("path", path);
        jar.put("isObfuscated", false);
        jars.add(jar);
        meta.put("jars", jars);
        entries.put("META-INF/jarjar/metadata.json", Json.write(meta).getBytes(StandardCharsets.UTF_8));
        return path;
    }

    /**
     * The jar of this update's stand-in types: every one of them, so the copy the loader picks holds what each mod
     * carrying it needs. A game library on NeoForge (loaded with the game, whose classes it uses), a mod on Fabric.
     */
    byte[] typesJar() throws IOException {
        org.objectweb.asm.commons.Remapper move = new org.objectweb.asm.commons.Remapper(Opcodes.ASM9) {
            @Override
            public String map(String name) {
                return standinTypes().contains(name) ? typesHome() + name.substring(STANDIN.length()) : name;
            }
        };
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nFMLModType: GAMELIBRARY\n".getBytes(StandardCharsets.UTF_8));
        files.put("fabric.mod.json", ("{\"schemaVersion\": 1, \"id\": \"mmt-standin-types-" + typesTag() + "\", \"version\": \"1\", \"name\": \"Stand-ins for "
            + port.a.version + " -> " + port.b.version + "\"}").getBytes(StandardCharsets.UTF_8));
        for (String name : standinTypes()) {
            files.put(move.map(name) + ".class", relocate(standins.get(name), move));
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(buffer)) {
            Set<String> folders = new HashSet<>();
            for (var e : files.entrySet()) {
                JarEntries.folders(out, folders, e.getKey(), Map.of());
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTime(0L);
                out.putNextEntry(entry);
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    static byte[] relocate(byte[] bytes, org.objectweb.asm.commons.Remapper move) {
        ClassReader r = new ClassReader(bytes);
        ClassWriter w = new ClassWriter(0);
        r.accept(new ClassRemapper(w, move), 0);
        return w.toByteArray();
    }
}

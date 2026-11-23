import java.nio.charset.StandardCharsets;
import java.util.*;
import org.objectweb.asm.*;

/**
 * The classes a mod's code needs on every start that neither release has. The link check leaves a reference the old
 * release did not have either as written: in a mod that ran on the old release it sits behind a check of the version, so
 * the update broke nothing there. A jar built for another release (one whose metadata accepts releases its code never
 * ran on) has no such check: its entry point names a class its own release has and these do not, and the game stops at
 * start (Fabric API's command callback v2, named on 1.18 by a mod built for 1.19 and later).
 *
 * What runs on every start is read from where the loader starts the mod: Fabric's main, client, server and preLaunch
 * entry points, and NeoForge's (and Forge's) @Mod classes. In each, the code before its first branch, return or throw,
 * outside any try block, runs whenever the mod does, and so does the start of a mod method it calls there (static, or a
 * constructor) and of a mod class it first uses there (its static initializer).
 */
final class EveryStart {

    final JarPort port;

    EveryStart(JarPort port) {
        this.port = port;
    }

    static final List<String> FABRIC_STAGES = List.of("main", "client", "server", "preLaunch");
    static final Set<String> STAGE_METHODS = Set.of("onInitialize()V", "onInitializeClient()V", "onInitializeServer()V", "onPreLaunch()V");
    static final Set<String> MOD_ANNOTATIONS = Set.of("Lnet/neoforged/fml/common/Mod;", "Lnet/minecraftforge/fml/common/Mod;");

    /** Reports, in mod.unlinked, each class the mod's start code names that neither release (nor the jar) has. */
    void check(JarPort.Mod mod, Map<String, byte[]> entries) {
        builtFor = builtFor(entries);
        if (builtFor != null && (builtFor.equals(port.a.version) || builtFor.equals(port.b.version))) {
            builtFor = null; // its libraries are these releases' own, already read
        }
        Deque<String> todo = new ArrayDeque<>(); // "class method+desc", or "class <init>" for each of its constructors
        for (String c : starts(mod, entries)) {
            todo.add(c + " <clinit>()V");
            todo.add(c + " <init>");
            STAGE_METHODS.forEach(m -> todo.add(c + " " + m));
        }
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            String next = todo.poll();
            if (!seen.add(next)) {
                continue;
            }
            String cls = next.substring(0, next.indexOf(' ')), method = next.substring(next.indexOf(' ') + 1);
            byte[] bytes = mod.bytes.get(cls);
            if (bytes != null) {
                scan(mod, bytes, cls, method, todo);
            }
        }
    }

    /** The mod classes the loader starts: entry points of the loader's own stages, or classes marked @Mod. */
    Set<String> starts(JarPort.Mod mod, Map<String, byte[]> entries) {
        Set<String> out = new TreeSet<>();
        byte[] fmj = entries.get("fabric.mod.json");
        if (fmj != null) {
            try {
                Object points = Json.object(Json.parse(new String(fmj, StandardCharsets.UTF_8))).get("entrypoints");
                for (String stage : FABRIC_STAGES) {
                    for (Object p : points instanceof Map<?, ?> m && m.get(stage) instanceof List<?> l ? l : List.of()) {
                        Object value = p instanceof Map<?, ?> adapted ? adapted.get("value") : p;
                        if (value instanceof String v) {
                            out.add(v.replaceFirst("::.*", "").replace('.', '/'));
                        }
                    }
                }
            } catch (RuntimeException unreadable) {
                // the loader refuses it too
            }
        }
        for (var e : mod.bytes.entrySet()) {
            boolean[] marked = {false};
            new ClassReader(e.getValue()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    marked[0] |= MOD_ANNOTATIONS.contains(desc);
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (marked[0]) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** Reads one method's start (method: name+desc, or "<init>" for every constructor) and queues the mod code it runs. */
    void scan(JarPort.Mod mod, byte[] bytes, String cls, String method, Deque<String> todo) {
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (!(name + desc).equals(method) && !(method.equals("<init>") && name.equals("<init>"))) {
                    return null;
                }
                String where = Exposure.dotted(cls) + "." + name;
                return new MethodVisitor(Opcodes.ASM9) {
                    boolean open = true;
                    int tries;
                    final Set<Label> tryStarts = new HashSet<>(), tryEnds = new HashSet<>();

                    void needs(String type) {
                        if (open && tries == 0) {
                            missing(mod, type, where);
                        }
                    }

                    void runs(String owner, String member) {
                        if (open && tries == 0 && mod.bytes.containsKey(owner)) {
                            todo.add(owner + " <clinit>()V");
                            if (member != null) {
                                todo.add(owner + " " + member);
                            }
                        }
                    }

                    @Override
                    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                        tryStarts.add(start);
                        tryEnds.add(end);
                    }

                    @Override
                    public void visitLabel(Label label) {
                        tries += (tryStarts.contains(label) ? 1 : 0) - (tryEnds.contains(label) ? 1 : 0);
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        open &= !(opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW);
                    }

                    @Override
                    public void visitJumpInsn(int opcode, Label label) {
                        open = false;
                    }

                    @Override
                    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
                        open = false;
                    }

                    @Override
                    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
                        open = false;
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        needs(type);
                        runs(type, null);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                        needs(owner);
                        if (opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC) {
                            runs(owner, null);
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        needs(owner);
                        if (opcode == Opcodes.INVOKESTATIC || opcode == Opcodes.INVOKESPECIAL && name.equals("<init>")) {
                            runs(owner, name + desc);
                        }
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) {
                        // Linking the call site resolves the interface it makes and the types and method its arguments name.
                        types(Type.getType(desc));
                        for (Object arg : args) {
                            if (arg instanceof Type t) {
                                types(t);
                            } else if (arg instanceof Handle h) {
                                needs(h.getOwner());
                                types(Type.getType(h.getDesc()));
                            }
                        }
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof Type t) {
                            types(t);
                        }
                    }

                    @Override
                    public void visitMultiANewArrayInsn(String desc, int dims) {
                        types(Type.getType(desc));
                    }

                    void types(Type t) {
                        switch (t.getSort()) {
                            case Type.METHOD -> {
                                types(t.getReturnType());
                                for (Type p : t.getArgumentTypes()) {
                                    types(p);
                                }
                            }
                            case Type.ARRAY -> types(t.getElementType());
                            case Type.OBJECT -> needs(t.getInternalName());
                            default -> { }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    /** Reports a class neither release has, nor the jar, in a package the game, its libraries or its platform hold. */
    void missing(JarPort.Mod mod, String type, String where) {
        while (type.startsWith("[")) {
            type = type.substring(1);
        }
        if (type.startsWith("L") && type.endsWith(";")) {
            type = type.substring(1, type.length() - 1);
        }
        String area = area(type);
        if (area.isEmpty() || port.a.classes.containsKey(type) || port.b.classes.containsKey(type) || port.b.classes.containsKey(port.cls(type))
            || mod.bytes.containsKey(type) || port.tree != null && port.tree.copies.containsKey(type)
            || !releasePackages().contains(area) && (builtFor == null || !ships(builtFor, area))
            || newIntermediary(type)) {
            return;
        }
        mod.unlinked.add("class in neither release, used on every start: " + Exposure.dotted(type) + " (in " + where + ")");
    }

    String builtFor; // the release the jar being checked was built against, as its build wrote it; null when it does not say

    /** The release Loom built a jar against (its manifest's Fabric-Minecraft-Version), or null. */
    static String builtFor(Map<String, byte[]> entries) {
        byte[] manifest = entries.get("META-INF/MANIFEST.MF");
        if (manifest == null) {
            return null;
        }
        for (String line : new String(manifest, StandardCharsets.UTF_8).split("\r?\n")) {
            if (line.startsWith("Fabric-Minecraft-Version:")) {
                return line.substring(line.indexOf(':') + 1).trim();
            }
        }
        return null;
    }

    // Per release and Maven group, the areas of that group's libraries the release ships: read once a run.
    static final Map<String, Set<String>> LIBRARY_AREAS = new java.util.concurrent.ConcurrentHashMap<>();
    static final java.util.regex.Pattern ARTIFACT = java.util.regex.Pattern.compile("\"artifact\"\\s*:\\s*\\{([^}]*)}");

    /**
     * Whether a release ships a library holding a class in this area. A jar built against another release than these
     * two uses that release's libraries as the game's own (a mod built for 1.21.5 logs through SLF4J, which the game
     * ships from 1.18 on): on a release without the library, a class of it the start code names is missing, and the game
     * stops at start. Only the libraries whose Maven group starts as the area does are fetched (org/slf4j/slf4j-api for
     * org/slf4j); false when the release's libraries cannot be read.
     */
    static boolean ships(String release, String area) {
        String[] parts = area.split("/");
        if (parts.length < 2) {
            return false;
        }
        String group = parts[0] + "/" + parts[1] + "/";
        return LIBRARY_AREAS.computeIfAbsent(release + " " + group, k -> {
            Set<String> out = new HashSet<>();
            try {
                java.util.regex.Matcher m = ARTIFACT.matcher(GameDiff.versionJson(release));
                while (m.find()) {
                    String path = VersionMap.jsonString(m.group(1), "path", 0);
                    if (path == null || !path.startsWith(group) || !path.endsWith(".jar") || path.contains("natives")) {
                        continue;
                    }
                    String[] download = { VersionMap.jsonString(m.group(1), "url", 0), VersionMap.jsonString(m.group(1), "sha1", 0) };
                    try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(GameDiff.verified(download, "lib-" + path.substring(path.lastIndexOf('/') + 1)).toFile())) {
                        z.stream().map(java.util.zip.ZipEntry::getName).filter(n -> n.endsWith(".class") && !n.startsWith("META-INF/")).forEach(n -> out.add(area(n)));
                    }
                }
            } catch (Exception unreadable) {
                return Set.of();
            }
            return out;
        }).contains(area);
    }

    Set<String> newIntermediary;
    boolean newIntermediaryRead;

    /**
     * Whether a class is the new release's by Fabric's intermediary name. A jar read in the old release's names keeps the
     * intermediary name of a class the old release lacks (TagKey, class_6862, named on 1.18.1 by a build for 1.18.2), and
     * the hand-back gives it to the new release as it is. A name that cannot be checked is taken as the new release's.
     */
    boolean newIntermediary(String type) {
        if (!Exposure.Intermediary.CLASS.matcher(type).matches()) {
            return false;
        }
        readNewIntermediary();
        return newIntermediary == null || newIntermediary.contains(type);
    }

    Map<String, Set<String>> newIntermediaryMethods; // intermediary name -> the descriptors (Mojang names) the new release gives it

    /**
     * The same for a method a jar names in intermediary (method_30010), with its descriptor in Mojang names when the name
     * gives one: whether the new release has it so. 1.16's method_30010 returns an ItemStack; a build for 1.17 names the
     * one returning void, which 1.16 lacks.
     */
    boolean newIntermediaryMethod(String name, String desc) {
        if (!name.matches("method_\\d+")) {
            return false;
        }
        readNewIntermediary();
        if (newIntermediaryMethods == null) {
            return true;
        }
        Set<String> descs = newIntermediaryMethods.getOrDefault(name, Set.of());
        return desc == null ? !descs.isEmpty() : descs.contains(desc);
    }

    void readNewIntermediary() {
        if (!newIntermediaryRead) {
            newIntermediaryRead = true;
            ModNames.Back back = "fabric".equals(port.loader) ? port.loaderNames.handBack() : null;
            newIntermediary = back == null ? null : new HashSet<>(back.classes.values());
            newIntermediaryMethods = back == null ? null : new HashMap<>();
            if (back != null) {
                back.methods.forEach((key, inter) -> newIntermediaryMethods.computeIfAbsent(inter, k -> new HashSet<>()).add(key.substring(key.indexOf('('))));
            }
        }
    }

    Set<String> releasePackages;

    /** The areas (area()) of every class either release reads: the game, the libraries it ships, its platform. */
    Set<String> releasePackages() {
        if (releasePackages == null) {
            Set<String> out = new HashSet<>();
            for (GameDiff.Snap s : List.of(port.a, port.b)) {
                for (String c : s.classes.keySet()) {
                    out.add(area(c));
                }
            }
            releasePackages = out;
        }
        return releasePackages;
    }

    /** A class's package to three levels (net/fabricmc/fabric, net/minecraft/world), or all of it when shorter. */
    static String area(String cls) {
        int end = cls.lastIndexOf('/'), at = -1;
        for (int level = 0; level < 3; level++) {
            int next = cls.indexOf('/', at + 1);
            if (next < 0 || next > end) {
                break;
            }
            at = next;
        }
        return at < 0 ? "" : cls.substring(0, at);
    }
}

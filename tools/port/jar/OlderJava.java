import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Ports to a release on an older Java: the mod's classes lowered to it (Downgrade), with stand-ins for the JDK classes it lacks. */
final class OlderJava {

    final JarPort port;

    OlderJava(JarPort port) {
        this.port = port;
    }

    /** Stand-ins for the JDK methods an older Java lacks (standins/jdk/), which a mod lowered to that Java calls instead. */
    Downgrade.Standins jdkStandins = Downgrade.Standins.NONE;

    /**
     * The JDK stand-ins the jars of the tree being ported call, under the outermost jar's package: only it carries them.
     * A stand-in for a whole class (HexFormat) is a type in the descriptors of a library's methods and of the mod's calls
     * to them, so a jar and the jars nested in it must name one copy; and one copy in one jar is one package in one module.
     */
    final Map<String, byte[]> treeStandins = new TreeMap<>();

    /**
     * The jar's classes lowered to the Java the new release runs on, when they were built for a newer one (Downgrade): the
     * release's JVM refuses a newer class file outright. What that Java cannot run is reported, and the JDK stand-ins the
     * classes call now are named under the outermost jar's package (treeStandins), which carries them (carryStandins).
     */
    void lowerJava(JarPort.Mod mod, Map<String, byte[]> rewritten, Map<String, byte[]> entries, String jarName) {
        int java = port.b.java > 0 ? port.b.java : StandinClasses.standinJava(port.b.version);
        Set<String> breaks = new TreeSet<>();
        Map<String, Integer> changed = new TreeMap<>();
        // The classes kept for another loader are lowered too: the port leaves their names as they came, but a loader
        // that runs them (Sinytra Connector, a Fabric build's classes in a NeoForge jar) does so on this release's JVM,
        // which refuses a newer class file. What they ask of the JDK is not judged, as nothing else of them is.
        Map<String, byte[]> classes = new HashMap<>(rewritten);
        for (String n : mod.foreign) {
            classes.put(n, entries.get(n));
        }
        String outermost = jarName.contains("!") ? jarName.substring(0, jarName.indexOf('!')) : jarName;
        Map<String, byte[]> carried = new Downgrade(java, jdkStandins).lower(classes, StandinClasses.standinHome(outermost, "java", "java" + java), breaks, changed);
        if (changed.isEmpty()) {
            return;
        }
        for (String n : mod.foreign) {
            entries.put(n, classes.get(n));
        }
        classes.keySet().removeAll(mod.foreign);
        rewritten.putAll(classes);
        Set<String> foreign = new HashSet<>();
        for (String n : mod.foreign) {
            foreign.add(Downgrade.dotted(n.substring(0, n.length() - ".class".length())));
        }
        breaks.removeIf(b -> b.endsWith(")") && foreign.contains(b.substring(b.lastIndexOf(" (in ") + 5, b.length() - 1)));
        mod.lowered = java;
        mod.unlinked.addAll(breaks);
        changed.forEach((k, v) -> mod.changed.merge(k, v, Integer::sum));
        // Verified with the jar's classes, as the loader will find them; written only into the outermost jar.
        rewritten.putAll(carried);
        treeStandins.putAll(carried);
        if (!carried.isEmpty() && !outermost.equals(jarName)) {
            mod.change("JDK stand-ins called, carried in the outermost jar: " + named(carried.keySet()));
        }
    }

    /** The outermost jar, once every jar nested in it is ported, carries the JDK stand-ins all of them call. */
    void carryStandins(JarPort.Mod mod, Map<String, byte[]> entries) {
        if (treeStandins.isEmpty()) {
            return;
        }
        entries.putAll(treeStandins);
        mod.change("JDK stand-ins carried: " + named(treeStandins.keySet()));
    }

    static List<String> named(Set<String> entries) {
        return entries.stream().filter(n -> !n.matches(".*\\$\\d+\\.class$")).map(n -> Exposure.dotted(n.substring(n.indexOf("/java/") + 1, n.length() - 6))).toList();
    }

    static final Pattern MIXIN_LEVEL = Pattern.compile("(\"compatibilityLevel\"\\s*:\\s*\"JAVA_)(\\d+)(\")");

    /**
     * A Mixin config asking for a newer Java than the jar was lowered to asks for that one: Mixin refuses a level the JVM
     * running it does not reach (JAVA_17 on Java 8), and the lowered classes need no more.
     */
    static byte[] mixinLevel(byte[] json, int java) {
        String text = new String(json, StandardCharsets.UTF_8);
        Matcher m = MIXIN_LEVEL.matcher(text);
        if (!m.find() || Integer.parseInt(m.group(2)) <= java) {
            return json;
        }
        return m.replaceFirst("$1" + java + "$3").getBytes(StandardCharsets.UTF_8);
    }

    /** fabric.mod.json asking for the Java the jar was lowered to: Fabric Loader refuses a mod whose "java" dependency the JVM does not meet. */
    static byte[] runsOn(byte[] fmj, int java) {
        if (java == 0) {
            return fmj;
        }
        try {
            Map<String, Object> meta = Json.object(Json.parse(new String(fmj, StandardCharsets.UTF_8)));
            if (meta.get("depends") instanceof Map<?, ?> depends && depends.containsKey("java")) {
                Json.object(depends).put("java", ">=" + java);
                return Json.write(meta).getBytes(StandardCharsets.UTF_8);
            }
        } catch (RuntimeException unreadable) {
            // the loader cannot read it either
        }
        return fmj;
    }
}

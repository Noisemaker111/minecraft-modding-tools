import java.util.*;

/** Ports back to an older release: the newer game's code the older one lacks, copied into the mod. */
final class NewerCode {

    final JarPort port;

    NewerCode(JarPort port) {
        this.port = port;
    }

    // ---- Copies of the newer game's code: a port back to an older release meets members the older one does not have yet.

    /**
     * On a port back to an older release (a newer than b): the game methods the jar calls that b does not have yet and
     * nothing else answers (a route, a rule, a stand-in), copied from a's own code into a class the jar carries
     * (copiedHome), where a's code uses only what b has, or other such methods. Calls to them are pointed at the copies,
     * an instance method's receiver passed first, and the copies are then ported with the jar's own classes, which
     * checks and opens what they use in b. The copy is the newer game's own code run on the older one: a method using a
     * field, constructor, class or super call b lacks has no older form here, and its call stays a break. A port across
     * several updates copies on each step, so a copy carried from one step is the jar's own code on the next. Returns
     * the members copied (owner.name+desc, a's names), the jar's classes rewritten in entries.
     */
    List<String> copyNewer(Map<String, byte[]> entries, String jarName) {
        if (JarPort.compareVersions(port.a.version, port.b.version) <= 0) {
            return List.of();
        }
        GameCode code = port.code(port.a);
        if (code == null) {
            return List.of();
        }
        Map<String, GameDiff.RawCls> own = new HashMap<>();
        for (var e : entries.entrySet()) {
            if (e.getKey().endsWith(".class") && !e.getKey().startsWith("META-INF/versions/")) {
                try {
                    GameDiff.RawCls rc = GameDiff.parse(e.getValue());
                    own.put(rc.name, rc);
                } catch (RuntimeException unreadable) {
                    // read (and reported) with the jar's classes
                }
            }
        }
        Copier copier = new Copier(port, code, own, copiedHome(jarName));
        Map<String, byte[]> redirected = new HashMap<>();
        for (var e : entries.entrySet()) {
            if (!e.getKey().endsWith(".class") || e.getKey().startsWith("META-INF/versions/") || port.loaderNames.otherLoader(e.getValue())) {
                continue;
            }
            byte[] out = copier.redirect(e.getValue());
            if (out != null) {
                redirected.put(e.getKey(), out);
            }
        }
        if (copier.copies.isEmpty()) {
            return List.of();
        }
        entries.putAll(redirected);
        entries.putAll(copier.write());
        return copier.called.stream().sorted().map(Exposure::dotted).toList();
    }

    /**
     * The package a jar carries one step's copies of the newer game's code under, its own (as standinHome): a jar nested
     * in another is a module of its own on NeoForge, which refuses one package in two modules.
     */
    String copiedHome(String jarName) {
        return StandinClasses.standinHome(jarName, port.a.version, port.b.version) + "copied/";
    }

    /** Whether the port answers a's member owner.name+desc (a field: desc empty) on its own: a route, a rule or a stand-in. */
    boolean handled(String owner, String name, String desc) {
        String key = owner + "." + name + desc;
        if (port.rewrites.containsKey(key) || port.removals.containsKey(key) || port.chains.containsKey(key) || port.chains.containsKey(owner + "." + name) || port.defaults.containsKey(key)) {
            return true;
        }
        for (JarPort.Fix f : port.fixes.getOrDefault(owner, List.of())) {
            if (f.name().matcher(name).matches() && (f.args() == null || desc.startsWith(f.args()))) {
                return true;
            }
        }
        for (JarPort.RetypedCall c : port.retypedCalls.getOrDefault(owner, List.of())) {
            if (c.name().equals(name) && c.desc().equals(desc)) {
                return true;
            }
        }
        return false;
    }
}

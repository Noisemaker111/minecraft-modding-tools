import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Jars a mod nests for several releases or loaders: which of them the game it is ported to loads. */
final class BundledMods {

    final JarPort port;

    BundledMods(JarPort port) {
        this.port = port;
    }

    /** The jars nested in a jar (named as port names it) that the loader loads on the release ported to. */
    Set<String> loadedNested(Map<String, byte[]> entries, String name) {
        Set<String> out = JarEntries.nested(entries);
        out.removeAll(notLoaded(entries, name).keySet());
        return out;
    }

    /**
     * The nested jars the loader passes over on the release ported to, each with why. They are left as they are, neither
     * ported nor linked, and keep the releases their metadata names, so the loader goes on passing them over.
     *
     * Fabric Loader loads a nested mod where its own "minecraft" holds (fabricLoads), which is how a bundle of one module
     * per release (ViaFabric's viafabric-mc1144 to -mc12111) runs only the module for the release it starts on. A module
     * that accepts neither release is loaded on neither. One that accepts the old release only is passed over when the
     * bundle carries a module that accepts the new release and not the old: the loader runs that one instead. With no
     * such module, the old release's module is ported and asks for the new release, as every nested mod is.
     *
     * NeoForge's loader picks among nested jars by their artifacts' version ranges, and refuses a mod whose "minecraft"
     * range fails instead of passing it over, so no jar's metadata picks a module per release there: every nested jar is
     * ported, as before. A bundle that picks its module in its own code reads to the porter as modules loaded together.
     */
    Map<String, String> notLoaded(Map<String, byte[]> entries, String name) {
        Map<String, String> kept = port.decided == null ? null : port.decided.get(name);
        if (kept != null) {
            return kept;
        }
        Map<String, String> out = port.loader.equals("fabric") ? JarPort.fabricNotLoaded(entries, port.origin != null ? port.origin : port.a.version, port.portTo != null ? port.portTo : port.to) : new TreeMap<>();
        if (port.decided != null) {
            port.decided.put(name, out);
        }
        return out;
    }

    /**
     * Whether Fabric Loader loads a nested mod on a release, by the mod's own fabric.mod.json: its resolver takes a nested
     * mod only when the mod's "depends" hold, and passes it over otherwise. A jar that names no "minecraft", or whose
     * metadata cannot be read, is loaded.
     */
    static boolean fabricLoads(byte[] jar, String release) {
        Object range = fabricMinecraft(jar);
        return range == null || fabricAccepts(range, release);
    }

    /** The "minecraft" a jar's fabric.mod.json depends on, or null. */
    static Object fabricMinecraft(byte[] jar) {
        if (jar == null) {
            return null;
        }
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (e.getName().equals("fabric.mod.json")) {
                    Object meta = Json.parse(new String(z.readAllBytes(), StandardCharsets.UTF_8));
                    return meta instanceof Map<?, ?> m && m.get("depends") instanceof Map<?, ?> d ? d.get("minecraft") : null;
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        return null;
    }

    /**
     * Whether a fabric.mod.json version predicate accepts a release: a string of terms that must all hold ("||" between
     * alternatives), or a list any one of which may. Build metadata after "+" never counts in Fabric's comparison.
     */
    static boolean fabricAccepts(Object range, String release) {
        if (range instanceof List<?> any) {
            for (Object r : any) {
                if (fabricAccepts(r, release)) {
                    return true;
                }
            }
            return any.isEmpty();
        }
        for (String alternative : String.valueOf(range).split("\\|\\|")) {
            if (Versions.matches(alternative.replaceAll("\\+[^\\s]*", ""), release)) {
                return true;
            }
        }
        return false;
    }
}

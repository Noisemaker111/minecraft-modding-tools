import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.objectweb.asm.*;

/** The names a jar is written in (Fabric's intermediary, Forge's SRG, Mojang's): read in, handed back, and told apart. */
final class LoaderNames {

    final JarPort port;

    LoaderNames(JarPort port) {
        this.port = port;
    }

    /**
     * The jar's own intermediary method names, which the hand-back gives back where it cannot follow a class to the game:
     * from the jar, or from the jar as it came where it was read into Mojang's names before (--originals). Read before
     * the first step of a port, whose later steps read the jar in Mojang's names.
     */
    Map<String, Set<String>> originals(byte[] jar, String name) throws IOException {
        if (!port.loader.equals("fabric")) {
            return Map.of();
        }
        Path came = originalsDir == null ? null : originalsDir.resolve(name);
        return intermediary(jar) ? ModNames.Back.methodNames(jar) : came != null && Files.isRegularFile(came) ? ModNames.Back.methodNames(Files.readAllBytes(came)) : Map.of();
    }
    Path originalsDir; // where each jar is as it came, by its file name, when the jars given were read into Mojang's names first
    ModNames in;
    ModNames.Back out;

    ModNames fabricIn() throws IOException {
        try {
            return in != null ? in : (in = new ModNames(Exposure.Intermediary.of(port.a.version)));
        } catch (Exception unreadable) {
            throw new IOException("Fabric's intermediary names for " + port.a.version + " could not be read: " + unreadable.getMessage(), unreadable);
        }
    }

    /** The hand-back to the target's intermediary, or null where a release has none to read (a test's made-up one). */
    ModNames.Back handBack() {
        if (handBackMissing) {
            return null;
        }
        try {
            return fabricOut();
        } catch (IOException unreadable) {
            handBackMissing = true;
            return null;
        }
    }

    boolean handBackMissing;

    ModNames.Back fabricOut() throws IOException {
        try {
            return out != null ? out : (out = new ModNames.Back(port.b.version, port.b));
        } catch (Exception unreadable) {
            throw new IOException("Fabric's intermediary names for " + port.b.version + " could not be read: " + unreadable.getMessage(), unreadable);
        }
    }

    /** Whether a jar's classes, or its nested jars', name the game in Fabric's intermediary (net/minecraft/class_N). */
    static boolean intermediary(byte[] jar) throws IOException {
        return intermediary(JarEntries.entries(jar, "", new HashMap<>()));
    }

    static boolean intermediary(Map<String, byte[]> entries) throws IOException {
        byte[] mark = "net/minecraft/class_".getBytes(StandardCharsets.ISO_8859_1);
        for (var e : entries.entrySet()) {
            if (e.getKey().endsWith(".class") && JarEntries.indexOf(e.getValue(), mark) >= 0) {
                return true;
            }
        }
        for (String n : JarEntries.nested(entries)) {
            if (intermediary(entries.get(n))) {
                return true;
            }
        }
        return false;
    }

    static final Pattern SRG = Pattern.compile("\\b[mf]_\\d+_\\b");
    static final Pattern INTERMEDIARY = Pattern.compile("net/minecraft/class_\\d+");

    /** Whether the bytes hold m_N_ or f_N_ anywhere: SRG without its word bounds, which raw bytes beside a name can break. */
    static boolean srgAnywhere(byte[] b) {
        for (int i = 0; i + 3 < b.length; i++) {
            if ((b[i] == 'm' || b[i] == 'f') && b[i + 1] == '_' && b[i + 2] >= '0' && b[i + 2] <= '9') {
                int j = i + 3;
                while (j < b.length && b[j] >= '0' && b[j] <= '9') {
                    j++;
                }
                if (j < b.length && b[j] == '_') {
                    return true;
                }
            }
        }
        return false;
    }

    static final byte[] INTERMEDIARY_CLASS = "net/minecraft/class_".getBytes(StandardCharsets.ISO_8859_1);

    /** Whether the bytes hold net/minecraft/class_N anywhere. */
    static boolean intermediaryAnywhere(byte[] b) {
        for (int i = JarEntries.indexOf(b, INTERMEDIARY_CLASS, 0); i >= 0; i = JarEntries.indexOf(b, INTERMEDIARY_CLASS, i + 1)) {
            int next = i + INTERMEDIARY_CLASS.length;
            if (next < b.length && b[next] >= '0' && b[next] <= '9') {
                return true;
            }
        }
        return false;
    }

    /**
     * A class a jar for several loaders carries for another one (a Forge class beside the Fabric ones), which the loader
     * ported for never loads, so it is no part of the mod there, before the port or after. It names the game as only
     * that loader's build does. A Fabric jar is read in Mojang's names through intermediary, so a class of it still
     * naming members by SRG id (m_1234_) was built for Forge; any other jar is read in Mojang's names too, so a class
     * still naming a game class by intermediary (class_1234) was built for Fabric. (An SRG id left in a NeoForge jar of
     * the SRG releases is a gap in NeoForm's names, not another loader.)
     */
    boolean otherLoader(byte[] bytes) {
        // Read from what the JVM links (types, calls, fields, declared names), not from strings: a mixin for every
        // loader lists each loader's names in its annotations and runs on all of them.
        Pattern other = port.loader.equals("fabric") ? SRG : INTERMEDIARY;
        // Every name the class links is text in its constant pool: a class whose bytes hold no such name anywhere has none,
        // and reading every class of every jar at every step of a long port was a tenth of the port.
        if (!(port.loader.equals("fabric") ? srgAnywhere(bytes) : intermediaryAnywhere(bytes))) {
            return false;
        }
        boolean[] found = new boolean[1];
        java.util.function.Consumer<String> seen = text -> found[0] |= text != null && other.matcher(text).find();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                seen.accept(superName);
                for (String i : interfaces == null ? new String[0] : interfaces) {
                    seen.accept(i);
                }
            }

            @Override
            public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                seen.accept(name);
                seen.accept(desc);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                seen.accept(name);
                seen.accept(desc);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String n, String d, boolean itf) {
                        seen.accept(owner);
                        seen.accept(n);
                        seen.accept(d);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String n, String d) {
                        seen.accept(owner);
                        seen.accept(n);
                        seen.accept(d);
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        seen.accept(type);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }
}

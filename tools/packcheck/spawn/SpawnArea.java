import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * pack-check's server agent: a server it starts generates no spawn area before the world opens, only the chunk the
 * spawn is in. The probe loads its own test area (75 chunks) through the normal chunk system, and those chunks are
 * generated then, with every mod's world generation, so what the probe checks is unchanged. The spawn area it skips
 * is 441 chunks before 1.20.5: 44 of a 75 s server start and 22 of a 39 s reopen on GitHub's runners (fabric 1.18,
 * 2026-11-23).
 *
 * Read by shape, not by name, so one agent serves every loader and every naming (Mojang, SRG, intermediary):
 * - Before 1.20.5, MinecraftServer's start adds a START ticket of distance 11 at the spawn and waits for its 441
 *   ticking chunks (bipush 11 ... sipush 441, if_icmpeq/if_icmpne). The distance becomes 1, whose ticking area is the
 *   spawn chunk alone, and the wait ends at 1 or more, so a mod that loads chunks of its own while the level loads
 *   cannot make it wait forever.
 * - 1.20.5 to 1.21.8 read the distance from the spawnChunkRadius game rule, whose default is 25 chunks (3.4 s of a fabric
 *   1.21.1 start). Left as it is: at 0 (no spawn chunk at all) the 1.21.1 server hung for two minutes stopping and
 *   lost the probe's placed blocks, so its reopen found stone turned to air.
 * - From 1.21.9 the server prepares no spawn area at start.
 * Neither has the shape, so nothing changes for them.
 * The change is said on standard output (MMT-SPAWN-AREA), and a class with no such shape is left as it came.
 */
public final class SpawnArea {
    static final String SERVER = "net/minecraft/server/MinecraftServer";
    static final OutputStream console = new FileOutputStream(FileDescriptor.out);

    public static void premain(String args, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
                try {
                    if (SERVER.equals(name)) return spawnWait(bytes);
                } catch (Throwable error) {
                    say("MMT-SPAWN-AREA unchanged " + name + ": " + error);
                }
                return null;
            }
        });
    }

    /** MinecraftServer with its spawn wait cut to the spawn chunk, or null when no method has the shape. */
    static byte[] spawnWait(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        // First pass: the method that waits for 441 chunks, and which of its bipush 11s comes last before that wait.
        final String[] found = new String[2];
        final int[] radius = {-1};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, final String method, final String desc, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    int elevens = 0, lastEleven = -1;
                    @Override public void visitIntInsn(int opcode, int operand) {
                        if (opcode == Opcodes.BIPUSH && operand == 11) lastEleven = elevens++;
                        if (opcode == Opcodes.SIPUSH && operand == 441 && lastEleven >= 0 && found[0] == null) {
                            found[0] = method;
                            found[1] = desc;
                            radius[0] = lastEleven;
                        }
                    }
                };
            }
        }, 0);
        if (found[0] == null) return null;
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final boolean[] done = new boolean[3];
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                MethodVisitor out = super.visitMethod(access, method, desc, signature, exceptions);
                if (!method.equals(found[0]) || !desc.equals(found[1])) return out;
                return new MethodVisitor(Opcodes.ASM9, out) {
                    int elevens = 0;
                    boolean waiting;
                    @Override public void visitIntInsn(int opcode, int operand) {
                        if (opcode == Opcodes.BIPUSH && operand == 11 && elevens++ == radius[0]) {
                            super.visitInsn(Opcodes.ICONST_1);
                            done[0] = true;
                        } else if (opcode == Opcodes.SIPUSH && operand == 441 && !done[1]) {
                            super.visitInsn(Opcodes.ICONST_1);
                            done[1] = waiting = true;
                        } else {
                            super.visitIntInsn(opcode, operand);
                        }
                    }
                    @Override public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
                        if (waiting) {
                            waiting = false;
                            if (opcode == Opcodes.IF_ICMPEQ) { super.visitJumpInsn(Opcodes.IF_ICMPGE, label); done[2] = true; return; }
                            if (opcode == Opcodes.IF_ICMPNE) { super.visitJumpInsn(Opcodes.IF_ICMPLT, label); done[2] = true; return; }
                        }
                        super.visitJumpInsn(opcode, label);
                    }
                };
            }
        }, 0);
        if (!done[0] || !done[1] || !done[2]) {
            say("MMT-SPAWN-AREA unchanged " + SERVER + ": its wait for 441 spawn chunks is not followed by the comparison it ends on");
            return null;
        }
        say("MMT-SPAWN-AREA spawn chunk only: " + SERVER + "." + found[0] + found[1] + " waits for 1 ticking chunk at distance 1, not 441 at 11");
        return writer.toByteArray();
    }

    static void say(String line) {
        try {
            console.write((line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException closed) {
            // The server's output is gone; the change still applies.
        }
    }
}

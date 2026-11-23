import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/** Relocate the agent's private ASM copy without loading or changing the game's ASM. */
final class Relocate {

    static String name(String name) {
        return name.replace("org/objectweb/asm", "dev/mmt/internal/asm");
    }

    static byte[] classes(byte[] bytes) throws IOException {
        var input = new DataInputStream(new ByteArrayInputStream(bytes));
        var buffer = new ByteArrayOutputStream();
        var output = new DataOutputStream(buffer);
        if (input.readInt() != 0xCAFEBABE) throw new IOException("Invalid class in agent");
        output.writeInt(0xCAFEBABE);
        output.writeShort(input.readUnsignedShort());
        output.writeShort(input.readUnsignedShort());
        int count = input.readUnsignedShort();
        output.writeShort(count);
        for (int i = 1; i < count; i++) {
            int tag = input.readUnsignedByte();
            output.writeByte(tag);
            switch (tag) {
                case 1 -> output.writeUTF(
                    name(input.readUTF()).replace("org.objectweb.asm", "dev.mmt.internal.asm")
                );
                case 3, 4, 9, 10, 11, 12, 17, 18 -> output.writeInt(input.readInt());
                case 5, 6 -> {
                    output.writeLong(input.readLong());
                    i++;
                }
                case 7, 8, 16, 19, 20 -> output.writeShort(input.readUnsignedShort());
                case 15 -> {
                    output.writeByte(input.readUnsignedByte());
                    output.writeShort(input.readUnsignedShort());
                }
                default -> throw new IOException("Unknown class constant " + tag);
            }
        }
        input.transferTo(output);
        return buffer.toByteArray();
    }

    /**
     * A java agent's jar: its compiled classes and the ASM they were built against, both with ASM renamed, so the agent
     * never meets the ASM the game or a loader ships. Written under a name of this run's own beside target, then moved
     * onto it: two builds side by side each write their own.
     */
    static void agentJar(Path classes, Path asm, Manifest manifest, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path part = Files.createTempFile(target.getParent(), target.getFileName() + "-", ".part");
        Set<String> entries = new HashSet<>();
        try (var output = new JarOutputStream(Files.newOutputStream(part), manifest); var input = new ZipFile(asm.toFile()); var walk = Files.walk(classes)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                String name = classes.relativize(file).toString().replace('\\', '/');
                output.putNextEntry(new JarEntry(name));
                output.write(classes(Files.readAllBytes(file)));
                output.closeEntry();
                entries.add(name);
            }
            var items = input.entries();
            while (items.hasMoreElements()) {
                ZipEntry entry = items.nextElement();
                if (!entry.getName().startsWith("org/objectweb/asm/") || !entry.getName().endsWith(".class") || !entries.add(entry.getName())) continue;
                output.putNextEntry(new JarEntry(name(entry.getName())));
                try (var stream = input.getInputStream(entry)) {
                    output.write(classes(stream.readAllBytes()));
                }
                output.closeEntry();
            }
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
    }
}

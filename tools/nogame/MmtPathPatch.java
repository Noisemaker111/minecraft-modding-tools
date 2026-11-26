import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Run once on each Java a walled start uses: writes that Java's own classes with three small changes, so that two of
 * its lookups go on where a folder above may not be read (MmtListed and MmtFileSystem say which and why).
 * - sun.nio.fs.WindowsLinkSupport: its one call of WindowsNativeDispatcher.FindFirstFile goes to MmtListed.
 * - java.io.DefaultFileSystem: the file system it makes is MmtFileSystem.
 * - java.io.WinNTFileSystem: no longer final, so MmtFileSystem can extend it.
 * Only constant pools and one flag change: a class name is added and a reference moved to it. Written for Java 8, the
 * oldest a game runs on, where the file system needs no change (it never asks the root).
 * Usage: MmtPathPatch OUT_FOLDER [files]
 */
public final class MmtPathPatch {

    static final int UTF8 = 1, CLASS = 7, METHOD = 10, FINAL = 0x0010;

    /** A class file's constant pool: what each entry is, where it is, and what it names. */
    static final class Pool {
        final byte[] bytes;
        final int count, end;
        final String[] text;
        final int[] tag, first, second, at;

        Pool(byte[] bytes) throws IOException {
            this.bytes = bytes;
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            in.skipBytes(8);
            count = in.readUnsignedShort();
            text = new String[count];
            tag = new int[count];
            first = new int[count];
            second = new int[count];
            at = new int[count];
            int position = 10;
            for (int index = 1; index < count; index++) {
                at[index] = position;
                tag[index] = in.readUnsignedByte();
                int size;
                switch (tag[index]) {
                    case UTF8:
                        text[index] = in.readUTF();
                        size = 2 + utfLength(text[index]);
                        break;
                    case 3: case 4:
                        in.skipBytes(4);
                        size = 4;
                        break;
                    case 5: case 6:
                        in.skipBytes(8);
                        size = 8;
                        break;
                    case CLASS: case 8: case 16: case 19: case 20:
                        first[index] = in.readUnsignedShort();
                        size = 2;
                        break;
                    case 15:
                        in.skipBytes(3);
                        size = 3;
                        break;
                    case 9: case METHOD: case 11: case 12: case 17: case 18:
                        first[index] = in.readUnsignedShort();
                        second[index] = in.readUnsignedShort();
                        size = 4;
                        break;
                    default:
                        throw new IOException("unknown constant " + tag[index]);
                }
                position += 1 + size;
                // A long or a double takes two places.
                if (tag[index] == 5 || tag[index] == 6) index++;
            }
            end = position;
        }

        /** The class file with entries added at the end of its pool (their bytes, how many) and the rest as it is in changed. */
        byte[] with(byte[] changed, byte[] added, int entries) {
            ByteArrayOutputStream whole = new ByteArrayOutputStream();
            whole.write(changed, 0, 8);
            whole.write((count + entries) >> 8);
            whole.write(count + entries);
            whole.write(changed, 10, end - 10);
            whole.write(added, 0, added.length);
            whole.write(changed, end, changed.length - end);
            return whole.toByteArray();
        }
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args[0]);
        write(out, "sun/nio/fs/WindowsLinkSupport", call(read("sun/nio/fs/WindowsLinkSupport"), "sun/nio/fs/WindowsNativeDispatcher", "FindFirstFile", "sun/nio/fs/MmtListed"));
        if (args.length > 1 && args[1].equals("files")) {
            write(out, "java/io/DefaultFileSystem", named(read("java/io/DefaultFileSystem"), "java/io/WinNTFileSystem", "java/io/MmtFileSystem"));
            write(out, "java/io/WinNTFileSystem", open(read("java/io/WinNTFileSystem")));
        }
    }

    static byte[] read(String name) throws IOException {
        try (InputStream in = Object.class.getResourceAsStream("/" + name + ".class")) {
            if (in == null) throw new IOException("this Java has no " + name);
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read; (read = in.read(buffer)) > 0;) all.write(buffer, 0, read);
            return all.toByteArray();
        }
    }

    static void write(File out, String name, byte[] bytes) throws IOException {
        File target = new File(out, name + ".class");
        target.getParentFile().mkdirs();
        try (FileOutputStream file = new FileOutputStream(target)) {
            file.write(bytes);
        }
    }

    /** The one call of from.call in a class goes to to.call instead: same name, same parameters. */
    static byte[] call(byte[] original, String from, String call, String to) throws IOException {
        Pool pool = new Pool(original);
        byte[] changed = original.clone();
        int moved = 0;
        for (int index = 1; index < pool.count; index++) {
            // A method reference: its class, then its name and type.
            if (pool.tag[index] != METHOD || !from.equals(pool.text[pool.first[pool.first[index]]]) || !call.equals(pool.text[pool.first[pool.second[index]]])) continue;
            // Two entries are added, the name and then the class: the class is the second.
            changed[pool.at[index] + 1] = (byte) ((pool.count + 1) >> 8);
            changed[pool.at[index] + 2] = (byte) (pool.count + 1);
            moved++;
        }
        if (moved != 1) throw new IOException("this Java calls " + from + "." + call + " " + moved + " times there, not once");
        ByteArrayOutputStream added = new ByteArrayOutputStream();
        DataOutputStream entries = new DataOutputStream(added);
        entries.writeByte(UTF8);
        entries.writeUTF(to);
        entries.writeByte(CLASS);
        entries.writeShort(pool.count);
        return pool.with(changed, added.toByteArray(), 2);
    }

    /** Every use of the class from in a class file is of the class to. */
    static byte[] named(byte[] original, String from, String to) throws IOException {
        Pool pool = new Pool(original);
        byte[] changed = original.clone();
        int moved = 0;
        for (int index = 1; index < pool.count; index++) {
            if (pool.tag[index] != CLASS || !from.equals(pool.text[pool.first[index]])) continue;
            changed[pool.at[index] + 1] = (byte) (pool.count >> 8);
            changed[pool.at[index] + 2] = (byte) pool.count;
            moved++;
        }
        if (moved != 1) throw new IOException("this Java names " + from + " " + moved + " times there, not once");
        ByteArrayOutputStream added = new ByteArrayOutputStream();
        DataOutputStream entries = new DataOutputStream(added);
        entries.writeByte(UTF8);
        entries.writeUTF(to);
        return pool.with(changed, added.toByteArray(), 1);
    }

    /** The class with its final flag off: the two bytes after the constant pool are its flags. */
    static byte[] open(byte[] original) throws IOException {
        Pool pool = new Pool(original);
        byte[] changed = original.clone();
        int flags = ((changed[pool.end] & 0xFF) << 8) | (changed[pool.end + 1] & 0xFF);
        flags &= ~FINAL;
        changed[pool.end] = (byte) (flags >> 8);
        changed[pool.end + 1] = (byte) flags;
        return changed;
    }

    static int utfLength(String value) {
        int length = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            length += c >= 1 && c <= 0x7F ? 1 : c <= 0x7FF ? 2 : 3;
        }
        return length;
    }
}

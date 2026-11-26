import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Run once on each Java a walled start uses: writes that Java's own sun.nio.fs.WindowsLinkSupport with its one lookup
 * of a folder's name (WindowsNativeDispatcher.FindFirstFile) pointed at MmtListed.FindFirstFile, which goes on where
 * the folder above may not be listed. Only the constant pool changes: one class name is added and the call's class
 * reference is moved to it. Written for Java 8, the oldest a game runs on.
 * Usage: MmtPathPatch OUT_FOLDER
 */
public final class MmtPathPatch {

    static final String HOST = "sun/nio/fs/WindowsLinkSupport", FROM = "sun/nio/fs/WindowsNativeDispatcher", CALL = "FindFirstFile", TO = "sun/nio/fs/MmtListed";

    public static void main(String[] args) throws Exception {
        byte[] original;
        try (InputStream in = Object.class.getResourceAsStream("/" + HOST + ".class")) {
            if (in == null) throw new IllegalStateException("this Java has no " + HOST);
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read; (read = in.read(buffer)) > 0;) all.write(buffer, 0, read);
            original = all.toByteArray();
        }
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(original));
        in.readInt();
        in.readInt();
        int count = in.readUnsignedShort();
        String[] text = new String[count];
        int[] first = new int[count], second = new int[count], tag = new int[count], at = new int[count];
        int position = 10;
        for (int index = 1; index < count; index++) {
            at[index] = position;
            tag[index] = in.readUnsignedByte();
            position++;
            switch (tag[index]) {
                case 1:
                    text[index] = in.readUTF();
                    position += 2 + utfLength(text[index]);
                    break;
                case 3: case 4:
                    in.skipBytes(4);
                    position += 4;
                    break;
                case 5: case 6:
                    in.skipBytes(8);
                    position += 8;
                    index++;
                    break;
                case 7: case 8: case 16: case 19: case 20:
                    first[index] = in.readUnsignedShort();
                    position += 2;
                    break;
                case 15:
                    in.skipBytes(3);
                    position += 3;
                    break;
                case 9: case 10: case 11: case 12: case 17: case 18:
                    first[index] = in.readUnsignedShort();
                    second[index] = in.readUnsignedShort();
                    position += 4;
                    break;
                default:
                    throw new IllegalStateException("unknown constant " + tag[index]);
            }
        }
        int end = position, moved = 0;
        byte[] patched = new byte[original.length];
        System.arraycopy(original, 0, patched, 0, original.length);
        for (int index = 1; index < count; index++) {
            // A method reference: its class, then its name and type.
            if (tag[index] != 10 || !FROM.equals(text[first[first[index]]]) || !CALL.equals(text[first[second[index]]])) continue;
            // The new class entry is the second of the two appended: count is the name, count + 1 the class.
            patched[at[index] + 1] = (byte) ((count + 1) >> 8);
            patched[at[index] + 2] = (byte) (count + 1);
            moved++;
        }
        if (moved != 1) throw new IllegalStateException(HOST + " calls " + CALL + " " + moved + " times in this Java, not once");
        ByteArrayOutputStream added = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(added);
        out.writeByte(1);
        out.writeUTF(TO);
        out.writeByte(7);
        out.writeShort(count);
        ByteArrayOutputStream whole = new ByteArrayOutputStream();
        whole.write(patched, 0, 8);
        whole.write((count + 2) >> 8);
        whole.write(count + 2);
        whole.write(patched, 10, end - 10);
        whole.write(added.toByteArray());
        whole.write(patched, end, patched.length - end);
        File target = new File(args[0], HOST + ".class");
        target.getParentFile().mkdirs();
        try (FileOutputStream file = new FileOutputStream(target)) {
            file.write(whole.toByteArray());
        }
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

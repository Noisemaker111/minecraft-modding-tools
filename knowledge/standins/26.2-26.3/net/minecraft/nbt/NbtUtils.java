package mmt.standin.net.minecraft.nbt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.EndTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;

/**
 * 26.2's NbtUtils.prettyPrint, which 26.3 removed.
 *
 * Read from both releases: 26.2's prettyPrint(tag, withBinaryBlobs) and prettyPrint(builder, tag, indent, withBinaryBlobs)
 * wrote a tag as indented text for debugging: a primitive as its toString(); an end tag as nothing; a list as
 * "list[n] [" with its elements one per line; a compound as "{" with its keys sorted, quoted and padded to the longest;
 * a byte, int or long array as "byte[n] {", "int[n] {", "long[n] {" with its values in hex, 16 to a line (or a note that
 * they were skipped); each nesting level indenting two spaces from the start of the line. 26.3 dropped both; every tag
 * type and accessor they read (keySet, get, size, getAsByteArray, getAsIntArray, getAsLongArray) is still there.
 *
 * Agrees with NeoForge's 26.2 -> 26.3 primer (https://github.com/neoforged/.github/blob/main/primers/26.3/index.md, CC BY 4.0),
 * whose list of removed members names "net.minecraft.nbt.NbtUtils ... prettyPrint".
 */
public final class NbtUtils {

    private NbtUtils() {}

    public static String prettyPrint(Tag tag, boolean withBinaryBlobs) {
        return prettyPrint(new StringBuilder(), tag, 0, withBinaryBlobs).toString();
    }

    public static StringBuilder prettyPrint(StringBuilder out, Tag tag, int indent, boolean withBinaryBlobs) {
        if (tag instanceof EndTag) {
            return out;
        }
        if (tag instanceof ByteArrayTag bytes) {
            byte[] values = bytes.getAsByteArray();
            String[] hex = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                hex[i] = String.format(Locale.ROOT, "0x%02X", values[i] & 255);
            }
            return array(out, "byte", hex, indent, withBinaryBlobs);
        }
        if (tag instanceof IntArrayTag ints) {
            int[] values = ints.getAsIntArray();
            int width = 0;
            for (int v : values) {
                width = Math.max(width, String.format(Locale.ROOT, "%X", v).length());
            }
            String[] hex = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                hex[i] = String.format(Locale.ROOT, "0x%0" + width + "X", values[i]);
            }
            return array(out, "int", hex, indent, withBinaryBlobs);
        }
        if (tag instanceof LongArrayTag longs) {
            long[] values = longs.getAsLongArray();
            int width = 0;
            for (long v : values) {
                width = Math.max(width, String.format(Locale.ROOT, "%X", v).length());
            }
            String[] hex = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                hex[i] = String.format(Locale.ROOT, "0x%0" + width + "X", values[i]);
            }
            return array(out, "long", hex, indent, withBinaryBlobs);
        }
        if (tag instanceof ListTag list) {
            int size = list.size();
            indent(indent, out).append("list[").append(size).append("] [");
            if (size != 0) {
                out.append('\n');
            }
            for (int i = 0; i < size; i++) {
                if (i != 0) {
                    out.append(",\n");
                }
                indent(indent + 1, out);
                prettyPrint(out, list.get(i), indent + 1, withBinaryBlobs);
            }
            if (size != 0) {
                out.append('\n');
            }
            return indent(indent, out).append(']');
        }
        if (tag instanceof CompoundTag compound) {
            List<String> keys = new ArrayList<>(compound.keySet());
            Collections.sort(keys);
            indent(indent, out).append('{');
            if (out.length() - out.lastIndexOf("\n") > 2 * (indent + 1)) {
                out.append('\n');
                indent(indent + 1, out);
            }
            int longest = 0;
            for (String key : keys) {
                longest = Math.max(longest, key.length());
            }
            for (int i = 0; i < keys.size(); i++) {
                if (i != 0) {
                    out.append(",\n");
                }
                String key = keys.get(i);
                indent(indent + 1, out).append('"').append(key).append('"').append(" ".repeat(longest - key.length())).append(": ");
                prettyPrint(out, compound.get(key), indent + 1, withBinaryBlobs);
            }
            if (!keys.isEmpty()) {
                out.append('\n');
            }
            return indent(indent, out).append('}');
        }
        return out.append(tag);
    }

    private static StringBuilder array(StringBuilder out, String kind, String[] hex, int indent, boolean withBinaryBlobs) {
        indent(indent, out).append(kind).append('[').append(hex.length).append("] {\n");
        if (withBinaryBlobs) {
            indent(indent + 1, out);
            for (int i = 0; i < hex.length; i++) {
                if (i != 0) {
                    out.append(',');
                }
                if (i % 16 == 0 && i / 16 > 0) {
                    out.append('\n');
                    indent(indent + 1, out);
                } else if (i != 0) {
                    out.append(' ');
                }
                out.append(hex[i]);
            }
        } else {
            indent(indent + 1, out).append(" // Skipped, supply withBinaryBlobs true");
        }
        out.append('\n');
        return indent(indent, out).append('}');
    }

    private static StringBuilder indent(int indent, StringBuilder out) {
        int column = out.length() - (out.lastIndexOf("\n") + 1);
        for (int i = column; i < 2 * indent; i++) {
            out.append(' ');
        }
        return out;
    }
}

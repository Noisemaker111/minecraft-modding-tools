package mmt.standin.java.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * Java 17's java.util.HexFormat, for a release on an older Java, from the JDK's documented contract: "a formatter and
 * parser between bytes and hexadecimal strings ... optional delimiter, prefix and suffix, uppercase or lowercase". of()
 * has no delimiter, prefix or suffix and lowercase digits; ofDelimiter(d) the same with d between bytes. formatHex
 * writes each byte as prefix, two digits, suffix, with the delimiter between bytes; parseHex reads that back, throwing
 * IllegalArgumentException where the text is not in that form. The toHexDigits and fromHexDigits methods ignore the
 * delimiter, prefix and suffix.
 */
public final class HexFormat {

    private static final char[] LOWER = "0123456789abcdef".toCharArray();
    private static final char[] UPPER = "0123456789ABCDEF".toCharArray();
    private static final HexFormat HEX = new HexFormat("", "", "", false);

    private final String delimiter;
    private final String prefix;
    private final String suffix;
    private final boolean upper;

    private HexFormat(String delimiter, String prefix, String suffix, boolean upper) {
        this.delimiter = Objects.requireNonNull(delimiter, "delimiter");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.suffix = Objects.requireNonNull(suffix, "suffix");
        this.upper = upper;
    }

    public static HexFormat of() {
        return HEX;
    }

    public static HexFormat ofDelimiter(String delimiter) {
        return new HexFormat(delimiter, "", "", false);
    }

    public HexFormat withDelimiter(String delimiter) {
        return new HexFormat(delimiter, prefix, suffix, upper);
    }

    public HexFormat withPrefix(String prefix) {
        return new HexFormat(delimiter, prefix, suffix, upper);
    }

    public HexFormat withSuffix(String suffix) {
        return new HexFormat(delimiter, prefix, suffix, upper);
    }

    public HexFormat withUpperCase() {
        return new HexFormat(delimiter, prefix, suffix, true);
    }

    public HexFormat withLowerCase() {
        return new HexFormat(delimiter, prefix, suffix, false);
    }

    public String delimiter() {
        return delimiter;
    }

    public String prefix() {
        return prefix;
    }

    public String suffix() {
        return suffix;
    }

    public boolean isUpperCase() {
        return upper;
    }

    public String formatHex(byte[] bytes) {
        return formatHex(bytes, 0, bytes.length);
    }

    public String formatHex(byte[] bytes, int fromIndex, int toIndex) {
        StringBuilder out = new StringBuilder();
        formatHex(out, bytes, fromIndex, toIndex);
        return out.toString();
    }

    public <A extends Appendable> A formatHex(A out, byte[] bytes) {
        return formatHex(out, bytes, 0, bytes.length);
    }

    public <A extends Appendable> A formatHex(A out, byte[] bytes, int fromIndex, int toIndex) {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(bytes, "bytes");
        if (fromIndex < 0 || fromIndex > toIndex || toIndex > bytes.length) {
            throw new IndexOutOfBoundsException("Range [" + fromIndex + ", " + toIndex + ") out of bounds for length " + bytes.length);
        }
        try {
            for (int i = fromIndex; i < toIndex; i++) {
                if (i > fromIndex) {
                    out.append(delimiter);
                }
                out.append(prefix);
                out.append(toHighHexDigit(bytes[i]));
                out.append(toLowHexDigit(bytes[i]));
                out.append(suffix);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e.getMessage(), e);
        }
        return out;
    }

    public byte[] parseHex(CharSequence string) {
        return parseHex(string, 0, string.length());
    }

    public byte[] parseHex(CharSequence string, int fromIndex, int toIndex) {
        Objects.requireNonNull(string, "string");
        if (fromIndex < 0 || fromIndex > toIndex || toIndex > string.length()) {
            throw new IndexOutOfBoundsException("Range [" + fromIndex + ", " + toIndex + ") out of bounds for length " + string.length());
        }
        String s = string.subSequence(fromIndex, toIndex).toString();
        if (s.isEmpty()) {
            return new byte[0];
        }
        int each = prefix.length() + 2 + suffix.length();
        int stride = each + delimiter.length();
        if ((s.length() + delimiter.length()) % stride != 0) {
            throw new IllegalArgumentException("extra or missing delimiters or values consisting of prefix, two hexadecimal digits, and suffix");
        }
        byte[] out = new byte[(s.length() + delimiter.length()) / stride];
        for (int i = 0, at = 0; i < out.length; i++, at += stride) {
            if (i > 0 && !s.startsWith(delimiter, at - delimiter.length())) {
                throw new IllegalArgumentException("found: \"" + s.substring(at - delimiter.length(), at) + "\", expected: \"" + delimiter + "\"");
            }
            if (!s.startsWith(prefix, at) || !s.startsWith(suffix, at + prefix.length() + 2)) {
                throw new IllegalArgumentException("prefix or suffix not found at index " + (fromIndex + at));
            }
            out[i] = (byte) fromHexDigits(s, at + prefix.length(), at + prefix.length() + 2);
        }
        return out;
    }

    public byte[] parseHex(char[] chars, int fromIndex, int toIndex) {
        return parseHex(new String(chars), fromIndex, toIndex);
    }

    public char toLowHexDigit(int value) {
        return (upper ? UPPER : LOWER)[value & 0xF];
    }

    public char toHighHexDigit(int value) {
        return (upper ? UPPER : LOWER)[value >> 4 & 0xF];
    }

    public <A extends Appendable> A toHexDigits(A out, byte value) {
        try {
            out.append(toHighHexDigit(value));
            out.append(toLowHexDigit(value));
        } catch (IOException e) {
            throw new UncheckedIOException(e.getMessage(), e);
        }
        return out;
    }

    public String toHexDigits(byte value) {
        return digits(value, 2);
    }

    public String toHexDigits(char value) {
        return digits(value, 4);
    }

    public String toHexDigits(short value) {
        return digits(value, 4);
    }

    public String toHexDigits(int value) {
        return digits(value, 8);
    }

    public String toHexDigits(long value) {
        return digits(value, 16);
    }

    public String toHexDigits(long value, int digits) {
        if (digits < 0 || digits > 16) {
            throw new IllegalArgumentException("number of digits: " + digits);
        }
        return digits(value, digits);
    }

    private String digits(long value, int count) {
        char[] out = new char[count];
        for (int i = count - 1; i >= 0; i--) {
            out[i] = toLowHexDigit((int) value);
            value >>>= 4;
        }
        return new String(out);
    }

    public static boolean isHexDigit(int ch) {
        return ch >= '0' && ch <= '9' || ch >= 'a' && ch <= 'f' || ch >= 'A' && ch <= 'F';
    }

    public static int fromHexDigit(int ch) {
        if (ch >= '0' && ch <= '9') {
            return ch - '0';
        }
        if (ch >= 'a' && ch <= 'f') {
            return ch - 'a' + 10;
        }
        if (ch >= 'A' && ch <= 'F') {
            return ch - 'A' + 10;
        }
        throw new NumberFormatException("not a hexadecimal digit: \"" + (char) ch + "\" = " + ch);
    }

    public static int fromHexDigits(CharSequence string) {
        return fromHexDigits(string, 0, string.length());
    }

    public static int fromHexDigits(CharSequence string, int fromIndex, int toIndex) {
        if (toIndex - fromIndex > 8) {
            throw new IllegalArgumentException("string length greater than 8: " + (toIndex - fromIndex));
        }
        return (int) fromHexDigitsToLong(string, fromIndex, toIndex);
    }

    public static long fromHexDigitsToLong(CharSequence string) {
        return fromHexDigitsToLong(string, 0, string.length());
    }

    public static long fromHexDigitsToLong(CharSequence string, int fromIndex, int toIndex) {
        Objects.requireNonNull(string, "string");
        if (fromIndex < 0 || fromIndex > toIndex || toIndex > string.length()) {
            throw new IndexOutOfBoundsException("Range [" + fromIndex + ", " + toIndex + ") out of bounds for length " + string.length());
        }
        if (toIndex - fromIndex > 16) {
            throw new IllegalArgumentException("string length greater than 16: " + (toIndex - fromIndex));
        }
        long value = 0;
        for (int i = fromIndex; i < toIndex; i++) {
            value = value << 4 | fromHexDigit(string.charAt(i));
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof HexFormat)) {
            return false;
        }
        HexFormat other = (HexFormat) o;
        return upper == other.upper && delimiter.equals(other.delimiter) && prefix.equals(other.prefix) && suffix.equals(other.suffix);
    }

    @Override
    public int hashCode() {
        return Objects.hash(delimiter, prefix, suffix, upper);
    }

    @Override
    public String toString() {
        return (upper ? "uppercase" : "lowercase") + ", delimiter: \"" + delimiter + "\", prefix: \"" + prefix + "\", suffix: \"" + suffix + "\"";
    }
}

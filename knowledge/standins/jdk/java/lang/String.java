package mmt.standin.java.lang;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.stream.Stream;

/**
 * java.lang.String members Java 8 lacks, from the JDK's documented contract. Whitespace is what
 * java.lang.Character.isWhitespace(int) says of a code point. isBlank() (Java 11) "true if the string is empty or contains only white
 * space codepoints"; strip(), stripLeading(), stripTrailing() (Java 11) the string with that white space removed from both
 * ends, the start or the end; repeat(count) (Java 11) the string count times, IllegalArgumentException for a negative
 * count ("count is negative: " + count); lines() (Java 11) the lines "separated by line terminators" (\n, \r, \r\n), a
 * last empty line left out, as BufferedReader.readLine splits them; formatted(args) (Java 15) String.format(this, args);
 * indent(n) (Java 12) each line given n more spaces or (n < 0) up to -n fewer leading white space characters, every line
 * ending in \n. splitWithDelimiters(regex, limit) (Java 21) the parts split(regex, limit) gives with each delimiter that
 * split them between them: "the array contains ... alternating substrings and matching delimiters", a zero-width match
 * at the start splitting off no empty leading part, at most limit parts when limit is positive (the last holding the
 * rest), and trailing empty strings (parts or delimiters) dropped when limit is zero.
 */
public final class String {

    private String() {}

    public static boolean isBlank(java.lang.String self) {
        return start(self) == self.length();
    }

    public static java.lang.String strip(java.lang.String self) {
        int from = start(self);
        return from == self.length() ? "" : self.substring(from, end(self));
    }

    public static java.lang.String stripLeading(java.lang.String self) {
        return self.substring(start(self));
    }

    public static java.lang.String stripTrailing(java.lang.String self) {
        return self.substring(0, end(self));
    }

    static int start(java.lang.String s) {
        int i = 0;
        while (i < s.length()) {
            int c = s.codePointAt(i);
            if (!java.lang.Character.isWhitespace(c)) {
                break;
            }
            i += java.lang.Character.charCount(c);
        }
        return i;
    }

    static int end(java.lang.String s) {
        int i = s.length();
        while (i > 0) {
            int c = s.codePointBefore(i);
            if (!java.lang.Character.isWhitespace(c)) {
                break;
            }
            i -= java.lang.Character.charCount(c);
        }
        return i;
    }

    public static java.lang.String repeat(java.lang.String self, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count is negative: " + count);
        }
        if (count == 1) {
            return self;
        }
        if (self.isEmpty() || count == 0) {
            return "";
        }
        if ((long) self.length() * count > Integer.MAX_VALUE - 8) {
            throw new OutOfMemoryError("Required length exceeds implementation limit");
        }
        StringBuilder out = new StringBuilder(self.length() * count);
        for (int i = 0; i < count; i++) {
            out.append(self);
        }
        return out.toString();
    }

    public static Stream<java.lang.String> lines(java.lang.String self) {
        return new BufferedReader(new StringReader(self)).lines();
    }

    public static java.lang.String formatted(java.lang.String self, Object... args) {
        return java.lang.String.format(self, args);
    }

    public static java.lang.String indent(java.lang.String self, int n) {
        if (self.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        lines(self).forEach(line -> {
            if (n > 0) {
                for (int i = 0; i < n; i++) {
                    out.append(' ');
                }
                out.append(line);
            } else if (n < 0) {
                int i = 0;
                while (i < -n && i < line.length() && java.lang.Character.isWhitespace(line.charAt(i))) {
                    i++;
                }
                out.append(line, i, line.length());
            } else {
                out.append(line);
            }
            out.append('\n');
        });
        return out.toString();
    }

    public static java.lang.String[] splitWithDelimiters(java.lang.String self, java.lang.String regex, int limit) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(self);
        java.util.List<java.lang.String> out = new java.util.ArrayList<>();
        int from = 0, parts = 0;
        while ((limit <= 0 || parts < limit - 1) && m.find()) {
            if (m.end() == 0) {
                continue; // a zero-width match at the start splits off no empty leading part
            }
            out.add(self.substring(from, m.start()));
            out.add(m.group());
            from = m.end();
            parts++;
        }
        if (parts == 0) {
            return new java.lang.String[] { self };
        }
        out.add(self.substring(from));
        int size = out.size();
        if (limit == 0) {
            while (size > 0 && out.get(size - 1).isEmpty()) {
                size--;
            }
        }
        return out.subList(0, size).toArray(new java.lang.String[0]);
    }
}

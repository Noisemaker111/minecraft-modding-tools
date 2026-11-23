import java.util.*;
import java.util.regex.*;

/** Reading Java source as text: where a call's arguments, a string or a block ends. */
final class JavaText {

    /** Past a call chain (.a(..).b(..)) that starts at AT, within LIMIT; -1 when a call does not close. */
    static int chainEnd(String text, int at, int limit) {
        if (at <= 0) return -1;
        while (true) {
            Matcher next = Pattern.compile("\\G\\s*\\.\\s*[A-Za-z_$][\\w$]*\\s*\\(").matcher(text);
            next.region(at, limit);
            if (!next.find()) return at;
            int close = closeParen(text, next.end() - 1);
            if (close < 0 || close >= limit) return at;
            at = close + 1;
        }
    }

    /** The index of the parenthesis that closes the one at OPEN, skipping strings and characters; -1 if none. */
    static int closeParen(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"' || ch == '\'') {
                for (i++; i < text.length() && text.charAt(i) != ch; i++) if (text.charAt(i) == '\\') i++;
            } else if (ch == '(') depth++;
            else if (ch == ')' && --depth == 0) return i;
        }
        return -1;
    }

    /** Top-level argument spans between FROM and TO (exclusive), split on commas outside brackets and strings. */
    static List<int[]> splitArgs(String text, int from, int to) {
        List<int[]> out = new ArrayList<>();
        int depth = 0, start = from;
        for (int i = from; i < to; i++) {
            char ch = text.charAt(i);
            if (ch == '"' || ch == '\'') {
                for (i++; i < to && text.charAt(i) != ch; i++) if (text.charAt(i) == '\\') i++;
            } else if ("([{".indexOf(ch) >= 0) depth++;
            else if (")]}".indexOf(ch) >= 0) depth--;
            else if (ch == ',' && depth == 0) {
                out.add(new int[] { start, i });
                start = i + 1;
            }
        }
        if (to > from && !text.substring(start, to).isBlank()) out.add(new int[] { start, to });
        return out;
    }

    /**
     * The top-level arguments of the call whose '(' is at open, as [start, end) spans: nested calls, lambda bodies, string
     * and char literals are skipped over. Empty for f(); what was found so far when the call never closes.
     */
    static List<int[]> arguments(String text, int open) {
        List<int[]> spans = new ArrayList<>();
        int depth = 0, start = open + 1;
        char quote = 0;
        for (int at = open + 1; at < text.length(); at++) {
            char c = text.charAt(at);
            if (quote != 0) {
                if (c == '\\') at++;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') quote = c;
            else if (c == '(' || c == '[' || c == '{') depth++;
            else if ((c == ')' || c == ']' || c == '}') && depth > 0) depth--;
            else if (c == ')' || (c == ',' && depth == 0)) {
                if (c == ',' || !text.substring(start, at).isBlank() || !spans.isEmpty()) spans.add(
                    new int[] { start, at }
                );
                start = at + 1;
                if (c == ')') break;
            }
        }
        return spans;
    }

    static int closingBrace(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') i = stringEnd(text, i);
            else if (ch == '{') depth++;
            else if (ch == '}' && --depth == 0) return i;
        }
        return -1;
    }

    /** The index just after the ';' that ends the statement starting at from, outside brackets and strings; -1 if none. */
    static int statementEnd(String text, int from) {
        int depth = 0;
        for (int i = from; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') i = stringEnd(text, i);
            else if (ch == '(' || ch == '{' || ch == '[') depth++;
            else if (ch == ')' || ch == '}' || ch == ']') depth--;
            else if (ch == ';' && depth <= 0) return i + 1;
        }
        return -1;
    }

    /** The index of the ')' closing the '(' at open; -1 if none. */
    static int closingParen(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') i = stringEnd(text, i);
            else if (ch == '(') depth++;
            else if (ch == ')' && --depth == 0) return i;
        }
        return -1;
    }

    static int stringEnd(String text, int quote) {
        for (int i = quote + 1; i < text.length(); i++) {
            if (text.charAt(i) == '\\') i++;
            else if (text.charAt(i) == '"') return i;
        }
        return text.length() - 1;
    }

    /** An argument list split at its top-level commas, each part with its own whitespace. */
    static List<String> topLevelArguments(String args) {
        List<String> parts = new ArrayList<>();
        int depth = 0, from = 0;
        for (int i = 0; i < args.length(); i++) {
            char ch = args.charAt(i);
            if (ch == '"') i = stringEnd(args, i);
            else if (ch == '(' || ch == '{' || ch == '[') depth++;
            else if (ch == ')' || ch == '}' || ch == ']') depth--;
            else if (ch == ',' && depth == 0) {
                parts.add(args.substring(from, i));
                from = i + 1;
            }
        }
        parts.add(args.substring(from));
        return parts;
    }

    static String addImport(String text, String name) {
        if (text.contains("import " + name + ";")) return text;
        Matcher header = Pattern.compile("(?m)^package ([^;]+);").matcher(text);
        if (header.find() && name.equals(header.group(1).strip() + "." + simpleName(name))) return text;
        return header.find(0)
            ? text.substring(0, header.end()) + "\nimport " + name + ";" + text.substring(header.end())
            : "import " + name + ";\n" + text;
    }

    /** Where the receiver of the call whose '.' is at dot starts: a chain of names, calls and indexes such as a.b().c[0]. */
    static int receiverStart(String text, int dot) {
        int begin = dot;
        while (true) {
            int end = begin - 1;
            while (end >= 0 && Character.isWhitespace(text.charAt(end))) end--;
            if (end < 0) return begin;
            char c = text.charAt(end);
            int start;
            if (c == ')' || c == ']') {
                int depth = 0, at = end;
                for (; at >= 0; at--) {
                    char d = text.charAt(at);
                    if (d == ')' || d == ']') depth++;
                    else if ((d == '(' || d == '[') && --depth == 0) break;
                }
                if (at < 0) return begin;
                start = at;
                int name = start - 1;
                while (name >= 0 && Character.isJavaIdentifierPart(text.charAt(name))) name--;
                if (name + 1 < start) start = name + 1; // a call or an index on a name: foo(...), foo[...]
                else if (c == ']') {
                    begin = start;
                    continue;
                }
            } else if (Character.isJavaIdentifierPart(c)) {
                start = end;
                while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
            } else return begin;
            if (
                text.startsWith("new", Math.max(0, start - 4)) &&
                start >= 4 &&
                Character.isWhitespace(text.charAt(start - 1)) &&
                (start == 4 || !Character.isJavaIdentifierPart(text.charAt(start - 5)))
            ) start -= 4;
            begin = start;
            int previous = start - 1;
            while (previous >= 0 && Character.isWhitespace(text.charAt(previous))) previous--;
            if (previous >= 0 && text.charAt(previous) == '.') begin = previous;
            else return begin;
        }
    }

    static String simpleName(String name) {
        String s = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
        return s.contains("$") ? s.substring(s.lastIndexOf('$') + 1) : s;
    }
}

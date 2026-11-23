import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

// ------------------------------------------------------------------ learn from real ports

/**
 * Reads unified diffs of real ports of the same jump (other mods' "old version branch ->
 * new version branch") and keeps the line rewrites that touch something the map says changed:
 * the smallest differing span of each changed line pair, e.g.
 * "RenderLayer::getGuiTextured" -> "RenderPipelines.GUI_TEXTURED", with the imports that file
 * added for it. Writes one rule per line, most-seen first.
 */
final class MapLearn {

    static final Pattern TOKEN = Pattern.compile("\\w+|::|->|\\S");
    static final String SEP = "\u0001";

    static void run(Path mapFile, List<String> diffs, Path out) throws IOException {
        MapKnown k = MapKnown.load(mapFile);
        Set<String> changed = new HashSet<>();
        changed.addAll(k.sigNames);
        changed.addAll(k.methodGone);
        changed.addAll(k.fieldGone);
        changed.addAll(k.methodTo.keySet());
        changed.addAll(k.fieldTo.keySet());
        changed.addAll(k.classBySimple.keySet());
        changed.addAll(k.removedClassBySimple.keySet());
        Map<String, int[]> seen = new LinkedHashMap<>();
        Map<String, Set<String>> sources = new HashMap<>(), imports = new HashMap<>();
        for (String d : diffs) {
            Path p = Path.of(d);
            String source = p.getFileName().toString().replaceAll("\\.diff$", "");
            List<String> removed = new ArrayList<>(), added = new ArrayList<>(), fileImports = new ArrayList<>();
            List<String[]> pairsOfFile = new ArrayList<>();
            boolean java = false;
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line.startsWith("diff --git")) {
                    flush(removed, added, pairsOfFile);
                    record(pairsOfFile, fileImports, changed, seen, sources, imports, source);
                    pairsOfFile.clear();
                    fileImports.clear();
                    java = line.endsWith(".java");
                    continue;
                }
                if (!java || line.startsWith("+++") || line.startsWith("---")) {
                    continue;
                }
                if (line.startsWith("-")) {
                    if (!added.isEmpty()) {
                        flush(removed, added, pairsOfFile);
                    }
                    removed.add(line.substring(1));
                } else if (line.startsWith("+")) {
                    String a = line.substring(1);
                    if (a.startsWith("import ")) {
                        fileImports.add(a.replaceAll("^import\\s+|;\\s*$", ""));
                    }
                    added.add(a);
                } else {
                    flush(removed, added, pairsOfFile);
                }
            }
            flush(removed, added, pairsOfFile);
            record(pairsOfFile, fileImports, changed, seen, sources, imports, source);
        }
        List<String> keys = new ArrayList<>(seen.keySet());
        keys.sort((a, b) -> seen.get(b)[0] - seen.get(a)[0]);
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            String[] ft = key.split(SEP, 2);
            sb
                .append("{ \"from\": ")
                .append(VersionMap.Diff.q(ft[0]))
                .append(", \"to\": ")
                .append(VersionMap.Diff.q(ft[1]))
                .append(", \"seen\": ")
                .append(seen.get(key)[0])
                .append(", \"sources\": [")
                .append(String.join(", ", sources.get(key).stream().map(VersionMap.Diff::q).toList()))
                .append("]")
                .append(", \"imports\": [")
                .append(String.join(", ", imports.getOrDefault(key, Set.of()).stream().map(VersionMap.Diff::q).toList()))
                .append("] }\n");
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("learned " + keys.size() + " rewrite(s) from " + diffs.size() + " port(s) -> " + out);
        keys
            .stream()
            .limit(15)
            .forEach(x -> System.out.println("  " + seen.get(x)[0] + "x  " + x.replace(SEP, "  ->  ")));
    }

    /** Pairs each removed line with its most similar added line. */
    static void flush(List<String> removed, List<String> added, List<String[]> pairs) {
        Set<Integer> used = new HashSet<>();
        for (String r : removed) {
            int best = -1;
            double score = 0.5;
            for (int i = 0; i < added.size(); i++) {
                if (used.contains(i)) {
                    continue;
                }
                double s = similarity(r, added.get(i));
                if (s > score) {
                    score = s;
                    best = i;
                }
            }
            if (best >= 0) {
                used.add(best);
                pairs.add(new String[] { r, added.get(best) });
            }
        }
        removed.clear();
        added.clear();
    }

    static double similarity(String a, String b) {
        Set<String> ta = new HashSet<>(tokens(a)), tb = new HashSet<>(tokens(b));
        if (ta.isEmpty() || tb.isEmpty()) {
            return 0;
        }
        Set<String> both = new HashSet<>(ta);
        both.retainAll(tb);
        Set<String> any = new HashSet<>(ta);
        any.addAll(tb);
        return (double) both.size() / any.size();
    }

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(s);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    static void record(
        List<String[]> pairs,
        List<String> fileImports,
        Set<String> changed,
        Map<String, int[]> seen,
        Map<String, Set<String>> sources,
        Map<String, Set<String>> imports,
        String source
    ) {
        for (String[] pr : pairs) {
            String[] span = span(pr[0], pr[1]);
            if (span == null) {
                continue;
            }
            boolean anchored = false;
            for (String t : tokens(span[0])) {
                if (changed.contains(t)) {
                    anchored = true;
                    break;
                }
            }
            if (!anchored || span[0].contains("\"") || span[0].length() > 80 || span[1].length() > 100) {
                continue;
            }
            // Bare names lose their declaring type; published mappings already cover certain renames.
            // Keep self-contained qualified expressions, never arbitrary fragments of a mod's code.
            if (
                !span[0].matches("[A-Z]\\w*(?:\\.|::).*") ||
                !span[1].matches("[A-Z]\\w*(?:\\.|::).*") ||
                !balanced(span[0]) ||
                !balanced(span[1])
            ) {
                continue;
            }
            if (
                fileImports.stream().anyMatch(im -> im.contains(".impl.") && tokens(span[1]).contains(VersionMap.simple(im)))
            ) {
                continue;
            }
            if (introducesLocalName(span, fileImports)) {
                continue;
            }
            String key = span[0] + SEP + span[1];
            seen.computeIfAbsent(key, x -> new int[1])[0]++;
            sources.computeIfAbsent(key, x -> new TreeSet<>()).add(source);
            for (String im : fileImports) {
                String simpleName = im.substring(im.lastIndexOf('.') + 1);
                if (Pattern.compile("\\b" + Pattern.quote(simpleName) + "\\b").matcher(span[1]).find()) {
                    imports.computeIfAbsent(key, x -> new TreeSet<>()).add(im);
                }
            }
        }
    }

    static final Set<String> KEYWORDS = Set.of(
        "new",
        "this",
        "super",
        "null",
        "true",
        "false",
        "return",
        "instanceof",
        "int",
        "long",
        "float",
        "double",
        "boolean",
        "byte",
        "char",
        "short",
        "void",
        "var",
        "final",
        "class"
    );

    /**
     * True when the new side names something the target file cannot see: not in the old side,
     * not imported by that port, not reached through "." or "::". Those come from that mod's own
     * code (a local constant, a helper), so the rewrite would not compile anywhere else.
     */
    static boolean introducesLocalName(String[] span, List<String> fileImports) {
        Set<String> old = new HashSet<>(tokens(span[0]));
        Set<String> imported = new HashSet<>();
        for (String im : fileImports) {
            imported.add(im.substring(im.lastIndexOf('.') + 1));
        }
        List<String> t = tokens(span[1]);
        for (int i = 0; i < t.size(); i++) {
            String x = t.get(i);
            if (x.equals("this") || x.equals("super")) {
                return true;
            }
            if (!x.matches("[A-Za-z_]\\w*") || old.contains(x) || imported.contains(x) || KEYWORDS.contains(x)) {
                continue;
            }
            if (i > 0 && (t.get(i - 1).equals(".") || t.get(i - 1).equals("::"))) {
                continue;
            }
            return true;
        }
        return false;
    }

    static boolean balanced(String expression) {
        Deque<String> open = new ArrayDeque<>();
        for (String t : tokens(expression)) {
            if (t.equals("(") || t.equals("[")) {
                open.push(t);
            }
            if (t.equals(")") || t.equals("]")) {
                if (open.isEmpty() || !open.pop().equals(t.equals(")") ? "(" : "[")) {
                    return false;
                }
            }
        }
        return open.isEmpty();
    }

    /** The smallest differing source span between two lines, cut on token boundaries. */
    static String[] span(String a, String b) {
        List<int[]> pa = positions(a), pb = positions(b);
        int pre = 0;
        while (pre < pa.size() && pre < pb.size() && tok(a, pa.get(pre)).equals(tok(b, pb.get(pre)))) {
            pre++;
        }
        int suf = 0;
        while (
            suf < pa.size() - pre &&
            suf < pb.size() - pre &&
            tok(a, pa.get(pa.size() - 1 - suf)).equals(tok(b, pb.get(pb.size() - 1 - suf)))
        ) {
            suf++;
        }
        if (pre == pa.size() && pre == pb.size()) {
            return null;
        }
        if (pre >= pa.size() - suf) {
            return null;
        }
        int as = pa.get(pre)[0], ae = pa.get(pa.size() - 1 - suf)[1];
        String na = a.substring(as, ae);
        String nb = pre < pb.size() - suf ? b.substring(pb.get(pre)[0], pb.get(pb.size() - 1 - suf)[1]) : "";
        return new String[] { na, nb };
    }

    static List<int[]> positions(String s) {
        List<int[]> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(s);
        while (m.find()) {
            out.add(new int[] { m.start(), m.end() });
        }
        return out;
    }

    static String tok(String s, int[] p) {
        return s.substring(p[0], p[1]);
    }
}

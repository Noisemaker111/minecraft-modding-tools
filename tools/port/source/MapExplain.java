import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

// ------------------------------------------------------------------ explain a build log

final class MapExplain {

    static String verdict(VersionMap.Err e, MapKnown k, StringBuilder detail) {
        String sym = e.symbol() == null ? "" : e.symbol();
        String msg = e.message();
        if (msg.startsWith("package ") && msg.endsWith(" does not exist")) {
            return k.packages.containsKey(msg.substring(8, msg.length() - 15))
                ? "mechanical: package moved"
                : "removed: package";
        }
        if (msg.equals("cannot find symbol") && sym.startsWith("class ")) {
            String c = sym.substring(6).trim();
            detail.append(c);
            if (k.classBySimple.containsKey(c)) {
                detail.append(" -> ").append(k.classBySimple.get(c));
                return "mechanical: class renamed/moved";
            }
            return k.removedClassBySimple.containsKey(c) ? "removed: class" : "unknown symbol: class";
        }
        if (msg.equals("cannot find symbol") && sym.startsWith("method ")) {
            String n = sym.substring(7, sym.indexOf('('));
            detail.append(n);
            String to = MapKnown.pick(k.methodTo.get(n), e.location());
            if (to != null) {
                detail.append(" -> ").append(to);
                return "mechanical: method renamed";
            }
            if (k.methodTo.containsKey(n)) {
                return "renamed, target ambiguous";
            }
            return k.methodGone.contains(n) ? "removed: method" : "unknown symbol: method";
        }
        if (msg.equals("cannot find symbol") && sym.startsWith("variable ")) {
            String n = sym.substring(9).trim();
            detail.append(n);
            String to = MapKnown.pick(k.fieldTo.get(n), e.location());
            if (to != null) {
                detail.append(" -> ").append(to);
                return "mechanical: field renamed";
            }
            return k.fieldGone.contains(n) ? "removed: field" : "unknown symbol: variable";
        }
        Matcher am = Pattern.compile("method (\\w+) in (?:class|interface) (\\w+)").matcher(msg);
        if (am.find() && k.sigTo.containsKey(am.group(2) + "." + am.group(1))) {
            detail
                .append(am.group(2))
                .append('.')
                .append(am.group(1))
                .append(" -> ")
                .append(k.sigTo.get(am.group(2) + "." + am.group(1)));
            return "signature changed (map knows the new one)";
        }
        if (!msg.startsWith("method does not override") && VersionMap.lineCalls(e.source(), k.sigNames) != null) {
            detail.append("line calls ").append(VersionMap.lineCalls(e.source(), k.sigNames));
            return "signature changed (map knows the new one)";
        }
        if (msg.startsWith("method does not override")) {
            return "override broke";
        }
        String m = msg.replaceAll("[A-Z][\\w.$<>,? ]*", "T").replaceAll("\\s+", " ");
        return "other: " + (m.length() > 50 ? m.substring(0, 50) : m);
    }

    static void run(Path mapFile, Path log) throws IOException {
        MapKnown k = MapKnown.load(mapFile);
        Map<String, Integer> counts = new TreeMap<>();
        List<String> examples = new ArrayList<>();
        for (VersionMap.Err e : VersionMap.readErrors(log)) {
            StringBuilder detail = new StringBuilder();
            String v = verdict(e, k, detail);
            counts.merge(v, 1, Integer::sum);
            if (detail.length() > 0 && examples.size() < 25) {
                examples.add(v + "  " + detail);
            }
        }
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        int mech = counts
            .entrySet()
            .stream()
            .filter(x -> x.getKey().startsWith("mechanical"))
            .mapToInt(Map.Entry::getValue)
            .sum();
        int sig = counts.getOrDefault("signature changed (map knows the new one)", 0);
        System.out.printf(
            "%d errors: %d mechanical (rename/move), %d signature known, %d other%n",
            total,
            mech,
            sig,
            total - mech - sig
        );
        counts
            .entrySet()
            .stream()
            .sorted((x, y) -> y.getValue() - x.getValue())
            .forEach(x -> System.out.printf("  %4d  %s%n", x.getValue(), x.getKey()));
        for (String ex : examples) {
            System.out.println("        " + ex);
        }
    }
}

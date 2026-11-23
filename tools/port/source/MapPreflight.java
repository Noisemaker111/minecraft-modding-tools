import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Only reviewed AST recipes with a matching target mapping, before the first compile. */
final class MapPreflight {

    /** A removed name used more than this many times across the repo prints once as a common word
        instead of once per use: past the 90th percentile of repetition the uses are lookalikes,
        not the removed member (measured p90 = 19 on Apotheosis 8.9.0). */
    static final int COMMON_WORD_USES = 20;

    /** One printed line per finding, except common words, which print once per repo (see run). */
    static List<String> renderGrouped(String rel, List<ApiFix.LintHit> hits, Map<String, Integer> uses) {
        List<String> lines = new ArrayList<>();
        for (ApiFix.LintHit hit : hits) {
            if (!hit.auto() && uses.getOrDefault(hit.detail(), 0) > COMMON_WORD_USES) continue;
            lines.add(rel + ":" + hit.line() + ": " + hit.detail());
        }
        return lines;
    }

    static int run(Path mapFile, Path repo, boolean dryRun) throws IOException {
        MapKnown known = MapKnown.load(mapFile);
        if ("yarn".equals(known.names)) {
            int files = 0;
            for (Path file : MapFix.sourceFiles(repo)) {
                String before = Files.readString(file, StandardCharsets.UTF_8);
                String after = ApiFix.preflight(known, file, before);
                if (after.equals(before)) {
                    continue;
                }
                files++;
                if (!dryRun) {
                    Files.writeString(file, after, StandardCharsets.UTF_8);
                }
            }
            System.out.println("map-preflight: " + files + " file(s)" + (dryRun ? " (dry run)" : ""));
            return files;
        }
        // Map-driven lint for MCP and Mojang names: every method call and dotted field read
        // whose name the map renamed, moved or removed is reported with the update that changed
        // it. Unanimous renames on provably game-owned receivers are rewritten; everything else
        // (ambiguous renames, removals, unknown receivers, mod-owned or self-declared names) is
        // reported for a human. Parse only: no build, no game.
        record FileLint(Path file, String before, ApiFix.Lint lint) {}
        List<Path> lintFiles = new ArrayList<>();
        List<String> lintSources = new ArrayList<>();
        for (Path file : MapFix.sourceFiles(repo)) {
            lintFiles.add(file);
            lintSources.add(Files.readString(file, StandardCharsets.UTF_8));
        }
        List<ApiFix> parsed = null;
        try {
            parsed = ApiFix.parseAll(lintFiles, lintSources);
        } catch (RuntimeException batchFailure) {
            parsed = null;
        }
        List<FileLint> lints = new ArrayList<>();
        for (int i = 0; i < lintFiles.size(); i++) {
            Path file = lintFiles.get(i);
            String before = lintSources.get(i);
            ApiFix.Lint lint;
            try {
                if (parsed == null) lint = ApiFix.lintKnown(known, file, before);
                else lint = ApiFix.lintParsed(known, parsed.get(i), before);
            } catch (RuntimeException failure) {
                System.out.println(repo.relativize(file) + ": skipped, could not parse (" + failure.getMessage() + ")");
                continue;
            }
            lints.add(new FileLint(file, before, lint));
        }
        Map<String, Integer> uses = new HashMap<>();
        for (FileLint fileLint : lints) for (ApiFix.LintHit hit : fileLint.lint.hits) if (!hit.auto()) {
            uses.merge(hit.detail(), 1, Integer::sum);
        }
        Map<String, Set<String>> commonWords = new LinkedHashMap<>();
        for (FileLint fileLint : lints) for (ApiFix.LintHit hit : fileLint.lint.hits) if (!hit.auto()) {
            if (uses.getOrDefault(hit.detail(), 0) > COMMON_WORD_USES) commonWords.computeIfAbsent(hit.detail(), key -> new LinkedHashSet<>()).add(repo.relativize(fileLint.file).toString());
        }
        int files = 0, auto = 0, review = 0;
        for (FileLint fileLint : lints) {
            int fileAuto = 0;
            for (ApiFix.LintHit hit : fileLint.lint.hits) if (hit.auto()) fileAuto++;
            List<String> lines = renderGrouped(repo.relativize(fileLint.file).toString(), fileLint.lint.hits, uses);
            for (String line : lines) System.out.println(line);
            auto += fileAuto;
            review += lines.size() - fileAuto;
            if (!fileLint.lint.fixed.equals(fileLint.before)) {
                files++;
                if (!dryRun) {
                    Files.writeString(fileLint.file, fileLint.lint.fixed, StandardCharsets.UTF_8);
                }
            }
        }
        for (var word : commonWords.entrySet()) {
            System.out.println("common word: " + uses.get(word.getKey()) + " uses across " + word.getValue().size() + " files: " + word.getKey() + " [common-word]");
            review++;
        }
        System.out.println(
            "map-preflight: " + files + " file(s), " + auto + " auto-fix(es), " + review + " to review" + (dryRun ? " (dry run)" : "")
        );
        return files;
    }
}

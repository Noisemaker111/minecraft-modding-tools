import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** What the jar porter learned, applied to source (SourceAnswers): planned, written, and taken back when the compiler rejects one. */
final class PortAnswers {

    final Port port;

    PortAnswers(Port port) {
        this.port = port;
    }

    /**
     * What JarPort writes into a class, written into the source first, while the mod still builds on its own release: javac
     * resolves every game use against the mod's own compile (PortPlan.ownCompile), and each use of a member the route map
     * says the update broke gets the same answer a jar port gets (the map's learned replacements, the rules' inClass),
     * from the same files (tools/port/source/SourceAnswers.java). The sources are already backed up, so undo covers it. A mod whose
     * own compile cannot be recorded goes on as before, with the typed fixer and rules alone.
     */
    PortPlan.Compiled ownCompile(Map<String, Object> model) throws Exception {
        try {
            return PortPlan.ownCompile(port.build, model, false);
        } catch (IOException unrecorded) {
            port.build.report.stages.add(Json.map("name", "source-answers", "skipped", "the mod's own compile recorded nothing: " + String.valueOf(unrecorded.getMessage()).lines().findFirst().orElse("")));
            return null;
        }
    }

    /** Each answer written into the source: file, lines in the text as written, and the text it replaced. */
    final List<Map<String, Object>> answersWritten = new ArrayList<>();

    /**
     * Puts back the answers the new release's compiler rejects (an error on one of their lines): javac sees source types
     * a jar port does not (a generic method's value where the old call gave a holder), and the place goes on as before
     * this step, through the typed fixer and rules. Before any other repair changes the files, so each answer is where it
     * was written. Returns how many were put back.
     */
    int revertRejectedAnswers(Path log) throws IOException {
        if (answersWritten.isEmpty()) return 0;
        Map<String, Set<Integer>> errorLines = new HashMap<>();
        for (Object o : Port.errors(log)) {
            var e = Json.object(o);
            errorLines.computeIfAbsent(Path.of(Json.text(e, "file", "")).toAbsolutePath().normalize().toString(), k -> new HashSet<>()).add(((Number) e.get("line")).intValue());
        }
        Map<String, List<Map<String, Object>>> byFile = new LinkedHashMap<>();
        for (var w : answersWritten) byFile.computeIfAbsent(Path.of(Json.text(w, "file", "")).toAbsolutePath().normalize().toString(), k -> new ArrayList<>()).add(w);
        int reverted = 0;
        for (var e : byFile.entrySet()) {
            Set<Integer> lines = errorLines.getOrDefault(e.getKey(), Set.of());
            if (lines.isEmpty()) continue;
            Path file = Path.of(e.getKey());
            String text = Files.readString(file, StandardCharsets.UTF_8);
            // Later steps (the preflight's renames) may have moved the text since it was written: each answer is found
            // where its written text still stands, at its place or as the one such text in the file, and is left alone
            // otherwise. One the compiler reports an error on is put back.
            List<int[]> back = new ArrayList<>(); // start, length, index into sites
            List<Map<String, Object>> sites = e.getValue();
            for (int k = 0; k < sites.size(); k++) {
                var w = sites.get(k);
                String written = Json.text(w, "text", "");
                int start = ((Number) w.get("start")).intValue();
                if (written.isEmpty()) continue;
                if (start < 0 || !text.startsWith(written, start)) {
                    int found = text.indexOf(written);
                    if (found < 0 || text.indexOf(written, found + 1) >= 0) continue;
                    start = found;
                }
                int first = 1 + (int) text.substring(0, start).chars().filter(c -> c == '\n').count();
                int last = first + (int) written.chars().filter(c -> c == '\n').count();
                boolean rejected = false;
                for (int l = first; l <= last && !rejected; l++) rejected = lines.contains(l);
                if (rejected) back.add(new int[] { start, written.length(), k });
            }
            back.sort(Comparator.comparingInt((int[] b) -> b[0]).reversed());
            for (int[] b : back) {
                text = text.substring(0, b[0]) + Json.text(sites.get(b[2]), "original", "") + text.substring(b[0] + b[1]);
                reverted++;
            }
            if (!back.isEmpty()) Files.writeString(file, text, StandardCharsets.UTF_8);
        }
        answersWritten.clear();
        return reverted;
    }

    void sourceAnswers(JavaTools tools, Map<String, Object> model, Path map, String from, String to, PortPlan.Compiled own) throws Exception {
        Path dir = port.build.project.state().resolve("source-answers");
        Files.createDirectories(dir);
        Path sourcesFile = dir.resolve("sources.txt"), classpathFile = dir.resolve("classpath.txt"), report = dir.resolve("report.json");
        Files.write(sourcesFile, own.sources().stream().map(Path::toString).toList());
        Files.write(classpathFile, own.classpath().stream().map(Path::toString).toList());
        Files.deleteIfExists(report);
        var run = tools.run("SourceAnswers", List.of("--map", map.toString(), "--fixes", port.build.harness.resolve("knowledge/fixes.json").toString(),
            "--from", from, "--to", to, "--loader", MixinCheck.loader(model), "--sources", sourcesFile.toString(), "--classpath", classpathFile.toString(),
            "--report", report.toString(), "--dry-run"), "port-source-answers");
        if (run.exit() != 0 || !Files.isRegularFile(report)) throw new IOException("Source answers stopped: " + Run.tail(run.log()));
        answerPlan = Json.read(report);
    }

    /** The source answers planned on the sources as written, waiting for the preflight (SourceAnswers --dry-run's report). */
    Map<String, Object> answerPlan;

    /**
     * Writes the planned answers into the sources as they are after the preflight: each where its original text still
     * stands near its line (the one such text within a few lines), so a place the preflight renamed inside is left to
     * the typed fixer and rules; each file gets the imports its answers name.
     */
    void applyAnswerPlan() throws IOException {
        if (answerPlan == null) return;
        Map<String, List<Map<String, Object>>> byFile = new LinkedHashMap<>();
        for (Object o : Json.array(answerPlan.getOrDefault("written", List.of()))) {
            var w = Json.object(o);
            byFile.computeIfAbsent(Json.text(w, "file", ""), k -> new ArrayList<>()).add(w);
        }
        var imports = Json.object(answerPlan.getOrDefault("imports", Map.of()));
        int placed = 0, skipped = 0;
        for (var e : byFile.entrySet()) {
            Path file = Path.of(e.getKey());
            if (!Files.isRegularFile(file)) continue;
            String text = Files.readString(file, StandardCharsets.UTF_8);
            List<int[]> spots = new ArrayList<>(); // start, end, index
            List<Map<String, Object>> sites = e.getValue();
            for (int k = 0; k < sites.size(); k++) {
                String original = Json.text(sites.get(k), "original", "");
                int line = ((Number) sites.get(k).get("originalLine")).intValue(), found = -1, count = 0;
                for (int at = text.indexOf(original); at >= 0 && !original.isEmpty(); at = text.indexOf(original, at + 1)) {
                    int l = 1 + (int) text.substring(0, at).chars().filter(c -> c == '\n').count();
                    if (Math.abs(l - line) <= 12) {
                        found = at;
                        count++;
                    }
                }
                int f = found;
                if (count == 1 && spots.stream().noneMatch(s -> f < s[1] && s[0] < f + original.length())) spots.add(new int[] { found, found + original.length(), k });
                else skipped++;
            }
            if (spots.isEmpty()) continue;
            spots.sort(Comparator.comparingInt((int[] s) -> s[0]).reversed());
            for (int[] s : spots) text = text.substring(0, s[0]) + Json.text(sites.get(s[2]), "text", "") + text.substring(s[1]);
            // The imports the answers name, after the file's last import (or its package line); the answers sit below them.
            List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
            int after = -1;
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i).strip();
                if (l.startsWith("import ") || l.startsWith("package ")) after = i;
                else if (!l.isEmpty() && !l.startsWith("//") && !l.startsWith("/*") && !l.startsWith("*") && after >= 0) break;
            }
            String eol = text.contains("\r\n") ? "\r" : "";
            List<String> fresh = new ArrayList<>();
            for (Object q : Json.array(imports.getOrDefault(e.getKey(), List.of()))) {
                if (lines.stream().noneMatch(l -> l.strip().equals("import " + q + ";"))) fresh.add("import " + q + ";" + eol);
            }
            lines.addAll(after + 1, fresh);
            text = String.join("\n", lines);
            Files.writeString(file, text, StandardCharsets.UTF_8);
            for (int[] s : spots) {
                var w = new LinkedHashMap<>(sites.get(s[2]));
                w.put("file", file.toString());
                w.put("start", -1); // found by its text when it is checked
                answersWritten.add(w);
                placed++;
            }
        }
        port.build.report.stages.add(Json.map("name", "source-answers", "planned", answerPlan.get("replaced"), "written", placed, "leftForRules", skipped,
            "bySource", answerPlan.get("bySource")));
        answerPlan = null;
    }
}

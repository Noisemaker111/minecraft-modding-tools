import java.nio.file.*;
import java.util.*;

/**
 * The data this repository commits, read from the checkout (not the package): kept perf runs, the pattern library's
 * citations of them, and the bench experiments' files. Run by bench/run_tests.py against the packaged jar's classes:
 *     java -cp build/distributions/mmt.jar:CLASSES CheckoutDataTest .
 */
public final class CheckoutDataTest {
    static int checks;

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        List<Object> runs = Json.array(Json.read(root.resolve("coverage/perf-runs.json")).get("runs"));
        for (Object item : runs) {
            Map<String, Object> kept = Json.object(item);
            check(PerfRecord.validate(kept).isEmpty(), "kept run " + kept.get("id") + " validates: " + PerfRecord.validate(kept));
        }
        List<String> found = PerfPatterns.validate(Json.read(PerfPatterns.file(root)), PerfPatterns.acceptedRecords(root));
        check(found.isEmpty(), "knowledge/perf-patterns.json: " + String.join("; ", found));
        int experiments = committedExperiments(root);
        porterShips(root);
        System.out.println(runs.size() + " kept perf runs, the pattern library and " + experiments + " bench experiments check out: PASS");
    }

    /**
     * Every source the porter is compiled from is one the package carries: mmt compiles the porter from its shipped
     * sources (JavaTools.portSources lists tools/port and the files compiled with it), so one the package's
     * resources leave out compiles in a checkout and fails for everyone else.
     */
    static void porterShips(Path root) throws Exception {
        Set<String> shipped = new HashSet<>(Installation.resources(root));
        for (Path source : JavaTools.portSources(root)) {
            String name = root.relativize(source).toString().replace('\\', '/');
            check(shipped.contains(name), name + " is a porter source the package does not carry: tools/port ships whole, a file beside it needs a line in Installation.RESOURCES");
        }
    }

    /**
     * Every experiment under bench/ names only files the checkout has; only its save is made once per machine. A file the
     * repository ignores (Pipez's options.txt was) passes on the machine that made it and fails on every other. Nested
     * experiments count too: Diorama's is bench/diorama/play. Files copied out of the save are made by its prepare step.
     */
    static int committedExperiments(Path root) throws Exception {
        List<Path> files;
        try (var walk = Files.walk(root.resolve("bench"), 3)) {
            files = walk.filter(p -> p.getFileName().toString().equals("experiment.json")).sorted().toList();
        }
        for (Path file : files) {
            Map<String, Object> experiment = Json.read(file);
            Path save = file.getParent().resolve(Json.text(experiment, "worldSource", "-")).normalize();
            Map<String, Object> result = PerfCheck.check(file, experiment, null, null, null);
            List<String> problems = new ArrayList<>();
            for (Object item : Json.array(result.get("problems"))) {
                String problem = item.toString();
                if (problem.startsWith("worldSource ")) continue;
                if (problem.startsWith("files: ") && file.getParent().resolve(problem.substring(7, problem.indexOf(" (for "))).normalize().startsWith(save)) continue;
                problems.add(problem);
            }
            check(problems.isEmpty(), root.relativize(file) + " names files the checkout lacks: " + problems);
        }
        check(files.size() >= 4, "bench experiments are checked, nested ones too: " + files.size());
        return files.size();
    }
}

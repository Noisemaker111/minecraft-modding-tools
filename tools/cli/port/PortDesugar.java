import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Ports to releases on older Java: newer language features written the old way (Desugar), and the annotation libraries they need. */
final class PortDesugar {

    final Port port;

    PortDesugar(Port port) {
        this.port = port;
    }

    /**
     * Lowers modern Java the legacy target cannot compile (records, pattern matching, switch expressions,
     * text blocks, sealed, var) using the evaluated compile classpath for the types var needs. Only runs
     * when the log shows the legacy release rejecting modern syntax and the model targets a legacy release.
     */
    boolean desugar(Path log) throws Exception {
        if (!Desugar.needsDesugar(Port.logText(log))) return false;
        if (!targetsLegacyRelease()) return false;
        List<String> classpath = compileClasspath();
        if (classpath.isEmpty()) return false;
        Set<Path> files = new LinkedHashSet<>(port.rules.ownedJava());
        Matcher reported = Pattern.compile("(?m)^\\s*" + Port.FILE + ":[0-9]+: error:").matcher(Port.logText(log));
        while (reported.find()) {
            Path file = Path.of(reported.group(1));
            if (!file.isAbsolute()) file = port.build.project.root.resolve(file);
            file = file.normalize();
            if (file.startsWith(port.build.project.root) && Files.isRegularFile(file) && file.toString().endsWith(".java")) files.add(file);
        }
        boolean changed = false;
        for (Path file : files) {
            String before = Files.readString(file);
            String after = Desugar.desugarFile(file, classpath);
            if (!after.equals(before)) {
                Files.writeString(file, after);
                changed = true;
            }
        }
        if (changed) port.rulesApplied.putIfAbsent("desugar", List.of());
        return changed;
    }

    /** True when an evaluated compile task targets Java 8 or older. */
    boolean targetsLegacyRelease() {
        for (Object project : Json.array(port.model.get("projects"))) {
            for (Object task : Json.array(Json.object(project).get("compile"))) {
                if (isLegacyRelease(Json.object(task).get("release"))) return true;
            }
        }
        return false;
    }

    static boolean isLegacyRelease(Object release) {
        if (release == null) return false;
        String text = release.toString().strip();
        if (text.startsWith("1.")) text = text.substring(2);
        try {
            return Integer.parseInt(text) <= 8;
        } catch (NumberFormatException notVersion) {
            return false;
        }
    }

    /** The union of every evaluated compile classpath: a superset, which is all attribution needs. */
    List<String> compileClasspath() {
        Set<String> entries = new LinkedHashSet<>();
        for (Object project : Json.array(port.model.get("projects"))) {
            for (Object task : Json.array(Json.object(project).get("compile"))) {
                for (Object entry : Json.array(Json.object(task).get("classpath"))) entries.add(entry.toString());
            }
        }
        return new ArrayList<>(entries);
    }

    /**
     * Annotation-only libraries an older game or loader brought along and a newer one does not (Trinkets 1.20.1 -> 1.21.1
     * lost javax.annotation): only nullness annotations, never read at run time, so compileOnly is safe.
     */
    static final Map<String, String> ANNOTATION_LIBRARIES = Map.of(
        "javax.annotation",
        "com.google.code.findbugs:jsr305:3.0.2",
        "org.jetbrains.annotations",
        "org.jetbrains:annotations:26.0.2",
        "org.jspecify.annotations",
        "org.jspecify:jspecify:1.0.0"
    );

    /** Adds the compileOnly annotation libraries the log says are missing to the selected project's build script. */
    void restoreAnnotationLibraries(Path log) throws Exception {
        String errors = Port.logText(log);
        List<String> wanted = new ArrayList<>();
        for (var library : ANNOTATION_LIBRARIES.entrySet()) if (
            errors.contains("package " + library.getKey() + " does not exist")
        ) wanted.add(library.getValue());
        if (wanted.isEmpty()) return;
        for (Object p : Json.array(port.model.get("projects"))) {
            Object script = Json.object(p).get("buildFile");
            if (script == null || !Files.isRegularFile(Path.of(script.toString()))) continue;
            Path file = Path.of(script.toString());
            String text = Files.readString(file), after = withCompileOnly(text, wanted, file.toString().endsWith(".kts"));
            if (after.equals(text)) continue;
            port.journal.track(file);
            Files.writeString(file, after);
            port.rulesApplied.putIfAbsent("annotation-library-compile-only", List.of());
        }
    }

    /** A build script with each coordinate added as compileOnly to its top-level dependencies block (not twice). */
    static String withCompileOnly(String text, List<String> coordinates, boolean kotlin) {
        Matcher block = Pattern.compile("(?m)^dependencies\\s*\\{[ \\t]*\\r?\\n").matcher(text);
        if (!block.find()) return text;
        StringBuilder lines = new StringBuilder();
        for (String coordinate : coordinates) {
            String name = coordinate.substring(0, coordinate.lastIndexOf(':'));
            if (text.contains(name)) continue;
            lines.append(kotlin ? "    compileOnly(\"" + coordinate + "\")\n" : "    compileOnly \"" + coordinate + "\"\n");
        }
        if (lines.length() == 0) return text;
        return text.substring(0, block.end()) + "    // mmt port: annotation-only library the target no longer brings along\n" + lines + text.substring(block.end());
    }
}

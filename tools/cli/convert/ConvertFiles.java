import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** The project's files as a conversion reads and writes them: staged, tracked for undo, and listed by kind. */
final class ConvertFiles {

    final Convert convert;

    ConvertFiles(Convert convert) {
        this.convert = convert;
    }

    Path transaction;
    final Map<String, Object> before = new LinkedHashMap<>();
    final List<Map<String, Object>> left = new ArrayList<>();
    /** --dry-run: files change here, never on disk; null is a removed file. */
    boolean dryRun;
    final Map<String, String> staged = new HashMap<>();
    static final Pattern SOURCE = Pattern.compile("src/([^/]+)/java/.+\\.java");

    // ---- the transaction ----------------------------------------------------------------------------

    void saveInProgress() throws IOException {
        Json.save(
            transaction.resolve("transaction.json"),
            Json.map(
                "root",
                convert.project.root.toString(),
                "before",
                before,
                "after",
                Map.of(),
                "removed",
                List.of(),
                "plan",
                Json.map("edits", new ArrayList<>(before.keySet()), "removals", convert.removed),
                "status",
                "in-progress"
            )
        );
    }

    String relative(Path file) {
        return convert.project.root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    void track(Path file) throws Exception {
        String relative = relative(file);
        if (before.containsKey(relative)) return;
        if (Files.isRegularFile(file)) {
            Path backup = transaction.resolve("before").resolve(relative);
            Files.createDirectories(backup.getParent());
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            before.put(relative, Fingerprints.bytes(Files.readAllBytes(file)));
        } else before.put(relative, null);
        // Appended, not the whole record saved again: undo after an interruption reads it (Port.withJournal).
        String hash = before.get(relative) == null ? "" : String.valueOf(before.get(relative));
        Files.writeString(transaction.resolve(PortJournal.JOURNAL), relative + "\t" + hash + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Files whose lines end in CRLF: they are read with LF and written back with CRLF. */
    final Set<String> crlf = new HashSet<>();

    String read(String relative) throws IOException {
        String text = current(relative);
        if (text == null) return "";
        if (text.contains("\r\n")) crlf.add(relative);
        return text.replace("\r\n", "\n");
    }

    /** The files this run has read or written, as they now are (null: none); convert alone changes them while it runs. */
    final Map<String, String> texts = new HashMap<>();

    /** A file's text, with a dry run's changes; null when there is none. */
    String current(String relative) throws IOException {
        if (staged.containsKey(relative)) return staged.get(relative);
        if (texts.containsKey(relative)) return texts.get(relative);
        Path file = convert.project.root.resolve(relative);
        String text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        texts.put(relative, text);
        return text;
    }

    boolean exists(String relative) throws IOException {
        return current(relative) != null;
    }

    void write(String relative, String text) throws Exception {
        Path file = convert.project.root.resolve(relative);
        if (crlf.contains(relative) || (!exists(relative) && !crlf.isEmpty())) text = text
            .replace("\r\n", "\n")
            .replace("\n", "\r\n");
        if (text.equals(current(relative))) return;
        if (dryRun) {
            staged.put(relative, text);
            if (!convert.changed.contains(relative)) convert.changed.add(relative);
            return;
        }
        track(file);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        texts.put(relative, text);
        if (!convert.changed.contains(relative)) convert.changed.add(relative);
    }

    void delete(String relative) throws Exception {
        Path file = convert.project.root.resolve(relative);
        if (!exists(relative)) return;
        if (dryRun) {
            staged.put(relative, null);
            convert.removed.add(relative);
            convert.changed.remove(relative);
            return;
        }
        track(file);
        convert.removed.add(relative);
        saveInProgress();
        Files.delete(file);
        texts.put(relative, null);
        convert.changed.remove(relative);
        for (
            Path parent = file.getParent();
            parent != null && !parent.equals(convert.project.root);
            parent = parent.getParent()
        ) {
            try (var entries = Files.list(parent)) {
                if (entries.findAny().isPresent()) break;
            }
            Files.delete(parent);
        }
    }

    void leave(String file, int line, String what, String why) {
        left.add(Json.map("file", file, "line", line, "what", what, "why", why));
    }

    // ---- sources -------------------------------------------------------------------------------------

    List<String> javaFiles() throws IOException {
        Path src = convert.project.root.resolve("src").toAbsolutePath().normalize();
        Map<Path, TreeSet<Path>> sets = new TreeMap<>();
        if (Files.isDirectory(src)) try (var list = Files.list(src)) {
            for (Path set : list.toList()) {
                Path java = set.resolve("java");
                if (!Files.isDirectory(java)) continue;
                TreeSet<Path> files = sets.computeIfAbsent(set, k -> new TreeSet<>());
                try (var walk = Files.walk(java)) {
                    walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
                }
            }
        }
        // A dry run's files, where the run that writes them would find them.
        for (var entry : staged.entrySet()) {
            Matcher source = SOURCE.matcher(entry.getKey());
            if (!source.matches()) continue;
            Path set = src.resolve(source.group(1));
            Path file = convert.project.root.resolve(entry.getKey()).toAbsolutePath().normalize();
            if (entry.getValue() != null) sets.computeIfAbsent(set, k -> new TreeSet<>()).add(file);
            else if (sets.containsKey(set)) sets.get(set).remove(file);
        }
        List<String> out = new ArrayList<>();
        for (var set : sets.entrySet()) if (!set.getKey().getFileName().toString().startsWith("test")) {
            for (Path file : set.getValue()) out.add(relative(file));
        }
        return out;
    }

    List<String> testJavaFiles() throws IOException {
        List<String> out = new ArrayList<>();
        Path src = convert.project.root.resolve("src").toAbsolutePath().normalize();
        if (!Files.isDirectory(src)) return out;
        try (var list = Files.list(src)) {
            for (Path set : list.sorted().toList()) {
                Path java = set.resolve("java");
                if (!set.getFileName().toString().startsWith("test") || !Files.isDirectory(java)) continue;
                try (var walk = Files.walk(java)) {
                    for (Path file : walk.filter(p -> p.toString().endsWith(".java")).sorted().toList()) out.add(relative(file));
                }
            }
        }
        return out;
    }

    /** The source file of a class, in any source set; null when none has it. */
    String classFile(String name) throws IOException {
        String path = name.replace('.', '/') + ".java";
        for (String file : javaFiles()) if (file.endsWith("/java/" + path)) return file;
        return null;
    }

    String readQuiet(String relative) {
        try {
            return read(relative);
        } catch (IOException e) {
            return "";
        }
    }
}

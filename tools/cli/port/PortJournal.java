import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** What a port changed, written down as it goes, so port --undo (or a port that was interrupted) puts every file back. */
final class PortJournal {

    final Port port;

    PortJournal(Port port) {
        this.port = port;
    }

    /** The running port's undo record: each file's hash before the port (null for a file the port created) and the files it plans to change. */
    Path transaction;
    final Map<String, Object> before = new LinkedHashMap<>();
    final List<String> planned = new ArrayList<>();
    List<String> plannedRemovals = List.of();

    void saveInProgress() throws IOException {
        Json.save(
            transaction.resolve("transaction.json"),
            Json.map(
                "root",
                port.build.project.root.toString(),
                "before",
                before,
                "after",
                Map.of(),
                "removed",
                List.of(),
                "plan",
                Json.map(
                    "edits",
                    planned,
                    "removals",
                    plannedRemovals,
                    "backups",
                    transaction.resolve("before").toString()
                ),
                "status",
                "in-progress"
            )
        );
    }

    /**
     * Records a file the port is about to change or create that the opening backup did not cover (only sources and build
     * files are backed up up front), before the first write: undo restores it, or deletes it when the port created it.
     */
    void track(Path file) throws Exception {
        String relative = port.build.project.root
            .relativize(file.toAbsolutePath().normalize())
            .toString()
            .replace('\\', '/');
        if (before.containsKey(relative)) return;
        if (Files.isRegularFile(file)) {
            Path backup = transaction.resolve("before").resolve(relative);
            Files.createDirectories(backup.getParent());
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            before.put(relative, Fingerprints.bytes(Files.readAllBytes(file)));
        } else before.put(relative, null);
        planned.add(relative);
        saveInProgress();
    }

    void finishTransaction(Path transaction, Map<String, Object> before) throws Exception {
        finishTransaction(port.build.project, transaction, before, List.of());
    }

    static void finishTransaction(ProjectModel project, Path transaction, Map<String, Object> before) throws Exception {
        finishTransaction(project, transaction, before, List.of());
    }

    static void finishTransaction(
        ProjectModel project,
        Path transaction,
        Map<String, Object> before,
        List<String> removed
    ) throws Exception {
        Map<String, Object> after = new LinkedHashMap<>();
        StringBuilder patch = new StringBuilder();
        for (String relative : before.keySet()) {
            Path file = project.root.resolve(relative);
            boolean created = before.get(relative) == null && !removed.contains(relative);
            if (created && !Files.isRegularFile(file)) continue;
            if (!Files.isRegularFile(file)) {
                if (!removed.contains(relative)) throw new IOException(
                    "Repair unexpectedly removed " + relative + "; backups: " + transaction
                );
                after.put(relative, null);
                patch.append("deleted ").append(relative).append('\n');
                continue;
            }
            String hash = Fingerprints.bytes(Files.readAllBytes(file));
            if (!hash.equals(before.get(relative))) {
                after.put(relative, hash);
                String old = created ? "" : Files.readString(transaction.resolve("before").resolve(relative)), now =
                    Files.readString(file);
                patch
                    .append("diff --git a/")
                    .append(relative)
                    .append(" b/")
                    .append(relative)
                    .append("\n--- a/")
                    .append(relative)
                    .append("\n+++ b/")
                    .append(relative)
                    .append('\n');
                List<String> a = old.lines().toList(), b = now.lines().toList();
                patch.append("@@ -1,").append(a.size()).append(" +1,").append(b.size()).append(" @@\n");
                a.forEach(line -> patch.append('-').append(line).append('\n'));
                b.forEach(line -> patch.append('+').append(line).append('\n'));
            }
        }
        Files.writeString(transaction.resolve("changes.patch"), patch);
        Json.save(
            transaction.resolve("transaction.json"),
            Json.map(
                "root",
                project.root.toString(),
                "before",
                before,
                "after",
                after,
                "removed",
                removed,
                "status",
                "complete",
                "patch",
                transaction.resolve("changes.patch").toString()
            )
        );
    }

    Map<String, Object> undo(String id) throws Exception {
        return undo(port.build.project, id);
    }

    /** This project's port transactions, newest first. */
    static List<Path> transactions(ProjectModel project) throws IOException {
        return transactions(project, "ports");
    }

    /** The transactions recorded under one folder of the project's state (ports, or converts for convert), newest first. */
    static List<Path> transactions(ProjectModel project, String folder) throws IOException {
        Path ports = project.state().resolve(folder);
        if (!Files.isDirectory(ports)) return List.of();
        try (var list = Files.list(ports)) {
            return list
                .filter(p -> Files.isRegularFile(p.resolve("transaction.json")))
                .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                .toList();
        }
    }

    /**
     * A port that stopped part-way (killed, closed terminal, power cut) and was never undone, as a
     * sentence naming the files it left changed; null when there is none. A command holds the
     * project lock while it runs, so an "in-progress" record seen under the lock is a dead one.
     */
    static String interruptedText(ProjectModel project) throws Exception {
        for (Path transaction : transactions(project)) {
            Map<String, Object> state;
            try {
                state = withJournal(transaction, Json.read(transaction.resolve("transaction.json")));
            } catch (IOException | IllegalArgumentException unreadable) {
                continue;
            }
            if (!"in-progress".equals(state.get("status"))) continue;
            // Only the files the port planned to edit or remove count here: a source edited by hand
            // since then must not make an old record look like an unfinished port forever.
            Set<String> planned = new HashSet<>();
            if (state.get("plan") instanceof Map<?, ?> plan) for (String key : List.of(
                "edits",
                "removals"
            )) planned.addAll(ProjectModel.strings(plan.get(key)));
            List<String> changed = changedSince(project, state).stream().filter(planned::contains).toList();
            if (changed.isEmpty()) continue;
            return (
                "A port of this project stopped part-way (" +
                transaction
                    .getFileName()
                    .toString()
                    .replaceFirst("T(\\d\\d)-(\\d\\d)-(\\d\\d).*$", " at $1:$2:$3 UTC") +
                ") and left " +
                changed.size() +
                (changed.size() == 1 ? " file" : " files") +
                " changed: " +
                String.join(", ", changed) +
                "."
            );
        }
        return null;
    }

    /** Backed-up files that now differ from their backup, or are gone. */
    /** The journal a run appends each file to before it first writes it (tracked.tsv: path, tab, hash or empty when new). */
    static final String JOURNAL = "tracked.tsv";

    /**
     * An unfinished run's record with the files its journal lists: convert appends a line per file instead of saving the
     * whole record before every write (which grew with the square of the files it writes). Undo restores or deletes them.
     */
    static Map<String, Object> withJournal(Path transaction, Map<String, Object> state) throws IOException {
        Path journal = transaction.resolve(JOURNAL);
        if (!"in-progress".equals(state.get("status")) || !Files.isRegularFile(journal)) return state;
        Map<String, Object> before = new LinkedHashMap<>(Json.object(state.get("before")));
        Map<String, Object> plan = new LinkedHashMap<>(Json.object(state.getOrDefault("plan", Map.of())));
        List<String> edits = new ArrayList<>(ProjectModel.strings(plan.get("edits")));
        for (String line : Files.readAllLines(journal, StandardCharsets.UTF_8)) {
            int tab = line.indexOf('\t');
            if (tab < 0) continue;
            String relative = line.substring(0, tab);
            String hash = line.substring(tab + 1).strip();
            if (before.containsKey(relative)) continue;
            before.put(relative, hash.isEmpty() ? null : hash);
            edits.add(relative);
        }
        Map<String, Object> out = new LinkedHashMap<>(state);
        out.put("before", before);
        plan.put("edits", edits);
        out.put("plan", plan);
        return out;
    }

    static List<String> changedSince(ProjectModel project, Map<String, Object> state) throws Exception {
        List<String> changed = new ArrayList<>();
        for (var entry : Json.object(state.get("before")).entrySet()) {
            Path file = project.root.resolve(entry.getKey()).normalize();
            if (!file.startsWith(project.root)) continue;
            // A null hash is a file the port created: changed once it exists.
            if (
                entry.getValue() == null
                    ? Files.exists(file)
                    : !Files.isRegularFile(file) ||
                    !Fingerprints.bytes(Files.readAllBytes(file)).equals(entry.getValue())
            ) changed.add(entry.getKey());
        }
        return changed;
    }

    static Map<String, Object> undo(ProjectModel project, String id) throws Exception {
        return undo(project, id, "ports");
    }

    /** Undoes one recorded transaction under the folder: a port (ports) or a loader conversion (converts). */
    static Map<String, Object> undo(ProjectModel project, String id, String folder) throws Exception {
        String kind = folder.equals("ports") ? "port" : "conversion";
        if (id.isBlank() || id.equals("latest")) {
            // --undo with no transaction: the newest port that has not been undone.
            Path newest = null;
            for (Path candidate : transactions(project, folder)) {
                try {
                    if (!"undone".equals(Json.read(candidate.resolve("transaction.json")).get("status"))) {
                        newest = candidate;
                        break;
                    }
                } catch (IOException | IllegalArgumentException unreadable) {}
            }
            if (newest == null) throw new IOException(
                "There is no " + kind + " of this project to undo" +
                (transactions(project, folder).isEmpty() ? "." : ": every recorded " + kind + " was already undone.")
            );
            id = newest.toString();
        }
        // The port's own id (the name its result and receipt carry) names its transaction as well as the full path does.
        Path transaction = id.matches("[\\w.-]+") && Files.isDirectory(project.state().resolve(folder).resolve(id))
            ? project.state().resolve(folder).resolve(id)
            : Path.of(id).toAbsolutePath().normalize();
        if (!transaction.startsWith(project.state().resolve(folder))) throw new IOException(
            "Undo must name this project's recorded " + kind + " transaction: its id or its folder under " +
            project.state().resolve(folder) +
            "."
        );
        var state = withJournal(transaction, Json.read(transaction.resolve("transaction.json")));
        if (!project.root.toString().equals(state.get("root"))) throw new IOException(
            "Undo belongs to a different project."
        );
        String status = Json.text(state, "status", "");
        if (status.equals("undone")) throw new IOException(
            "This port was already undone; nothing to restore. Transaction: " + transaction
        );
        if (status.equals("in-progress")) return undoInterrupted(project, transaction, state);
        if (!status.equals("complete")) throw new IOException(interrupted(project, transaction, state));
        var after = Json.object(state.get("after"));
        List<String> removed = ProjectModel.strings(state.get("removed"));
        for (String relative : removed) {
            Path file = project.root.resolve(relative).normalize();
            if (!file.startsWith(project.root)) throw new IOException("Undo entry escapes the project: " + relative);
            if (Files.exists(file)) throw new IOException(
                "Refusing undo: " + relative + " was recreated after porting. Your later edits have been preserved."
            );
        }
        for (var entry : after.entrySet()) {
            if (removed.contains(entry.getKey())) continue;
            Path file = project.root.resolve(entry.getKey()).normalize();
            if (
                !file.startsWith(project.root) ||
                !Files.isRegularFile(file) ||
                !Fingerprints.bytes(Files.readAllBytes(file)).equals(entry.getValue())
            ) throw new IOException(
                "Refusing undo: " + entry.getKey() + " changed after porting. Your later edits have been preserved."
            );
        }
        var original = Json.object(state.get("before"));
        for (String relative : after.keySet()) {
            if (original.get(relative) == null && !removed.contains(relative)) deleteWithEmptiedFolders(
                project.root,
                project.root.resolve(relative)
            );
            else {
                // A removed file may have taken its emptied folder with it.
                Files.createDirectories(project.root.resolve(relative).getParent());
                Files.copy(
                    transaction.resolve("before").resolve(relative),
                    project.root.resolve(relative),
                    StandardCopyOption.REPLACE_EXISTING
                );
            }
        }
        state.put("status", "undone");
        Json.save(transaction.resolve("transaction.json"), state);
        return Json.map(
            "ok",
            true,
            "restoredFiles",
            after.size(),
            "transaction",
            transaction.toString(),
            "details",
            after.isEmpty()
                ? "The " + kind + " changed no files, so there was nothing to restore."
                : "Restored " + after.size() + " file(s) to their state before the " + kind + "."
        );
    }

    /**
     * Deletes a file undo found the port or conversion made, and every folder that deleting it left empty, up to (not
     * including) the project root: a folder that held only made files was made with them.
     */
    static void deleteWithEmptiedFolders(Path root, Path file) throws IOException {
        Files.delete(file);
        for (Path parent = file.getParent(); parent != null && parent.startsWith(root) && !parent.equals(root); parent = parent.getParent()) {
            try (var entries = Files.list(parent)) {
                if (entries.findAny().isPresent()) break;
            }
            Files.delete(parent);
        }
    }

    /**
     * A port that stopped part-way recorded its backups before the first edit but never the files'
     * state after. Every file that differs from its backup is put back, and its current copy is kept
     * beside the backups first, so an edit made after the port stopped is never lost.
     */
    static Map<String, Object> undoInterrupted(ProjectModel project, Path transaction, Map<String, Object> state)
        throws Exception {
        List<String> changed = changedSince(project, state);
        var original = Json.object(state.get("before"));
        Path kept = transaction.resolve("current-before-undo");
        for (String relative : changed) {
            Path file = project.root.resolve(relative).normalize(), backup = transaction
                .resolve("before")
                .resolve(relative);
            if (original.get(relative) != null && !Files.isRegularFile(backup)) throw new IOException(
                "Refusing undo: no backup of " +
                relative +
                " in " +
                transaction.resolve("before") +
                ". Nothing was restored."
            );
            if (Files.isRegularFile(file)) {
                Files.createDirectories(kept.resolve(relative).getParent());
                Files.copy(file, kept.resolve(relative), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        for (String relative : changed) {
            Path file = project.root.resolve(relative).normalize();
            if (original.get(relative) == null) {
                deleteWithEmptiedFolders(project.root, file);
                continue;
            }
            Files.createDirectories(file.getParent());
            Files.copy(transaction.resolve("before").resolve(relative), file, StandardCopyOption.REPLACE_EXISTING);
        }
        state.put("status", "undone");
        state.put("undoneAfterInterruption", changed);
        Json.save(transaction.resolve("transaction.json"), state);
        return Json.map(
            "ok",
            true,
            "restoredFiles",
            changed.size(),
            "transaction",
            transaction.toString(),
            "interrupted",
            true,
            "details",
            changed.isEmpty()
                ? "The port stopped before changing any file, so there was nothing to restore."
                : "The port had stopped part-way. Restored " +
                changed.size() +
                (changed.size() == 1 ? " file" : " files") +
                " to their state before it: " +
                String.join(", ", changed) +
                ".\nTheir versions from just before this undo are kept in " +
                kept +
                ", in case you edited them after the port stopped."
        );
    }

    /**
     * A port that never finished recorded no "after" state, so an undo cannot prove which files it
     * changed or that they were not edited since. Refuse, and name what differs from the backups.
     */
    static String interrupted(ProjectModel project, Path transaction, Map<String, Object> state) throws Exception {
        Path backups = transaction.resolve("before");
        List<String> changed = new ArrayList<>(), missing = new ArrayList<>();
        for (var entry : Json.object(state.get("before")).entrySet()) {
            Path file = project.root.resolve(entry.getKey()).normalize();
            if (!file.startsWith(project.root)) continue;
            if (entry.getValue() == null) {
                if (Files.exists(file)) changed.add(entry.getKey());
            } else if (!Files.isRegularFile(file)) missing.add(entry.getKey());
            else if (!Fingerprints.bytes(Files.readAllBytes(file)).equals(entry.getValue())) changed.add(
                entry.getKey()
            );
        }
        StringBuilder out = new StringBuilder(
            "Refusing undo: this port did not finish (status " +
            Json.text(state, "status", "unknown") +
            "), so it never recorded which files it changed and an undo could overwrite edits made since. Nothing was restored."
        );
        if (changed.isEmpty() && missing.isEmpty()) out.append(
            "\nEvery backed-up file still matches its backup, so the port changed nothing that needs restoring."
        );
        else {
            if (!changed.isEmpty()) out.append("\nDiffer from their backup: ").append(String.join(", ", changed));
            if (!missing.isEmpty()) out.append("\nMissing now: ").append(String.join(", ", missing));
            out
                .append("\nTo restore by hand, copy those files back from ")
                .append(backups)
                .append(" (same relative paths), after checking you made no edits to them since.");
        }
        return out.toString();
    }
}

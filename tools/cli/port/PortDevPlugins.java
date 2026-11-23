import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Build plugins a mod needs only while developing, set aside for the port and put back after. */
final class PortDevPlugins {

    /**
     * Gradle plugins that only choose the decompiler an IDE shows the game's source with, by plugin id, with the
     * configuration blocks they add. Old builds name versions that are no longer published anywhere they look (owo-lib,
     * Polymer and Satin name loom-quiltflower 1.3.0 and 1.6.0, which lived on a repository that is gone), so the build
     * cannot even be read. Removing such a plugin changes nothing the mod's jar or the game sees.
     */
    static final Map<String, List<String>> DEV_ONLY_PLUGINS = Map.of(
        "io.github.juuxel.loom-quiltflower",
        List.of("quiltflower", "loomQuiltflower"),
        "io.github.juuxel.loom-vineflower",
        List.of("vineflower", "loomVineflower"),
        "io.github.juuxel.loom-quiltflower-mini",
        List.of("quiltflower")
    );

    /** The decompiler-only plugin a failed build read names as missing, or null. */
    static String deadDevPlugin(String failure) {
        if (failure == null) return null;
        Matcher plugin = Pattern.compile("Plugin \\[id: '([^']+)'").matcher(failure);
        return plugin.find() && DEV_ONLY_PLUGINS.containsKey(plugin.group(1)) ? plugin.group(1) : null;
    }

    /** Originals of build scripts changed before the port's transaction exists; the transaction takes them over. */
    static Path pendingOriginals(ProjectModel project) {
        return project.state().resolve("ports/pending-before");
    }

    /**
     * Removes a decompiler-only plugin's declaration and configuration blocks from every build script of the project; each
     * original waits in pendingOriginals until the port's transaction backs it up (or restorePending puts it back).
     */
    static List<String> removeDevPlugin(ProjectModel project, String id) throws IOException {
        // The build's own scripts only: a separate build in a subfolder is not part of this port (ProjectModel.inputs).
        List<Path> scripts = project.inputs().stream().filter(p -> p.getFileName().toString().matches("build\\.gradle(\\.kts)?")).toList();
        Pattern declaration = Pattern.compile(
            "(?m)^[ \\t]*(?:id\\s*\\(?\\s*[\"']" +
            Pattern.quote(id) +
            "[\"']\\s*\\)?(?:\\s*version\\s*\\(?\\s*[\"'][^\"']*[\"']\\s*\\)?)?|apply\\s*\\(?\\s*plugin\\s*[:=]\\s*[\"']" +
            Pattern.quote(id) +
            "[\"']\\s*\\)?)[ \\t]*(?:\\r?\\n|$)"
        );
        List<String> changed = new ArrayList<>();
        for (Path script : scripts) {
            String text = Files.readString(script), after = declaration.matcher(text).replaceAll("");
            if (after.equals(text)) continue;
            for (String block : DEV_ONLY_PLUGINS.get(id)) after = Target.removeStatements(
                after,
                Pattern.compile("(?m)^[ \\t]*" + block + "\\s*\\{")
            );
            Path saved = pendingOriginals(project).resolve(project.root.relativize(script));
            Files.createDirectories(saved.getParent());
            if (!Files.exists(saved)) Files.copy(script, saved);
            Files.writeString(script, after);
            changed.add(project.root.relativize(script).toString().replace('\\', '/'));
        }
        return changed;
    }

    /** Puts back what removeDevPlugin changed when no port transaction took it over. */
    static void restorePending(ProjectModel project) throws IOException {
        Path pending = pendingOriginals(project);
        if (!Files.isDirectory(pending)) return;
        List<Path> files;
        try (var walk = Files.walk(pending)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        for (Path saved : files) Files.copy(saved, project.root.resolve(pending.relativize(saved).toString()), StandardCopyOption.REPLACE_EXISTING);
        deletePending(project);
    }

    static void deletePending(ProjectModel project) throws IOException {
        Path pending = pendingOriginals(project);
        if (!Files.isDirectory(pending)) return;
        List<Path> all;
        try (var walk = Files.walk(pending)) {
            all = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path p : all) Files.deleteIfExists(p);
    }
}

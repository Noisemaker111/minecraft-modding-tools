import java.io.*;
import java.nio.file.*;
import java.util.*;

/** One compiled copy of the mapping, mixin and game-prep tools, shared by all projects. */
final class JavaTools {

    final Build build;
    final Runtimes.Jdk jdk;
    final Path classes;
    /** ASM, which GameDiff reads the loader's jars with (through Exposure and ModNames). */
    final String asm;

    JavaTools(Build build) throws Exception {
        this.build = build;
        jdk = Runtimes.ensureAtLeast(build.harness, 25);
        List<Path> sources = new ArrayList<>(portSources(build.harness));
        sources.add(build.harness.resolve("tools/MixinAudit.java"));
        sources.add(build.harness.resolve("tools/GamePrep.java"));
        asm = build.harness.resolve("tools/perf/lib/asm-9.9.jar") + File.pathSeparator + build.harness.resolve("tools/perf/lib/asm-commons-9.9.jar");
        classes = build.harness.resolve(".mmt/portable-tools/" + Fingerprints.files(sources));
        Path ready = classes.resolve("ready");
        if (!Files.exists(ready)) {
            Files.createDirectories(classes);
            var command = jdk.javac("--release", "25", "-encoding", "UTF-8", "-cp", asm, "-d", classes.toString());
            sources.forEach(p -> command.add(p.toString()));
            var result = Run.command(command, build.project.root, Map.of(), build.report.log("tools-compile"), 120);
            build.report.stage("tools-compile", result, Map.of());
            if (result.exit() != 0) throw new IOException("Mapping tools could not compile. " + Run.tail(result.log()));
            Files.writeString(ready, "ok");
        }
    }

    /**
     * The porter's sources: every .java under tools/port, and the three files of this folder compiled with them. Naming
     * folders of tools/port gives those and tools/port's own files, for a cache key that only they change. The one list
     * of them here (bench/port_tools.py is the same rule for the checks): a file added under tools/port is compiled
     * and shipped by being there. They compile together, as each reads the others.
     */
    static List<Path> portSources(Path harness, String... folders) throws IOException {
        Path port = harness.resolve("tools/port");
        List<Path> found = new ArrayList<>();
        if (folders.length == 0) {
            try (var walk = Files.walk(port)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(found::add);
            }
        } else {
            try (var own = Files.list(port)) {
                own.filter(p -> p.toString().endsWith(".java")).forEach(found::add);
            }
            for (String folder : folders) {
                try (var walk = Files.walk(port.resolve(folder))) {
                    walk.filter(p -> p.toString().endsWith(".java")).forEach(found::add);
                }
            }
        }
        Collections.sort(found);
        for (String with : List.of("Json", "Route", "Versions")) found.add(harness.resolve("tools/cli/" + with + ".java"));
        return found;
    }

    Run.Result run(String tool, List<String> arguments, String stage) throws Exception {
        List<String> command = new ArrayList<>(List.of(jdk.java().toString(), "-cp", classes + File.pathSeparator + asm, tool));
        command.addAll(arguments);
        var result = Run.command(command, build.project.root, Map.of(), build.report.log(stage), 300);
        build.report.stage(stage, result, Map.of());
        return result;
    }

    Run.Result audit(Map<String, Object> model) throws Exception {
        Set<String> classpath = new LinkedHashSet<>();
        Set<String> roots = new LinkedHashSet<>();
        for (Object item : Json.array(model.get("projects"))) {
            var p = Json.object(item);
            for (Object c : Json.array(p.get("compile"))) classpath.addAll(
                ProjectModel.strings(Json.object(c).get("classpath"))
            );
            for (Object s : Json.array(p.get("sourceSets"))) roots.addAll(
                ProjectModel.strings(Json.object(s).get("java"))
            );
        }
        Path file = build.report.directory.resolve("classpath.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join(File.pathSeparator, classpath));
        List<String> args = new ArrayList<>(
            List.of("--root", build.project.root.toString(), "--classpath", file.toString(), "--strict")
        );
        for (String root : roots) if (Files.isDirectory(Path.of(root))) args.addAll(List.of("--src", root));
        return run("MixinAudit", args, "mixin-audit");
    }
}

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

final class Installation {

    /**
     * What a package carries beside its classes. An entry ending in / is a whole folder: a file added to it ships by
     * being there (this list named every file once, and every new probe, porter source and test had to be added here).
     * The self-test's sources are not named either: they follow from SUITES (testSources).
     */
    static final List<String> RESOURCES = List.of(
        "tools/cli/gradle.init.gradle",
        "tools/cli/gradle.resolution.gradle",
        "PERFORMANCE.md",
        "tools/cli/Json.java",
        "tools/cli/Route.java",
        "tools/cli/Versions.java",
        "tools/cli/Relocate.java",
        "tools/cli/QuietOptions.java",
        "tools/agent/",
        "tools/datacheck/",
        "tools/mixincheck/",
        "tools/nogame/",
        "tools/packcheck/",
        "tools/perf/",
        "tools/plugincheck/",
        "tools/runner/",
        "tools/servercheck/",
        "tools/port/",
        "tools/MixinAudit.java",
        "tools/MixinTriage.java",
        "tools/GamePrep.java",
        "knowledge/",
        "coverage/changes/index.json"
    );

    /** The jar entry listing every resource the package carries, so unpacking restores exactly what was packed. */
    static final String RESOURCE_LIST = "mmt-resources.txt";

    /**
     * Every file a package carries: RESOURCES with each directory (an entry ending in /) expanded, and the change
     * index's step files for updates into unobfuscated releases, which port's by-name lookups read (Port.byName).
     * A hand-kept list once missed knowledge/meaning-changes.json and perf-patterns.json, so the shipped port and
     * perf-optimize silently ran without them.
     */
    static List<String> resources(Path harness) throws IOException {
        List<String> files = new ArrayList<>(testSources(harness));
        for (String name : RESOURCES) {
            if (!name.endsWith("/")) {
                files.add(name);
                continue;
            }
            try (var walk = Files.walk(harness.resolve(name))) {
                walk
                    .filter(Files::isRegularFile)
                    .map(p -> harness.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .forEach(files::add);
            }
        }
        try (var steps = Files.list(harness.resolve("coverage/changes"))) {
            steps
                .map(p -> p.getFileName().toString())
                .filter(n -> n.endsWith(".json") && n.contains("__"))
                .filter(n -> {
                    String[] sides = n.substring(0, n.length() - 5).split("__");
                    return Versions.compare(sides[1], Route.FIRST_UNOBFUSCATED) >= 0;
                })
                .sorted()
                .forEach(n -> files.add("coverage/changes/" + n));
        }
        return files;
    }

    /**
     * The self-test's sources: each suite's class, and every other file of tools/tests one of them names (a shared
     * fixture, a suite another suite runs), followed until nothing new is named.
     */
    static List<String> testSources(Path harness) throws IOException {
        Path tests = harness.resolve("tools/tests");
        Map<String, String> sources = new TreeMap<>();
        try (var list = Files.list(tests)) {
            for (Path file : list.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                sources.put(name.substring(0, name.length() - 5), Files.readString(file));
            }
        }
        Set<String> shipped = new TreeSet<>();
        Deque<String> open = new ArrayDeque<>();
        for (Suite suite : SUITES) open.add(suite.main());
        while (!open.isEmpty()) {
            String name = open.poll();
            if (!sources.containsKey(name) || !shipped.add(name)) continue;
            var words = java.util.regex.Pattern.compile("\\b[A-Z]\\w*\\b").matcher(sources.get(name));
            while (words.find()) open.add(words.group());
        }
        return shipped.stream().map(name -> "tools/tests/" + name + ".java").toList();
    }

    static synchronized Path home() throws Exception {
        Path location = Path.of(Mmt.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        // A child started by perf-optimize (PerfOptimize.childJava) is handed its parent's resources; a test hands its own.
        // Not mmt.home: the portable launchers set that to the ZIP's folder, which has no resources.
        if (System.getProperty("mmt.resources") != null) return Path.of(System.getProperty("mmt.resources"))
            .toAbsolutePath()
            .normalize();
        if (!location.toString().endsWith(".jar")) return Path.of(System.getProperty("mmt.home", "."))
            .toAbsolutePath()
            .normalize();
        Path directory = Path.of(
            System.getProperty("user.home"),
            ".mmt/packages",
            Fingerprints.bytes(Files.readAllBytes(location))
        );
        // The folder is named for the jar's bytes, so once unpacked whole it never changes: a command reads nothing more.
        Path unpacked = directory.resolve(".unpacked");
        if (Files.isRegularFile(unpacked)) return directory;
        Files.createDirectories(directory);
        // Commands from one package often start together (the checks, the desktop app): one unpacks while the others
        // wait, so none sees a file another is replacing. The JVM's own threads share one lock, hence synchronized.
        try (
            var channel = java.nio.channels.FileChannel.open(
                directory.resolve(".unpack.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            );
            var lock = channel.lock();
            var jar = new JarFile(location.toFile())
        ) {
            if (Files.isRegularFile(unpacked)) return directory;
            List<String> names;
            try (var input = jar.getInputStream(jar.getJarEntry(RESOURCE_LIST))) {
                names = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().toList();
            }
            for (String name : names) {
                Path file = directory.resolve(name);
                byte[] bytes;
                try (var input = jar.getInputStream(jar.getJarEntry(name))) {
                    bytes = input.readAllBytes();
                }
                if (!Files.isRegularFile(file) || !Arrays.equals(Files.readAllBytes(file), bytes)) {
                    Files.createDirectories(file.getParent());
                    Path temporary = Files.createTempFile(file.getParent(), "resource-", ".tmp");
                    Files.write(temporary, bytes);
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            // Last, so a folder an interrupted command left half unpacked is unpacked again.
            Files.writeString(unpacked, "");
        }
        return directory;
    }

    static Map<String, Object> pack(Path harness, Mmt.Report report) throws Exception {
        Runtimes.Jdk jdk = Runtimes.ensureAtLeast(harness, 17);
        Path directory = report.root.resolve("build/distributions");
        Files.createDirectories(directory);
        Path classes = report.directory.resolve("package-classes");
        Files.createDirectories(classes);
        List<String> command = new ArrayList<>(
            List.of(
                jdk.home().resolve("bin/" + (Runtimes.WINDOWS ? "javac.exe" : "javac")).toString(),
                "--release",
                "17",
                "-encoding",
                "UTF-8",
                "-d",
                classes.toString()
            )
        );
        try (var files = Files.walk(harness.resolve("tools/cli"))) {
            files
                .filter(p -> p.toString().endsWith(".java") && !p.getFileName().toString().equals("Bootstrap.java"))
                .sorted()
                .forEach(p -> command.add(p.toString()));
        }
        var compile = Run.command(command, report.root, Map.of(), report.log("package-compile"), 120);
        report.stage("package-compile", compile, Map.of());
        if (compile.exit() != 0) throw new IOException(Run.tail(compile.log()));
        // The entry point is built for Java 8 so `java -jar` on an old Java says which Java it needs instead of a class-version stack trace.
        var launcher = Run.command(
            List.of(
                command.get(0),
                "--release",
                "8",
                "-nowarn",
                "-Xlint:-options",
                "-encoding",
                "UTF-8",
                "-d",
                classes.toString(),
                harness.resolve("tools/launcher/MmtLauncher.java").toString()
            ),
            report.root,
            Map.of(),
            report.log("package-launcher"),
            60
        );
        report.stage("package-launcher", launcher, Map.of());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", launcher.exit() == 0 ? "MmtLauncher" : "Mmt");
        Path target = directory.resolve("mmt.jar"), temporary = directory.resolve("mmt.jar.tmp");
        try (
            var output = new JarOutputStream(Files.newOutputStream(temporary), manifest);
            var files = Files.walk(classes)
        ) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                var entry = new JarEntry(classes.relativize(file).toString().replace('\\', '/'));
                entry.setTime(0);
                output.putNextEntry(entry);
                Files.copy(file, output);
                output.closeEntry();
            }
            List<String> resources = resources(harness);
            for (String name : resources) {
                var entry = new JarEntry(name);
                entry.setTime(0);
                output.putNextEntry(entry);
                Files.copy(harness.resolve(name), output);
                output.closeEntry();
            }
            var list = new JarEntry(RESOURCE_LIST);
            list.setTime(0);
            output.putNextEntry(list);
            output.write(String.join("\n", resources).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        String sha = Fingerprints.bytes(Files.readAllBytes(target));
        Files.writeString(directory.resolve("mmt.jar.sha256"), sha + "  mmt.jar\n");
        Path zip = directory.resolve("mmt-portable.zip"), zipTemporary = directory.resolve(
            "mmt-portable.zip.tmp"
        );
        Map<String, byte[]> contents = new LinkedHashMap<>();
        contents.put("mmt.jar", Files.readAllBytes(target));
        contents.put("mmt.jar.sha256", Files.readAllBytes(directory.resolve("mmt.jar.sha256")));
        // modaccel and modaccel.cmd are the old names: they print a one-line note and forward to mmt.
        for (String file : List.of("mmt", "mmt.cmd", "modaccel", "modaccel.cmd", "LICENSE", "NOTICE", "PERFORMANCE.md")) contents.put(
            file,
            Files.readAllBytes(harness.resolve(file))
        );
        contents.put("bootstrap-jre.ps1", Files.readAllBytes(harness.resolve("tools/bootstrap-jre.ps1")));
        List<String> desktop = desktopApps(harness, target, contents);
        String desktopText = desktop.isEmpty()
            ? ""
            : "Desktop app: open " +
            String.join(" or ", desktop) +
            " to port .jar or source mods, switch loaders, fix modpacks, run checks, or profile Minecraft without a terminal.\n\n";
        contents.put(
            "GETTING_STARTED.txt",
            ("Minecraft Modding Tools\n\n" +
                desktopText +
                "Command line: extract the entire ZIP, then run ./mmt (macOS/Linux) or mmt.cmd (Windows) from this folder.\n" +
                "Windows needs no Java installed: if Java 17 or newer is missing, the launcher downloads a verified Java 21\n" +
                "runtime once into %LOCALAPPDATA%\\mmt\\jre. macOS and Linux need Java 17 or newer on PATH or JAVA_HOME.\n\n" +
                "Examples:\n" +
                "  mmt.cmd port --jar my-mod-1.21.1.jar --to 1.21.2\n" +
                "  mmt.cmd port --root \"C:\\path to\\your mod\" --from 1.21.5 --to 1.21.6 --fix\n" +
                "  mmt.cmd pack-check --pack my-pack.mrpack --fix\n" +
                "  mmt.cmd ui-check --root \"C:\\path to\\your mod\"\n" +
                "  mmt.cmd help\n\n" +
                "No Minecraft code or game assets are included. See LICENSE and NOTICE for terms and attribution.\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        try (var output = new ZipOutputStream(Files.newOutputStream(zipTemporary))) {
            for (var item : contents.entrySet()) {
                var entry = new ZipEntry(item.getKey());
                entry.setTime(0);
                output.putNextEntry(entry);
                output.write(item.getValue());
                output.closeEntry();
            }
        }
        // ZipOutputStream has no Unix-mode API; mark the shell launcher executable in central directory.
        byte[] archive = Files.readAllBytes(zipTemporary);
        int end = -1;
        for (int p = archive.length - 22; p >= Math.max(0, archive.length - 65557); p--) {
            if (u32(archive, p) == 0x06054b50L && p + 22 + u16(archive, p + 20) == archive.length) {
                end = p;
                break;
            }
        }
        if (end < 0) throw new IOException("Missing ZIP central directory");
        long start = u32(archive, end + 16), size = u32(archive, end + 12);
        if (start + size != end || start > Integer.MAX_VALUE) throw new IOException(
            "Unsupported ZIP central directory"
        );
        int p = (int) start, limit = end;
        boolean executable = false;
        while (p < limit) {
            if (p + 46 > limit || u32(archive, p) != 0x02014b50L) throw new IOException(
                "Invalid ZIP central directory entry"
            );
            int nameLength = u16(archive, p + 28), next =
                p + 46 + nameLength + u16(archive, p + 30) + u16(archive, p + 32);
            if (next > limit) throw new IOException("Invalid ZIP central directory entry length");
            String name = new String(archive, p + 46, nameLength, java.nio.charset.StandardCharsets.UTF_8);
            if (name.equals("mmt") || name.equals("modaccel") || name.equals(DESKTOP_LINUX)) {
                archive[p + 5] = 3; // made by Unix
                int mode = 0100755 << 16;
                for (int i = 0; i < 4; i++) archive[p + 38 + i] = (byte) (mode >>> (i * 8));
                executable = true;
            }
            p = next;
        }
        if (!executable || p != limit) throw new IOException("Missing shell launcher ZIP entry");
        Files.write(zipTemporary, archive);
        Files.move(zipTemporary, zip, StandardCopyOption.REPLACE_EXISTING);
        String zipSha = Fingerprints.bytes(Files.readAllBytes(zip));
        Files.writeString(directory.resolve("mmt-portable.zip.sha256"), zipSha + "  mmt-portable.zip\n");
        return Json.map(
            "jar",
            target.toString(),
            "bytes",
            Files.size(target),
            "sha256",
            sha,
            "zip",
            zip.toString(),
            "zipBytes",
            Files.size(zip),
            "zipSha256",
            zipSha,
            "desktopApp",
            desktop,
            "requires",
            "Java 17 or newer; project builds may require other JDKs",
            "published",
            false
        );
    }

    static final String DESKTOP_WINDOWS = "mmt-desktop.exe", DESKTOP_LINUX = "mmt-desktop-linux";

    /**
     * The desktop app (app/, built with cargo build --release) goes into the ZIP once it is built: the Windows exe,
     * and an ELF binary as the Linux one. Without a build the package is the command line alone.
     */
    static List<String> desktopApps(Path harness, Path jar, Map<String, byte[]> contents) throws Exception {
        List<String> added = new ArrayList<>();
        Path app = harness.resolve("app"), release = app.resolve("target/release");
        Path exe = release.resolve(DESKTOP_WINDOWS);
        // A built app older than its sources is not the app: rebuild it with cargo, or refuse to package it.
        for (Path binary : List.of(exe, release.resolve("mmt-desktop"))) {
            if (!Files.isRegularFile(binary) || newerSource(app, binary) == null) continue;
            Path cargo = onPath("cargo");
            if (cargo != null) {
                ProcessBuilder pb = new ProcessBuilder(cargo.toString(), "build", "--release").directory(app.toFile()).inheritIO();
                pb.environment().put("MMT_EMBED_JAR", jar.toAbsolutePath().toString());
                Process build = pb.start();
                if (build.waitFor() != 0) throw new IOException("cargo build --release failed in " + app + "; the desktop app was not packaged.");
            }
            Path newer = newerSource(app, binary);
            if (newer != null) throw new IOException(
                binary + " is older than " + newer + ", so it is not the app in this checkout. Run cargo build --release in " + app +
                " (cargo was " + (cargo == null ? "not found" : "run, and did not rebuild it") + ") or delete the stale build, then package again."
            );
        }
        if (Files.isRegularFile(exe)) {
            contents.put(DESKTOP_WINDOWS, Files.readAllBytes(exe));
            added.add(DESKTOP_WINDOWS);
        }
        Path unix = release.resolve("mmt-desktop");
        byte[] bytes = Files.isRegularFile(unix) ? Files.readAllBytes(unix) : new byte[0];
        boolean elf = bytes.length > 4 && bytes[0] == 0x7f && bytes[1] == 'E' && bytes[2] == 'L' && bytes[3] == 'F';
        if (elf) {
            contents.put(DESKTOP_LINUX, bytes);
            added.add(DESKTOP_LINUX);
        }
        return added;
    }

    /** A file of the app's sources (anything in app/ but its build output and hidden folders) changed after a built binary; null when none. */
    static Path newerSource(Path app, Path binary) throws IOException {
        if (!Files.isDirectory(app)) return null;
        var built = Files.getLastModifiedTime(binary);
        try (var walk = Files.walk(app)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                Path rel = app.relativize(p);
                String first = rel.getName(0).toString();
                if (first.equals("target") || first.startsWith(".")) continue;
                if (Files.getLastModifiedTime(p).compareTo(built) > 0) return p;
            }
        }
        return null;
    }

    /** An executable on PATH (with Windows' extensions), or null. */
    static Path onPath(String name) {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            for (String ext : List.of("", ".exe", ".cmd", ".bat")) {
                try {
                    Path candidate = Path.of(dir).resolve(name + ext);
                    if (!dir.isBlank() && Files.isRegularFile(candidate)) return candidate;
                } catch (java.nio.file.InvalidPathException unusable) {
                    break;
                }
            }
        }
        return null;
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
    }

    private static long u32(byte[] bytes, int offset) {
        return (u16(bytes, offset) & 65535L) | ((u16(bytes, offset + 2) & 65535L) << 16);
    }

    /** One self-test suite: a class in tools/tests with a main, and what it is given beside the classpath. */
    record Suite(String name, String main, boolean wantsHarness, boolean wantsScratch) {}

    // perf is listed first: the suites run side by side and it takes longest (its captures last seconds), so it starts first.
    static final List<Suite> SUITES = List.of(
        new Suite("perf", "PerfTest", true, true),
        new Suite("desugar", "DesugarTests", true, true),
        new Suite("port", "PortTests", true, true),
        new Suite("port-scaffold", "PortScaffoldTests", true, true),
        new Suite("port-repair", "PortRepairTests", true, true),
        new Suite("convert", "ConvertTests", true, true),
        new Suite("build-script", "BuildScriptTests", true, true),
        new Suite("command", "CommandTests", true, true),
        new Suite("game", "GameTests", true, true),
        new Suite("jdk", "JdkDownloadsTest", false, false),
        new Suite("dependency", "DependenciesTest", false, false),
        new Suite("manifest", "FabricMetadataTest", false, false),
        new Suite("hardening", "HardeningTest", false, false),
        new Suite("trust", "TrustTest", false, false),
        new Suite("mixin-check", "MixinCheckTest", true, false),
        new Suite("start-check", "StartCheckTest", true, false),
        new Suite("mod-files", "ModFileTests", true, false),
        new Suite("error-messages", "ErrorMessageTests", true, false),
        new Suite("project-safety", "ProjectSafetyTests", true, false),
        new Suite("pack-metadata", "PackMetadataTests", true, false),
        new Suite("pack-logs", "PackLogTests", true, false),
        new Suite("pack-server", "PackServerTests", true, false),
        new Suite("pack-repairs", "PackRepairTests", true, false),
        new Suite("rules", "RulesTest", true, false)
    );

    /**
     * Runs the suites (all of them, or the comma-separated names in only) and reports the failed ones first,
     * each with the end of its log, so the cause is the first thing read. A passed suite is one line.
     */
    static Map<String, Object> test(Path harness, Mmt.Report report, String only) throws Exception {
        List<String> wanted = only.isBlank() ? List.of() : Arrays.stream(only.split(",")).map(String::strip).toList();
        List<String> known = SUITES.stream().map(Suite::name).toList();
        for (String name : wanted) {
            if (!known.contains(name)) {
                throw new IllegalArgumentException(
                    "self-test has no suite " + name + ". Suites: " + String.join(", ", known) + "."
                );
            }
        }
        List<Suite> suites = SUITES.stream().filter(s -> wanted.isEmpty() || wanted.contains(s.name())).toList();

        Runtimes.Jdk jdk = Runtimes.ensureAtLeast(harness, 17);
        Path classes = report.directory.resolve("test-classes");
        Files.createDirectories(classes);
        Path own = Path.of(Mmt.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> javac = new ArrayList<>(
            List.of(
                jdk.home().resolve("bin/" + (Runtimes.WINDOWS ? "javac.exe" : "javac")).toString(),
                "--release",
                "17",
                "-encoding",
                "UTF-8",
                "-cp",
                own.toString(),
                // A suite may use a shared fixture class beside it (PackCheckFixtures), or a shared
                // production helper beside tools/ (MixinTriage); javac compiles them on demand. Agent
                // sources resolve the same way (the frame-hook table in GameTests); their ASM import
                // rides on the jar mmt ships for its own bytecode tools.
                "-sourcepath",
                harness.resolve("tools/tests").toString() + File.pathSeparator + harness.resolve("tools").toString() + File.pathSeparator + harness.resolve("tools/agent").toString(),
                "-d",
                classes.toString()
            )
        );
        for (Suite suite : suites) javac.add(harness.resolve("tools/tests/" + suite.main() + ".java").toString());
        Path bundledAsm = Game.bundledAsm(harness);
        if (bundledAsm != null) javac.set(javac.indexOf("-cp") + 1, own + File.pathSeparator + bundledAsm);
        var compile = Run.command(javac, report.root, Map.of(), report.log("test-compile"), 120);
        report.stage("test-compile", compile, Map.of());
        if (compile.exit() != 0) throw new IOException(Run.tail(compile.log()));

        // The suites share nothing (each makes its own scratch folder), so they run side by side, one per core; the
        // report keeps their listed order.
        List<Path> logs = new ArrayList<>();
        for (Suite suite : suites) logs.add(report.log(suite.name() + "-tests"));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        List<java.util.concurrent.Future<Run.Result>> runs = new ArrayList<>();
        try {
            for (int i = 0; i < suites.size(); i++) {
                Suite suite = suites.get(i);
                Path log = logs.get(i);
                List<String> command = new ArrayList<>(
                    List.of(jdk.java().toString(), "-cp", own + File.pathSeparator + classes, suite.main())
                );
                if (suite.wantsHarness()) command.add(harness.toString());
                if (suite.wantsScratch()) command.add(report.directory.resolve("fixtures").toString());
                runs.add(pool.submit(() -> Run.command(command, report.root, Map.of(), log, 120)));
            }
        } finally {
            pool.shutdown();
        }
        List<Object> results = new ArrayList<>();
        List<String> failed = new ArrayList<>(), passedLines = new ArrayList<>(), failedText = new ArrayList<>();
        for (int i = 0; i < suites.size(); i++) {
            Suite suite = suites.get(i);
            Run.Result run;
            try {
                run = runs.get(i).get();
            } catch (java.util.concurrent.ExecutionException e) {
                throw e.getCause() instanceof Exception cause ? cause : e;
            }
            report.stage(suite.name() + "-tests", run, Map.of());
            boolean ok = run.exit() == 0;
            results.add(
                Json.map("name", suite.name(), "ok", ok, "seconds", run.seconds(), "log", run.log().toString())
            );
            String tail = Run.tail(run.log()).strip();
            if (ok) {
                passedLines.add("  " + suite.name() + ": " + tail.substring(tail.lastIndexOf('\n') + 1).strip());
            } else {
                failed.add(suite.name());
                failedText.add(
                    "FAILED " +
                    suite.name() +
                    " (mmt self-test --only " +
                    suite.name() +
                    " runs it alone)\n" +
                    tail +
                    "\nLog: " +
                    run.log()
                );
            }
        }
        String summary = failed.isEmpty()
            ? suites.size() + " of " + suites.size() + " suites passed"
            : failed.size() + " of " + suites.size() + " suites failed: " + String.join(", ", failed);
        String details =
            (failedText.isEmpty() ? "" : String.join("\n\n", failedText) + "\n\n") +
            summary +
            (passedLines.isEmpty() ? "" : "\nPassed:\n" + String.join("\n", passedLines));
        return Json.map("ok", failed.isEmpty(), "suites", results, "failed", failed, "details", details);
    }
}

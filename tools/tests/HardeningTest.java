import java.io.*;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Each case triggers a failure mmt used to report badly (or as a pass) and checks the message. */
public final class HardeningTest {

    static int count;

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createTempDirectory("mmt-hardening");
        try {
            missingDependency(scratch);
            nonCompilerFailure(scratch);
            mixinAudit(scratch);
            interruptedUndo(scratch);
            neoForgeUnchecked(scratch);
            serverDeath(scratch);
            lookupFailures();
            badRoot(scratch);
            timeouts(scratch);
            loomVersions();
            missingWrapper(scratch);
            newerGradleOfLine(scratch);
            wrapperOrigin(scratch);
            publishingSecrets(scratch);
            cachedReload(scratch);
            failedStartEndsGame(scratch);
            System.out.println(
                count +
                " hardening checks passed: missing dependency, non-compiler failure, mixin audit summary, interrupted undo, NeoForge unchecked, server death, lookup errors, bad root, Gradle timeout, missing wrapper, newer Gradle of the line, wrapper origin and checksum, publishing secrets, reload resolves as compile, failed start ends its game"
            );
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }

    static final String CREATE_LOG =
        """
        > Task :compileJava FAILED

        FAILURE: Build failed with an exception.

        * What went wrong:
        Execution failed for task ':compileJava'.
        > Could not resolve all files for configuration ':compileClasspath'.
           > Could not find com.simibubi.create:create-1.21.1:6.0.4-55.
             Searched in the following locations:
               - https://maven.createmod.net/com/simibubi/create/create-1.21.1/6.0.4-55/create-1.21.1-6.0.4-55.pom
               - https://maven.neoforged.net/releases/com/simibubi/create/create-1.21.1/6.0.4-55/create-1.21.1-6.0.4-55.pom
             Required by:
                 root project 'reactive'
           > Could not find dev.engine-room.flywheel:flywheel-neoforge-api-1.21.1:1.0.2.
             Required by:
                 root project 'reactive'

        * Try:
        > Run with --stacktrace option to get the stack trace.
        """ +
        "Gradle footer line\n".repeat(40);

    /** A missing dependency names its coordinate and where Gradle looked, even below a long footer. */
    static void missingDependency(Path scratch) throws Exception {
        Path log = scratch.resolve("missing.log");
        Files.writeString(log, CREATE_LOG);
        String text = Run.gradleFailure(log);
        require(
            text.startsWith(
                "Gradle could not resolve 2 dependencies: com.simibubi.create:create-1.21.1:6.0.4-55, dev.engine-room.flywheel:flywheel-neoforge-api-1.21.1:1.0.2."
            ),
            "missing dependencies are named with their full coordinates: " + text
        );
        require(
            Build.discoveryHeadline(text).startsWith("Missing dependency:"),
            "a build that cannot be read for a missing library says so first: " + Build.discoveryHeadline(text)
        );
        require(
            Build.discoveryHeadline(
                Run.unreachable(
                    List.of(
                        "> Could not GET 'https://maven.example.com/a.pom'.",
                        "Caused by: java.net.UnknownHostException: maven.example.com"
                    )
                )
            ).startsWith("No connection"),
            "a network failure is headed as one"
        );
        // Gradle's words when a build's plugins { } block names a plugin no repository has, as a real build printed them.
        String plugin = Build.discoveryHeadline(
            "Plugin [id: 'com.example.ghost', version: '1.0'] was not found in any of the following sources:"
        );
        require(
            plugin.startsWith(
                "Missing build plugin: this project's build uses the Gradle plugin com.example.ghost 1.0,"
            ),
            "a missing Gradle plugin is named: " + plugin
        );
        require(
            text.contains("https://maven.createmod.net") && text.contains("https://maven.neoforged.net"),
            "the searched repositories are named"
        );
        require(
            text.contains("Execution failed for task ':compileJava'.") && !text.contains("Gradle footer"),
            "Gradle's own failure block is shown, not the footer"
        );
        Path plain = scratch.resolve("plain.log");
        Files.writeString(plain, "Caused by: java.lang.IllegalStateException: boom\n" + "tail\n".repeat(30));
        require(
            Run.gradleFailure(plain).contains("IllegalStateException: boom"),
            "a log without a Gradle block still shows its cause"
        );
    }

    /** A build that fails with no compiler error is not called a semantic migration. */
    static void nonCompilerFailure(Path scratch) throws Exception {
        Path log = scratch.resolve("missing.log");
        String reason = Port.failureReason("1.21.4", false, null, 0, 1, "", log);
        require(
            reason.startsWith("The build failed without compiler errors") &&
            reason.contains("com.simibubi.create:create-1.21.1:6.0.4-55") &&
            !reason.contains("semantic migration"),
            "a dependency failure reads as a build failure with its block: " + reason
        );
        String packaged = Port.failureReason("1.21.4", true, Json.map("ok", true, "checked", true), 0, 1, "", log);
        require(
            packaged.contains("packaged build failed without a compiler error") && packaged.contains("Could not find"),
            "a failed packaged build shows why"
        );
        require(
            Port.failureReason("1.21.4", false, null, 3, 2, "", log).startsWith("3 compiler error(s) remain"),
            "real compiler errors keep the migration message"
        );
        String unchecked = Port.failureReason(
            "1.21.4",
            true,
            Json.map("ok", false, "checked", false, "reason", "No client run task (runClient) in the selected project"),
            0,
            1,
            "",
            log
        );
        require(
            unchecked.contains("not checked by Mixin") && unchecked.contains("No client run task"),
            "mixins Mixin could not check are named as unchecked, never passed: " + unchecked
        );
        String failed = Port.failureReason("1.21.4", true, Json.map("ok", false, "checked", true), 0, 1, "", log);
        require(failed.contains("Mixin did not apply every mixin cleanly"), "a Mixin failure reads as one: " + failed);
    }

    /** The port result says what the audit looked at; "checked" never stands for nothing checked. */
    static void mixinAudit(Path scratch) throws Exception {
        Path log = scratch.resolve("mixin.log");
        Files.writeString(log, "# 0 mixins, nothing to audit\n");
        require(PortMixins.auditSummary(log).equals("no mixins to audit"), "no mixins is reported as nothing to audit");
        Files.writeString(
            log,
            "note: a.java: target Foo is not in the given jars, not checked\n# 2 mixins, 1 target, 4 members checked, 0 dead, 1 targets not in the jar\n"
        );
        String summary = PortMixins.auditSummary(log);
        require(
            summary.startsWith("mixin targets checked (2 mixins, 1 target, 4 members checked, 0 dead)") &&
            summary.contains("1 mixin target(s) are in no jar"),
            "skipped mixin targets are counted in the result: " + summary
        );
    }

    /**
     * An undo of a port that never finished puts the backups back, keeps every file it overwrites
     * beside them first (so a hand edit made after the port stopped is never lost), and says so.
     * It used to refuse and leave the modder to copy the backups by hand.
     */
    static void interruptedUndo(Path scratch) throws Exception {
        Path root = scratch.resolve("interrupted");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("settings.gradle"), "");
        Path source = root.resolve("src/A.java");
        byte[] original = "class A {}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(source, original);
        ProjectModel project = new ProjectModel(root);
        Path transaction = project.state().resolve("ports/interrupted"), backup = transaction.resolve(
            "before/src/A.java"
        );
        Files.createDirectories(backup.getParent());
        Files.write(backup, original);
        Json.save(
            transaction.resolve("transaction.json"),
            Json.map(
                "root",
                project.root.toString(),
                "before",
                Json.map("src/A.java", Fingerprints.bytes(original)),
                "after",
                Map.of(),
                "removed",
                List.of(),
                "plan",
                Json.map("edits", List.of("src/A.java"), "removals", List.of()),
                "status",
                "in-progress"
            )
        );
        Files.writeString(source, "class A { half edited }\n");
        Map<String, Object> undone = PortJournal.undo(project, transaction.toString());
        String details = String.valueOf(undone.get("details"));
        require(
            Boolean.TRUE.equals(undone.get("ok")) &&
            details.contains("stopped part-way") &&
            details.contains("src/A.java") &&
            details.contains(transaction.resolve("current-before-undo").toString()),
            "interrupted undo says what it restored and where the overwritten copies are: " + details
        );
        require(
            Arrays.equals(Files.readAllBytes(source), original),
            "interrupted undo restores the exact original bytes"
        );
        require(
            Files.readString(transaction.resolve("current-before-undo/src/A.java")).contains("half edited"),
            "the half-edited file is kept, not lost"
        );
        Json.save(
            transaction.resolve("transaction.json"),
            Json.map(
                "root",
                project.root.toString(),
                "before",
                Map.of(),
                "after",
                Map.of(),
                "removed",
                List.of(),
                "status",
                "undone"
            )
        );
        try {
            PortJournal.undo(project, transaction.toString());
            throw new AssertionError("second undo passed");
        } catch (IOException expected) {
            require(expected.getMessage().contains("already undone"), "a second undo says it was already undone");
        }
    }

    /** A NeoForge mod dependency is listed as unchecked instead of being skipped silently. */
    static void neoForgeUnchecked(Path scratch) throws Exception {
        Path jar = scratch.resolve("create-1.21.1-6.0.4.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/neoforge.mods.toml"));
            zip.write("modId=\"create\"\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path library = scratch.resolve("gson-2.10.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(library))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("com/google/gson/Gson.class"));
            zip.closeEntry();
        }
        Map<String, Object> project = Json.map(
            "dependencies",
            List.of(
                Json.map(
                    "group",
                    "com.simibubi.create",
                    "name",
                    "create-1.21.1",
                    "version",
                    "6.0.4",
                    "configuration",
                    "implementation"
                ),
                Json.map(
                    "group",
                    "com.google.code.gson",
                    "name",
                    "gson",
                    "version",
                    "2.10",
                    "configuration",
                    "implementation"
                )
            ),
            "artifacts",
            List.of(
                Json.map(
                    "group",
                    "com.simibubi.create",
                    "name",
                    "create-1.21.1",
                    "version",
                    "6.0.4",
                    "file",
                    jar.toString()
                ),
                Json.map("group", "com.google.code.gson", "name", "gson", "version", "2.10", "file", library.toString())
            )
        );
        Target.Setup setup = new Target.Setup(null, project, "1.21.4", List.of());
        Dependencies.resolve(setup, "neoforge");
        require(
            setup.unchecked.size() == 1 &&
            "com.simibubi.create:create-1.21.1".equals(setup.unchecked.get(0).get("artifact")),
            "a NeoForge mod jar is listed as unchecked; a plain library is not"
        );
        require(setup.blockers.isEmpty() && setup.applied.isEmpty(), "listing it unchecked blocks nothing");
        require(
            Port.uncheckedText(setup.unchecked).contains("com.simibubi.create:create-1.21.1 6.0.4") &&
            Port.uncheckedText(setup.unchecked).contains("1.21.4"),
            "the result names it and the target"
        );
    }

    /** A dedicated server that dies says how: exit code, crash report description, log. */
    static void serverDeath(Path scratch) throws Exception {
        Path crash = scratch.resolve("crash-2026.txt");
        Files.writeString(
            crash,
            "---- Minecraft Crash Report ----\nTime: now\nDescription: Exception in server tick loop\n\njava.lang.NullPointerException: zombie spawner\n\tat demo.Spawner.tick(Spawner.java:10)\n\nA detailed walkthrough\n"
        );
        Path log = scratch.resolve("server.log");
        Files.writeString(
            log,
            "[Server thread/INFO]: Done (3.2s)!\n[Server thread/ERROR]: This crash report has been saved to: " +
            crash +
            "\n"
        );
        String text = Server.death(1, log);
        require(
            text.contains("launcher exit code 1") &&
            text.contains("Description: Exception in server tick loop") &&
            text.contains("zombie spawner") &&
            text.contains("Server log: " + log),
            "server death names exit, crash description and log: " + text
        );
        Files.writeString(
            log,
            "[Server thread/ERROR]: Failed to start the minecraft server\nCaused by: java.net.BindException: Address already in use\n"
        );
        require(Server.death(1, log).contains("BindException"), "without a crash report, the error lines are shown");
        String netstat =
            "  Proto  Local Address          Foreign Address        State           PID\n  TCP    0.0.0.0:25565          0.0.0.0:0              LISTENING       4242\n  TCP    0.0.0.0:255650         0.0.0.0:0              LISTENING       1\n";
        require(
            Long.valueOf(4242).equals(Server.parseListeningPid(netstat, 25565, true)),
            "the real server pid comes from the listening port"
        );
        require(
            Long.valueOf(77).equals(Server.parseListeningPid("77\n", 25565, false)) &&
            Server.parseListeningPid("", 25565, true) == null,
            "lsof output and no listener"
        );
    }

    /** A network error is never reported as "not published", and keeps the real error and the repositories. */
    static void lookupFailures() throws Exception {
        var selection = new Dependencies.Selection("a:b:1", "a:b:2", "", "", false, "");
        Map<String, Object> project = Json.map(
            "repositories",
            List.of("https://maven.example.net/releases", "https://repo.example.org/maven")
        );
        try {
            Dependencies.verify(
                selection,
                new String[] { "a", "b", "2" },
                project,
                "1.21.6",
                url -> {
                    throw Target.unreachable(url, new ConnectException("Connection refused"));
                },
                url -> null
            );
            throw new AssertionError("unreachable repositories accepted");
        } catch (Target.LookupFailure expected) {
            require(
                expected.getMessage().contains("Could not check") &&
                expected.getMessage().contains("Connection refused") &&
                expected.getMessage().contains("maven.example.net") &&
                expected.getMessage().contains("repo.example.org") &&
                !expected.getMessage().contains("is not published"),
                "network errors keep the real error and the repository list: " + expected.getMessage()
            );
        }
        try {
            Dependencies.verify(
                selection,
                new String[] { "a", "b", "2" },
                project,
                "1.21.6",
                url -> {
                    throw new IOException("Target dependency lookup failed (HTTP 404): " + url);
                },
                url -> null
            );
            throw new AssertionError("missing version accepted");
        } catch (IOException expected) {
            require(
                !(expected instanceof Target.LookupFailure) && expected.getMessage().contains("is not published"),
                "a real 404 still reads as not published"
            );
        }
        require(
            Target.unreachable("https://api.modrinth.com/v2/x", new IOException())
                .getMessage()
                .contains("api.modrinth.com (IOException)"),
            "an exception without a message still names its type and host"
        );
        List<Object> versions = List.of(
            Json.map("version_number", "2.0-beta", "version_type", "beta"),
            Json.map("version_number", "1.9", "version_type", "release"),
            Json.map("version_number", "1.8-alpha", "version_type", "alpha")
        );
        require(
            "1.9".equals(Json.object(Dependencies.stableFirst(versions).get(0)).get("version_number")),
            "a full release is preferred over a newer beta"
        );
        require(
            "2.0-beta".equals(
                    Json.object(Dependencies.stableFirst(versions.subList(0, 1)).get(0)).get("version_number")
                ),
            "a beta is still used when nothing else exists"
        );
        require(
            !Dependencies.stable(Json.map("version_type", "alpha")) && Dependencies.stable(Json.map()),
            "version types"
        );
    }

    /** A bad --root or a folder with no Gradle build gets a plain message. */
    static void badRoot(Path scratch) throws Exception {
        try {
            new ProjectModel(scratch.resolve("no-such-mod"));
            throw new AssertionError("missing root accepted");
        } catch (IOException expected) {
            require(
                expected.getMessage().contains("does not exist") && expected.getMessage().contains("no-such-mod"),
                "a missing root says so: " + expected.getMessage()
            );
        }
        Path file = scratch.resolve("mod.txt");
        Files.writeString(file, "");
        try {
            new ProjectModel(file);
            throw new AssertionError("file root accepted");
        } catch (IOException expected) {
            require(expected.getMessage().contains("is a file, not a folder"), "a file root says so");
        }
        Path empty = Files.createDirectories(scratch.resolve("empty"));
        try {
            new ProjectModel(empty).requireGradleBuild();
            throw new AssertionError("folder without a build accepted");
        } catch (IOException expected) {
            require(
                expected.getMessage().startsWith("No Gradle build in") &&
                expected.getMessage().contains("build.gradle"),
                "a folder without a build says so: " + expected.getMessage()
            );
        }
        Files.writeString(empty.resolve("settings.gradle.kts"), "");
        new ProjectModel(empty).requireGradleBuild();
        count++;
    }

    /** A Gradle timeout names its limit, whether it was a cold first setup, and how to raise it. */
    static void timeouts(Path scratch) throws Exception {
        require(
            Build.gradleTimeout(" 3600 ") == 3600 && Build.gradleTimeout("") == Build.GRADLE_TIMEOUT,
            "MMT_GRADLE_TIMEOUT overrides the default, and blank means the default"
        );
        try {
            Build.gradleTimeout("soon");
            throw new AssertionError("bad timeout accepted");
        } catch (IllegalArgumentException expected) {
            require(expected.getMessage().contains("MMT_GRADLE_TIMEOUT"), "a bad value names the variable");
        }
        String message = Build.timeoutMessage(2700, true, scratch.resolve("metadata.log"));
        require(
            message.contains("2700 s") &&
            message.contains("cold first setup") &&
            message.contains("MMT_GRADLE_TIMEOUT=5400") &&
            message.contains("metadata.log"),
            "the timeout message names the limit, the cold setup and how to raise it: " + message
        );
        require(!Build.timeoutMessage(900, false, scratch).contains("cold"), "a warm timeout is not called cold");
    }

    /**
     * A checkout without its wrapper jar (Freecam, GeckoLib ignore it) or without gradle/wrapper/ at all (CreativeCore)
     * builds through the jar the Gradle release itself ships, kept in .mmt/wrapper/ and never in the checkout's files.
     */
    static void missingWrapper(Path scratch) throws Exception {
        String url = "https://services.gradle.org/distributions/gradle-8.1-bin.zip";
        require(
            GradleWrapper.distFolder(url).endsWith(Path.of("wrapper/dists/gradle-8.1-bin/2eyty4r6kz6fpakefpk52nbbm")),
            "the wrapper's own folder for a distribution (Gradle's PathAssembler hash), so a fetched zip is reused: " + GradleWrapper.distFolder(url)
        );
        require(
            "9.0-rc-1".equals(GradleWrapper.version("https://services.gradle.org/distributions/gradle-9.0-rc-1-all.zip")) &&
            "8.14.3".equals(GradleWrapper.version("https://services.gradle.org/distributions/gradle-8.14.3-bin.zip")),
            "the Gradle release a distribution URL names"
        );
        String home = System.getProperty("user.home");
        System.setProperty("user.home", scratch.resolve("home").toString());
        System.setProperty("gradle.user.home", scratch.resolve("gradle-home").toString());
        try {
            for (boolean properties : List.of(true, false)) {
                Path root = Files.createDirectories(scratch.resolve("no-wrapper-" + properties));
                Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }\n");
                String version = properties ? "7.3.1" : GradleWrapper.NAMED_BY_NONE;
                if (properties) {
                    Files.createDirectories(root.resolve("gradle/wrapper"));
                    Files.writeString(
                        root.resolve(GradleWrapper.PROPERTIES),
                        "distributionUrl=https\\://services.gradle.org/distributions/gradle-7.3.1-bin.zip\nzipStoreBase=GRADLE_USER_HOME\n"
                    );
                }
                String dist = "https://services.gradle.org/distributions/gradle-" + version + "-bin.zip";
                require(GradleWrapper.distributionUrl(root).equals(dist), "the distribution a checkout runs on: " + GradleWrapper.distributionUrl(root));
                require(new ProjectModel(root).wrapperVersion().equals(version), "the Gradle a checkout runs on, " + version);
                // The release's zip where the wrapper keeps it, its wrapper plugin holding gradle-wrapper.jar as Gradle ships it
                // (lib/ in 7.3, lib/plugins/ later).
                byte[] wrapperJar = ("wrapper of " + version).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                var plugin = new java.io.ByteArrayOutputStream();
                try (var zip = new java.util.zip.ZipOutputStream(plugin)) {
                    zip.putNextEntry(new java.util.zip.ZipEntry("gradle-wrapper.jar"));
                    zip.write(wrapperJar);
                }
                Path zipFile = GradleWrapper.distFolder(dist).resolve("gradle-" + version + "-bin.zip");
                Files.createDirectories(zipFile.getParent());
                try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(zipFile))) {
                    zip.putNextEntry(new java.util.zip.ZipEntry("gradle-" + version + (properties ? "/lib/" : "/lib/plugins/") + "gradle-wrapper-" + version + ".jar"));
                    zip.write(plugin.toByteArray());
                }
                Path jar = GradleWrapper.jar(root);
                Properties written = ProjectModel.properties(jar.resolveSibling("gradle-wrapper.properties"));
                require(
                    jar.equals(root.resolve(".mmt/wrapper/gradle-wrapper.jar")) &&
                    Arrays.equals(Files.readAllBytes(jar), wrapperJar) &&
                    written.getProperty("distributionUrl").equals(dist) &&
                    (!properties || "GRADLE_USER_HOME".equals(written.getProperty("zipStoreBase"))) &&
                    !Files.exists(root.resolve(GradleWrapper.JAR)),
                    "a checkout " + (properties ? "missing its wrapper jar" : "with no wrapper") + " runs Gradle " + version +
                    " through the jar that release ships, beside the checkout's files, not in them"
                );
            }
        } finally {
            System.setProperty("user.home", home);
            System.clearProperty("gradle.user.home");
        }
    }

    /**
     * A plugin resolved to a release built for a newer Gradle (VanillaGradle 0.2.1-SNAPSHOT, now built with 8.14.3) stops
     * an older wrapper; the build runs once more on the newest release of its own line, never the next major.
     */
    static void newerGradleOfLine(Path scratch) throws Exception {
        for (String log : List.of(
            // FerriteCore, Gradle 8.0.
            "A problem occurred configuring project ':Common'.\n> Failed to notify project evaluation listener.\n   > 'void org.gradle.api.artifacts.repositories.InclusiveRepositoryContentDescriptor.includeGroupAndSubgroups(java.lang.String)'",
            // Melody, Gradle 8.1.1: no repository is asked at all.
            "Execution failed for task ':common:compileJava'.\n> Could not resolve all files for configuration ':common:compileClasspath'.\n   > Could not find org.ow2.asm:asm-tree:9.3.\n     Required by:\n         project :common",
            // Controlling, Gradle 6.
            "> Failed to apply plugin class 'org.spongepowered.gradle.vanilla.internal.ProvideMinecraftPlugin'.\n   > Extension of type 'JavaToolchainService' does not exist.",
            // Placeholder API, Gradle 7.3: Minotaur calls Task.notCompatibleWithConfigurationCache (Gradle 7.4).
            "> Could not create task ':modrinth'.\n   > 'void com.modrinth.minotaur.TaskModrinthUpload.notCompatibleWithConfigurationCache(java.lang.String)'"
        )) require(GradleWrapper.needsNewerGradle(log), "a plugin needing a newer Gradle: " + log.lines().reduce((a, b) -> b).orElse(""));
        for (String log : List.of(
            "Main.java:3: error: cannot find symbol\nExecution failed for task ':compileJava'.",
            "> Could not find net.mehvahdjukaar:moonlight:1.21-2.17.13.\n     Searched in the following locations:\n       - https://maven.example/x.pom\n     Required by:\n         project :"
        )) require(!GradleWrapper.needsNewerGradle(log), "not a newer-Gradle stop: " + log.lines().findFirst().orElse(""));
        List<String> released = List.of("7.6.6", "8.1.1", "8.14", "8.14.8", "9.4.1", "9.10.0-rc-1");
        // A plugin that names the Gradle it needs, and stops while it resolves, before any method is missing.
        String neoGradle = "> Could not resolve net.neoforged.gradle:userdev:7.0.+.\n   > No matching variant of net.neoforged.gradle:userdev:7.0.192 was found." +
            " The consumer was configured to find a library for use during runtime, compatible with Java 17, as well as attribute 'org.gradle.plugin.api-version' with value '8.8' but:\n" +
            "       - Incompatible because this component declares a component, as well as attribute 'org.gradle.plugin.api-version' with value '8.13' and the consumer needed a component, as well as attribute 'org.gradle.plugin.api-version' with value '8.8'";
        String loom = "> Failed to apply plugin 'fabric-loom'.\n   > You are using an outdated version of Gradle (8.11.1). Gradle 8.12 or higher is required.";
        require(
            "8.13".equals(GradleWrapper.minimumGradle(neoGradle)) && "8.12".equals(GradleWrapper.minimumGradle(loom)),
            "the Gradle a plugin says it needs: " + GradleWrapper.minimumGradle(neoGradle) + ", " + GradleWrapper.minimumGradle(loom)
        );
        require(
            "8.14.8".equals(GradleWrapper.riseTo("8.8", neoGradle, released)) &&
            "8.14.8".equals(GradleWrapper.riseTo("8.11.1", loom, released)),
            "a plugin needing a newer release of the build's own line rises to the newest of it"
        );
        require(
            GradleWrapper.riseTo("8.8", neoGradle, List.of("8.8", "8.12")) == null &&
            GradleWrapper.riseTo("8.14.8", loom.replace("8.12", "9.1.0"), released) == null &&
            GradleWrapper.riseTo("8.1.1", loom.replace("8.12", "9.1.0"), released) == null,
            "no rise to a release short of what the plugin needs, nor across a major line"
        );
        require(
            "8.14.8".equals(GradleWrapper.newestOfLine("8.1.1", released)) &&
            "7.6.6".equals(GradleWrapper.newestOfLine("7.3.1", released)) &&
            GradleWrapper.newestOfLine("8.14.8", released) == null &&
            "9.4.1".equals(GradleWrapper.newestOfLine("9.1.0", released)),
            "the newest stable release of the build's own major line, never the next major or a candidate"
        );
        Path root = Files.createDirectories(scratch.resolve("risen"));
        Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.writeString(
            root.resolve(GradleWrapper.PROPERTIES),
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.1.1-bin.zip\ndistributionSha256Sum=e111cb9948407e26351227dabce49822fb88c37ee72f1d1582a69c68af2e702f\n"
        );
        Files.createDirectories(GradleWrapper.risenFile(root).getParent());
        Files.writeString(GradleWrapper.risenFile(root), "8.14.8");
        require(
            new ProjectModel(root).wrapperVersion().equals("8.14.8") &&
            GradleWrapper.rise(root, "> 'void org.gradle.api.Foo.bar(java.lang.String)'") == null,
            "a risen checkout runs the newer release, and rises only once"
        );
        Files.writeString(root.resolve(GradleWrapper.PROPERTIES), "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.4.1-bin.zip\n");
        require(
            new ProjectModel(root).wrapperVersion().equals("9.4.1"),
            "a wrapper that moved past the risen release (a port to 26.x moves it to Gradle 9) is not taken back down"
        );
    }

    /**
     * The wrapper jar mmt supplies comes from the distribution the checkout names: a mirror's never stands in for
     * Gradle's own, and a pinned distributionSha256Sum is checked before anything in the zip is read.
     */
    static void wrapperOrigin(Path scratch) throws Exception {
        String home = System.getProperty("user.home");
        System.setProperty("user.home", scratch.resolve("origin-home").toString());
        System.setProperty("gradle.user.home", scratch.resolve("origin-gradle").toString());
        try {
            String official = GradleWrapper.official("8.2"), mirror = "https://mirror.example/gradle/gradle-8.2-bin.zip";
            byte[] ours = "official wrapper".getBytes(StandardCharsets.UTF_8), theirs = "mirror wrapper".getBytes(StandardCharsets.UTF_8);
            Path officialZip = distribution(official, ours), mirrorZip = distribution(mirror, theirs);
            require(
                Arrays.equals(Files.readAllBytes(GradleWrapper.shipped(mirror, null)), theirs) &&
                Arrays.equals(Files.readAllBytes(GradleWrapper.shipped(official, null)), ours),
                "each distribution's own wrapper, though both are Gradle 8.2"
            );
            // An unpacked copy another checkout left, holding a wrapper the pinned zip does not.
            Path unpacked = Files.createDirectories(GradleWrapper.distFolder(official).resolve("gradle-8.2/lib/plugins"));
            Files.write(unpacked.resolve("gradle-wrapper-8.2.jar"), plugin("unchecked wrapper".getBytes(StandardCharsets.UTF_8)));
            String pinned = GradleWrapper.sha256(officialZip);
            require(
                Arrays.equals(Files.readAllBytes(GradleWrapper.shipped(official, pinned)), ours),
                "a pinned checksum takes the wrapper from the checked zip"
            );
            try {
                GradleWrapper.shipped(mirror, pinned);
                throw new AssertionError("a zip that does not match the checkout's checksum gave a wrapper: " + mirrorZip);
            } catch (IOException refused) {
                require(refused.getMessage().contains("distributionSha256Sum"), "a checksum mismatch says so: " + refused.getMessage());
            }
        } finally {
            System.setProperty("user.home", home);
            System.clearProperty("gradle.user.home");
        }
    }

    static byte[] plugin(byte[] wrapperJar) throws IOException {
        var plugin = new ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(plugin)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("gradle-wrapper.jar"));
            zip.write(wrapperJar);
        }
        return plugin.toByteArray();
    }

    /** A distribution's zip where the wrapper keeps it, shipping wrapperJar in its wrapper plugin. */
    static Path distribution(String url, byte[] wrapperJar) throws IOException {
        String version = GradleWrapper.version(url);
        Path zipFile = GradleWrapper.distFolder(url).resolve(url.substring(url.lastIndexOf('/') + 1));
        Files.createDirectories(zipFile.getParent());
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(zipFile))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("gradle-" + version + "/lib/plugins/gradle-wrapper-" + version + ".jar"));
            zip.write(plugin(wrapperJar));
        }
        return zipFile;
    }

    /** A reload's configuration-cached compile resolves from where the full build does, without mmt's tasks. */
    static void cachedReload(Path scratch) {
        Path init = scratch.resolve("tools/cli/gradle.init.gradle");
        String resolution = Build.resolution(init).toString();
        List<String> cached = Build.cached(
            List.of("java", "-cp", "w.jar", "org.gradle.wrapper.GradleWrapperMain", "--console=plain", "--no-configuration-cache",
                "-I", init.toString(), "-I", resolution, "-Dmmt.project=:a", "-PossrhToken=" + BuildSecrets.PLACEHOLDER,
                "-Dmmt.pluginPins=net.neoforged.gradle.userdev=7.0.80", "-Dmmt.portedLibraries=/libs", "-Dfabric.loom.ci=true", "-Dmmt.runId=r", ":a:classes"),
            init
        );
        require(
            cached.equals(List.of("java", "-cp", "w.jar", "org.gradle.wrapper.GradleWrapperMain", "--console=plain", "--configuration-cache",
                "-I", resolution, "-PossrhToken=" + BuildSecrets.PLACEHOLDER, "-Dmmt.pluginPins=net.neoforged.gradle.userdev=7.0.80", "-Dmmt.portedLibraries=/libs", ":a:classes")),
            "a reload keeps the repository repairs, plugin pins, ported libraries and placeholders, and drops mmt's tasks: " + cached
        );
    }

    /** The publishing secrets a build reads while it loads get placeholders; signing keys and values already set do not. */
    static void publishingSecrets(Path scratch) throws Exception {
        Properties set = new Properties();
        set.setProperty("curseKey", "mine");
        var names = BuildSecrets.named(
            List.of(
                // MidnightLib, Kotlin: an environment variable read while the build loads.
                "val curseforgeToken = System.getenv(\"CURSEFORGE_TOKEN\")\naccessToken = curseforgeToken.toString()\nval mr: String? = System.getenv(\"MINECRAFT_VERSION\")",
                // Cherished Worlds: a property, and one the user set.
                "modrinth {\n    token = findProperty('modrinthKey') ?: 0\n}\ncurseforge { apiToken = findProperty('curseKey') }",
                // YUNG's API: a bare property name; a signing key stays unset.
                "credentials { username ossrhToken\n password ossrhTokenPassword }\nsigning { useInMemoryPgpKeys(findProperty('signingKey'), signingPassword) }",
                // BadOptimizations: Kotlin's by-project delegate.
                "val mr_token: String by project // the Modrinth token"
            ),
            set,
            Map.of("HOME", "/home/u", "ORG_GRADLE_PROJECT_mr_token", "set")
        );
        require(
            names.containsAll(List.of("env:CURSEFORGE_TOKEN", "prop:modrinthKey", "prop:ossrhToken", "prop:ossrhTokenPassword")),
            "each secret a build names, as the script reads it: " + names
        );
        require(
            !names.contains("prop:curseKey") && !names.contains("prop:mr_token") && !names.contains("env:MINECRAFT_VERSION") && names.stream().noneMatch(n -> n.contains("sign")),
            "no placeholder for a value the user set, a name that holds no secret, or a signing key: " + names
        );
        require(
            BuildSecrets.arguments(names).contains("-PmodrinthKey=" + BuildSecrets.PLACEHOLDER) &&
            BuildSecrets.environment(names).equals(Map.of("CURSEFORGE_TOKEN", BuildSecrets.PLACEHOLDER)),
            "placeholders as Gradle properties and environment variables"
        );
        // Saved after a first build; the user then exports the token, or sets the property, before the next one.
        List<String> saved = List.of("env:CURSEFORGE_TOKEN", "prop:modrinthKey", "prop:ossrhToken");
        Properties later = new Properties();
        later.setProperty("ossrhToken", "real");
        require(
            BuildSecrets.given(saved, later, Map.of("CURSEFORGE_TOKEN", "real", "ORG_GRADLE_PROJECT_modrinthKey", "real")).isEmpty() &&
            BuildSecrets.given(saved, new Properties(), Map.of()).equals(new TreeSet<>(saved)),
            "a placeholder mmt saved gives way to a value the user sets later"
        );
        // Every Gradle run of the checkout gets them, the game tests' and a server's too (Build.environment).
        Path root = Files.createDirectories(scratch.resolve("secrets"));
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }\n");
        ProjectModel project = new ProjectModel(root);
        Files.createDirectories(BuildSecrets.file(project).getParent());
        Files.write(BuildSecrets.file(project), List.of("env:MMT_TEST_UNSET_TOKEN", "prop:ossrhToken"));
        Map<String, String> environment = Build.environment(project, scratch.resolve("jdk"));
        require(
            BuildSecrets.PLACEHOLDER.equals(environment.get("MMT_TEST_UNSET_TOKEN")) && environment.get("JAVA_HOME").equals(scratch.resolve("jdk").toString()),
            "a Gradle run's environment carries the build's Java and the placeholders saved for it: " + environment
        );
        for (String failure : List.of(
            "Could not create domain object 'curseforge' (Curseforge) > Cannot invoke \"String.toString()\" because \"this.$curseforgeToken\" is null",
            "Could not create domain object 'modrinth' (Modrinth) > Key mr_token is missing in the map.",
            "Cannot set the value of extension 'modrinth' property 'token' of type java.lang.String using an instance of type java.lang.Integer.",
            "Could not get unknown property 'ossrhToken' for Credentials [username: null]"
        )) require(BuildSecrets.stoppedOn(failure), "a load that stopped on a secret: " + failure);
        require(
            !BuildSecrets.stoppedOn("Could not resolve all artifacts for configuration ':classpath'. > Could not find :architectury-loom-1.7"),
            "a load that stopped on something else is not read again with placeholders"
        );
    }

    /** The project's Loom version comes from its own files, so a dependency built by a newer Loom gets a warning. */
    static void loomVersions() {
        Properties props = new Properties();
        props.setProperty("loom_version", "1.10-SNAPSHOT");
        require(ProjectModel.loomVersion(props, List.of()).equals("1.10-SNAPSHOT"), "loom_version property");
        Properties none = new Properties();
        none.setProperty("loom", "1.9.2");
        require(
            ProjectModel.loomVersion(
                new Properties(),
                List.of("plugins {\n  id 'fabric-loom' version '1.11.4'\n}")
            ).equals("1.11.4"),
            "groovy plugins block"
        );
        require(
            ProjectModel.loomVersion(
                new Properties(),
                List.of("plugins { id(\"net.fabricmc.fabric-loom-remap\") version \"1.14-SNAPSHOT\" }")
            ).equals("1.14-SNAPSHOT"),
            "kotlin plugins block"
        );
        require(
            ProjectModel.loomVersion(none, List.of("plugins { id 'fabric-loom' version \"${loom}\" }")).equals("1.9.2"),
            "a plugin version from a property"
        );
        require(
            ProjectModel.loomVersion(new Properties(), List.of("[versions]\nloom = \"1.8.13\"\n")).equals("1.8.13"),
            "version catalog"
        );
        require(
            ProjectModel.loomVersion(new Properties(), List.of("apply plugin: 'java'")).isEmpty(),
            "no Loom stated"
        );
        require(
            Dependencies.minor("1.10-SNAPSHOT").equals("1.10") &&
            Versions.compare(Dependencies.minor("1.11.2"), Dependencies.minor("1.10-SNAPSHOT")) > 0,
            "Loom minors compare"
        );
    }

    /**
     * A command that dies before handing its game off takes the game with it: the game it started itself, and one
     * a Gradle daemon started that the log names (MMT_PID). A handed-off game stays for reload and eval.
     * Hidden games left behind by dead commands ran for 13 hours.
     */
    static void failedStartEndsGame(Path scratch) throws Exception {
        Path log = scratch.resolve("owned-game.log");
        Process launcher = java(FailedStart.class, log.toString()), outside = null;
        ProcessHandle owned = null, handedOff = null;
        try {
            String[] pids = new BufferedReader(
                new InputStreamReader(launcher.getInputStream(), StandardCharsets.UTF_8)
            ).readLine().split(" ");
            // Started here, not by the failing command, once it owns its start: like a game the Gradle daemon started.
            outside = java(Sleep.class);
            Files.writeString(log, "> Task :runClient\nMMT_PID " + outside.pid() + "\n");
            launcher.getOutputStream().close();
            require(launcher.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "the failing launcher exits");
            owned = ProcessHandle.of(Long.parseLong(pids[0])).orElse(null);
            handedOff = ProcessHandle.of(Long.parseLong(pids[1])).orElse(null);
            require(gone(owned) && gone(outside.toHandle()), "a start that failed ended its game and the one its log names");
            require(handedOff != null && handedOff.isAlive(), "a handed-off game keeps running");
        } finally {
            for (ProcessHandle process : Arrays.asList(
                outside == null ? null : outside.toHandle(),
                owned,
                handedOff,
                launcher.toHandle()
            )) if (process != null) process.destroyForcibly();
        }
    }

    static Process java(Class<?> main, String... args) throws IOException {
        List<String> command = new ArrayList<>(
            List.of(
                ProcessHandle.current().info().command().orElse("java"),
                "-cp",
                System.getProperty("java.class.path"),
                main.getName()
            )
        );
        command.addAll(List.of(args));
        return new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }

    static boolean gone(ProcessHandle process) throws Exception {
        if (process == null) return true;
        try {
            process.onExit().get(15, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException stillRunning) {}
        return !process.isAlive();
    }

    /** Stands in for a game: runs until ended. */
    public static final class Sleep {

        public static void main(String[] args) throws Exception {
            Thread.sleep(Long.MAX_VALUE);
        }
    }

    /** A start that fails: one game owned, one handed off, then an exception ends this JVM. */
    public static final class FailedStart {

        public static void main(String[] args) throws Exception {
            Process owned = java(Sleep.class), handedOff = java(Sleep.class);
            new Run.Owned(owned.toHandle(), Path.of(args[0]));
            new Run.Owned(handedOff.toHandle(), null).handOff();
            System.out.println(owned.pid() + " " + handedOff.pid());
            System.out.flush();
            // The test starts the game "Gradle" launched, then lets this start fail.
            System.in.readAllBytes();
            throw new IllegalStateException("the game did not start");
        }
    }

    static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        count++;
    }
}

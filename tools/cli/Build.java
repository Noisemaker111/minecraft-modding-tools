import java.io.*;
import java.nio.file.*;
import java.util.*;

final class Build {

    final ProjectModel project;
    final Path harness, init, resolution;
    final String selected;
    final Mmt.Report report;
    Runtimes.Jdk jdk;

    Build(ProjectModel project, Path harness, String selected, Mmt.Report report) throws Exception {
        this.project = project;
        this.harness = harness;
        this.selected = selected;
        this.report = report;
        init = harness.resolve("tools/cli/gradle.init.gradle");
        resolution = resolution(init);
        long start = System.nanoTime();
        int required = project.javaRequirement();
        for (int version : project.javaToolchainVersions()) Runtimes.ensureExact(harness, version);
        if (required > 0) Runtimes.ensureAtLeast(harness, required);
        jdk = Runtimes.ensureForGradle(harness, project.wrapperVersion());
        report.stages.add(
            Json.map(
                "name",
                "java-setup",
                "seconds",
                (System.nanoTime() - start) / 1e9,
                "required",
                required,
                "gradleJava",
                jdk.version(),
                "home",
                jdk.home().toString()
            )
        );
    }

    /**
     * Choose Gradle's JDK again after a port rewrote the wrapper: a JDK picked for Gradle 6.9 (Java 11) cannot load the
     * Loom 0.10 that a port to 1.17 moves to. Returns true when the JDK changed.
     */
    boolean refreshJdk() throws Exception {
        int required = project.javaRequirement();
        if (required > 0) Runtimes.ensureAtLeast(harness, required);
        Runtimes.Jdk chosen = Runtimes.ensureForGradle(harness, project.wrapperVersion());
        if (chosen.home().equals(jdk.home())) return false;
        report.stages.add(Json.map("name", "java-setup-after-target", "gradleJava", chosen.version(), "home", chosen.home().toString()));
        jdk = chosen;
        return true;
    }

    static final long DAEMON_IDLE_MS = 10 * 60 * 1000;

    /** True when the user's Gradle home, the build, or GRADLE_OPTS already sets the daemon idle timeout. */
    boolean userSetsIdleTimeout() throws IOException {
        String key = "org.gradle.daemon.idletimeout";
        if (System.getenv().getOrDefault("GRADLE_OPTS", "").contains(key)) return true;
        Path gradleHome = Path.of(
            System.getenv()
                .getOrDefault("GRADLE_USER_HOME", Path.of(System.getProperty("user.home"), ".gradle").toString())
        );
        for (Path properties : List.of(
            gradleHome.resolve("gradle.properties"),
            project.root.resolve("gradle.properties")
        )) if (!ProjectModel.properties(properties).getProperty(key, "").isBlank()) return true;
        return false;
    }

    Path daemons() {
        return project.state().resolve("gradle-daemons").toAbsolutePath();
    }

    /** Stops the Gradle daemons mmt started for this checkout; null if it never started any. */
    Run.Result stopDaemons() throws Exception {
        if (!Files.isDirectory(daemons())) return null;
        Path wrapper = GradleWrapper.jar(project.root);
        Run.Result result = Run.command(
            List.of(
                jdk.java().toString(),
                "-cp",
                wrapper.toString(),
                "org.gradle.wrapper.GradleWrapperMain",
                "--stop",
                "-Dorg.gradle.daemon.registry.base=" + daemons()
            ),
            project.root,
            Map.of("JAVA_HOME", jdk.home().toString()),
            report.log("stop-daemons"),
            120
        );
        report.stage("stop-daemons", result, Json.map("registry", daemons().toString()));
        return result;
    }

    static final int GRADLE_TIMEOUT = 900;
    static final int NETWORK_RETRY_SECONDS = 20;
    static final String TIMEOUT_VARIABLE = "MMT_GRADLE_TIMEOUT";

    /** No evaluated model yet: Gradle may still download the game, mappings and every dependency. */
    boolean coldSetup() {
        return (
            !Files.isRegularFile(project.state().resolve("model.json")) &&
            !Files.isRegularFile(project.state().resolve("plan-model.json"))
        );
    }

    /** Seconds one Gradle invocation may take: MMT_GRADLE_TIMEOUT when set, else 15 minutes, cold or warm. */
    static int gradleTimeout(String configured) {
        if (configured != null && !configured.isBlank()) {
            try {
                int seconds = Integer.parseInt(configured.strip());
                if (seconds > 0) return seconds;
            } catch (NumberFormatException invalid) {}
            throw new IllegalArgumentException(
                TIMEOUT_VARIABLE + " must be a whole number of seconds above 0; got " + configured
            );
        }
        return GRADLE_TIMEOUT;
    }

    static String timeoutMessage(int seconds, boolean cold, Path log) {
        return (
            "Gradle stopped at mmt's " +
            seconds +
            " s limit for " +
            (cold
                    ? "a cold first setup (no evaluated build yet, so Gradle was still downloading and preparing the game and dependencies)"
                    : "one build step") +
            ". Nothing is wrong with the project yet; it was cut off. Raise the limit with the " +
            TIMEOUT_VARIABLE +
            " environment variable (seconds), for example " +
            TIMEOUT_VARIABLE +
            "=" +
            seconds * 2 +
            ", then rerun; a warm rerun reuses what was downloaded.\nLog: " +
            log
        );
    }

    Run.Result timed(List<String> command, String stage) throws Exception {
        boolean cold = coldSetup();
        int seconds = gradleTimeout(UserState.env("GRADLE_TIMEOUT"));
        try {
            return Run.command(
                command,
                project.root,
                environment(),
                report.log(stage),
                seconds
            );
        } catch (Run.Timeout timeout) {
            throw new IOException(timeoutMessage(timeout.seconds, cold, timeout.log), timeout);
        }
    }

    /**
     * The environment every Gradle run of this checkout gets beside the inherited one, whoever starts it (compile, game
     * tests, a server, a client Gradle launches): the build's Java, and the publishing-secret placeholders it needs to load.
     */
    Map<String, String> environment() throws IOException {
        return environment(project, jdk.home());
    }

    static Map<String, String> environment(ProjectModel project, Path javaHome) throws IOException {
        Map<String, String> environment = new TreeMap<>(BuildSecrets.environment(BuildSecrets.given(project)));
        environment.put("JAVA_HOME", javaHome.toString());
        return environment;
    }

    Run.Result gradle(List<String> arguments, String stage) throws Exception {
        List<String> command = command(arguments);
        Run.Result result = timed(command, stage);
        // A plugin resolved to a release built for a newer Gradle than the wrapper's: once, the newest of its line.
        String newer = result.exit() == 0 ? null : GradleWrapper.rise(project.root, Files.readString(result.log()));
        if (newer != null) {
            // The Java Gradle runs on stays: a newer release of a line runs on every Java its older ones did.
            report.stage(stage, result, Json.map("gradle", project.wrapperVersion(), "risesTo", newer));
            command = command(arguments);
            stage = stage + "-gradle-" + newer;
            result = timed(command, stage);
        }
        boolean fallback = result.exit() != 0 && configuresOnDemand() && selectionFailed(result.log());
        report.stage(
            stage,
            result,
            Json.map(
                "java",
                jdk.version(),
                "gradle",
                project.wrapperVersion(),
                "configureOnDemand",
                configuresOnDemand(),
                "selectionFailure",
                fallback
            )
        );
        if (!fallback) return result;
        // The init script registers mmt's tasks as each project configures. If a build still hides
        // a task from Gradle's on-demand selection, configure every project once, as before, and say so.
        command.add(command.indexOf("-I") + 2, "--no-configure-on-demand");
        Run.Result retry = timed(command, stage + "-all-projects");
        report.stage(
            stage + "-all-projects",
            retry,
            Json.map(
                "java",
                jdk.version(),
                "gradle",
                project.wrapperVersion(),
                "configureOnDemand",
                false,
                "fallback",
                "Gradle could not select a task while configuring on demand; retried once with --no-configure-on-demand",
                "firstLog",
                result.log().toString()
            )
        );
        System.err.println(
            "Note: " +
            stage +
            " could not select its task with configure-on-demand; retried with every project configured (receipt stage " +
            stage +
            "-all-projects)."
        );
        return retry;
    }

    static final java.util.regex.Pattern SELECTION_FAILURE = java.util.regex.Pattern.compile(
        "Task '[^']*' not found in |Cannot locate tasks? that match "
    );

    static boolean selectionFailed(Path log) throws IOException {
        return (
            Files.exists(log) &&
            SELECTION_FAILURE.matcher(new String(Files.readAllBytes(log), java.nio.charset.StandardCharsets.UTF_8)).find()
        );
    }

    Boolean configureOnDemand;

    /** True when the build or the user's Gradle home turns on org.gradle.configureondemand. */
    boolean configuresOnDemand() throws IOException {
        if (configureOnDemand != null) return configureOnDemand;
        String key = "org.gradle.configureondemand";
        String value = "";
        Path gradleHome = Path.of(
            System.getenv()
                .getOrDefault("GRADLE_USER_HOME", Path.of(System.getProperty("user.home"), ".gradle").toString())
        );
        // The user's Gradle home overrides the build's own setting.
        for (Path properties : List.of(
            project.root.resolve("gradle.properties"),
            gradleHome.resolve("gradle.properties")
        )) {
            String set = ProjectModel.properties(properties).getProperty(key, "").trim();
            if (!set.isEmpty()) value = set;
        }
        return configureOnDemand = value.equalsIgnoreCase("true");
    }

    /**
     * A compile with none of mmt's tasks in it, so Gradle can reuse its configuration cache:
     * on a 21-project build that is about 3 s instead of 11 s per reload. A build that can't use
     * the cache still compiles; Gradle just reports the problem and configures as usual. It keeps
     * where the build resolves from (gradle.resolution.gradle), or it would resolve otherwise than the build it reloads.
     */
    Run.Result compileCached(List<String> tasks, String stage) throws Exception {
        Run.Result result = timed(cachedCommand(tasks), stage);
        report.stage(
            stage,
            result,
            Json.map("java", jdk.version(), "gradle", project.wrapperVersion(), "configurationCache", true)
        );
        return result;
    }

    List<String> cachedCommand(List<String> tasks) throws IOException {
        return cached(command(tasks), init);
    }

    /** A full Gradle command as a configuration-cached one: mmt's tasks and properties out, where it resolves from kept. */
    static List<String> cached(List<String> full, Path init) {
        List<String> command = new ArrayList<>(full);
        int at = command.indexOf(init.toString());
        command.remove(at);
        command.remove(at - 1);
        command.replaceAll(argument -> argument.equals("--no-configuration-cache") ? "--configuration-cache" : argument
        );
        command.removeIf(argument -> argument.equals("-Dfabric.loom.ci=true") || argument.startsWith("-Dmmt.") && !RESOLUTION.matcher(argument).lookingAt());
        return command;
    }

    /** mmt's init script that says where a build resolves from, beside gradle.init.gradle. */
    static Path resolution(Path init) {
        return init.resolveSibling("gradle.resolution.gradle");
    }

    /** The system properties gradle.resolution.gradle reads. */
    static final java.util.regex.Pattern RESOLUTION = java.util.regex.Pattern.compile("-Dmmt\\.(pluginPins|portedLibraries)=");

    List<String> command(List<String> arguments) throws IOException {
        Path wrapper = GradleWrapper.jar(project.root);
        List<String> command = new ArrayList<>(
            List.of(
                jdk.java().toString(),
                "-cp",
                wrapper.toString(),
                "org.gradle.wrapper.GradleWrapperMain",
                // The init script registers its tasks as each project configures, so a build with
                // org.gradle.configureondemand=true configures only the projects a command names.
                "--console=plain",
                "--no-configuration-cache",
                "-I",
                init.toString(),
                "-I",
                resolution.toString()
            )
        );
        // Test hook: make the init script miss under configure-on-demand to exercise the fallback.
        if ("true".equals(UserState.env("SIMULATE_COD_MISS"))) command.add("-Dmmt.simulateCodMiss=true");
        List<String> toolchains = new ArrayList<>();
        Path downloaded = JdkDownloads.cache();
        for (Runtimes.Jdk installed : Runtimes.installed(harness)) {
            Path home = installed.home().toAbsolutePath().normalize();
            if (home.startsWith(downloaded)) toolchains.add(home.toString());
        }
        if (!toolchains.isEmpty()) {
            Path gradleHome = Path.of(
                System.getenv()
                    .getOrDefault("GRADLE_USER_HOME", Path.of(System.getProperty("user.home"), ".gradle").toString())
            );
            for (Path properties : List.of(
                gradleHome.resolve("gradle.properties"),
                project.root.resolve("gradle.properties")
            )) {
                String existing = ProjectModel.properties(properties).getProperty(
                    "org.gradle.java.installations.paths",
                    ""
                );
                if (!existing.isBlank()) toolchains.add(existing);
            }
            command.add("-Dorg.gradle.java.installations.paths=" + String.join(",", toolchains));
        }
        if (!selected.isBlank()) command.add("-Dmmt.project=" + selected);
        command.addAll(BuildSecrets.arguments(BuildSecrets.given(project)));
        Path pins = project.state().resolve("plugin-pins.txt");
        if (Files.isRegularFile(pins)) command.add("-Dmmt.pluginPins=" + Files.readString(pins).trim());
        // Libraries mmt ported from source (LibraryPort) resolve from its local repository, and only from there.
        if (Files.isDirectory(LibraryPort.store())) command.add("-Dmmt.portedLibraries=" + LibraryPort.store());
        // Loom skips dependency source remapping for CLI checks; compiled dependency remapping still runs.
        command.add("-Dfabric.loom.ci=true");
        command.add("-Dmmt.runId=" + report.id);
        // Daemons of mmt's own, so game-stop can stop them without touching the user's or another
        // agent's (gradle --stop would end them all), and one left running leaves after ten minutes idle.
        command.add("-Dorg.gradle.daemon.registry.base=" + daemons());
        // A command-line value would override one the user set, so only fill in a default.
        if (!userSetsIdleTimeout()) command.add("-Dorg.gradle.daemon.idletimeout=" + DAEMON_IDLE_MS);
        command.addAll(arguments);
        return command;
    }

    Map<String, Object> model(boolean refresh) throws Exception {
        return model(refresh, false);
    }

    /** Kept beside the selected project's model files, so an every-project read does not replace them. */
    String cacheSuffix = "";

    /**
     * The same build with no project selected: its planning model lists every project, which a multi-loader port uses to
     * move the other loaders' libraries too. Cached in files of its own.
     */
    Build allProjects() throws Exception {
        Build all = new Build(project, harness, "", report);
        all.cacheSuffix = "-all";
        all.jdk = jdk;
        return all;
    }

    Map<String, Object> model(boolean refresh, boolean planning) throws Exception {
        Path model = project.state().resolve((planning ? "plan-model" : "model") + cacheSuffix + ".json");
        Path stamp = project.state().resolve((planning ? "plan-model-stamp" : "model-stamp") + cacheSuffix + ".json");
        String key =
            project.configurationHash(init) + "|" + selected + "|" + jdk.home() + "|" + (planning ? "plan" : "full");
        if (!refresh && Files.isRegularFile(model) && Files.isRegularFile(stamp)) {
            Map<String, Object> cached = Json.read(stamp);
            // No expiry: the key covers every build input, and reading the build again costs a Gradle start
            // (25 s on a cold daemon). A dynamic dependency version Gradle would re-resolve takes --refresh.
            if (key.equals(cached.get("key")) && classpathExists(Json.read(model))) {
                report.stages.add(Json.map("name", "metadata", "cache", "hit", "seconds", 0));
                return Json.read(model);
            }
        }
        List<String> task = new ArrayList<>(planning ? List.of("-Dmmt.metadataMode=plan", ":mmtMetadata") : List.of(":mmtMetadata"));
        // The init script writes the model under this name, so an every-project read never replaces the selected one's.
        task.add(0, "-Dmmt.modelFile=" + model.getFileName());
        String stage = planning ? "metadata-plan" : "metadata";
        Run.Result result = gradle(task, stage);
        // The first read downloads Gradle and the build's dependencies, and the wrapper gives up after one 10 s try:
        // on 2026-11-08 services.gradle.org timed out on CI for an hour. One more try after a pause rides that out.
        // Loom states the Java it needs when it refuses to load ("outdated version of Java (11). Java 16 or higher is
        // required": Polymer and Carpet's Loom 0.8/0.9 on Gradle 7.1, whose JDKs run 8 to 16). Gradle restarts on that Java.
        if (result.exit() != 0) {
            java.util.regex.Matcher needs = java.util.regex.Pattern.compile("outdated version of Java \\((\\d+)\\)\\. Java (\\d+) or higher is required").matcher(
                new String(Files.readAllBytes(result.log()), java.nio.charset.StandardCharsets.UTF_8)
            );
            if (needs.find() && Integer.parseInt(needs.group(2)) > jdk.version()) {
                Runtimes.Jdk needed = Runtimes.ensureExact(harness, Integer.parseInt(needs.group(2)));
                report.stages.add(Json.map("name", "java-for-loom", "gradleJava", needed.version(), "evidence", needs.group(0)));
                jdk = needed;
                result = gradle(task, stage + "-java-" + needed.version());
            }
        }
        // A plugin asked for by a range ("1.+") resolves to its newest release, which can need a newer Java than the
        // build's Gradle runs on (Curios 1.21.1: CurseForgeGradle 1.3.33 needs Java 25, Gradle 8.8 runs on 21). The
        // release's own Gradle metadata states its Java, so mmt pins the newest release of that major line that runs.
        if (result.exit() != 0) {
            String log = new String(Files.readAllBytes(result.log()), java.nio.charset.StandardCharsets.UTF_8);
            String pin = pluginPin(log);
            if (pin != null) {
                Path pins = project.state().resolve("plugin-pins.txt");
                Files.createDirectories(pins.getParent());
                String existing = Files.isRegularFile(pins) ? Files.readString(pins).trim() : "";
                Files.writeString(pins, existing.isEmpty() ? pin : existing + "," + pin);
                report.stages.add(Json.map("name", "plugin-pin", "pin", pin));
                result = gradle(task, stage + "-pinned");
            }
        }
        // A build that reads its author's publishing secrets while it loads stops everyone else's checkout; mmt never
        // publishes, so the secrets it names get placeholders (BuildSecrets) and the build is read once more.
        if (result.exit() != 0) {
            SortedSet<String> secrets = BuildSecrets.named(project);
            SortedSet<String> given = BuildSecrets.given(project);
            if (!given.containsAll(secrets) && BuildSecrets.stoppedOn(Run.gradleFailure(result.log()))) {
                given.addAll(secrets);
                Files.createDirectories(BuildSecrets.file(project).getParent());
                Files.write(BuildSecrets.file(project), given);
                report.stages.add(Json.map("name", "secret-placeholders", "names", new ArrayList<>(given)));
                result = gradle(task, stage + "-secret-placeholders");
            }
        }
        String unreachable = result.exit() == 0
            ? null
            : Run.unreachable(
                new String(Files.readAllBytes(result.log()), java.nio.charset.StandardCharsets.UTF_8).lines().toList()
            );
        if (unreachable != null) {
            System.err.println(
                "degraded: " +
                unreachable.substring(0, unreachable.indexOf(". ") + 1) +
                " Trying once more in " +
                NETWORK_RETRY_SECONDS +
                " s."
            );
            Thread.sleep(NETWORK_RETRY_SECONDS * 1000L);
            result = gradle(task, stage + "-retry");
        }
        if (result.exit() != 0) {
            String why = Run.gradleFailure(result.log());
            throw new IOException(discoveryHeadline(why) + "\n" + why + "\nLog: " + result.log());
        }
        if (planning && !Files.isRegularFile(model)) {
            // Some older Gradle builds (ModMenu 1.16.5 on Gradle 6.9.1) never show the init script the planning flag, so it
            // writes the full model; that is a superset of the planning model, so it serves as one.
            java.util.regex.Matcher written = java.util.regex.Pattern.compile("(?m)^MMT_MODEL (.+?)\s*$").matcher(
                Files.readString(result.log())
            );
            Path full = null;
            while (written.find()) full = Path.of(written.group(1));
            if (full != null && Files.isRegularFile(full)) Files.copy(full, model, StandardCopyOption.REPLACE_EXISTING);
        }
        Map<String, Object> value = Json.read(model);
        Json.save(stamp, Json.map("key", key));
        return value;
    }

    /** "pluginId=version" for a plugin whose resolved release needs a newer Java than Gradle runs on, or null. */
    String pluginPin(String log) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "Could not resolve ([\\w.-]+):([\\w.-]+):([\\w.-]+)\\.\\s+Required by:\\s+[^\\n]*> ([\\w.-]+):\\4\\.gradle\\.plugin:[\\w.-]+\\s+> Dependency requires at least JVM runtime version (\\d+)"
        ).matcher(log);
        if (!m.find() || Integer.parseInt(m.group(5)) <= jdk.version()) return null;
        String base = "https://plugins.gradle.org/m2/" + m.group(1).replace('.', '/') + "/" + m.group(2) + "/";
        String major = m.group(3).split("\\.")[0];
        try {
            List<String> versions = new ArrayList<>();
            java.util.regex.Matcher v = java.util.regex.Pattern.compile("<version>([^<]+)</version>").matcher(
                Target.fetch(this, base + "maven-metadata.xml")
            );
            while (v.find()) if (v.group(1).split("\\.")[0].equals(major)) versions.add(v.group(1));
            for (int i = versions.size() - 1; i >= 0; i--) {
                String candidate = versions.get(i);
                String module;
                try {
                    module = Target.fetch(this, base + candidate + "/" + m.group(2) + "-" + candidate + ".module");
                } catch (IOException none) {
                    // Releases without Gradle metadata predate the Java attribute; they ran on the Java of their time.
                    return m.group(4) + "=" + candidate;
                }
                int needs = 0;
                java.util.regex.Matcher jvm = java.util.regex.Pattern.compile("\"org\\.gradle\\.jvm\\.version\":\\s*(\\d+)").matcher(module);
                while (jvm.find()) needs = Math.max(needs, Integer.parseInt(jvm.group(1)));
                if (needs <= jdk.version()) return m.group(4) + "=" + candidate;
            }
        } catch (Exception unreachable) {
            return null;
        }
        return null;
    }

    /** What stopped mmt reading the build, in a few words, ahead of Gradle's own detail. */
    static String discoveryHeadline(String why) {
        if (
            why.startsWith("Gradle could not resolve")
        ) return "Missing dependency: this project's build needs a library Gradle could not find, so mmt could not read the build.";
        java.util.regex.Matcher plugin = java.util.regex.Pattern.compile(
            "Plugin \\[id: '([^']+)'(?:, version: '([^']+)')?\\] was not found"
        ).matcher(why);
        if (plugin.find()) return (
            "Missing build plugin: this project's build uses the Gradle plugin " +
            plugin.group(1) +
            (plugin.group(2) == null ? "" : " " + plugin.group(2)) +
            ", and none of its plugin repositories has it, so mmt could not read the build. Check the plugin id and version, or add the repository that publishes it (pluginManagement in settings.gradle)."
        );
        if (
            why.startsWith("Gradle could not reach")
        ) return "No connection to a repository: Gradle could not download what this project's build needs, so mmt could not read the build.";
        return "Could not read this project's build: Gradle stopped while loading it, before any code was compiled.";
    }

    static boolean classpathExists(Map<String, Object> model) {
        for (Object p : Json.array(model.get("projects"))) {
            var project = Json.object(p);
            for (Object artifact : Json.array(project.get("artifacts"))) if (
                !Files.isRegularFile(Path.of(Json.text(Json.object(artifact), "file", "")))
            ) return false;
            for (Object c : Json.array(project.get("compile"))) for (String path : ProjectModel.strings(
                Json.object(c).get("classpath")
            )) if (path.endsWith(".jar") && !Files.isRegularFile(Path.of(path))) return false;
        }
        return true;
    }

    List<String> tasks(Map<String, Object> model, String name) {
        List<String> result = new ArrayList<>();
        for (Object item : Json.array(model.get("projects"))) {
            var p = Json.object(item);
            if (name.equals("classes")) {
                for (Object s : Json.array(p.get("sourceSets"))) {
                    var set = Json.object(s);
                    String task = Json.text(set, "classesTask", "");
                    if (!task.isEmpty()) result.add((p.get("path").equals(":") ? "" : p.get("path")) + ":" + task);
                }
                continue;
            }
            boolean found = name.equals("mmtPure")
                ? Json.array(p.get("sourceSets")).stream().anyMatch(s -> "test".equals(Json.object(s).get("name")))
                : Json.array(p.get("tasks"))
                    .stream()
                    .anyMatch(t -> Json.text(Json.object(t), "task", "").endsWith(":" + name));
            if (found) result.add((p.get("path").equals(":") ? "" : p.get("path")) + ":" + name);
        }
        return result;
    }
}

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * The publishing secrets a build reads while it loads: upload tokens for Modrinth and CurseForge, a Maven password, a
 * Discord webhook. A mod's author has them and nobody else does, so a build script that reads one unguarded stops
 * every other checkout before anything compiles (MidnightLib's `System.getenv("CURSEFORGE_TOKEN").toString()`,
 * `token = findProperty('modrinthKey') ?: 0` that Minotaur 2.8 refuses as an Integer, YUNG's `username ossrhToken`).
 * mmt only compiles and never publishes, so after the build fails while loading it gives each such name a placeholder,
 * a Gradle property (https://docs.gradle.org/current/userguide/build_environment.html#sec:project_properties) or an
 * environment variable as the script reads it, and reads the build once more. A build that loads is never given one.
 */
final class BuildSecrets {

    static final String PLACEHOLDER = "mmt-no-secret";

    /** A name that holds a secret by what it says. Signing keys are left out: a placeholder key would make signing fail. */
    static final Pattern SECRET = Pattern.compile("(?i)token|secret|passw|webhook|api_?key|(?<=[a-z])Key$|_KEY$");
    static final Pattern NOT_A_SECRET = Pattern.compile("(?i)sign|gpg|pgp");

    static final Pattern ENV = Pattern.compile("(?:getenv|environmentVariable)\\s*\\(\\s*[\"']([A-Za-z_][A-Za-z0-9_]*)[\"']\\s*\\)");
    static final Pattern IDENTIFIER = Pattern.compile("(?<![\\w$.\"'])([A-Za-z_][A-Za-z0-9_]*)(?![\\w$\"'(])");
    static final Pattern QUOTED = Pattern.compile("[\"']([A-Za-z_][A-Za-z0-9_.]*)[\"']");

    private BuildSecrets() {}

    static boolean secret(String name) {
        return SECRET.matcher(name).find() && !NOT_A_SECRET.matcher(name).find();
    }

    /**
     * The secrets a build's scripts name: "env:NAME" for an environment variable it reads, "prop:NAME" for a Gradle
     * property (read by findProperty, property, by project, properties[...] or as a bare name). Names the build's or the
     * user's gradle.properties, or the environment, already set are left alone.
     */
    static SortedSet<String> named(ProjectModel project) throws IOException {
        List<String> scripts = new ArrayList<>();
        for (Path file : project.inputs()) {
            String name = file.getFileName().toString();
            if (name.endsWith(".gradle") || name.endsWith(".gradle.kts")) scripts.add(ProjectModel.read(file));
        }
        return named(scripts, set(project), System.getenv());
    }

    /** The Gradle properties the build's and the user's gradle.properties set. */
    static Properties set(ProjectModel project) throws IOException {
        Properties set = ProjectModel.properties(GradleWrapper.userHome().resolve("gradle.properties"));
        for (Path file : project.inputs()) if (file.getFileName().toString().equals("gradle.properties")) set.putAll(ProjectModel.properties(file));
        return set;
    }

    /**
     * True while nothing gives the name a value: the environment for "env:NAME"; for "prop:NAME" a gradle.properties or
     * ORG_GRADLE_PROJECT_NAME (https://docs.gradle.org/current/userguide/build_environment.html#setting_a_project_property).
     */
    static boolean unset(String name, Properties set, Map<String, String> environment) {
        if (name.startsWith("env:")) return !environment.containsKey(name.substring(4));
        String property = name.substring(name.indexOf(':') + 1);
        return !set.containsKey(property) && !environment.containsKey("ORG_GRADLE_PROJECT_" + property);
    }

    static SortedSet<String> named(List<String> scripts, Properties set, Map<String, String> environment) {
        SortedSet<String> names = new TreeSet<>();
        for (String script : scripts) {
            String code = script.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)(^|[^:])//.*$", "$1");
            Set<String> env = new HashSet<>();
            for (Matcher m = ENV.matcher(code); m.find();) env.add(m.group(1));
            for (String name : env) if (secret(name) && unset("env:" + name, set, environment)) names.add("env:" + name);
            List<String> props = new ArrayList<>();
            for (Matcher m = QUOTED.matcher(code); m.find();) props.add(m.group(1));
            for (Matcher m = IDENTIFIER.matcher(code.replaceAll("\"(?:[^\"\\\\\\n]|\\\\.)*\"|'(?:[^'\\\\\\n]|\\\\.)*'", "\"\"")); m.find();) props.add(m.group(1));
            for (String name : props) if (secret(name) && !env.contains(name) && unset("prop:" + name, set, environment)) names.add("prop:" + name);
        }
        return names;
    }

    /** True when Gradle's failure while loading the build names a secret: the reason to read it once more with placeholders. */
    static boolean stoppedOn(String failure) {
        for (Matcher m = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*").matcher(failure); m.find();) if (secret(m.group())) return true;
        return false;
    }

    /** The command-line arguments for the property placeholders. */
    static List<String> arguments(Collection<String> names) {
        List<String> out = new ArrayList<>();
        for (String name : names) if (name.startsWith("prop:")) out.add("-P" + name.substring(5) + "=" + PLACEHOLDER);
        return out;
    }

    /** The environment for the variable placeholders. */
    static Map<String, String> environment(Collection<String> names) {
        Map<String, String> out = new TreeMap<>();
        for (String name : names) if (name.startsWith("env:")) out.put(name.substring(4), PLACEHOLDER);
        return out;
    }

    /** Where mmt keeps the placeholders it gave this checkout, so every later Gradle run of it gets them too. */
    static Path file(ProjectModel project) {
        return project.state().resolve("secret-placeholders.txt");
    }

    /**
     * The placeholders this checkout's Gradle runs get now: those mmt gave it, less any the user has since set, so a
     * token exported after the first build reaches the build instead of the placeholder.
     */
    static SortedSet<String> given(ProjectModel project) throws IOException {
        SortedSet<String> saved = saved(project);
        return saved.isEmpty() ? saved : given(saved, set(project), System.getenv());
    }

    static SortedSet<String> given(Collection<String> saved, Properties set, Map<String, String> environment) {
        SortedSet<String> names = new TreeSet<>();
        for (String name : saved) if (unset(name, set, environment)) names.add(name);
        return names;
    }

    static SortedSet<String> saved(ProjectModel project) throws IOException {
        Path file = file(project);
        SortedSet<String> names = new TreeSet<>();
        if (Files.isRegularFile(file)) for (String line : Files.readAllLines(file)) if (!line.isBlank()) names.add(line.strip());
        return names;
    }
}

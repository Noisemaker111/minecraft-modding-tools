import java.io.*;
import java.util.*;
import java.util.regex.*;

/** A Gradle build script read as text: its blocks, statements, properties and dependency lines, for either loader. */
final class BuildScript {

    final Convert convert;

    BuildScript(Convert convert) {
        this.convert = convert;
    }

    /**
     * Metadata with Gradle's ${property} placeholders, as the build's own expand would write it: each one gradle.properties
     * defines is replaced; the rest stay as they are.
     */
    String expandProperties(String text) throws IOException {
        Map<String, String> props = properties(convert.projectFiles.read("gradle.properties"));
        Matcher m = Pattern.compile("\\$\\{(?:project\\.)?([\\w.]+)}").matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(
            out,
            Matcher.quoteReplacement(props.getOrDefault(m.group(1), m.group()))
        );
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Build code written against the source loader's Gradle plugin classes (import net.fabricmc.loom.task..., import
     * net.neoforged.moddevgradle.internal...): the imports and every top-level statement that names an imported class go,
     * since the other loader's plugin has no such class; each removed statement is named.
     */
    String dropPluginCode(String gradle, String pluginPackage) {
        Matcher m = Pattern.compile("(?m)^import\\s+(" + Pattern.quote(pluginPackage) + "[\\w.]*\\.(\\w+))\\s*;?[ \\t]*\\n?").matcher(gradle);
        List<String> simple = new ArrayList<>();
        while (m.find()) simple.add(m.group(2));
        if (simple.isEmpty()) return gradle;
        gradle = m.reset().replaceAll("");
        Pattern names = Pattern.compile("\\b(?:" + String.join("|", simple) + ")\\b");
        List<String> removed = new ArrayList<>();
        gradle = dropStatements(gradle, s -> names.matcher(s).find(), removed);
        for (String statement : removed) convert.projectFiles.leave(
            "build.gradle",
            0,
            statement.strip().lines().findFirst().orElse(""),
            "build code using " + pluginPackage + " classes, which the other loader's Gradle plugin does not have; removed"
        );
        return gradle;
    }

    /** The block the way to Fabric adds so kept NeoForge mods do not bring NeoForge into the Fabric build. */
    static final String NEOFORGE_EXCLUSION =
        "\n// Dependencies kept from the NeoForge build may ask for NeoForge itself, which the Fabric build does not have.\n" +
        "configurations.configureEach {\n";

    /** The loader a publishing plugin names (mod-publish-plugin's modLoaders.add, publishMods' loaders) follows the build. */
    static String publishLoader(String gradle, String from, String to) {
        return gradle.replaceAll("(modLoaders\\.add\\(\\s*[\"'])" + from + "([\"']\\s*\\))", "$1" + to + "$2");
    }

    /** The marker of a dependency the Fabric bridge stands in for, kept as a comment in the NeoForge build for the way back. */
    static final String BRIDGED_DEPENDENCY = "// mmt convert, Fabric bridge stands in for: ";

    static List<String> metadataVersions(String xml) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        while (m.find()) out.add(m.group(1).strip());
        return out;
    }

    static String newest(List<String> versions) {
        String best = null;
        for (String v : versions) if (best == null || Versions.compare(v, best) > 0) best = v;
        return best;
    }

    /**
     * The newest stable release of a Gradle plugin that runs on this project's Gradle: its published Gradle module
     * metadata names the oldest Gradle it supports (org.gradle.plugin.api-version), and alphas and snapshots are skipped.
     */
    String stablePlugin(String repository, String group, String artifact) throws Exception {
        String base = repository + "/" + group.replace('.', '/') + "/" + artifact;
        List<String> stable = new ArrayList<>(
            metadataVersions(Target.fetch(convert.build, base + "/maven-metadata.xml"))
                .stream()
                .filter(v -> !v.contains("-"))
                .toList()
        );
        stable.sort((a, b) -> Versions.compare(b, a));
        String gradle = convert.project.wrapperVersion(), newestNeeds = null;
        boolean anyMetadata = false;
        for (String version : stable.subList(0, Math.min(12, stable.size()))) {
            String needs = null;
            boolean metadata = true;
            try {
                for (Object variant : Json.array(
                    Json.object(
                        Json.parse(
                            Target.fetch(convert.build, base + "/" + version + "/" + artifact + "-" + version + ".module")
                        )
                    ).get("variants")
                )) {
                    Object attributes = Json.object(variant).get("attributes");
                    if (
                        attributes instanceof Map<?, ?> &&
                        Json.object(attributes).get("org.gradle.plugin.api-version") != null
                    ) needs = String.valueOf(Json.object(attributes).get("org.gradle.plugin.api-version"));
                }
            } catch (IOException | IllegalArgumentException noModule) {
                // No Gradle module metadata (yet: a release mid-publication has its jar before its .module): what it
                // needs is unknown, so an older release that says is preferred.
                metadata = false;
            }
            if (!metadata) continue;
            anyMetadata = true;
            if (needs == null || gradle == null || Versions.compare(gradle, needs) >= 0) return version;
            if (newestNeeds == null) newestNeeds = needs;
        }
        if (stable.isEmpty()) throw new IOException("No stable " + group + ":" + artifact + " release in " + repository + ".");
        // No release says what it needs: the newest, as before.
        if (!anyMetadata) return stable.get(0);
        // None runs on this Gradle: the newest release, and the wrapper moves to the Gradle its own metadata asks for.
        upgradeWrapper(newestNeeds, group + ":" + artifact + " " + stable.get(0));
        return stable.get(0);
    }

    /**
     * Points the Gradle wrapper at the newest final Gradle release of the line a plugin's metadata asks for (at least
     * that version), with the SHA-256 Gradle publishes beside it when the wrapper pins one.
     */
    void upgradeWrapper(String needs, String why) throws Exception {
        String file = "gradle/wrapper/gradle-wrapper.properties";
        String text = convert.projectFiles.read(file);
        String line = needs.replaceAll("^(\\d+\\.\\d+).*", "$1");
        String best = null;
        for (Object item : Json.array(Json.parse(Target.fetch(convert.build, "https://services.gradle.org/versions/all")))) {
            var v = Json.object(item);
            String version = Json.text(v, "version", "");
            if (
                Boolean.TRUE.equals(v.get("snapshot")) ||
                Boolean.TRUE.equals(v.get("broken")) ||
                !Json.text(v, "rcFor", "").isEmpty() ||
                !Json.text(v, "milestoneFor", "").isEmpty() ||
                version.contains("-")
            ) continue;
            if (!(version.equals(line) || version.startsWith(line + "."))) continue;
            if (Versions.compare(version, needs) < 0) continue;
            if (best == null || Versions.compare(version, best) > 0) best = version;
        }
        if (best == null) throw new IOException("Gradle has no final release " + needs + " or later in the " + line + " line, which " + why + " needs.");
        String kind = text.contains("-all.zip") ? "all" : "bin";
        String url = "https\\://services.gradle.org/distributions/gradle-" + best + "-" + kind + ".zip";
        String out = text.replaceAll("(?m)^distributionUrl=.*$", Matcher.quoteReplacement("distributionUrl=" + url));
        if (Pattern.compile("(?m)^distributionSha256Sum=").matcher(out).find()) {
            String sum = Target.fetch(convert.build, "https://services.gradle.org/distributions/gradle-" + best + "-" + kind + ".zip.sha256").strip();
            out = out.replaceAll("(?m)^distributionSha256Sum=.*$", "distributionSha256Sum=" + sum);
        }
        String old = convert.project.wrapperVersion();
        convert.projectFiles.write(file, out);
        convert.notes.add("Gradle wrapper: " + old + " to " + best + ", the Gradle " + why + " asks for in its published metadata (" + needs + " or later).");
        // Gradle's foojay toolchain resolver before 1.0.0 names JvmVendorSpec.IBM_SEMERU, which Gradle 9 removed: a build
        // that provisions a toolchain then fails to evaluate. 1.0.0 is the resolver release written for Gradle 9.
        String settings = convert.projectFiles.read("settings.gradle");
        Matcher foojay = Pattern.compile("(id\\s*\\(?\\s*['\"]org\\.gradle\\.toolchains\\.foojay-resolver-convention['\"]\\s*\\)?\\s*version\\s*\\(?\\s*['\"])([\\d.]+)(['\"])").matcher(settings);
        if (Versions.compare(best, "9") >= 0 && foojay.find() && Versions.compare(foojay.group(2), "1.0.0") < 0) {
            convert.projectFiles.write("settings.gradle", settings.substring(0, foojay.start(2)) + "1.0.0" + settings.substring(foojay.end(2)));
            convert.notes.add("Gradle's foojay toolchain resolver: " + foojay.group(2) + " to 1.0.0, the first release that runs on Gradle 9.");
        }
    }

    static Map<String, String> properties(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : text.lines().toList()) {
            String s = line.strip();
            if (s.startsWith("#") || !s.contains("=")) continue;
            out.put(s.substring(0, s.indexOf('=')).strip(), s.substring(s.indexOf('=') + 1).strip());
        }
        return out;
    }

    /** Sets, adds or removes (value null) gradle.properties keys, keeping comments and order. */
    static String setProperties(String text, Map<String, String> values) {
        List<String> lines = new ArrayList<>(text.lines().toList());
        Set<String> done = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i).strip();
            if (s.startsWith("#") || !s.contains("=")) continue;
            String key = s.substring(0, s.indexOf('=')).strip();
            if (!values.containsKey(key)) continue;
            done.add(key);
            if (values.get(key) == null) lines.remove(i--);
            else lines.set(i, key + "=" + values.get(key));
        }
        for (var entry : values.entrySet()) if (entry.getValue() != null && !done.contains(entry.getKey())) lines.add(
            entry.getKey() + "=" + entry.getValue()
        );
        return String.join("\n", lines) + "\n";
    }

    /** The span of a top-level block "name {" ... "}" in a build script, or null. */
    static int[] block(String text, String name) {
        // A top-level block first: buildscript { dependencies { ... } } is not the build's own dependencies.
        int[] top = block(text, name, "(?m)^");
        return top != null ? top : block(text, name, "(?m)^[ \\t]*");
    }

    static int[] block(String text, String name, String indent) {
        boolean[] code = LoaderRules.code(text);
        Matcher m = Pattern.compile(indent + Pattern.quote(name) + "\\s*\\{").matcher(text);
        while (m.find()) {
            if (!code[m.start() + m.group().indexOf(name.charAt(0))]) continue;
            int open = m.end() - 1, end = LoaderRules.close(text, code, open);
            if (end < 0) return null;
            int stop = end;
            while (stop < text.length() && text.charAt(stop) != '\n') stop++;
            return new int[] { m.start(), Math.min(text.length(), stop + 1), open, end };
        }
        return null;
    }

    /** A block body without the named blocks directly inside it: the settings written as plain lines. */
    static String withoutChildren(String body) {
        boolean[] code = LoaderRules.code(body);
        Matcher m = Pattern.compile("([\\w\"'${}]+)\\s*\\{").matcher(body);
        StringBuilder out = new StringBuilder();
        int from = 0;
        while (m.find(from)) {
            int open = m.end() - 1, end = LoaderRules.close(body, code, open);
            if (end < 0) break;
            out.append(body, from, m.start());
            from = end;
        }
        return out.append(body.substring(from)).toString();
    }

    /** The next top-level block of that name starting at or after from, or null. */
    static int[] nextBlock(String text, String name, int from) {
        int[] found = block(text.substring(from), name, "(?m)^");
        return found == null ? null : new int[] { found[0] + from, found[1] + from, found[2] + from, found[3] + from };
    }

    /** The named blocks directly inside a block body: runs { client { ... } } gives client. */
    static Map<String, String> children(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        boolean[] code = LoaderRules.code(body);
        Matcher m = Pattern.compile("([\\w\"'${}]+)\\s*\\{").matcher(body);
        int from = 0;
        while (m.find(from)) {
            int open = m.end() - 1, end = LoaderRules.close(body, code, open);
            if (end < 0) break;
            // A name given twice (two loom blocks read as one) keeps both bodies.
            out.merge(m.group(1).replaceAll("[\"']", ""), body.substring(open + 1, end - 1), (a, b) -> a + "\n" + b);
            from = end;
        }
        return out;
    }

    String dropStatements(String gradle, java.util.function.Predicate<String> drop, String why) {
        List<String> removed = new ArrayList<>();
        String out = dropStatements(gradle, drop, removed);
        for (String r : removed) convert.notes.add("Build: removed `" + r + "` (" + why + ").");
        return out;
    }
    static final Map<String, String[]> LOADER_WORDS = Map.of(
        "fabric",
        new String[] { "fabric", "Fabric", "FABRIC" },
        "neoforge",
        new String[] { "neoforge", "NeoForge", "NEOFORGE" }
    );

    /** The Maven repositories a build script declares, then Maven Central. */
    static List<String> repositories(String gradle) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\burl\\s*=?\\s*\\(?\\s*(?:uri\\s*\\(\\s*)?[\"'](https?://[^\"'$]+?)/?[\"']").matcher(gradle);
        while (m.find()) if (!out.contains(m.group(1))) out.add(m.group(1));
        out.add("https://repo.maven.apache.org/maven2");
        return out;
    }

    /** An artifact name with the loader's word, as a whole part between - _ or ., changed to the other loader's; null when it has none. */
    static String swapLoaderWord(String artifact, String from, String to) {
        String[] a = LOADER_WORDS.get(from), b = LOADER_WORDS.get(to);
        for (int i = 0; i < a.length; i++) {
            Matcher m = Pattern.compile("(?<=^|[-_.])" + a[i] + "(?=$|[-_.])").matcher(artifact);
            if (m.find()) return artifact.substring(0, m.start()) + b[i] + artifact.substring(m.end());
        }
        return null;
    }

    /** Whether an artifact names no loader at all (a library, or a mod's loader-neutral API). */
    static boolean loaderNeutral(String artifact) {
        return !Pattern.compile("(?i)(?:^|[-_.])(?:fabric|neoforge|forge|quilt)(?:$|[-_.])").matcher(artifact).find();
    }

    String resolveProperties(String text) throws IOException {
        Map<String, String> props = properties(convert.projectFiles.read("gradle.properties"));
        Matcher m = Pattern.compile("\\$\\{?(?:project\\.)?(\\w+)}?").matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(props.getOrDefault(m.group(1), m.group())));
        m.appendTail(out);
        return out.toString();
    }

    /**
     * A dependency line moved to the other loader's build of the same project: the coordinate's artifact with its loader
     * word changed (jei-26.1.2-neoforge to jei-26.1.2-fabric), only when one of the build's own repositories publishes that
     * artifact at that version. Null when none does; the line is then named, never guessed.
     */
    String otherLoaderDependency(String line, String from, String to, List<String> repos) throws IOException {
        Matcher c = Convert.COORDINATE.matcher(line);
        if (!c.find()) return null;
        String artifact = c.group(3), swapped = swapLoaderWord(artifact, from, to);
        if (swapped == null) return null;
        String group = resolveProperties(c.group(2)), name = resolveProperties(swapped), version = resolveProperties(c.group(4));
        if (group.contains("$") || name.contains("$")) return null;
        for (String repo : repos) {
            try {
                List<String> versions = metadataVersions(
                    Target.fetch(convert.build, repo + "/" + group.replace('.', '/') + "/" + name + "/maven-metadata.xml")
                );
                if (version.contains("$") ? versions.isEmpty() : !versions.contains(version)) continue;
                return line.substring(0, c.start(3)) + swapped + line.substring(c.end(3));
            } catch (Exception notThere) {
                // The next repository.
            }
        }
        return null;
    }

    /**
     * One dependency line of the source loader's build, for the target build: a loader-specific artifact moves to the
     * other loader's build of the same project when a repository publishes it; a loader-neutral library stays and is
     * not named; anything else is named with the reason.
     */
    String dependencyLine(String raw, String from, String to, List<String> repos, boolean remapMods) throws IOException {
        // A statement over several lines ({ exclude ... }) is named by its first line.
        String line = raw.strip().lines().findFirst().orElse("");
        Matcher c = Convert.COORDINATE.matcher(line);
        if (!c.find()) return raw;
        if (c.group(2).startsWith("curse.maven") || c.group(2).startsWith("maven.modrinth")) {
            convert.projectFiles.leave("build.gradle", 0, "dependency " + line, "a file of one loader's build on CurseForge or Modrinth: the other loader's file has another id, which convert cannot choose");
            return raw;
        }
        if (loaderNeutral(c.group(3))) {
            neutralDependencies.add(c.group(2) + ":" + c.group(3));
            return raw;
        }
        String moved = otherLoaderDependency(raw, from, to, repos);
        if (moved == null) {
            convert.projectFiles.leave("build.gradle", 0, "dependency " + line, "a " + from + " build of another mod: no " + to + " build of it at this version in the build's repositories");
            return raw;
        }
        if (remapMods) moved = remapConfiguration(moved);
        Matcher now = Convert.COORDINATE.matcher(moved);
        String coordinate = now.find() ? now.group(2) + ":" + now.group(3) : moved.strip();
        convert.applied.computeIfAbsent("dependency-other-loader-build", k -> new ArrayList<>()).add("build.gradle: " + coordinate);
        return moved;
    }

    /** Loom's remapping configurations for a mod dependency (before 26.1): implementation becomes modImplementation and so on. */
    static String remapConfiguration(String line) {
        Matcher m = Pattern.compile("\\b(implementation|compileOnly|runtimeOnly|localRuntime|api)(?=\\s*[(\"'])").matcher(line);
        if (!m.find()) return line;
        String config = m.group(1);
        return line.substring(0, m.start()) + "mod" + Character.toUpperCase(config.charAt(0)) + config.substring(1) + line.substring(m.end());
    }

    final Set<String> neutralDependencies = new TreeSet<>();

    /** Removes each top-level statement of a build script (balanced brackets; comments are kept) that matches. */
    static String dropStatements(String gradle, java.util.function.Predicate<String> drop, List<String> removed) {
        StringBuilder out = new StringBuilder();
        for (String statement : statements(gradle)) {
            String s = statement.strip();
            boolean comment = s.startsWith("//") || s.startsWith("/*") || s.startsWith("*");
            if (!comment && drop.test(statement)) removed.add(s.lines().findFirst().orElse(""));
            else out.append(statement);
        }
        return out.toString();
    }

    /**
     * A build script (or a block's body) as its statements, each with its line ends: a statement runs to the end of the
     * line where its brackets balance, so implementation("x") { exclude(...) } is one statement over three lines.
     */
    static List<String> statements(String gradle) {
        boolean[] code = LoaderRules.code(gradle);
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < gradle.length()) {
            int depth = 0, end = i;
            while (end < gradle.length()) {
                char c = gradle.charAt(end);
                if (code[end] && (c == '{' || c == '(' || c == '[')) depth++;
                else if (code[end] && (c == '}' || c == ')' || c == ']')) depth--;
                end++;
                if (c == '\n' && depth <= 0) break;
            }
            out.add(gradle.substring(i, end));
            i = end;
        }
        return out;
    }
}

import java.io.*;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * The Gradle wrapper a mod's build runs through, when its checkout lacks one. Gradle's own advice is to commit the
 * wrapper (https://docs.gradle.org/current/userguide/gradle_wrapper.html, "Adding the Gradle Wrapper"), but many mods
 * ignore gradle-wrapper.jar, or all of gradle/wrapper/, and build with whatever Gradle their author has. mmt never adds
 * files to the checkout for this: the wrapper it supplies lives in .mmt/wrapper/, beside its other state.
 */
final class GradleWrapper {

    /**
     * The Gradle a build that names none runs on: the last Gradle 8 release. Gradle 9 removed the APIs its 8.x line had
     * deprecated (https://docs.gradle.org/9.0.0/userguide/upgrading_major_version_9.html), which the mod build plugins of
     * 2023-2025 (ForgeGradle 6, NeoGradle 7, Loom 1.x before 1.11) still call; 8.14 runs on Java 8 to 24
     * (https://docs.gradle.org/current/userguide/compatibility.html).
     */
    static final String NAMED_BY_NONE = "8.14.3";

    static final String PROPERTIES = "gradle/wrapper/gradle-wrapper.properties";
    static final String JAR = "gradle/wrapper/gradle-wrapper.jar";

    private GradleWrapper() {}

    /** The distribution URL the checkout's wrapper names, or Gradle's own URL for NAMED_BY_NONE when it names none. */
    static String ownUrl(Path root) throws IOException {
        String url = ProjectModel.properties(root.resolve(PROPERTIES)).getProperty("distributionUrl", "").strip();
        return url.isEmpty() ? official(NAMED_BY_NONE) : url;
    }

    static String official(String version) {
        return "https://services.gradle.org/distributions/gradle-" + version + "-bin.zip";
    }

    /** The distribution the build runs on: the checkout's own, or the newer release of its line mmt rose to (rise). */
    static String distributionUrl(Path root) throws IOException {
        String own = ownUrl(root), risen = risen(root);
        String version = version(own);
        if (risen == null || version == null) return own;
        // Only upward and within the line: a port that moved the wrapper itself past the rise wins.
        boolean newer = major(risen) == major(version) && Versions.compare(risen, version) > 0;
        return newer ? official(risen) : own;
    }

    /** The Gradle version in a distribution URL (gradle-8.14.3-bin.zip, gradle-9.0-rc-1-all.zip), or null. */
    static String version(String url) {
        Matcher found = Pattern.compile("gradle-([0-9]+\\.[0-9]+(?:\\.[0-9]+)?(?:-rc-?\\d+|-milestone-\\d+)?)-(?:bin|all)\\.zip").matcher(url);
        return found.find() ? found.group(1) : null;
    }

    static int major(String version) {
        return Integer.parseInt(version.split("\\.")[0]);
    }

    /**
     * The wrapper jar to launch Gradle with: the checkout's own when it has one and mmt runs the release it names, else
     * a copy in .mmt/wrapper/ of the jar the release mmt runs ships, with a properties file naming that release.
     */
    static Path jar(Path root) throws IOException {
        Path own = root.resolve(JAR);
        String url = distributionUrl(root);
        boolean complete = Files.isRegularFile(own) && Files.isRegularFile(root.resolve(PROPERTIES));
        if (complete && url.equals(ownUrl(root))) return own;
        // GradleWrapperMain reads the .properties beside its jar and takes the project root to be three folders above
        // the jar (gradle/wrapper/gradle-wrapper.jar), so .mmt/wrapper/ keeps both right.
        Path folder = root.resolve(".mmt/wrapper");
        Path jar = folder.resolve("gradle-wrapper.jar");
        Path properties = folder.resolve("gradle-wrapper.properties");
        Files.createDirectories(folder);
        Properties wanted = new Properties();
        if (Files.isRegularFile(root.resolve(PROPERTIES))) wanted.putAll(ProjectModel.properties(root.resolve(PROPERTIES)));
        // The checkout's checksum is its own release's; another release is checked by nothing the checkout states.
        if (!url.equals(ownUrl(root))) wanted.remove("distributionSha256Sum");
        wanted.setProperty("distributionUrl", url);
        try (Writer out = Files.newBufferedWriter(properties)) {
            wanted.store(out, "Written by mmt: the Gradle release this checkout runs on, beside its own files");
        }
        // Only the checkout's own release is checked by its pinned checksum; a risen one was removed from wanted above.
        Files.copy(shipped(url, wanted.getProperty("distributionSha256Sum")), jar, StandardCopyOption.REPLACE_EXISTING);
        return jar;
    }

    /**
     * A build plugin asked for by a range or a -SNAPSHOT resolves to its newest release, which can be built against a
     * newer Gradle than the checkout's wrapper: VanillaGradle 0.2.1-SNAPSHOT is now built with Gradle 8.14.3 and calls
     * includeGroupAndSubgroups (Gradle 8.1), so FerriteCore's Gradle 8.0 stops with a NoSuchMethodError, and Melody's
     * 8.1.1 finds none of its common project's libraries; both build on the newest 8.x. Gradle keeps every API of a
     * major line until the next major (https://docs.gradle.org/current/userguide/feature_lifecycle.html, "Backward
     * compatibility policy"), so a build that stopped this way runs once more on the newest release of its own line.
     */
    static final Pattern NEEDS_NEWER_GRADLE = Pattern.compile(
        // A method a plugin calls that this Gradle lacks (NoSuchMethodError prints it quoted with its parameters).
        "'[\\w.$\\[\\]<> ]+ [\\w.$]+\\([^']*\\)'" +
        "|Missing implementation of resolved method" +
        "|Extension of type '\\w+' does not exist" +
        "|NoClassDefFoundError: org/gradle/"
    );

    /** Libraries no repository was asked for: "Could not find G:A:V. Required by:" with no searched locations. */
    static final Pattern UNSEARCHED = Pattern.compile("Could not find [\\w.-]+:[\\w.-]+:[\\w.+-]+\\.\\s+Required by:");

    /**
     * A plugin that states the Gradle it needs: Loom's own check ("requires at least Gradle 8.12", "Gradle 8.12 or
     * higher is required"), and a plugin whose metadata declares a newer org.gradle.plugin.api-version, which Gradle
     * refuses while resolving it (NeoGradle 7.0.192 declares 8.13; MineColonies' 1.20.4 wrapper is 8.8).
     */
    static final List<Pattern> MINIMUM_GRADLE = List.of(
        Pattern.compile("requires at least Gradle (\\d+\\.\\d+(?:\\.\\d+)?)"),
        Pattern.compile("Gradle (\\d+\\.\\d+(?:\\.\\d+)?) or (?:higher|newer|later) is required"),
        // Gradle's variant report: "this component declares ..., as well as attribute 'org.gradle.plugin.api-version' with
        // value '8.13' and the consumer needed ... with value '8.8'".
        Pattern.compile("org\\.gradle\\.plugin\\.api-version' with value '(\\d+\\.\\d+(?:\\.\\d+)?)' and the consumer needed"),
        Pattern.compile("org\\.gradle\\.plugin\\.api-version'? with value '[\\d.]+' but the component declares '(\\d+\\.\\d+(?:\\.\\d+)?)'"),
        Pattern.compile("compatible with Gradle (\\d+\\.\\d+(?:\\.\\d+)?) and the consumer needed")
    );

    /** The newest Gradle a plugin in this failure says it needs, or null when none says. */
    static String minimumGradle(String log) {
        String minimum = null;
        for (Pattern pattern : MINIMUM_GRADLE) for (Matcher m = pattern.matcher(log); m.find();) {
            if (minimum == null || Versions.compare(m.group(1), minimum) > 0) minimum = m.group(1);
        }
        return minimum;
    }

    static boolean needsNewerGradle(String log) {
        return minimumGradle(log) != null ||
            NEEDS_NEWER_GRADLE.matcher(log).find() ||
            (UNSEARCHED.matcher(log).find() && !log.contains("Searched in the following locations"));
    }

    /** The newest stable release of a version's major line among Gradle's released versions, or null when it is that. */
    static String newestOfLine(String version, List<String> released) {
        String newest = version;
        for (String candidate : released) if (
            candidate.matches("\\d+\\.\\d+(\\.\\d+)?") && major(candidate) == major(version) && Versions.compare(candidate, newest) > 0
        ) newest = candidate;
        return newest.equals(version) ? null : newest;
    }

    /** Gradle's released versions (https://services.gradle.org/versions/all), without snapshots, candidates or broken ones. */
    static List<String> released() throws IOException {
        Path file = Files.createTempFile("gradle-versions", ".json");
        try {
            download("https://services.gradle.org/versions/all", file);
            List<String> out = new ArrayList<>();
            for (Object entry : (List<?>) Json.parse(Files.readString(file))) {
                Map<?, ?> v = (Map<?, ?>) entry;
                if (Boolean.TRUE.equals(v.get("snapshot")) || Boolean.TRUE.equals(v.get("broken"))) continue;
                out.add(String.valueOf(v.get("version")));
            }
            return out;
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * The release a build on current that failed with this log runs once more on: the newest of its line, and when a
     * plugin names the Gradle it needs, only if that release meets it (never across a major line). Null otherwise.
     */
    static String riseTo(String current, String log, List<String> released) {
        if (!needsNewerGradle(log)) return null;
        String newest = newestOfLine(current, released);
        String minimum = minimumGradle(log);
        return newest == null || minimum != null && Versions.compare(newest, minimum) < 0 ? null : newest;
    }

    static Path risenFile(Path root) {
        return root.resolve(".mmt/portable/gradle-release.txt");
    }

    static String risen(Path root) throws IOException {
        Path file = risenFile(root);
        return Files.isRegularFile(file) ? Files.readString(file).strip() : null;
    }

    /**
     * After a Gradle run that failed: the newest release of the build's line when the failure is a plugin needing a
     * newer Gradle and mmt has not risen already; it is kept for every later run of this checkout. Null otherwise.
     */
    static String rise(Path root, String log) throws IOException {
        if (!needsNewerGradle(log)) return null;
        // Once per checkout: a build already on a risen release has nowhere left in its line to go.
        String current = version(distributionUrl(root));
        if (current == null || !distributionUrl(root).equals(ownUrl(root))) return null;
        String newest;
        try {
            newest = riseTo(current, log, released());
        } catch (IOException unreachable) {
            return null; // without Gradle's list of releases the build keeps its own; its failure stands
        }
        if (newest == null) return null;
        Files.createDirectories(risenFile(root).getParent());
        Files.writeString(risenFile(root), newest);
        return newest;
    }

    /** Gradle's user home, where the wrapper keeps its distributions: gradle.user.home, else GRADLE_USER_HOME, else ~/.gradle. */
    static Path userHome() {
        String set = System.getProperty("gradle.user.home", System.getenv("GRADLE_USER_HOME"));
        return set != null && !set.isBlank() ? Path.of(set) : Path.of(System.getProperty("user.home"), ".gradle");
    }

    /**
     * Where the wrapper keeps a distribution: wrapper/dists/NAME/HASH/, HASH being the distribution URL's MD5 in base 36
     * (Gradle's PathAssembler), so a zip mmt fetched there is the one the wrapper then unpacks instead of downloading.
     */
    static Path distFolder(String url) {
        String name = url.substring(url.lastIndexOf('/') + 1).replaceFirst("\\.zip$", "");
        try {
            byte[] md5 = MessageDigest.getInstance("MD5").digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return userHome().resolve("wrapper/dists").resolve(name).resolve(new BigInteger(1, md5).toString(36));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * The gradle-wrapper.jar a Gradle release ships inside its own wrapper plugin, lib/plugins/gradle-wrapper*.jar
     * (lib/gradle-wrapper-VERSION.jar in older releases such as 7.3), the jar its wrapper task writes, cached in
     * ~/.mmt/gradle-wrapper/ under the distribution's own URL (and pinned checksum), so a mirror's or a custom build's
     * wrapper is never handed to a checkout that names another distribution. Read from the unpacked distribution, else
     * its zip, else the zip is downloaded to where the wrapper looks for it. A pinned checksum (the checkout's
     * distributionSha256Sum) is checked on the zip before anything in it is read, as Gradle's wrapper would before
     * unpacking it; the unpacked copy may have come from a checkout that pinned none, so it is not used then.
     */
    static Path shipped(String url, String sha256) throws IOException {
        String version = version(url);
        if (version == null) throw new IOException(
            "This checkout has no gradle-wrapper.jar and its distributionUrl " + url + " names no Gradle release; restore its Gradle wrapper."
        );
        boolean pinned = sha256 != null && !sha256.isBlank();
        Path dist = distFolder(url);
        Path cached = Path.of(System.getProperty("user.home"), ".mmt", "gradle-wrapper", version)
            .resolve(dist.getFileName() + (pinned ? "-" + sha256.strip().toLowerCase(Locale.ROOT) : ""))
            .resolve("gradle-wrapper.jar");
        if (Files.isRegularFile(cached)) return cached;
        byte[] jar = null;
        if (!pinned) for (String lib : List.of("lib/plugins", "lib")) {
            Path unpacked = dist.resolve("gradle-" + version).resolve(lib);
            if (Files.isDirectory(unpacked)) try (var files = Files.list(unpacked)) {
                for (Path plugin : files.filter(f -> f.getFileName().toString().matches("gradle-wrapper.*\\.jar")).toList()) {
                    if (jar == null) jar = entry(Files.readAllBytes(plugin), "gradle-wrapper.jar");
                }
            }
        }
        if (jar == null) {
            Path zip = dist.resolve(url.substring(url.lastIndexOf('/') + 1));
            if (!Files.isRegularFile(zip)) download(url, zip);
            if (pinned) {
                String actual = sha256(zip);
                if (!actual.equalsIgnoreCase(sha256.strip())) throw new IOException(
                    "The Gradle distribution " + url + " does not match this checkout's distributionSha256Sum (" + sha256.strip() +
                    ", got " + actual + "); nothing in it was run. Delete " + zip + " to download it again, or fix the checksum."
                );
            }
            try (ZipFile file = new ZipFile(zip.toFile())) {
                for (ZipEntry e : Collections.list(file.entries())) if (
                    jar == null && e.getName().matches("gradle-[^/]+/lib/(plugins/)?gradle-wrapper[^/]*\\.jar")
                ) jar = entry(file.getInputStream(e).readAllBytes(), "gradle-wrapper.jar");
            }
        }
        if (jar == null) throw new IOException("Gradle " + version + " ships no gradle-wrapper.jar in its wrapper plugin; restore this checkout's Gradle wrapper.");
        Files.createDirectories(cached.getParent());
        Path part = cached.resolveSibling("gradle-wrapper.jar.part");
        Files.write(part, jar);
        Files.move(part, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return cached;
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            for (int read; (read = in.read(buffer)) > 0;) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** One entry of a jar held in memory, or null. */
    static byte[] entry(byte[] jar, String name) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null;) if (e.getName().equals(name)) return in.readAllBytes();
        }
        return null;
    }

    static void download(String url, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Path part = to.resolveSibling(to.getFileName() + ".mmt-part");
        HttpResponse<Path> response;
        try {
            response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
                .send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build(), HttpResponse.BodyHandlers.ofFile(part));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted downloading " + url, interrupted);
        }
        if (response.statusCode() != 200) {
            Files.deleteIfExists(part);
            throw new IOException("Could not download Gradle (HTTP " + response.statusCode() + "): " + url);
        }
        Files.move(part, to, StandardCopyOption.REPLACE_EXISTING);
    }
}

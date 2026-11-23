import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Gradle itself for the target release: the wrapper, the Java toolchain and build DSL the newer Gradle removed. */
final class TargetGradle {

    /**
     * The unobfuscated game's build tools run on Java 25 (Loom for 26.x refuses a Gradle on 21: Carry On's Fabric project),
     * and Gradle runs on Java 25 from 9.1. A wrapper older than that rises to 9.4.1, which NeoGradle 7.1, ModDevGradle 2
     * and Loom 1.14+ all run on. A wrapper already on 9.1 or newer stays.
     */
    static void gradleForJava25(Target.Setup setup, Build build, String to) throws Exception {
        if (Versions.compare(to, "26.1") < 0) return;
        Path wrapper = build.project.root.resolve("gradle/wrapper/gradle-wrapper.properties");
        if (!Files.isRegularFile(wrapper)) return;
        String text = setup.text(wrapper);
        Matcher url = Pattern.compile("(distributionUrl=[^\\r\\n]*?gradle-)([0-9][0-9.]*(?:-rc-?\\d+)?)(-(?:bin|all)\\.zip)").matcher(text);
        if (!url.find() || Versions.compare(url.group(2), "9.1") >= 0) return;
        String gradle = "9.4.1";
        setup.replace(wrapper, url.group(0), url.group(1) + gradle + url.group(3));
        Matcher sum = Pattern.compile("(?m)^distributionSha256Sum=\\S+").matcher(setup.text(wrapper));
        if (sum.find()) setup.replace(
            wrapper,
            sum.group(0),
            "distributionSha256Sum=" + Target.fetch(build, "https://services.gradle.org/distributions/gradle-" + gradle + url.group(3) + ".sha256").trim()
        );
        setup.applied.add(
            Json.map(
                "file",
                wrapper.toString(),
                "change",
                "Gradle " + url.group(2) + " -> " + gradle + " for " + to,
                "evidence",
                "Minecraft " + to + " builds on Java 25, which Gradle runs on from 9.1"
            )
        );
    }

    /**
     * Build-script settings the build tools the port moves to no longer have. Gradle 8 removed a jar task's classifier
     * (archiveClassifier replaces it: ModMenu 1.16.5 on Gradle 8 stopped with "unknown property 'classifier'"), and Loom
     * 1.x removed loom.shareCaches(), a cache-sharing switch with no effect on what is built (owo-lib). Only the build's own
     * scripts are changed, and only when the port's Gradle is 8 or newer for the first.
     */
    static void buildDslFollows(Target.Setup setup, Build build) throws Exception {
        buildDslFollows(setup, build, true);
    }

    /** One build script without the settings buildDslFollows names. */
    static String removedDsl(String text, boolean gradle8) {
        return removedDsl(text, gradle8, false);
    }

    /**
     * Also, for Gradle 9: project-level sourceCompatibility and targetCompatibility belong to the java extension
     * (a Fabric port 1.20.1 -> 1.21.1 moves to Gradle 9 with Loom 1.18 and stopped on them).
     */
    static String removedDsl(String text, boolean gradle8, boolean gradle9) {
        if (gradle9) {
            text = text.replaceAll("(?m)^(sourceCompatibility|targetCompatibility)([ \\t]*=)", "java.$1$2");
            // Gradle 9 removed the project-level archivesBaseName; the base extension owns it.
            text = text.replaceAll("(?m)^archivesBaseName([ \\t]*=)", "base.archivesName$1");
            // Inside a block (MidnightLib sets it in configurations { }) a bare name resolves against that block.
            text = text.replaceAll("(?m)^([ \\t]+)archivesBaseName([ \\t]*=)", "$1project.base.archivesName$2");
            text = text.replaceAll("(?<![\\w.$])(?:project\\.)?archivesBaseName(?![\\w(])", "project.base.archivesName.get()");
        }
        if (gradle8) {
            text = text.replaceAll("(?m)^([ \\t]*)classifier(\\s*=\\s*|\\s+)([\"'])", "$1archiveClassifier = $3");
            // Gradle 8 removed the archive task's file properties for their Provider forms (FerriteCore: jar.archivePath).
            text = text.replaceAll("\\.archivePath(?![\\w$])", ".archiveFile.get().asFile");
            text = text.replaceAll("\\.archiveName(?![\\w$(])", ".archiveFileName.get()");
            text = text.replaceAll("\\.destinationDir(?![\\w$])", ".destinationDirectory.get().asFile");
        }
        return text.replaceAll("(?m)^[ \\t]*shareCaches\\(\\)[ \\t]*\\r?\\n", "");
    }

    private static void buildDslFollows(Target.Setup setup, Build build, boolean unused) throws Exception {
        Path wrapper = build.project.root.resolve("gradle/wrapper/gradle-wrapper.properties");
        Matcher gradle = Pattern.compile("gradle-([0-9]+)\\.").matcher(Files.isRegularFile(wrapper) ? setup.text(wrapper) : "");
        int major = gradle.find() ? Integer.parseInt(gradle.group(1)) : 0;
        boolean gradle8 = major >= 8, gradle9 = major >= 9;
        for (Path script : build.project.inputs()) {
            // build.gradle and the scripts it applies (ModMenu's gradle/*.gradle via apply from:), not settings.
            String file = script.getFileName().toString();
            if (!file.matches(".*\\.gradle(\\.kts)?") || file.startsWith("settings.") || file.startsWith("gradle.init")) continue;
            String before;
            try {
                before = setup.text(script);
            } catch (java.nio.charset.CharacterCodingException notText) {
                continue; // a script in another encoding (owo-lib has one); nothing to rewrite in it
            }
            String text = removedDsl(before, gradle8, gradle9);
            if (text.equals(before)) continue;
            setup.originals.putIfAbsent(script, before);
            setup.texts.put(script, text);
            setup.applied.add(Json.map("file", script.toString(), "change", "build-script settings the new Gradle and Loom removed (classifier -> archiveClassifier, shareCaches())"));
        }
    }

    /**
     * Gradle rises to the version a build plugin's own module metadata requires (org.gradle.plugin.api-version), and is
     * never lowered: an older Gradle refuses the plugin while the build loads, before any source is compiled.
     */
    static void gradleFollows(Target.Setup setup, Build build, String moduleUrl, String plugin, String evidence)
        throws Exception {
        String module;
        try {
            module = Target.fetch(build, moduleUrl);
        } catch (Target.LookupFailure unreachable) {
            throw unreachable;
        } catch (IOException none) {
            module = ""; // Architectury Loom publishes no Gradle module metadata
        }
        Matcher api = Pattern.compile("\"org\\.gradle\\.plugin\\.api-version\"\\s*:\\s*\"([^\"]+)\"").matcher(module);
        String required = api.find() ? api.group(1) : null, source = " (org.gradle.plugin.api-version)";
        // Loom before 1.x (and Architectury Loom, its fork) publishes no api-version; its own bootstrap class states the Gradle it needs.
        if (required == null && (moduleUrl.contains("/fabric-loom/") || moduleUrl.contains("/architectury-loom/"))) {
            required = loomMinimumGradle(build, Target.download(build, moduleUrl.replaceFirst("\\.module$", ".jar")));
            source = " or newer (its LoomGradlePluginBootstrap states the minimum; the newest release of that line is used)";
        }
        if (required == null) return;
        Path wrapper = build.project.root.resolve("gradle/wrapper/gradle-wrapper.properties");
        if (!Files.isRegularFile(wrapper)) return;
        String text = setup.text(wrapper);
        Matcher url = Pattern.compile(
            "(distributionUrl=[^\\r\\n]*?gradle-)([0-9][0-9.]*(?:-rc-?\\d+)?)(-(?:bin|all)\\.zip)"
        ).matcher(text);
        if (!url.find() || Versions.compare(url.group(2), required) >= 0) return;
        String gradle = required;
        setup.replace(wrapper, url.group(0), url.group(1) + gradle + url.group(3));
        Matcher sum = Pattern.compile("(?m)^distributionSha256Sum=\\S+").matcher(setup.text(wrapper));
        if (sum.find()) setup.replace(
            wrapper,
            sum.group(0),
            "distributionSha256Sum=" +
            Target.fetch(build, "https://services.gradle.org/distributions/gradle-" + gradle + url.group(3) + ".sha256").trim()
        );
        setup.applied.add(
            Json.map(
                "file",
                wrapper.toString(),
                "change",
                "Gradle " + url.group(2) + " -> " + gradle + " for " + plugin,
                "evidence",
                evidence + " requires Gradle " + gradle + source
            )
        );
    }

    /**
     * The Gradle a Loom jar says it needs, from its bootstrap class's constants: MIN_SUPPORTED_GRADLE_VERSION ("8.1") as
     * stated, or MIN_SUPPORTED_MAJOR_GRADLE_VERSION (7) as that major line's newest release on services.gradle.org.
     */
    static String loomMinimumGradle(Build build, Path jar) throws Exception {
        Map<String, Object> constants;
        try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
            var entry = zip.getEntry("net/fabricmc/loom/bootstrap/LoomGradlePluginBootstrap.class");
            if (entry == null) return null;
            constants = classConstants(zip.getInputStream(entry).readAllBytes());
        }
        if (constants.get("MIN_SUPPORTED_GRADLE_VERSION") instanceof String version) return version;
        if (!(constants.get("MIN_SUPPORTED_MAJOR_GRADLE_VERSION") instanceof Integer major)) return null;
        return newestGradle(Target.fetch(build, "https://services.gradle.org/versions/all"), major);
    }

    /** The newest final release of one Gradle major line in services.gradle.org/versions/all. */
    static String newestGradle(String versionsJson, int major) {
        String newest = null;
        Matcher release = Pattern.compile("\"version\"\\s*:\\s*\"(" + major + "\\.[0-9]+(?:\\.[0-9]+)?)\"").matcher(versionsJson);
        while (release.find()) if (newest == null || Versions.compare(release.group(1), newest) > 0) newest = release.group(1);
        return newest;
    }

    /** The compile-time constants of a class file's fields (int and String ConstantValue attributes), by field name. */
    static Map<String, Object> classConstants(byte[] bytes) throws IOException {
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        in.readInt();
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        Object[] pool = new Object[count];
        int[] strings = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> pool[i] = in.readUTF();
                case 3 -> pool[i] = in.readInt();
                case 4 -> in.readFloat();
                case 5, 6 -> {
                    in.readLong();
                    i++;
                }
                case 8 -> strings[i] = in.readUnsignedShort();
                case 7, 16, 19, 20 -> in.readUnsignedShort();
                case 9, 10, 11, 12, 17, 18 -> in.readInt();
                case 15 -> {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                }
                default -> throw new IOException("Unknown constant pool tag " + tag);
            }
        }
        in.readUnsignedShort();
        in.readUnsignedShort();
        in.readUnsignedShort();
        int interfaces = in.readUnsignedShort();
        for (int i = 0; i < interfaces; i++) in.readUnsignedShort();
        Map<String, Object> constants = new LinkedHashMap<>();
        int fields = in.readUnsignedShort();
        for (int f = 0; f < fields; f++) {
            in.readUnsignedShort();
            String name = String.valueOf(pool[in.readUnsignedShort()]);
            in.readUnsignedShort();
            int attributes = in.readUnsignedShort();
            for (int a = 0; a < attributes; a++) {
                String attribute = String.valueOf(pool[in.readUnsignedShort()]);
                int length = in.readInt();
                if (attribute.equals("ConstantValue") && length == 2) {
                    int index = in.readUnsignedShort();
                    constants.put(name, strings[index] != 0 ? pool[strings[index]] : pool[index]);
                } else in.skipNBytes(length);
            }
        }
        return constants;
    }

    /**
     * The build plugin and Java toolchain move with the game. 26.1+ needs NeoGradle's 7.1 line and Java 25;
     * leaving either behind fails during configuration, before a single source error is reported.
     */
    static void toolchain(Target.Setup setup, Build build, String to) throws Exception {
        if (Versions.compare(to, "26.1") < 0) return;
        Path groovy = build.project.root.resolve("build.gradle"), kts = build.project.root.resolve("build.gradle.kts");
        Path file = Files.isRegularFile(groovy) ? groovy : Files.isRegularFile(kts) ? kts : null;
        if (file == null) return;
        Matcher java = Pattern.compile("JavaLanguageVersion\\.of\\((\\d+)\\)").matcher(setup.text(file));
        if (java.find()) {
            int want = javaFor(to);
            if (Integer.parseInt(java.group(1)) != want) {
                setup.replace(file, java.group(0), "JavaLanguageVersion.of(" + want + ")");
                setup.applied.add(
                    Json.map(
                        "file",
                        file.toString(),
                        "change",
                        "Java toolchain " + java.group(1) + " -> " + want + " for " + to
                    )
                );
            }
        }
        Path properties = build.project.root.resolve("gradle.properties");
        if (Files.isRegularFile(properties)) {
            for (String key : List.of("java_version", "java_toolchain_version", "java_target", "javaVersion", "java_release", "target_java_version")) {
                Matcher pin = Pattern.compile("(?m)^([ \\t]*" + key + "[ \\t]*=[ \\t]*)(\\d+)[ \\t]*$").matcher(
                    setup.text(properties)
                );
                if (!pin.find()) continue;
                int want = javaFor(to);
                if (Integer.parseInt(pin.group(2)) != want) {
                    setup.replace(properties, pin.group(0), pin.group(1) + want);
                    setup.applied.add(
                        Json.map(
                            "file",
                            properties.toString(),
                            "change",
                            key + " " + pin.group(2) + " -> " + want + " for " + to
                        )
                    );
                }
            }
        }
        // Gradle 9 removed the project-level sourceCompatibility and targetCompatibility; the java extension owns them.
        String compatibility = setup.text(file), javaOwned = compatibility.replaceAll(
            "(?m)^(sourceCompatibility|targetCompatibility)([ \\t]*=)",
            "java.$1$2"
        );
        if (!javaOwned.equals(compatibility)) {
            setup.originals.putIfAbsent(file, compatibility);
            setup.texts.put(file, javaOwned);
            setup.applied.add(
                Json.map("file", file.toString(), "change", "sourceCompatibility/targetCompatibility -> java.* for Gradle 9")
            );
        }
        // Gradle 9 removed the project-level archivesBaseName property; the base extension owns it now.
        Matcher archives = Pattern.compile("(?m)^[ \\t]*archivesBaseName[ \\t]*=[ \\t]*([^\\r\\n]+)$").matcher(
            setup.text(file)
        );
        if (archives.find()) {
            String value = archives.group(1).trim();
            setup.replace(file, archives.group(0), "base {\n\tarchivesName = " + value + "\n}");
            setup.applied.add(
                Json.map("file", file.toString(), "change", "archivesBaseName -> base.archivesName for Gradle 9")
            );
        }
        String archivesText = setup.text(file);
        String bare = archivesText.replaceAll(
            "(?<![\\w.])archivesBaseName(?![\\w(])",
            "project.base.archivesName.get()"
        );
        if (!bare.equals(archivesText)) {
            setup.originals.putIfAbsent(file, archivesText);
            setup.texts.put(file, bare);
            setup.applied.add(
                Json.map(
                    "file",
                    file.toString(),
                    "change",
                    "remaining archivesBaseName reads go through base.archivesName"
                )
            );
        }
        if (archivesText.contains("project.archivesBaseName")) {
            String text = setup.text(file);
            setup.originals.putIfAbsent(file, text);
            setup.texts.put(file, text.replace("project.archivesBaseName", "project.base.archivesName.get()"));
            setup.applied.add(
                Json.map(
                    "file",
                    file.toString(),
                    "change",
                    "publication archivesBaseName read through base.archivesName"
                )
            );
        }
    }

    /** The project's own Gradle stays: NeoGradle 7.1 runs on Gradle 8.14 and 9.4, and the newest Gradle (9.8) breaks it. */
    static int javaFor(String minecraft) {
        if (Versions.compare(minecraft, "26.1") >= 0) return 25;
        if (Versions.compare(minecraft, "1.20.5") >= 0) return 21;
        if (Versions.compare(minecraft, "1.18") >= 0) return 17;
        return Versions.compare(minecraft, "1.17") >= 0 ? 16 : 8;
    }
}

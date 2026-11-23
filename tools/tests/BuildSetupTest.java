import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A mod's own source builds through mmt as its author's checkout stands, on real Gradle: a checkout without its
 * gradle-wrapper.jar (Freecam, GeckoLib), a project selected by its folder's name that settings renamed
 * (Biomes O' Plenty's :neoforge is :NeoForge), a build script that reads a publishing secret while it loads
 * (YUNG's API's `username ossrhToken`), and a NeoForge artifact asked of NeoForged's repository root (Embeddium).
 * Each alone stopped the build before any source compiled.
 */
public final class BuildSetupTest {

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: java BuildSetupTest.java <checkout> <mmt.jar>");
        Path home = Path.of(args[0]).toRealPath(), jar = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) throw new IOException("Missing package: " + jar);
        Path runs = Files.createDirectories(home.resolve(".runs"));
        Path root = Files.createTempDirectory(runs, "build-setup-");
        // The fixture's own Gradle release (already downloaded by the other Gradle checks), but no wrapper jar.
        Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.copy(
            home.resolve("fixtures/prism-lab/gradle/wrapper/gradle-wrapper.properties"),
            root.resolve("gradle/wrapper/gradle-wrapper.properties")
        );
        Files.writeString(
            root.resolve("settings.gradle"),
            "rootProject.name = 'build-setup'\ninclude 'neoforge'\nproject(':neoforge').name = 'NeoForge'\n"
        );
        Files.createDirectories(root.resolve("neoforge/src/main/java/setup"));
        Files.writeString(
            root.resolve("neoforge/build.gradle"),
            """
            plugins { id 'java' }
            // Only the author has it; read while the build loads.
            def publisher = ossrhToken
            // NeoForged's repository root, which once served what it now serves from /releases only.
            repositories { maven { url = 'https://maven.neoforged.net' } }
            dependencies { compileOnly('net.neoforged:mergetool:2.0.0') { transitive = false } }
            """
        );
        Files.writeString(root.resolve("neoforge/src/main/java/setup/Mod.java"), "package setup; class Mod { }\n");

        String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        Path output = Files.createTempFile(runs, "build-setup-", ".log");
        Process process = new ProcessBuilder(
            List.of(java, "-Dmmt.home=" + home, "-jar", jar.toString(), "compile", "--root", root.toString(), "--project", ":neoforge", "--json")
        )
            .directory(home.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
        if (!process.waitFor(Duration.ofMinutes(15).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("mmt compile timed out after 15 minutes; fixture " + root);
        }
        String text = Files.readString(output, StandardCharsets.UTF_8);
        Path built = root.resolve("neoforge/build/classes/java/main/setup/Mod.class");
        if (process.exitValue() != 0 || !Files.isRegularFile(built)) throw new AssertionError(
            "the checkout should build as it stands; got exit " + process.exitValue() + "\n" + text
        );
        if (Files.exists(root.resolve("gradle/wrapper/gradle-wrapper.jar"))) throw new AssertionError(
            "mmt added a wrapper jar to the checkout's own files; it belongs in .mmt/wrapper/"
        );
        System.out.println("PASS a checkout with no wrapper jar, a renamed project, a publishing secret and NeoForged's old repository address builds: " + built);
    }

    static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows");
    }
}

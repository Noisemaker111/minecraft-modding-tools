import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The wall a start-check start runs behind, put up by the operating system: the start, the mods' start code, native
 * code and any program they run can open no connection and show no window. JVM proxy settings are no wall: they stop
 * the classes that read them, and a channel, a datagram or a name lookup goes straight past (measured: four of six
 * ways Java opens a connection got through them).
 * - Windows: an AppContainer with no capability, on a desktop of its own that is never shown (tools/nogame/MmtWall.java,
 *   a launcher run on a signed Java 22 or later). It needs no administrator rights. A start-check that cannot put it
 *   up starts nothing.
 * - Linux: a network namespace with no interface (unshare), where the kernel lets a user make one, and no display. On
 *   a machine that does not, and on macOS, the starts run with the proxy settings alone and the answer says so.
 */
final class StartWall {

    static final String SOURCE = "tools/nogame/MmtWall.java";
    static final String CONTAINER = "mmt-start-check";
    /** The folders of the pack tools' cache a start is made from: the container reads these, the Java it runs on and the start's own folder, and nothing else of the user's. */
    static final List<String> READ = List.of("loaders", "minecraft", "assets", "agents");

    /** before: what goes in front of a start's command. up: whether the operating system refuses the start every connection. says: that, in the answer's words. */
    record Wall(List<String> before, boolean up, String says) {}

    static Wall of(Path harness, Path cache) throws Exception {
        if (Runtimes.WINDOWS) return windows(harness, cache);
        return unix();
    }

    /** A start's command behind the wall, and what the start may read and write there. */
    static List<String> behind(Wall wall, Path harness, Path cache, List<String> command, Path instance) throws Exception {
        List<String> walled = new ArrayList<>(wall.before());
        if (!Runtimes.WINDOWS) {
            walled.addAll(command);
            return walled;
        }
        Path java = Path.of(command.get(0)).toAbsolutePath().getParent().getParent();
        List<String> inside = new ArrayList<>(command);
        inside.addAll(1, paths(harness, cache, java));
        // The command goes in a file, one argument a line: on a second command line Windows' quoting rules would be applied to it twice.
        if (inside.stream().anyMatch(argument -> argument.contains("\n"))) throw new IOException("A start's argument holds a line break, which the wall's launcher cannot be handed: " + command);
        Path file = instance.resolve("mmt-wall-command.txt");
        Files.write(file, inside, StandardCharsets.UTF_8);
        walled.addAll(List.of("--read", java.toString()));
        for (String folder : READ) if (Files.isDirectory(cache.resolve(folder))) walled.addAll(List.of("--read", cache.resolve(folder).toString()));
        walled.addAll(List.of("--write", instance.toString(), "--command", file.toString()));
        return walled;
    }

    static final List<String> PATHS = List.of("tools/nogame/MmtPathPatch.java", "tools/nogame/sun/nio/fs/MmtListed.java");

    /**
     * The options that give one Java its real-path check behind the wall (tools/nogame/sun/nio/fs/MmtListed.java says
     * why): that Java's own WindowsLinkSupport with one call moved, and the class it is moved to, put into java.base.
     * Built once per Java and content of the two sources, by that Java's own compiler: the class it changes is its own.
     */
    static List<String> paths(Path harness, Path cache, Path java) throws Exception {
        String release = Files.isRegularFile(java.resolve("release")) ? Files.readString(java.resolve("release")) : java.toString();
        Matcher version = Pattern.compile("JAVA_VERSION=\"(1\\.)?(\\d+)").matcher(release);
        int feature = version.find() ? Integer.parseInt(version.group(2)) : 17;
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        for (String source : PATHS) hash.update(Files.readAllBytes(harness.resolve(source)));
        hash.update((java + "\n" + release).getBytes(StandardCharsets.UTF_8));
        Path folder = cache.resolve("agents").resolve("wall-paths-" + HexFormat.of().formatHex(hash.digest()).substring(0, 16)), jar = folder.resolve("mmt-paths.jar");
        if (!Files.isRegularFile(jar)) {
            Path javac = java.resolve("bin/javac.exe");
            if (!Files.isRegularFile(javac)) throw new IOException("The Java a walled start runs on must be a JDK: " + java + " has no compiler, and its real-path check is patched with it.");
            Files.createDirectories(folder);
            Path work = Files.createTempDirectory(folder, "classes-");
            try {
                // Made first: Java 8's compiler does not make the folder it is told to write to.
                Path tool = Files.createDirectories(work.resolve("tool")), classes = Files.createDirectories(work.resolve("classes"));
                said(javac.toString(), "-nowarn", "-encoding", "UTF-8", "-d", tool.toString(), harness.resolve(PATHS.get(0)).toString());
                said(java.resolve("bin/java.exe").toString(), "-cp", tool.toString(), "MmtPathPatch", classes.toString());
                // Java 8 has no modules: its compiler is told to read the runtime's own classes, hidden ones too.
                List<String> compile = new ArrayList<>(List.of(javac.toString(), "-nowarn", "-encoding", "UTF-8", "-d", classes.toString()));
                compile.addAll(feature > 8 ? List.of("--patch-module", "java.base=" + harness.resolve("tools/nogame")) : List.of("-XDignore.symbol.file"));
                compile.add(harness.resolve(PATHS.get(1)).toString());
                said(compile.toArray(String[]::new));
                Path built = work.resolve("mmt-paths.jar");
                StartCheck.jarOf(classes, built, null);
                try {
                    Files.move(built, jar, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException there) {
                    if (!Files.isRegularFile(jar)) throw there;
                }
            } finally {
                PackCheck.deleteTree(work);
            }
        }
        return feature > 8 ? List.of("--patch-module", "java.base=" + jar) : List.of("-Xbootclasspath/p:" + jar);
    }

    /** What a walled start's process is told of its surroundings: a temporary folder inside its own (the container cannot write the user's), and no display to open a window on. */
    static void surroundings(Wall wall, ProcessBuilder start, Path instance) throws IOException {
        if (Runtimes.WINDOWS) {
            Path temporary = Files.createDirectories(instance.resolve("tmp"));
            start.environment().put("TEMP", temporary.toString());
            start.environment().put("TMP", temporary.toString());
        } else {
            start.environment().remove("DISPLAY");
            start.environment().remove("WAYLAND_DISPLAY");
        }
    }

    /** The Java the wall's launcher runs on: its calls into Windows need Java 22 or later. */
    static Runtimes.Jdk modern(Path harness) throws IOException {
        return Runtimes.ensureAtLeast(harness, 22);
    }

    static Wall windows(Path harness, Path cache) throws Exception {
        byte[] source = Files.readAllBytes(harness.resolve(SOURCE));
        String content = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source)).substring(0, 16);
        Path folder = cache.resolve("agents").resolve("wall-" + content), launcher = folder.resolve("mmt-wall.jar");
        Runtimes.Jdk java = modern(harness);
        if (!Files.isRegularFile(launcher)) {
            Files.createDirectories(folder);
            // Built beside its place and moved there: start-checks run side by side on one machine.
            Path work = Files.createTempDirectory(folder, "classes-");
            try {
                Process compile = new ProcessBuilder(java.home().resolve("bin/javac.exe").toString(), "--release", "22", "-nowarn", "-encoding", "UTF-8", "-d", work.resolve("classes").toString(),
                    harness.resolve(SOURCE).toString()).redirectErrorStream(true).start();
                String said = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!compile.waitFor(120, TimeUnit.SECONDS) || compile.exitValue() != 0) throw new IOException("The wall's launcher did not compile (" + SOURCE + "): " + said.strip());
                Path built = work.resolve("mmt-wall.jar");
                StartCheck.jarOf(work.resolve("classes"), built, null);
                try {
                    Files.move(built, launcher, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException there) {
                    if (!Files.isRegularFile(launcher)) throw there;
                }
            } finally {
                PackCheck.deleteTree(work);
            }
        }
        // The quick compiler only: the launcher makes a few calls and waits.
        return new Wall(List.of(java.java().toString(), "--enable-native-access=ALL-UNNAMED", "-XX:TieredStopAtLevel=1", "-Xmx64m", "-cp", launcher.toString(), "MmtWall", "--name", CONTAINER), true,
            "every start ran in a Windows AppContainer with no capability, on a desktop of its own: Windows refused it every connection (the internet, the local network, other programs here, name lookups) and showed no window of it");
    }

    static Wall unix() {
        String why;
        try {
            List<String> unshare = List.of("unshare", "--user", "--map-user=" + said("id", "-u"), "--map-group=" + said("id", "-g"), "--net", "--");
            List<String> probe = new ArrayList<>(unshare);
            probe.add("true");
            Process tried = new ProcessBuilder(probe).redirectErrorStream(true).start();
            String answer = new String(tried.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (tried.waitFor(30, TimeUnit.SECONDS) && tried.exitValue() == 0) return new Wall(unshare, true,
                "every start ran in a network namespace of its own with no interface (unshare) and with no display: the kernel gave it no address to reach and nowhere to show a window");
            why = answer.isEmpty() ? "unshare ended with " + tried.exitValue() : answer.lines().findFirst().orElse("");
        } catch (IOException | InterruptedException missing) {
            why = String.valueOf(missing.getMessage());
        }
        return new Wall(List.of(), false, "NOT walled off: this machine makes no network namespace for a user (" + why
            + "), so the starts ran with every Java proxy and Mojang address pointed at a closed port, which a mod's own socket or name lookup goes past; no display was given");
    }

    static String said(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IOException(String.join(" ", command) + ": " + out);
        return out;
    }
}

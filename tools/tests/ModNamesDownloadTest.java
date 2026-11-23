import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reading a release's intermediary names (tools/port/names/VersionMap.java) says "no published intermediary" only when
 * Fabric's maven answered 404. Four cloud jobs died on a reset connection while the message said the release had no
 * intermediary, which sent the reader after the wrong cause. Here one local server answers 404 and another resets every
 * connection; Mojang's version list and the release's version.json are planted, so nothing leaves this machine.
 */
public final class ModNamesDownloadTest {

    static int failures;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + what);
        failures += ok ? 0 : 1;
    }

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args.length > 0 ? args[0] : System.getProperty("java.io.tmpdir")).resolve("download-test"));
        VersionMap.cache = Files.createTempDirectory(scratch, "mappings");
        Files.writeString(VersionMap.cache.resolve("version_manifest_v2.json"),
            "{\"versions\": [{\"id\": \"1.18.2\", \"url\": \"http://127.0.0.1:9/1.18.2.json\"}]}", StandardCharsets.UTF_8);
        Files.writeString(VersionMap.cache.resolve("version-1.18.2.json"), "{}", StandardCharsets.UTF_8);

        try (ServerSocket missing = answering("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
             ServerSocket reset = answering(null)) {
            VersionMap.fabricMaven = "http://127.0.0.1:" + missing.getLocalPort() + "/";
            String notThere = failure();
            check(notThere.contains("has no published intermediary") && notThere.contains("HTTP 404"),
                "a 404 from Fabric's maven says the release has no published intermediary: " + notThere);

            VersionMap.fabricMaven = "http://127.0.0.1:" + reset.getLocalPort() + "/";
            String cut = failure();
            check(cut.contains("network failure") && cut.contains("intermediary-1.18.2-v2.jar") && !cut.contains("no published"),
                "a reset connection is called a network failure, with its address, not a missing intermediary: " + cut);
        }
        check(!Files.exists(VersionMap.cache.resolve("intermediary-1.18.2-v2.jar")), "a failed download leaves no file behind");
        if (failures > 0) {
            System.exit(1);
        }
        System.out.println("ModNamesDownload: every check passed");
    }

    static String failure() {
        try {
            VersionMap.Table.load("1.18.2", "mojmap");
            return "no error";
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    /** A server on this machine that sends one answer to every request, or with no answer resets each connection. */
    static ServerSocket answering(String answer) throws IOException {
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        Thread serve = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    s.getInputStream().read(new byte[8192]);
                    if (answer == null) {
                        s.setSoLinger(true, 0); // closing now sends a reset, as Fabric's maven did
                    } else {
                        s.getOutputStream().write(answer.getBytes(StandardCharsets.US_ASCII));
                        s.getOutputStream().flush();
                    }
                } catch (IOException closed) {
                    return;
                }
            }
        });
        serve.setDaemon(true);
        serve.start();
        return server;
    }
}

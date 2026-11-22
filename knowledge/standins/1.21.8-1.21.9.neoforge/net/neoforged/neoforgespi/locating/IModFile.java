package mmt.standin.net.neoforged.neoforgespi.locating;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NeoForge 21.8's IModFile (FancyModLoader 9.0.18) on 21.9 (21.9.16-beta, FancyModLoader 10.0.14).
 *
 * Read from both loaders' files: 9.0.18's ModFile.findResource(parts...) refused no parts and otherwise gave
 * getSecureJar().getPath(parts joined by "/"), a path inside the mod file's own file system whether or not anything is
 * there. 10.0.14 dropped findResource and getSecureJar; a mod file's contents are a JarContents, which offers URIs and
 * streams but no paths, and getFilePath() is still the mod's jar (or folder). So the path is the same name inside the
 * jar, read through a zip file system opened on the jar once, or inside the folder.
 *
 * Loader files only: NeoForge's game primer covers vanilla classes, not FancyModLoader.
 */
public final class IModFile {

    private static final Map<Path, FileSystem> OPENED = new ConcurrentHashMap<>();

    private IModFile() {}

    public static Path findResource(net.neoforged.neoforgespi.locating.IModFile self, String[] path) {
        if (path.length < 1) {
            throw new IllegalArgumentException("Missing path");
        }
        String joined = String.join("/", path);
        Path file = self.getFilePath();
        if (Files.isDirectory(file)) {
            return file.resolve(joined);
        }
        FileSystem jar = OPENED.computeIfAbsent(file, f -> {
            try {
                return FileSystems.newFileSystem(f);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return jar.getPath("/").resolve(joined);
    }
}

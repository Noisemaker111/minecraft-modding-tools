package mmt.standin.java.nio.file;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.ProviderNotFoundException;
import java.nio.file.spi.FileSystemProvider;
import java.util.Collections;
import java.util.Map;

/**
 * Java 13's FileSystems.newFileSystem(Path) and newFileSystem(Path, Map env), from the JDK's documented contract: "the
 * installed providers are examined" and the first that does not refuse the file (UnsupportedOperationException) opens it
 * as a file system, else ProviderNotFoundException. That is Java 8's newFileSystem(Path, ClassLoader) with no class
 * loader, which passes no environment.
 */
public final class FileSystems {

    private FileSystems() {}

    public static FileSystem newFileSystem(Path path) throws IOException {
        return newFileSystem(path, Collections.<String, Object>emptyMap());
    }

    public static FileSystem newFileSystem(Path path, Map<String, ?> env) throws IOException {
        if (path == null) {
            throw new NullPointerException();
        }
        for (FileSystemProvider provider : FileSystemProvider.installedProviders()) {
            try {
                return provider.newFileSystem(path, env);
            } catch (UnsupportedOperationException refused) {
                // the next provider may read it
            }
        }
        throw new ProviderNotFoundException("Provider not found");
    }
}

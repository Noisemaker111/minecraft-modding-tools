package mmt.standin.java.nio.file;

import java.net.URI;
import java.nio.file.Paths;

/** Java 11's Path.of(first, more...) and Path.of(uri), which the JDK specifies as what Paths.get gives for the same arguments. */
public final class Path {

    private Path() {}

    public static java.nio.file.Path of(String first, String... more) {
        return Paths.get(first, more);
    }

    public static java.nio.file.Path of(URI uri) {
        return Paths.get(uri);
    }
}

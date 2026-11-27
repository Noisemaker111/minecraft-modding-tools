package mmt.standin.java.lang;

import java.io.File;

/**
 * java.lang.ProcessBuilder.Redirect members Java 8 lacks, from the JDK's documented contract: DISCARD (Java 9) "indicates
 * that subprocess output will be discarded ... It will always be true that DISCARD.file() is the File of an operating
 * system specific 'null file'", type WRITE, so writing to it as Redirect.to(that file) does, never appending. One value,
 * as the constant is one.
 */
public final class ProcessBuilder$Redirect {

    private ProcessBuilder$Redirect() {}

    static final java.lang.ProcessBuilder.Redirect DISCARD = java.lang.ProcessBuilder.Redirect.to(
        new File(System.getProperty("os.name", "").startsWith("Windows") ? "NUL" : "/dev/null"));

    public static java.lang.ProcessBuilder.Redirect DISCARD() {
        return DISCARD;
    }
}

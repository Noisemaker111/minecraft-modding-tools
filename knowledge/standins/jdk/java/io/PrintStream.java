package mmt.standin.java.io;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

/**
 * Java 10's PrintStream constructors that take a Charset, from the JDK's documented contract: PrintStream(OutputStream,
 * boolean autoFlush, Charset), PrintStream(File, Charset) and PrintStream(String fileName, Charset), each the stream Java
 * 8 makes with that charset's name, which every Java 8 JVM reads back as the same charset.
 */
public final class PrintStream {

    private PrintStream() {}

    public static java.io.PrintStream init(OutputStream out, boolean autoFlush, Charset charset) {
        try {
            return new java.io.PrintStream(out, autoFlush, charset.name());
        } catch (UnsupportedEncodingException named) {
            throw new IllegalStateException("charset " + charset + " not found by its own name", named);
        }
    }

    public static java.io.PrintStream init(File file, Charset charset) throws IOException {
        return new java.io.PrintStream(file, charset.name());
    }

    public static java.io.PrintStream init(java.lang.String fileName, Charset charset) throws IOException {
        return new java.io.PrintStream(fileName, charset.name());
    }
}

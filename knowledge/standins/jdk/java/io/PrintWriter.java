package mmt.standin.java.io;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;

/**
 * Java 10's PrintWriter constructors that take a Charset, from the JDK's documented contract: PrintWriter(File, Charset)
 * and PrintWriter(String fileName, Charset) "a new PrintWriter, without automatic line flushing" writing the file encoded
 * with the charset (truncated or created), and PrintWriter(OutputStream, boolean autoFlush, Charset) one writing the
 * stream encoded with the charset. Each is the PrintWriter Java 8 makes over a BufferedWriter of an OutputStreamWriter in
 * that charset, which is how the JDK writes them.
 */
public final class PrintWriter {

    private PrintWriter() {}

    public static java.io.PrintWriter init(File file, Charset charset) throws IOException {
        return new java.io.PrintWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), charset)), false);
    }

    public static java.io.PrintWriter init(java.lang.String fileName, Charset charset) throws IOException {
        return new java.io.PrintWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(fileName), charset)), false);
    }

    public static java.io.PrintWriter init(OutputStream out, boolean autoFlush, Charset charset) {
        return new java.io.PrintWriter(new BufferedWriter(new OutputStreamWriter(out, charset)), autoFlush);
    }
}

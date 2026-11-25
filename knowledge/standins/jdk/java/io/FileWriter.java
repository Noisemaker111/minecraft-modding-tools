package mmt.standin.java.io;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;

/**
 * Java 11's FileWriter(File, Charset[, boolean append]) and FileWriter(String, Charset[, boolean append]): "a FileWriter
 * given the File to write, charset and a boolean indicating whether to append the data written", the characters encoded
 * with that charset. Java 8's FileWriter encodes only with the platform's charset, so the writer made is a FileWriter
 * whose every write goes to an OutputStreamWriter of the file in the given charset; closing it closes the file.
 */
public final class FileWriter {

    private FileWriter() {}

    public static java.io.FileWriter init(File file, Charset charset) throws IOException {
        return new Encoding(new FileOutputStream(file), charset);
    }

    public static java.io.FileWriter init(File file, Charset charset, boolean append) throws IOException {
        return new Encoding(new FileOutputStream(file, append), charset);
    }

    public static java.io.FileWriter init(String fileName, Charset charset) throws IOException {
        return new Encoding(new FileOutputStream(fileName), charset);
    }

    public static java.io.FileWriter init(String fileName, Charset charset, boolean append) throws IOException {
        return new Encoding(new FileOutputStream(fileName, append), charset);
    }

    static FileDescriptor fd(FileOutputStream out) throws IOException {
        return out.getFD();
    }

    static final class Encoding extends java.io.FileWriter {

        private final OutputStreamWriter out;

        Encoding(FileOutputStream file, Charset charset) throws IOException {
            super(fd(file));
            this.out = new OutputStreamWriter(file, charset);
        }

        @Override
        public void write(int c) throws IOException {
            out.write(c);
        }

        @Override
        public void write(char[] buf, int off, int len) throws IOException {
            out.write(buf, off, len);
        }

        @Override
        public void write(String str, int off, int len) throws IOException {
            out.write(str, off, len);
        }

        // Java 17's OutputStreamWriter appends straight to its own encoder, which writes the platform's charset into a
        // buffer nothing here flushes; every append goes to the writer of the charset asked for instead.
        @Override
        public java.io.Writer append(CharSequence csq) throws IOException {
            out.append(csq);
            return this;
        }

        @Override
        public java.io.Writer append(CharSequence csq, int start, int end) throws IOException {
            out.append(csq, start, end);
            return this;
        }

        @Override
        public java.io.Writer append(char c) throws IOException {
            out.append(c);
            return this;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public String getEncoding() {
            return out.getEncoding();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }
}

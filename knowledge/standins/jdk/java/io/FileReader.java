package mmt.standin.java.io;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/**
 * Java 11's FileReader(File, Charset) and FileReader(String, Charset): "a new FileReader, given the File to read and the
 * charset", reading the file's bytes decoded with that charset. Java 8's FileReader decodes only with the platform's
 * charset, so the reader made is a FileReader whose every read goes to an InputStreamReader of the file in the given
 * charset; closing it closes the file.
 */
public final class FileReader {

    private FileReader() {}

    public static java.io.FileReader init(File file, Charset charset) throws IOException {
        return new Decoding(new FileInputStream(file), charset);
    }

    public static java.io.FileReader init(String fileName, Charset charset) throws IOException {
        return new Decoding(new FileInputStream(fileName), charset);
    }

    static FileDescriptor fd(FileInputStream in) throws IOException {
        return in.getFD();
    }

    static final class Decoding extends java.io.FileReader {

        private final InputStreamReader in;

        Decoding(FileInputStream file, Charset charset) throws IOException {
            super(fd(file));
            this.in = new InputStreamReader(file, charset);
        }

        @Override
        public int read() throws IOException {
            return in.read();
        }

        @Override
        public int read(char[] buf, int off, int len) throws IOException {
            return in.read(buf, off, len);
        }

        @Override
        public boolean ready() throws IOException {
            return in.ready();
        }

        @Override
        public String getEncoding() {
            return in.getEncoding();
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}

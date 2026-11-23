package mmt.standin.java.io;

import java.io.IOException;
import java.util.Objects;

/**
 * Java 11's OutputStream.nullOutputStream(): "a new OutputStream which discards all bytes"; once closed, writing throws
 * IOException ("Stream closed"), and the bounds of write(b, off, len) are checked as before.
 */
public final class OutputStream {

    private OutputStream() {}

    public static java.io.OutputStream nullOutputStream() {
        return new java.io.OutputStream() {
            private volatile boolean closed;

            private void open() throws IOException {
                if (closed) {
                    throw new IOException("Stream closed");
                }
            }

            @Override
            public void write(int b) throws IOException {
                open();
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                Objects.requireNonNull(b);
                if (off < 0 || len < 0 || len > b.length - off) {
                    throw new IndexOutOfBoundsException();
                }
                open();
            }

            @Override
            public void close() {
                closed = true;
            }
        };
    }
}

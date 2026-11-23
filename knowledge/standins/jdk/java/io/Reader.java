package mmt.standin.java.io;

import java.io.IOException;

/** Java 11's Reader.nullReader(): "a new Reader that reads no characters", at its end from the start; closed, reading throws IOException ("Stream closed"). */
public final class Reader {

    private Reader() {}

    public static java.io.Reader nullReader() {
        return new java.io.Reader() {
            private volatile boolean closed;

            @Override
            public int read(char[] cbuf, int off, int len) throws IOException {
                if (closed) {
                    throw new IOException("Stream closed");
                }
                if (off < 0 || len < 0 || len > cbuf.length - off) {
                    throw new IndexOutOfBoundsException();
                }
                return len == 0 ? 0 : -1;
            }

            @Override
            public void close() {
                closed = true;
            }
        };
    }
}

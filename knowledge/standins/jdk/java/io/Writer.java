package mmt.standin.java.io;

import java.io.IOException;

/** Java 11's Writer.nullWriter(): "a new Writer which discards all characters"; closed, writing throws IOException ("Stream closed"). */
public final class Writer {

    private Writer() {}

    public static java.io.Writer nullWriter() {
        return new java.io.Writer() {
            private volatile boolean closed;

            @Override
            public void write(char[] cbuf, int off, int len) throws IOException {
                if (off < 0 || len < 0 || len > cbuf.length - off) {
                    throw new IndexOutOfBoundsException();
                }
                if (closed) {
                    throw new IOException("Stream closed");
                }
            }

            @Override
            public void flush() throws IOException {
                if (closed) {
                    throw new IOException("Stream closed");
                }
            }

            @Override
            public void close() {
                closed = true;
            }
        };
    }
}

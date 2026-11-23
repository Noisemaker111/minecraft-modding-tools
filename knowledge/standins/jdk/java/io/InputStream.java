package mmt.standin.java.io;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * java.io.InputStream members Java 8 lacks, as their default methods are specified: readAllBytes() (Java 9) "reads all
 * remaining bytes"; readNBytes(len) (Java 11) up to len bytes, fewer at the end of the stream, IllegalArgumentException
 * for a negative len ("len < 0"); transferTo(out) (Java 9) every byte read written to out, returning how many;
 * skipNBytes(n) (Java 12) skips exactly n bytes, EOFException when the stream ends first.
 */
public final class InputStream {

    private InputStream() {}

    public static byte[] readAllBytes(java.io.InputStream self) throws IOException {
        return readNBytes(self, Integer.MAX_VALUE);
    }

    public static byte[] readNBytes(java.io.InputStream self, int len) throws IOException {
        if (len < 0) {
            throw new IllegalArgumentException("len < 0");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int left = len;
        while (left > 0) {
            int n = self.read(buffer, 0, java.lang.Math.min(buffer.length, left));
            if (n < 0) {
                break;
            }
            out.write(buffer, 0, n);
            left -= n;
        }
        return out.toByteArray();
    }

    public static long transferTo(java.io.InputStream self, OutputStream out) throws IOException {
        Objects.requireNonNull(out, "out");
        long transferred = 0;
        byte[] buffer = new byte[8192];
        int read;
        while ((read = self.read(buffer, 0, 8192)) >= 0) {
            out.write(buffer, 0, read);
            transferred += read;
        }
        return transferred;
    }

    public static void skipNBytes(java.io.InputStream self, long n) throws IOException {
        while (n > 0) {
            long ns = self.skip(n);
            if (ns > 0 && ns <= n) {
                n -= ns;
            } else if (ns == 0) {
                if (self.read() == -1) {
                    throw new EOFException();
                }
                n--;
            } else {
                throw new IOException("Unable to skip exactly");
            }
        }
    }
}

package mmt.standin.java.io;

import java.nio.charset.Charset;

/** Java 10's ByteArrayOutputStream.toString(charset): "the buffer's contents into a string by decoding the bytes using the specified charset", as new String(toByteArray(), charset). */
public final class ByteArrayOutputStream {

    private ByteArrayOutputStream() {}

    public static String toString(java.io.ByteArrayOutputStream self, Charset charset) {
        return new String(self.toByteArray(), charset);
    }
}

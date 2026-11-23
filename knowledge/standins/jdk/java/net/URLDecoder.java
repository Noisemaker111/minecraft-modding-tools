package mmt.standin.java.net;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

/** Java 10's URLDecoder.decode(s, charset), which decodes as decode(s, charset.name()) does, the charset known to exist. */
public final class URLDecoder {

    private URLDecoder() {}

    public static String decode(String s, Charset charset) {
        try {
            return java.net.URLDecoder.decode(s, charset.name());
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

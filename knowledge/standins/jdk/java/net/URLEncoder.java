package mmt.standin.java.net;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

/** Java 10's URLEncoder.encode(s, charset), which encodes as encode(s, charset.name()) does, the charset known to exist. */
public final class URLEncoder {

    private URLEncoder() {}

    public static String encode(String s, Charset charset) {
        try {
            return java.net.URLEncoder.encode(s, charset.name());
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

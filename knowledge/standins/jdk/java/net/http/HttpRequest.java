package mmt.standin.java.net.http;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Java 11's java.net.http.HttpRequest, for a release on Java 8, from the JDK's documented contract: a request is built
 * with a Builder ("the default request method is GET"), which takes the URI, headers (header() adds, setHeader()
 * replaces), a timeout, a preferred version and the method with its BodyPublisher; build() "returns a new HttpRequest"
 * and throws IllegalStateException when no URI was set. BodyPublishers make the body from a String ("converted using
 * the given character set", UTF-8 when none is named), a byte array or nothing (noBody()).
 */
public abstract class HttpRequest {

    public interface BodyPublisher {
        long contentLength();
    }

    public static class BodyPublishers {
        private BodyPublishers() {}

        public static BodyPublisher ofString(String s) {
            return ofString(s, StandardCharsets.UTF_8);
        }

        public static BodyPublisher ofString(String s, Charset charset) {
            return new Bytes(s.getBytes(charset));
        }

        public static BodyPublisher ofByteArray(byte[] buf) {
            return new Bytes(buf.clone());
        }

        public static BodyPublisher ofByteArray(byte[] buf, int offset, int length) {
            byte[] part = new byte[length];
            System.arraycopy(buf, offset, part, 0, length);
            return new Bytes(part);
        }

        public static BodyPublisher noBody() {
            return new Bytes(new byte[0]);
        }
    }

    static final class Bytes implements BodyPublisher {
        final byte[] bytes;

        Bytes(byte[] bytes) {
            this.bytes = bytes;
        }

        public long contentLength() {
            return bytes.length;
        }
    }

    public interface Builder {
        Builder uri(URI uri);

        Builder expectContinue(boolean enable);

        Builder version(HttpClient.Version version);

        Builder header(String name, String value);

        Builder headers(String... headers);

        Builder timeout(Duration duration);

        Builder setHeader(String name, String value);

        Builder GET();

        Builder POST(BodyPublisher body);

        Builder PUT(BodyPublisher body);

        Builder DELETE();

        Builder HEAD();

        Builder method(String method, BodyPublisher body);

        HttpRequest build();

        Builder copy();
    }

    static final class Making implements Builder {
        URI uri;
        String method = "GET";
        byte[] body;
        Duration timeout;
        HttpClient.Version version;
        boolean expectContinue;
        final Map<String, List<String>> headers = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);

        public Builder uri(URI u) {
            if (u == null) {
                throw new NullPointerException("uri");
            }
            String scheme = u.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("invalid URI scheme " + scheme);
            }
            uri = u;
            return this;
        }

        public Builder expectContinue(boolean enable) {
            expectContinue = enable;
            return this;
        }

        public Builder version(HttpClient.Version v) {
            if (v == null) {
                throw new NullPointerException("version");
            }
            version = v;
            return this;
        }

        public Builder header(String name, String value) {
            List<String> values = headers.get(name);
            if (values == null) {
                values = new ArrayList<String>();
                headers.put(name, values);
            }
            values.add(value);
            return this;
        }

        public Builder headers(String... pairs) {
            if (pairs.length == 0 || pairs.length % 2 != 0) {
                throw new IllegalArgumentException("wrong number, " + pairs.length + ", of parameters");
            }
            for (int i = 0; i < pairs.length; i += 2) {
                header(pairs[i], pairs[i + 1]);
            }
            return this;
        }

        public Builder timeout(Duration d) {
            if (d.isNegative() || d.isZero()) {
                throw new IllegalArgumentException("Invalid duration: " + d);
            }
            timeout = d;
            return this;
        }

        public Builder setHeader(String name, String value) {
            headers.remove(name);
            return header(name, value);
        }

        public Builder GET() {
            return method("GET", BodyPublishers.noBody());
        }

        public Builder POST(BodyPublisher b) {
            return method("POST", b);
        }

        public Builder PUT(BodyPublisher b) {
            return method("PUT", b);
        }

        public Builder DELETE() {
            return method("DELETE", BodyPublishers.noBody());
        }

        public Builder HEAD() {
            return method("HEAD", BodyPublishers.noBody());
        }

        public Builder method(String m, BodyPublisher b) {
            if (m.isEmpty()) {
                throw new IllegalArgumentException("illegal method <empty string>");
            }
            if (!(b instanceof Bytes)) {
                throw new IllegalArgumentException("a BodyPublisher of the mod's own needs Java 9's Flow, which this Java lacks");
            }
            method = m;
            body = ((Bytes) b).bytes;
            return this;
        }

        public HttpRequest build() {
            if (uri == null) {
                throw new IllegalStateException("uri is null");
            }
            return new Made(this);
        }

        public Builder copy() {
            Making m = new Making();
            m.uri = uri;
            m.method = method;
            m.body = body;
            m.timeout = timeout;
            m.version = version;
            m.expectContinue = expectContinue;
            for (Map.Entry<String, List<String>> h : headers.entrySet()) {
                m.headers.put(h.getKey(), new ArrayList<String>(h.getValue()));
            }
            return m;
        }
    }

    static final class Made extends HttpRequest {
        Made(Making m) {
            super(m.uri, m.method, m.body, m.timeout, m.version, m.expectContinue, new HttpHeaders(m.headers));
        }

        Made(URI uri, String method, byte[] body, Duration timeout, HttpClient.Version version, boolean expectContinue, HttpHeaders headers) {
            super(uri, method, body, timeout, version, expectContinue, headers);
        }
    }

    private final URI uri;
    private final String method;
    final byte[] body;
    private final Duration timeout;
    private final HttpClient.Version version;
    private final boolean expectContinue;
    private final HttpHeaders headers;

    protected HttpRequest() {
        this(null, "GET", null, null, null, false, new HttpHeaders(new TreeMap<String, List<String>>()));
    }

    HttpRequest(URI uri, String method, byte[] body, Duration timeout, HttpClient.Version version, boolean expectContinue, HttpHeaders headers) {
        this.uri = uri;
        this.method = method;
        this.body = body;
        this.timeout = timeout;
        this.version = version;
        this.expectContinue = expectContinue;
        this.headers = headers;
    }

    public static Builder newBuilder() {
        return new Making();
    }

    public static Builder newBuilder(URI uri) {
        return new Making().uri(uri);
    }

    public URI uri() {
        return uri;
    }

    public String method() {
        return method;
    }

    public Optional<Duration> timeout() {
        return Optional.ofNullable(timeout);
    }

    public Optional<HttpClient.Version> version() {
        return Optional.ofNullable(version);
    }

    public boolean expectContinue() {
        return expectContinue;
    }

    public HttpHeaders headers() {
        return headers;
    }

    public Optional<BodyPublisher> bodyPublisher() {
        return body == null ? Optional.<BodyPublisher>empty() : Optional.<BodyPublisher>of(new Bytes(body));
    }

    /**
     * The request a redirect makes: the body kept only for the same method, and to another origin (scheme, host or port)
     * without the headers the JDK keeps for the first (Authorization, Cookie, Origin, Referer, Host), so a server's
     * credentials are never handed to the one it sends the client to.
     */
    HttpRequest redirected(URI to, String newMethod, boolean sameOrigin) {
        HttpHeaders kept = headers;
        if (!sameOrigin) {
            Map<String, List<String>> left = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
            left.putAll(headers.map());
            for (String name : new String[] { "Authorization", "Cookie", "Origin", "Referer", "Host" }) {
                left.remove(name);
            }
            kept = new HttpHeaders(left);
        }
        return new Made(to, newMethod, newMethod.equals(method) ? body : null, timeout, version, expectContinue, kept);
    }

    @Override
    public String toString() {
        return uri + " " + method;
    }
}

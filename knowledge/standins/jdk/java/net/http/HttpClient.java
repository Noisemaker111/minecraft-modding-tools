package mmt.standin.java.net.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Java 11's java.net.http.HttpClient, for a release on Java 8, over java.net.HttpURLConnection, from the JDK's documented
 * contract: "an HttpClient can be used to send requests and retrieve their responses"; newHttpClient() is
 * newBuilder().build() with "default settings": redirects NEVER, HTTP_2 preferred, the default executor. send() "sends
 * the given request ... blocking if necessary to get the response", and the response's body is what the BodyHandler
 * makes of it; a status code is a response, never an exception. sendAsync() does the same on the client's executor.
 * Redirect: NEVER "never redirect", ALWAYS "always redirect", NORMAL "always redirect, except from HTTPS URLs to HTTP
 * URLs". Version is a preference: HttpURLConnection speaks HTTP/1.1, which the contract allows ("if the server does
 * not support HTTP/2, then HTTP/1.1 may be used"). A connect timeout ends in HttpConnectTimeoutException, a request
 * timeout in HttpTimeoutException. WebSocket is not written here: a mod using it keeps it as a named break.
 */
public class HttpClient implements AutoCloseable {

    public enum Redirect {
        NEVER, ALWAYS, NORMAL
    }

    public enum Version {
        HTTP_1_1, HTTP_2
    }

    public interface Builder {
        Builder connectTimeout(Duration duration);

        Builder followRedirects(Redirect policy);

        Builder version(Version version);

        Builder executor(Executor executor);

        Builder priority(int priority);

        HttpClient build();
    }

    static final class Settings implements Builder {
        Duration connectTimeout;
        Redirect redirect = Redirect.NEVER;
        Version version = Version.HTTP_2;
        Executor executor;

        public Builder connectTimeout(Duration duration) {
            if (duration == null) {
                throw new NullPointerException("duration");
            }
            if (duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException("Invalid duration: " + duration);
            }
            connectTimeout = duration;
            return this;
        }

        public Builder followRedirects(Redirect policy) {
            if (policy == null) {
                throw new NullPointerException("policy");
            }
            redirect = policy;
            return this;
        }

        public Builder version(Version v) {
            if (v == null) {
                throw new NullPointerException("version");
            }
            version = v;
            return this;
        }

        public Builder executor(Executor e) {
            if (e == null) {
                throw new NullPointerException("executor");
            }
            executor = e;
            return this;
        }

        public Builder priority(int priority) {
            if (priority < 1 || priority > 256) {
                throw new IllegalArgumentException("priority must be between 1 and 256");
            }
            return this;
        }

        public HttpClient build() {
            return new HttpClient(this);
        }
    }

    private final Duration connectTimeout;
    private final Redirect redirect;
    private final Version version;
    private final Executor executor;

    protected HttpClient() {
        this(new Settings());
    }

    HttpClient(Settings s) {
        connectTimeout = s.connectTimeout;
        redirect = s.redirect;
        version = s.version;
        executor = s.executor;
    }

    public static HttpClient newHttpClient() {
        return newBuilder().build();
    }

    public static Builder newBuilder() {
        return new Settings();
    }

    public java.util.Optional<Duration> connectTimeout() {
        return java.util.Optional.ofNullable(connectTimeout);
    }

    public Redirect followRedirects() {
        return redirect;
    }

    public Version version() {
        return version;
    }

    public java.util.Optional<Executor> executor() {
        return java.util.Optional.ofNullable(executor);
    }

    public void close() {}

    public void shutdown() {}

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
        final CompletableFuture<HttpResponse<T>> out = new CompletableFuture<HttpResponse<T>>();
        Runnable send = new Runnable() {
            public void run() {
                try {
                    out.complete(send(request, handler));
                } catch (Throwable t) {
                    out.completeExceptionally(t);
                }
            }
        };
        (executor != null ? executor : ForkJoinPool.commonPool()).execute(send);
        return out;
    }

    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        if (request == null || handler == null) {
            throw new NullPointerException();
        }
        URI uri = request.uri();
        for (int hops = 0; ; hops++) {
            HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod(request.method());
            if (connectTimeout != null) {
                c.setConnectTimeout(millis(connectTimeout));
            }
            if (request.timeout().isPresent()) {
                c.setReadTimeout(millis(request.timeout().get()));
            }
            for (Map.Entry<String, List<String>> h : request.headers().map().entrySet()) {
                for (String v : h.getValue()) {
                    c.addRequestProperty(h.getKey(), v);
                }
            }
            byte[] body = request.body;
            boolean sends = body != null && (body.length > 0 || request.method().equals("POST") || request.method().equals("PUT"));
            if (sends) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
            }
            boolean connected = false;
            try {
                c.connect();
                connected = true;
                if (sends) {
                    OutputStream os = c.getOutputStream();
                    try {
                        os.write(body);
                    } finally {
                        os.close();
                    }
                }
                int status = c.getResponseCode();
                String location = c.getHeaderField("Location");
                if (status >= 300 && status < 400 && status != 304 && location != null && follows(uri, uri.resolve(location))) {
                    if (hops >= 5) {
                        throw new IOException("too many redirects");
                    }
                    c.disconnect();
                    uri = uri.resolve(location);
                    if (status == 303 || ((status == 301 || status == 302) && request.method().equals("POST"))) {
                        request = request.redirected(uri, "GET");
                    } else {
                        request = request.redirected(uri, request.method());
                    }
                    continue;
                }
                Map<String, List<String>> headers = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
                for (Map.Entry<String, List<String>> h : c.getHeaderFields().entrySet()) {
                    if (h.getKey() != null) {
                        headers.put(h.getKey(), new ArrayList<String>(h.getValue()));
                    }
                }
                final HttpHeaders read = new HttpHeaders(headers);
                final int code = status;
                HttpResponse.ResponseInfo info = new HttpResponse.ResponseInfo() {
                    public int statusCode() {
                        return code;
                    }

                    public HttpHeaders headers() {
                        return read;
                    }

                    public Version version() {
                        return Version.HTTP_1_1;
                    }
                };
                HttpResponse.BodySubscriber<T> subscriber = handler.apply(info);
                if (!(subscriber instanceof HttpResponse.Collect)) {
                    throw new IOException("a BodySubscriber of the mod's own needs Java 9's Flow, which this Java lacks");
                }
                InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
                @SuppressWarnings("unchecked")
                T value = (T) ((HttpResponse.Collect<T>) subscriber).read(in == null ? new java.io.ByteArrayInputStream(new byte[0]) : in, read);
                return new HttpResponse.Done<T>(code, read, value, uri, request);
            } catch (SocketTimeoutException timeout) {
                throw !connected ? new HttpConnectTimeoutException(timeout.getMessage()) : new HttpTimeoutException(timeout.getMessage());
            }
        }
    }

    private boolean follows(URI from, URI to) {
        return redirect == Redirect.ALWAYS || redirect == Redirect.NORMAL && !("https".equalsIgnoreCase(from.getScheme()) && "http".equalsIgnoreCase(to.getScheme()));
    }

    static int millis(Duration d) {
        long ms = d.toMillis();
        return ms > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(1, ms);
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return out.toByteArray();
    }
}

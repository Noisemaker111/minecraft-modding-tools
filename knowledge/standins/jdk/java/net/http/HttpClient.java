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
        // The JDK times each request it sends (a redirect's starts its timer again) from before connecting until the
        // response's headers arrive, by the clock and not by how long the socket stays quiet; the body is not timed.
        boolean timed = request.timeout().isPresent();
        long limit = timed ? nanos(request.timeout().get()) : Long.MAX_VALUE;
        URI uri = request.uri();
        for (int hops = 0; ; hops++) {
            HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
            c.setInstanceFollowRedirects(false);
            String method = request.method();
            Methods.set(c, method);
            int connectLimit = connectTimeout != null ? millis(connectTimeout) : 0;
            if (timed) {
                int limitMillis = millis(Duration.ofNanos(limit));
                connectLimit = connectLimit == 0 ? limitMillis : Math.min(connectLimit, limitMillis);
            }
            c.setConnectTimeout(connectLimit);
            for (Map.Entry<String, List<String>> h : request.headers().map().entrySet()) {
                for (String v : h.getValue()) {
                    c.addRequestProperty(h.getKey(), v);
                }
            }
            byte[] body = request.body;
            boolean sends = body != null && (body.length > 0 || method.equals("POST") || method.equals("PUT"));
            if (sends) {
                c.setDoOutput(true);
                // HttpURLConnection turns a GET with a body into a POST as it opens the output stream; held until the
                // response is asked for, the request is written after the method is put back.
                if (!method.equals("GET")) {
                    c.setFixedLengthStreamingMode(body.length);
                }
            }
            Deadline deadline = timed ? Deadline.at(c, limit) : null;
            boolean connected = false;
            int status;
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
                    if (method.equals("GET")) {
                        Methods.force(c, "GET");
                    }
                }
                status = c.getResponseCode();
                // Closed under a waiting read, HttpURLConnection may answer -1 and keep the failure for the next call.
                if (deadline != null && deadline.passed) {
                    throw new HttpTimeoutException("request timed out");
                }
            } catch (SocketTimeoutException timeout) {
                throw !connected ? new HttpConnectTimeoutException("HTTP connect timed out") : new HttpTimeoutException("request timed out");
            } catch (IOException e) {
                if (deadline != null && deadline.passed) {
                    throw new HttpTimeoutException("request timed out");
                }
                throw e;
            } finally {
                if (deadline != null) {
                    deadline.cancel();
                }
            }
            String location = c.getHeaderField("Location");
            // As the JDK, the fifth redirect (jdk.httpclient.redirects.limit) is the response, not followed.
            if (redirects(status) && location != null && follows(uri, uri.resolve(location)) && hops + 1 < REDIRECTS) {
                c.disconnect();
                URI to = uri.resolve(location);
                String next = status == 303 || ((status == 301 || status == 302) && method.equals("POST")) ? "GET" : method;
                request = request.redirected(to, next, sameOrigin(uri, to));
                uri = to;
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
            Body stream = new Body(in == null ? new java.io.ByteArrayInputStream(new byte[0]) : in, c, expected(method, status, read));
            @SuppressWarnings("unchecked")
            T value = (T) ((HttpResponse.Collect<T>) subscriber).read(stream, read);
            return new HttpResponse.Done<T>(code, read, value, uri, request);
        }
    }

    static final int REDIRECTS = Integer.getInteger("jdk.httpclient.redirects.limit", 5);

    /** The redirects the JDK follows: 301, 302, 303, 307 and 308. */
    static boolean redirects(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    static boolean sameOrigin(URI from, URI to) {
        String a = from.getRawAuthority(), b = to.getRawAuthority();
        return from.getScheme().equalsIgnoreCase(to.getScheme()) && (a == null ? b == null : a.equals(b));
    }

    /** The body's length the response announces, or -1 when it announces none or carries no body (HEAD, 1xx, 204, 304). */
    static long expected(String method, int status, HttpHeaders headers) {
        if (method.equals("HEAD") || status < 200 || status == 204 || status == 304 || headers.firstValue("Transfer-Encoding").isPresent()) {
            return -1;
        }
        java.util.Optional<String> length = headers.firstValue("Content-Length");
        if (!length.isPresent()) {
            return -1;
        }
        try {
            return Long.parseLong(length.get().trim());
        } catch (NumberFormatException unreadable) {
            return -1;
        }
    }

    /**
     * A response's body as it arrives. HttpURLConnection ends a body the server cut short as if it were whole; the JDK
     * fails it with an IOException, so a body that ends before the length the response announced fails here too.
     * Closing it lets the connection go.
     */
    static final class Body extends java.io.FilterInputStream {
        private final HttpURLConnection connection;
        private final long expected;
        private long read;
        private boolean ended;

        Body(InputStream in, HttpURLConnection connection, long expected) {
            super(in);
            this.connection = connection;
            this.expected = expected;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b < 0) {
                end();
            } else {
                read++;
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = in.read(buf, off, len);
            if (n < 0) {
                end();
            } else {
                read += n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = in.skip(n);
            read += skipped;
            return skipped;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        private void end() throws IOException {
            if (!ended && expected >= 0 && read < expected) {
                ended = true;
                throw new IOException("fixed content-length: " + expected + ", bytes received: " + read);
            }
            ended = true;
        }

        @Override
        public void close() throws IOException {
            try {
                in.close();
            } finally {
                if (!ended) {
                    connection.disconnect();
                }
            }
        }
    }

    /**
     * A request's deadline while it waits for the response's headers: a read timeout would bound one blocking read (a
     * server sending a little at a time outlasts it) and stay on for the body; at the deadline the connection is closed
     * under the waiting read instead.
     */
    static final class Deadline implements Runnable {
        private static java.util.concurrent.ScheduledThreadPoolExecutor timer;

        private final HttpURLConnection connection;
        private java.util.concurrent.ScheduledFuture<?> task;
        volatile boolean passed;

        private Deadline(HttpURLConnection connection) {
            this.connection = connection;
        }

        static Deadline at(HttpURLConnection connection, long nanos) {
            Deadline d = new Deadline(connection);
            d.task = timer().schedule(d, nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
            return d;
        }

        private static synchronized java.util.concurrent.ScheduledThreadPoolExecutor timer() {
            if (timer == null) {
                timer = new java.util.concurrent.ScheduledThreadPoolExecutor(1, new java.util.concurrent.ThreadFactory() {
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "HttpClient request timeouts");
                        t.setDaemon(true);
                        return t;
                    }
                });
                timer.setRemoveOnCancelPolicy(true);
            }
            return timer;
        }

        public void run() {
            passed = true;
            connection.disconnect();
        }

        void cancel() {
            task.cancel(false);
        }
    }

    /**
     * Any request method, as the JDK sends any: HttpURLConnection's setRequestMethod refuses all but seven (PATCH among
     * those refused), while the request line it writes is whatever its method field holds. The field is set directly,
     * on the connection and on the one an HTTPS connection hands its work to; a JDK that closes java.net to reflection
     * (16 and later) still lets sun.misc.Unsafe write it.
     */
    static final class Methods {
        private static final java.util.Set<String> KNOWN = new java.util.HashSet<String>(java.util.Arrays.asList("GET", "POST", "HEAD", "OPTIONS", "PUT", "DELETE", "TRACE"));

        static void set(HttpURLConnection c, String method) throws IOException {
            if (KNOWN.contains(method)) {
                c.setRequestMethod(method);
            } else {
                force(c, method);
            }
        }

        /** The method set whatever it is and however far the connection has got (setRequestMethod refuses once connected). */
        static void force(HttpURLConnection c, String method) throws IOException {
            try {
                java.lang.reflect.Field field = HttpURLConnection.class.getDeclaredField("method");
                write(field, c, method);
                for (Class<?> k = c.getClass(); k != null && k != HttpURLConnection.class; k = k.getSuperclass()) {
                    for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                        if (f.getName().equals("delegate") && HttpURLConnection.class.isAssignableFrom(f.getType())) {
                            Object delegate = read(f, c);
                            if (delegate != null) {
                                write(field, delegate, method);
                            }
                        }
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                java.net.ProtocolException refused = new java.net.ProtocolException("Invalid HTTP method: " + method);
                refused.initCause(e);
                throw refused;
            }
        }

        private static void write(java.lang.reflect.Field f, Object on, Object value) throws ReflectiveOperationException {
            try {
                f.setAccessible(true);
                f.set(on, value);
            } catch (RuntimeException closed) {
                Object unsafe = unsafe();
                long offset = (Long) unsafe.getClass().getMethod("objectFieldOffset", java.lang.reflect.Field.class).invoke(unsafe, f);
                unsafe.getClass().getMethod("putObject", Object.class, long.class, Object.class).invoke(unsafe, on, offset, value);
            }
        }

        private static Object read(java.lang.reflect.Field f, Object on) throws ReflectiveOperationException {
            try {
                f.setAccessible(true);
                return f.get(on);
            } catch (RuntimeException closed) {
                Object unsafe = unsafe();
                long offset = (Long) unsafe.getClass().getMethod("objectFieldOffset", java.lang.reflect.Field.class).invoke(unsafe, f);
                return unsafe.getClass().getMethod("getObject", Object.class, long.class).invoke(unsafe, on, offset);
            }
        }

        private static Object unsafe() throws ReflectiveOperationException {
            java.lang.reflect.Field the = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            the.setAccessible(true);
            return the.get(null);
        }
    }

    private boolean follows(URI from, URI to) {
        return redirect == Redirect.ALWAYS || redirect == Redirect.NORMAL && !("https".equalsIgnoreCase(from.getScheme()) && "http".equalsIgnoreCase(to.getScheme()));
    }

    static long nanos(Duration d) {
        try {
            return d.toNanos();
        } catch (ArithmeticException huge) {
            return Long.MAX_VALUE;
        }
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

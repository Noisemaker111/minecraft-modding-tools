package mmt.standin.java.net.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java 11's java.net.http.HttpResponse, for a release on Java 8, from the JDK's documented contract: a response has a
 * status code, headers, the request's URI and a body, whose type the BodyHandler chose. BodyHandlers: ofString() decodes
 * the body "using the character set specified in the Content-Type response header", UTF-8 when there is none;
 * ofString(charset) with that one; ofByteArray() the bytes; ofInputStream() the body as it arrives, given as soon as the
 * headers are ("closing the stream ... releases the connection"); discarding() drops the body (null); ofFile(path)
 * writes it to the file (CREATE, WRITE, TRUNCATE_EXISTING) as it arrives and gives the path. A BodySubscriber
 * a mod writes itself needs java.util.concurrent.Flow, which Java 8 lacks, so only the handlers' own subscribers run.
 */
public interface HttpResponse<T> {

    int statusCode();

    HttpRequest request();

    Optional<HttpResponse<T>> previousResponse();

    HttpHeaders headers();

    T body();

    URI uri();

    HttpClient.Version version();

    interface ResponseInfo {
        int statusCode();

        HttpHeaders headers();

        HttpClient.Version version();
    }

    interface BodyHandler<T> {
        BodySubscriber<T> apply(ResponseInfo info);
    }

    interface BodySubscriber<T> {
        CompletionStage<T> getBody();
    }

    class BodyHandlers {
        private BodyHandlers() {}

        public static BodyHandler<String> ofString() {
            return new BodyHandler<String>() {
                public BodySubscriber<String> apply(ResponseInfo info) {
                    return new Collect<String>() {
                        String make(InputStream in, HttpHeaders headers) throws IOException {
                            return new String(HttpClient.readAll(in), charset(headers));
                        }
                    };
                }
            };
        }

        public static BodyHandler<String> ofString(final Charset charset) {
            if (charset == null) {
                throw new NullPointerException("charset");
            }
            return new BodyHandler<String>() {
                public BodySubscriber<String> apply(ResponseInfo info) {
                    return new Collect<String>() {
                        String make(InputStream in, HttpHeaders headers) throws IOException {
                            return new String(HttpClient.readAll(in), charset);
                        }
                    };
                }
            };
        }

        public static BodyHandler<byte[]> ofByteArray() {
            return new BodyHandler<byte[]>() {
                public BodySubscriber<byte[]> apply(ResponseInfo info) {
                    return new Collect<byte[]>() {
                        byte[] make(InputStream in, HttpHeaders headers) throws IOException {
                            return HttpClient.readAll(in);
                        }
                    };
                }
            };
        }

        public static BodyHandler<InputStream> ofInputStream() {
            return new BodyHandler<InputStream>() {
                public BodySubscriber<InputStream> apply(ResponseInfo info) {
                    return new Collect<InputStream>() {
                        InputStream make(InputStream in, HttpHeaders headers) {
                            return in;
                        }
                    };
                }
            };
        }

        public static BodyHandler<Void> discarding() {
            return new BodyHandler<Void>() {
                public BodySubscriber<Void> apply(ResponseInfo info) {
                    return new Collect<Void>() {
                        Void make(InputStream in, HttpHeaders headers) throws IOException {
                            byte[] buf = new byte[8192];
                            try {
                                while (in.read(buf) >= 0) {
                                    continue;
                                }
                            } finally {
                                in.close();
                            }
                            return null;
                        }
                    };
                }
            };
        }

        public static BodyHandler<Path> ofFile(Path file) {
            return ofFile(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        public static BodyHandler<Path> ofFile(final Path file, final OpenOption... options) {
            return new BodyHandler<Path>() {
                public BodySubscriber<Path> apply(ResponseInfo info) {
                    return new Collect<Path>() {
                        Path make(InputStream in, HttpHeaders headers) throws IOException {
                            byte[] buf = new byte[8192];
                            try (InputStream body = in; OutputStream out = Files.newOutputStream(file, options)) {
                                for (int n; (n = body.read(buf)) >= 0; ) {
                                    out.write(buf, 0, n);
                                }
                            }
                            return file;
                        }
                    };
                }
            };
        }

        static Charset charset(HttpHeaders headers) {
            Optional<String> type = headers.firstValue("Content-Type");
            if (type.isPresent()) {
                Matcher m = Pattern.compile("(?i)charset=\"?([^\";\\s]+)").matcher(type.get());
                if (m.find()) {
                    try {
                        return Charset.forName(m.group(1));
                    } catch (RuntimeException unknown) {
                        return StandardCharsets.UTF_8;
                    }
                }
            }
            return StandardCharsets.UTF_8;
        }
    }

    /** The subscribers BodyHandlers make: the body's stream from the connection, made into the handler's type. */
    abstract class Collect<T> implements BodySubscriber<T> {
        private final CompletableFuture<T> body = new CompletableFuture<T>();

        public CompletionStage<T> getBody() {
            return body;
        }

        abstract T make(InputStream in, HttpHeaders headers) throws IOException;

        T read(InputStream in, HttpHeaders headers) throws IOException {
            try {
                T value = make(in, headers);
                body.complete(value);
                return value;
            } catch (IOException | UncheckedIOException e) {
                body.completeExceptionally(e);
                throw e;
            }
        }
    }

    final class Done<T> implements HttpResponse<T> {
        private final int status;
        private final HttpHeaders headers;
        private final T body;
        private final URI uri;
        private final HttpRequest request;

        Done(int status, HttpHeaders headers, T body, URI uri, HttpRequest request) {
            this.status = status;
            this.headers = headers;
            this.body = body;
            this.uri = uri;
            this.request = request;
        }

        public int statusCode() {
            return status;
        }

        public HttpRequest request() {
            return request;
        }

        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        public HttpHeaders headers() {
            return headers;
        }

        public T body() {
            return body;
        }

        public URI uri() {
            return uri;
        }

        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public String toString() {
            return "(" + request.method() + " " + uri + ") " + status;
        }
    }
}

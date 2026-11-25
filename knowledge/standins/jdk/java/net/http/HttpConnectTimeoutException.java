package mmt.standin.java.net.http;

/** Java 11's java.net.http.HttpConnectTimeoutException: "thrown when a connection ... is not successfully established within a specified time period". */
public class HttpConnectTimeoutException extends HttpTimeoutException {

    private static final long serialVersionUID = 321L;

    public HttpConnectTimeoutException(String message) {
        super(message);
    }
}

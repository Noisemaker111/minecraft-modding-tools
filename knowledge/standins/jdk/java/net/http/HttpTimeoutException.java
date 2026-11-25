package mmt.standin.java.net.http;

import java.io.IOException;

/** Java 11's java.net.http.HttpTimeoutException: "thrown when a response is not received within a specified time period". */
public class HttpTimeoutException extends IOException {

    private static final long serialVersionUID = 981344271622632951L;

    public HttpTimeoutException(String message) {
        super(message);
    }
}

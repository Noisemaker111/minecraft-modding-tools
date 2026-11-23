package mmt.standin.java.util.concurrent;

import java.util.Objects;

/** Java 9's CompletableFuture.failedFuture(ex): "a new CompletableFuture that is already completed exceptionally with the given exception". */
public final class CompletableFuture {

    private CompletableFuture() {}

    public static <U> java.util.concurrent.CompletableFuture<U> failedFuture(Throwable ex) {
        java.util.concurrent.CompletableFuture<U> f = new java.util.concurrent.CompletableFuture<>();
        f.completeExceptionally(Objects.requireNonNull(ex));
        return f;
    }
}

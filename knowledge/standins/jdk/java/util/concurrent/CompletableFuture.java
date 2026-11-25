package mmt.standin.java.util.concurrent;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * java.util.concurrent.CompletableFuture members Java 8 lacks, from the JDK's documented contract:
 * - failedFuture(ex) (Java 9): "a new CompletableFuture that is already completed exceptionally with the given exception".
 * - delayedExecutor(delay, unit[, executor]) (Java 9): "a new Executor that submits a task to the given base executor
 *   (else the default executor) after the given delay"; the default executor is the common pool unless it runs on one
 *   thread, then a new thread per task, and the delay is kept by one daemon thread, as the JDK's own Delayer.
 * - orTimeout(timeout, unit) (Java 9): "exceptionally completes this CompletableFuture with a TimeoutException if not
 *   otherwise completed before the given timeout"; completeOnTimeout(value, timeout, unit) completes it with the value.
 * - exceptionallyAsync(fn[, executor]) and exceptionallyCompose(fn) (Java 12): a stage completed as this one when it
 *   completes normally, else with fn's result for its exception (run on the executor; the stage fn gives), as the
 *   default methods of CompletionStage write them.
 */
public final class CompletableFuture {

    private CompletableFuture() {}

    static final ScheduledThreadPoolExecutor DELAYER = delayer();

    static ScheduledThreadPoolExecutor delayer() {
        ScheduledThreadPoolExecutor d = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("CompletableFutureDelayScheduler");
            return t;
        });
        d.setRemoveOnCancelPolicy(true);
        return d;
    }

    static Executor defaultExecutor() {
        return ForkJoinPool.getCommonPoolParallelism() > 1 ? ForkJoinPool.commonPool() : r -> new Thread(r).start();
    }

    public static <U> java.util.concurrent.CompletableFuture<U> failedFuture(Throwable ex) {
        java.util.concurrent.CompletableFuture<U> f = new java.util.concurrent.CompletableFuture<>();
        f.completeExceptionally(Objects.requireNonNull(ex));
        return f;
    }

    public static Executor delayedExecutor(long delay, TimeUnit unit, Executor executor) {
        Objects.requireNonNull(unit);
        Objects.requireNonNull(executor);
        return r -> DELAYER.schedule(() -> executor.execute(r), delay, unit);
    }

    public static Executor delayedExecutor(long delay, TimeUnit unit) {
        return delayedExecutor(delay, unit, defaultExecutor());
    }

    public static <T> java.util.concurrent.CompletableFuture<T> orTimeout(java.util.concurrent.CompletableFuture<T> self, long timeout, TimeUnit unit) {
        Objects.requireNonNull(unit);
        if (!self.isDone()) {
            java.util.concurrent.ScheduledFuture<?> f = DELAYER.schedule(() -> self.completeExceptionally(new TimeoutException()), timeout, unit);
            self.whenComplete((r, ex) -> f.cancel(false));
        }
        return self;
    }

    public static <T> java.util.concurrent.CompletableFuture<T> completeOnTimeout(java.util.concurrent.CompletableFuture<T> self, T value, long timeout, TimeUnit unit) {
        Objects.requireNonNull(unit);
        if (!self.isDone()) {
            java.util.concurrent.ScheduledFuture<?> f = DELAYER.schedule(() -> self.complete(value), timeout, unit);
            self.whenComplete((r, ex) -> f.cancel(false));
        }
        return self;
    }

    public static <T> java.util.concurrent.CompletableFuture<T> exceptionallyAsync(java.util.concurrent.CompletableFuture<T> self, Function<Throwable, ? extends T> fn) {
        return exceptionallyAsync(self, fn, defaultExecutor());
    }

    public static <T> java.util.concurrent.CompletableFuture<T> exceptionallyAsync(java.util.concurrent.CompletableFuture<T> self, Function<Throwable, ? extends T> fn, Executor executor) {
        Objects.requireNonNull(fn);
        Objects.requireNonNull(executor);
        return self.handle((r, ex) -> ex == null ? (CompletionStage<T>) self : self.<T>handleAsync((r1, ex1) -> fn.apply(ex1), executor))
            .thenCompose(Function.identity());
    }

    public static <T> java.util.concurrent.CompletableFuture<T> exceptionallyCompose(java.util.concurrent.CompletableFuture<T> self, Function<Throwable, ? extends CompletionStage<T>> fn) {
        Objects.requireNonNull(fn);
        return self.handle((r, ex) -> ex == null ? (CompletionStage<T>) self : fn.apply(ex)).thenCompose(Function.identity());
    }
}

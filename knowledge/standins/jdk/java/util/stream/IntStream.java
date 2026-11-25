package mmt.standin.java.util.stream;

import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import java.util.stream.StreamSupport;

/**
 * Java 9's IntStream.iterate(seed, hasNext, next), from the JDK's documented contract: "a sequential ordered IntStream
 * produced by iterative application of the given next function to an initial element, conditioned on satisfying the
 * given hasNext predicate", as a for loop (seed; hasNext; next) would give them; empty when the seed fails hasNext.
 */
public final class IntStream {

    private IntStream() {}

    public static java.util.stream.IntStream iterate(int seed, IntPredicate hasNext, IntUnaryOperator next) {
        Objects.requireNonNull(hasNext);
        Objects.requireNonNull(next);
        return StreamSupport.intStream(new Spliterators.AbstractIntSpliterator(Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.IMMUTABLE | Spliterator.NONNULL) {
            int prev;
            boolean started, finished;

            @Override
            public boolean tryAdvance(IntConsumer action) {
                Objects.requireNonNull(action);
                if (finished) {
                    return false;
                }
                int t;
                if (started) {
                    t = next.applyAsInt(prev);
                } else {
                    t = seed;
                    started = true;
                }
                if (!hasNext.test(t)) {
                    finished = true;
                    return false;
                }
                action.accept(prev = t);
                return true;
            }
        }, false);
    }
}

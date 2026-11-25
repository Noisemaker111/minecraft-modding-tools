package mmt.standin.java.util.stream;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.StreamSupport;

/**
 * java.util.stream.Stream members Java 8 lacks, from the JDK's documented contract: toList() (Java 16) "an unmodifiable
 * List containing the stream elements" in encounter order, nulls allowed, as its default method writes it
 * (Collections.unmodifiableList(new ArrayList<>(Arrays.asList(this.toArray())))); ofNullable(t) (Java 9) a stream of t, or
 * an empty stream for null; mapMulti(mapper) (Java 16) each element replaced by what the mapper gives its consumer, as
 * its default method writes it with flatMap; iterate(seed, hasNext, next) (Java 9) "a sequential ordered Stream produced
 * by iterative application of the given next function to an initial element, conditioned on satisfying the given
 * hasNext predicate", as a for loop (seed; hasNext; next) gives them.
 */
public final class Stream {

    private Stream() {}

    @SuppressWarnings("unchecked")
    public static <T> List<T> toList(java.util.stream.Stream<T> self) {
        return (List<T>) Collections.unmodifiableList(new ArrayList<>(Arrays.asList(self.toArray())));
    }

    public static <T> java.util.stream.Stream<T> ofNullable(T t) {
        return t == null ? java.util.stream.Stream.empty() : java.util.stream.Stream.of(t);
    }

    public static <T, R> java.util.stream.Stream<R> mapMulti(java.util.stream.Stream<T> self, BiConsumer<? super T, ? super Consumer<R>> mapper) {
        Objects.requireNonNull(mapper);
        return self.flatMap(e -> {
            List<R> buffer = new ArrayList<>();
            mapper.accept(e, (Consumer<R>) buffer::add);
            return buffer.stream();
        });
    }

    public static <T> java.util.stream.Stream<T> iterate(T seed, Predicate<? super T> hasNext, UnaryOperator<T> next) {
        Objects.requireNonNull(hasNext);
        Objects.requireNonNull(next);
        return StreamSupport.stream(new Spliterators.AbstractSpliterator<T>(Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.IMMUTABLE) {
            T prev;
            boolean started, finished;

            @Override
            public boolean tryAdvance(Consumer<? super T> action) {
                Objects.requireNonNull(action);
                if (finished) {
                    return false;
                }
                T t;
                if (started) {
                    t = next.apply(prev);
                } else {
                    t = seed;
                    started = true;
                }
                if (!hasNext.test(t)) {
                    prev = null;
                    finished = true;
                    return false;
                }
                action.accept(prev = t);
                return true;
            }
        }, false);
    }
}

package mmt.standin.java.util;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * java.util.Optional members Java 8 lacks, from the JDK's documented contract: isEmpty() (Java 11) "true if a value is not
 * present"; orElseThrow() (Java 10) the value, else NoSuchElementException, as get() does; stream() (Java 9) a stream of
 * the value or an empty one; or(supplier) (Java 9) this Optional when a value is present, else the supplier's, neither the
 * supplier nor what it gives may be null; ifPresentOrElse(action, emptyAction) (Java 9) one or the other.
 */
public final class Optional {

    private Optional() {}

    public static boolean isEmpty(java.util.Optional<?> self) {
        return !self.isPresent();
    }

    public static <T> T orElseThrow(java.util.Optional<T> self) {
        return self.get();
    }

    public static <T> Stream<T> stream(java.util.Optional<T> self) {
        return self.isPresent() ? Stream.of(self.get()) : Stream.empty();
    }

    @SuppressWarnings("unchecked")
    public static <T> java.util.Optional<T> or(java.util.Optional<T> self, Supplier<? extends java.util.Optional<? extends T>> supplier) {
        Objects.requireNonNull(supplier);
        return self.isPresent() ? self : Objects.requireNonNull((java.util.Optional<T>) supplier.get());
    }

    public static <T> void ifPresentOrElse(java.util.Optional<T> self, Consumer<? super T> action, Runnable emptyAction) {
        if (self.isPresent()) {
            action.accept(self.get());
        } else {
            emptyAction.run();
        }
    }
}

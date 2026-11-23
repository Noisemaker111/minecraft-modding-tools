package mmt.standin.java.util.function;

import java.util.Objects;

/** Java 11's Predicate.not(target), as the JDK writes it: "Objects.requireNonNull(target); return target.negate()". */
public final class Predicate {

    private Predicate() {}

    @SuppressWarnings("unchecked")
    public static <T> java.util.function.Predicate<T> not(java.util.function.Predicate<? super T> target) {
        Objects.requireNonNull(target);
        return (java.util.function.Predicate<T>) target.negate();
    }
}

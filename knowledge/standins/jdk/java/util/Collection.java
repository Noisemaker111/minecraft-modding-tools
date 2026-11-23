package mmt.standin.java.util;

import java.util.function.IntFunction;

/**
 * Java 11's Collection.toArray(IntFunction generator), as its default method is specified: "calls the generator function
 * with zero and then passes the resulting array to toArray(T[])".
 */
public final class Collection {

    private Collection() {}

    public static <T> T[] toArray(java.util.Collection<?> self, IntFunction<T[]> generator) {
        return self.toArray(generator.apply(0));
    }
}

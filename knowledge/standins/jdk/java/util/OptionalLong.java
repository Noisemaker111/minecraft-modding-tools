package mmt.standin.java.util;

import java.util.function.LongConsumer;
import java.util.stream.LongStream;

/**
 * java.util.OptionalLong members Java 8 lacks, from the JDK's documented contract: isEmpty() (Java 11) "true if a value is
 * not present"; orElseThrow() (Java 10) the value, else NoSuchElementException, as getAsLong() does; ifPresentOrElse(action,
 * emptyAction) (Java 9) one or the other; stream() (Java 9) a stream of the value or an empty one.
 */
public final class OptionalLong {

    private OptionalLong() {}

    public static boolean isEmpty(java.util.OptionalLong self) {
        return !self.isPresent();
    }

    public static long orElseThrow(java.util.OptionalLong self) {
        return self.getAsLong();
    }

    public static void ifPresentOrElse(java.util.OptionalLong self, LongConsumer action, Runnable emptyAction) {
        if (self.isPresent()) {
            action.accept(self.getAsLong());
        } else {
            emptyAction.run();
        }
    }

    public static LongStream stream(java.util.OptionalLong self) {
        return self.isPresent() ? LongStream.of(self.getAsLong()) : LongStream.empty();
    }
}

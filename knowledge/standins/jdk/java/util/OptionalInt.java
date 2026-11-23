package mmt.standin.java.util;

import java.util.function.IntConsumer;
import java.util.stream.IntStream;

/**
 * java.util.OptionalInt members Java 8 lacks, from the JDK's documented contract: isEmpty() (Java 11) "true if a value is
 * not present"; orElseThrow() (Java 10) the value, else NoSuchElementException, as getAsInt() does; ifPresentOrElse(action,
 * emptyAction) (Java 9) one or the other; stream() (Java 9) a stream of the value or an empty one.
 */
public final class OptionalInt {

    private OptionalInt() {}

    public static boolean isEmpty(java.util.OptionalInt self) {
        return !self.isPresent();
    }

    public static int orElseThrow(java.util.OptionalInt self) {
        return self.getAsInt();
    }

    public static void ifPresentOrElse(java.util.OptionalInt self, IntConsumer action, Runnable emptyAction) {
        if (self.isPresent()) {
            action.accept(self.getAsInt());
        } else {
            emptyAction.run();
        }
    }

    public static IntStream stream(java.util.OptionalInt self) {
        return self.isPresent() ? IntStream.of(self.getAsInt()) : IntStream.empty();
    }
}

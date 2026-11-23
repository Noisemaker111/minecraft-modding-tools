package mmt.standin.java.util;

import java.util.function.DoubleConsumer;
import java.util.stream.DoubleStream;

/**
 * java.util.OptionalDouble members Java 8 lacks, from the JDK's documented contract: isEmpty() (Java 11) "true if a value is
 * not present"; orElseThrow() (Java 10) the value, else NoSuchElementException, as getAsDouble() does; ifPresentOrElse(action,
 * emptyAction) (Java 9) one or the other; stream() (Java 9) a stream of the value or an empty one.
 */
public final class OptionalDouble {

    private OptionalDouble() {}

    public static boolean isEmpty(java.util.OptionalDouble self) {
        return !self.isPresent();
    }

    public static double orElseThrow(java.util.OptionalDouble self) {
        return self.getAsDouble();
    }

    public static void ifPresentOrElse(java.util.OptionalDouble self, DoubleConsumer action, Runnable emptyAction) {
        if (self.isPresent()) {
            action.accept(self.getAsDouble());
        } else {
            emptyAction.run();
        }
    }

    public static DoubleStream stream(java.util.OptionalDouble self) {
        return self.isPresent() ? DoubleStream.of(self.getAsDouble()) : DoubleStream.empty();
    }
}

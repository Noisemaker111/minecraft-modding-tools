package mmt.standin.com.mojang.serialization;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * DataFixerUpper 6.0.8's DataResult (1.20.4's) on 7.0.14 (1.20.5's).
 *
 * Read from both libraries' files: 6.0.8's getOrThrow(allowPartial, onError) gave the result when it succeeded; when it
 * failed it handed the error message to onError, then gave the partial result if allowed and present, else threw a
 * RuntimeException with the message. 7.0.14 dropped that form; a failure is a DataResult.Error with message(), and
 * resultOrPartial() gives the result or the partial one. The same steps.
 * The game's primer does not cover DataFixerUpper; this is from the two libraries' code alone.
 */
public final class DataResult {

    private DataResult() {}

    public static Object getOrThrow(com.mojang.serialization.DataResult self, boolean allowPartial, Consumer onError) {
        Optional error = self.error();
        if (error.isEmpty()) {
            return self.getOrThrow();
        }
        String message = ((com.mojang.serialization.DataResult.Error) error.get()).message();
        onError.accept(message);
        Optional partial = self.resultOrPartial();
        if (allowPartial && partial.isPresent()) {
            return partial.get();
        }
        throw new RuntimeException(message);
    }
}

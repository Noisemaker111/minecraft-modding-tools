package mmt.standin.com.mojang.serialization;

import com.mojang.serialization.Lifecycle;

/**
 * DataFixerUpper 5's DataResult.error (1.19.3) on DataFixerUpper 6 (1.19.4).
 *
 * Read from both libraries, as each release's server jar bundles them (datafixerupper 5.0.28 and 6.0.6): 5's static
 * error(message[, partialResult][, lifecycle]) took the message as a String; 6's take a Supplier<String> and make the
 * message only when it is read. A supplier of the given message is the old call.
 *
 * Agrees with Fabric's 1.19.4 post (https://fabricmc.net/2023/03/01/1194.html): "`DataResult#error` now expects a
 * `Supplier<String>`, to lazy-evaluate the error message."
 */
public final class DataResult {

    private DataResult() {}

    public static com.mojang.serialization.DataResult error(String message) {
        return com.mojang.serialization.DataResult.error(() -> message);
    }

    public static com.mojang.serialization.DataResult error(String message, Object partialResult) {
        return com.mojang.serialization.DataResult.error(() -> message, partialResult);
    }

    public static com.mojang.serialization.DataResult error(String message, Object partialResult, Lifecycle lifecycle) {
        return com.mojang.serialization.DataResult.error(() -> message, partialResult, lifecycle);
    }

    public static com.mojang.serialization.DataResult error(String message, Lifecycle lifecycle) {
        return com.mojang.serialization.DataResult.error(() -> message, lifecycle);
    }
}

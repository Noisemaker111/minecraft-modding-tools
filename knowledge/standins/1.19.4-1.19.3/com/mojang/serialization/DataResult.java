package mmt.standin.com.mojang.serialization;

import com.mojang.serialization.Lifecycle;
import java.util.function.Supplier;

/**
 * DataFixerUpper 6's DataResult.error(Supplier message, ...) on DataFixerUpper 5, for a port back from 1.19.4 to 1.19.3.
 *
 * Read from both releases' version files and libraries: 1.19.4 ships datafixerupper 6.0.6, 1.19.3 ships 5.0.28. The four
 * error(...) forms are the same in both but for the message: 6.0.6 takes a Supplier of it, 5.0.28 the String. So each is
 * 5.0.28's form with the message asked for.
 *
 * Not the same: 6.0.6 builds the message only when something reads it; here it is built when the error is made.
 */
public final class DataResult {

    private DataResult() {
    }

    public static com.mojang.serialization.DataResult error(Supplier message) {
        return com.mojang.serialization.DataResult.error((String) message.get());
    }

    public static com.mojang.serialization.DataResult error(Supplier message, Object partial) {
        return com.mojang.serialization.DataResult.error((String) message.get(), partial);
    }

    public static com.mojang.serialization.DataResult error(Supplier message, Lifecycle lifecycle) {
        return com.mojang.serialization.DataResult.error((String) message.get(), lifecycle);
    }

    public static com.mojang.serialization.DataResult error(Supplier message, Object partial, Lifecycle lifecycle) {
        return com.mojang.serialization.DataResult.error((String) message.get(), partial, lifecycle);
    }
}

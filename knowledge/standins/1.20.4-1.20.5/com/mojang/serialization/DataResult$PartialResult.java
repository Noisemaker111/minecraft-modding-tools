package mmt.standin.com.mojang.serialization;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * DataFixerUpper 6.0.8's DataResult.PartialResult (1.20.4's), which 7.0.14 (1.20.5's) removed.
 *
 * Read from both libraries' files: 6.0.8's PartialResult held a failure's message (read lazily from a supplier) and
 * the partial value, if any; map carried the message to a new partial value and flatMap joined the two messages
 * (DataResult.appendMessages, still in 7.0.14); equality was by the message supplier and the partial value. 7.0.14 keeps the same two things on DataResult.Error and has no PartialResult. The class as it was.
 *
 * The game's primer does not cover DataFixerUpper; this is from the two libraries' code alone.
 */
public final class DataResult$PartialResult {

    private final Supplier message;
    private final Optional partialResult;

    public DataResult$PartialResult(Supplier message, Optional partialResult) {
        this.message = message;
        this.partialResult = partialResult;
    }

    public DataResult$PartialResult map(Function function) {
        return new DataResult$PartialResult(this.message, this.partialResult.map(function));
    }

    public DataResult$PartialResult flatMap(Function function) {
        if (this.partialResult.isPresent()) {
            DataResult$PartialResult result = (DataResult$PartialResult) function.apply(this.partialResult.get());
            return new DataResult$PartialResult(() -> com.mojang.serialization.DataResult.appendMessages((String) this.message.get(), result.message()), result.partialResult);
        }
        return new DataResult$PartialResult(this.message, Optional.empty());
    }

    public String message() {
        return (String) this.message.get();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DataResult$PartialResult other && Objects.equals(this.message, other.message) && Objects.equals(this.partialResult, other.partialResult);
    }

    @Override
    public int hashCode() {
        return 31 * this.message.hashCode() + this.partialResult.hashCode();
    }

    @Override
    public String toString() {
        return "DynamicException[" + this.message() + " " + this.partialResult + "]";
    }
}

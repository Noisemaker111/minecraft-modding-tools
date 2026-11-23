package mmt.standin.java.util.concurrent.atomic;

/**
 * Java 9's AtomicReference memory-order methods, from the JDK's documented contract, each given the stronger volatile access Java 8
 * has, which meets every weaker order: getPlain() and getOpaque() read the value; weakCompareAndSetVolatile(e, n) "possibly
 * atomically sets the value to newValue if the current value == expectedValue" (compareAndSet always does);
 * compareAndExchange(e, n) "atomically sets the value to newValue if the current value, referred to as the witness value,
 * == expectedValue" and returns the witness value.
 */
public final class AtomicReference {

    private AtomicReference() {}

    public static <V> V getPlain(java.util.concurrent.atomic.AtomicReference<V> self) {
        return self.get();
    }

    public static <V> V getOpaque(java.util.concurrent.atomic.AtomicReference<V> self) {
        return self.get();
    }

    public static <V> boolean weakCompareAndSetVolatile(java.util.concurrent.atomic.AtomicReference<V> self, V expectedValue, V newValue) {
        return self.compareAndSet(expectedValue, newValue);
    }

    public static <V> V compareAndExchange(java.util.concurrent.atomic.AtomicReference<V> self, V expectedValue, V newValue) {
        while (true) {
            V witness = self.get();
            if (witness != expectedValue) {
                return witness;
            }
            if (self.compareAndSet(expectedValue, newValue)) {
                return expectedValue;
            }
        }
    }
}

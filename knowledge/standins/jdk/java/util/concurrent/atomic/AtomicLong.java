package mmt.standin.java.util.concurrent.atomic;

/**
 * Java 9's AtomicLong memory-order methods, from the JDK's documented contract, each given the stronger volatile access Java 8
 * has, which meets every weaker order: getPlain() and getOpaque() read the value; weakCompareAndSetVolatile(e, n) "possibly
 * atomically sets the value to newValue if the current value == expectedValue" (compareAndSet always does);
 * compareAndExchange(e, n) "atomically sets the value to newValue if the current value, referred to as the witness value,
 * == expectedValue" and returns the witness value.
 */
public final class AtomicLong {

    private AtomicLong() {}

    public static long getPlain(java.util.concurrent.atomic.AtomicLong self) {
        return self.get();
    }

    public static long getOpaque(java.util.concurrent.atomic.AtomicLong self) {
        return self.get();
    }

    public static boolean weakCompareAndSetVolatile(java.util.concurrent.atomic.AtomicLong self, long expectedValue, long newValue) {
        return self.compareAndSet(expectedValue, newValue);
    }

    public static long compareAndExchange(java.util.concurrent.atomic.AtomicLong self, long expectedValue, long newValue) {
        while (true) {
            long witness = self.get();
            if (witness != expectedValue) {
                return witness;
            }
            if (self.compareAndSet(expectedValue, newValue)) {
                return expectedValue;
            }
        }
    }
}

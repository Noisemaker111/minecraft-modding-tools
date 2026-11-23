package mmt.standin.java.util.concurrent.atomic;

/**
 * Java 9's AtomicInteger memory-order methods, from the JDK's documented contract, each given the stronger volatile access Java 8
 * has, which meets every weaker order: getPlain() and getOpaque() read the value; weakCompareAndSetVolatile(e, n) "possibly
 * atomically sets the value to newValue if the current value == expectedValue" (compareAndSet always does);
 * compareAndExchange(e, n) "atomically sets the value to newValue if the current value, referred to as the witness value,
 * == expectedValue" and returns the witness value.
 */
public final class AtomicInteger {

    private AtomicInteger() {}

    public static int getPlain(java.util.concurrent.atomic.AtomicInteger self) {
        return self.get();
    }

    public static int getOpaque(java.util.concurrent.atomic.AtomicInteger self) {
        return self.get();
    }

    public static boolean weakCompareAndSetVolatile(java.util.concurrent.atomic.AtomicInteger self, int expectedValue, int newValue) {
        return self.compareAndSet(expectedValue, newValue);
    }

    public static int compareAndExchange(java.util.concurrent.atomic.AtomicInteger self, int expectedValue, int newValue) {
        while (true) {
            int witness = self.get();
            if (witness != expectedValue) {
                return witness;
            }
            if (self.compareAndSet(expectedValue, newValue)) {
                return expectedValue;
            }
        }
    }
}

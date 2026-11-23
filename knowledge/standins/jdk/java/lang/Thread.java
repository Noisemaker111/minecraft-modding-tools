package mmt.standin.java.lang;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * java.lang.Thread members older Javas lack, from the JDK's documented contract: threadId() (Java 19) "the identifier of
 * this Thread", which getId() returns; sleep(duration) (Java 19) sleeps for the duration, returning at once for a
 * negative or zero one, as sleep(millis, nanos).
 */
public final class Thread {

    private Thread() {}

    public static long threadId(java.lang.Thread self) {
        return self.getId();
    }

    public static void sleep(Duration duration) throws InterruptedException {
        long nanos;
        try {
            nanos = duration.toNanos();
        } catch (ArithmeticException tooLong) {
            nanos = Long.MAX_VALUE;
        }
        if (nanos <= 0) {
            return;
        }
        java.lang.Thread.sleep(TimeUnit.NANOSECONDS.toMillis(nanos), (int) (nanos % 1_000_000));
    }
}

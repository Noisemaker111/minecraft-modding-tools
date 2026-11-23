package mmt.standin.java.util.concurrent;

import java.util.concurrent.TimeUnit;

/**
 * Java 19's ExecutorService.close(), as its default method is written: unless terminated, shutdown() and wait for
 * termination; an interrupt while waiting calls shutdownNow() once, the wait goes on, and the interrupt is set again after.
 */
public final class ExecutorService {

    private ExecutorService() {}

    public static void close(java.util.concurrent.ExecutorService self) {
        boolean terminated = self.isTerminated();
        if (!terminated) {
            self.shutdown();
            boolean interrupted = false;
            while (!terminated) {
                try {
                    terminated = self.awaitTermination(1L, TimeUnit.DAYS);
                } catch (InterruptedException e) {
                    if (!interrupted) {
                        self.shutdownNow();
                        interrupted = true;
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

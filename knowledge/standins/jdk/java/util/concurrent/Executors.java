package mmt.standin.java.util.concurrent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Java 21's Executors members, from the JDK's documented contract. newThreadPerTaskExecutor(factory) "creates an Executor
 * that starts a new Thread for each task", made by the factory, and "the number of threads created by the Executor is
 * unbounded"; shutdownNow() interrupts the threads running tasks; a task submitted after shutdown, or one the factory makes
 * no thread for, is rejected (RejectedExecutionException). newVirtualThreadPerTaskExecutor() is the same with a virtual
 * thread for each task: Thread.ofVirtual().factory() (the Thread stand-in's).
 */
public final class Executors {

    private Executors() {}

    public static java.util.concurrent.ExecutorService newThreadPerTaskExecutor(ThreadFactory factory) {
        if (factory == null) {
            throw new NullPointerException();
        }
        return new PerTask(factory);
    }

    public static java.util.concurrent.ExecutorService newVirtualThreadPerTaskExecutor() {
        return new PerTask(mmt.standin.java.lang.Thread.ofVirtual().factory());
    }

    static final class PerTask extends AbstractExecutorService {

        final ThreadFactory factory;
        final Set<Thread> running = new HashSet<>(); // guarded by this
        boolean shut; // guarded by this

        PerTask(ThreadFactory factory) {
            this.factory = factory;
        }

        @Override
        public void execute(Runnable task) {
            if (task == null) {
                throw new NullPointerException();
            }
            Thread[] self = new Thread[1];
            Thread t = factory.newThread(() -> {
                try {
                    task.run();
                } finally {
                    synchronized (PerTask.this) {
                        running.remove(self[0]);
                        PerTask.this.notifyAll();
                    }
                }
            });
            if (t == null) {
                throw new RejectedExecutionException("the thread factory made no thread");
            }
            self[0] = t;
            synchronized (this) {
                if (shut) {
                    throw new RejectedExecutionException("the executor is shut down");
                }
                running.add(t);
            }
            t.start();
        }

        @Override
        public synchronized void shutdown() {
            shut = true;
            notifyAll();
        }

        @Override
        public synchronized List<Runnable> shutdownNow() {
            shut = true;
            for (Thread t : running) {
                t.interrupt();
            }
            notifyAll();
            return new ArrayList<>();
        }

        @Override
        public synchronized boolean isShutdown() {
            return shut;
        }

        @Override
        public synchronized boolean isTerminated() {
            return shut && running.isEmpty();
        }

        @Override
        public synchronized boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            long end = System.nanoTime() + unit.toNanos(timeout);
            while (!isTerminated()) {
                long left = end - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(this, left);
            }
            return true;
        }
    }
}

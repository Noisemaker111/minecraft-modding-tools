package mmt.standin.java.lang;

import java.time.Duration;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * java.lang.Thread members older Javas lack, from the JDK's documented contract: threadId() (Java 19) "the identifier of
 * this Thread", which getId() returns; sleep(duration) (Java 19) sleeps for the duration, returning at once for a
 * negative or zero one, as sleep(millis, nanos).
 *
 * Java 21's virtual threads, on a Java that has none: ofVirtual() "returns a builder for creating a virtual Thread or
 * ThreadFactory that creates virtual threads", startVirtualThread(task) "creates a virtual thread to execute a task and
 * schedules it to execute". What a mod can see of a virtual thread is kept: it is a daemon thread ("virtual threads are
 * daemon threads"), of normal priority, named "" unless the builder names it (name(prefix, start) numbers each thread it
 * makes from start), inheriting inheritable thread-locals unless told not to, with the builder's uncaught exception
 * handler. It runs as a thread of the platform's own, which is how such a thread ran before Java 21.
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

    public static Builder.OfVirtual ofVirtual() {
        return new Virtual();
    }

    public static java.lang.Thread startVirtualThread(Runnable task) {
        if (task == null) {
            throw new NullPointerException();
        }
        return ofVirtual().start(task);
    }

    /** Java 21's Thread.Builder. */
    public interface Builder {

        Builder name(java.lang.String name);

        Builder name(java.lang.String prefix, long start);

        Builder inheritInheritableThreadLocals(boolean inherit);

        Builder uncaughtExceptionHandler(java.lang.Thread.UncaughtExceptionHandler ueh);

        java.lang.Thread unstarted(Runnable task);

        java.lang.Thread start(Runnable task);

        ThreadFactory factory();

        /** Java 21's Thread.Builder.OfVirtual. */
        interface OfVirtual extends Builder {

            OfVirtual name(java.lang.String name);

            OfVirtual name(java.lang.String prefix, long start);

            OfVirtual inheritInheritableThreadLocals(boolean inherit);

            OfVirtual uncaughtExceptionHandler(java.lang.Thread.UncaughtExceptionHandler ueh);
        }
    }

    /** Java 9's constructor that can leave inheritable thread-locals out; Java 8 always passes them on. */
    static final java.lang.reflect.Constructor<java.lang.Thread> UNINHERITED = uninherited();

    static java.lang.reflect.Constructor<java.lang.Thread> uninherited() {
        try {
            return java.lang.Thread.class.getConstructor(ThreadGroup.class, Runnable.class, java.lang.String.class, long.class, boolean.class);
        } catch (NoSuchMethodException java8) {
            return null;
        }
    }

    static final class Virtual implements Builder.OfVirtual {

        java.lang.String name = "";
        AtomicLong counter; // set by name(prefix, start): each thread made is named prefix + the next number
        boolean inherit = true;
        java.lang.Thread.UncaughtExceptionHandler ueh;

        public Builder.OfVirtual name(java.lang.String name) {
            if (name == null) {
                throw new NullPointerException();
            }
            this.name = name;
            this.counter = null;
            return this;
        }

        public Builder.OfVirtual name(java.lang.String prefix, long start) {
            if (prefix == null) {
                throw new NullPointerException();
            }
            if (start < 0) {
                throw new IllegalArgumentException("'start' is negative");
            }
            this.name = prefix;
            this.counter = new AtomicLong(start);
            return this;
        }

        public Builder.OfVirtual inheritInheritableThreadLocals(boolean inherit) {
            this.inherit = inherit;
            return this;
        }

        public Builder.OfVirtual uncaughtExceptionHandler(java.lang.Thread.UncaughtExceptionHandler ueh) {
            this.ueh = ueh;
            return this;
        }

        public java.lang.Thread unstarted(Runnable task) {
            if (task == null) {
                throw new NullPointerException();
            }
            return make(task, counter == null ? name : name + counter.getAndIncrement());
        }

        public java.lang.Thread start(Runnable task) {
            java.lang.Thread t = unstarted(task);
            t.start();
            return t;
        }

        public ThreadFactory factory() {
            // A factory is "thread safe" and keeps this builder's settings as they are now.
            java.lang.String prefix = name;
            AtomicLong count = counter == null ? null : new AtomicLong(counter.get());
            boolean inherits = inherit;
            java.lang.Thread.UncaughtExceptionHandler handler = ueh;
            return task -> {
                if (task == null) {
                    throw new NullPointerException();
                }
                Virtual made = new Virtual();
                made.inherit = inherits;
                made.ueh = handler;
                return made.make(task, count == null ? prefix : prefix + count.getAndIncrement());
            };
        }

        java.lang.Thread make(Runnable task, java.lang.String threadName) {
            java.lang.Thread t;
            try {
                t = inherit || UNINHERITED == null ? new java.lang.Thread(null, task, threadName, 0)
                    : UNINHERITED.newInstance(null, task, threadName, 0L, false);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            t.setDaemon(true);
            t.setPriority(java.lang.Thread.NORM_PRIORITY);
            if (ueh != null) {
                t.setUncaughtExceptionHandler(ueh);
            }
            return t;
        }
    }
}

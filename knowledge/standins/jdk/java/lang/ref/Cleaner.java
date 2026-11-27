package mmt.standin.java.lang.ref;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Java 9's java.lang.ref.Cleaner, for a release on Java 8, over PhantomReference and a ReferenceQueue, from the JDK's
 * documented contract: "Cleaner manages a set of object references and corresponding cleaning actions. Cleaning actions
 * are registered to run after the cleaner is notified that the object has become phantom reachable." create() makes a
 * cleaner whose thread is a daemon thread; create(threadFactory) takes its thread from the factory, "set to be a daemon
 * thread". register(obj, action) returns a Cleanable whose clean() "unregisters the cleanable and invokes the cleaning
 * action. The cleanable's cleaning action is invoked at most once regardless of the number of calls to clean"; an action
 * that throws is ignored, as "exceptions thrown by the cleaning action are ignored". "The cleaner terminates when it is
 * phantom reachable and all of the registered cleaning actions are complete": the cleaner registers itself, as the
 * JDK's does, and its thread ends once nothing registered is left.
 */
public final class Cleaner {

    public interface Cleanable {
        void clean();
    }

    static final AtomicInteger COUNT = new AtomicInteger();

    final ReferenceQueue<Object> queue = new ReferenceQueue<>();
    final Set<Ref> registered = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<Ref, Boolean>()));

    private Cleaner() {}

    public static Cleaner create() {
        return create(task -> {
            Thread t = new Thread(task, "Cleaner-" + COUNT.getAndIncrement());
            t.setPriority(Thread.MAX_PRIORITY - 2);
            return t;
        });
    }

    public static Cleaner create(ThreadFactory threadFactory) {
        Objects.requireNonNull(threadFactory, "threadFactory");
        Cleaner cleaner = new Cleaner();
        cleaner.register(cleaner, () -> {});
        Thread thread = threadFactory.newThread(new Run(cleaner.queue, cleaner.registered));
        if (thread == null) {
            throw new IllegalStateException("ThreadFactory returned null");
        }
        thread.setDaemon(true);
        thread.start();
        return cleaner;
    }

    public Cleanable register(Object obj, Runnable action) {
        Objects.requireNonNull(obj, "obj");
        Objects.requireNonNull(action, "action");
        Ref ref = new Ref(obj, queue, registered, action);
        registered.add(ref);
        return ref;
    }

    /** The cleaner's thread: holds the queue and the set, never the cleaner, so the cleaner can become phantom reachable. */
    static final class Run implements Runnable {

        final ReferenceQueue<Object> queue;
        final Set<Ref> registered;

        Run(ReferenceQueue<Object> queue, Set<Ref> registered) {
            this.queue = queue;
            this.registered = registered;
        }

        public void run() {
            while (!registered.isEmpty()) {
                try {
                    Ref ref = (Ref) queue.remove(60_000);
                    if (ref != null) {
                        ref.clean();
                    }
                } catch (Throwable ignored) {
                    // "exceptions thrown by the cleaning action are ignored", as is an interrupt of the cleaner's thread
                }
            }
        }
    }

    static final class Ref extends PhantomReference<Object> implements Cleanable {

        final Set<Ref> registered;
        Runnable action;

        Ref(Object obj, ReferenceQueue<Object> queue, Set<Ref> registered, Runnable action) {
            super(obj, queue);
            this.registered = registered;
            this.action = action;
        }

        public void clean() {
            Runnable run;
            synchronized (this) {
                run = action;
                action = null;
            }
            if (run != null) {
                registered.remove(this);
                clear();
                run.run();
            }
        }
    }
}

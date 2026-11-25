package mmt.standin.java.util;

import java.util.Iterator;
import java.util.ServiceConfigurationError;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;

/**
 * java.util.ServiceLoader members Java 8 lacks (Java 9), from the JDK's documented contract:
 * - findFirst(): "Load the first available service provider of this loader's service ... an Optional describing it, or an
 *   empty Optional if no service is found", errors the iterator throws (ServiceConfigurationError) passed on.
 * - stream(): "a stream to lazily load available providers of this loader's service", each a Provider whose type() is the
 *   provider's class and get() an instance of it. Java 8 finds a provider only by making it, so each is made as the stream
 *   reaches it (the JDK makes it at get()): get() gives that one first, then a new one each call, as the JDK makes a
 *   provider class anew; an error making one is thrown as ServiceConfigurationError, as get() throws it.
 * - ServiceLoader.Provider, the type stream() gives, written here because Java 8 has none.
 */
public final class ServiceLoader {

    private ServiceLoader() {}

    public static <S> java.util.Optional<S> findFirst(java.util.ServiceLoader<S> self) {
        Iterator<S> it = self.iterator();
        return it.hasNext() ? java.util.Optional.of(it.next()) : java.util.Optional.empty();
    }

    public static <S> java.util.stream.Stream<Provider<S>> stream(java.util.ServiceLoader<S> self) {
        Iterator<S> it = self.iterator();
        return StreamSupport.stream(new Spliterators.AbstractSpliterator<Provider<S>>(Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL) {
            @Override
            public boolean tryAdvance(Consumer<? super Provider<S>> action) {
                if (!it.hasNext()) {
                    return false;
                }
                action.accept(new Made<>(it.next()));
                return true;
            }
        }, false);
    }

    public interface Provider<S> extends Supplier<S> {

        java.lang.Class<? extends S> type();

        @Override
        S get();
    }

    static final class Made<S> implements Provider<S> {

        private S first;
        private final java.lang.Class<? extends S> type;

        @SuppressWarnings("unchecked")
        Made(S made) {
            this.first = made;
            this.type = (java.lang.Class<? extends S>) made.getClass();
        }

        @Override
        public java.lang.Class<? extends S> type() {
            return type;
        }

        @Override
        public synchronized S get() {
            if (first != null) {
                S out = first;
                first = null;
                return out;
            }
            try {
                return type.getConstructor().newInstance();
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new ServiceConfigurationError(type.getName() + ": provider could not be instantiated", e);
            }
        }
    }
}

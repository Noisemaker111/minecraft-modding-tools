package mmt.standin.java.util;

import java.util.Iterator;

/**
 * Java 9's ServiceLoader.findFirst(), from the JDK's documented contract: "Load the first available service provider of
 * this loader's service ... an Optional describing it, or an empty Optional if no service is found", errors the iterator
 * throws (ServiceConfigurationError) passed on.
 */
public final class ServiceLoader {

    private ServiceLoader() {}

    public static <S> java.util.Optional<S> findFirst(java.util.ServiceLoader<S> self) {
        Iterator<S> it = self.iterator();
        return it.hasNext() ? java.util.Optional.of(it.next()) : java.util.Optional.empty();
    }
}

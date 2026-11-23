package mmt.standin.java.util;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;

/**
 * java.util.Set members older Javas lack, from the JDK's documented contract: Java 9's Set.of ("disallow null elements ...
 * NullPointerException"; "they reject duplicate elements at creation time. Duplicate elements passed to a static factory
 * method result in IllegalArgumentException"; "the iteration order of set elements is unspecified") and Java 10's
 * Set.copyOf ("If the given Collection contains duplicate elements, an arbitrary element of the duplicates is preserved").
 */
public final class Set {

    private Set() {}

    public static <E> java.util.Set<E> of() {
        return unmodifiable(new Object[] {  }, true);
    }

    public static <E> java.util.Set<E> of(E e1) {
        return unmodifiable(new Object[] { e1 }, true);
    }

    public static <E> java.util.Set<E> of(E e1, E e2) {
        return unmodifiable(new Object[] { e1, e2 }, true);
    }

    public static <E> java.util.Set<E> of(E e1, E e2, E e3) {
        return unmodifiable(new Object[] { e1, e2, e3 }, true);
    }

    public static <E> java.util.Set<E> of(E e1, E e2, E e3, E e4) {
        return unmodifiable(new Object[] { e1, e2, e3, e4 }, true);
    }

    public static <E> java.util.Set<E> of(E e1, E e2, E e3, E e4, E e5) {
        return unmodifiable(new Object[] { e1, e2, e3, e4, e5 }, true);
    }

    public static <E> java.util.Set<E> of(E e1, E e2, E e3, E e4, E e5, E e6) {
        return unmodifiable(new Object[] { e1, e2, e3, e4, e5, e6 }, true);
    }

    public static <E> java.util.Set<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7) {
        return unmodifiable(new Object[] { e1, e2, e3, e4, e5, e6, e7 }, true);
    }

    public static <E> java.util.Set<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8) {
        return unmodifiable(new Object[] { e1, e2, e3, e4, e5, e6, e7, e8 }, true);
    }

    public static <E> java.util.Set<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8, E e9) {
        return unmodifiable(new Object[] { e1, e2, e3, e4, e5, e6, e7, e8, e9 }, true);
    }

    public static <E> java.util.Set<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8, E e9, E e10) {
        return unmodifiable(new Object[] { e1, e2, e3, e4, e5, e6, e7, e8, e9, e10 }, true);
    }

    @SafeVarargs
    public static <E> java.util.Set<E> of(E... elements) {
        return unmodifiable(elements.clone(), true);
    }

    public static <E> java.util.Set<E> copyOf(Collection<? extends E> coll) {
        return unmodifiable(coll.toArray(), false);
    }

    @SuppressWarnings("unchecked")
    static <E> java.util.Set<E> unmodifiable(Object[] elements, boolean distinct) {
        LinkedHashSet<Object> set = new LinkedHashSet<>();
        for (Object e : elements) {
            if (!set.add(Objects.requireNonNull(e)) && distinct) {
                throw new IllegalArgumentException("duplicate element: " + e);
            }
        }
        return (java.util.Set<E>) Collections.unmodifiableSet(set);
    }
}

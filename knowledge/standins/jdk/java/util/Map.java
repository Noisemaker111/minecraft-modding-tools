package mmt.standin.java.util;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * java.util.Map members older Javas lack, from the JDK's documented contract: Java 9's Map.of and Map.ofEntries ("disallow
 * null keys and values ... NullPointerException"; "reject duplicate keys at creation time ... IllegalArgumentException";
 * "the iteration order of mappings is unspecified"), Map.entry ("an unmodifiable Map.Entry containing the given key and
 * value", neither null) and Java 10's Map.copyOf (an unmodifiable map of the given one's entries, none null).
 */
public final class Map {

    private Map() {}

    public static <K, V> java.util.Map<K, V> of() {
        return unmodifiable(new Object[] {  });
    }

    public static <K, V> java.util.Map<K, V> of(K k1, V v1) {
        return unmodifiable(new Object[] { k1, v1 });
    }

    public static <K, V> java.util.Map<K, V> of(K k1, V v1, K k2, V v2) {
        return unmodifiable(new Object[] { k1, v1, k2, v2 });
    }

    public static <K, V> java.util.Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3) {
        return unmodifiable(new Object[] { k1, v1, k2, v2, k3, v3 });
    }

    public static <K, V> java.util.Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4) {
        return unmodifiable(new Object[] { k1, v1, k2, v2, k3, v3, k4, v4 });
    }

    public static <K, V> java.util.Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5) {
        return unmodifiable(new Object[] { k1, v1, k2, v2, k3, v3, k4, v4, k5, v5 });
    }

    public static <K, V> java.util.Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6) {
        return unmodifiable(new Object[] { k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6 });
    }

    public static <K, V> java.util.Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7) {
        return unmodifiable(new Object[] { k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7 });
    }

    public static <K, V> java.util.Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7, K k8, V v8) {
        return unmodifiable(new Object[] { k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8 });
    }

    public static <K, V> java.util.Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7, K k8, V v8, K k9, V v9) {
        return unmodifiable(new Object[] { k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9 });
    }

    public static <K, V> java.util.Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7, K k8, V v8, K k9, V v9, K k10, V v10) {
        return unmodifiable(new Object[] { k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10 });
    }

    @SafeVarargs
    public static <K, V> java.util.Map<K, V> ofEntries(java.util.Map.Entry<? extends K, ? extends V>... entries) {
        Object[] pairs = new Object[entries.length * 2];
        for (int i = 0; i < entries.length; i++) {
            pairs[2 * i] = entries[i].getKey();
            pairs[2 * i + 1] = entries[i].getValue();
        }
        return unmodifiable(pairs);
    }

    public static <K, V> java.util.Map.Entry<K, V> entry(K k, V v) {
        return new AbstractMap.SimpleImmutableEntry<>(Objects.requireNonNull(k), Objects.requireNonNull(v));
    }

    @SuppressWarnings("unchecked")
    public static <K, V> java.util.Map<K, V> copyOf(java.util.Map<? extends K, ? extends V> map) {
        LinkedHashMap<Object, Object> copy = new LinkedHashMap<>();
        for (java.util.Map.Entry<? extends K, ? extends V> e : map.entrySet()) {
            copy.put(Objects.requireNonNull(e.getKey()), Objects.requireNonNull(e.getValue()));
        }
        return (java.util.Map<K, V>) Collections.unmodifiableMap(copy);
    }

    @SuppressWarnings("unchecked")
    static <K, V> java.util.Map<K, V> unmodifiable(Object[] pairs) {
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (map.put(Objects.requireNonNull(pairs[i]), Objects.requireNonNull(pairs[i + 1])) != null) {
                throw new IllegalArgumentException("duplicate key: " + pairs[i]);
            }
        }
        return (java.util.Map<K, V>) Collections.unmodifiableMap(map);
    }
}

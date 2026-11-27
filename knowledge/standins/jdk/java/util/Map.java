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
 *
 * Java 21's SequencedMap methods, for a map a class names as Map because the release's Java lacks SequencedMap (Downgrade's
 * inserted): sequencedKeySet(), sequencedValues() and sequencedEntrySet() are, as SequencedMap's default methods are
 * specified, views of keySet(), values() and entrySet() in the map's encounter order; firstEntry() and lastEntry() "the
 * first [last] key-value mapping ... or null if this map is empty", as an unmodifiable copy, and pollFirstEntry() and
 * pollLastEntry() remove it and return the same copy. A NavigableMap answers them itself, as it does on Java 21.
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

    public static <K> java.util.Set<K> sequencedKeySet(java.util.Map<K, ?> self) {
        return self.keySet();
    }

    public static <V> java.util.Collection<V> sequencedValues(java.util.Map<?, V> self) {
        return self.values();
    }

    public static <K, V> java.util.Set<java.util.Map.Entry<K, V>> sequencedEntrySet(java.util.Map<K, V> self) {
        return self.entrySet();
    }

    public static <K, V> java.util.Map.Entry<K, V> firstEntry(java.util.Map<K, V> self) {
        if (self instanceof java.util.NavigableMap) {
            return ((java.util.NavigableMap<K, V>) self).firstEntry();
        }
        java.util.Iterator<java.util.Map.Entry<K, V>> it = self.entrySet().iterator();
        return it.hasNext() ? new AbstractMap.SimpleImmutableEntry<>(it.next()) : null;
    }

    public static <K, V> java.util.Map.Entry<K, V> lastEntry(java.util.Map<K, V> self) {
        if (self instanceof java.util.NavigableMap) {
            return ((java.util.NavigableMap<K, V>) self).lastEntry();
        }
        java.util.Map.Entry<K, V> last = null;
        for (java.util.Map.Entry<K, V> e : self.entrySet()) {
            last = e;
        }
        return last == null ? null : new AbstractMap.SimpleImmutableEntry<>(last);
    }

    public static <K, V> java.util.Map.Entry<K, V> pollFirstEntry(java.util.Map<K, V> self) {
        if (self instanceof java.util.NavigableMap) {
            return ((java.util.NavigableMap<K, V>) self).pollFirstEntry();
        }
        java.util.Iterator<java.util.Map.Entry<K, V>> it = self.entrySet().iterator();
        if (!it.hasNext()) {
            return null;
        }
        java.util.Map.Entry<K, V> first = new AbstractMap.SimpleImmutableEntry<>(it.next());
        it.remove();
        return first;
    }

    public static <K, V> java.util.Map.Entry<K, V> pollLastEntry(java.util.Map<K, V> self) {
        if (self instanceof java.util.NavigableMap) {
            return ((java.util.NavigableMap<K, V>) self).pollLastEntry();
        }
        java.util.Iterator<java.util.Map.Entry<K, V>> it = self.entrySet().iterator();
        if (!it.hasNext()) {
            return null;
        }
        java.util.Map.Entry<K, V> last = it.next();
        while (it.hasNext()) {
            last = it.next();
        }
        java.util.Map.Entry<K, V> copy = new AbstractMap.SimpleImmutableEntry<>(last);
        it.remove();
        return copy;
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

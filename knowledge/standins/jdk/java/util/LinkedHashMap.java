package mmt.standin.java.util;

import java.util.AbstractMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Java 21's SequencedMap methods on LinkedHashMap, as SequencedMap's default methods are specified: firstEntry() and
 * lastEntry() the first and last mapping in encounter order as an unmodifiable copy, or null when empty; pollFirstEntry()
 * "removes and returns the first key-value mapping", the same copy, or null.
 */
public final class LinkedHashMap {

    private LinkedHashMap() {}

    public static <K, V> Map.Entry<K, V> firstEntry(java.util.LinkedHashMap<K, V> self) {
        Iterator<Map.Entry<K, V>> it = self.entrySet().iterator();
        return it.hasNext() ? new AbstractMap.SimpleImmutableEntry<>(it.next()) : null;
    }

    public static <K, V> Map.Entry<K, V> lastEntry(java.util.LinkedHashMap<K, V> self) {
        Map.Entry<K, V> last = null;
        for (Map.Entry<K, V> e : self.entrySet()) {
            last = e;
        }
        return last == null ? null : new AbstractMap.SimpleImmutableEntry<>(last);
    }

    public static <K, V> Map.Entry<K, V> pollFirstEntry(java.util.LinkedHashMap<K, V> self) {
        Iterator<Map.Entry<K, V>> it = self.entrySet().iterator();
        if (!it.hasNext()) {
            return null;
        }
        Map.Entry<K, V> first = new AbstractMap.SimpleImmutableEntry<>(it.next());
        it.remove();
        return first;
    }
}

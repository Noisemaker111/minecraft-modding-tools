package mmt.standin.java.util.stream;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collector;

/**
 * Java 10's unmodifiable collectors, from the JDK's documented contract: toUnmodifiableList() and toUnmodifiableSet()
 * gather into an unmodifiable List or Set and throw NullPointerException "if it is presented with a null value";
 * toUnmodifiableMap(keyMapper, valueMapper) the same for keys and values, and IllegalStateException for a duplicate key
 * ("If the mapped keys contain duplicates (according to Object.equals(Object)), an IllegalStateException is thrown").
 */
public final class Collectors {

    private Collectors() {}

    public static <T> Collector<T, ?, List<T>> toUnmodifiableList() {
        return java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(), list -> {
            list.forEach(Objects::requireNonNull);
            return Collections.unmodifiableList(list);
        });
    }

    public static <T> Collector<T, ?, Set<T>> toUnmodifiableSet() {
        return java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toCollection(HashSet::new), set -> {
            set.forEach(Objects::requireNonNull);
            return Collections.unmodifiableSet(set);
        });
    }

    public static <T, K, U> Collector<T, ?, Map<K, U>> toUnmodifiableMap(Function<? super T, ? extends K> keyMapper, Function<? super T, ? extends U> valueMapper) {
        Objects.requireNonNull(keyMapper, "keyMapper");
        Objects.requireNonNull(valueMapper, "valueMapper");
        return java.util.stream.Collectors.collectingAndThen(
            java.util.stream.Collectors.toMap(t -> Objects.requireNonNull(keyMapper.apply(t)), t -> Objects.requireNonNull(valueMapper.apply(t)), (a, b) -> {
                throw new IllegalStateException("Duplicate key");
            }, HashMap::new), Collections::unmodifiableMap);
    }
}

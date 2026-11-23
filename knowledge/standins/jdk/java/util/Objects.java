package mmt.standin.java.util;

import java.util.function.Supplier;

/**
 * java.util.Objects members Java 8 lacks, from the JDK's documented contract (Java 9):
 * requireNonNullElse(obj, defaultObj) "the first argument if it is non-null and otherwise the second argument if it is
 * non-null", NullPointerException when both are null; requireNonNullElseGet(obj, supplier) the same with
 * "the value of supplier.get()"; checkIndex(index, length) throws IndexOutOfBoundsException unless 0 <= index < length and
 * returns index; checkFromToIndex(from, to, length) unless 0 <= from <= to <= length, returning from;
 * checkFromIndexSize(from, size, length) unless 0 <= from <= from + size <= length, returning from. The messages are the
 * JDK's own (jdk.internal.util.Preconditions.outOfBoundsMessage).
 */
public final class Objects {

    private Objects() {}

    public static <T> T requireNonNullElse(T obj, T defaultObj) {
        return obj != null ? obj : java.util.Objects.requireNonNull(defaultObj, "defaultObj");
    }

    public static <T> T requireNonNullElseGet(T obj, Supplier<? extends T> supplier) {
        return obj != null ? obj : java.util.Objects.requireNonNull(java.util.Objects.requireNonNull(supplier, "supplier").get(), "supplier.get()");
    }

    public static int checkIndex(int index, int length) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException(String.format("Index %s out of bounds for length %s", index, length));
        }
        return index;
    }

    public static int checkFromToIndex(int fromIndex, int toIndex, int length) {
        if (fromIndex < 0 || fromIndex > toIndex || toIndex > length) {
            throw new IndexOutOfBoundsException(String.format("Range [%s, %s) out of bounds for length %s", fromIndex, toIndex, length));
        }
        return fromIndex;
    }

    public static int checkFromIndexSize(int fromIndex, int size, int length) {
        if ((length | fromIndex | size) < 0 || size > length - fromIndex) {
            throw new IndexOutOfBoundsException(String.format("Range [%s, %<s + %s) out of bounds for length %s", fromIndex, size, length));
        }
        return fromIndex;
    }
}

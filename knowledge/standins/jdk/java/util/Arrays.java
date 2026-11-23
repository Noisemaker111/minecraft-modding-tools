package mmt.standin.java.util;

/**
 * Java 9's java.util.Arrays range comparisons, from the JDK's documented contract: equals(a, aFrom, aTo, b, bFrom, bTo)
 * "true if the two arrays, over the specified ranges, are equal" (floats and doubles as Float.floatToIntBits and
 * Double.doubleToLongBits compare them, objects by Objects.equals), each range checked as Arrays.rangeCheck does
 * (IllegalArgumentException when from > to, ArrayIndexOutOfBoundsException when out of the array); compare(a, b) for int
 * arrays: null before non-null, then the first mismatching element by Integer.compare, else by length.
 */
public final class Arrays {

    private Arrays() {}

    static void range(int length, int from, int to) {
        if (from > to) {
            throw new IllegalArgumentException("fromIndex(" + from + ") > toIndex(" + to + ")");
        }
        if (from < 0) {
            throw new ArrayIndexOutOfBoundsException(from);
        }
        if (to > length) {
            throw new ArrayIndexOutOfBoundsException(to);
        }
    }

    public static boolean equals(int[] a, int aFromIndex, int aToIndex, int[] b, int bFromIndex, int bToIndex) {
        range(a.length, aFromIndex, aToIndex);
        range(b.length, bFromIndex, bToIndex);
        if (aToIndex - aFromIndex != bToIndex - bFromIndex) {
            return false;
        }
        for (int i = aFromIndex, j = bFromIndex; i < aToIndex; i++, j++) {
            if (a[i] != b[j]) {
                return false;
            }
        }
        return true;
    }

    public static boolean equals(long[] a, int aFromIndex, int aToIndex, long[] b, int bFromIndex, int bToIndex) {
        range(a.length, aFromIndex, aToIndex);
        range(b.length, bFromIndex, bToIndex);
        if (aToIndex - aFromIndex != bToIndex - bFromIndex) {
            return false;
        }
        for (int i = aFromIndex, j = bFromIndex; i < aToIndex; i++, j++) {
            if (a[i] != b[j]) {
                return false;
            }
        }
        return true;
    }

    public static boolean equals(byte[] a, int aFromIndex, int aToIndex, byte[] b, int bFromIndex, int bToIndex) {
        range(a.length, aFromIndex, aToIndex);
        range(b.length, bFromIndex, bToIndex);
        if (aToIndex - aFromIndex != bToIndex - bFromIndex) {
            return false;
        }
        for (int i = aFromIndex, j = bFromIndex; i < aToIndex; i++, j++) {
            if (a[i] != b[j]) {
                return false;
            }
        }
        return true;
    }

    public static boolean equals(char[] a, int aFromIndex, int aToIndex, char[] b, int bFromIndex, int bToIndex) {
        range(a.length, aFromIndex, aToIndex);
        range(b.length, bFromIndex, bToIndex);
        if (aToIndex - aFromIndex != bToIndex - bFromIndex) {
            return false;
        }
        for (int i = aFromIndex, j = bFromIndex; i < aToIndex; i++, j++) {
            if (a[i] != b[j]) {
                return false;
            }
        }
        return true;
    }

    public static boolean equals(short[] a, int aFromIndex, int aToIndex, short[] b, int bFromIndex, int bToIndex) {
        range(a.length, aFromIndex, aToIndex);
        range(b.length, bFromIndex, bToIndex);
        if (aToIndex - aFromIndex != bToIndex - bFromIndex) {
            return false;
        }
        for (int i = aFromIndex, j = bFromIndex; i < aToIndex; i++, j++) {
            if (a[i] != b[j]) {
                return false;
            }
        }
        return true;
    }

    public static boolean equals(float[] a, int aFromIndex, int aToIndex, float[] b, int bFromIndex, int bToIndex) {
        range(a.length, aFromIndex, aToIndex);
        range(b.length, bFromIndex, bToIndex);
        if (aToIndex - aFromIndex != bToIndex - bFromIndex) {
            return false;
        }
        for (int i = aFromIndex, j = bFromIndex; i < aToIndex; i++, j++) {
            if (Float.floatToIntBits(a[i]) != Float.floatToIntBits(b[j])) {
                return false;
            }
        }
        return true;
    }

    public static boolean equals(double[] a, int aFromIndex, int aToIndex, double[] b, int bFromIndex, int bToIndex) {
        range(a.length, aFromIndex, aToIndex);
        range(b.length, bFromIndex, bToIndex);
        if (aToIndex - aFromIndex != bToIndex - bFromIndex) {
            return false;
        }
        for (int i = aFromIndex, j = bFromIndex; i < aToIndex; i++, j++) {
            if (Double.doubleToLongBits(a[i]) != Double.doubleToLongBits(b[j])) {
                return false;
            }
        }
        return true;
    }

    public static boolean equals(Object[] a, int aFromIndex, int aToIndex, Object[] b, int bFromIndex, int bToIndex) {
        range(a.length, aFromIndex, aToIndex);
        range(b.length, bFromIndex, bToIndex);
        if (aToIndex - aFromIndex != bToIndex - bFromIndex) {
            return false;
        }
        for (int i = aFromIndex, j = bFromIndex; i < aToIndex; i++, j++) {
            if (!java.util.Objects.equals(a[i], b[j])) {
                return false;
            }
        }
        return true;
    }

    public static int compare(int[] a, int[] b) {
        if (a == b) {
            return 0;
        }
        if (a == null || b == null) {
            return a == null ? -1 : 1;
        }
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return a.length - b.length;
    }
}

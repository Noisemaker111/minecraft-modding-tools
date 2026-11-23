package mmt.standin.java.lang;

/**
 * java.lang.Math members older Javas lack, as the JDK writes them: Java 9's floorMod(long, int), floorDiv(long, int) and
 * multiplyExact(long, int), each the long version with the int widened; Java 18's ceilDiv(int, int) "the smallest
 * (closest to negative infinity) int value that is greater than or equal to the algebraic quotient"; Java 21's clamp,
 * "value clamped between min and max", IllegalArgumentException when min > max (or, for floating point, either is NaN:
 * "min is NaN", "max is NaN"), with -0.0 below 0.0.
 */
public final class Math {

    private Math() {}

    public static int floorMod(long x, int y) {
        return (int) java.lang.Math.floorMod(x, (long) y);
    }

    public static long floorDiv(long x, int y) {
        return java.lang.Math.floorDiv(x, (long) y);
    }

    public static long multiplyExact(long x, int y) {
        return java.lang.Math.multiplyExact(x, (long) y);
    }

    public static int ceilDiv(int x, int y) {
        int q = x / y;
        if ((x ^ y) >= 0 && q * y != x) {
            return q + 1;
        }
        return q;
    }

    public static int clamp(long value, int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException(min + " > " + max);
        }
        return (int) java.lang.Math.min(max, java.lang.Math.max(value, min));
    }

    public static long clamp(long value, long min, long max) {
        if (min > max) {
            throw new IllegalArgumentException(min + " > " + max);
        }
        return java.lang.Math.min(max, java.lang.Math.max(value, min));
    }

    public static double clamp(double value, double min, double max) {
        if (Double.isNaN(min)) {
            throw new IllegalArgumentException("min is NaN");
        }
        if (Double.isNaN(max)) {
            throw new IllegalArgumentException("max is NaN");
        }
        if (Double.compare(min, max) > 0) {
            throw new IllegalArgumentException(min + " > " + max);
        }
        return java.lang.Math.min(max, java.lang.Math.max(value, min));
    }

    public static float clamp(float value, float min, float max) {
        if (Float.isNaN(min)) {
            throw new IllegalArgumentException("min is NaN");
        }
        if (Float.isNaN(max)) {
            throw new IllegalArgumentException("max is NaN");
        }
        if (Float.compare(min, max) > 0) {
            throw new IllegalArgumentException(min + " > " + max);
        }
        return java.lang.Math.min(max, java.lang.Math.max(value, min));
    }
}

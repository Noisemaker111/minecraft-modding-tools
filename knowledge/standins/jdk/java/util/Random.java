package mmt.standin.java.util;

/**
 * Java 17's bounded Random methods (RandomGenerator's default methods, which java.util.Random does not override), as
 * jdk.internal.util.random.RandomSupport writes them: nextInt(origin, bound) checks origin < bound
 * ("bound must be greater than origin") and draws as boundedNextInt does; nextFloat(bound) checks a finite positive
 * bound ("bound must be finite and positive") and scales nextFloat(), nextFloat(origin, bound) checks finite origin < bound
 * and scales into the range; a result that rounds up to the bound is moved just below it (Math.nextDown).
 */
public final class Random {

    private Random() {}

    public static int nextInt(java.util.Random self, int origin, int bound) {
        if (origin >= bound) {
            throw new IllegalArgumentException("bound must be greater than origin");
        }
        int r = self.nextInt();
        int n = bound - origin, m = n - 1;
        if ((n & m) == 0) {
            r = (r & m) + origin;
        } else if (n > 0) {
            for (int u = r >>> 1; u + m - (r = u % n) < 0; u = self.nextInt() >>> 1) {
            }
            r += origin;
        } else {
            while (r < origin || r >= bound) {
                r = self.nextInt();
            }
        }
        return r;
    }

    public static float nextFloat(java.util.Random self, float bound) {
        if (!(0.0f < bound && bound < Float.POSITIVE_INFINITY)) {
            throw new IllegalArgumentException("bound must be finite and positive");
        }
        float r = self.nextFloat() * bound;
        return r >= bound ? Math.nextDown(bound) : r;
    }

    public static float nextFloat(java.util.Random self, float origin, float bound) {
        if (!(Float.NEGATIVE_INFINITY < origin && origin < bound && bound < Float.POSITIVE_INFINITY)) {
            throw new IllegalArgumentException("bound must be greater than origin");
        }
        float r = self.nextFloat();
        if (bound - origin < Float.POSITIVE_INFINITY) {
            r = r * (bound - origin) + origin;
        } else {
            float halfOrigin = 0.5f * origin;
            r = (r * (0.5f * bound - halfOrigin) + halfOrigin) * 2.0f;
        }
        return r >= bound ? Math.nextDown(bound) : r;
    }
}

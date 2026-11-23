package mmt.standin.net.minecraft.util;

/**
 * 26.2's Mth on 26.3.
 *
 * Read from both releases: 26.2's smoothstep(x) was x * x * x * (x * (x * 6 - 15) + 10) and smoothstepDerivative(x)
 * 30 * x * x * (x - 1) * (x - 1), both in double. 26.3 computes the same polynomials in float only, so the double ones
 * are written out as 26.2 had them, giving the same doubles.
 *
 * Agrees with NeoForge's 26.2 -> 26.3 primer (https://github.com/neoforged/.github/blob/main/primers/26.3/index.md, CC BY 4.0): "Mth", "smoothstep, smoothstepDerivative now work with floats instead of doubles".
 */
public final class Mth {

    private Mth() {}

    public static double smoothstep(double x) {
        return x * x * x * (x * (x * 6.0 - 15.0) + 10.0);
    }

    public static double smoothstepDerivative(double x) {
        return 30.0 * x * x * (x - 1.0) * (x - 1.0);
    }
}

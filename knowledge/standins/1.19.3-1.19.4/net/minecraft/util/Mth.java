package mmt.standin.net.minecraft.util;

/**
 * 1.19.3's Mth.absFloor(double) and Mth.clamp(long, long, long) on 1.19.4.
 *
 * Read from both releases: 1.19.3's absFloor(x) was (int) x for x >= 0, else (int) (-x + 1.0); clamp(value, min, max) of
 * longs returned min below it, max above it, else the value. 1.19.4 dropped both. These are 1.19.3's bodies.
 *
 * Fabric's 1.19.4 post (https://fabricmc.net/2023/03/01/1194.html) does not mention Mth, and NeoForge's primers begin at 1.20.2 -> 1.20.3; this is from the game's code alone.
 */
public final class Mth {

    private Mth() {}

    public static int absFloor(double x) {
        return (int) (x >= 0.0 ? x : -x + 1.0);
    }

    public static long clamp(long value, long min, long max) {
        if (value < min) {
            return min;
        }
        return value > max ? max : value;
    }
}

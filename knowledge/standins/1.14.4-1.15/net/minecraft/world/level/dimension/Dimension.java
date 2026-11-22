package mmt.standin.net.minecraft.world.level.dimension;

/**
 * 1.14.4's Dimension.getBrightnessRamp() on 1.15.
 *
 * Read from both releases: both keep the 16 brightnesses of the light levels in the dimension's brightnessRamp; 1.14.4
 * returned the array, 1.15 returns one entry, getBrightness(level). This returns the 16 entries as an array.
 *
 * Fabric's 1.15 post (https://fabricmc.net/2019/12/10/115.html) does not mention it; this is from the game's code alone.
 */
public final class Dimension {

    private Dimension() {}

    public static float[] getBrightnessRamp(net.minecraft.world.level.dimension.Dimension self) {
        float[] ramp = new float[16];
        for (int i = 0; i < 16; i++) {
            ramp[i] = self.getBrightness(i);
        }
        return ramp;
    }
}

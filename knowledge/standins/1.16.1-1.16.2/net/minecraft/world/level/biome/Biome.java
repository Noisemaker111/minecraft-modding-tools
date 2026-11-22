package mmt.standin.net.minecraft.world.level.biome;

/**
 * 1.16.1's Biome.getTemperature() on 1.16.2.
 *
 * Read from both releases: 1.16.1's getTemperature() returned the biome's own temperature field, before any height
 * adjustment (getTemperature(BlockPos) adds that). 1.16.2 keeps it in the biome's climate settings and returns it from
 * getBaseTemperature(). This returns that.
 *
 * Fabric's 1.16.2 post (https://fabricmc.net/2020/08/11/1162.html) does not mention it; this is from the game's code alone.
 */
public final class Biome {

    private Biome() {}

    public static float getTemperature(net.minecraft.world.level.biome.Biome self) {
        return self.getBaseTemperature();
    }
}

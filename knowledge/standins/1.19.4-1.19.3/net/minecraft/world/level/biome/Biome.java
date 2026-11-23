package mmt.standin.net.minecraft.world.level.biome;

import net.minecraft.core.BlockPos;

/**
 * 1.19.4's Biome.getPrecipitationAt(pos) on 1.19.3, for a port back.
 *
 * Read from both releases: 1.19.4's getPrecipitationAt(pos) is NONE when the biome has no precipitation
 * (hasPrecipitation()), else SNOW where coldEnoughToSnow(pos), else RAIN. 1.19.3 has no hasPrecipitation(): its biome
 * declares one precipitation, getPrecipitation(), NONE for a biome without any, and decides rain or snow at a position
 * with the same coldEnoughToSnow(pos). So it is NONE where 1.19.3 declares NONE, else the same temperature test.
 *
 * Agrees with Fabric's 1.19.4 post (https://fabricmc.net/2023/03/01/1194.html): "Biome precipitation is now a boolean.
 * Whether it rains or snows is now entirely dependent on temperature and is positional; if the temperature is below
 * `0.15`, it snows."
 */
public final class Biome {

    private Biome() {}

    public static net.minecraft.world.level.biome.Biome.Precipitation getPrecipitationAt(net.minecraft.world.level.biome.Biome self, BlockPos pos) {
        if (self.getPrecipitation() == net.minecraft.world.level.biome.Biome.Precipitation.NONE) {
            return net.minecraft.world.level.biome.Biome.Precipitation.NONE;
        }
        return self.coldEnoughToSnow(pos) ? net.minecraft.world.level.biome.Biome.Precipitation.SNOW : net.minecraft.world.level.biome.Biome.Precipitation.RAIN;
    }
}

package mmt.standin.net.minecraft.world.level.biome;

import net.minecraft.core.BlockPos;

/**
 * 1.19.3's Biome.getPrecipitation() on 1.19.4.
 *
 * Read from both releases: 1.19.3's getPrecipitation() was the biome's declared precipitation, NONE, RAIN or SNOW, one
 * for the whole biome. 1.19.4 keeps only whether it has precipitation (hasPrecipitation()) and works out rain or snow
 * where it falls: getPrecipitationAt(pos) is NONE without precipitation, else SNOW where the temperature is cold enough
 * to snow, else RAIN; the temperature is the biome's own below y 80. Asked at the origin, it is the biome's answer at its
 * own temperature, which is what 1.19.3's declared value followed.
 *
 * Agrees with Fabric's 1.19.4 post (https://fabricmc.net/2023/03/01/1194.html): "Biome precipitation is now a boolean.
 * Whether it rains or snows is now entirely dependent on temperature and is positional; if the temperature is below
 * `0.15`, it snows."
 */
public final class Biome {

    private Biome() {}

    public static net.minecraft.world.level.biome.Biome.Precipitation getPrecipitation(net.minecraft.world.level.biome.Biome self) {
        return self.getPrecipitationAt(BlockPos.ZERO);
    }
}

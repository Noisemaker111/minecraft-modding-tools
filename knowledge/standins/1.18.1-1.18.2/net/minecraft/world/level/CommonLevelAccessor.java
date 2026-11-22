package mmt.standin.net.minecraft.world.level;

import java.util.Optional;
import net.minecraft.core.BlockPos;

/**
 * 1.18.1's CommonLevelAccessor.getBiomeName(BlockPos) on 1.18.2.
 *
 * Read from both releases: 1.18.1's getBiomeName looked the biome at the position (getBiome) up in the level's biome
 * registry and returned its key, empty when the biome was not registered. 1.18.2's getBiome returns a Holder of the
 * biome, which carries its registry key itself (Holder.unwrapKey(), empty for a biome made outside the registry), and
 * dropped getBiomeName. This returns the holder's key.
 *
 * Fabric has no 1.18.2 post of its own (its 1.18 post, https://fabricmc.net/2021/11/30/118.html, covers 1.18.2 and does
 * not mention biomes' holders); this is from the game's code alone.
 */
public final class CommonLevelAccessor {

    private CommonLevelAccessor() {}

    public static Optional getBiomeName(net.minecraft.world.level.CommonLevelAccessor self, BlockPos pos) {
        return self.getBiome(pos).unwrapKey();
    }
}

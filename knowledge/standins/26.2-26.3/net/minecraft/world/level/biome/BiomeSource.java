package mmt.standin.net.minecraft.world.level.biome;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Climate;

/**
 * 26.2's BiomeSource on 26.3.
 *
 * Read from both releases: 26.2's BiomeSource was itself the resolver: getNoiseBiome(quartX, quartY, quartZ, sampler)
 * gave the biome at a quart position for a climate sampler. 26.3 separates the two: createResolver(sampler) makes the
 * BiomeResolver for a sampler, whose getNoiseBiome(quartX, quartY, quartZ) gives the biome, and the source's own searches
 * (findClosestBiome3d, findBiomeHorizontal) make one the same way. So the old method asks a resolver made for the sampler.
 *
 * Agrees with NeoForge's 26.2 -> 26.3 primer (https://github.com/neoforged/.github/blob/main/primers/26.3/index.md, CC BY 4.0): "BiomeSource no longer implements BiomeResolver" and "BiomeResolver#getNoiseBiome no longer takes in the Climate$Sampler".
 */
public final class BiomeSource {

    private BiomeSource() {}

    public static Holder getNoiseBiome(net.minecraft.world.level.biome.BiomeSource self, int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
        return self.createResolver(sampler).getNoiseBiome(quartX, quartY, quartZ);
    }
}

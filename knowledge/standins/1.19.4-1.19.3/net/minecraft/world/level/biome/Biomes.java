package mmt.standin.net.minecraft.world.level.biome;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.19.4's Biomes.CHERRY_GROVE on 1.19.3, for a port back.
 *
 * Read from both releases: each Biomes field is a name, ResourceKey.create(Registries.BIOME, new ResourceLocation(name)),
 * made the same way in both. 1.19.4 registers the cherry grove only with its update_1_20 experiment on; without it the
 * key names no biome. 1.19.3 has no cherry grove, so the same key names no biome there either: a lookup by it finds
 * nothing, as in a 1.19.4 world without the experiment.
 */
public final class Biomes {

    private static final ResourceKey CHERRY_GROVE = ResourceKey.create(Registries.BIOME, new ResourceLocation("cherry_grove"));

    private Biomes() {
    }

    public static ResourceKey CHERRY_GROVE() {
        return CHERRY_GROVE;
    }
}

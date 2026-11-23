package mmt.standin.net.minecraft.core.registries;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;

/**
 * 1.19.3's Registries.DIMENSION on 1.19.2, for a port back.
 *
 * Read from both releases: 1.19.3 moved the registry keys from Registry (BLOCK_REGISTRY, DIMENSION_REGISTRY ...) to
 * Registries (BLOCK, DIMENSION ...); the route carries Registries back to Registry. DIMENSION is the key of the
 * dimensions' registry, ResourceKey.createRegistryKey("dimension"), which 1.19.2 names Registry.DIMENSION_REGISTRY.
 *
 * No migration note names this key: Fabric's 1.19.3 post (https://fabricmc.net/2022/11/24/1193.html) describes the
 * registry rework, and NeoForge's primers begin at 1.20.4 -> 1.20.5.
 */
public final class Registries {

    private Registries() {}

    public static ResourceKey DIMENSION() {
        return Registry.DIMENSION_REGISTRY;
    }
}

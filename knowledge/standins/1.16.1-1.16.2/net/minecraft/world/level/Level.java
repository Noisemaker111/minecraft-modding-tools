package mmt.standin.net.minecraft.world.level;

import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * 1.16.1's Level.dimensionTypeKey() on 1.16.2.
 *
 * Read from both releases: 1.16.1's Level was made with the key of its dimension type (the level stem's type: overworld,
 * the_nether, the_end, or a data pack's) and returned it. 1.16.2 keeps the DimensionType itself (dimensionType()), whose
 * key is its entry in the server's dimension type registry (registryAccess().dimensionTypes()). On a server level this
 * looks it up there; elsewhere, or for a type not registered, it gives the key 1.16.1 gave the three vanilla levels
 * (DimensionType.OVERWORLD_LOCATION, NETHER_LOCATION, END_LOCATION), or the dimension's own name.
 *
 * Fabric's 1.16.2 post (https://fabricmc.net/2020/08/11/1162.html) does not mention it; this is from the game's code alone.
 */
public final class Level {

    private Level() {}

    public static ResourceKey dimensionTypeKey(net.minecraft.world.level.Level self) {
        if (self instanceof ServerLevel) {
            Optional key = ((ServerLevel) self).getServer().registryAccess().dimensionTypes().getResourceKey(self.dimensionType());
            if (key.isPresent()) {
                return (ResourceKey) key.get();
            }
        }
        if (self.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
            return DimensionType.OVERWORLD_LOCATION;
        }
        if (self.dimension() == net.minecraft.world.level.Level.NETHER) {
            return DimensionType.NETHER_LOCATION;
        }
        if (self.dimension() == net.minecraft.world.level.Level.END) {
            return DimensionType.END_LOCATION;
        }
        return ResourceKey.create(Registry.DIMENSION_TYPE_REGISTRY, self.dimension().location());
    }
}

package mmt.standin.net.minecraft.client.renderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * 1.21.5's DimensionSpecialEffects on 1.21.6.
 *
 * Read from both releases: 1.21.5's effects held the cloud level they were made with (the overworld's 192, the nether's
 * and end's NaN, meaning no clouds), returned by getCloudHeight(). 1.21.6 moved the height to the dimension type
 * (DimensionType.cloudHeight(), empty for none; the overworld's is DimensionDefaults.OVERWORLD_CLOUD_HEIGHT, 192), and a
 * type's effects are DimensionSpecialEffects.forType(type). So an effects' cloud height is that of the dimension type it
 * is the effects of, the client's current one first, NaN when it has none.
 *
 * Agrees with NeoForge's 1.21.5 -> 1.21.6 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.6/index.md, CC BY 4.0): "DimensionSpecialEffects no longer takes in the current cloud level and whether there is a ground", and the
 * dimension type's cloud height.
 */
public final class DimensionSpecialEffects {

    private DimensionSpecialEffects() {}

    public static float getCloudHeight(net.minecraft.client.renderer.DimensionSpecialEffects self) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return Float.NaN;
        }
        if (level.effects() == self) {
            return height(level.dimensionType());
        }
        for (Object type : level.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE).stream().toList()) {
            if (net.minecraft.client.renderer.DimensionSpecialEffects.forType((DimensionType) type) == self) {
                return height((DimensionType) type);
            }
        }
        return Float.NaN;
    }

    private static float height(DimensionType type) {
        return type.cloudHeight().isPresent() ? ((Integer) type.cloudHeight().get()).floatValue() : Float.NaN;
    }
}

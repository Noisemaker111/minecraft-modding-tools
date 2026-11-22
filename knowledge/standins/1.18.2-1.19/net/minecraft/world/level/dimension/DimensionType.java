package mmt.standin.net.minecraft.world.level.dimension;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;

/**
 * 1.18.2's DimensionType effects ids on 1.19.
 *
 * Read from both releases: 1.18.2's DimensionType.OVERWORLD_EFFECTS, NETHER_EFFECTS and END_EFFECTS were the ids of the
 * sky and fog effects (minecraft:overworld, the_nether, the_end); 1.19 moved the same three ids to BuiltinDimensionTypes.
 *
 * Fabric's 1.19 post (https://fabricmc.net/2022/06/07/119.html) does not mention the effects ids, and NeoForge's primers begin at 1.20.2 -> 1.20.3; this is from the game's code alone.
 */
public final class DimensionType {

    private DimensionType() {}

    public static ResourceLocation OVERWORLD_EFFECTS() {
        return BuiltinDimensionTypes.OVERWORLD_EFFECTS;
    }

    public static ResourceLocation NETHER_EFFECTS() {
        return BuiltinDimensionTypes.NETHER_EFFECTS;
    }

    public static ResourceLocation END_EFFECTS() {
        return BuiltinDimensionTypes.END_EFFECTS;
    }
}

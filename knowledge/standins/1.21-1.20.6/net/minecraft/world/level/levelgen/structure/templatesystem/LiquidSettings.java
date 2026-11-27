package mmt.standin.net.minecraft.world.level.levelgen.structure.templatesystem;

/**
 * 1.21's LiquidSettings on 1.20.6, for a port back: the enum itself, which 1.20.6 does not have yet.
 *
 * Read from both releases: 1.21's IGNORE_WATERLOGGING ("ignore_waterlogging") and APPLY_WATERLOGGING
 * ("apply_waterlogging"), read by StringRepresentable.fromValues, choose whether a structure's blocks take the water
 * already where they are placed (StructurePlaceSettings.shouldApplyWaterlogging, default APPLY). 1.20.6's structures
 * always take it, as APPLY does: what reads a LiquidSettings here (the stand-ins for PoolElementStructurePiece,
 * StructurePlaceSettings, JigsawStructure and JigsawPlacement) places as APPLY whatever it holds.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings` - The settings to apply for blocks spawning with liquids within them".
 */
public enum LiquidSettings implements net.minecraft.util.StringRepresentable {
    IGNORE_WATERLOGGING("ignore_waterlogging"),
    APPLY_WATERLOGGING("apply_waterlogging");

    public static com.mojang.serialization.Codec CODEC = net.minecraft.util.StringRepresentable.fromValues(LiquidSettings::values);

    private final String name;

    LiquidSettings(String name) {
        this.name = name;
    }

    public String getSerializedName() {
        return name;
    }
}

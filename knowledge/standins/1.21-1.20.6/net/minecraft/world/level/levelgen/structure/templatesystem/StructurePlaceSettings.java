package mmt.standin.net.minecraft.world.level.levelgen.structure.templatesystem;

/**
 * 1.21's StructurePlaceSettings.setLiquidSettings and shouldApplyWaterlogging on 1.20.6, for a port back: 1.20.6 places
 * every structure as APPLY_WATERLOGGING (see the stand-in LiquidSettings), so the setting is kept nowhere and asked as that.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings` - The settings to apply for blocks spawning with liquids within them".
 */
public final class StructurePlaceSettings {

    private StructurePlaceSettings() {}

    public static net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings setLiquidSettings(net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings self, LiquidSettings liquids) {
        return self;
    }

    public static boolean shouldApplyWaterlogging(net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings self) {
        return true;
    }
}

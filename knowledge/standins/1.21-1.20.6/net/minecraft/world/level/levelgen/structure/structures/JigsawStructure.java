package mmt.standin.net.minecraft.world.level.levelgen.structure.structures;

/**
 * 1.21's JigsawStructure constructor with its DimensionPadding and LiquidSettings on 1.20.6, for a port back: 1.20.6's
 * structure from the rest, which places as ZERO padding and APPLY_WATERLOGGING (see those stand-ins).
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`net.minecraft.world.level.levelgen.structure.structures.JigsawStructure` now takes in `LiquidSettings`".
 */
public final class JigsawStructure {

    private JigsawStructure() {}

    public static net.minecraft.world.level.levelgen.structure.structures.JigsawStructure init(net.minecraft.world.level.levelgen.structure.Structure.StructureSettings settings, net.minecraft.core.Holder startPool, java.util.Optional startJigsawName, int maxDepth, net.minecraft.world.level.levelgen.heightproviders.HeightProvider startHeight, boolean useExpansionHack, java.util.Optional projectStartToHeightmap, int maxDistanceFromCenter, java.util.List poolAliases, mmt.standin.net.minecraft.world.level.levelgen.structure.pools.DimensionPadding padding, mmt.standin.net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings liquids) {
        return new net.minecraft.world.level.levelgen.structure.structures.JigsawStructure(settings, startPool, startJigsawName, maxDepth, startHeight, useExpansionHack, projectStartToHeightmap, maxDistanceFromCenter, poolAliases);
    }
}

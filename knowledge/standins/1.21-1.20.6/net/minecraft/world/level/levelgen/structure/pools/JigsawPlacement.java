package mmt.standin.net.minecraft.world.level.levelgen.structure.pools;

/**
 * 1.21's JigsawPlacement.addPieces with a DimensionPadding and LiquidSettings on 1.20.6, for a port back: 1.20.6's
 * addPieces from the rest, which places as ZERO padding and APPLY_WATERLOGGING (see those stand-ins).
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement#addPieces` now takes in a `DimensionPadding` when determining the starting generation point".
 */
public final class JigsawPlacement {

    private JigsawPlacement() {}

    public static java.util.Optional addPieces(net.minecraft.world.level.levelgen.structure.Structure.GenerationContext context, net.minecraft.core.Holder startPool, java.util.Optional startJigsawName, int maxDepth, net.minecraft.core.BlockPos position, boolean useExpansionHack, java.util.Optional projectStartToHeightmap, int maxDistanceFromCenter, net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup aliases, DimensionPadding padding, mmt.standin.net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings liquids) {
        return net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement.addPieces(context, startPool, startJigsawName, maxDepth, position, useExpansionHack, projectStartToHeightmap, maxDistanceFromCenter, aliases);
    }
}

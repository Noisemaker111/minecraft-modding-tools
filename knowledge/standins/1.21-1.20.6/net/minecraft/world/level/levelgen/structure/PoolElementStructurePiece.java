package mmt.standin.net.minecraft.world.level.levelgen.structure;

/**
 * 1.21's PoolElementStructurePiece(..., LiquidSettings) on 1.20.6, for a port back: 1.20.6's piece, which places as
 * APPLY_WATERLOGGING (see the stand-in LiquidSettings).
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece` now takes in `LiquidSettings`".
 */
public final class PoolElementStructurePiece {

    private PoolElementStructurePiece() {}

    public static net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece init(net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager templates, net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement element, net.minecraft.core.BlockPos position, int groundLevelDelta, net.minecraft.world.level.block.Rotation rotation, net.minecraft.world.level.levelgen.structure.BoundingBox box, mmt.standin.net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings liquids) {
        return new net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece(templates, element, position, groundLevelDelta, rotation, box);
    }
}

package mmt.standin.net.minecraft.world.level.levelgen.structure.pools;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;

/**
 * 1.20.2's JigsawPlacement on 1.20.3.
 *
 * Read from both releases: 1.20.2's addPieces(context, startPool, startJigsawName, maxDepth, pos, useExpansionHack,
 * projectStartToHeightmap, maxDistanceFromCenter) placed a jigsaw structure from its pools as named. 1.20.3 lets a
 * structure swap pools by alias and takes the lookup last (PoolAliasLookup), PoolAliasLookup.EMPTY naming every pool as
 * itself. 1.20.2 had no aliases, so the old method is the new one with the empty lookup.
 *
 * NeoForge's primers begin at 1.20.4 -> 1.20.5, and Fabric's 1.20.3 post (https://fabricmc.net/2023/11/30/1203.html)
 * does not mention jigsaw pools; this is from the game's code alone.
 */
public final class JigsawPlacement {

    private JigsawPlacement() {}

    public static Optional addPieces(Structure.GenerationContext context, Holder startPool, Optional startJigsawName, int maxDepth, BlockPos pos, boolean useExpansionHack, Optional projectStartToHeightmap, int maxDistanceFromCenter) {
        return net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement.addPieces(context, startPool, startJigsawName, maxDepth, pos, useExpansionHack, projectStartToHeightmap, maxDistanceFromCenter, PoolAliasLookup.EMPTY);
    }
}

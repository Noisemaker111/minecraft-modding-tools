package mmt.standin.net.minecraft.world.level.chunk;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 1.21.4's ChunkAccess on 1.21.5.
 *
 * Read from both releases: 1.21.4's setBlockState(pos, state, isMoving) told the old block's onRemove and the new one's
 * onPlace whether a piston moved it (Level.setBlock passed (flags & 64) != 0); onRemove updated neighbours and dropped a
 * container's contents. 1.21.5's setBlockState(pos, state, flags) reads the same piston bit (Block.UPDATE_MOVE_BY_PISTON,
 * 64), updates neighbours (affectNeighborsAfterRemoval) only with Block.UPDATE_NEIGHBORS (1), and runs the block entity's
 * removal and onPlace unless 256 or 512 are set. So the old call is flags 1, plus 64 when moving.
 *
 * Agrees with NeoForge's 1.21.4 -> 1.21.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.5/index.md, CC BY 4.0): "ChunkAccess#setBlockState now takes in the block flags instead of a boolean" and "onRemove ... -> affectNeighborsAfterRemoval".
 */
public final class ChunkAccess {

    private ChunkAccess() {}

    public static BlockState setBlockState(net.minecraft.world.level.chunk.ChunkAccess self, BlockPos pos, BlockState state, boolean isMoving) {
        return self.setBlockState(pos, state, Block.UPDATE_NEIGHBORS | (isMoving ? Block.UPDATE_MOVE_BY_PISTON : 0));
    }
}

package mmt.standin.net.minecraft.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 1.21.3's Block on 1.21.4.
 *
 * Read from both releases: 1.21.3's getCloneItemStack(level, pos, state) gave the stack a pick-block makes of the
 * block (by default one of its item); a creative pick with control held added the block entity's data afterwards.
 * 1.21.4 takes a fourth argument, whether to include that data, and is reached publicly through
 * BlockState.getCloneItemStack(level, pos, includeData), which asks the state's block with the state. Without the
 * data, as 1.21.3's method gave it, asked of the state given (the block's own state, as every caller in the game
 * passes).
 *
 * Agrees with NeoForge's 1.21.3 -> 1.21.4 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.4/index.md, CC BY 4.0): "Block#getCloneItemStack -> state.BlockBehaviour#getCloneItemStack, now protected"; "$BlockStateBase#getCloneItemStack now takes in a boolean representing if there is infinite materials and whether the current block data should be saved".
 */
public final class Block {

    private Block() {}

    public static ItemStack getCloneItemStack(net.minecraft.world.level.block.Block self, LevelReader level, BlockPos pos, BlockState state) {
        return state.getCloneItemStack(level, pos, false);
    }
}

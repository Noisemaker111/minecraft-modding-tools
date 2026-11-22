package mmt.standin.net.minecraft.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 1.20.2's Block on 1.20.3.
 *
 * Read from both releases: 1.20.2's getCloneItemStack(BlockGetter, pos, state) gave the stack a pick-block makes, the
 * block's own item (new ItemStack(this)) unless a block's class said otherwise. 1.20.3 asks the same of a LevelReader
 * (getCloneItemStack(LevelReader, pos, state)), which the levels the game passes are. So the old method asks the new one
 * when the getter is a LevelReader, and otherwise gives what Block itself gave, the block's own item.
 *
 * NeoForge's primers begin at 1.20.4 -> 1.20.5, and Fabric's 1.20.3 post (https://fabricmc.net/2023/11/30/1203.html)
 * does not mention pick-block stacks; this is from the game's code alone.
 */
public final class Block {

    private Block() {}

    public static ItemStack getCloneItemStack(net.minecraft.world.level.block.Block self, BlockGetter getter, BlockPos pos, BlockState state) {
        if (getter instanceof LevelReader level) {
            return self.getCloneItemStack(level, pos, state);
        }
        return new ItemStack(self);
    }
}

package mmt.standin.net.minecraft.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/**
 * 1.14.4's Block.dropResources(BlockState, LootContext.Builder) on 1.15.
 *
 * Read from both releases: 1.14.4's took a loot context builder, rolled the state's drops with it
 * (BlockState.getDrops(builder)), popped each at the builder's BLOCK_POS in its level, and called spawnAfterBreak with
 * an empty tool. 1.15 kept every piece (getDrops, popResource, spawnAfterBreak, the builder's getLevel and
 * getParameter) and dropped only this overload. This does the same.
 *
 * Fabric's 1.15 post (https://fabricmc.net/2019/12/10/115.html) does not mention it; this is from the game's code alone.
 */
public final class Block {

    private Block() {}

    public static void dropResources(BlockState state, LootContext.Builder builder) {
        ServerLevel level = builder.getLevel();
        BlockPos pos = (BlockPos) builder.getParameter(LootContextParams.BLOCK_POS);
        for (Object drop : state.getDrops(builder)) {
            net.minecraft.world.level.block.Block.popResource(level, pos, (ItemStack) drop);
        }
        state.spawnAfterBreak(level, pos, ItemStack.EMPTY);
    }
}

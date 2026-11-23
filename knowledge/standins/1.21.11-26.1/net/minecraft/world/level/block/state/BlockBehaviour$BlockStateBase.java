package mmt.standin.net.minecraft.world.level.block.state;

import java.util.stream.Stream;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * 1.21.11's BlockBehaviour.BlockStateBase.getTags() on 26.1.
 *
 * Read from both releases: 1.21.11's getTags() answered the tags of the state's block, read from the block's built-in
 * registry holder. 26.1's BlockStateBase implements TypedInstance<Block>, whose tags() answers the tags of the same
 * holder (typeHolder()).
 *
 * Agrees with NeoForge's 1.21.11 -> 26.1 primer (https://github.com/neoforged/.github/blob/main/primers/26.1/index.md, CC BY 4.0):
 * "BlockBehaviour$BlockStateBase now implements TypedInstance<Block>: getBlockHolder -> typeHolder, getTags -> tags".
 */
public final class BlockBehaviour$BlockStateBase {

    private BlockBehaviour$BlockStateBase() {}

    public static Stream getTags(BlockBehaviour.BlockStateBase self) {
        return self.tags();
    }
}

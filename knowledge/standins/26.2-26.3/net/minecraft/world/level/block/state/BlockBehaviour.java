package mmt.standin.net.minecraft.world.level.block.state;

import com.mojang.serialization.MapCodec;
import java.util.function.Function;

/**
 * 26.2's BlockBehaviour.simpleCodec(factory) on 26.3.
 *
 * Read from both releases: 26.2's simpleCodec(factory) built the MapCodec a block class returned from codec(), reading
 * the block's Properties (propertiesCodec) and calling the factory; the game used those codecs for its block type
 * registry. 26.3 deleted the block type registry, Block.codec() and propertiesCodec(), and nothing in 26.3 encodes or
 * decodes a block: a mod's CODEC field and codec() method are left with no reader. So this gives the field a codec that
 * holds nothing (MapCodec.unit) and, should anything decode it, fails there rather than make a block outside the
 * registry.
 *
 * Agrees with NeoForge's 26.2 -> 26.3 primer (https://github.com/neoforged/.github/blob/main/primers/26.3/index.md, CC BY 4.0), "The Death of the Unused Block Types": "The block type MapCodec registry, and subsequently Block#codec, have been completely removed. As such, any codec overrides or direct MapCodecs should be deleted as well", its example deleting "public static final MapCodec<ExampleBlock> CODEC = BlockBehaviour.simpleCodec(ExampleBlock::new);".
 */
public final class BlockBehaviour {

    private BlockBehaviour() {}

    public static MapCodec simpleCodec(Function factory) {
        return MapCodec.unit(() -> {
            throw new UnsupportedOperationException("26.3 has no block codecs: Block#codec and the block type registry were removed");
        });
    }
}

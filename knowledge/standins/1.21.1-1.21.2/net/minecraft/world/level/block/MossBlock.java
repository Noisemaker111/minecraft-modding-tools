package mmt.standin.net.minecraft.world.level.block;

import net.minecraft.data.worldgen.features.CaveFeatures;
import net.minecraft.world.level.block.BonemealableFeaturePlacerBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * 1.21.1's new MossBlock(Properties) on 1.21.2, where the route takes MossBlock to BonemealableFeaturePlacerBlock.
 *
 * Read from both releases: 1.21.1's MossBlock, bonemealed with air above, placed the configured feature
 * CaveFeatures.MOSS_PATCH_BONEMEAL one block up. 1.21.2's BonemealableFeaturePlacerBlock does the same for the feature it
 * is made with, and Blocks.MOSS_BLOCK is new BonemealableFeaturePlacerBlock(CaveFeatures.MOSS_PATCH_BONEMEAL, properties).
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "BonemealableFeaturePlacerBlock - A block that places a configured feature and can be bonemealed".
 */
public final class MossBlock {

    private MossBlock() {}

    public static BonemealableFeaturePlacerBlock init(BlockBehaviour.Properties properties) {
        return new BonemealableFeaturePlacerBlock(CaveFeatures.MOSS_PATCH_BONEMEAL, properties);
    }
}

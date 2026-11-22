package mmt.standin.net.minecraft.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.util.RandomSource;

/**
 * 26.1.2's OrderedSubmitNodeCollector on 26.2.
 *
 * Read from both releases: 26.1.2's submitBreakingBlockModel(pose, model, seed, progress) kept the model and seed, and
 * BlockFeatureRenderer drew the breaking overlay over the parts the model gave for its random
 * (RandomSource.createThreadLocalInstance) seeded with it
 * (random.setSeed(seed); model.collectParts(random, parts)). 26.2 takes those parts instead
 * (submitBreakingBlockModel(pose, parts, progress)). So the old method collects the model's parts for a random of that
 * seed and submits them.
 *
 * Agrees with NeoForge's 26.1.2 -> 26.2 primer (https://github.com/neoforged/.github/blob/main/primers/26.2/index.md, CC BY 4.0): "submitBreakingBlockModel now takes in a list of BlockStatModelParts instead of a BlockStateModel and a long seed".
 */
public final class OrderedSubmitNodeCollector {

    private OrderedSubmitNodeCollector() {}

    public static void submitBreakingBlockModel(net.minecraft.client.renderer.OrderedSubmitNodeCollector self, PoseStack pose, BlockStateModel model, long seed, int progress) {
        List parts = new ArrayList();
        model.collectParts(RandomSource.createThreadLocalInstance(seed), parts);
        self.submitBreakingBlockModel(pose, parts, progress);
    }
}

package mmt.standin.net.minecraft.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Proxy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 1.21.11's OrderedSubmitNodeCollector on 26.1.
 *
 * Read from both releases: 1.21.11's submitBlock(pose, state, light, overlay, outlineColor) drew a block state where an
 * entity is drawn: its block model (SubmitNodeCollection kept a BlockSubmit that BlockFeatureRenderer drew) and its special
 * renderer if it had one (SpecialBlockModelRenderer.renderByBlock). 26.1 drops it; the game's own renderers resolve the
 * state into a BlockModelRenderState (BlockModelResolver.update(renderState, state, displayContext), the model set's model
 * for the state with seed 42) and submit that (BlockModelRenderState.submit(pose, collector, light, overlay, outlineColor):
 * the model's parts through submitBlockModel, then the special renderer), as AbstractMinecartRenderer does with its
 * displayed block. So the old method resolves the state the same way and submits it here; a collector of one order (what
 * order(n) returns, not itself a SubmitNodeCollector) takes it as the collector whose every order is itself.
 *
 * Agrees with NeoForge's 1.21.11 -> 26.1 primer (https://github.com/neoforged/.github/blob/main/primers/26.1/index.md, CC BY 4.0): "OrderedSubmitNodeCollector: submitBlock is removed" and "submitBlockModel now takes in a list of BlockStateModelParts instead of the BlockStateModel".
 */
public final class OrderedSubmitNodeCollector {

    private OrderedSubmitNodeCollector() {}

    public static void submitBlock(net.minecraft.client.renderer.OrderedSubmitNodeCollector self, PoseStack pose, BlockState state, int light, int overlay, int outlineColor) {
        BlockModelRenderState block = new BlockModelRenderState();
        new BlockModelResolver(Minecraft.getInstance().getModelManager()).update(block, state, BlockDisplayContext.create());
        block.submit(pose, collector(self), light, overlay, outlineColor);
    }

    private static SubmitNodeCollector collector(net.minecraft.client.renderer.OrderedSubmitNodeCollector self) {
        if (self instanceof SubmitNodeCollector all) {
            return all;
        }
        return (SubmitNodeCollector) Proxy.newProxyInstance(SubmitNodeCollector.class.getClassLoader(), new Class[] {SubmitNodeCollector.class},
            (proxy, method, args) -> method.getName().equals("order") && method.getParameterCount() == 1 ? self : method.invoke(self, args));
    }
}

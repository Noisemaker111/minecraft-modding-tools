package mmt.standin.net.minecraft.client.renderer;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 1.21.1's ItemBlockRenderTypes.getRenderType(BlockState, boolean) and getRenderType(ItemStack, boolean) on 1.21.2.
 *
 * Read from both releases: 1.21.1 gave a translucent block or item Sheets.translucentCullBlockSheet() or, with shader
 * transparency on and the boolean false, Sheets.translucentItemSheet(), and anything else the cutout block sheet. 1.21.2
 * dropped translucentCullBlockSheet and the boolean: a translucent block or item gets translucentItemSheet() and the rest
 * the cutout sheet, the one translucent sheet left. So the old calls are the new ones.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "ItemBlockRenderTypes#getRenderType no longer takes in a boolean indicating whether to use the translucent render type".
 */
public final class ItemBlockRenderTypes {

    private ItemBlockRenderTypes() {}

    public static RenderType getRenderType(BlockState state, boolean cull) {
        return net.minecraft.client.renderer.ItemBlockRenderTypes.getRenderType(state);
    }

    public static RenderType getRenderType(ItemStack stack, boolean cull) {
        return net.minecraft.client.renderer.ItemBlockRenderTypes.getRenderType(stack);
    }
}

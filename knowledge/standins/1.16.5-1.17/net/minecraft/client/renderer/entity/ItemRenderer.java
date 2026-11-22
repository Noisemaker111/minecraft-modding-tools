package mmt.standin.net.minecraft.client.renderer.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 1.16.5's ItemRenderer.renderStatic and renderAndDecorateItem(LivingEntity, ...) on 1.17.
 *
 * Read from both releases: 1.17 added a seed to each, which picks the model of an item whose model varies; its own
 * calls without one (renderAndDecorateItem(stack, x, y), renderAndDecorateFakeItem, and every other path that had no
 * seed before) pass 0. These pass 0 too.
 *
 * Fabric's 1.17 post for developers (https://fabricmc.net/2021/05/27/117-for-developers.html) covers Java 16, Fabric API and
 * Yarn, not this; this is from the game's code alone.
 */
public final class ItemRenderer {

    private ItemRenderer() {}

    public static void renderStatic(net.minecraft.client.renderer.entity.ItemRenderer self, ItemStack stack, ItemTransforms.TransformType transform, int light, int overlay, PoseStack pose, MultiBufferSource buffers) {
        self.renderStatic(stack, transform, light, overlay, pose, buffers, 0);
    }

    public static void renderStatic(net.minecraft.client.renderer.entity.ItemRenderer self, LivingEntity entity, ItemStack stack, ItemTransforms.TransformType transform, boolean leftHand, PoseStack pose, MultiBufferSource buffers, Level level, int light, int overlay) {
        self.renderStatic(entity, stack, transform, leftHand, pose, buffers, level, light, overlay, 0);
    }

    public static void renderAndDecorateItem(net.minecraft.client.renderer.entity.ItemRenderer self, LivingEntity entity, ItemStack stack, int x, int y) {
        self.renderAndDecorateItem(entity, stack, x, y, 0);
    }
}

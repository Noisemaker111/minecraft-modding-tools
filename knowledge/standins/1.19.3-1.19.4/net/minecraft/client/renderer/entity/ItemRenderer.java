package mmt.standin.net.minecraft.client.renderer.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.item.ItemColors;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 1.19.3's ItemRenderer GUI item drawing and constructor on 1.19.4.
 *
 * Read from both releases: 1.19.3's renderGuiItem(stack, x, y), renderAndDecorateItem(stack, x, y[, seed[, guiOffset]]),
 * renderAndDecorateItem(entity, stack, x, y, seed), renderAndDecorateFakeItem(stack, x, y) and
 * renderGuiItemDecorations(font, stack, x, y[, text]) drew at (x, y) and the depth 100 + the renderer's blitOffset (0
 * unless a caller raised it), on RenderSystem's model view stack. 1.19.4's take a PoseStack first and draw at (x, y) and
 * depth 100 within it; blitOffset is gone. A fresh PoseStack is depth 0, the old blitOffset's start. The player,
 * the level and the entity's level that 1.19.3 read from Minecraft 1.19.4 reads from the Minecraft the renderer now
 * keeps, which its constructor takes first: the old constructor is the new one given the running Minecraft.
 *
 * Agrees with Fabric's 1.19.4 post (https://fabricmc.net/2023/03/01/1194.html): "Most rendering methods now take
 * `MatrixStack` if they did not already." (PoseStack in Mojang's names.)
 */
public final class ItemRenderer {

    private ItemRenderer() {}

    public static net.minecraft.client.renderer.entity.ItemRenderer init(TextureManager textures, ModelManager models, ItemColors colors, BlockEntityWithoutLevelRenderer blockEntities) {
        return new net.minecraft.client.renderer.entity.ItemRenderer(Minecraft.getInstance(), textures, models, colors, blockEntities);
    }

    public static void renderGuiItem(net.minecraft.client.renderer.entity.ItemRenderer self, ItemStack stack, int x, int y) {
        self.renderGuiItem(new PoseStack(), stack, x, y);
    }

    public static void renderAndDecorateItem(net.minecraft.client.renderer.entity.ItemRenderer self, ItemStack stack, int x, int y) {
        self.renderAndDecorateItem(new PoseStack(), stack, x, y);
    }

    public static void renderAndDecorateItem(net.minecraft.client.renderer.entity.ItemRenderer self, ItemStack stack, int x, int y, int seed) {
        self.renderAndDecorateItem(new PoseStack(), stack, x, y, seed);
    }

    public static void renderAndDecorateItem(net.minecraft.client.renderer.entity.ItemRenderer self, ItemStack stack, int x, int y, int seed, int guiOffset) {
        self.renderAndDecorateItem(new PoseStack(), stack, x, y, seed, guiOffset);
    }

    public static void renderAndDecorateItem(net.minecraft.client.renderer.entity.ItemRenderer self, LivingEntity entity, ItemStack stack, int x, int y, int seed) {
        self.renderAndDecorateItem(new PoseStack(), entity, stack, x, y, seed);
    }

    public static void renderAndDecorateFakeItem(net.minecraft.client.renderer.entity.ItemRenderer self, ItemStack stack, int x, int y) {
        self.renderAndDecorateFakeItem(new PoseStack(), stack, x, y);
    }

    public static void renderGuiItemDecorations(net.minecraft.client.renderer.entity.ItemRenderer self, Font font, ItemStack stack, int x, int y) {
        self.renderGuiItemDecorations(new PoseStack(), font, stack, x, y);
    }

    public static void renderGuiItemDecorations(net.minecraft.client.renderer.entity.ItemRenderer self, Font font, ItemStack stack, int x, int y, String text) {
        self.renderGuiItemDecorations(new PoseStack(), font, stack, x, y, text);
    }
}

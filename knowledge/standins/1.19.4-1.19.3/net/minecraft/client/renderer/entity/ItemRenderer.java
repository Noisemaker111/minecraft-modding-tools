package mmt.standin.net.minecraft.client.renderer.entity;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 1.19.4's ItemRenderer.renderAndDecorateItem(pose, ...) and renderGuiItemDecorations(pose, ...) on 1.19.3, for a port
 * back.
 *
 * Read from both releases: 1.19.3 draws a GUI item at the model-view matrix (RenderSystem.getModelViewStack(), translated
 * to x, y and 100 + blitOffset, then scaled to 16) and its count and bars at that same matrix, and lifts nested items
 * by adding 50 to blitOffset. 1.19.4 took a PoseStack instead: it multiplies the pose onto the model-view stack before the
 * same translation and scale, and lifts by translating the pose 50. So the 1.19.4 call is 1.19.3's call of the same
 * arguments with the pose multiplied onto the model-view stack around it: the same matrices reach the shader.
 *
 * One difference stays: 1.19.4 looked the model up in the given entity's level (or the client's level), 1.19.3 in the
 * client's level for every entity, which differs only for an entity in another level than the player's.
 */
public final class ItemRenderer {

    private ItemRenderer() {
    }

    public static void renderAndDecorateItem(net.minecraft.client.renderer.entity.ItemRenderer self, PoseStack pose, ItemStack stack, int x, int y) {
        push(pose);
        self.renderAndDecorateItem(stack, x, y);
        pop();
    }

    public static void renderAndDecorateItem(net.minecraft.client.renderer.entity.ItemRenderer self, PoseStack pose, ItemStack stack, int x, int y, int seed) {
        push(pose);
        self.renderAndDecorateItem(stack, x, y, seed);
        pop();
    }

    public static void renderAndDecorateItem(net.minecraft.client.renderer.entity.ItemRenderer self, PoseStack pose, ItemStack stack, int x, int y, int seed, int guiOffset) {
        push(pose);
        self.renderAndDecorateItem(stack, x, y, seed, guiOffset);
        pop();
    }

    public static void renderAndDecorateItem(net.minecraft.client.renderer.entity.ItemRenderer self, PoseStack pose, LivingEntity entity, ItemStack stack, int x, int y, int seed) {
        push(pose);
        self.renderAndDecorateItem(entity, stack, x, y, seed);
        pop();
    }

    public static void renderGuiItemDecorations(net.minecraft.client.renderer.entity.ItemRenderer self, PoseStack pose, Font font, ItemStack stack, int x, int y) {
        push(pose);
        self.renderGuiItemDecorations(font, stack, x, y);
        pop();
    }

    public static void renderGuiItemDecorations(net.minecraft.client.renderer.entity.ItemRenderer self, PoseStack pose, Font font, ItemStack stack, int x, int y, String text) {
        push(pose);
        self.renderGuiItemDecorations(font, stack, x, y, text);
        pop();
    }

    private static void push(PoseStack pose) {
        PoseStack view = RenderSystem.getModelViewStack();
        view.pushPose();
        view.mulPoseMatrix(pose.last().pose());
        RenderSystem.applyModelViewMatrix();
    }

    private static void pop() {
        RenderSystem.getModelViewStack().popPose();
        RenderSystem.applyModelViewMatrix();
    }
}

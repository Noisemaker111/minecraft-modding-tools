package mmt.standin.net.minecraft.client.renderer.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 1.19.4's ItemRenderer GUI item drawing on 1.20.
 *
 * Read from both releases: 1.19.4's ItemRenderer drew items in a GUI with the pose stack it was given:
 * renderGuiItem(pose, stack, x, y) the item's model alone; renderAndDecorateItem(pose, stack, x, y[, seed[, guiOffset]])
 * for the local player in the client's level, renderAndDecorateItem(pose, entity, stack, x, y, seed) for an entity, and
 * renderAndDecorateFakeItem(pose, stack, x, y) for no entity (each the model, as tryRenderGuiItem did);
 * renderGuiItemDecorations(pose, font, stack, x, y[, text]) the count, bar and cooldown. 1.20 moved these onto
 * GuiGraphics: renderItem(stack, x, y[, seed[, guiOffset]]) for the player, renderItem(entity, stack, x, y, seed),
 * renderFakeItem(stack, x, y), and renderItemDecorations(font, stack, x, y[, text]), the same drawing. Each old call is
 * the new one on a GuiGraphics made with 1.20's public constructor over the game's buffer source, its pose set to the old
 * stack's top, flushed after. renderGuiItem drew the model with no entity, which renderFakeItem does.
 *
 * Agrees with Fabric's 1.20 post (https://fabricmc.net/2023/05/25/120.html): "`drawItem`", "`drawItemWithoutEntity`" and
 * "`drawItemInSlot`" among the methods added to DrawContext (GuiGraphics.renderItem, renderFakeItem and
 * renderItemDecorations in Mojang's names).
 */
public final class ItemRenderer {

    private ItemRenderer() {}

    /** A GuiGraphics drawing where the old pose stack stood: 1.20's public constructor, its pose set to the old one's top. */
    static GuiGraphics graphics(PoseStack pose) {
        Minecraft mc = Minecraft.getInstance();
        GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
        graphics.pose().last().pose().set(pose.last().pose());
        graphics.pose().last().normal().set(pose.last().normal());
        return graphics;
    }

    public static void renderGuiItem(net.minecraft.client.renderer.entity.ItemRenderer self, PoseStack pose, ItemStack stack, int x, int y) {
        GuiGraphics graphics = graphics(pose);
        graphics.renderFakeItem(stack, x, y);
        graphics.flush();
    }

    public static void renderAndDecorateItem(net.minecraft.client.renderer.entity.ItemRenderer self, PoseStack pose, ItemStack stack, int x, int y) {
        GuiGraphics graphics = graphics(pose);
        graphics.renderItem(stack, x, y);
        graphics.flush();
    }

    public static void renderAndDecorateItem(net.minecraft.client.renderer.entity.ItemRenderer self, PoseStack pose, ItemStack stack, int x, int y, int seed) {
        GuiGraphics graphics = graphics(pose);
        graphics.renderItem(stack, x, y, seed);
        graphics.flush();
    }

    public static void renderAndDecorateItem(net.minecraft.client.renderer.entity.ItemRenderer self, PoseStack pose, ItemStack stack, int x, int y, int seed, int guiOffset) {
        GuiGraphics graphics = graphics(pose);
        graphics.renderItem(stack, x, y, seed, guiOffset);
        graphics.flush();
    }

    public static void renderAndDecorateItem(net.minecraft.client.renderer.entity.ItemRenderer self, PoseStack pose, LivingEntity entity, ItemStack stack, int x, int y, int seed) {
        GuiGraphics graphics = graphics(pose);
        graphics.renderItem(entity, stack, x, y, seed);
        graphics.flush();
    }

    public static void renderAndDecorateFakeItem(net.minecraft.client.renderer.entity.ItemRenderer self, PoseStack pose, ItemStack stack, int x, int y) {
        GuiGraphics graphics = graphics(pose);
        graphics.renderFakeItem(stack, x, y);
        graphics.flush();
    }

    public static void renderGuiItemDecorations(net.minecraft.client.renderer.entity.ItemRenderer self, PoseStack pose, Font font, ItemStack stack, int x, int y) {
        GuiGraphics graphics = graphics(pose);
        graphics.renderItemDecorations(font, stack, x, y);
        graphics.flush();
    }

    public static void renderGuiItemDecorations(net.minecraft.client.renderer.entity.ItemRenderer self, PoseStack pose, Font font, ItemStack stack, int x, int y, String text) {
        GuiGraphics graphics = graphics(pose);
        graphics.renderItemDecorations(font, stack, x, y, text);
        graphics.flush();
    }
}

package mmt.standin.net.minecraft.client.gui.components;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 1.19.4's Renderable.render(poseStack, mouseX, mouseY, partialTick) on 1.20.
 *
 * Read from both releases: 1.19.4's Renderable (widgets, screens' drawables) drew itself with render(poseStack, ...), each draw at once with the
 * pose's matrix. 1.20's take a GuiGraphics instead (render(graphics, ...)): it carries the pose stack and a buffer source,
 * and flushes it after each draw it is not batching. The old call is the new one on a GuiGraphics made with 1.20's public
 * constructor over the game's buffer source (as GameRenderer makes the frame's), its pose set to the old stack's top,
 * flushed after the widget draws, so what it drew is on screen when the call returns, as before.
 *
 * Agrees with Fabric's 1.20 post (https://fabricmc.net/2023/05/25/120.html): "various rendering methods now take
 * `DrawContext` instead of `MatrixStack`" (GuiGraphics and PoseStack in Mojang's names).
 */
public final class Renderable {

    private Renderable() {}

    /** A GuiGraphics drawing where the old pose stack stood: 1.20's public constructor, its pose set to the old one's top. */
    static GuiGraphics graphics(PoseStack pose) {
        Minecraft mc = Minecraft.getInstance();
        GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
        graphics.pose().last().pose().set(pose.last().pose());
        graphics.pose().last().normal().set(pose.last().normal());
        return graphics;
    }

    public static void render(net.minecraft.client.gui.components.Renderable self, PoseStack pose, int mouseX, int mouseY, float partialTick) {
        GuiGraphics graphics = graphics(pose);
        self.render(graphics, mouseX, mouseY, partialTick);
        graphics.flush();
    }
}

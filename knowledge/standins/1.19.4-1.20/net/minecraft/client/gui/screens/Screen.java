package mmt.standin.net.minecraft.client.gui.screens;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 1.19.4's Screen tooltips and itemRenderer field on 1.20.
 *
 * Read from both releases: 1.19.4's Screen drew tooltips at once with the pose stack it was given:
 * renderTooltip(pose, stack, x, y) the stack's tooltip lines and image, renderTooltip(pose, lines, image, x, y),
 * renderTooltip(pose, component, x, y), renderComponentTooltip(pose, components, x, y) and renderTooltip(pose,
 * charSequences, x, y), each in the screen's font (the client's font, set in init); its itemRenderer field was the
 * client's ItemRenderer. 1.20 moved the tooltips onto GuiGraphics, renderTooltip(font, ...) and
 * renderComponentTooltip(font, ...), the same drawing, and dropped the field. Each old call is the new one in the
 * client's font on a GuiGraphics made with 1.20's public constructor over the game's buffer source, its pose set to the
 * old stack's top, flushed after; the field reads Minecraft.getItemRenderer().
 *
 * Agrees with Fabric's 1.20 post (https://fabricmc.net/2023/05/25/120.html): "`drawTooltip` (replaces
 * `Screen#renderTooltip`)".
 */
public final class Screen {

    private Screen() {}

    /** A GuiGraphics drawing where the old pose stack stood: 1.20's public constructor, its pose set to the old one's top. */
    static GuiGraphics graphics(PoseStack pose) {
        Minecraft mc = Minecraft.getInstance();
        GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
        graphics.pose().last().pose().set(pose.last().pose());
        graphics.pose().last().normal().set(pose.last().normal());
        return graphics;
    }

    public static void renderTooltip(net.minecraft.client.gui.screens.Screen self, PoseStack pose, ItemStack stack, int x, int y) {
        GuiGraphics graphics = graphics(pose);
        graphics.renderTooltip(Minecraft.getInstance().font, stack, x, y);
        graphics.flush();
    }

    public static void renderTooltip(net.minecraft.client.gui.screens.Screen self, PoseStack pose, List lines, Optional image, int x, int y) {
        GuiGraphics graphics = graphics(pose);
        graphics.renderTooltip(Minecraft.getInstance().font, lines, image, x, y);
        graphics.flush();
    }

    public static void renderTooltip(net.minecraft.client.gui.screens.Screen self, PoseStack pose, Component line, int x, int y) {
        GuiGraphics graphics = graphics(pose);
        graphics.renderTooltip(Minecraft.getInstance().font, line, x, y);
        graphics.flush();
    }

    public static void renderComponentTooltip(net.minecraft.client.gui.screens.Screen self, PoseStack pose, List lines, int x, int y) {
        GuiGraphics graphics = graphics(pose);
        graphics.renderComponentTooltip(Minecraft.getInstance().font, lines, x, y);
        graphics.flush();
    }

    public static void renderTooltip(net.minecraft.client.gui.screens.Screen self, PoseStack pose, List lines, int x, int y) {
        GuiGraphics graphics = graphics(pose);
        graphics.renderTooltip(Minecraft.getInstance().font, lines, x, y);
        graphics.flush();
    }

    public static ItemRenderer itemRenderer(net.minecraft.client.gui.screens.Screen self) {
        return Minecraft.getInstance().getItemRenderer();
    }
}

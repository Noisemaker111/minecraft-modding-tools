package mmt.standin.net.minecraft.client.gui;

import java.util.List;
import java.util.function.Function;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ARGB;

/**
 * 1.21.1's GuiGraphics on 1.21.2.
 *
 * Read from both releases: 1.21.1 drew each call at once unless inside drawManaged(r), which flushed, ran r with the
 * per-call flushes off (managed = true) so its draws batched, and flushed again. 1.21.2 batches every draw and has no
 * managed mode (flushIfManaged and flushIfUnmanaged are gone; flush() ends the batch), so drawManaged is the two flushes
 * around r.
 *
 * 1.21.1's sprite and texture blits took a blit offset (z, written as each vertex's depth) and drew with the plain
 * texture shader, a color variant with blending on. 1.21.2's take a Function<ResourceLocation, RenderType> (the game's GUI
 * draws pass RenderType::guiTextured) and an ARGB color (-1 for none), write every vertex at depth 0, and lift a draw by
 * translating the pose, as its own tooltip and item code does. So each old call is the new one with guiTextured, the
 * color its floats make or -1, inside a pose translated by z. renderTooltipInternal takes the tooltip style last, null for
 * the vanilla box, which GuiGraphics passes for an item without one.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "drawManaged is removed", "setColor is removed - Now a parameter within the blit and blitSprite methods", "blit(int, int, int, int, int, TextureAtlasSprite, *) is removed", and blits now take "RenderType::guiTextured" for how to render the texture.
 */
public final class GuiGraphics {

    private static final Function GUI_TEXTURED = texture -> RenderType.guiTextured((ResourceLocation) texture);

    private GuiGraphics() {}

    public static void drawManaged(net.minecraft.client.gui.GuiGraphics self, Runnable runnable) {
        self.flush();
        runnable.run();
        self.flush();
    }

    public static void blit(net.minecraft.client.gui.GuiGraphics self, int x, int y, int z, int width, int height, TextureAtlasSprite sprite) {
        self.pose().pushPose();
        self.pose().translate(0.0F, 0.0F, (float) z);
        self.blitSprite(GUI_TEXTURED, sprite, x, y, width, height);
        self.pose().popPose();
    }

    public static void blit(net.minecraft.client.gui.GuiGraphics self, int x, int y, int z, int width, int height, TextureAtlasSprite sprite, float red, float green, float blue, float alpha) {
        self.pose().pushPose();
        self.pose().translate(0.0F, 0.0F, (float) z);
        self.blitSprite(GUI_TEXTURED, sprite, x, y, width, height, ARGB.colorFromFloat(alpha, red, green, blue));
        self.pose().popPose();
    }

    public static void blitSprite(net.minecraft.client.gui.GuiGraphics self, TextureAtlasSprite sprite, int textureWidth, int textureHeight, int u, int v, int x, int y, int z, int width, int height) {
        self.pose().pushPose();
        self.pose().translate(0.0F, 0.0F, (float) z);
        self.blitSprite(GUI_TEXTURED, sprite, textureWidth, textureHeight, u, v, x, y, width, height, -1);
        self.pose().popPose();
    }

    public static void blitNineSlicedSprite(net.minecraft.client.gui.GuiGraphics self, TextureAtlasSprite sprite, GuiSpriteScaling.NineSlice slice, int x, int y, int z, int width, int height) {
        self.pose().pushPose();
        self.pose().translate(0.0F, 0.0F, (float) z);
        self.blitNineSlicedSprite(GUI_TEXTURED, sprite, slice, x, y, width, height, -1);
        self.pose().popPose();
    }

    public static void innerBlit(net.minecraft.client.gui.GuiGraphics self, ResourceLocation texture, int x1, int x2, int y1, int y2, int z, float u0, float u1, float v0, float v1) {
        self.pose().pushPose();
        self.pose().translate(0.0F, 0.0F, (float) z);
        self.innerBlit(GUI_TEXTURED, texture, x1, x2, y1, y2, u0, u1, v0, v1, -1);
        self.pose().popPose();
    }

    public static void renderTooltipInternal(net.minecraft.client.gui.GuiGraphics self, Font font, List components, int x, int y, ClientTooltipPositioner positioner) {
        self.renderTooltipInternal(font, components, x, y, positioner, null);
    }
}

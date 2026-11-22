package mmt.standin.net.minecraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * 1.15.2's GuiComponent drawing (fill, fillGradient, blit, drawString, drawCenteredString) on 1.16.
 *
 * Read from both releases: 1.15.2 drew each with the current OpenGL model-view matrix; 1.16 takes a PoseStack first,
 * whose top pose is multiplied in as well, and still draws with OpenGL's model-view matrix. A new PoseStack's pose is the
 * identity, so each passes one and draws where 1.15.2 did.
 *
 * Fabric's 1.16 post (https://fabricmc.net/2020/06/23/116.html) does not mention it; this is from the game's code alone.
 */
public final class GuiComponent {

    private GuiComponent() {}

    public static void fill(int x1, int y1, int x2, int y2, int color) {
        net.minecraft.client.gui.GuiComponent.fill(new PoseStack(), x1, y1, x2, y2, color);
    }

    public static void fillGradient(net.minecraft.client.gui.GuiComponent self, int x1, int y1, int x2, int y2, int from, int to) {
        self.fillGradient(new PoseStack(), x1, y1, x2, y2, from, to);
    }

    public static void drawCenteredString(net.minecraft.client.gui.GuiComponent self, Font font, String text, int x, int y, int color) {
        self.drawCenteredString(new PoseStack(), font, text, x, y, color);
    }

    public static void drawString(net.minecraft.client.gui.GuiComponent self, Font font, String text, int x, int y, int color) {
        self.drawString(new PoseStack(), font, text, x, y, color);
    }

    public static void blit(net.minecraft.client.gui.GuiComponent self, int x, int y, int u, int v, int width, int height) {
        self.blit(new PoseStack(), x, y, u, v, width, height);
    }

    public static void blit(int x, int y, int z, int width, int height, TextureAtlasSprite sprite) {
        net.minecraft.client.gui.GuiComponent.blit(new PoseStack(), x, y, z, width, height, sprite);
    }

    public static void blit(int x, int y, int z, float u, float v, int width, int height, int textureHeight, int textureWidth) {
        net.minecraft.client.gui.GuiComponent.blit(new PoseStack(), x, y, z, u, v, width, height, textureHeight, textureWidth);
    }

    public static void blit(int x, int y, int width, int height, float u, float v, int uWidth, int vHeight, int textureWidth, int textureHeight) {
        net.minecraft.client.gui.GuiComponent.blit(new PoseStack(), x, y, width, height, u, v, uWidth, vHeight, textureWidth, textureHeight);
    }

    public static void blit(int x, int y, float u, float v, int width, int height, int textureWidth, int textureHeight) {
        net.minecraft.client.gui.GuiComponent.blit(new PoseStack(), x, y, u, v, width, height, textureWidth, textureHeight);
    }
}

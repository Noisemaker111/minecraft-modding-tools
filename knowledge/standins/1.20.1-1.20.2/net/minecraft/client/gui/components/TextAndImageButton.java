package mmt.standin.net.minecraft.client.gui.components;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.20.1's TextAndImageButton on 1.20.2, which dropped it: a 150 x 20 button with its label on the left and a region of a
 * texture sheet drawn beside it.
 *
 * Read from both releases: 1.20.1's TextAndImageButton drew the plain button (Button.renderWidget), then the sheet region
 * (xTexStart, yTexStart, usedTextureWidth x usedTextureHeight of a textureWidth x textureHeight sheet) at the button's
 * middle moved by xOffset and yOffset, through AbstractWidget.renderTexture: v down one yDiffTex step when hovered or
 * focused and two when inactive, depth test on, blit. Its renderString scrolled the label between x + 2 and the room left
 * of the image. Builder (TextAndImageButton$Builder) set those and built one. 1.20.2 replaced it with SpriteIconButton,
 * which draws a GUI sprite and has no sheet, and dropped renderTexture; Button.renderWidget,
 * AbstractWidget.renderScrollingString and GuiGraphics.blit(texture, x, y, u, v, width, height, textureWidth,
 * textureHeight) are still there, so the old button is 1.20.1's own code on them.
 *
 * Agrees with Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html): "Texture rendering methods have changed;
 * drawTexture calls should be replaced with drawGuiTexture." A mod's sheet has no sprites, so it keeps blit.
 */
public class TextAndImageButton extends Button {

    protected final ResourceLocation resourceLocation;
    protected final int xTexStart;
    protected final int yTexStart;
    protected final int yDiffTex;
    protected final int textureWidth;
    protected final int textureHeight;
    private final int xOffset;
    private final int yOffset;
    private final int usedTextureWidth;
    private final int usedTextureHeight;

    protected TextAndImageButton(Component message, int xTexStart, int yTexStart, int xOffset, int yOffset, int yDiffTex, int usedTextureWidth, int usedTextureHeight, int textureWidth, int textureHeight, ResourceLocation texture, Button.OnPress onPress) {
        super(0, 0, 150, 20, message, onPress, DEFAULT_NARRATION);
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
        this.xTexStart = xTexStart;
        this.yTexStart = yTexStart;
        this.yDiffTex = yDiffTex;
        this.resourceLocation = texture;
        this.xOffset = xOffset;
        this.yOffset = yOffset;
        this.usedTextureWidth = usedTextureWidth;
        this.usedTextureHeight = usedTextureHeight;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
        int v = this.yTexStart;
        if (!this.isActive()) {
            v = this.yTexStart + this.yDiffTex * 2;
        } else if (this.isHoveredOrFocused()) {
            v = this.yTexStart + this.yDiffTex;
        }
        RenderSystem.enableDepthTest();
        int x = this.getX() + (this.width / 2 - this.usedTextureWidth / 2) + this.xOffset;
        int y = this.getY() + this.yOffset;
        graphics.blit(this.resourceLocation, x, y, this.xTexStart, v, this.usedTextureWidth, this.usedTextureHeight, this.textureWidth, this.textureHeight);
    }

    @Override
    public void renderString(GuiGraphics graphics, Font font, int color) {
        int left = this.getX() + 2;
        int right = this.getX() + this.getWidth() - this.usedTextureWidth - 6;
        renderScrollingString(graphics, font, this.getMessage(), left, this.getY(), right, this.getY() + this.getHeight(), color);
    }

    public static TextAndImageButton$Builder builder(Component message, ResourceLocation texture, Button.OnPress onPress) {
        return new TextAndImageButton$Builder(message, texture, onPress);
    }
}

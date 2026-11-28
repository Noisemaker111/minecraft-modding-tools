package mmt.standin.net.minecraft.client.gui.components;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.20.1's ImageButton on 1.20.2, as the old class mods extend and make: a button drawn from a region of a texture sheet.
 *
 * Read from both releases: 1.20.1's ImageButton kept a texture (resourceLocation), the region's corner (xTexStart,
 * yTexStart), the step down the sheet to the hovered and the disabled looks (yDiffTex) and the sheet's size; its
 * constructors defaulted yDiffTex to the button's height, the sheet to 256 x 256 and the message to
 * CommonComponents.EMPTY. renderWidget called AbstractWidget.renderTexture, which moved v down one step when hovered or
 * focused and two when inactive, enabled the depth test and blit the region. 1.20.2's ImageButton takes WidgetSprites and
 * draws the one sprite for its state with blitSprite; no constructor takes a sheet, and renderTexture is gone. The
 * sheet's own drawing is kept here on 1.20.2's GuiGraphics.blit(texture, x, y, u, v, width, height, textureWidth,
 * textureHeight), which 1.20.2 still has; the sprites handed to the new class name the sheet and are never drawn.
 * A button the game made from sprites has no sheet: its fields read as no texture and offsets of 0.
 *
 * Agrees with Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html): "Texture rendering methods have changed;
 * drawTexture calls should be replaced with drawGuiTexture. Note that since the game no longer uses atlases in GUI
 * textures, there is no need to specify UV." A mod's sheet keeps its UVs, so it keeps blit.
 */
public class ImageButton extends net.minecraft.client.gui.components.ImageButton {

    protected ResourceLocation resourceLocation;
    protected int xTexStart;
    protected int yTexStart;
    protected int yDiffTex;
    protected int textureWidth;
    protected int textureHeight;

    public ImageButton(int x, int y, int width, int height, int xTexStart, int yTexStart, ResourceLocation texture, Button.OnPress onPress) {
        this(x, y, width, height, xTexStart, yTexStart, height, texture, 256, 256, onPress);
    }

    public ImageButton(int x, int y, int width, int height, int xTexStart, int yTexStart, int yDiffTex, ResourceLocation texture, Button.OnPress onPress) {
        this(x, y, width, height, xTexStart, yTexStart, yDiffTex, texture, 256, 256, onPress);
    }

    public ImageButton(int x, int y, int width, int height, int xTexStart, int yTexStart, int yDiffTex, ResourceLocation texture, int textureWidth, int textureHeight, Button.OnPress onPress) {
        this(x, y, width, height, xTexStart, yTexStart, yDiffTex, texture, textureWidth, textureHeight, onPress, CommonComponents.EMPTY);
    }

    public ImageButton(int x, int y, int width, int height, int xTexStart, int yTexStart, int yDiffTex, ResourceLocation texture, int textureWidth, int textureHeight, Button.OnPress onPress, Component message) {
        super(x, y, width, height, new WidgetSprites(texture, texture), onPress, message);
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
        this.xTexStart = xTexStart;
        this.yTexStart = yTexStart;
        this.yDiffTex = yDiffTex;
        this.resourceLocation = texture;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int v = this.yTexStart;
        if (!this.isActive()) {
            v = this.yTexStart + this.yDiffTex * 2;
        } else if (this.isHoveredOrFocused()) {
            v = this.yTexStart + this.yDiffTex;
        }
        RenderSystem.enableDepthTest();
        graphics.blit(this.resourceLocation, this.getX(), this.getY(), this.xTexStart, v, this.width, this.height, this.textureWidth, this.textureHeight);
    }

    public static net.minecraft.client.gui.components.ImageButton init(int x, int y, int width, int height, int xTexStart, int yTexStart, ResourceLocation texture, Button.OnPress onPress) {
        return new ImageButton(x, y, width, height, xTexStart, yTexStart, texture, onPress);
    }

    public static net.minecraft.client.gui.components.ImageButton init(int x, int y, int width, int height, int xTexStart, int yTexStart, int yDiffTex, ResourceLocation texture, Button.OnPress onPress) {
        return new ImageButton(x, y, width, height, xTexStart, yTexStart, yDiffTex, texture, onPress);
    }

    public static net.minecraft.client.gui.components.ImageButton init(int x, int y, int width, int height, int xTexStart, int yTexStart, int yDiffTex, ResourceLocation texture, int textureWidth, int textureHeight, Button.OnPress onPress) {
        return new ImageButton(x, y, width, height, xTexStart, yTexStart, yDiffTex, texture, textureWidth, textureHeight, onPress);
    }

    public static net.minecraft.client.gui.components.ImageButton init(int x, int y, int width, int height, int xTexStart, int yTexStart, int yDiffTex, ResourceLocation texture, int textureWidth, int textureHeight, Button.OnPress onPress, Component message) {
        return new ImageButton(x, y, width, height, xTexStart, yTexStart, yDiffTex, texture, textureWidth, textureHeight, onPress, message);
    }

    public static ResourceLocation resourceLocation(net.minecraft.client.gui.components.ImageButton self) {
        return self instanceof ImageButton sheet ? sheet.resourceLocation : null;
    }

    public static int xTexStart(net.minecraft.client.gui.components.ImageButton self) {
        return self instanceof ImageButton sheet ? sheet.xTexStart : 0;
    }

    public static void xTexStart(net.minecraft.client.gui.components.ImageButton self, int value) {
        if (self instanceof ImageButton sheet) {
            sheet.xTexStart = value;
        }
    }

    public static int yTexStart(net.minecraft.client.gui.components.ImageButton self) {
        return self instanceof ImageButton sheet ? sheet.yTexStart : 0;
    }

    public static void yTexStart(net.minecraft.client.gui.components.ImageButton self, int value) {
        if (self instanceof ImageButton sheet) {
            sheet.yTexStart = value;
        }
    }
}

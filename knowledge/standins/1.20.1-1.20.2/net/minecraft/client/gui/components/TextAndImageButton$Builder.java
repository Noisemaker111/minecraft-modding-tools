package mmt.standin.net.minecraft.client.gui.components;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.20.1's TextAndImageButton.Builder on 1.20.2, which dropped it with TextAndImageButton.
 *
 * Read from 1.20.1: the builder kept the label, texture and press action it was made with, and texStart(x, y),
 * offset(x, y), yDiffTex(step), usedTextureSize(w, h) and textureSize(w, h) set the region, its place and the sheet's size,
 * each returning the builder; build() made the button from them. 1.20.2's SpriteIconButton.Builder takes a sprite and no
 * sheet, so the old builder makes the old button (TextAndImageButton's stand-in), as 1.20.1's did.
 */
public class TextAndImageButton$Builder {

    private final Component message;
    private final ResourceLocation resourceLocation;
    private final Button.OnPress onPress;
    private int xTexStart;
    private int yTexStart;
    private int yDiffTex;
    private int usedTextureWidth;
    private int usedTextureHeight;
    private int textureWidth;
    private int textureHeight;
    private int xOffset;
    private int yOffset;

    public TextAndImageButton$Builder(Component message, ResourceLocation texture, Button.OnPress onPress) {
        this.message = message;
        this.resourceLocation = texture;
        this.onPress = onPress;
    }

    public TextAndImageButton$Builder texStart(int x, int y) {
        this.xTexStart = x;
        this.yTexStart = y;
        return this;
    }

    public TextAndImageButton$Builder offset(int x, int y) {
        this.xOffset = x;
        this.yOffset = y;
        return this;
    }

    public TextAndImageButton$Builder yDiffTex(int step) {
        this.yDiffTex = step;
        return this;
    }

    public TextAndImageButton$Builder usedTextureSize(int width, int height) {
        this.usedTextureWidth = width;
        this.usedTextureHeight = height;
        return this;
    }

    public TextAndImageButton$Builder textureSize(int width, int height) {
        this.textureWidth = width;
        this.textureHeight = height;
        return this;
    }

    public TextAndImageButton build() {
        return new TextAndImageButton(this.message, this.xTexStart, this.yTexStart, this.xOffset, this.yOffset, this.yDiffTex, this.usedTextureWidth,
            this.usedTextureHeight, this.textureWidth, this.textureHeight, this.resourceLocation, this.onPress);
    }
}

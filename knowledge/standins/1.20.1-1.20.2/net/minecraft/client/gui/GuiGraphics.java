package mmt.standin.net.minecraft.client.gui;

import com.mojang.math.Divisor;
import it.unimi.dsi.fastutil.ints.IntIterator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * 1.20.1's GuiGraphics.blitNineSliced and blitRepeating on 1.20.2.
 *
 * Read from both releases: 1.20.1's blitNineSliced(texture, x, y, width, height, borders..., regionWidth, regionHeight, u, v)
 * drew a region of a texture stretched to a size: the corners as they are, the edges and the middle repeated, each part a
 * blit(texture, x, y, u, v, width, height); blitRepeating tiled a region over an area in pieces of near-equal size
 * (Divisor over Mth.positiveCeilDiv). The two shorter overloads passed one border, or a horizontal and a vertical one,
 * for all four sides. 1.20.2 draws its own GUI from sprites whose metadata says how they slice (blitSprite) and dropped
 * both; blit(texture, x, y, u, v, width, height), Divisor and Mth.positiveCeilDiv are still there, so the old drawing is
 * 1.20.1's own code on them.
 *
 * Agrees with Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html): "Texture rendering methods have changed;
 * drawTexture calls should be replaced with drawGuiTexture." A mod's own texture has no sprite metadata, so it keeps the
 * old slicing.
 */
public final class GuiGraphics {

    private GuiGraphics() {}

    public static void blitNineSliced(net.minecraft.client.gui.GuiGraphics self, ResourceLocation texture, int x, int y, int width, int height, int border, int regionWidth, int regionHeight, int u, int v) {
        blitNineSliced(self, texture, x, y, width, height, border, border, border, border, regionWidth, regionHeight, u, v);
    }

    public static void blitNineSliced(net.minecraft.client.gui.GuiGraphics self, ResourceLocation texture, int x, int y, int width, int height, int borderX, int borderY, int regionWidth, int regionHeight, int u, int v) {
        blitNineSliced(self, texture, x, y, width, height, borderX, borderY, borderX, borderY, regionWidth, regionHeight, u, v);
    }

    public static void blitNineSliced(net.minecraft.client.gui.GuiGraphics self, ResourceLocation texture, int x, int y, int width, int height, int left, int top, int right, int bottom, int regionWidth, int regionHeight, int u, int v) {
        left = Math.min(left, width / 2);
        right = Math.min(right, width / 2);
        top = Math.min(top, height / 2);
        bottom = Math.min(bottom, height / 2);
        if (width == regionWidth && height == regionHeight) {
            self.blit(texture, x, y, u, v, width, height);
        } else if (height == regionHeight) {
            self.blit(texture, x, y, u, v, left, height);
            blitRepeating(self, texture, x + left, y, width - right - left, height, u + left, v, regionWidth - right - left, regionHeight);
            self.blit(texture, x + width - right, y, u + regionWidth - right, v, right, height);
        } else if (width == regionWidth) {
            self.blit(texture, x, y, u, v, width, top);
            blitRepeating(self, texture, x, y + top, width, height - bottom - top, u, v + top, regionWidth, regionHeight - bottom - top);
            self.blit(texture, x, y + height - bottom, u, v + regionHeight - bottom, width, bottom);
        } else {
            self.blit(texture, x, y, u, v, left, top);
            blitRepeating(self, texture, x + left, y, width - right - left, top, u + left, v, regionWidth - right - left, top);
            self.blit(texture, x + width - right, y, u + regionWidth - right, v, right, top);
            self.blit(texture, x, y + height - bottom, u, v + regionHeight - bottom, left, bottom);
            blitRepeating(self, texture, x + left, y + height - bottom, width - right - left, bottom, u + left, v + regionHeight - bottom, regionWidth - right - left, bottom);
            self.blit(texture, x + width - right, y + height - bottom, u + regionWidth - right, v + regionHeight - bottom, right, bottom);
            blitRepeating(self, texture, x, y + top, left, height - bottom - top, u, v + top, left, regionHeight - bottom - top);
            blitRepeating(self, texture, x + left, y + top, width - right - left, height - bottom - top, u + left, v + top, regionWidth - right - left, regionHeight - bottom - top);
            blitRepeating(self, texture, x + width - right, y + top, left, height - bottom - top, u + regionWidth - right, v + top, right, regionHeight - bottom - top);
        }
    }

    public static void blitRepeating(net.minecraft.client.gui.GuiGraphics self, ResourceLocation texture, int x, int y, int width, int height, int u, int v, int regionWidth, int regionHeight) {
        int atX = x;
        IntIterator columns = slices(width, regionWidth);
        while (columns.hasNext()) {
            int column = columns.nextInt();
            int fromU = (regionWidth - column) / 2;
            int atY = y;
            IntIterator rows = slices(height, regionHeight);
            while (rows.hasNext()) {
                int row = rows.nextInt();
                int fromV = (regionHeight - row) / 2;
                self.blit(texture, atX, atY, u + fromU, v + fromV, column, row);
                atY += row;
            }
            atX += column;
        }
    }

    private static IntIterator slices(int length, int piece) {
        return new Divisor(length, Mth.positiveCeilDiv(length, piece));
    }
}

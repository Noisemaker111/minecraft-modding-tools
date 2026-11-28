package mmt.standin.net.minecraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;

/**
 * 1.19.4's static GuiComponent.blit(pose, x, y, u, v, width, height), blitNineSliced and blitRepeating on 1.19.3, for a
 * port back.
 *
 * Read from both releases: 1.19.4 dropped GuiComponent's blitOffset and made blit(pose, x, y, u, v, width, height) static,
 * drawing at depth 0 from a 256x256 texture through blit(pose, x, y, 0, u, v, width, height, 256, 256). 1.19.3 has that
 * same ten-argument static blit; its seven-argument blit is an instance method that passes the component's blitOffset as
 * the depth. So the static form is the ten-argument call with depth 0, as in 1.19.4.
 *
 * blitNineSliced (three forms) and blitRepeating are new in 1.19.4 and draw only through that blit: the corners at their
 * size, the edges and middle repeated in slices no wider than the source, each slice taken from the middle of the source.
 * They are 1.19.4's code as it reads, with its slices(length, slice) written out: Mth.positiveCeilDiv(length, slice)
 * pieces, the remainder spread over them as com.mojang.math.Divisor (also 1.19.4's) spreads it.
 */
public final class GuiComponent {

    private GuiComponent() {
    }

    public static void blit(PoseStack pose, int x, int y, int u, int v, int width, int height) {
        net.minecraft.client.gui.GuiComponent.blit(pose, x, y, 0, (float) u, (float) v, width, height, 256, 256);
    }

    public static void blitNineSliced(PoseStack pose, int x, int y, int width, int height, int border, int sourceWidth, int sourceHeight, int u, int v) {
        blitNineSliced(pose, x, y, width, height, border, border, border, border, sourceWidth, sourceHeight, u, v);
    }

    public static void blitNineSliced(PoseStack pose, int x, int y, int width, int height, int sideBorder, int topBorder, int sourceWidth, int sourceHeight, int u, int v) {
        blitNineSliced(pose, x, y, width, height, sideBorder, topBorder, sideBorder, topBorder, sourceWidth, sourceHeight, u, v);
    }

    public static void blitNineSliced(PoseStack pose, int x, int y, int width, int height, int left, int top, int right, int bottom, int sourceWidth, int sourceHeight, int u, int v) {
        left = Math.min(left, width / 2);
        right = Math.min(right, width / 2);
        top = Math.min(top, height / 2);
        bottom = Math.min(bottom, height / 2);
        if (width == sourceWidth && height == sourceHeight) {
            blit(pose, x, y, u, v, width, height);
        } else if (height == sourceHeight) {
            blit(pose, x, y, u, v, left, height);
            blitRepeating(pose, x + left, y, width - right - left, height, u + left, v, sourceWidth - right - left, sourceHeight);
            blit(pose, x + width - right, y, u + sourceWidth - right, v, right, height);
        } else if (width == sourceWidth) {
            blit(pose, x, y, u, v, width, top);
            blitRepeating(pose, x, y + top, width, height - bottom - top, u, v + top, sourceWidth, sourceHeight - bottom - top);
            blit(pose, x, y + height - bottom, u, v + sourceHeight - bottom, width, bottom);
        } else {
            blit(pose, x, y, u, v, left, top);
            blitRepeating(pose, x + left, y, width - right - left, top, u + left, v, sourceWidth - right - left, top);
            blit(pose, x + width - right, y, u + sourceWidth - right, v, right, top);
            blit(pose, x, y + height - bottom, u, v + sourceHeight - bottom, left, bottom);
            blitRepeating(pose, x + left, y + height - bottom, width - right - left, bottom, u + left, v + sourceHeight - bottom, sourceWidth - right - left, bottom);
            blit(pose, x + width - right, y + height - bottom, u + sourceWidth - right, v + sourceHeight - bottom, right, bottom);
            blitRepeating(pose, x, y + top, left, height - bottom - top, u, v + top, left, sourceHeight - bottom - top);
            blitRepeating(pose, x + left, y + top, width - right - left, height - bottom - top, u + left, v + top, sourceWidth - right - left, sourceHeight - bottom - top);
            blitRepeating(pose, x + width - right, y + top, left, height - bottom - top, u + sourceWidth - right, v + top, right, sourceHeight - bottom - top);
        }
    }

    public static void blitRepeating(PoseStack pose, int x, int y, int width, int height, int u, int v, int sourceWidth, int sourceHeight) {
        int atX = x;
        for (int sliceWidth : slices(width, sourceWidth)) {
            int insetU = (sourceWidth - sliceWidth) / 2;
            int atY = y;
            for (int sliceHeight : slices(height, sourceHeight)) {
                int insetV = (sourceHeight - sliceHeight) / 2;
                blit(pose, atX, atY, u + insetU, v + insetV, sliceWidth, sliceHeight);
                atY += sliceHeight;
            }
            atX += sliceWidth;
        }
    }

    private static int[] slices(int length, int slice) {
        int count = -Math.floorDiv(-length, slice);
        int[] out = new int[Math.max(count, 0)];
        int remainder = 0;
        for (int i = 0; i < out.length; i++) {
            int part = length / count;
            remainder += length % count;
            if (remainder >= count) {
                remainder -= count;
                part++;
            }
            out[i] = part;
        }
        return out;
    }
}

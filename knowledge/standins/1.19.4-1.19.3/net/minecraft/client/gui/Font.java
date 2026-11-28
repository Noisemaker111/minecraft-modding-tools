package mmt.standin.net.minecraft.client.gui;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;

/**
 * 1.19.4's Font.drawInBatch(..., Font.DisplayMode mode, background, light[, bidi]) on 1.19.3, for a port back.
 *
 * Read from both releases: both have the enum Font.DisplayMode (NORMAL, SEE_THROUGH, POLYGON_OFFSET) and draw through
 * the same StringRenderOutput, which takes one. 1.19.4's public drawInBatch forms take the mode; 1.19.3's take a boolean
 * seeThrough in its place and pass SEE_THROUGH for true, NORMAL for false. So each form is 1.19.3's with
 * mode == SEE_THROUGH.
 *
 * Not the same: POLYGON_OFFSET, which 1.19.3 uses only inside drawInBatch8xOutline and no public form takes, draws as
 * NORMAL here (without the depth offset that keeps text off the surface behind it).
 */
public final class Font {

    private Font() {
    }

    public static int drawInBatch(net.minecraft.client.gui.Font self, String text, float x, float y, int color, boolean shadow, Matrix4f matrix, MultiBufferSource buffers, net.minecraft.client.gui.Font.DisplayMode mode, int background, int light) {
        return self.drawInBatch(text, x, y, color, shadow, matrix, buffers, mode == net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH, background, light);
    }

    public static int drawInBatch(net.minecraft.client.gui.Font self, String text, float x, float y, int color, boolean shadow, Matrix4f matrix, MultiBufferSource buffers, net.minecraft.client.gui.Font.DisplayMode mode, int background, int light, boolean bidirectional) {
        return self.drawInBatch(text, x, y, color, shadow, matrix, buffers, mode == net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH, background, light, bidirectional);
    }

    public static int drawInBatch(net.minecraft.client.gui.Font self, Component text, float x, float y, int color, boolean shadow, Matrix4f matrix, MultiBufferSource buffers, net.minecraft.client.gui.Font.DisplayMode mode, int background, int light) {
        return self.drawInBatch(text, x, y, color, shadow, matrix, buffers, mode == net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH, background, light);
    }

    public static int drawInBatch(net.minecraft.client.gui.Font self, FormattedCharSequence text, float x, float y, int color, boolean shadow, Matrix4f matrix, MultiBufferSource buffers, net.minecraft.client.gui.Font.DisplayMode mode, int background, int light) {
        return self.drawInBatch(text, x, y, color, shadow, matrix, buffers, mode == net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH, background, light);
    }
}

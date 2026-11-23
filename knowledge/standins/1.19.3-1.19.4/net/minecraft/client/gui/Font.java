package mmt.standin.net.minecraft.client.gui;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;

/**
 * 1.19.3's Font.drawInBatch(..., seeThrough, ...) on 1.19.4.
 *
 * Read from both releases: 1.19.3's drawInBatch took a boolean seeThrough (draw the text through blocks, as a name tag of
 * a creature that is not sneaking is) between the buffer source and the background colour. 1.19.4 replaced it with a
 * Font.DisplayMode (NORMAL, SEE_THROUGH, or the new POLYGON_OFFSET), and the game's own callers turned the flag into
 * the mode: EntityRenderer.renderNameTag passed its flag where 1.19.3 passed it, and passes flag ? SEE_THROUGH : NORMAL
 * in 1.19.4; callers that passed false pass NORMAL. Every other argument is the same, so each old overload is the new one
 * with that mode.
 *
 * Agrees with NeoForge's 1.19.3 -> 1.19.4 primer (https://github.com/neoforged/.github/blob/main/primers/1.19.4/index.md, CC BY 4.0): "Font#drawInBatch and any subsequent delegates ... now take in a Font$DisplayMode instead of a boolean to determine how the font should be displayed."
 */
public final class Font {

    private Font() {}

    private static net.minecraft.client.gui.Font.DisplayMode mode(boolean seeThrough) {
        return seeThrough ? net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH : net.minecraft.client.gui.Font.DisplayMode.NORMAL;
    }

    public static int drawInBatch(net.minecraft.client.gui.Font self, String text, float x, float y, int color, boolean shadow, Matrix4f matrix, MultiBufferSource buffers, boolean seeThrough, int background, int light) {
        return self.drawInBatch(text, x, y, color, shadow, matrix, buffers, mode(seeThrough), background, light);
    }

    public static int drawInBatch(net.minecraft.client.gui.Font self, String text, float x, float y, int color, boolean shadow, Matrix4f matrix, MultiBufferSource buffers, boolean seeThrough, int background, int light, boolean bidirectional) {
        return self.drawInBatch(text, x, y, color, shadow, matrix, buffers, mode(seeThrough), background, light, bidirectional);
    }

    public static int drawInBatch(net.minecraft.client.gui.Font self, Component text, float x, float y, int color, boolean shadow, Matrix4f matrix, MultiBufferSource buffers, boolean seeThrough, int background, int light) {
        return self.drawInBatch(text, x, y, color, shadow, matrix, buffers, mode(seeThrough), background, light);
    }

    public static int drawInBatch(net.minecraft.client.gui.Font self, FormattedCharSequence text, float x, float y, int color, boolean shadow, Matrix4f matrix, MultiBufferSource buffers, boolean seeThrough, int background, int light) {
        return self.drawInBatch(text, x, y, color, shadow, matrix, buffers, mode(seeThrough), background, light);
    }
}

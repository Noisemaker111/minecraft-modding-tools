package mmt.standin.net.minecraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;

/**
 * 1.16.1's Font.draw, drawShadow and drawInBatch of a FormattedText on 1.16.2.
 *
 * Read from both releases: 1.16.1 drew a FormattedText (a Component among them) as it was. 1.16.2 draws a
 * FormattedCharSequence, the text laid out in display order; its Component overloads pass getVisualOrderText(), which
 * is Language.getInstance().getVisualOrder(this). These lay the FormattedText out the same way and draw it.
 *
 * Fabric's 1.16.2 post (https://fabricmc.net/2020/08/11/1162.html) does not mention it; this is from the game's code alone.
 */
public final class Font {

    private Font() {}

    public static int draw(net.minecraft.client.gui.Font self, PoseStack pose, FormattedText text, float x, float y, int color) {
        return self.draw(pose, Language.getInstance().getVisualOrder(text), x, y, color);
    }

    public static int drawShadow(net.minecraft.client.gui.Font self, PoseStack pose, FormattedText text, float x, float y, int color) {
        return self.drawShadow(pose, Language.getInstance().getVisualOrder(text), x, y, color);
    }

    public static int drawInBatch(net.minecraft.client.gui.Font self, FormattedText text, float x, float y, int color, boolean shadow, Matrix4f matrix, MultiBufferSource buffers, boolean seeThrough, int background, int light) {
        return self.drawInBatch(Language.getInstance().getVisualOrder(text), x, y, color, shadow, matrix, buffers, seeThrough, background, light);
    }
}

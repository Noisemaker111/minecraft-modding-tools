package mmt.standin.net.minecraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * 1.19.4's Font.draw and Font.drawShadow on 1.20.
 *
 * Read from both releases: 1.19.4's draw(poseStack, text, x, y, color) and drawShadow drew at once: drawInternal made an
 * immediate buffer source (MultiBufferSource.immediate on the Tesselator's builder), called drawInBatch(text, x, y,
 * color, shadow, the pose's matrix, it, DisplayMode.NORMAL, background 0, full light 15728880) and ended the batch; a
 * String was shaped for right-to-left text when the font said so (isBidirectional), a Component drawn as its visual order
 * text. 1.20 dropped draw and drawShadow (GuiGraphics.drawString took their place) and kept drawInBatch, the immediate
 * buffer source and isBidirectional, so the old drawing is the same calls.
 *
 * Agrees with Fabric's 1.20 post (https://fabricmc.net/2023/05/25/120.html): "`drawTextWithShadow` (replaces
 * `TextRenderer#drawWithShadow`)" among the methods added to DrawContext (GuiGraphics in Mojang's names).
 */
public final class Font {

    private Font() {}

    static int draw(net.minecraft.client.gui.Font font, String text, float x, float y, int color, PoseStack pose, boolean shadow) {
        if (text == null) {
            return 0;
        }
        MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        int end = font.drawInBatch(text, x, y, color, shadow, pose.last().pose(), buffers, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880, font.isBidirectional());
        buffers.endBatch();
        return end;
    }

    static int draw(net.minecraft.client.gui.Font font, FormattedCharSequence text, float x, float y, int color, PoseStack pose, boolean shadow) {
        MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        int end = font.drawInBatch(text, x, y, color, shadow, pose.last().pose(), buffers, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
        buffers.endBatch();
        return end;
    }

    public static int draw(net.minecraft.client.gui.Font self, PoseStack pose, String text, float x, float y, int color) {
        return draw(self, text, x, y, color, pose, false);
    }

    public static int draw(net.minecraft.client.gui.Font self, PoseStack pose, FormattedCharSequence text, float x, float y, int color) {
        return draw(self, text, x, y, color, pose, false);
    }

    public static int draw(net.minecraft.client.gui.Font self, PoseStack pose, Component text, float x, float y, int color) {
        return draw(self, text.getVisualOrderText(), x, y, color, pose, false);
    }

    public static int drawShadow(net.minecraft.client.gui.Font self, PoseStack pose, String text, float x, float y, int color) {
        return draw(self, text, x, y, color, pose, true);
    }

    public static int drawShadow(net.minecraft.client.gui.Font self, PoseStack pose, FormattedCharSequence text, float x, float y, int color) {
        return draw(self, text, x, y, color, pose, true);
    }

    public static int drawShadow(net.minecraft.client.gui.Font self, PoseStack pose, Component text, float x, float y, int color) {
        return draw(self, text.getVisualOrderText(), x, y, color, pose, true);
    }
}

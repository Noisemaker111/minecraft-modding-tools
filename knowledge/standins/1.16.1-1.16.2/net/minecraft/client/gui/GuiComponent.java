package mmt.standin.net.minecraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

/**
 * 1.16.1's GuiComponent.drawCenteredString and drawString (instance methods, of a String or a FormattedText) on 1.16.2.
 *
 * Read from both releases: 1.16.1's drew the text with the font's shadow at (x, y), centred on x for
 * drawCenteredString (x minus half the font's width of it). 1.16.2 made both static and takes a String or a Component,
 * laid out with getVisualOrderText(), Language.getInstance().getVisualOrder(this). These draw the same way.
 *
 * Fabric's 1.16.2 post (https://fabricmc.net/2020/08/11/1162.html) does not mention it; this is from the game's code alone.
 */
public final class GuiComponent {

    private GuiComponent() {}

    public static void drawCenteredString(net.minecraft.client.gui.GuiComponent self, PoseStack pose, Font font, String text, int x, int y, int color) {
        net.minecraft.client.gui.GuiComponent.drawCenteredString(pose, font, text, x, y, color);
    }

    public static void drawCenteredString(net.minecraft.client.gui.GuiComponent self, PoseStack pose, Font font, FormattedText text, int x, int y, int color) {
        FormattedCharSequence line = Language.getInstance().getVisualOrder(text);
        font.drawShadow(pose, line, (float) (x - font.width(line) / 2), (float) y, color);
    }

    public static void drawString(net.minecraft.client.gui.GuiComponent self, PoseStack pose, Font font, String text, int x, int y, int color) {
        net.minecraft.client.gui.GuiComponent.drawString(pose, font, text, x, y, color);
    }

    public static void drawString(net.minecraft.client.gui.GuiComponent self, PoseStack pose, Font font, FormattedText text, int x, int y, int color) {
        font.drawShadow(pose, Language.getInstance().getVisualOrder(text), (float) x, (float) y, color);
    }
}

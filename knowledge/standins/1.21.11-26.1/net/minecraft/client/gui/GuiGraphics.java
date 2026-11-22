package mmt.standin.net.minecraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

/**
 * 1.21.11's GuiGraphics on 26.1, where it is GuiGraphicsExtractor.
 *
 * Read from both releases: 1.21.11's public renderComponentHoverEffect(font, style, x, y) showed a style's hover event as
 * the next frame's tooltip: a ShowItem's stack (setTooltipForNextFrame(font, stack, x, y)), a ShowEntity's lines when
 * advanced tooltips are on (setComponentTooltipForNextFrame), a ShowText's text split to half the GUI's width but at least
 * 200 (font.split(text, max(guiWidth() / 2, 200))); nothing for a null style or one without a hover event. 26.1 renames the
 * class GuiGraphicsExtractor and keeps the same steps in the private componentHoverEffect, the item now a template it makes
 * a stack from (ItemStackTemplate.create()). So the old method takes those steps through the extractor's public tooltip
 * methods.
 *
 * Agrees with NeoForge's 1.21.11 -> 26.1 primer (https://github.com/neoforged/.github/blob/main/primers/26.1/index.md, CC BY 4.0): "GuiGraphics -> GuiGraphicsExtractor" and "renderComponentHoverEffect -> componentHoverEffect, now private instead of public".
 */
public final class GuiGraphics {

    private GuiGraphics() {}

    public static void renderComponentHoverEffect(GuiGraphicsExtractor self, Font font, Style style, int x, int y) {
        if (style == null || style.getHoverEvent() == null) {
            return;
        }
        HoverEvent event = style.getHoverEvent();
        if (event instanceof HoverEvent.ShowItem item) {
            self.setTooltipForNextFrame(font, item.item().create(), x, y);
        } else if (event instanceof HoverEvent.ShowEntity entity) {
            if (Minecraft.getInstance().options.advancedItemTooltips) {
                self.setComponentTooltipForNextFrame(font, entity.entity().getTooltipLines(), x, y);
            }
        } else if (event instanceof HoverEvent.ShowText text) {
            self.setTooltipForNextFrame(font, font.split(text.value(), Math.max(self.guiWidth() / 2, 200)), x, y);
        }
    }
}

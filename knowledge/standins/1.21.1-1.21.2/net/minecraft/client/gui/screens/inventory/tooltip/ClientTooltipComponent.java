package mmt.standin.net.minecraft.client.gui.screens.inventory.tooltip;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 1.21.1's ClientTooltipComponent.getHeight() and renderImage(font, x, y, graphics) on 1.21.2.
 *
 * Read from both releases: 1.21.2 passes the font to getHeight and the tooltip's width and height to renderImage; the
 * text components measure with the font they are given, and GuiGraphics.renderTooltipInternal always gives the client's
 * font. A height asked with no font is the height in the client's font (Minecraft.font). A component drawn alone fills its
 * own box, so its image gets its own width and height.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "getHeight() -> getHeight(Font)" and "renderImage now takes in the int width and height of the rendering tooltip".
 */
public final class ClientTooltipComponent {

    private ClientTooltipComponent() {}

    public static int getHeight(net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent self) {
        return self.getHeight(Minecraft.getInstance().font);
    }

    public static void renderImage(net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent self, Font font, int x, int y, GuiGraphics graphics) {
        self.renderImage(font, x, y, self.getWidth(font), self.getHeight(font), graphics);
    }
}

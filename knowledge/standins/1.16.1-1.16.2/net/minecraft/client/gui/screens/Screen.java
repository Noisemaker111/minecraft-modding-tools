package mmt.standin.net.minecraft.client.gui.screens;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;

/**
 * 1.16.1's Screen.renderTooltip of a FormattedText, and of a list of them, on 1.16.2.
 *
 * Read from both releases: 1.16.1's renderTooltip(pose, text, x, y) drew a one-line tooltip, and renderTooltip(pose,
 * List of FormattedText, x, y) one line per entry. 1.16.2 takes a Component (laid out with getVisualOrderText()) or a
 * List of FormattedCharSequence, already laid out; the list form keeps 1.16.1's descriptor, so a 1.16.1 caller's list of
 * Components would reach it unconverted. These lay each line out as 1.16.2 does (Language.getVisualOrder) and draw them.
 *
 * Fabric's 1.16.2 post (https://fabricmc.net/2020/08/11/1162.html) does not mention it; this is from the game's code alone.
 */
public final class Screen {

    private Screen() {}

    public static void renderTooltip(net.minecraft.client.gui.screens.Screen self, PoseStack pose, FormattedText text, int x, int y) {
        self.renderTooltip(pose, Collections.singletonList(Language.getInstance().getVisualOrder(text)), x, y);
    }

    public static void renderTooltip(net.minecraft.client.gui.screens.Screen self, PoseStack pose, List lines, int x, int y) {
        List laidOut = new ArrayList();
        for (Object line : lines) {
            laidOut.add(line instanceof FormattedText ? Language.getInstance().getVisualOrder((FormattedText) line) : line);
        }
        self.renderTooltip(pose, laidOut, x, y);
    }
}

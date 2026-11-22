package mmt.standin.net.minecraft.client.gui.screens;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;

/**
 * 1.21.10's Screen on 1.21.11.
 *
 * Read from both releases: 1.21.10's handleComponentClicked(style) inserted the style's insertion text when shift was
 * held (and answered false), else ran the style's click event through handleClickEvent, which by default was
 * defaultHandleGameClickEvent(event, minecraft, this), and answered true; with no click event it answered false. 1.21.11
 * moved the method to ChatScreen (private) and dropped handleClickEvent; every screen still has insertText and the static
 * defaultHandleGameClickEvent, the same handling of each kind of click event. The same steps.
 *
 * Agrees with NeoForge's 1.21.10 -> 1.21.11 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.11/index.md, CC BY 4.0): "handleComponentClicked -> ChatScreen#handleComponentClicked, now private".
 */
public final class Screen {

    private Screen() {}

    public static boolean handleComponentClicked(net.minecraft.client.gui.screens.Screen self, Style style) {
        if (style == null) {
            return false;
        }
        ClickEvent event = style.getClickEvent();
        if (self.minecraft.hasShiftDown()) {
            if (style.getInsertion() != null) {
                self.insertText(style.getInsertion(), false);
            }
        } else if (event != null) {
            net.minecraft.client.gui.screens.Screen.defaultHandleGameClickEvent(event, self.minecraft, self);
            return true;
        }
        return false;
    }
}

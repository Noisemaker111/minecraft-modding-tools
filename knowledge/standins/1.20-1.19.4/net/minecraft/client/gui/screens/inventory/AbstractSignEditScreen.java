package mmt.standin.net.minecraft.client.gui.screens.inventory;

import mmt.standin.net.minecraft.world.level.block.entity.SignBlockEntity;
import mmt.standin.net.minecraft.world.level.block.entity.SignText;

/**
 * 1.20's sign edit screen fields isFrontText and text on 1.19.4.
 *
 * Read from both releases: 1.20's screen edits one side of the sign (isFrontText) and keeps that side's text as it is
 * typed (text, a SignText it replaces at each keystroke, sent to the sign when the screen closes). 1.19.4's sign has one
 * side, the front, and its screen writes each keystroke to the sign itself (sign.setMessage(line, Component.literal(s))),
 * so the text being edited is the sign's front text (SignBlockEntity's stand-in). 1.19.4 keeps no copy of it beside the
 * sign, so setting it writes the sign's front, as 1.20's own setMessage does right after it sets the field.
 */
public final class AbstractSignEditScreen {

    private AbstractSignEditScreen() {
    }

    public static boolean isFrontText(net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen self) {
        return true;
    }

    public static SignText text(net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen self) {
        return SignBlockEntity.getFrontText(self.sign);
    }

    public static void text(net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen self, SignText text) {
        SignBlockEntity.setText(self.sign, text, true);
    }
}

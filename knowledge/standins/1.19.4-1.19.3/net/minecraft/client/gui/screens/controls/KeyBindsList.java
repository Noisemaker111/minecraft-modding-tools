package mmt.standin.net.minecraft.client.gui.screens.controls;

import net.minecraft.client.KeyMapping;

/**
 * 1.19.4's KeyBindsList.resetMappingAndUpdateButtons() on 1.19.3, for a port back.
 *
 * Read from both releases: 1.19.4's calls KeyMapping.resetMapping() and then has each entry set its buttons' text and
 * colour from its key (refreshEntry). 1.19.3's entries do the same when their key's buttons are pressed (they call
 * KeyMapping.resetMapping()) and set their buttons from their key each time they draw, so the reset is all that is left
 * to do.
 */
public final class KeyBindsList {

    private KeyBindsList() {
    }

    public static void resetMappingAndUpdateButtons(net.minecraft.client.gui.screens.controls.KeyBindsList self) {
        KeyMapping.resetMapping();
    }
}

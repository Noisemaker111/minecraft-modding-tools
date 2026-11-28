package mmt.standin.net.minecraft.client;

import net.minecraft.client.ToggleKeyMapping;

/**
 * 1.20's KeyMapping.resetToggleKeys() on 1.19.4.
 *
 * Read from both releases: 1.20 lets go of every toggle key (each ToggleKeyMapping among KeyMapping.ALL), its reset()
 * being KeyMapping.setDown(false), which sets isDown; ToggleKeyMapping overrides setDown to toggle, so that is the
 * field itself. 1.19.4's KeyMapping keeps the same ALL and isDown.
 */
public final class KeyMapping {

    private KeyMapping() {
    }

    public static void resetToggleKeys() {
        for (Object k : net.minecraft.client.KeyMapping.ALL.values()) {
            if (k instanceof ToggleKeyMapping toggle) {
                toggle.isDown = false;
            }
        }
    }
}

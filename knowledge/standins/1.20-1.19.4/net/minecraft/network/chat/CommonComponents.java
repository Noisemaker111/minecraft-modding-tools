package mmt.standin.net.minecraft.network.chat;

import net.minecraft.network.chat.Component;

/**
 * 1.20's CommonComponents.GUI_OK on 1.19.4: Component.translatable("gui.ok") in 1.20, a key 1.19.4's language files
 * have too.
 */
public final class CommonComponents {

    private static final Component GUI_OK = Component.translatable("gui.ok");

    private CommonComponents() {
    }

    public static Component GUI_OK() {
        return GUI_OK;
    }
}

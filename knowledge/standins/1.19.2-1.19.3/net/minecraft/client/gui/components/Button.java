package mmt.standin.net.minecraft.client.gui.components;

import net.minecraft.network.chat.Component;

/**
 * 1.19.2's Button(x, y, width, height, message, onPress) constructor on 1.19.3.
 *
 * Read from both releases: 1.19.2's public Button(x, y, width, height, message, onPress) made a button with no tooltip,
 * narrated as its message. 1.19.3 made the constructor protected and builds buttons with Button.builder(message,
 * onPress).bounds(x, y, width, height).build(), which gives no tooltip and the default narration (the message). The old
 * constructor is that builder.
 *
 * Agrees with Fabric's 1.19.3 post (https://fabricmc.net/2022/11/24/1193.html): "`ButtonWidget` is now constructed using
 * a builder." (Button in Mojang's names.)
 */
public final class Button {

    private Button() {}

    public static net.minecraft.client.gui.components.Button init(int x, int y, int width, int height, Component message, net.minecraft.client.gui.components.Button.OnPress onPress) {
        return net.minecraft.client.gui.components.Button.builder(message, onPress).bounds(x, y, width, height).build();
    }
}

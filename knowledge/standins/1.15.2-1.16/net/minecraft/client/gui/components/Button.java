package mmt.standin.net.minecraft.client.gui.components;

import net.minecraft.client.gui.components.Button.OnPress;
import net.minecraft.network.chat.TextComponent;

/**
 * 1.15.2's new Button(x, y, width, height, String, OnPress) on 1.16.
 *
 * Read from both releases: 1.15.2's widgets took their message as a String (drawn as it was); 1.16's take a Component.
 * A TextComponent of the String draws the same text, so this makes 1.16's Button with one.
 *
 * Fabric's 1.16 post (https://fabricmc.net/2020/06/23/116.html) does not mention it; this is from the game's code alone.
 */
public final class Button {

    private Button() {}

    public static net.minecraft.client.gui.components.Button init(int x, int y, int width, int height, String message, OnPress onPress) {
        return new net.minecraft.client.gui.components.Button(x, y, width, height, new TextComponent(message), onPress);
    }
}

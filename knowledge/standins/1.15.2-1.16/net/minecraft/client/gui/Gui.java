package mmt.standin.net.minecraft.client.gui;

import net.minecraft.network.chat.TextComponent;

/**
 * 1.15.2's Gui.setTitles(String, String, int, int, int) on 1.16.
 *
 * Read from both releases: both setTitles clear, show a title, set a subtitle or set the times by which arguments are
 * null or negative; 1.15.2 took the title and subtitle as Strings (drawn as they were, formatting codes and all), 1.16
 * takes Components. A TextComponent of a String draws the same text, so these are passed as that, null as null.
 *
 * Fabric's 1.16 post (https://fabricmc.net/2020/06/23/116.html) does not mention it; this is from the game's code alone.
 */
public final class Gui {

    private Gui() {}

    public static void setTitles(net.minecraft.client.gui.Gui self, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        self.setTitles(title == null ? null : new TextComponent(title), subtitle == null ? null : new TextComponent(subtitle), fadeIn, stay, fadeOut);
    }
}

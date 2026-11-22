package mmt.standin.net.minecraft.client.gui;

import net.minecraft.network.chat.Component;

/**
 * 1.16.5's Gui.setTitles(title, subtitle, fadeIn, stay, fadeOut) on 1.17.
 *
 * Read from both releases: 1.16.5's setTitles did one of four things by what it was given: all null and negative cleared
 * the title; a title showed it for the fade-in, stay and fade-out time; else a subtitle set it; else it set the times
 * that were not negative, restarting a showing title's time. 1.17 split these into clear(), setTitle, setSubtitle and
 * setTimes, each the same code. This picks between them as 1.16.5 did.
 *
 * Fabric's 1.17 post for developers (https://fabricmc.net/2021/05/27/117-for-developers.html) covers Java 16, Fabric API and
 * Yarn, not this; this is from the game's code alone.
 */
public final class Gui {

    private Gui() {}

    public static void setTitles(net.minecraft.client.gui.Gui self, Component title, Component subtitle, int fadeIn, int stay, int fadeOut) {
        if (title == null && subtitle == null && fadeIn < 0 && stay < 0 && fadeOut < 0) {
            self.clear();
        } else if (title != null) {
            self.setTitle(title);
        } else if (subtitle != null) {
            self.setSubtitle(subtitle);
        } else {
            self.setTimes(fadeIn, stay, fadeOut);
        }
    }
}

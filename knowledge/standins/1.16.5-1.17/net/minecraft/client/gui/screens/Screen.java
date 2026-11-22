package mmt.standin.net.minecraft.client.gui.screens;

import java.util.List;

/**
 * 1.16.5's Screen.buttons, read on 1.17.
 *
 * Read from both releases: 1.16.5's buttons was the list of widgets the screen drew (render went through it; addButton
 * added to it and to children, init cleared it); mods added, removed, cleared and walked it. 1.17 keeps that list as
 * renderables (render goes through it; addRenderableWidget adds to it, children and narratables; clearWidgets clears
 * it). Reading buttons gives that same list, so a change to it does what the same change did to buttons.
 *
 * Fabric's 1.17 post for developers (https://fabricmc.net/2021/05/27/117-for-developers.html) covers Java 16, Fabric API and
 * Yarn, not this; this is from the game's code alone.
 */
public final class Screen {

    private Screen() {}

    public static List buttons(net.minecraft.client.gui.screens.Screen self) {
        return self.renderables;
    }
}

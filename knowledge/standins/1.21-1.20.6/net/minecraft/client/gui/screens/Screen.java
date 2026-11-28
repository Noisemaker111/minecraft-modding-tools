package mmt.standin.net.minecraft.client.gui.screens;

/**
 * 1.21's Screen.narratorButton on 1.20.6, for a port back: the narrator button of the screen, where 1.20.6 keeps one.
 *
 * Read from both releases: 1.21's Screen holds the narrator CycleButton its options screens make (null on any other
 * screen) and updateNarratorStatus sets it to the option's value; 1.20.6 keeps that button only in SimpleOptionsSubScreen
 * (the option list's narrator button, a CycleButton from Options.narrator().createButton), so it is read there and is
 * null on any other screen, as in 1.21.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "The options screens within `net.minecraft.client.gui.screens` and `net.minecraft.client.gui.screens.controls` have been moved to `net.minecraft.client.gui.screens.options`".
 */
public final class Screen {

    private Screen() {}

    public static net.minecraft.client.gui.components.CycleButton narratorButton(net.minecraft.client.gui.screens.Screen self) {
        if (self instanceof net.minecraft.client.gui.screens.SimpleOptionsSubScreen options && options.narratorButton instanceof net.minecraft.client.gui.components.CycleButton button) {
            return button;
        }
        return null;
    }
}

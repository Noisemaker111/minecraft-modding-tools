package mmt.standin.net.minecraft.client.gui.screens;

import net.minecraft.client.Minecraft;

/**
 * 1.21.8's Screen on 1.21.9.
 *
 * Read from both releases: 1.21.8's static hasControlDown(), hasShiftDown() and hasAltDown() asked whether either key of
 * the modifier was held in the game's window (control is the command key on macOS), and isCut, isPaste, isCopy and
 * isSelectAll(key) were that key (X, V, C, A) with control held and neither shift nor alt. 1.21.9 moved the three
 * questions to the Minecraft instance (hasControlDown() asks the same keys of the same window, through InputQuirks'
 * edit-shortcut keys) and the four shortcuts to a key event's modifiers. With no event in hand, the shortcuts are the
 * same key with the modifiers the window holds now, as in 1.21.8.
 *
 * Agrees with NeoForge's 1.21.8 -> 1.21.9 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.9/index.md, CC BY 4.0): "hasControlDown -> Minecraft#hasControlDown, InputWithModifiers#hasControlDown" (and shift, alt), "isCopy -> InputWithModifiers#isCopy" (and cut, paste, select all).
 */
public final class Screen {

    private Screen() {}

    public static boolean hasControlDown() {
        return Minecraft.getInstance().hasControlDown();
    }

    public static boolean hasShiftDown() {
        return Minecraft.getInstance().hasShiftDown();
    }

    public static boolean hasAltDown() {
        return Minecraft.getInstance().hasAltDown();
    }

    public static boolean isCut(int key) {
        return key == 88 && shortcut();
    }

    public static boolean isPaste(int key) {
        return key == 86 && shortcut();
    }

    public static boolean isCopy(int key) {
        return key == 67 && shortcut();
    }

    public static boolean isSelectAll(int key) {
        return key == 65 && shortcut();
    }

    private static boolean shortcut() {
        return hasControlDown() && !hasShiftDown() && !hasAltDown();
    }
}

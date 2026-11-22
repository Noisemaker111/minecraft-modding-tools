package mmt.standin.net.minecraft.client.gui.navigation;

/**
 * 1.21.8's CommonInputs, which 1.21.9 removed.
 *
 * Read from both releases: 1.21.8's selected(key) was true for enter (257), space (32) and keypad enter (335). 1.21.9
 * asks the same of a key event (InputWithModifiers.isSelection(), the same three keys); with only the key in hand, the
 * same comparison.
 *
 * Agrees with NeoForge's 1.21.8 -> 1.21.9 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.9/index.md, CC BY 4.0): "CommonInputs class is removed", "selected -> InputWithModifiers#isSelection".
 */
public final class CommonInputs {

    private CommonInputs() {}

    public static boolean selected(int key) {
        return key == 257 || key == 32 || key == 335;
    }
}

package mmt.standin.net.minecraft.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;

/**
 * 1.21.11's CharacterEvent on 26.1.
 *
 * Read from both releases: 1.21.11's CharacterEvent(codepoint, modifiers) carried the modifier keys GLFW's char-mods
 * callback reported with the character (InputConstants.setupKeyboardCallbacks took a GLFWCharModsCallbackI, lock keys not
 * reported): shift, control, alt and super as InputConstants.MOD_SHIFT, MOD_CONTROL, MOD_ALT, MOD_SUPER. 26.1's
 * CharacterEvent(codepoint) carries the character alone (KeyboardHandler: new CharacterEvent(codepoint)). So the old
 * constructor makes the new event from its character, and modifiers() is the modifier keys held now, read from the
 * window as the game reads a key (InputConstants.isKeyDown); the event is handled as the character arrives, so they are
 * the keys GLFW reported with it. A modifier set an event was made with is not kept.
 *
 * Agrees with NeoForge's 1.21.11 -> 26.1 primer (https://github.com/neoforged/.github/blob/main/primers/26.1/index.md, CC BY 4.0): "CharacterEvent no longer takes in the modifier int set".
 */
public final class CharacterEvent {

    private static final int KEY_LSUPER = 343;
    private static final int KEY_RSUPER = 347;

    private CharacterEvent() {}

    public static net.minecraft.client.input.CharacterEvent init(int codepoint, int modifiers) {
        return new net.minecraft.client.input.CharacterEvent(codepoint);
    }

    public static int modifiers(net.minecraft.client.input.CharacterEvent self) {
        Window window = Minecraft.getInstance().getWindow();
        int modifiers = 0;
        if (InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT) || InputConstants.isKeyDown(window, InputConstants.KEY_RSHIFT)) {
            modifiers |= InputConstants.MOD_SHIFT;
        }
        if (InputConstants.isKeyDown(window, InputConstants.KEY_LCONTROL) || InputConstants.isKeyDown(window, InputConstants.KEY_RCONTROL)) {
            modifiers |= InputConstants.MOD_CONTROL;
        }
        if (InputConstants.isKeyDown(window, InputConstants.KEY_LALT) || InputConstants.isKeyDown(window, InputConstants.KEY_RALT)) {
            modifiers |= InputConstants.MOD_ALT;
        }
        if (InputConstants.isKeyDown(window, KEY_LSUPER) || InputConstants.isKeyDown(window, KEY_RSUPER)) {
            modifiers |= InputConstants.MOD_SUPER;
        }
        return modifiers;
    }
}

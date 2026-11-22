package mmt.standin.com.mojang.blaze3d.platform;

import net.minecraft.client.input.KeyEvent;

/**
 * 1.21.8's InputConstants on 1.21.9.
 *
 * Read from both releases: 1.21.8's getKey(key, scancode) gave the scancode's key when the key was -1 (unknown), else the
 * key's, and isKeyDown(window, key) asked GLFW whether the key was pressed in the window with that handle
 * (glfwGetKey(handle, key) == GLFW_PRESS). 1.21.9's getKey takes a KeyEvent(key, scancode, modifiers) and makes the same
 * choice from its key and scancode; isKeyDown takes the Window and asks GLFW with its handle, the same call.
 *
 * Agrees with NeoForge's 1.21.8 -> 1.21.9 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.9/index.md, CC BY 4.0): "getKey now takes in a KeyEvent instead of the key and scancode ints"; "isKeyDown ... now take in a Window instead of the long handle".
 */
public final class InputConstants {

    private InputConstants() {}

    public static com.mojang.blaze3d.platform.InputConstants.Key getKey(int key, int scancode) {
        return com.mojang.blaze3d.platform.InputConstants.getKey(new KeyEvent(key, scancode, 0));
    }

    public static boolean isKeyDown(long window, int key) {
        return org.lwjgl.glfw.GLFW.glfwGetKey(window, key) == 1;
    }
}

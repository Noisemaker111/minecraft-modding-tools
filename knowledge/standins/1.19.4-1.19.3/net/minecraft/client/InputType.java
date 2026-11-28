package mmt.standin.net.minecraft.client;

/**
 * 1.19.4's InputType on 1.19.3: the class itself, which 1.19.3 does not have.
 *
 * Read from both releases: 1.19.4 added it with keyboard navigation, the kind of input the player last used
 * (Minecraft.getLastInputType()). Its code here is 1.19.4's as it reads.
 */
public enum InputType {
    NONE,
    MOUSE,
    KEYBOARD_ARROW,
    KEYBOARD_TAB;

    public boolean isMouse() {
        return this == MOUSE;
    }

    public boolean isKeyboard() {
        return this == KEYBOARD_ARROW || this == KEYBOARD_TAB;
    }
}

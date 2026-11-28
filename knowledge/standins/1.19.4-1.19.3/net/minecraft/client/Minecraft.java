package mmt.standin.net.minecraft.client;

/**
 * 1.19.4's Minecraft.getLastInputType() on 1.19.3, for a port back.
 *
 * Read from both releases: 1.19.4's Minecraft keeps the last kind of input (NONE until the player clicks or navigates
 * with arrows or tab, which 1.19.4's MouseHandler and Screen.keyPressed record). 1.19.3 records none: it has no arrow or
 * tab navigation of 1.19.4's kind. So it is NONE, what 1.19.4 answers before any such input.
 *
 * Not the same: where 1.19.4 would answer MOUSE after a click, a mod asking here gets NONE; both are not keyboard, so a
 * mod's "show keyboard focus" test (isKeyboard()) answers as in 1.19.4.
 */
public final class Minecraft {

    private Minecraft() {
    }

    public static InputType getLastInputType(net.minecraft.client.Minecraft self) {
        return InputType.NONE;
    }
}

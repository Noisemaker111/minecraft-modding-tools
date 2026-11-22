package mmt.standin.net.minecraft.client;

/**
 * 26.1.2's Options on 26.2.
 *
 * Read from both releases: 26.1.2's Options kept hideGui, the flag F1 flips to hide the heads-up display, read by the
 * game where it draws the HUD and written by the key handler. 26.2 keeps it in the client's Hud (minecraft.gui.hud):
 * isHidden() reads it and toggle() flips it, and the game reads it there. The client has one Options and one Hud, so the
 * old field is the Hud's flag: reading it asks isHidden(), and setting it flips the flag when it differs.
 *
 * Agrees with NeoForge's 26.1.2 -> 26.2 primer (https://github.com/neoforged/.github/blob/main/primers/26.2/index.md, CC BY 4.0): "Options: hideGui is replaced by Hud#isHidden, GuiRenderState#isHudHidden".
 */
public final class Options {

    private Options() {}

    public static boolean hideGui(net.minecraft.client.Options self) {
        return net.minecraft.client.Minecraft.getInstance().gui.hud.isHidden();
    }

    public static void hideGui(net.minecraft.client.Options self, boolean hidden) {
        net.minecraft.client.gui.Hud hud = net.minecraft.client.Minecraft.getInstance().gui.hud;
        if (hud.isHidden() != hidden) {
            hud.toggle();
        }
    }
}

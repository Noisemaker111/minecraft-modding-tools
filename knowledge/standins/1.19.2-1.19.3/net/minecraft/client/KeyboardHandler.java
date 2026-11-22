package mmt.standin.net.minecraft.client;

/**
 * 1.19.2's KeyboardHandler.setSendRepeatsToGui(send) on 1.19.3.
 *
 * Read from both releases: 1.19.2 passed a held key's repeats to the open screen only after setSendRepeatsToGui(true)
 * (no game code called it; mods with text fields did); 1.19.3 dropped the flag and always passes them (keyPress sends
 * action 1 and 2 alike to keyPressed). Asking for repeats is what 1.19.3 does; asking to stop them has no answer in
 * 1.19.3 and is left as 1.19.3 does it.
 *
 * Fabric's 1.19.3 post (https://fabricmc.net/2022/11/24/1193.html) does not mention setSendRepeatsToGui, and NeoForge's primers begin at 1.20.2 -> 1.20.3; this is from the game's code alone.
 */
public final class KeyboardHandler {

    private KeyboardHandler() {}

    public static void setSendRepeatsToGui(net.minecraft.client.KeyboardHandler self, boolean send) {
    }
}

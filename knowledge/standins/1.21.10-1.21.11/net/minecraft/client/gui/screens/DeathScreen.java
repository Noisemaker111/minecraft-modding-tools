package mmt.standin.net.minecraft.client.gui.screens;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 1.21.10's new DeathScreen(causeOfDeath, hardcore) on 1.21.11.
 *
 * Read from both releases: 1.21.10's death screen read the client's own player (minecraft.player) when it showed the
 * score and when its buttons respawned. 1.21.11's takes that player when it is made (new DeathScreen(cause, hardcore,
 * player)), reading the score from it at once, and the game's own call passes minecraft.player; so the old call passes
 * the same player.
 *
 * Agrees with NeoForge's 1.21.10 -> 1.21.11 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.11/index.md, CC BY 4.0):
 * "DeathScreen now takes in the LocalPlayer".
 */
public final class DeathScreen {

    private DeathScreen() {}

    public static net.minecraft.client.gui.screens.DeathScreen init(Component causeOfDeath, boolean hardcore) {
        return new net.minecraft.client.gui.screens.DeathScreen(causeOfDeath, hardcore, Minecraft.getInstance().player);
    }
}

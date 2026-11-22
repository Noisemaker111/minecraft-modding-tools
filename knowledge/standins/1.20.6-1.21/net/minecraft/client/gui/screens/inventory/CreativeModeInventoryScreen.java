package mmt.standin.net.minecraft.client.gui.screens.inventory;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;

/**
 * 1.20.6's CreativeModeInventoryScreen on 1.21.
 *
 * Read from both releases: 1.20.6's constructor took the Player whose inventory it shows, always the client's own player
 * (Minecraft.player, a LocalPlayer, in the game's own callers). 1.21's takes that LocalPlayer.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0): "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen now takes in a LocalPlayer instead of a Player".
 */
public final class CreativeModeInventoryScreen {

    private CreativeModeInventoryScreen() {}

    public static net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen init(Player player, FeatureFlagSet features, boolean operatorTab) {
        return new net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen((LocalPlayer) player, features, operatorTab);
    }
}

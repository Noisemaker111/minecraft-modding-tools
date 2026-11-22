package mmt.standin.net.minecraft.client.gui.screens.inventory;

import net.minecraft.client.Minecraft;

/**
 * 1.17.1's EffectRenderingInventoryScreen.doRenderEffects, read on 1.18.
 *
 * Read from both releases: 1.17.1 set doRenderEffects in checkEffectRendering (on init) to whether the player had any
 * active effect, shifting the screen right to make room, and drew the effects beside it when set. 1.18 dropped the field
 * and the shift: render draws the effects to the right of the screen when the player has active effects and
 * canSeeEffects() finds at least 32 pixels free there. Reading the field answers that same question.
 *
 * Fabric's 1.18 post (https://fabricmc.net/2021/11/30/118.html) does not mention the inventory's effects; this is from
 * the game's code alone.
 */
public final class EffectRenderingInventoryScreen {

    private EffectRenderingInventoryScreen() {}

    public static boolean doRenderEffects(net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen self) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && !minecraft.player.getActiveEffects().isEmpty() && self.canSeeEffects();
    }
}

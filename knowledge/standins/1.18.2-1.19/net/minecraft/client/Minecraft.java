package mmt.standin.net.minecraft.client;

import net.minecraft.client.renderer.ItemInHandRenderer;

/**
 * 1.18.2's Minecraft.getItemInHandRenderer() on 1.19.
 *
 * Read from both releases: 1.18.2's Minecraft made the client's one ItemInHandRenderer and handed it to the game
 * renderer; 1.19's EntityRenderDispatcher makes it (getItemInHandRenderer()) and Minecraft hands that one to the game
 * renderer. It is the same single renderer, asked of the dispatcher.
 *
 * Fabric's 1.19 post (https://fabricmc.net/2022/06/07/119.html) does not mention getItemInHandRenderer, and NeoForge's primers begin at 1.20.2 -> 1.20.3; this is from the game's code alone.
 */
public final class Minecraft {

    private Minecraft() {}

    public static ItemInHandRenderer getItemInHandRenderer(net.minecraft.client.Minecraft self) {
        return self.getEntityRenderDispatcher().getItemInHandRenderer();
    }
}

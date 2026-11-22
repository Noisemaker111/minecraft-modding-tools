package mmt.standin.net.minecraft.client;

import com.mojang.blaze3d.pipeline.RenderTarget;

/**
 * 26.1.2's Minecraft on 26.2.
 *
 * Read from both releases: 26.1.2's Minecraft.getMainRenderTarget() returned the main render target; 26.2 keeps it on the
 * GameRenderer (minecraft.gameRenderer.mainRenderTarget()), the target the game itself draws to. 26.1.2's
 * isSingleplayer() was a single-player server that is not opened to the network (getSingleplayerServer() != null and not
 * isPublished()); 26.2 drops it and asks the same where it pauses the game. So each old method reads the same thing where
 * 26.2 keeps it.
 *
 * Agrees with NeoForge's 26.1.2 -> 26.2 primer (https://github.com/neoforged/.github/blob/main/primers/26.2/index.md, CC BY 4.0): "getMainRenderTarget -> GameRenderer#mainRenderTarget", and whose list of removals names "Minecraft: isSingleplayer".
 */
public final class Minecraft {

    private Minecraft() {}

    public static RenderTarget getMainRenderTarget(net.minecraft.client.Minecraft self) {
        return self.gameRenderer.mainRenderTarget();
    }

    public static boolean isSingleplayer(net.minecraft.client.Minecraft self) {
        return self.getSingleplayerServer() != null && !self.getSingleplayerServer().isPublished();
    }
}

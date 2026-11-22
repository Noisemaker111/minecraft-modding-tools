package mmt.standin.com.mojang.blaze3d.systems;

import java.util.function.Supplier;

/**
 * 1.17.1's RenderSystem.assertThread(Supplier) on 1.18.
 *
 * Read from both releases: 1.17.1's assertThread asked the supplier (RenderSystem::isOnRenderThread and the like) and
 * threw IllegalStateException("Rendersystem called from wrong thread") when it answered false. 1.18 replaced it with one
 * method per check (assertOnRenderThread, assertOnGameThreadOrInit, ...) that throw the same exception; the checks
 * themselves (isOnRenderThread, isOnGameThread, isInInitPhase) are still public. This is 1.17.1's method as it was.
 *
 * Fabric's 1.18 post (https://fabricmc.net/2021/11/30/118.html) does not mention RenderSystem; this is from the game's
 * code alone.
 */
public final class RenderSystem {

    private RenderSystem() {}

    public static void assertThread(Supplier check) {
        if (!((Boolean) check.get()).booleanValue()) {
            throw new IllegalStateException("Rendersystem called from wrong thread");
        }
    }
}

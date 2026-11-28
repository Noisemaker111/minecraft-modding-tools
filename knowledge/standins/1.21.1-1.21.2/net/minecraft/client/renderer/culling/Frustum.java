package mmt.standin.net.minecraft.client.renderer.culling;

import net.minecraft.world.phys.AABB;

/**
 * 1.21.1's Frustum.cubeInFrustum(minX, minY, minZ, maxX, maxY, maxZ) (private, opened by mods' access transformers) on 1.21.2.
 *
 * Read from both releases: 1.21.1's returned whether the box was not wholly outside the frustum, and isVisible(AABB)
 * returned exactly that for the box's corners. 1.21.2's private cubeInFrustum returns an int (-2 inside, -1 crossing,
 * else the plane that culled it) and isVisible(AABB) is that result being -2 or -1. So the old answer is isVisible of
 * the same box.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "Frustum#cubeInFrustum now returns an int representing the index of the first plane that culled the box".
 */
public final class Frustum {

    private Frustum() {}

    public static boolean cubeInFrustum(net.minecraft.client.renderer.culling.Frustum self, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return self.isVisible(new AABB(minX, minY, minZ, maxX, maxY, maxZ));
    }
}

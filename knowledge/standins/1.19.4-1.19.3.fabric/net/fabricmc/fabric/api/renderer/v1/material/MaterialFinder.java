package mmt.standin.net.fabricmc.fabric.api.renderer.v1.material;

import net.fabricmc.fabric.api.renderer.v1.material.BlendMode;

/**
 * Fabric API 0.87's MaterialFinder.emissive(boolean) and blendMode(BlendMode) on Fabric API 0.76, for a port back from
 * 1.19.4 to 1.19.3.
 *
 * Read from both releases' Fabric API (0.87.2+1.19.4 and 0.76.1+1.19.3, fabric-renderer-api-v1): 1.19.4's Fabric
 * API dropped the sprite index from the quad and material calls (only sprite 0 was ever used) and named the old forms
 * anew; its own default bodies call the old forms with sprite index 0, which 0.76.1 has. emissive(b) is emissive(0, b) and blendMode(m) blendMode(0, m).
 */
public final class MaterialFinder {

    private MaterialFinder() {
    }

    public static net.fabricmc.fabric.api.renderer.v1.material.MaterialFinder emissive(net.fabricmc.fabric.api.renderer.v1.material.MaterialFinder self, boolean emissive) {
        return self.emissive(0, emissive);
    }

    public static net.fabricmc.fabric.api.renderer.v1.material.MaterialFinder blendMode(net.fabricmc.fabric.api.renderer.v1.material.MaterialFinder self, BlendMode mode) {
        return self.blendMode(0, mode);
    }
}

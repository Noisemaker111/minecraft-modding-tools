package mmt.standin.net.fabricmc.fabric.api.renderer.v1.model;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Fabric API 0.87's SpriteFinder.find(quad) on Fabric API 0.76, for a port back from 1.19.4 to 1.19.3.
 *
 * Read from both releases' Fabric API (0.87.2+1.19.4 and 0.76.1+1.19.3, fabric-renderer-api-v1): 1.19.4's Fabric
 * API dropped the sprite index from the quad and material calls (only sprite 0 was ever used) and named the old forms
 * anew; its own default bodies call the old forms with sprite index 0, which 0.76.1 has. find(quad) is find(quad, 0).
 */
public final class SpriteFinder {

    private SpriteFinder() {
    }

    public static TextureAtlasSprite find(net.fabricmc.fabric.api.renderer.v1.model.SpriteFinder self, net.fabricmc.fabric.api.renderer.v1.mesh.QuadView quad) {
        return self.find(quad, 0);
    }
}

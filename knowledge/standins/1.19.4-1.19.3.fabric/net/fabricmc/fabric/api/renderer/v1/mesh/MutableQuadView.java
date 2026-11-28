package mmt.standin.net.fabricmc.fabric.api.renderer.v1.mesh;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Fabric API 0.87's MutableQuadView.color, uv and spriteBake(sprite, flags) on Fabric API 0.76, for a port back from
 * 1.19.4 to 1.19.3.
 *
 * Read from both releases' Fabric API (0.87.2+1.19.4 and 0.76.1+1.19.3, fabric-renderer-api-v1): 1.19.4's Fabric
 * API dropped the sprite index from the quad and material calls (only sprite 0 was ever used) and named the old forms
 * anew; its own default bodies call the old forms with sprite index 0, which 0.76.1 has. The same calls as the QuadEmitter stand-in's, on a MutableQuadView.
 */
public final class MutableQuadView {

    private MutableQuadView() {
    }

    public static net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView color(net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView self, int vertex, int color) {
        return self.spriteColor(vertex, 0, color);
    }

    public static net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView color(net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView self, int c0, int c1, int c2, int c3) {
        return self.spriteColor(0, c0, c1, c2, c3);
    }

    public static net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView uv(net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView self, int vertex, float u, float v) {
        return self.sprite(vertex, 0, u, v);
    }

    public static net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView spriteBake(net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView self, TextureAtlasSprite sprite, int flags) {
        return self.spriteBake(0, sprite, flags);
    }
}

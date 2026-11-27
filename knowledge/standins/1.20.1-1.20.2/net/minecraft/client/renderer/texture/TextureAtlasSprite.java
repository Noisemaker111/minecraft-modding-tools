package mmt.standin.net.minecraft.client.renderer.texture;

/**
 * 1.20.1's TextureAtlasSprite.getU(double) and getV(double) on 1.20.2.
 *
 * Read from both releases: 1.20.1's getU(u) was u0 + (u1 - u0) * (float) u / 16, a coordinate within the sprite given in
 * sixteenths, and getV(v) the same between v0 and v1. 1.20.2's getU(float) and getV(float) take the coordinate as a
 * fraction of the sprite, u0 + (u1 - u0) * u; the game's own callers divide what they passed before by 16
 * (ModelBlockRenderer, LiquidBlockRenderer). So the old call is the new one given a sixteenth of its argument.
 *
 * NeoForge's primers begin at 1.20.2 -> 1.20.3, and Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html) does
 * not mention sprites; this is from the game's code alone.
 */
public final class TextureAtlasSprite {

    private TextureAtlasSprite() {}

    public static float getU(net.minecraft.client.renderer.texture.TextureAtlasSprite self, double u) {
        return self.getU((float) u / 16.0F);
    }

    public static float getV(net.minecraft.client.renderer.texture.TextureAtlasSprite self, double v) {
        return self.getV((float) v / 16.0F);
    }
}

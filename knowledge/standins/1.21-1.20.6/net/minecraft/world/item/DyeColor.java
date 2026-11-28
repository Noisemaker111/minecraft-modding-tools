package mmt.standin.net.minecraft.world.item;

/**
 * 1.21's DyeColor.getTextureDiffuseColor() on 1.20.6, for a port back: the dye's color as one opaque ARGB int.
 *
 * Read from both releases: 1.21's DyeColor keeps textureDiffuseColor = ARGB32.opaque(color), the color it is made with;
 * 1.20.6's keeps the same color's red, green and blue as floats of 255 (getTextureDiffuseColors()), so each is scaled
 * back to its byte, with alpha 255.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`net.minecraft.world.item.DyeColor#getTextureDiffuseColors` -> `getTextureDiffuseColor`".
 */
public final class DyeColor {

    private DyeColor() {}

    public static int getTextureDiffuseColor(net.minecraft.world.item.DyeColor self) {
        float[] rgb = self.getTextureDiffuseColors();
        int r = Math.round(rgb[0] * 255.0F);
        int g = Math.round(rgb[1] * 255.0F);
        int b = Math.round(rgb[2] * 255.0F);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }
}

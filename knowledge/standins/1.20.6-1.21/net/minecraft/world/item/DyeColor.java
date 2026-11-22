package mmt.standin.net.minecraft.world.item;

import java.util.EnumMap;
import java.util.Map;

/**
 * 1.20.6's DyeColor on 1.21.
 *
 * Read from both releases: 1.20.6's DyeColor kept its texture colour as float[] {red, green, blue}, each 0-255 / 255,
 * made once from the colour int (textureDiffuseColors, getTextureDiffuseColors()). 1.21 keeps the colour as an opaque ARGB
 * int (getTextureDiffuseColor()), so the old array is made from it, once per colour as before.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0): "net.minecraft.world.item.DyeColor#getTextureDiffuseColors -> getTextureDiffuseColor".
 */
public final class DyeColor {

    private static final Map<net.minecraft.world.item.DyeColor, float[]> COLORS = new EnumMap<>(net.minecraft.world.item.DyeColor.class);

    private DyeColor() {}

    public static synchronized float[] getTextureDiffuseColors(net.minecraft.world.item.DyeColor self) {
        return COLORS.computeIfAbsent(self, color -> {
            int rgb = color.getTextureDiffuseColor();
            return new float[] {(rgb >> 16 & 255) / 255.0F, (rgb >> 8 & 255) / 255.0F, (rgb & 255) / 255.0F};
        });
    }

    public static float[] textureDiffuseColors(net.minecraft.world.item.DyeColor self) {
        return getTextureDiffuseColors(self);
    }
}

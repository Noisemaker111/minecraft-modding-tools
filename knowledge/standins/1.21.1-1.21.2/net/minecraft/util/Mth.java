package mmt.standin.net.minecraft.util;

import net.minecraft.util.ARGB;


/**
 * 1.21.1's Mth.color(float r, float g, float b) on 1.21.2.
 *
 * Read from both releases: 1.21.1 returned ARGB32.color(0, floor(r * 255), floor(g * 255), floor(b * 255)), an RGB
 * value with alpha 0. 1.21.2 moved the color helpers to ARGB (ARGB.color(a, r, g, b) packs the same way) and dropped this
 * one. So it is ARGB.color with alpha 0 and the same floored channels.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "Mth#color -> ARGB#color".
 */
public final class Mth {

    private Mth() {}

    public static int color(float red, float green, float blue) {
        return ARGB.color(0, net.minecraft.util.Mth.floor(red * 255.0F), net.minecraft.util.Mth.floor(green * 255.0F), net.minecraft.util.Mth.floor(blue * 255.0F));
    }
}

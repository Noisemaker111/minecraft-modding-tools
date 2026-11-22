package mmt.standin.com.mojang.blaze3d.platform;

import net.minecraft.util.ARGB;

/**
 * 1.21.1's NativeImage on 1.21.2.
 *
 * Read from both releases: 1.21.1's blendPixel(x, y, abgr) blended an ABGR colour over the pixel (getPixelRGBA, which
 * gave the stored ABGR value) by its alpha, with the result's alpha a*a + a'*(1-a), and wrote it with setPixelRGBA.
 * 1.21.2 dropped blendPixel, made the ABGR accessors private and reads and writes pixels as ARGB (getPixel/setPixel,
 * converting with ARGB.fromABGR/toABGR). The same arithmetic on the same ABGR values, through the ARGB accessors.
 *
 * NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0) lists blendPixel among the removals; the arithmetic is the 1.21.1 game's.
 */
public final class NativeImage {

    private NativeImage() {}

    public static void blendPixel(com.mojang.blaze3d.platform.NativeImage self, int x, int y, int abgrColor) {
        if (self.format() != com.mojang.blaze3d.platform.NativeImage.Format.RGBA) {
            throw new UnsupportedOperationException("Can only call blendPixel with RGBA format");
        }
        int i = ARGB.toABGR(self.getPixel(x, y));
        float f = (abgrColor >>> 24) / 255.0F;
        float f1 = (abgrColor >> 16 & 255) / 255.0F;
        float f2 = (abgrColor >> 8 & 255) / 255.0F;
        float f3 = (abgrColor & 255) / 255.0F;
        float f4 = (i >>> 24) / 255.0F;
        float f5 = (i >> 16 & 255) / 255.0F;
        float f6 = (i >> 8 & 255) / 255.0F;
        float f7 = (i & 255) / 255.0F;
        float f8 = 1.0F - f;
        float a = Math.min(1.0F, f * f + f4 * f8);
        float b = Math.min(1.0F, f1 * f + f5 * f8);
        float g = Math.min(1.0F, f2 * f + f6 * f8);
        float r = Math.min(1.0F, f3 * f + f7 * f8);
        int abgr = (int) (a * 255.0F) << 24 | (int) (b * 255.0F) << 16 | (int) (g * 255.0F) << 8 | (int) (r * 255.0F);
        self.setPixel(x, y, ARGB.fromABGR(abgr));
    }
}

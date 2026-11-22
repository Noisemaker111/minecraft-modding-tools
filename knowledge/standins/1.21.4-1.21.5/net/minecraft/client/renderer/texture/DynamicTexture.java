package mmt.standin.net.minecraft.client.renderer.texture;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * 1.21.4's DynamicTexture on 1.21.5.
 *
 * Read from both releases: 1.21.4's new DynamicTexture(image) and new DynamicTexture(width, height, clear) made the GL
 * texture for the image (uploading it in the first case). 1.21.5's constructors take a label first, the name the
 * GpuDevice gives the texture for debugging (new DynamicTexture(Supplier<String>, NativeImage), new DynamicTexture(String,
 * int, int, boolean)), and otherwise do the same. The old call had no label, so these give one naming the class.
 *
 * Agrees with NeoForge's 1.21.4 -> 1.21.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.5/index.md, CC BY 4.0): "DynamicTexture now takes in the label of the texture".
 */
public final class DynamicTexture {

    private DynamicTexture() {}

    public static net.minecraft.client.renderer.texture.DynamicTexture init(NativeImage image) {
        return new net.minecraft.client.renderer.texture.DynamicTexture(() -> "Dynamic texture", image);
    }

    public static net.minecraft.client.renderer.texture.DynamicTexture init(int width, int height, boolean clear) {
        return new net.minecraft.client.renderer.texture.DynamicTexture("Dynamic texture", width, height, clear);
    }
}

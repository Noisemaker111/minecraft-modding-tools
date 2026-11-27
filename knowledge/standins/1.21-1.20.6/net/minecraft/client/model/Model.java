package mmt.standin.net.minecraft.client.model;

/**
 * 1.21's Model.renderToBuffer with an ARGB tint, and without one, on 1.20.6, for a port back.
 *
 * Read from both releases: 1.21's renderToBuffer(pose, consumer, light, overlay, color) tints every vertex by the ARGB
 * int, and its final renderToBuffer(pose, consumer, light, overlay) passes -1, opaque white; 1.20.6's
 * renderToBuffer(pose, consumer, light, overlay, red, green, blue, alpha) tints by four floats of 1, which its
 * VertexConsumer writes back as bytes (times 255), so each byte of the int is read as a float of 255.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`net.minecraft.client.model.Model#renderToBuffer` now takes in an integer representing the ARGB tint instead of four floats".
 */
public final class Model {

    private Model() {}

    static float red(int argb) {
        return (argb >> 16 & 255) / 255.0F;
    }

    static float green(int argb) {
        return (argb >> 8 & 255) / 255.0F;
    }

    static float blue(int argb) {
        return (argb & 255) / 255.0F;
    }

    static float alpha(int argb) {
        return (argb >>> 24) / 255.0F;
    }

    public static void renderToBuffer(net.minecraft.client.model.Model self, com.mojang.blaze3d.vertex.PoseStack pose, com.mojang.blaze3d.vertex.VertexConsumer consumer, int light, int overlay, int color) {
        self.renderToBuffer(pose, consumer, light, overlay, red(color), green(color), blue(color), alpha(color));
    }

    public static void renderToBuffer(net.minecraft.client.model.Model self, com.mojang.blaze3d.vertex.PoseStack pose, com.mojang.blaze3d.vertex.VertexConsumer consumer, int light, int overlay) {
        self.renderToBuffer(pose, consumer, light, overlay, 1.0F, 1.0F, 1.0F, 1.0F);
    }
}

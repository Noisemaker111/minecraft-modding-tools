package mmt.standin.net.minecraft.client.model.geom;

/**
 * 1.21's ModelPart.render(pose, consumer, light, overlay, color) on 1.20.6, for a port back: 1.20.6's render with the
 * tint's four floats.
 *
 * Read from both releases: the two draw the part and its children the same way, tinted by 1.21's ARGB int or by
 * 1.20.6's red, green, blue and alpha floats of 1; each byte of the int is read as a float of 255.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`net.minecraft.client.model.geom.ModelPart#render`, `$Cube#compile` now takes in an integer representing the ARGB tint instead of four floats".
 */
public final class ModelPart {

    private ModelPart() {}

    public static void render(net.minecraft.client.model.geom.ModelPart self, com.mojang.blaze3d.vertex.PoseStack pose, com.mojang.blaze3d.vertex.VertexConsumer consumer, int light, int overlay, int color) {
        self.render(pose, consumer, light, overlay, (color >> 16 & 255) / 255.0F, (color >> 8 & 255) / 255.0F, (color & 255) / 255.0F, (color >>> 24) / 255.0F);
    }
}

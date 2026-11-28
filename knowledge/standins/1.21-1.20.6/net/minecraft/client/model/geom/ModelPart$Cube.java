package mmt.standin.net.minecraft.client.model.geom;

/**
 * 1.21's ModelPart.Cube.compile(pose, consumer, light, overlay, color) on 1.20.6, for a port back: 1.20.6's compile
 * with the tint's four floats (see the stand-in ModelPart).
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`net.minecraft.client.model.geom.ModelPart#render`, `$Cube#compile` now takes in an integer representing the ARGB tint instead of four floats".
 */
public final class ModelPart$Cube {

    private ModelPart$Cube() {}

    public static void compile(net.minecraft.client.model.geom.ModelPart.Cube self, com.mojang.blaze3d.vertex.PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer consumer, int light, int overlay, int color) {
        self.compile(pose, consumer, light, overlay, (color >> 16 & 255) / 255.0F, (color >> 8 & 255) / 255.0F, (color & 255) / 255.0F, (color >>> 24) / 255.0F);
    }
}

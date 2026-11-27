package mmt.standin.net.minecraft.client.model;

/**
 * 1.21's AgeableListModel.renderToBuffer(pose, consumer, light, overlay, color) on 1.20.6, for a port back: its own
 * override, called with the four floats 1.20.6's takes (see the stand-in Model).
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`net.minecraft.client.model.Model#renderToBuffer` now takes in an integer representing the ARGB tint instead of four floats".
 */
public final class AgeableListModel {

    private AgeableListModel() {}

    public static void renderToBuffer(net.minecraft.client.model.AgeableListModel self, com.mojang.blaze3d.vertex.PoseStack pose, com.mojang.blaze3d.vertex.VertexConsumer consumer, int light, int overlay, int color) {
        self.renderToBuffer(pose, consumer, light, overlay, Model.red(color), Model.green(color), Model.blue(color), Model.alpha(color));
    }
}

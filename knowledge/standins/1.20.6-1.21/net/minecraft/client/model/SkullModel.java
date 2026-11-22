package mmt.standin.net.minecraft.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.FastColor;

/**
 * 1.20.6's SkullModel on 1.21.
 *
 * Read from both releases: 1.20.6's renderToBuffer(pose, buffer, light, overlay, red, green, blue, alpha) rendered the
 * skull's root part tinted by the four channels (0 to 1). 1.21's takes one ARGB tint int, which
 * FastColor.ARGB32.colorFromFloat(alpha, red, green, blue) makes from the same channels.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0): "net.minecraft.client.model.Model#renderToBuffer now takes in an integer representing the ARGB tint instead of four floats".
 */
public final class SkullModel {

    private SkullModel() {}

    public static void renderToBuffer(net.minecraft.client.model.SkullModel self, PoseStack pose, VertexConsumer buffer, int light, int overlay, float red, float green, float blue, float alpha) {
        self.renderToBuffer(pose, buffer, light, overlay, FastColor.ARGB32.colorFromFloat(alpha, red, green, blue));
    }
}

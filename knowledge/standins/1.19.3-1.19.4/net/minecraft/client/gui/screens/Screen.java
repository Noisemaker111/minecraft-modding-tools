package mmt.standin.net.minecraft.client.gui.screens;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;

/**
 * 1.19.3's Screen.renderDirtBackground(vOffset) on 1.19.4.
 *
 * Read from both releases: 1.19.3's renderDirtBackground(vOffset) drew the options background texture over the whole
 * screen in 32-pixel tiles, shaded to grey 64, its texture moved down by vOffset tiles, with the Tesselator. 1.19.4's
 * renderDirtBackground(poseStack) draws it without an offset (a blit at a quarter brightness). This is 1.19.3's drawing,
 * with the same Tesselator, shader and texture calls, which 1.19.4 keeps, and Screen.BACKGROUND_LOCATION
 * (GuiComponent's, which 1.19.4 keeps).
 *
 * Fabric's 1.19.4 post (https://fabricmc.net/2023/03/01/1194.html) does not mention renderDirtBackground, and NeoForge's primers begin at 1.20.2 -> 1.20.3; this is from the game's code alone.
 */
public final class Screen {

    private Screen() {}

    public static void renderDirtBackground(net.minecraft.client.gui.screens.Screen self, int vOffset) {
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, net.minecraft.client.gui.GuiComponent.BACKGROUND_LOCATION);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        builder.vertex(0.0, self.height, 0.0).uv(0.0F, self.height / 32.0F + vOffset).color(64, 64, 64, 255).endVertex();
        builder.vertex(self.width, self.height, 0.0).uv(self.width / 32.0F, self.height / 32.0F + vOffset).color(64, 64, 64, 255).endVertex();
        builder.vertex(self.width, 0.0, 0.0).uv(self.width / 32.0F, vOffset).color(64, 64, 64, 255).endVertex();
        builder.vertex(0.0, 0.0, 0.0).uv(0.0F, vOffset).color(64, 64, 64, 255).endVertex();
        tesselator.end();
    }
}

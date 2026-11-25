package mmt.standin.net.minecraft.client.renderer;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import mmt.standin.com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.GameRenderer;

/**
 * 1.20's render types for screens (gui, guiOverlay, guiTextHighlight, guiGhostRecipeOverlay), written on 1.19.4.
 *
 * Read from both releases: 1.20 made each one position-color quads with translucent blending (enableBlend,
 * blendFuncSeparate(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ONE_MINUS_SRC_ALPHA), cleared with disableBlend and
 * defaultBlendFunc) and a depth setting: gui tests LEQUAL (enableDepthTest, depthFunc(515), cleared with
 * disableDepthTest and depthFunc(515)); guiOverlay leaves the depth test as it is and writes no depth (depthMask(false),
 * cleared with depthMask(true)); guiTextHighlight leaves the depth test and draws with the OR_REVERSE logic op
 * (enableColorLogicOp, logicOp(OR_REVERSE), cleared with disableColorLogicOp); guiGhostRecipeOverlay tests GREATER
 * (depthFunc(516)) and writes no depth. 1.20 gave each its own shader (rendertype_gui and its siblings); 1.19.4 drew the
 * same quads with the position-color shader (GuiComponent.fill, EditBox.renderHighlight), which these use. Built from
 * the RenderSystem calls 1.20's RenderStateShards make, through RenderType's public constructor (the class is abstract
 * with nothing left to write), as 256-byte buffers as 1.20's are.
 *
 * And 1.20's end(buffer, sorting), which is 1.19.4's end(buffer, x, y, z) with the point the sorting is by distance to,
 * the origin for any other (see VertexSorting; 1.19.4's BufferSource.endBatch passes 0, 0, 0 where 1.20's passes
 * RenderSystem.getVertexSorting()).
 */
public final class RenderType {

    private static final net.minecraft.client.renderer.RenderType GUI = make("gui", () -> {
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(515);
    }, () -> {
        RenderSystem.disableDepthTest();
        RenderSystem.depthFunc(515);
    });

    private static final net.minecraft.client.renderer.RenderType GUI_OVERLAY = make("gui_overlay", () -> RenderSystem.depthMask(false), () -> RenderSystem.depthMask(true));

    private static final net.minecraft.client.renderer.RenderType GUI_TEXT_HIGHLIGHT = make("gui_text_highlight", () -> {
        RenderSystem.enableColorLogicOp();
        RenderSystem.logicOp(GlStateManager.LogicOp.OR_REVERSE);
    }, RenderSystem::disableColorLogicOp);

    private static final net.minecraft.client.renderer.RenderType GUI_GHOST_RECIPE_OVERLAY = make("gui_ghost_recipe_overlay", () -> {
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(516);
        RenderSystem.depthMask(false);
    }, () -> {
        RenderSystem.depthMask(true);
        RenderSystem.disableDepthTest();
        RenderSystem.depthFunc(515);
    });

    private RenderType() {
    }

    private static net.minecraft.client.renderer.RenderType make(String name, Runnable setup, Runnable clear) {
        return new net.minecraft.client.renderer.RenderType(name, DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 256, false, false, () -> {
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            setup.run();
        }, () -> {
            clear.run();
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
        }) {
        };
    }

    public static void end(net.minecraft.client.renderer.RenderType self, com.mojang.blaze3d.vertex.BufferBuilder buffer, VertexSorting sorting) {
        if (sorting instanceof VertexSorting.Point p) {
            self.end(buffer, Math.round(p.x), Math.round(p.y), Math.round(p.z));
        } else {
            self.end(buffer, 0, 0, 0);
        }
    }

    public static net.minecraft.client.renderer.RenderType gui() {
        return GUI;
    }

    public static net.minecraft.client.renderer.RenderType guiOverlay() {
        return GUI_OVERLAY;
    }

    public static net.minecraft.client.renderer.RenderType guiTextHighlight() {
        return GUI_TEXT_HIGHLIGHT;
    }

    public static net.minecraft.client.renderer.RenderType guiGhostRecipeOverlay() {
        return GUI_GHOST_RECIPE_OVERLAY;
    }
}

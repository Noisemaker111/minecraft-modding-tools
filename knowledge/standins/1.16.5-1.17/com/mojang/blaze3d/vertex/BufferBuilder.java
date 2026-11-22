package mmt.standin.com.mojang.blaze3d.vertex;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.function.Supplier;
import net.minecraft.client.renderer.GameRenderer;

/**
 * 1.16.5's BufferBuilder.begin(int glMode, VertexFormat) on 1.17.
 *
 * Read from both releases: 1.16.5's begin took the OpenGL primitive as an int (GL_LINES 1, GL_LINE_STRIP 3,
 * GL_TRIANGLES 4, GL_TRIANGLE_STRIP 5, GL_TRIANGLE_FAN 6, GL_QUADS 7), and the vertices were drawn by the fixed-function
 * pipeline for that format. 1.17's begin takes a VertexFormat.Mode, and a draw needs a shader set
 * (RenderSystem.setShader), which 1.17's own code picks by the format before each such begin (GameRenderer's
 * getPositionShader for POSITION, getPositionColorShader for POSITION_COLOR, getPositionTexShader for POSITION_TEX, and
 * so on). OpenGL's thin lines are 1.17's DEBUG_LINES and DEBUG_LINE_STRIP (its LINES and LINE_STRIP are quads with
 * normals for the line shader). This picks the mode by the OpenGL primitive and the shader by the format, as 1.17's code
 * does for the same format; a format with no shader of its own keeps the one set.
 *
 * Fabric's 1.17 post for developers (https://fabricmc.net/2021/05/27/117-for-developers.html) covers Java 16, Fabric API and
 * Yarn, not this; this is from the game's code alone.
 */
public final class BufferBuilder {

    private BufferBuilder() {}

    public static void begin(com.mojang.blaze3d.vertex.BufferBuilder self, int glMode, VertexFormat format) {
        VertexFormat.Mode mode = switch (glMode) {
            case 1 -> VertexFormat.Mode.DEBUG_LINES;
            case 3 -> VertexFormat.Mode.DEBUG_LINE_STRIP;
            case 4 -> VertexFormat.Mode.TRIANGLES;
            case 5 -> VertexFormat.Mode.TRIANGLE_STRIP;
            case 6 -> VertexFormat.Mode.TRIANGLE_FAN;
            case 7 -> VertexFormat.Mode.QUADS;
            default -> throw new IllegalArgumentException("OpenGL primitive " + glMode + " has no vertex mode in 1.17");
        };
        Supplier shader = shader(format);
        if (shader != null) {
            com.mojang.blaze3d.systems.RenderSystem.setShader(shader);
        }
        self.begin(mode, format);
    }

    static Supplier shader(VertexFormat format) {
        if (format == DefaultVertexFormat.POSITION) {
            return GameRenderer::getPositionShader;
        }
        if (format == DefaultVertexFormat.POSITION_COLOR) {
            return GameRenderer::getPositionColorShader;
        }
        if (format == DefaultVertexFormat.POSITION_TEX) {
            return GameRenderer::getPositionTexShader;
        }
        if (format == DefaultVertexFormat.POSITION_COLOR_TEX) {
            return GameRenderer::getPositionColorTexShader;
        }
        if (format == DefaultVertexFormat.POSITION_TEX_COLOR) {
            return GameRenderer::getPositionTexColorShader;
        }
        if (format == DefaultVertexFormat.POSITION_COLOR_LIGHTMAP) {
            return GameRenderer::getPositionColorLightmapShader;
        }
        if (format == DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP) {
            return GameRenderer::getPositionColorTexLightmapShader;
        }
        if (format == DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR) {
            return GameRenderer::getPositionTexLightmapColorShader;
        }
        if (format == DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL) {
            return GameRenderer::getPositionTexColorNormalShader;
        }
        return null;
    }
}

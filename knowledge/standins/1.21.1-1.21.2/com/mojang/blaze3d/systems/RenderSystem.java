package mmt.standin.com.mojang.blaze3d.systems;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Matrix4f;

/**
 * 1.21.1's RenderSystem.setProjectionMatrix(Matrix4f, VertexSorting) on 1.21.2.
 *
 * Read from both releases: 1.21.1 kept the projection matrix and the vertex sorting used for translucent geometry, set
 * together (on the render thread, or recorded for it). 1.21.2 keeps the matrix and a ProjectionType, whose
 * vertexSorting() gives the sorting: PERSPECTIVE sorts by VertexSorting.DISTANCE_TO_ORIGIN and ORTHOGRAPHIC by
 * VertexSorting.ORTHOGRAPHIC_Z, the two the game passed in 1.21.1 (the level and the GUI). So the old sorting picks the type
 * that sorts the same way; any other sorting is a distance sort, which PERSPECTIVE is.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "setProjectionMatrix now takes in a ProjectionType than just the VertexSorting" and "the VertexSorting is accessible on the ProjectionType".
 */
public final class RenderSystem {

    private RenderSystem() {}

    public static void setProjectionMatrix(Matrix4f matrix, VertexSorting sorting) {
        ProjectionType type = sorting == VertexSorting.ORTHOGRAPHIC_Z ? ProjectionType.ORTHOGRAPHIC : ProjectionType.PERSPECTIVE;
        if (com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
            com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(matrix, type);
        } else {
            Matrix4f copy = new Matrix4f(matrix);
            com.mojang.blaze3d.systems.RenderSystem.recordRenderCall(() -> com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(copy, type));
        }
    }
}

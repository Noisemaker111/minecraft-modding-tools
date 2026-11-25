package mmt.standin.com.mojang.blaze3d.systems;

import mmt.standin.com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Matrix4f;

/**
 * 1.20's RenderSystem.setProjectionMatrix(matrix, sorting) and getVertexSorting() on 1.19.4.
 *
 * Read from both releases: 1.20 keeps a sorting beside the projection matrix (GameRenderer passes DISTANCE_TO_ORIGIN
 * for the world, the GUI ORTHOGRAPHIC_Z, where 1.19.4's same calls are setProjectionMatrix(matrix)), and
 * MultiBufferSource.BufferSource.endBatch hands getVertexSorting() to RenderType.end where 1.19.4's passes the origin.
 * 1.19.4 always sorted by distance to the origin there, which is what getVertexSorting() gives here.
 */
public final class RenderSystem {

    private RenderSystem() {
    }

    public static void setProjectionMatrix(Matrix4f matrix, VertexSorting sorting) {
        com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(matrix);
    }

    public static VertexSorting getVertexSorting() {
        return VertexSorting.DISTANCE_TO_ORIGIN;
    }
}

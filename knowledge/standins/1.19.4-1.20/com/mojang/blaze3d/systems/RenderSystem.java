package mmt.standin.com.mojang.blaze3d.systems;

import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Matrix4f;

/**
 * 1.19.4's RenderSystem.setProjectionMatrix(matrix) on 1.20.
 *
 * Read from both releases: 1.19.4's setProjectionMatrix(matrix) set the projection only; translucent quads were sorted
 * by their distance from the sort origin (BufferBuilder.setQuadSortOrigin). 1.20's setProjectionMatrix(matrix, sorting)
 * also sets how quads are sorted, and RenderSystem starts with VertexSorting.DISTANCE_TO_ORIGIN, the old sorting.
 *
 * Fabric's 1.20 post (https://fabricmc.net/2023/05/25/120.html) does not mention setProjectionMatrix, and NeoForge's primers begin at 1.20.2 -> 1.20.3; this is from the game's code alone.
 */
public final class RenderSystem {

    private RenderSystem() {}

    public static void setProjectionMatrix(Matrix4f matrix) {
        com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(matrix, VertexSorting.DISTANCE_TO_ORIGIN);
    }
}

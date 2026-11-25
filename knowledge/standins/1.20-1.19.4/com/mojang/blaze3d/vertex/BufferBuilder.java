package mmt.standin.com.mojang.blaze3d.vertex;

/**
 * 1.20's BufferBuilder.setQuadSorting on 1.19.4.
 *
 * Read from both releases: 1.20's setQuadSorting(sorting) keeps the sorting its translucent quads are put in order by;
 * 1.19.4's setQuadSortOrigin(x, y, z) keeps the one point they are sorted by distance to. A sorting by distance to a
 * point gives that point; any other the origin (see VertexSorting).
 */
public final class BufferBuilder {

    private BufferBuilder() {
    }

    public static void setQuadSorting(com.mojang.blaze3d.vertex.BufferBuilder self, VertexSorting sorting) {
        if (sorting instanceof VertexSorting.Point p) {
            self.setQuadSortOrigin(p.x, p.y, p.z);
        } else {
            self.setQuadSortOrigin(0.0F, 0.0F, 0.0F);
        }
    }
}

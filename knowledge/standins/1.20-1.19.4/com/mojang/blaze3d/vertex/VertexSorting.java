package mmt.standin.com.mojang.blaze3d.vertex;

import java.util.Arrays;
import org.joml.Vector3f;

/**
 * 1.20's VertexSorting on 1.19.4: the class itself, which 1.19.4 does not have yet.
 *
 * Read from both releases: 1.20 made the order translucent quads are drawn in a value of its own (an interface mods
 * implement: sort(Vector3f[]) gives the quads' order, farthest first), with DISTANCE_TO_ORIGIN, ORTHOGRAPHIC_Z (by -z,
 * for screens) and byDistance. Its sort is 1.20's: a stable sort (fastutil's mergeSort there, Java's here) of the quads
 * by their distance, farthest first. 1.19.4 sorted by distance to one point only (BufferBuilder.setQuadSortOrigin(x, y, z), and
 * RenderType.end(buffer, x, y, z)), and its MultiBufferSource.BufferSource.endBatch passes 0, 0, 0 where 1.20's passes
 * RenderSystem.getVertexSorting(). A sorting by distance to a point (Point) gives that point where 1.19.4 takes one; any
 * other gives the origin, as 1.19.4's own screens and world drew.
 */
public interface VertexSorting {

    VertexSorting DISTANCE_TO_ORIGIN = byDistance(0.0F, 0.0F, 0.0F);

    VertexSorting ORTHOGRAPHIC_Z = byDistance((DistanceFunction) v -> -v.z());

    static VertexSorting byDistance(float x, float y, float z) {
        return new Point(x, y, z);
    }

    static VertexSorting byDistance(Vector3f point) {
        return new Point(point.x(), point.y(), point.z());
    }

    static VertexSorting byDistance(DistanceFunction distance) {
        return points -> sorted(points, distance);
    }

    int[] sort(Vector3f[] points);

    private static int[] sorted(Vector3f[] points, DistanceFunction distance) {
        float[] far = new float[points.length];
        Integer[] order = new Integer[points.length];
        for (int i = 0; i < points.length; i++) {
            far[i] = distance.apply(points[i]);
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Float.compare(far[b], far[a]));
        int[] out = new int[order.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = order[i];
        }
        return out;
    }

    interface DistanceFunction {
        float apply(Vector3f point);
    }

    /** A sorting by distance to one point, the one sorting 1.19.4 knows: the point is what 1.19.4's calls take. */
    final class Point implements VertexSorting {

        public final float x;
        public final float y;
        public final float z;

        Point(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public int[] sort(Vector3f[] points) {
            Vector3f origin = new Vector3f(x, y, z);
            return sorted(points, origin::distanceSquared);
        }
    }
}

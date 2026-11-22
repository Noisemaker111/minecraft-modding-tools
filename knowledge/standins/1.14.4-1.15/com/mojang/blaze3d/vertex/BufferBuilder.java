package mmt.standin.com.mojang.blaze3d.vertex;

/**
 * 1.14.4's BufferBuilder.vertex, uv, uv2 and color, each returning the BufferBuilder, on 1.15.
 *
 * Read from both releases: 1.14.4's BufferBuilder wrote the current vertex element (position, texture coordinate as
 * doubles, light map coordinates, colour as ints or floats) and returned itself for the next one. 1.15's BufferBuilder
 * is a VertexConsumer whose vertex(double, double, double), uv(float, float), uv2(int, int) and color(int, int, int, int)
 * / color(float, float, float, float) write the same elements in the same order and return the consumer (itself). Each
 * calls 1.15's and returns the builder, the texture coordinate as floats as 1.15 stores it.
 *
 * Fabric's 1.15 post (https://fabricmc.net/2019/12/10/115.html) does not mention it; this is from the game's code alone.
 */
public final class BufferBuilder {

    private BufferBuilder() {}

    public static com.mojang.blaze3d.vertex.BufferBuilder vertex(com.mojang.blaze3d.vertex.BufferBuilder self, double x, double y, double z) {
        self.vertex(x, y, z);
        return self;
    }

    public static com.mojang.blaze3d.vertex.BufferBuilder uv(com.mojang.blaze3d.vertex.BufferBuilder self, double u, double v) {
        self.uv((float) u, (float) v);
        return self;
    }

    public static com.mojang.blaze3d.vertex.BufferBuilder uv2(com.mojang.blaze3d.vertex.BufferBuilder self, int u, int v) {
        self.uv2(u, v);
        return self;
    }

    public static com.mojang.blaze3d.vertex.BufferBuilder color(com.mojang.blaze3d.vertex.BufferBuilder self, int r, int g, int b, int a) {
        self.color(r, g, b, a);
        return self;
    }

    public static com.mojang.blaze3d.vertex.BufferBuilder color(com.mojang.blaze3d.vertex.BufferBuilder self, float r, float g, float b, float a) {
        self.color(r, g, b, a);
        return self;
    }
}

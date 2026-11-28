package mmt.standin.com.mojang.blaze3d.vertex;

/**
 * 1.21's BufferBuilder on 1.20.6, for a port back: its vertex calls are the stand-in VertexConsumer's, and the mesh it
 * builds is 1.20.6's RenderedBuffer.
 *
 * Read from both releases: 1.21's BufferBuilder writes each vertex as VertexConsumer's addVertex and setters say (its
 * own overrides of them only write faster), and build() ends the last vertex and returns the mesh, or null when no
 * vertex was added; buildOrThrow() throws "BufferBuilder was empty" instead. 1.20.6's BufferBuilder.end() returns the
 * batch as a RenderedBuffer (which 1.21's MeshData replaced: BufferUploader.drawWithShader takes one in each release),
 * endOrDiscardIfEmpty() null when it has no vertex. 1.21 makes a builder on a ByteBufferBuilder, the memory it writes
 * to, begun with the mode and format (BufferBuilder(ByteBufferBuilder, Mode, VertexFormat)); 1.20.6's BufferBuilder is
 * its own memory (the game's MultiBufferSource holds BufferBuilders where 1.21's holds ByteBufferBuilders), begun with
 * begin(mode, format), so the builder on one is that one, begun, with any batch it left open ended and released as
 * 1.21 drops it.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "The `MeshData` provided, which contains the vertex and index buffer along with the draw state, can be built via
 * `BufferBuilder#buildOrThrow`. This replaces `BufferBuilder#end`." and "`com.mojang.blaze3d.vertex.BufferBuilder$RenderedBuffer` -> `MeshData`".
 */
public final class BufferBuilder {

    private BufferBuilder() {}

    public static com.mojang.blaze3d.vertex.BufferBuilder init(com.mojang.blaze3d.vertex.BufferBuilder memory, com.mojang.blaze3d.vertex.VertexFormat.Mode mode, com.mojang.blaze3d.vertex.VertexFormat format) {
        VertexConsumer.forget(memory);
        if (memory.building()) {
            memory.end().release();
        }
        memory.begin(mode, format);
        return memory;
    }

    public static com.mojang.blaze3d.vertex.BufferBuilder.RenderedBuffer buildOrThrow(com.mojang.blaze3d.vertex.BufferBuilder self) {
        com.mojang.blaze3d.vertex.BufferBuilder.RenderedBuffer mesh = build(self);
        if (mesh == null) {
            throw new IllegalStateException("BufferBuilder was empty");
        }
        return mesh;
    }

    public static com.mojang.blaze3d.vertex.BufferBuilder.RenderedBuffer build(com.mojang.blaze3d.vertex.BufferBuilder self) {
        VertexConsumer.endOpen(self);
        VertexConsumer.forget(self);
        return self.endOrDiscardIfEmpty();
    }

    public static com.mojang.blaze3d.vertex.VertexConsumer addVertex(com.mojang.blaze3d.vertex.BufferBuilder self, float x, float y, float z) {
        return VertexConsumer.addVertex(self, x, y, z);
    }

    public static void addVertex(com.mojang.blaze3d.vertex.BufferBuilder self, float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
        VertexConsumer.addVertex(self, x, y, z, color, u, v, overlay, light, nx, ny, nz);
    }

    public static com.mojang.blaze3d.vertex.VertexConsumer setColor(com.mojang.blaze3d.vertex.BufferBuilder self, int r, int g, int b, int a) {
        return VertexConsumer.setColor(self, r, g, b, a);
    }

    public static com.mojang.blaze3d.vertex.VertexConsumer setColor(com.mojang.blaze3d.vertex.BufferBuilder self, int argb) {
        return VertexConsumer.setColor(self, argb);
    }

    public static com.mojang.blaze3d.vertex.VertexConsumer setUv(com.mojang.blaze3d.vertex.BufferBuilder self, float u, float v) {
        return VertexConsumer.setUv(self, u, v);
    }

    public static com.mojang.blaze3d.vertex.VertexConsumer setUv1(com.mojang.blaze3d.vertex.BufferBuilder self, int u, int v) {
        return VertexConsumer.setUv1(self, u, v);
    }

    public static com.mojang.blaze3d.vertex.VertexConsumer setOverlay(com.mojang.blaze3d.vertex.BufferBuilder self, int overlay) {
        return VertexConsumer.setOverlay(self, overlay);
    }

    public static com.mojang.blaze3d.vertex.VertexConsumer setUv2(com.mojang.blaze3d.vertex.BufferBuilder self, int u, int v) {
        return VertexConsumer.setUv2(self, u, v);
    }

    public static com.mojang.blaze3d.vertex.VertexConsumer setLight(com.mojang.blaze3d.vertex.BufferBuilder self, int light) {
        return VertexConsumer.setLight(self, light);
    }

    public static com.mojang.blaze3d.vertex.VertexConsumer setNormal(com.mojang.blaze3d.vertex.BufferBuilder self, float x, float y, float z) {
        return VertexConsumer.setNormal(self, x, y, z);
    }
}

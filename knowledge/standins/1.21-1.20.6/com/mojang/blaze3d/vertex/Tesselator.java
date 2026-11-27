package mmt.standin.com.mojang.blaze3d.vertex;

/**
 * 1.21's Tesselator.begin(mode, format) on 1.20.6, for a port back: the tesselator's one builder, begun.
 *
 * Read from both releases: 1.21's begin(mode, format) returns a new BufferBuilder on the tesselator's one buffer, and a
 * builder begun before and never built is dropped with what it held; 1.20.6's Tesselator keeps one BufferBuilder
 * (getBuilder()), begun with begin(mode, format), which throws "Already building!" while a batch is open, so an open
 * batch is ended and its buffer released first, as 1.21 drops it.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "The `MultiBufferSource` constructs a `VertexConsumer` via `#getBuffer` while `Tesselator` does so via `Tesselator#begin`."
 */
public final class Tesselator {

    private Tesselator() {}

    public static com.mojang.blaze3d.vertex.BufferBuilder begin(com.mojang.blaze3d.vertex.Tesselator self, com.mojang.blaze3d.vertex.VertexFormat.Mode mode, com.mojang.blaze3d.vertex.VertexFormat format) {
        com.mojang.blaze3d.vertex.BufferBuilder builder = self.getBuilder();
        VertexConsumer.forget(builder);
        if (builder.building()) {
            builder.end().release();
        }
        builder.begin(mode, format);
        return builder;
    }

    public static void clear(com.mojang.blaze3d.vertex.Tesselator self) {
        com.mojang.blaze3d.vertex.BufferBuilder builder = self.getBuilder();
        VertexConsumer.forget(builder);
        if (builder.building()) {
            builder.end().release();
        }
        builder.clear();
    }
}

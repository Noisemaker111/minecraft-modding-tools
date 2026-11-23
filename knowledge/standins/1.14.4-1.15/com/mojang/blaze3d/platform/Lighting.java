package mmt.standin.com.mojang.blaze3d.platform;

import java.nio.FloatBuffer;

/**
 * 1.14.4's Lighting.getBuffer(r, g, b, a) on 1.15.
 *
 * Read from both releases: 1.14.4's getBuffer cleared Lighting's own 16-float buffer, put the four values in it, flipped
 * it and returned it, for a glLight or glMaterial call made right after (the buffer is overwritten by the next call).
 * 1.15 rewrote Lighting around matrices (setupLevel(Matrix4f), setupFor3DItems) and set its lights through
 * GlStateManager.setupLevelDiffuseLighting, so the helper and its buffer are gone. This keeps a buffer of its own, made
 * the way the game makes direct buffers (MemoryTracker.createFloatBuffer), and fills it the same way.
 *
 * NeoForge's 1.15 primer (https://github.com/neoforged/.github/blob/main/primers/1.15/index.md) says only to drop GL setup
 * the batched renderer now does; it does not name this method, so this is from the game's code alone.
 */
public final class Lighting {

    private static final FloatBuffer BUFFER = com.mojang.blaze3d.platform.MemoryTracker.createFloatBuffer(16);

    private Lighting() {}

    public static FloatBuffer getBuffer(float r, float g, float b, float a) {
        BUFFER.clear();
        BUFFER.put(r).put(g).put(b).put(a);
        BUFFER.flip();
        return BUFFER;
    }
}

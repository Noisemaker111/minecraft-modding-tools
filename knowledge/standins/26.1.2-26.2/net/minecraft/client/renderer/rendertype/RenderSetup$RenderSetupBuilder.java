package mmt.standin.net.minecraft.client.renderer.rendertype;

import net.minecraft.client.renderer.rendertype.RenderSetup;

/**
 * 26.1.2's RenderSetup.RenderSetupBuilder.bufferSize(size) on 26.2.
 *
 * Read from both releases: on 26.1.2 a render setup carried the size of the byte buffer its render type's vertices were
 * built into before upload (1536 bytes unless bufferSize(size) asked for more), each type having its own buffer. 26.2
 * builds every feature's vertices into the one staged vertex buffer the render buffers hold (RenderBuffers.stagedVertexBuffer,
 * growing as needed), so a render type has no buffer of its own to size and the builder keeps every other setting as it
 * was; the size asked for has nothing left to set.
 *
 * Agrees with NeoForge's 26.1 -> 26.2 primer (https://github.com/neoforged/.github/blob/main/primers/26.2/index.md, CC BY 4.0):
 * "$RenderSetupBuilder#bufferSize is removed"; "bufferSize, outlineBufferSource replaced by stagedVertexBuffer".
 */
public final class RenderSetup$RenderSetupBuilder {

    private RenderSetup$RenderSetupBuilder() {}

    public static RenderSetup.RenderSetupBuilder bufferSize(RenderSetup.RenderSetupBuilder self, int size) {
        return self;
    }
}

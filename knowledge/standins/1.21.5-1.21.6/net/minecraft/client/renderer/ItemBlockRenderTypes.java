package mmt.standin.net.minecraft.client.renderer;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * 1.21.5's ItemBlockRenderTypes on 1.21.6.
 *
 * Read from both releases: 1.21.5's getChunkRenderType(state) and getRenderLayer(fluidState) answered the chunk render type
 * a block or fluid is drawn with, one of RenderType.chunkBufferLayers() (solid, cutoutMipped, cutout, translucent,
 * tripwire). 1.21.6 answers the same choice, made the same way, as a ChunkSectionLayer (SOLID, CUTOUT_MIPPED, CUTOUT,
 * TRANSLUCENT, TRIPWIRE, each on the pipeline of the type of the same name). So each layer is answered with the type of
 * its name (translucent from RenderType's stand-in, the rest kept by 1.21.6).
 *
 * Agrees with NeoForge's 1.21.5 -> 1.21.6 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.6/index.md, CC BY 4.0):
 * "getChunkRenderType now returns a ChunkSectionLayer", "getRenderLayer(FluidState) now returns a ChunkSectionLayer".
 */
public final class ItemBlockRenderTypes {

    private ItemBlockRenderTypes() {}

    private static net.minecraft.client.renderer.RenderType type(ChunkSectionLayer layer) {
        return switch (layer) {
            case SOLID -> net.minecraft.client.renderer.RenderType.solid();
            case CUTOUT_MIPPED -> net.minecraft.client.renderer.RenderType.cutoutMipped();
            case CUTOUT -> net.minecraft.client.renderer.RenderType.cutout();
            case TRANSLUCENT -> mmt.standin.net.minecraft.client.renderer.RenderType.translucent();
            case TRIPWIRE -> net.minecraft.client.renderer.RenderType.tripwire();
        };
    }

    public static net.minecraft.client.renderer.RenderType getChunkRenderType(BlockState state) {
        return type(net.minecraft.client.renderer.ItemBlockRenderTypes.getChunkRenderType(state));
    }

    public static net.minecraft.client.renderer.RenderType getRenderLayer(FluidState state) {
        return type(net.minecraft.client.renderer.ItemBlockRenderTypes.getRenderLayer(state));
    }
}

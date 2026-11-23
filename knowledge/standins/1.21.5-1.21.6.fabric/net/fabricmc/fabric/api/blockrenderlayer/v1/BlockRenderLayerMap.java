package mmt.standin.net.fabricmc.fabric.api.blockrenderlayer.v1;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;

/**
 * Fabric API's fabric-blockrenderlayer-v1 BlockRenderLayerMap (Minecraft 1.21.5), which Fabric API for 1.21.6 removed.
 *
 * Read from both releases' Fabric API (0.128.2+1.21.5 and 0.128.2+1.21.6): the old module's interface, through
 * BlockRenderLayerMap.INSTANCE, put a block's or fluid's chunk render type (one of 1.21.5's solid, cutoutMipped, cutout,
 * translucent, tripwire) into ItemBlockRenderTypes, refusing a null block, fluid or type. 1.21.6's fabric-rendering-v1
 * has net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap with the same four methods as statics, taking the
 * ChunkSectionLayer 1.21.6 chooses chunk layers by (SOLID, CUTOUT_MIPPED, CUTOUT, TRANSLUCENT, TRIPWIRE, each on the
 * pipeline of the 1.21.5 type of the same name). So each call goes to the new class with the layer of its type's name.
 *
 * Agrees with NeoForge's 1.21.5 -> 1.21.6 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.6/index.md, CC BY 4.0):
 * "adding to ItemBlockRenderTypes#TYPE_BY_BLOCK must specify the ChunkSectionLayer instead of the associated RenderType".
 */
public interface BlockRenderLayerMap {

    BlockRenderLayerMap INSTANCE = new BlockRenderLayerMap() {
    };

    private static ChunkSectionLayer layer(RenderType type) {
        if (type == null) {
            throw new IllegalArgumentException("Request to map to a null RenderLayer");
        }
        String name = type.name;
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            if (layer.label().equals(name)) {
                return layer;
            }
        }
        throw new IllegalArgumentException(name + " is not a chunk render layer (solid, cutout_mipped, cutout, translucent, tripwire)");
    }

    default void putBlock(Block block, RenderType type) {
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putBlock(block, layer(type));
    }

    default void putBlocks(RenderType type, Block... blocks) {
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putBlocks(layer(type), blocks);
    }

    default void putFluid(Fluid fluid, RenderType type) {
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putFluid(fluid, layer(type));
    }

    default void putFluids(RenderType type, Fluid... fluids) {
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putFluids(layer(type), fluids);
    }
}

package mmt.standin.net.minecraft.world.level.levelgen.structure.templatesystem;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * 1.21.4's StructureTemplate.JigsawBlockInfo on 1.21.5.
 *
 * Read from both releases: 1.21.4's record held the jigsaw's pool as the ResourceLocation read from the block's "pool"
 * tag; 1.21.5's holds a ResourceKey<StructureTemplatePool> made from the same location (ResourceKey.create(
 * Registries.TEMPLATE_POOL, ...) in JigsawBlockInfo.of), so the old pool() is the key's location.
 *
 * Agrees with NeoForge's 1.21.4 -> 1.21.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.5/index.md, CC BY 4.0): "StructureTemplate$JigsawBlockInfo now takes in a ResourceKey to the StructureTemplatePool instead of a raw ResourceLocation".
 */
public final class StructureTemplate$JigsawBlockInfo {

    private StructureTemplate$JigsawBlockInfo() {}

    public static ResourceLocation pool(StructureTemplate.JigsawBlockInfo self) {
        return self.pool().location();
    }
}

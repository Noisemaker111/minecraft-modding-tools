package mmt.standin.net.minecraft.world.level.levelgen.structure.templatesystem;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ServerLevelAccessor;

/**
 * 1.19.4's StructureTemplate.processBlockInfos(LevelAccessor, ...) on 1.20.
 *
 * Read from both releases: 1.19.4's static processBlockInfos(level, offset, pivot, settings, blockInfos) ran the
 * placement's structure processors over the blocks for the level being placed into; 1.20's takes a ServerLevelAccessor,
 * the same work, and its own caller (placeInWorld) now passes the ServerLevelAccessor it places into. Structures are
 * placed into a server level, so the old call is the new one on the same level.
 *
 * Fabric's 1.20 post (https://fabricmc.net/2023/05/25/120.html) does not mention processBlockInfos, and NeoForge's primers begin at 1.20.2 -> 1.20.3; this is from the game's code alone.
 */
public final class StructureTemplate {

    private StructureTemplate() {}

    public static List processBlockInfos(LevelAccessor level, BlockPos offset, BlockPos pivot, net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings settings, List blockInfos) {
        return net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.processBlockInfos((ServerLevelAccessor) level, offset, pivot, settings, blockInfos);
    }
}

package mmt.standin.net.minecraft.world.level.block.state;

import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * 1.20.2's BlockBehaviour.Properties.copy(block) on 1.20.3.
 *
 * Read from both releases: 1.20.2's copy(block) made new Properties holding 24 of the block's settings (destroy time,
 * explosion resistance, collision, ticking, light, map colour, sound, friction, speed and jump factors, occlusion, ...).
 * 1.20.3 split it in two: ofFullCopy(block) copies every setting, and ofLegacyCopy(block) copies the same 24 fields as
 * 1.20.2's copy, field for field (checked against both releases' bytecode). So the old method is ofLegacyCopy.
 *
 * NeoForge's primers begin at 1.20.4 -> 1.20.5; this is from the game's code alone.
 */
public final class BlockBehaviour$Properties {

    private BlockBehaviour$Properties() {}

    public static BlockBehaviour.Properties copy(BlockBehaviour block) {
        return BlockBehaviour.Properties.ofLegacyCopy(block);
    }
}

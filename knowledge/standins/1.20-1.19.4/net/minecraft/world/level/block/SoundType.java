package mmt.standin.net.minecraft.world.level.block;

import net.minecraft.sounds.SoundEvents;

/**
 * 1.20's SoundType.EMPTY on 1.19.4.
 *
 * Read from both releases: 1.20 made EMPTY as new SoundType(1.0F, 1.0F, SoundEvents.EMPTY x5), every sound of it the
 * empty sound; 1.19.4 has SoundEvents.EMPTY and SoundType's public constructor, so this is that same sound type, made
 * once as 1.20's static field is.
 */
public final class SoundType {

    private static final net.minecraft.world.level.block.SoundType EMPTY = new net.minecraft.world.level.block.SoundType(1.0F, 1.0F, SoundEvents.EMPTY,
        SoundEvents.EMPTY, SoundEvents.EMPTY, SoundEvents.EMPTY, SoundEvents.EMPTY);

    private SoundType() {
    }

    public static net.minecraft.world.level.block.SoundType EMPTY() {
        return EMPTY;
    }
}

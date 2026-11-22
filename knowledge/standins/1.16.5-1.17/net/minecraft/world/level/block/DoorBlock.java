package mmt.standin.net.minecraft.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 1.16.5's DoorBlock.setOpen(Level, BlockState, BlockPos, boolean) on 1.17.
 *
 * Read from both releases: 1.16.5's setOpen opened or closed the door (setting OPEN with flags 10) and played its sound
 * when the state was this door and differed. 1.17 does the same, takes the entity that did it first, and also sends the
 * BLOCK_OPEN or BLOCK_CLOSE game event from it; the entity is @Nullable. This calls 1.17's with no entity.
 *
 * Fabric's 1.17 post for developers (https://fabricmc.net/2021/05/27/117-for-developers.html) covers Java 16, Fabric API and
 * Yarn, not this; this is from the game's code alone.
 */
public final class DoorBlock {

    private DoorBlock() {}

    public static void setOpen(net.minecraft.world.level.block.DoorBlock self, Level level, BlockState state, BlockPos pos, boolean open) {
        self.setOpen(null, level, state, pos, open);
    }
}

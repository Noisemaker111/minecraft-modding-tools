package mmt.standin.net.minecraft.world.level;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.TickPriority;

/**
 * 1.17.1's TickList on 1.18, where the level is the list (the porter reads TickList as LevelAccessor).
 *
 * Read from both releases: 1.17.1's LevelAccessor handed out a TickList of blocks (getBlockTicks) and of fluids
 * (getLiquidTicks); scheduleTick(pos, block or fluid, delay[, priority]) on it queued a tick at the level's game time
 * plus the delay, and hasScheduledTick, willTickThisTick and size asked the queue. 1.18 schedules on the level itself:
 * LevelAccessor.scheduleTick(pos, block, ...) and scheduleTick(pos, fluid, ...) make the tick from the game time plus
 * the delay and the level's next sub-tick count and queue it in getBlockTicks() or getFluidTicks(), the LevelTickAccess
 * that hasScheduledTick, willTickThisTick and count ask. A block always went to the block list and a fluid to the fluid
 * list, so the thing ticked picks the queue here as the list did then.
 *
 * Fabric's 1.18 post (https://fabricmc.net/2021/11/30/118.html) does not mention ticks; this is from the game's code
 * alone.
 */
public final class TickList {

    private TickList() {}

    static LevelTickAccess queue(LevelAccessor level, Object type) {
        return type instanceof Fluid ? level.getFluidTicks() : level.getBlockTicks();
    }

    public static void scheduleTick(LevelAccessor self, BlockPos pos, Object type, int delay) {
        scheduleTick(self, pos, type, delay, TickPriority.NORMAL);
    }

    public static void scheduleTick(LevelAccessor self, BlockPos pos, Object type, int delay, TickPriority priority) {
        if (type instanceof Fluid) {
            self.scheduleTick(pos, (Fluid) type, delay, priority);
        } else {
            self.scheduleTick(pos, (Block) type, delay, priority);
        }
    }

    public static boolean hasScheduledTick(LevelAccessor self, BlockPos pos, Object type) {
        return queue(self, type).hasScheduledTick(pos, type);
    }

    public static boolean willTickThisTick(LevelAccessor self, BlockPos pos, Object type) {
        return queue(self, type).willTickThisTick(pos, type);
    }
}

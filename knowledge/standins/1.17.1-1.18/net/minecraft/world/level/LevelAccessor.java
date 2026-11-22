package mmt.standin.net.minecraft.world.level;

/**
 * 1.17.1's LevelAccessor.getBlockTicks() and getLiquidTicks() on 1.18, where the porter reads the TickList they
 * returned as the level itself.
 *
 * Read from both releases: 1.17.1's getBlockTicks() and getLiquidTicks() returned the level's TickList of blocks and of
 * fluids. 1.18 schedules ticks on the level (LevelAccessor.scheduleTick for a block or a fluid), so each returns the
 * level, and this update's TickList stand-in schedules on it.
 *
 * Fabric's 1.18 post (https://fabricmc.net/2021/11/30/118.html) does not mention ticks; this is from the game's code
 * alone.
 */
public final class LevelAccessor {

    private LevelAccessor() {}

    public static net.minecraft.world.level.LevelAccessor getBlockTicks(net.minecraft.world.level.LevelAccessor self) {
        return self;
    }

    public static net.minecraft.world.level.LevelAccessor getLiquidTicks(net.minecraft.world.level.LevelAccessor self) {
        return self;
    }
}

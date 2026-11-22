package mmt.standin.net.minecraft.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 1.19.2's BonemealableBlock.isValidBonemealTarget(BlockGetter, ...) on 1.19.3.
 *
 * Read from both releases: 1.19.2's isValidBonemealTarget(level, pos, state, isClient) took a BlockGetter; 1.19.3's takes
 * a LevelReader, the same question. The game's own callers (BoneMealItem, the dispenser) passed a Level, which is a
 * LevelReader; the old call is the new one on the same level.
 *
 * Fabric's 1.19.3 post (https://fabricmc.net/2022/11/24/1193.html) does not mention isValidBonemealTarget, and NeoForge's primers begin at 1.20.2 -> 1.20.3; this is from the game's code alone.
 */
public final class BonemealableBlock {

    private BonemealableBlock() {}

    public static boolean isValidBonemealTarget(net.minecraft.world.level.block.BonemealableBlock self, BlockGetter level, BlockPos pos, BlockState state, boolean isClient) {
        return self.isValidBonemealTarget((LevelReader) level, pos, state, isClient);
    }
}

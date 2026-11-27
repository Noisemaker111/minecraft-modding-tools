package mmt.standin.net.minecraft.world.level.block;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RotationSegment;

/**
 * 1.20's SignBlock.getYRotationDegrees(BlockState) on 1.19.4.
 *
 * Read from both releases: 1.20's four signs answer it from their state, StandingSignBlock and CeilingHangingSignBlock
 * as RotationSegment.convertToDegrees of ROTATION (BlockStateProperties.ROTATION_16), WallSignBlock and
 * WallHangingSignBlock as FACING.toYRot() (BlockStateProperties.HORIZONTAL_FACING); 1.19.4's SignRenderer turns a sign
 * by the same two values. A sign is read by the property its state has, which is how each of the four reads it.
 */
public final class SignBlock {

    private SignBlock() {
    }

    public static float getYRotationDegrees(net.minecraft.world.level.block.SignBlock self, BlockState state) {
        if (state.hasProperty(BlockStateProperties.ROTATION_16)) {
            return RotationSegment.convertToDegrees((Integer) state.getValue(BlockStateProperties.ROTATION_16));
        }
        return ((Direction) state.getValue(BlockStateProperties.HORIZONTAL_FACING)).toYRot();
    }
}

package mmt.standin.net.minecraft.core;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 1.20.1's BlockSource on 1.20.2, where the dispenser hands over core.dispenser.BlockSource instead.
 *
 * Read from both releases: 1.20.1's core.BlockSource was an interface the dispenser passed to its behaviours (its
 * implementation BlockSourceImpl): getLevel(), getPos(), getBlockState(), getEntity() for the dispenser's block entity,
 * and x(), y(), z() for the block's centre (pos + 0.5). 1.20.2 dropped it for the record core.dispenser.BlockSource(level,
 * pos, state, blockEntity), whose center() is the block's centre, and every DispenseItemBehavior and DispenserBlock method
 * that took the old type takes the record.
 *
 * NeoForge's primers begin at 1.20.2 -> 1.20.3, and Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html) does
 * not mention dispensers; this is from the game's code alone.
 */
public final class BlockSource {

    private BlockSource() {}

    public static ServerLevel getLevel(net.minecraft.core.dispenser.BlockSource self) {
        return self.level();
    }

    public static BlockPos getPos(net.minecraft.core.dispenser.BlockSource self) {
        return self.pos();
    }

    public static BlockState getBlockState(net.minecraft.core.dispenser.BlockSource self) {
        return self.state();
    }

    public static BlockEntity getEntity(net.minecraft.core.dispenser.BlockSource self) {
        return self.blockEntity();
    }

    public static double x(net.minecraft.core.dispenser.BlockSource self) {
        return self.center().x;
    }

    public static double y(net.minecraft.core.dispenser.BlockSource self) {
        return self.center().y;
    }

    public static double z(net.minecraft.core.dispenser.BlockSource self) {
        return self.center().z;
    }
}

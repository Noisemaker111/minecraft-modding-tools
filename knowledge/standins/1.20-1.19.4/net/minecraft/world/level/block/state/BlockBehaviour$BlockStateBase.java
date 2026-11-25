package mmt.standin.net.minecraft.world.level.block.state;

import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;

/**
 * 1.20's block state checks that took over from 1.19.4's Material, written on 1.19.4.
 *
 * Read from both releases: 1.20 dropped Material and gave each state liquid(), isSolid(), blocksMotion() and
 * instrument(). At the same places the game's own code switched over: FallingBlock's getMaterial().isLiquid() is
 * liquid(), CakeBlock's and BannerBlock's getMaterial().isSolid() are isSolid(), EnderMan's getMaterial().blocksMotion()
 * is blocksMotion(), and NoteBlock's NoteBlockInstrument.byStateBelow(state) is state.instrument(). 1.20 works its
 * isSolid out from the block's shape unless a block says otherwise (forceSolidOn/Off), made to give what the material said.
 */
public final class BlockBehaviour$BlockStateBase {

    private BlockBehaviour$BlockStateBase() {
    }

    public static boolean liquid(BlockBehaviour.BlockStateBase self) {
        return self.getMaterial().isLiquid();
    }

    public static boolean isSolid(BlockBehaviour.BlockStateBase self) {
        return self.getMaterial().isSolid();
    }

    public static boolean blocksMotion(BlockBehaviour.BlockStateBase self) {
        return self.getMaterial().blocksMotion();
    }

    public static NoteBlockInstrument instrument(BlockBehaviour.BlockStateBase self) {
        return NoteBlockInstrument.byStateBelow((net.minecraft.world.level.block.state.BlockState) self);
    }
}

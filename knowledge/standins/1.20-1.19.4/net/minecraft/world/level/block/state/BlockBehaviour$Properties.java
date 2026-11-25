package mmt.standin.net.minecraft.world.level.block.state;

import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.Material;
import net.minecraft.world.level.material.PushReaction;

/**
 * 1.20's block settings that took over from 1.19.4's Material, written on 1.19.4 as the one flag of the block's
 * material each one is.
 *
 * Read from both releases: 1.20 dropped Material and moved its flags onto the settings, each read where 1.19.4 read the
 * material's: pushReaction(p) is what BlockStateBase.getPistonPushReaction() gives (1.19.4: getMaterial().getPushReaction()),
 * ignitedByLava() what LavaFluid.isFlammable reads (getMaterial().isFlammable()), replaceable() what canBeReplaced()
 * reads (getMaterial().isReplaceable()), liquid() what liquid() reads (getMaterial().isLiquid()), and forceSolidOn()/
 * forceSolidOff() fix isSolid(), and with it blocksMotion() (1.20's is isSolid() for every block but the cobweb and the
 * bamboo sapling; 1.19.4's getMaterial().isSolid() and blocksMotion()). Each setter here gives the settings the
 * material they had with that one flag changed: a block's material is the one its settings hold when it is made
 * (BlockBehaviour's constructor reads Properties.material), and a Material is a value of these flags.
 *
 * Not here: Properties.of() without a material, where 1.20 works isSolid out from the block's shape, which 1.19.4 must
 * fix in the material before any shape exists; and instrument(), which 1.19.4 reads from the material by identity.
 */
public final class BlockBehaviour$Properties {

    private BlockBehaviour$Properties() {
    }

    public static BlockBehaviour.Properties pushReaction(BlockBehaviour.Properties self, PushReaction reaction) {
        Material m = self.material;
        self.material = new Material(m.getColor(), m.isLiquid(), m.isSolid(), m.blocksMotion(), m.isSolidBlocking(), m.isFlammable(), m.isReplaceable(), reaction);
        return self;
    }

    public static BlockBehaviour.Properties ignitedByLava(BlockBehaviour.Properties self) {
        Material m = self.material;
        self.material = new Material(m.getColor(), m.isLiquid(), m.isSolid(), m.blocksMotion(), m.isSolidBlocking(), true, m.isReplaceable(), m.getPushReaction());
        return self;
    }

    public static BlockBehaviour.Properties replaceable(BlockBehaviour.Properties self) {
        Material m = self.material;
        self.material = new Material(m.getColor(), m.isLiquid(), m.isSolid(), m.blocksMotion(), m.isSolidBlocking(), m.isFlammable(), true, m.getPushReaction());
        return self;
    }

    public static BlockBehaviour.Properties liquid(BlockBehaviour.Properties self) {
        Material m = self.material;
        self.material = new Material(m.getColor(), true, m.isSolid(), m.blocksMotion(), m.isSolidBlocking(), m.isFlammable(), m.isReplaceable(), m.getPushReaction());
        return self;
    }

    public static BlockBehaviour.Properties forceSolidOn(BlockBehaviour.Properties self) {
        Material m = self.material;
        self.material = new Material(m.getColor(), m.isLiquid(), true, true, m.isSolidBlocking(), m.isFlammable(), m.isReplaceable(), m.getPushReaction());
        return self;
    }

    public static BlockBehaviour.Properties forceSolidOff(BlockBehaviour.Properties self) {
        Material m = self.material;
        self.material = new Material(m.getColor(), m.isLiquid(), false, false, m.isSolidBlocking(), m.isFlammable(), m.isReplaceable(), m.getPushReaction());
        return self;
    }
}

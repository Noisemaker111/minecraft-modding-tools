package mmt.standin.net.minecraft.core;

/**
 * 1.19.3's BlockPos on 1.19.4.
 *
 * Read from both releases: 1.19.3's offset(x, y, z) in doubles gave the same position when all three were 0, else
 * new BlockPos(getX() + x, getY() + y, getZ() + z), whose double constructor floored each coordinate. 1.19.4 dropped the
 * double constructor and offset for BlockPos.containing(x, y, z), which floors the same way.
 *
 * Agrees with NeoForge's 1.19.3 -> 1.19.4 primer (https://github.com/neoforged/.github/blob/main/primers/1.19.4/index.md, CC BY 4.0): "BlockPos constructors that take in doubles should migrate to using BlockPos#containing".
 */
public final class BlockPos {

    private BlockPos() {}

    public static net.minecraft.core.BlockPos offset(net.minecraft.core.BlockPos self, double x, double y, double z) {
        if (x == 0.0 && y == 0.0 && z == 0.0) {
            return self;
        }
        return net.minecraft.core.BlockPos.containing(self.getX() + x, self.getY() + y, self.getZ() + z);
    }
}

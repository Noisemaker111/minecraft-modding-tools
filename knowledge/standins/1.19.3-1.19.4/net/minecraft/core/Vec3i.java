package mmt.standin.net.minecraft.core;

import net.minecraft.util.Mth;

/**
 * 1.19.3's Vec3i(double, double, double) constructor on 1.19.4.
 *
 * Read from both releases: 1.19.3's Vec3i(x, y, z) of doubles was Vec3i(Mth.floor(x), Mth.floor(y), Mth.floor(z)).
 * 1.19.4 dropped it and keeps the int constructor and Mth.floor.
 *
 * Agrees with Fabric's 1.19.4 post (https://fabricmc.net/2023/03/01/1194.html): "`Vec3i` and `BlockPos` constructors
 * that take `double` or `Position` (such as `Vec3d`) are moved to `ofFloored` method."
 */
public final class Vec3i {

    private Vec3i() {}

    public static net.minecraft.core.Vec3i init(double x, double y, double z) {
        return new net.minecraft.core.Vec3i(Mth.floor(x), Mth.floor(y), Mth.floor(z));
    }
}

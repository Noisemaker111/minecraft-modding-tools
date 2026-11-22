package mmt.standin.net.minecraft.core;

import net.minecraft.core.Position;

/**
 * 1.18.1's Vec3i.distSqr(x, y, z, centered) and its two overloads on 1.18.2.
 *
 * Read from both releases: 1.18.1's distSqr(double x, double y, double z, boolean centered) measured the squared
 * distance from this position, moved by half a block on each axis when centered, to (x, y, z); distSqr(Position,
 * boolean) and distSqr(Vec3i, boolean) passed the other's coordinates. 1.18.2 split it in two: distToCenterSqr(x, y, z)
 * (the centered case) and distToLowCornerSqr(x, y, z) (the other). Each is 1.18.1's method, choosing between them.
 *
 * Fabric has no 1.18.2 post of its own (its 1.18 post, https://fabricmc.net/2021/11/30/118.html, covers 1.18.2 and does
 * not mention Vec3i); this is from the game's code alone.
 */
public final class Vec3i {

    private Vec3i() {}

    public static double distSqr(net.minecraft.core.Vec3i self, double x, double y, double z, boolean centered) {
        return centered ? self.distToCenterSqr(x, y, z) : self.distToLowCornerSqr(x, y, z);
    }

    public static double distSqr(net.minecraft.core.Vec3i self, Position other, boolean centered) {
        return distSqr(self, other.x(), other.y(), other.z(), centered);
    }

    public static double distSqr(net.minecraft.core.Vec3i self, net.minecraft.core.Vec3i other, boolean centered) {
        return distSqr(self, other.getX(), other.getY(), other.getZ(), centered);
    }
}

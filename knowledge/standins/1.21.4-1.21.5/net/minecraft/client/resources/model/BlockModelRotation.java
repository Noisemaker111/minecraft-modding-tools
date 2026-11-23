package mmt.standin.net.minecraft.client.resources.model;

import com.mojang.math.Quadrant;

/**
 * 1.21.4's BlockModelRotation.by(x, y) in degrees on 1.21.5.
 *
 * Read from both releases: 1.21.4's by(x, y) took each angle modulo 360 and answered the rotation of those x and y
 * angles, or null when either was not a multiple of 90 (the lookup by x * 360 + y found none). 1.21.5 keys the same
 * sixteen rotations by quarter turns, by(Quadrant x, Quadrant y), Quadrant's R0, R90, R180, R270 being the angles 0, 90,
 * 180, 270 in order.
 *
 * Agrees with NeoForge's 1.21.4 -> 1.21.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.5/index.md, CC BY 4.0):
 * "BlockModelRotation: by now takes in Quadrants instead of integers".
 */
public final class BlockModelRotation {

    private BlockModelRotation() {}

    public static net.minecraft.client.resources.model.BlockModelRotation by(int x, int y) {
        int xDegrees = Math.floorMod(x, 360);
        int yDegrees = Math.floorMod(y, 360);
        if (xDegrees % 90 != 0 || yDegrees % 90 != 0) {
            return null;
        }
        return net.minecraft.client.resources.model.BlockModelRotation.by(Quadrant.values()[xDegrees / 90], Quadrant.values()[yDegrees / 90]);
    }
}

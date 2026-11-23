package mmt.standin.net.minecraft.client.resources.model;

import com.mojang.math.OctahedralGroup;
import com.mojang.math.Quadrant;

/**
 * 1.21.10's BlockModelRotation.by(x, y) on 1.21.11.
 *
 * Read from both releases: 1.21.10's BlockModelRotation was an enum of the sixteen x/y quarter-turn pairs, by(x, y)
 * answering the one whose rotation is OctahedralGroup.fromXYAngles(x, y): the identity composed with ROT_90_Y_NEG y.shift
 * times, then with ROT_90_X_NEG x.shift times. 1.21.11 made BlockModelRotation a class keyed by any OctahedralGroup
 * (BlockModelRotation.get(group)) and dropped fromXYAngles; compose and the two quarter turns remain, so the same group is
 * composed and its rotation got.
 *
 * Agrees with NeoForge's 1.21.10 -> 1.21.11 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.11/index.md, CC BY 4.0):
 * "net.minecraft.client.resources.model.BlockModelRotation is now a class".
 */
public final class BlockModelRotation {

    private BlockModelRotation() {}

    public static net.minecraft.client.resources.model.BlockModelRotation by(Quadrant x, Quadrant y) {
        OctahedralGroup group = OctahedralGroup.IDENTITY;
        for (int i = 0; i < y.shift; i++) {
            group = group.compose(OctahedralGroup.ROT_90_Y_NEG);
        }
        for (int i = 0; i < x.shift; i++) {
            group = group.compose(OctahedralGroup.ROT_90_X_NEG);
        }
        return net.minecraft.client.resources.model.BlockModelRotation.get(group);
    }
}

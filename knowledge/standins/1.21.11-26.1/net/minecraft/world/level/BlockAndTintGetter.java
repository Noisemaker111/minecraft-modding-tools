package mmt.standin.net.minecraft.world.level;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.Direction;

/**
 * 1.21.11's BlockAndTintGetter.getShade(direction, shade) on 26.1.
 *
 * Read from both releases: 1.21.11's getShade answered the brightness a face is drawn with: unshaded, 1.0 (0.9 in a
 * nether-lit dimension); shaded, 0.5 down, 1.0 up (0.9 and 0.9 nether-lit), 0.8 north and south, 0.6 west and east.
 * 26.1 moved the interface to net.minecraft.client.renderer.block (the route follows it) and answers the same six numbers
 * as a CardinalLighting (DEFAULT 0.5, 1.0, 0.8, 0.8, 0.6, 0.6; NETHER 0.9, 0.9, 0.8, 0.8, 0.6, 0.6) from
 * cardinalLighting(): byFace(direction) for a shaded face, up() for an unshaded one, as 26.1's own block lighter does.
 *
 * Agrees with NeoForge's 1.21.11 -> 26.1 primer (https://github.com/neoforged/.github/blob/main/primers/26.1/index.md, CC BY 4.0):
 * "getShade -> cardinalLighting; not one-to-one".
 */
public final class BlockAndTintGetter {

    private BlockAndTintGetter() {}

    public static float getShade(net.minecraft.client.renderer.block.BlockAndTintGetter self, Direction direction, boolean shade) {
        return shade ? self.cardinalLighting().byFace(direction) : self.cardinalLighting().up();
    }
}

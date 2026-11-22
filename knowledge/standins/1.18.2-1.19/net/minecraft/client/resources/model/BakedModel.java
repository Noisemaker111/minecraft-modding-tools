package mmt.standin.net.minecraft.client.resources.model;

import java.util.List;
import java.util.Random;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * 1.18.2's BakedModel.getQuads(state, side, java.util.Random) on 1.19.
 *
 * Read from both releases: 1.18.2's getQuads took a java.util.Random (callers seeded it, usually with the block's
 * position seed, before asking); 1.19's takes the game's own RandomSource, the same draws (nextInt, nextLong, nextFloat
 * ...) under another interface. The old call is the new one with a RandomSource that draws from the caller's Random, so
 * the model makes the same choices from the same seed. Forks draw a seed from it, as the game's own sources do.
 *
 * Agrees with Fabric's 1.19 post (https://fabricmc.net/2022/06/07/119.html): "The JDK `java.util.Random` class has been
 * replaced entirely with Mojang's own interface" (RandomSource in Mojang's names).
 */
public final class BakedModel {

    private BakedModel() {}

    public static List getQuads(net.minecraft.client.resources.model.BakedModel self, BlockState state, Direction side, Random random) {
        return self.getQuads(state, side, new Drawn(random));
    }

    /** The game's RandomSource drawing from a java.util.Random. */
    static final class Drawn implements RandomSource {
        final Random random;

        Drawn(Random random) {
            this.random = random;
        }

        public RandomSource fork() {
            return new Drawn(new Random(random.nextLong()));
        }

        public PositionalRandomFactory forkPositional() {
            return new LegacyRandomSource(random.nextLong()).forkPositional();
        }

        public void setSeed(long seed) {
            random.setSeed(seed);
        }

        public int nextInt() {
            return random.nextInt();
        }

        public int nextInt(int bound) {
            return random.nextInt(bound);
        }

        public long nextLong() {
            return random.nextLong();
        }

        public boolean nextBoolean() {
            return random.nextBoolean();
        }

        public float nextFloat() {
            return random.nextFloat();
        }

        public double nextDouble() {
            return random.nextDouble();
        }

        public double nextGaussian() {
            return random.nextGaussian();
        }
    }
}

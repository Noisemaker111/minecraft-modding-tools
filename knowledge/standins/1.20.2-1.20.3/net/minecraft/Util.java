package mmt.standin.net.minecraft;

import it.unimi.dsi.fastutil.Hash;

/**
 * 1.20.2's Util on 1.20.3.
 *
 * Read from both releases: 1.20.2's Util.identityStrategy() returned Util.IdentityStrategy.INSTANCE, a fastutil hash
 * strategy that hashed by System.identityHashCode and compared with ==. 1.20.3 drops both. So the old method returns the
 * same strategy.
 *
 * NeoForge's primers begin at 1.20.4 -> 1.20.5, and Fabric's 1.20.3 post (https://fabricmc.net/2023/11/30/1203.html)
 * does not mention Util; this is from the game's code alone.
 */
public final class Util {

    private Util() {}

    public static Hash.Strategy identityStrategy() {
        return Identity.INSTANCE;
    }

    enum Identity implements Hash.Strategy {
        INSTANCE;

        @Override
        public int hashCode(Object value) {
            return System.identityHashCode(value);
        }

        @Override
        public boolean equals(Object a, Object b) {
            return a == b;
        }
    }
}

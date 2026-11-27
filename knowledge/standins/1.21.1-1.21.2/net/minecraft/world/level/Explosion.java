package mmt.standin.net.minecraft.world.level;

import java.util.Map;
import net.minecraft.world.level.ServerExplosion;

/**
 * 1.21.1's Explosion.getHitPlayers() and its radius field on 1.21.2.
 *
 * Read from both releases: 1.21.1's Explosion was the class that exploded, kept the players it pushed and how far
 * (hitPlayers, filled in explode()) and its radius. 1.21.2's Explosion is an interface with radius(); ServerExplosion
 * explodes, fills the same hitPlayers map and gives it from getHitPlayers(). Explosions are only made on the server in
 * 1.21.2 (the client gets the result in a packet), so an Explosion that is no ServerExplosion pushed no one.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "Explosion is now an interface that defines the metadata of the explosion ... ServerExplosion is still used internally to handle level explosions", "Explosion -> ServerExplosion".
 */
public final class Explosion {

    private Explosion() {}

    public static Map getHitPlayers(net.minecraft.world.level.Explosion self) {
        return self instanceof ServerExplosion server ? server.getHitPlayers() : Map.of();
    }

    public static float radius(net.minecraft.world.level.Explosion self) {
        return self.radius();
    }
}

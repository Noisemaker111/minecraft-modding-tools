package mmt.standin.net.minecraft.world.entity;

/**
 * 1.16.5's Entity.remove() on 1.17.
 *
 * Read from both releases: 1.16.5's remove() marked the entity removed, and the level dropped it on its next pass;
 * kill() called it too. 1.17 takes why (remove(RemovalReason)): kill() passes KILLED, and discard() passes DISCARDED, the
 * reason for an entity simply taken out of the world (an item picked up or merged, a projectile gone). A mod's remove()
 * took the entity out without killing it, which is discard().
 *
 * Fabric's 1.17 post for developers (https://fabricmc.net/2021/05/27/117-for-developers.html) covers Java 16, Fabric API and
 * Yarn, not this; this is from the game's code alone.
 */
public final class Entity {

    private Entity() {}

    public static void remove(net.minecraft.world.entity.Entity self) {
        self.discard();
    }
}

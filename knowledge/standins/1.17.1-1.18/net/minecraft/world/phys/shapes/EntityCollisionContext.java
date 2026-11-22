package mmt.standin.net.minecraft.world.phys.shapes;

import java.util.Optional;

/**
 * 1.17.1's EntityCollisionContext.getEntity() on 1.18.
 *
 * Read from both releases: 1.17.1 kept the entity as an Optional (empty for the context of no entity) and returned it.
 * 1.18 keeps the entity itself, null for no entity, and getEntity() returns it (@Nullable). This wraps it again.
 *
 * Fabric's 1.18 post (https://fabricmc.net/2021/11/30/118.html) does not mention collision contexts; this is from the
 * game's code alone.
 */
public final class EntityCollisionContext {

    private EntityCollisionContext() {}

    public static Optional getEntity(net.minecraft.world.phys.shapes.EntityCollisionContext self) {
        return Optional.ofNullable(self.getEntity());
    }
}

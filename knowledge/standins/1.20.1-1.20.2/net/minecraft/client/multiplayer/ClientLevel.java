package mmt.standin.net.minecraft.client.multiplayer;

import net.minecraft.world.entity.Entity;

/**
 * 1.20.1's ClientLevel.putNonPlayerEntity on 1.20.2.
 *
 * Read from both releases: 1.20.1's putNonPlayerEntity(id, entity) (and addPlayer) went to a private addEntity(id,
 * entity) that discarded the entity the level had under id and added the new one. 1.20.2 dropped both for a public
 * addEntity(entity), which discards the entity under entity.getId() and adds the new one, the network handler passing the
 * id it set on the entity. So the old call discards what is under id, then adds the entity the new way.
 *
 * NeoForge's primers begin at 1.20.2 -> 1.20.3, and Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html) does
 * not mention ClientLevel; this is from the game's code alone.
 */
public final class ClientLevel {

    private ClientLevel() {}

    public static void putNonPlayerEntity(net.minecraft.client.multiplayer.ClientLevel self, int id, Entity entity) {
        self.removeEntity(id, Entity.RemovalReason.DISCARDED);
        self.addEntity(entity);
    }
}

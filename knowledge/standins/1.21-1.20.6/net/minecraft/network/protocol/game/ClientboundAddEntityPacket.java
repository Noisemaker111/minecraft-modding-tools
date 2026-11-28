package mmt.standin.net.minecraft.network.protocol.game;

/**
 * 1.21's ClientboundAddEntityPacket(Entity, ServerEntity[, int]) on 1.20.6, for a port back: the packet 1.20.6 makes
 * from the entity alone.
 *
 * Read from both releases: 1.21 reads the position, rotations, motion and head rotation from what the ServerEntity last
 * sent; 1.20.6's ServerEntity keeps those private and its Entity.getAddEntityPacket() builds the packet from the entity
 * as it is now (ClientboundAddEntityPacket(Entity, int)), which this does, with the same data value.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`net.minecraft.network.protocol.game.ClientboundAddEntityPacket` now takes in the `ServerEntity` for the first two packet constructors".
 */
public final class ClientboundAddEntityPacket {

    private ClientboundAddEntityPacket() {}

    public static net.minecraft.network.protocol.game.ClientboundAddEntityPacket init(net.minecraft.world.entity.Entity entity, net.minecraft.server.level.ServerEntity tracked) {
        return new net.minecraft.network.protocol.game.ClientboundAddEntityPacket(entity, 0);
    }

    public static net.minecraft.network.protocol.game.ClientboundAddEntityPacket init(net.minecraft.world.entity.Entity entity, net.minecraft.server.level.ServerEntity tracked, int data) {
        return new net.minecraft.network.protocol.game.ClientboundAddEntityPacket(entity, data);
    }
}

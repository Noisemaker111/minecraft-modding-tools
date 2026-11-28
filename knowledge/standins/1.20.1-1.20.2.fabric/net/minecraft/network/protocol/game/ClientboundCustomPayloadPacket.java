package mmt.standin.net.minecraft.network.protocol.game;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.20.1's ClientboundCustomPayloadPacket on 1.20.2, through Fabric API's networking.
 *
 * Read from both releases: 1.20.1's game.ClientboundCustomPayloadPacket(identifier, data) held a channel id and the bytes a
 * mod wrote, and its write put the id and a copy of the bytes on the wire. 1.20.2 dropped it for
 * common.ClientboundCustomPayloadPacket(CustomPacketPayload), whose write puts payload.id() and then payload.write(buf).
 * Fabric API for 1.20.2 (fabric-networking-api-v1 0.91.6) makes that packet from a channel id and bytes:
 * ServerPlayNetworking.createS2CPacket(id, buf) is new ClientboundCustomPayloadPacket(new UntypedPayload(id, buf)), the
 * payload its own receivers read, also where a single-player connection hands the packet over without writing it.
 *
 * Agrees with Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html): "There is one breaking change to
 * Networking API: ClientPlayNetworking#createC2SPacket now returns Packet<ServerCommonPacketListener> instead of
 * Packet<ServerPlayPacketListener> (and similar changes with createS2CPacket as well.)"
 */
public final class ClientboundCustomPayloadPacket {

    private ClientboundCustomPayloadPacket() {}

    public static net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket init(ResourceLocation identifier, FriendlyByteBuf data) {
        return (net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket) ServerPlayNetworking.createS2CPacket(identifier, data);
    }
}

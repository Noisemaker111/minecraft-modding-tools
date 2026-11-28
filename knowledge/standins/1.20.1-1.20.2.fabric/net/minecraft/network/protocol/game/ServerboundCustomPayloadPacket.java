package mmt.standin.net.minecraft.network.protocol.game;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.20.1's ServerboundCustomPayloadPacket on 1.20.2, through Fabric API's networking.
 *
 * Read from both releases: 1.20.1's game.ServerboundCustomPayloadPacket(identifier, data) held a channel id and the bytes a
 * mod wrote for the server. 1.20.2 dropped it for common.ServerboundCustomPayloadPacket(CustomPacketPayload), whose write
 * puts payload.id() and then payload.write(buf). Fabric API for 1.20.2 (fabric-networking-api-v1 0.91.6) makes that packet
 * from a channel id and bytes: ClientPlayNetworking.createC2SPacket(id, buf) is new ServerboundCustomPayloadPacket(new
 * UntypedPayload(id, buf)), the payload its own server receivers read.
 *
 * Agrees with Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html): "ClientPlayNetworking#createC2SPacket now
 * returns Packet<ServerCommonPacketListener> instead of Packet<ServerPlayPacketListener>".
 */
public final class ServerboundCustomPayloadPacket {

    private ServerboundCustomPayloadPacket() {}

    public static net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket init(ResourceLocation identifier, FriendlyByteBuf data) {
        return (net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket) ClientPlayNetworking.createC2SPacket(identifier, data);
    }
}

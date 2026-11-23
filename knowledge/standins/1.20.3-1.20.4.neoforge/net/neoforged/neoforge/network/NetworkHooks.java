package mmt.standin.net.neoforged.neoforge.network;

import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.extensions.IPlayerExtension;

/**
 * NeoForge 20.3's NetworkHooks (menus and entity spawning) on NeoForge 20.4.
 *
 * Read from both loaders' jars: 20.3's openScreen(player, provider, extraData) closed the player's open menu, made the
 * provider's menu for the next container id, wrote extraData into a buffer and sent NeoForge's OpenContainer message with
 * it; openScreen(player, provider) passed no extra data and openScreen(player, provider, pos) wrote the position.
 * getEntitySpawningPacket(entity) wrapped NeoForge's SpawnEntity message (the vanilla spawn fields plus the entity's
 * additional spawn data) as a vanilla packet. 20.4 rewrote NeoForge's networking and dropped NetworkHooks: menus with
 * extra data open through IPlayerExtension.openMenu(provider, extraData) and openMenu(provider, pos), which the patched
 * ServerPlayer answers the same way, and an entity's extra spawn data is sent by NeoForge beside the vanilla
 * ClientboundAddEntityPacket, which is what Entity.getAddEntityPacket() returns. So each old method calls its 20.4 place.
 *
 * NeoForge's primers begin at 1.20.4 -> 1.20.5; this is from the loaders' jars alone.
 */
public final class NetworkHooks {

    private NetworkHooks() {}

    public static void openScreen(ServerPlayer player, MenuProvider provider) {
        ((IPlayerExtension) player).openMenu(provider, buffer -> {});
    }

    public static void openScreen(ServerPlayer player, MenuProvider provider, BlockPos pos) {
        ((IPlayerExtension) player).openMenu(provider, pos);
    }

    public static void openScreen(ServerPlayer player, MenuProvider provider, Consumer<FriendlyByteBuf> extraData) {
        ((IPlayerExtension) player).openMenu(provider, extraData);
    }

    public static Packet getEntitySpawningPacket(Entity entity) {
        return new ClientboundAddEntityPacket(entity);
    }
}

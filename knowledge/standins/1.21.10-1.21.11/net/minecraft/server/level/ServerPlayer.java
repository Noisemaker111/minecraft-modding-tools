package mmt.standin.net.minecraft.server.level;

/**
 * 1.21.10's ServerPlayer.getPermissionLevel on 1.21.11.
 *
 * Read from both releases: 1.21.10's ServerPlayer.getPermissionLevel() answered the player's op level (0 to 4) from the
 * server's profile permissions. 1.21.11 removed it with Player's; a ServerPlayer's permissions() is that same set, so the
 * call is answered as Player's stand-in answers it (the highest command level the set grants).
 *
 * Agrees with NeoForge's 1.21.10 -> 1.21.11 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.11/index.md, CC BY 4.0):
 * "Player#getPermissionLevel, hasPermissions -> permissions, not one-to-one".
 */
public final class ServerPlayer {

    private ServerPlayer() {}

    public static int getPermissionLevel(net.minecraft.server.level.ServerPlayer self) {
        return mmt.standin.net.minecraft.world.entity.player.Player.getPermissionLevel(self);
    }
}

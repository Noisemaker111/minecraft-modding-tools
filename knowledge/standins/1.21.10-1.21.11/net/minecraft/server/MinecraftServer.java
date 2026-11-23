package mmt.standin.net.minecraft.server;

import net.minecraft.server.players.NameAndId;

/**
 * 1.21.10's MinecraftServer on 1.21.11.
 *
 * Read from both releases: 1.21.10 gave permission levels as ints 0 to 4: operatorUserPermissionLevel() the level an
 * operator gets (the server's op-permission-level), getProfilePermissions(profile) a player's level (an op's own
 * level, 4 for the singleplayer owner or when cheats are on for all, else the operator level for an op, else 0).
 * 1.21.11 returns them as LevelBasedPermissionSet (operatorUserPermissions(), getProfilePermissions(profile)), whose
 * level() is a PermissionLevel numbered the same way (ALL 0, MODERATORS 1, GAMEMASTERS 2, ADMINS 3, OWNERS 4: id(),
 * PermissionLevel.byId), worked out by the same rules.
 *
 * Agrees with NeoForge's 1.21.10 -> 1.21.11 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.11/index.md, CC BY 4.0): "operatorUserPermissionLevel -> operatorUserPermissions, not one-to-one", "getProfilePermissions now returns a LevelBasedPermissionSet".
 */
public final class MinecraftServer {

    private MinecraftServer() {}

    public static int operatorUserPermissionLevel(net.minecraft.server.MinecraftServer self) {
        return self.operatorUserPermissions().level().id();
    }

    public static int getProfilePermissions(net.minecraft.server.MinecraftServer self, NameAndId profile) {
        return self.getProfilePermissions(profile).level().id();
    }
}

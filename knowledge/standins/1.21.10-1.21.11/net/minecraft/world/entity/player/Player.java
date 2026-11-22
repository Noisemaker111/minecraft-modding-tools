package mmt.standin.net.minecraft.world.entity.player;

import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;

/**
 * 1.21.10's Player on 1.21.11.
 *
 * Read from both releases: 1.21.10's Player had a command permission level (getPermissionLevel(), 0 for a player that
 * is not a server player, the op level for a ServerPlayer, from 0 to 4), and hasPermissions(level) was that level being
 * at least the one asked. 1.21.11 gives a player a PermissionSet (permissions(): none for a plain Player, the server's
 * profile permissions for a ServerPlayer), and asks it hasPermission(new Permission.HasCommandLevel(level)), which a
 * level-based set answers by its PermissionLevel being equal or higher (ALL 0, MODERATORS 1, GAMEMASTERS 2, ADMINS 3,
 * OWNERS 4), as the game now asks for creative-mode commands (COMMANDS_GAMEMASTER where 1.21.10 asked for level 2).
 * A level of 0 or below every player had; one above 4 none had (PermissionLevel.byId would clamp it to OWNERS).
 *
 * Agrees with NeoForge's 1.21.10 -> 1.21.11 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.11/index.md, CC BY 4.0): "Player#getPermissionLevel, hasPermissions -> permissions, not one-to-one"; "LevelBasedPermissionSet - A set of permissions that checks whether the user has an equal or higher command permission level".
 */
public final class Player {

    private Player() {}

    public static boolean hasPermissions(net.minecraft.world.entity.player.Player self, int level) {
        if (level <= 0) {
            return true;
        }
        if (level > 4) {
            return false;
        }
        return has(self.permissions(), level);
    }

    public static int getPermissionLevel(net.minecraft.world.entity.player.Player self) {
        PermissionSet permissions = self.permissions();
        for (int level = 4; level > 0; level--) {
            if (has(permissions, level)) {
                return level;
            }
        }
        return 0;
    }

    private static boolean has(PermissionSet permissions, int level) {
        return permissions.hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(level)));
    }
}

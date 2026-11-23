package mmt.standin.net.minecraft.commands;

import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSetSupplier;

/**
 * 1.21.10's PermissionSource.hasPermission(level) on 1.21.11.
 *
 * Read from both releases: 1.21.10's PermissionSource (implemented by CommandSourceStack and ClientSuggestionProvider)
 * answered hasPermission(int) by a command permission level from 0 to 4. 1.21.11 drops the interface: SharedSuggestionProvider
 * now extends PermissionSetSupplier, whose permissions() gives a PermissionSet, and the game asks it
 * hasPermission(new Permission.HasCommandLevel(level)), which a level-based set answers by its PermissionLevel being equal
 * or higher (Commands.LEVEL_GAMEMASTERS where 1.21.10 asked for 2). A level of 0 or below every source had; one above 4
 * none had (PermissionLevel.byId would clamp it to OWNERS). The same reading as this update's Player.hasPermissions.
 *
 * Agrees with NeoForge's 1.21.10 -> 1.21.11 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.11/index.md, CC BY 4.0): "PermissionSet is a functional interface that checks whether the user has the desired permission state (via hasPermission). Vanilla uses a LevelBasedPermissionSet for checking whether the permission queried matches the current PermissionLevel."
 */
public final class PermissionSource {

    private PermissionSource() {}

    public static boolean hasPermission(PermissionSetSupplier self, int level) {
        if (level <= 0) {
            return true;
        }
        if (level > 4) {
            return false;
        }
        return self.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(level)));
    }
}

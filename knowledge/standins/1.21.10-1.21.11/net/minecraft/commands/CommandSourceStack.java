package mmt.standin.net.minecraft.commands;

import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * 1.21.10's public CommandSourceStack constructor on 1.21.11.
 *
 * Read from both releases: 1.21.10's new CommandSourceStack(source, position, rotation, level, permissionLevel, name,
 * displayName, server, entity) made a source whose commands needed a level no higher than permissionLevel (0 to 4),
 * defaulting the rest (not silent, no callback, feet anchor, anonymous signing, the server's immediate task chainer).
 * 1.21.11's takes the same arguments with a PermissionSet in place of the level and defaults the rest the same way; a
 * LevelBasedPermissionSet for a PermissionLevel (forLevel, the levels numbered ALL 0 to OWNERS 4, PermissionLevel.byId)
 * answers a command's level check as the int did (its level equal or higher), which is how 1.21.11's own server builds
 * the sets it gives operators.
 *
 * Agrees with NeoForge's 1.21.10 -> 1.21.11 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.11/index.md, CC BY 4.0):
 * "LevelBasedPermissionSet - A set of permissions that checks whether the user has an equal or higher command permission level".
 */
public final class CommandSourceStack {

    private CommandSourceStack() {}

    public static net.minecraft.commands.CommandSourceStack init(CommandSource source, Vec3 position, Vec2 rotation, ServerLevel level, int permissionLevel,
                                                                 String name, Component displayName, MinecraftServer server, Entity entity) {
        return new net.minecraft.commands.CommandSourceStack(source, position, rotation, level,
            LevelBasedPermissionSet.forLevel(PermissionLevel.byId(permissionLevel)), name, displayName, server, entity);
    }
}

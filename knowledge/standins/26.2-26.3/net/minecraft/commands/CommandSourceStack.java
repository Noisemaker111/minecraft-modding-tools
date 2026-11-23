package mmt.standin.net.minecraft.commands;

import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * 26.2's public CommandSourceStack constructor on 26.3.
 *
 * Read from both releases: 26.2's new CommandSourceStack(source, position, rotation, level, permissions, textName,
 * displayName, server, entity) made a source named by the given text and display name whatever its entity. 26.3 asks a
 * CommandSourceStack.NamesProvider for both names (displayName(entity), textName(entity)); its two public constructors
 * name a source by its entity or by one Component, and the private one they call takes any provider with the same
 * other arguments and the same defaults. So the old call is made through that one with a provider answering the two
 * given names.
 *
 * Agrees with NeoForge's 26.2 -> 26.3 primer (https://github.com/neoforged/.github/blob/main/primers/26.3/index.md, CC BY 4.0):
 * "CommandSourceStack no longer takes in the String text name, and can optionally decide whether to provide the Component
 * display name"; "CommandSourceStack$NamesProvider - A provider for the display and text name of the source".
 */
public final class CommandSourceStack {

    private CommandSourceStack() {}

    public static net.minecraft.commands.CommandSourceStack init(CommandSource source, Vec3 position, Vec2 rotation, ServerLevel level, PermissionSet permissions,
                                                                 String textName, Component displayName, MinecraftServer server, Entity entity) {
        net.minecraft.commands.CommandSourceStack.NamesProvider names = new net.minecraft.commands.CommandSourceStack.NamesProvider() {
            @Override
            public Component displayName(Entity ignored) {
                return displayName;
            }

            @Override
            public String textName(Entity ignored) {
                return textName;
            }
        };
        return new net.minecraft.commands.CommandSourceStack(source, position, rotation, level, permissions, names, server, entity);
    }
}

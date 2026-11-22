package mmt.standin.net.minecraft.world.entity;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * 1.21.1's Entity.createCommandSourceStack() on 1.21.2.
 *
 * Read in both releases' code: 1.21.1's Entity was a CommandSource and built new CommandSourceStack(this, position(),
 * getRotationVector(), level() as a ServerLevel or null, getPermissionLevel(), getName().getString(), getDisplayName(),
 * level().getServer(), this); Entity.getPermissionLevel() was 0 and only ServerPlayer raised it. 1.21.2 drops both from
 * Entity: ServerPlayer keeps createCommandSourceStack() (its own CommandSource, its permission level), and every other
 * entity has createCommandSourceStackForNameResolution(ServerLevel), the same stack with CommandSource.NULL and level 0.
 * A plain entity's CommandSource in 1.21.1 dropped every message (sendSystemMessage did nothing), as NULL does.
 */
public final class Entity {

    private Entity() {}

    public static CommandSourceStack createCommandSourceStack(net.minecraft.world.entity.Entity self) {
        if (self instanceof ServerPlayer player) {
            return player.createCommandSourceStack();
        }
        Level level = self.level();
        return new CommandSourceStack(CommandSource.NULL, self.position(), self.getRotationVector(), level instanceof ServerLevel server ? server : null, 0,
            self.getName().getString(), self.getDisplayName(), level.getServer(), self);
    }
}

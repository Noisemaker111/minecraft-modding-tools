package mmt.standin.net.minecraft.world.entity;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import java.util.HashSet;
import java.util.Set;

/**
 * 1.21.1's Entity.createCommandSourceStack() and teleportTo(ServerLevel, x, y, z, relatives, yRot, xRot) on 1.21.2.
 *
 * Read in both releases' code: 1.21.1's Entity was a CommandSource and built new CommandSourceStack(this, position(),
 * getRotationVector(), level() as a ServerLevel or null, getPermissionLevel(), getName().getString(), getDisplayName(),
 * level().getServer(), this); Entity.getPermissionLevel() was 0 and only ServerPlayer raised it. 1.21.2 drops both from
 * Entity: ServerPlayer keeps createCommandSourceStack() (its own CommandSource, its permission level), and every other
 * entity has createCommandSourceStackForNameResolution(ServerLevel), the same stack with CommandSource.NULL and level 0.
 * A plain entity's CommandSource in 1.21.1 dropped every message (sendSystemMessage did nothing), as NULL does.
 *
 * And 1.21.1's teleportTo(ServerLevel, x, y, z, Set<RelativeMovement>, yRot, xRot): it moved the entity (or, in another
 * level, a copy made there) to the absolute place and rotation; ServerPlayer's moved the player in its level with the
 * relatives chosen, and to another level through teleportTo(level, x, y, z, yRot, xRot), which first made the player its
 * own camera. 1.21.2's takes a Set<Relative> and a last boolean, whether to make the player its own camera (ServerPlayer
 * calls setCamera(this) when true; Entity's ignores it), and carries both cases through teleport(TeleportTransition). So
 * the old call is the new one with the Relative of each relative's name and the camera reset only when the level changes,
 * as 1.21.1 did.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "RelativeMovement -> Relative, expanded to contain delta movement".
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

    public static boolean teleportTo(net.minecraft.world.entity.Entity self, ServerLevel level, double x, double y, double z, Set relatives, float yRot, float xRot) {
        Set<Relative> moved = new HashSet<>();
        for (Object relative : relatives) {
            moved.add(Relative.valueOf(((Enum) relative).name()));
        }
        return self.teleportTo(level, x, y, z, moved, yRot, xRot, self instanceof ServerPlayer && level != self.level());
    }
}

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
 * And 1.21.1's teleportTo(ServerLevel, x, y, z, Set<RelativeMovement>, yRot, xRot) always took an absolute target:
 * - any other entity: in its level moveTo(x, y, z, yRot, xRot), which set the place and rotation and left the velocity
 *   alone; in another level a copy (restoreFrom, velocity too) moved the same way. The relatives were not read.
 * - a ServerPlayer in its level: connection.teleport(x, y, z, yRot, xRot, relatives) put the player at the absolute place,
 *   and the relatives chose only what the packet sent as offsets; the client (ClientPacketListener.handleMovePlayer) kept
 *   its velocity on a relative axis and zeroed it on an absolute one. In another level: teleportTo(level, x, y, z, yRot,
 *   xRot), which made the player its own camera and moved it with no relatives and zero velocity.
 * Both set the head to yRot.
 * 1.21.2's teleportTo(..., Set<Relative>, yRot, xRot, setCamera) goes through teleport(TeleportTransition), where
 * PositionMoveRotation.calculateAbsolute adds the current value on every axis and angle the set names, so an old relative
 * passed through would move an entity at X=100 asked to X=20 to X=120. Its velocity is the current one on each axis whose
 * DELTA_X, DELTA_Y or DELTA_Z is named, else the transition's (zero). So the old call is the new one with the target and
 * angles absolute (no X, Y, Z, Y_ROT or X_ROT) and the velocity each case kept: all three DELTAs for another entity, the
 * DELTA of each axis the old set named for a player in its level, none for a player changing level, which is also when the
 * camera is reset; then the head is set to yRot (1.21.2's ServerPlayer adds it to the head when Y_ROT is named, else sets it).
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
        boolean player = self instanceof ServerPlayer;
        boolean sameLevel = level == self.level();
        Set<Relative> kept = new HashSet<>();
        if (!player) {
            kept.add(Relative.DELTA_X);
            kept.add(Relative.DELTA_Y);
            kept.add(Relative.DELTA_Z);
        } else if (sameLevel) {
            for (Object relative : relatives) {
                String name = ((Enum) relative).name();
                if (name.equals("X") || name.equals("Y") || name.equals("Z")) {
                    kept.add(Relative.valueOf("DELTA_" + name));
                }
            }
        }
        boolean moved = self.teleportTo(level, x, y, z, kept, yRot, xRot, player && !sameLevel);
        if (moved) {
            self.setYHeadRot(yRot);
        }
        return moved;
    }
}

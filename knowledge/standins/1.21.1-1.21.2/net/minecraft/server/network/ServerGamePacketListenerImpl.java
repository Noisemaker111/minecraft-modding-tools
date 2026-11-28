package mmt.standin.net.minecraft.server.network;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

/**
 * 1.21.1's ServerGamePacketListenerImpl on 1.21.2.
 *
 * Read from both releases: 1.21.1's teleport(x, y, z, yRot, xRot, relatives) moved the player to the absolute x, y, z
 * and rotation, the relatives only choosing which values the packet sent as offsets from the player's current ones; the
 * client kept its velocity on a relative axis and zeroed it on an absolute one (ClientPacketListener.handleMovePlayer).
 * 1.21.2's teleport(PositionMoveRotation, Set<Relative>) adds a relative value to the current one and sets the
 * velocity unless DELTA_X, DELTA_Y or DELTA_Z is given. So the old absolute target is sent as the offset on each
 * relative axis, with that axis's DELTA kept, and as itself on the others with zero velocity.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "RelativeMovement -> Relative, expanded to contain delta movement", and teleports now take a PositionMoveRotation.
 */
public final class ServerGamePacketListenerImpl {

    private ServerGamePacketListenerImpl() {}

    public static void teleport(net.minecraft.server.network.ServerGamePacketListenerImpl self, double x, double y, double z, float yRot, float xRot, Set relatives) {
        ServerPlayer player = self.player;
        Set<Relative> sent = new HashSet<>();
        for (Object relative : relatives) {
            sent.add(Relative.valueOf(((Enum) relative).name()));
        }
        double px = sent.contains(Relative.X) ? x - player.getX() : x;
        double py = sent.contains(Relative.Y) ? y - player.getY() : y;
        double pz = sent.contains(Relative.Z) ? z - player.getZ() : z;
        float pyRot = sent.contains(Relative.Y_ROT) ? yRot - player.getYRot() : yRot;
        float pxRot = sent.contains(Relative.X_ROT) ? xRot - player.getXRot() : xRot;
        if (sent.contains(Relative.X)) {
            sent.add(Relative.DELTA_X);
        }
        if (sent.contains(Relative.Y)) {
            sent.add(Relative.DELTA_Y);
        }
        if (sent.contains(Relative.Z)) {
            sent.add(Relative.DELTA_Z);
        }
        self.teleport(new PositionMoveRotation(new Vec3(px, py, pz), Vec3.ZERO, pyRot, pxRot), sent);
    }
}

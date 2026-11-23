package mmt.standin.net.minecraft.world.entity.player;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 * Also read from both: playNotifySound(sound, source, volume, pitch) did nothing on a plain Player, sent a
 * ClientboundSoundPacket at the player to that player alone on a ServerPlayer (connection.send, a random seed), and
 * played the sound locally at the player on a LocalPlayer (level().playLocalSound(..., false)); 1.21.11 removed it and
 * every piece it used is still public. sweepAttack() sent one SWEEP_ATTACK particle in front of the player on a server
 * level; 1.21.11 made it the private doSweepAttack, which also deals the sweep's damage, so the old public call keeps its
 * particle only. setClientLoaded(loaded) on 1.21.10 marked whether the client had loaded the world, false restarting a
 * 60-tick timeout; 1.21.11 keeps that state on a server player's connection (markClientLoaded, and
 * restartClientLoadTimerAfterRespawn setting the timer to 60) and none on the client's own players.
 *
 * Agrees with NeoForge's 1.21.10 -> 1.21.11 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.11/index.md, CC BY 4.0): "Player#playNotifySound" is removed; "sweepAttack -> doSweepAttack, now private, not one-to-one"; "setClientLoaded -> ServerGamePacketListenerImpl#markClientLoaded, markClientUnloadedAfterDeath, restartClientLoadTimerAfterRespawn; not one-to-one"; "Player#getPermissionLevel, hasPermissions -> permissions, not one-to-one"; "LevelBasedPermissionSet - A set of permissions that checks whether the user has an equal or higher command permission level".
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

    public static void playNotifySound(net.minecraft.world.entity.player.Player self, net.minecraft.sounds.SoundEvent sound, net.minecraft.sounds.SoundSource source, float volume, float pitch) {
        if (self instanceof ServerPlayer server) {
            server.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), source, self.getX(), self.getY(), self.getZ(), volume, pitch, self.getRandom().nextLong()));
        } else if (self.level().isClientSide() && self instanceof LocalPlayer) {
            self.level().playLocalSound(self.getX(), self.getY(), self.getZ(), sound, source, volume, pitch, false);
        }
    }

    public static void sweepAttack(net.minecraft.world.entity.player.Player self) {
        double x = -net.minecraft.util.Mth.sin(self.getYRot() * (float) (Math.PI / 180.0));
        double z = net.minecraft.util.Mth.cos(self.getYRot() * (float) (Math.PI / 180.0));
        if (self.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.SWEEP_ATTACK, self.getX() + x, self.getY(0.5), self.getZ() + z, 0, x, 0.0, z, 0.0);
        }
    }

    public static void setClientLoaded(net.minecraft.world.entity.player.Player self, boolean loaded) {
        if (self instanceof ServerPlayer server && server.connection != null) {
            if (loaded) {
                server.connection.markClientLoaded();
            } else {
                server.connection.restartClientLoadTimerAfterRespawn();
            }
        }
    }
}

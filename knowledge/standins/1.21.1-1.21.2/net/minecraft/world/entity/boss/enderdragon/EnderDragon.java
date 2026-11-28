package mmt.standin.net.minecraft.world.entity.boss.enderdragon;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;

/**
 * 1.21.1's EnderDragon.onCrystalDestroyed(EndCrystal, BlockPos, DamageSource) on 1.21.2.
 *
 * Read from both releases: the body is the same (pick the player from the source, hurt the dragon's head when the crystal
 * was the one healing it, tell the dragon's phase), and 1.21.2 only adds the ServerLevel it hurts the dragon in
 * (hurtServer). Its one caller, EndCrystal.hurtServer, passes the crystal's own server level. The dragon's level is that
 * level; on a client nothing is destroyed, as 1.21.1's EndCrystal.hurt only called it on the server.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "EnderDragon#onCrystalDestroyed now takes in a ServerLevel".
 */
public final class EnderDragon {

    private EnderDragon() {}

    public static void onCrystalDestroyed(net.minecraft.world.entity.boss.enderdragon.EnderDragon self, EndCrystal crystal, BlockPos pos, DamageSource source) {
        if (self.level() instanceof ServerLevel level) {
            self.onCrystalDestroyed(level, crystal, pos, source);
        }
    }
}

package mmt.standin.net.minecraft.world.effect;

import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * 1.20.2's MobEffectUtil on 1.20.3.
 *
 * Read from both releases: 1.20.2's formatDuration(effect, durationFactor) wrote an effect's time left as minutes and
 * seconds at 20 ticks a second (StringUtil.formatTickDuration(ticks)), "infinite" for an endless one. 1.20.3, with /tick
 * changing the rate, takes the ticks a second too (formatDuration(effect, durationFactor, ticksPerSecond)), and the game
 * passes the level's rate. So the old method is the new one at 1.20.2's 20 ticks a second.
 *
 * NeoForge's primers begin at 1.20.4 -> 1.20.5. Agrees with Fabric's 1.20.3 post (https://fabricmc.net/2023/11/30/1203.html): "It is also now possible for the game to run faster or slower than 50 MSPT intentionally".
 */
public final class MobEffectUtil {

    private MobEffectUtil() {}

    public static Component formatDuration(MobEffectInstance effect, float durationFactor) {
        return net.minecraft.world.effect.MobEffectUtil.formatDuration(effect, durationFactor, 20.0F);
    }
}

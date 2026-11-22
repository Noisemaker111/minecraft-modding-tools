package mmt.standin.net.minecraft.world.effect;

/**
 * 1.19.2's MobEffectInstance.isNoCounter() on 1.19.3.
 *
 * Read from both releases: 1.19.2's isNoCounter() read a flag only setNoCounter(true) set, which no game code called, so
 * the game's effects all answered false and showed their time left. 1.19.3 dropped the flag; every effect shows its
 * time. That is false.
 *
 * Fabric's 1.19.3 post (https://fabricmc.net/2022/11/24/1193.html) does not mention isNoCounter, and NeoForge's primers begin at 1.20.2 -> 1.20.3; this is from the game's code alone.
 */
public final class MobEffectInstance {

    private MobEffectInstance() {}

    public static boolean isNoCounter(net.minecraft.world.effect.MobEffectInstance self) {
        return false;
    }
}

package mmt.standin.net.minecraft.world.food;

import net.minecraft.world.effect.MobEffectInstance;

/**
 * 1.21.1's FoodProperties.PossibleEffect, which 1.21.2 dropped, on 1.21.2.
 *
 * Read from both releases: 1.21.1's record held an effect and the probability of giving it when the food is eaten, and
 * effect() gave a copy (new MobEffectInstance(effect)) so a caller could not change the food's. 1.21.2 keeps the same pair
 * in the item's Consumable as ApplyStatusEffectsConsumeEffect(effects, probability); FoodProperties.effects() (the
 * stand-in) makes one of these for each of its effects.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "effects -> ConsumeEffect", with "new ApplyStatusEffectsConsumeEffect(new MobEffectInstance(MobEffects.HUNGER, 600, 0), 0.3F)" for an effect given 30% of the time.
 */
public final class FoodProperties$PossibleEffect {

    private final MobEffectInstance effect;
    private final float probability;

    public FoodProperties$PossibleEffect(MobEffectInstance effect, float probability) {
        this.effect = effect;
        this.probability = probability;
    }

    public MobEffectInstance effect() {
        return new MobEffectInstance(effect);
    }

    public float probability() {
        return probability;
    }
}

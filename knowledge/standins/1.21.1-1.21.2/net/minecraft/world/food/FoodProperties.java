package mmt.standin.net.minecraft.world.food;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.UseRemainder;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;

/**
 * 1.21.1's FoodProperties.effects(), usingConvertsTo(), eatSeconds() and eatDurationTicks() on 1.21.2.
 *
 * Read from both releases: 1.21.1's FoodProperties held, besides nutrition, saturation and canAlwaysEat, how long the
 * food took to eat, the stack left after eating, and the effects it might give (PossibleEffect: an effect and its
 * probability). 1.21.2's keeps only the first three; the rest moved to the item's own components: Consumable
 * (consumeSeconds, and onConsumeEffects, where ApplyStatusEffectsConsumeEffect(effects, probability) is a 1.21.1
 * PossibleEffect for each of its effects) and UseRemainder (convertInto). Item.Properties.food(food, consumable) and
 * usingConvertsTo set them next to the food; a stack's components start from its item's, so a stack's food is the very
 * instance its item was made with. So these answers are read from the item whose default food is this one; a food no
 * item was made with has none of them (no effects, no remainder, the default 1.6 seconds).
 *
 * Known difference: these read the item's default components, not a stack's. A stack whose own CONSUMABLE or
 * USE_REMAINDER was changed (a data pack's or a command's) still answers with its item's: the old methods are asked of the
 * FoodProperties alone, and 1.21.2's food no longer leads back to the stack it came from.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "FoodProperties is now a ConsumableListener", "effects -> ConsumeEffect", "usingConvertsTo -> DataComponents#USE_REMAINDER", and "The Consumable data component ... effectively functions as FoodProperties used to previously".
 */
public final class FoodProperties {

    private FoodProperties() {}

    public static List effects(net.minecraft.world.food.FoodProperties self) {
        List effects = new ArrayList();
        DataComponentMap components = componentsOf(self);
        Consumable consumable = components == null ? null : (Consumable) components.get(DataComponents.CONSUMABLE);
        if (consumable != null) {
            for (Object effect : consumable.onConsumeEffects()) {
                if (effect instanceof ApplyStatusEffectsConsumeEffect apply) {
                    for (Object instance : apply.effects()) {
                        effects.add(new FoodProperties$PossibleEffect((MobEffectInstance) instance, apply.probability()));
                    }
                }
            }
        }
        return effects;
    }

    public static Optional usingConvertsTo(net.minecraft.world.food.FoodProperties self) {
        DataComponentMap components = componentsOf(self);
        UseRemainder remainder = components == null ? null : (UseRemainder) components.get(DataComponents.USE_REMAINDER);
        return remainder == null ? Optional.empty() : Optional.of(remainder.convertInto());
    }

    public static float eatSeconds(net.minecraft.world.food.FoodProperties self) {
        DataComponentMap components = componentsOf(self);
        Consumable consumable = components == null ? null : (Consumable) components.get(DataComponents.CONSUMABLE);
        return consumable == null ? Consumable.DEFAULT_CONSUME_SECONDS : consumable.consumeSeconds();
    }

    public static int eatDurationTicks(net.minecraft.world.food.FoodProperties self) {
        return (int) (eatSeconds(self) * 20.0F);
    }

    private static DataComponentMap componentsOf(net.minecraft.world.food.FoodProperties food) {
        for (Object o : BuiltInRegistries.ITEM) {
            DataComponentMap components = ((Item) o).components();
            if (components.get(DataComponents.FOOD) == food) {
                return components;
            }
        }
        return null;
    }
}

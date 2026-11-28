package mmt.standin.net.minecraft.world.food;

/**
 * 1.21.1's FoodData.getExhaustionLevel() and setExhaustion(float) on 1.21.2.
 *
 * Read from both releases: both keep the player's exhaustion in the field exhaustionLevel (addExhaustion adds to it up to
 * 40, tick takes 4 off for each point of saturation or food lost, the save keeps it as foodExhaustionLevel). 1.21.1 read
 * and set it with these two methods; 1.21.2 dropped them and kept the field, private. So they read and set the field.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "getLastFoodLevel, getExhaustionLevel, setExhaustion is removed".
 */
public final class FoodData {

    private FoodData() {}

    public static float getExhaustionLevel(net.minecraft.world.food.FoodData self) {
        return self.exhaustionLevel;
    }

    public static void setExhaustion(net.minecraft.world.food.FoodData self, float value) {
        self.exhaustionLevel = value;
    }
}

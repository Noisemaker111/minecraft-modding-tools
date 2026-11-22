package mmt.standin.net.minecraft.world.level.block.state;

import com.google.common.collect.ImmutableMap;

/**
 * 1.20.4's StateHolder on 1.20.5.
 *
 * Read from both releases: 1.20.4's getValues() gave the state's property values as an ImmutableMap, in the properties'
 * order; 1.20.5's gives the same values as a Map (a Reference2ObjectArrayMap, in the same order). An ImmutableMap copy
 * of it, in that order.
 *
 * NeoForge's 1.20.4 -> 1.20.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.20.5/index.md, CC BY 4.0) does not list this method; it is from the game's code alone.
 */
public final class StateHolder {

    private StateHolder() {}

    public static ImmutableMap getValues(net.minecraft.world.level.block.state.StateHolder self) {
        return ImmutableMap.copyOf(self.getValues());
    }
}

package mmt.standin.net.minecraft.world.level.storage.loot;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSet;

/**
 * 1.20's LootParams.Builder.create(set) and withDynamicDrop(id, drop) on 1.19.4's LootContext.Builder, which the change
 * map reads LootParams.Builder as (its withParameter, withOptionalParameter, withLuck and getLevel are 1.19.4's own).
 *
 * Read from both releases: create(set) checks the parameters against the set as 1.19.4's create(set) does and gives the
 * parameters (LootParams); a dynamic drop is 1.19.4's with the context it is handed left unread, as 1.20's
 * LootContext.addDynamicDrops hands it only the output.
 */
public final class LootParams$Builder {

    private LootParams$Builder() {
    }

    public static LootParams create(LootContext.Builder self, LootContextParamSet set) {
        return new LootParams(self, set);
    }

    public static LootContext.Builder withDynamicDrop(LootContext.Builder self, ResourceLocation id, LootParams.DynamicDrop drop) {
        return self.withDynamicDrop(id, (context, output) -> drop.add(output));
    }
}

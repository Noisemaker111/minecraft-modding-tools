package mmt.standin.net.minecraft.world.level.storage.loot;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootTable;

/** 1.20's LootDataResolver on 1.19.4, as far as its loot tables: see LootDataManager. */
public interface LootDataResolver {

    LootTable getLootTable(ResourceLocation id);
}

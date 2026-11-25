package mmt.standin.net.minecraft.world.level.storage.loot;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.LootTables;

/**
 * 1.20's LootDataManager on 1.19.4, as far as its loot tables.
 *
 * Read from both releases: 1.20 put 1.19.4's LootTables, PredicateManager and ItemModifierManager into one LootDataManager
 * (MinecraftServer.getLootData(), where 1.19.4 has getLootTables()). Its getLootTable(id) gives the table, LootTable.EMPTY
 * for one it does not have, as 1.19.4's LootTables.get(id) does. Fabric API's LootTableEvents hand 1.19.4's LootTables
 * where 1.20's hand the LootDataManager (from).
 */
public class LootDataManager implements LootDataResolver {

    final LootTables tables;

    public LootDataManager(LootTables tables) {
        this.tables = tables;
    }

    public static LootDataManager from(LootTables tables) {
        return new LootDataManager(tables);
    }

    @Override
    public LootTable getLootTable(ResourceLocation id) {
        return tables.get(id);
    }
}

package mmt.standin.net.minecraft.world.entity;

import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;

/**
 * 1.21.1's EntityType.getDefaultLootTable(), create(CompoundTag, Level) and loadEntityRecursive(CompoundTag, Level,
 * Function) on 1.21.2.
 *
 * Read from both releases: 1.21.1's getDefaultLootTable() always gave a key, the builder's or entities/ID, and the loot
 * table lookup gives an empty table for a key with no table. 1.21.2's gives an Optional, empty for the types built with
 * noLootTable(), and the key otherwise; so the old key is the present one, else entities/ID as 1.21.1 made it.
 * 1.21.2's create and loadEntityRecursive take the EntitySpawnReason the entity is made for; reading a saved entity
 * from its tag, as both calls do, is LOAD in the game's own code (EntityStorage.loadEntities, ServerLevel's entity loads).
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "getDefaultLootTable now returns an Optional in case the loot table is not present" and "create, loadEntityRecursive, loadEntitiesRecursive, loadStaticEntity now takes in an EntitySpawnReason".
 */
public final class EntityType {

    private EntityType() {}

    public static ResourceKey getDefaultLootTable(net.minecraft.world.entity.EntityType self) {
        Optional table = self.getDefaultLootTable();
        if (table.isPresent()) {
            return (ResourceKey) table.get();
        }
        return ResourceKey.create(Registries.LOOT_TABLE, BuiltInRegistries.ENTITY_TYPE.getKey(self).withPrefix("entities/"));
    }

    public static Optional create(CompoundTag tag, Level level) {
        return net.minecraft.world.entity.EntityType.create(tag, level, EntitySpawnReason.LOAD);
    }

    public static Entity loadEntityRecursive(CompoundTag tag, Level level, Function onLoad) {
        return net.minecraft.world.entity.EntityType.loadEntityRecursive(tag, level, EntitySpawnReason.LOAD, onLoad);
    }
}

package mmt.standin.net.minecraft.world.level.storage;

import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 1.20.1's DimensionDataStorage.computeIfAbsent and get on 1.20.2, through the game Fabric Loader runs.
 *
 * Read from both releases: 1.20.1's computeIfAbsent(load, make, id) returned the saved data cached or on disk under id,
 * read with load from the file's "data" tag, else made with make and kept; get(load, id) the same without making one. Each
 * file was run through DataFixTypes.SAVED_DATA, one fix type for every kind of saved data. 1.20.2 takes the two functions
 * as SavedData.Factory(constructor, deserializer, type), with a fix type per kind of vanilla data and none for anything
 * else: DimensionDataStorage.readTagFromDisk calls type.update. Fabric API for 1.20.2 (fabric-object-builder-api-v1
 * 0.91.6, PersistentStateManagerMixin.handleNullDataFixType) returns the file's tag as it is where the type is null, so a
 * factory with no type reads a mod's data as 1.20.1 read data it had no fix for.
 *
 * NeoForge's primers begin at 1.20.2 -> 1.20.3, and Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html) does
 * not mention saved data; this is from the game's and Fabric API's code.
 */
public final class DimensionDataStorage {

    private DimensionDataStorage() {}

    public static SavedData computeIfAbsent(net.minecraft.world.level.storage.DimensionDataStorage self, Function load, Supplier make, String id) {
        return self.computeIfAbsent(new SavedData.Factory(make, load, null), id);
    }

    public static SavedData get(net.minecraft.world.level.storage.DimensionDataStorage self, Function load, String id) {
        return self.get(new SavedData.Factory(make(), load, null), id);
    }

    private static Supplier make() {
        return () -> null;
    }
}

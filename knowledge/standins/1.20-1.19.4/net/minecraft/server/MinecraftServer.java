package mmt.standin.net.minecraft.server;

import mmt.standin.net.minecraft.world.level.storage.loot.LootDataManager;

/** 1.20's MinecraftServer.getLootData() on 1.19.4: its loot tables (getLootTables()), see LootDataManager. */
public final class MinecraftServer {

    private MinecraftServer() {
    }

    public static LootDataManager getLootData(net.minecraft.server.MinecraftServer self) {
        return new LootDataManager(self.getLootTables());
    }
}

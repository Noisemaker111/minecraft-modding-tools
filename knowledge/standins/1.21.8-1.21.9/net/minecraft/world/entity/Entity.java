package mmt.standin.net.minecraft.world.entity;

import net.minecraft.server.MinecraftServer;

/**
 * 1.21.8's Entity on 1.21.9.
 *
 * Read from both releases: 1.21.8's Entity.getServer() returned this.level().getServer(). 1.21.9 dropped it; Level still
 * has getServer() (the server a server level runs in, null on a client level), and its own callers ask the level.
 *
 * Agrees with NeoForge's 1.21.8 -> 1.21.9 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.9/index.md, CC BY 4.0), which lists Entity's getServer among the removals.
 */
public final class Entity {

    private Entity() {}

    public static MinecraftServer getServer(net.minecraft.world.entity.Entity self) {
        return self.level().getServer();
    }
}

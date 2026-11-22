package mmt.standin.net.minecraft.server.level;

import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;

/**
 * 1.21.11's ServerBossEvent on 26.1.
 *
 * Read from both releases: 1.21.11's ServerBossEvent(name, color, overlay) gave the bar a random id of its own
 * (super(Mth.createInsecureUUID(), ...), from Mth's thread-safe random). 26.1 takes the id first
 * (ServerBossEvent(UUID, name, color, overlay)) and Mth.createInsecureUUID takes the random to draw from; the game's own
 * bosses pass one (WitherBoss: Mth.createInsecureUUID(this.random)). So the old constructor draws the id from a thread-safe
 * random, as 1.21.11 did.
 *
 * Agrees with NeoForge's 1.21.11 -> 26.1 primer (https://github.com/neoforged/.github/blob/main/primers/26.1/index.md, CC BY 4.0): "ServerBossEvent now takes in an UUID for the id".
 */
public final class ServerBossEvent {

    private static final RandomSource RANDOM = RandomSource.createThreadSafe();

    private ServerBossEvent() {}

    public static net.minecraft.server.level.ServerBossEvent init(Component name, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay) {
        return new net.minecraft.server.level.ServerBossEvent(Mth.createInsecureUUID(RANDOM), name, color, overlay);
    }
}

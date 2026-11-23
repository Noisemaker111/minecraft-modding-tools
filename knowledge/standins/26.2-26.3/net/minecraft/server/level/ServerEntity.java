package mmt.standin.net.minecraft.server.level;

/**
 * 26.2's ServerEntity.updateInterval (an int) read on 26.3.
 *
 * Read from both releases: 26.2's ServerEntity kept the period, in ticks, at which it sent an entity's position
 * (tickCount % updateInterval == 0, the next send at tickCount / updateInterval * updateInterval + updateInterval). 26.3
 * keeps an UpdateInterval in the same field, and the entity type's period makes it UpdateInterval.periodic(period), whose
 * nextInterval(tick) is that same next send; so the period is the next send after tick 0. NEVER (an entity type that
 * never updates on the client, new in 26.3) answers 0, a period no 26.2 entity had.
 *
 * Agrees with NeoForge's 26.2 -> 26.3 primer (https://github.com/neoforged/.github/blob/main/primers/26.3/index.md, CC BY 4.0):
 * "ServerEntity now takes in an UpdateInterval instead of an int"; "UpdateInterval - Handles when an entity should be next updated".
 */
public final class ServerEntity {

    private ServerEntity() {}

    public static int updateInterval(net.minecraft.server.level.ServerEntity self) {
        return self.updateInterval.nextInterval(0);
    }
}

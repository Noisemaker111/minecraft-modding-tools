package mmt.standin.net.minecraft.server.level;

import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClocks;

/**
 * 1.21.11's ServerLevel on 26.1.
 *
 * Read from both releases: 1.21.11's ServerLevel.setDayTime(time) set its level data's day time, which the overworld
 * kept (PrimaryLevelData) and every other dimension read from it, its own data ignoring the call (DerivedLevelData.setDayTime
 * does nothing). 26.1 keeps time in world clocks the server's clock manager ticks: the overworld's day time is the
 * WorldClocks.OVERWORLD clock (Level.getOverworldClockTime reads it), and /time set sets a clock's total ticks
 * (ServerClockManager.setTotalTicks). So the old method sets the overworld clock when called on the overworld, and does
 * nothing on another dimension, as 1.21.11 did.
 *
 * Agrees with NeoForge's 1.21.11 -> 26.1 primer (https://github.com/neoforged/.github/blob/main/primers/26.1/index.md, CC BY 4.0): "ServerLevel: setDayTime, getDayCount are removed", "Level: getDayTime -> getOverworldClockTime, not one-to-one", and "ServerClockManager - Manages the ticking of the world clocks on the server side".
 */
public final class ServerLevel {

    private ServerLevel() {}

    public static void setDayTime(net.minecraft.server.level.ServerLevel self, long time) {
        if (self != self.getServer().overworld()) {
            return;
        }
        Optional clock = self.registryAccess().get(WorldClocks.OVERWORLD);
        if (clock.isPresent()) {
            self.clockManager().setTotalTicks((Holder) clock.get(), time);
        }
    }
}

package mmt.standin.net.minecraft.client;

import net.minecraft.client.DeltaTracker;

/**
 * 1.20.6's Timer on 1.21.
 *
 * Read from both releases: 1.20.6's Timer kept the fraction of a tick since the last game tick in the field partialTick
 * (set to the paused value while the game was paused). 1.21 renamed the class DeltaTracker.Timer and keeps the fraction
 * privately (deltaTickResidual, pausedDeltaTickResidual while paused), read with getGameTimeDeltaPartialTick(true).
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0): "net.minecraft.client.Timer -> DeltaTracker$Timer".
 */
public final class Timer {

    private Timer() {}

    public static float partialTick(DeltaTracker.Timer self) {
        return self.getGameTimeDeltaPartialTick(true);
    }
}

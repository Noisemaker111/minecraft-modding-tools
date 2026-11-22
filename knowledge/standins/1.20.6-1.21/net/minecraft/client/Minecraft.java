package mmt.standin.net.minecraft.client;

/**
 * 1.20.6's Minecraft on 1.21.
 *
 * Read from both releases: 1.20.6's getFrameTime() returned the timer's partialTick (held at pausePartialTick while the
 * game was paused) and getDeltaFrameTime() its tickDelta, the game ticks since the last frame. 1.21 keeps both in the
 * DeltaTracker getTimer() returns: getGameTimeDeltaPartialTick(true) is the partial tick, the paused value while paused
 * (true ignores the tick-freeze override, which 1.20.6 applied elsewhere), and getGameTimeDeltaTicks() the ticks since
 * the last frame.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0): "net.minecraft.client.Minecraft#getFrameTime, getDeltaFrameTime -> getTimer".
 */
public final class Minecraft {

    private Minecraft() {}

    public static float getFrameTime(net.minecraft.client.Minecraft self) {
        return self.getTimer().getGameTimeDeltaPartialTick(true);
    }

    public static float getDeltaFrameTime(net.minecraft.client.Minecraft self) {
        return self.getTimer().getGameTimeDeltaTicks();
    }

    public static float pausePartialTick(net.minecraft.client.Minecraft self) {
        return self.getTimer().getGameTimeDeltaPartialTick(true);
    }
}

package mmt.standin.net.minecraft.client;

/**
 * 1.21's DeltaTracker on 1.20.6, for a port back: the class itself, which 1.20.6 does not have yet.
 *
 * Read from both releases: 1.21's DeltaTracker gives the frame's time three ways, and Minecraft.getTimer() returns the
 * game's own (DeltaTracker.Timer). getGameTimeDeltaTicks() is the game ticks since the last frame; 1.20.6's Timer keeps
 * the same, tickDelta. getGameTimeDeltaPartialTick(runsNormally) is the fraction of a tick passed, the value kept when
 * the game paused while paused, and 1 for what does not run normally while the level's tick rate is frozen
 * (Minecraft.isLevelRunningNormally()); 1.20.6's game renderer is given the same fraction (pausePartialTick while
 * paused, else timer.partialTick), and 1.20.6's TickRateManager.runsNormally() is the same test. getRealtimeDeltaTicks()
 * is the frame's time in ticks at 20 a second whatever the tick rate, 0.5 past 7; 1.20.6's tickDelta is that time at the
 * tick rate (msPerTick over targetMsptProvider), so it is scaled back. 1.20.6 advances its timer only on frames that
 * render the level, where 1.21 advances the real time on every frame. ZERO and ONE answer the same fixed value always.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "net.minecraft.client.Minecraft#getFrameTime, getDeltaFrameTime -> getTimer" and "net.minecraft.client.Timer -> DeltaTracker$Timer".
 */
public interface DeltaTracker {

    DeltaTracker ZERO = new Fixed(0.0F);

    DeltaTracker ONE = new Fixed(1.0F);

    float getGameTimeDeltaTicks();

    float getGameTimeDeltaPartialTick(boolean runsNormally);

    float getRealtimeDeltaTicks();

    /** The game's own, read from Minecraft and its timer when asked. */
    final class Game implements DeltaTracker {

        final net.minecraft.client.Minecraft minecraft;

        public Game(net.minecraft.client.Minecraft minecraft) {
            this.minecraft = minecraft;
        }

        public float getGameTimeDeltaTicks() {
            return minecraft.timer.tickDelta;
        }

        public float getGameTimeDeltaPartialTick(boolean runsNormally) {
            if (!runsNormally && minecraft.level != null && !minecraft.level.tickRateManager().runsNormally()) {
                return 1.0F;
            }
            return minecraft.pause ? minecraft.pausePartialTick : minecraft.timer.partialTick;
        }

        public float getRealtimeDeltaTicks() {
            net.minecraft.client.Timer timer = minecraft.timer;
            float ticks = timer.tickDelta * timer.targetMsptProvider.apply(timer.msPerTick) / timer.msPerTick;
            return ticks > 7.0F ? 0.5F : ticks;
        }
    }

    /** One value for every question, as 1.21's ZERO and ONE. */
    final class Fixed implements DeltaTracker {

        final float value;

        public Fixed(float value) {
            this.value = value;
        }

        public float getGameTimeDeltaTicks() {
            return value;
        }

        public float getGameTimeDeltaPartialTick(boolean runsNormally) {
            return value;
        }

        public float getRealtimeDeltaTicks() {
            return value;
        }
    }
}

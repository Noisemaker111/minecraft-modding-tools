package mmt.standin.net.minecraft.client;

/**
 * 1.21's Minecraft.getTimer() on 1.20.6, for a port back: the game's DeltaTracker (see the stand-in DeltaTracker),
 * read from 1.20.6's timer and pause state.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "net.minecraft.client.Minecraft#getFrameTime, getDeltaFrameTime -> getTimer".
 */
public final class Minecraft {

    private Minecraft() {}

    public static DeltaTracker getTimer(net.minecraft.client.Minecraft self) {
        return new DeltaTracker.Game(self);
    }
}

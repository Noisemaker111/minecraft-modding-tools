package mmt.standin.net.neoforged.neoforge.client.event;

import mmt.standin.net.minecraft.client.DeltaTracker;

/**
 * NeoForge 21.0's RenderGuiEvent.getPartialTick() (21.0.167) on 20.6 (20.6.144), for a port back.
 *
 * Read from both loaders' files and both games': 21.0's getPartialTick() returns the DeltaTracker the game renders the
 * frame with, Minecraft's own; 20.6's returns the float that frame was rendered with, the game renderer's partial tick
 * (the paused value while paused). The stand-in DeltaTracker.Game is Minecraft's own on 20.6, whose
 * getGameTimeDeltaPartialTick(true) is that float.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "net.minecraft.client.Minecraft#getFrameTime, getDeltaFrameTime -> getTimer".
 */
public final class RenderGuiEvent {

    private RenderGuiEvent() {}

    public static DeltaTracker getPartialTick(net.neoforged.neoforge.client.event.RenderGuiEvent self) {
        return new DeltaTracker.Game(net.minecraft.client.Minecraft.getInstance());
    }
}

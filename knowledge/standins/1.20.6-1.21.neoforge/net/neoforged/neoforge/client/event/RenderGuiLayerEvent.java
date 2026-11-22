package mmt.standin.net.neoforged.neoforge.client.event;

/**
 * NeoForge 20.6's RenderGuiLayerEvent (20.6.144) on 21.0 (21.0.167).
 *
 * Read from both loaders' files and both games': 20.6's getPartialTick() was the float Gui.render received, the game
 * renderer's partial tick (the paused value while paused). 21.0's returns the DeltaTracker Gui.render now receives, whose
 * getGameTimeDeltaPartialTick(true) is that partial tick.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0): "net.minecraft.client.gui.Gui#render now takes in a DeltaTracker".
 */
public final class RenderGuiLayerEvent {

    private RenderGuiLayerEvent() {}

    public static float getPartialTick(net.neoforged.neoforge.client.event.RenderGuiLayerEvent self) {
        return self.getPartialTick().getGameTimeDeltaPartialTick(true);
    }
}

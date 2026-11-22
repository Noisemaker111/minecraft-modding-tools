package mmt.standin.net.neoforged.neoforge.client.event;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;

/**
 * NeoForge 21.8's RenderLevelStageEvent (21.8.57) on 21.9 (21.9.16-beta).
 *
 * Read from both releases: 21.8's event kept the Camera and DeltaTracker LevelRenderer.renderLevel was called with, which
 * 1.21.8's GameRenderer.renderLevel passed as its main camera (gameRenderer.getMainCamera()) and the client's delta tracker
 * (Minecraft.getDeltaTracker()). 21.9's event keeps the LevelRenderState extracted from them instead and has neither
 * getter; 1.21.9's GameRenderer still renders the level from the same main camera and delta tracker. Each getter is
 * that object, read from the client while the event runs.
 *
 * Loader files only: NeoForge's game primer covers vanilla classes, not NeoForge's own events.
 */
public final class RenderLevelStageEvent {

    private RenderLevelStageEvent() {}

    public static Camera getCamera(net.neoforged.neoforge.client.event.RenderLevelStageEvent self) {
        return Minecraft.getInstance().gameRenderer.getMainCamera();
    }

    public static DeltaTracker getPartialTick(net.neoforged.neoforge.client.event.RenderLevelStageEvent self) {
        return Minecraft.getInstance().getDeltaTracker();
    }
}

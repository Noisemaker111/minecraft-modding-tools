package mmt.standin.net.minecraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.DebugScreenOverlay;

/**
 * 1.20.1's Options.renderDebug and renderDebugCharts on 1.20.2.
 *
 * Read from both releases: 1.20.1 kept whether the F3 screen was open in Options.renderDebug, and the profiler chart
 * (shift+F3) in renderDebugCharts; Gui drew the screen when renderDebug was set and the GUI was not hidden. 1.20.2 moved
 * both into the DebugScreenOverlay that Minecraft.getDebugOverlay() returns: showDebugScreen() (open, GUI not hidden),
 * showProfilerChart(), and toggleOverlay() / toggleProfilerChart() to change them. Reading the field is what the game
 * now asks; writing it toggles the overlay when it differs. With the GUI hidden the overlay's own flag cannot be read
 * without opening a private field, so a write then leaves it.
 *
 * NeoForge's primers begin at 1.20.2 -> 1.20.3, and Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html) does not mention renderDebug; this is from the game's code alone.
 */
public final class Options {

    private Options() {}

    static DebugScreenOverlay overlay() {
        return Minecraft.getInstance().getDebugOverlay();
    }

    public static boolean renderDebug(net.minecraft.client.Options self) {
        return overlay().showDebugScreen();
    }

    public static void renderDebug(net.minecraft.client.Options self, boolean value) {
        if (!self.hideGui && overlay().showDebugScreen() != value) {
            overlay().toggleOverlay();
        }
    }

    public static boolean renderDebugCharts(net.minecraft.client.Options self) {
        return overlay().showProfilerChart();
    }

    public static void renderDebugCharts(net.minecraft.client.Options self, boolean value) {
        if (!self.hideGui && overlay().showProfilerChart() != value) {
            overlay().toggleProfilerChart();
        }
    }
}

package mmt.standin.com.mojang.blaze3d.systems;

import com.mojang.renderpearl.frontend.FrontendGpuDevice;

/**
 * 26.2's RenderSystem on 26.3.
 *
 * Read from both releases: 26.2's RenderSystem.getDevice() returned the game's GpuDevice. 26.3 renames that class
 * FrontendGpuDevice (renderpearl.frontend) and declares getDevice() to return the GpuDevice interface it implements
 * (renderpearl.api.device); the device installed is always a FrontendGpuDevice (GlBackend and VulkanBackend.createDevice
 * both return new FrontendGpuDevice(...), which Minecraft hands to RenderSystem.initRenderer). So the old method is the
 * installed device as the class it is.
 *
 * Agrees with NeoForge's 26.2 -> 26.3 primer (https://github.com/neoforged/.github/blob/main/primers/26.3/index.md, CC BY 4.0): "GpuDevice -> renderpearl.frontend.FrontendGpuDevice", "Definition interface in renderpearl.api.device.GpuDevice".
 */
public final class RenderSystem {

    private RenderSystem() {}

    public static FrontendGpuDevice getDevice() {
        return (FrontendGpuDevice) com.mojang.blaze3d.systems.RenderSystem.getDevice();
    }
}

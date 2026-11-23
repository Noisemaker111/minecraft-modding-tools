package mmt.standin.com.mojang.blaze3d.platform;

import com.mojang.blaze3d.systems.RenderSystem;

/**
 * 1.21.4's GlUtil, which 1.21.5 removed.
 *
 * Read from both releases: 1.21.4's GlUtil answered the GL vendor, renderer and version strings (glGetString of GL_VENDOR, GL_RENDERER, GL_VERSION) and the CPU description
 * (GLX._getCpuInfo()). 1.21.5 asks the render device for the same strings (RenderSystem.getDevice(): getVendor(),
 * getRenderer(), getVersion(), which its GL device answers with the same glGetString calls) and keeps GLX._getCpuInfo().
 *
 * Agrees with NeoForge's 1.21.4 -> 1.21.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.5/index.md, CC BY 4.0):
 * "GlUtil class is removed".
 */
public final class GlUtil {

    private GlUtil() {}

    public static String getVendor() {
        return RenderSystem.getDevice().getVendor();
    }

    public static String getCpuInfo() {
        return com.mojang.blaze3d.platform.GLX._getCpuInfo();
    }

    public static String getRenderer() {
        return RenderSystem.getDevice().getRenderer();
    }

    public static String getOpenGLVersion() {
        return RenderSystem.getDevice().getVersion();
    }
}

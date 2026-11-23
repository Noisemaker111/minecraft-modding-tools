package mmt.standin.com.mojang.blaze3d.platform;

import com.mojang.blaze3d.opengl.GlStateManager;

/**
 * 1.21.4's GlUtil, which 1.21.5 removed.
 *
 * Read from both releases: 1.21.4's GlUtil answered the GL vendor, renderer and version strings (glGetString of GL_VENDOR, GL_RENDERER, GL_VERSION) and the CPU description
 * (GLX._getCpuInfo()). 1.21.5 moved GlStateManager to com.mojang.blaze3d.opengl, where _getString(name) is still the
 * glGetString call its GL device answers getVendor(), getRenderer() and getVersion() with, and keeps GLX._getCpuInfo(); so
 * each old method makes the same call. (Asking RenderSystem.getDevice() instead would break a later update: 26.3 moved the
 * device API, while the route follows GlStateManager to its 26.3 home.)
 *
 * Agrees with NeoForge's 1.21.4 -> 1.21.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.5/index.md, CC BY 4.0):
 * "GlUtil class is removed".
 */
public final class GlUtil {

    private GlUtil() {}

    public static String getVendor() {
        return GlStateManager._getString(7936);
    }

    public static String getCpuInfo() {
        return com.mojang.blaze3d.platform.GLX._getCpuInfo();
    }

    public static String getRenderer() {
        return GlStateManager._getString(7937);
    }

    public static String getOpenGLVersion() {
        return GlStateManager._getString(7938);
    }
}

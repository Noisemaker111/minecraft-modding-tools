package mmt.standin.com.mojang.blaze3d.platform;

import java.util.function.BiConsumer;

/**
 * 26.2's Window.checkGlfwError(handler) on 26.3.
 *
 * Read from both releases: 26.2's checkGlfwError asked GLFW for its pending error (glfwGetError) and, when there was one,
 * handed its code and description to the handler; with no pending error it did nothing. 26.3 moved the window, input and
 * clipboard from GLFW to SDL (Window.handleEvent, getErrorSection), and no class of the 26.3 client refers to GLFW any
 * more, so GLFW is never initialised and has no error to report. The old call is the no-error case.
 *
 * Agrees with NeoForge's 26.2 -> 26.3 primer (https://github.com/neoforged/.github/blob/main/primers/26.3/index.md, CC BY 4.0), under Window: "checkGlfwError is removed", "handleEvent - Handles events sent from SDL", "getErrorSection - Returns the error sent by the window".
 */
public final class Window {

    private Window() {}

    public static void checkGlfwError(BiConsumer handler) {
    }
}

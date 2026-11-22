package mmt.standin.net.minecraft.client;

import net.minecraft.client.GraphicsStatus;

/**
 * 1.15.2's Options.fancyGraphics on 1.16.
 *
 * Read from both releases: 1.15.2 kept fancy or fast graphics as the boolean fancyGraphics. 1.16 keeps graphicsMode, a
 * GraphicsStatus (FAST, FANCY or FABULOUS), and reads an old options file's fancyGraphics=true as FANCY and false as
 * FAST. Reading answers whether the mode is not FAST; writing sets FANCY or FAST the same way.
 *
 * Fabric's 1.16 post (https://fabricmc.net/2020/06/23/116.html) does not mention it; this is from the game's code alone.
 */
public final class Options {

    private Options() {}

    public static boolean fancyGraphics(net.minecraft.client.Options self) {
        return self.graphicsMode != GraphicsStatus.FAST;
    }

    public static void fancyGraphics(net.minecraft.client.Options self, boolean fancy) {
        self.graphicsMode = fancy ? GraphicsStatus.FANCY : GraphicsStatus.FAST;
    }
}

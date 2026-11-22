package mmt.standin.net.minecraft.client.gui;

/**
 * 1.21.1's GuiGraphics on 1.21.2.
 *
 * Read from both releases: 1.21.1 drew each call at once unless inside drawManaged(r), which flushed, ran r with the
 * per-call flushes off (managed = true) so its draws batched, and flushed again. 1.21.2 batches every draw and has no
 * managed mode (flushIfManaged and flushIfUnmanaged are gone; flush() ends the batch), so drawManaged is the two flushes
 * around r.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "drawManaged is removed".
 */
public final class GuiGraphics {

    private GuiGraphics() {}

    public static void drawManaged(net.minecraft.client.gui.GuiGraphics self, Runnable runnable) {
        self.flush();
        runnable.run();
        self.flush();
    }
}

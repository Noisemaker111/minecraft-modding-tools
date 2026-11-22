package mmt.standin.net.minecraft.client.gui;

import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;

/**
 * 1.21.1's Font on 1.21.2.
 *
 * Read from both releases: renderText(String, ...) is private in both and draws one pass of the text (the shadow pass
 * when dropShadow), returning the x it ends at; drawInternal calls it twice, shadow first. 1.21.2 added a last parameter,
 * which every public path (drawInBatch) passes as true, and moved the background colour into the render output. The old
 * call is the new one with that last argument true. renderText is private: the port opens it through the mod's access
 * transformer, as the mod's own transformer opened the old one.
 *
 * NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0) does not mention renderText; this is from the game's code alone.
 */
public final class Font {

    private Font() {}

    public static float renderText(net.minecraft.client.gui.Font self, String text, float x, float y, int color, boolean dropShadow, Matrix4f matrix,
        MultiBufferSource buffer, net.minecraft.client.gui.Font.DisplayMode displayMode, int backgroundColor, int packedLightCoords) {
        return self.renderText(text, x, y, color, dropShadow, matrix, buffer, displayMode, backgroundColor, packedLightCoords, true);
    }
}

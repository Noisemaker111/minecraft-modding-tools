package mmt.standin.net.minecraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;

/**
 * 1.15.2's Font.draw and drawShadow(String, x, y, colour) on 1.16.
 *
 * Read from both releases: 1.15.2 drew a string at (x, y) with the current OpenGL model-view matrix; 1.16 takes a
 * PoseStack whose top pose is multiplied in as well, and still draws with OpenGL's model-view matrix. A new PoseStack's
 * pose is the identity, so drawing with one draws where 1.15.2 did.
 *
 * Fabric's 1.16 post (https://fabricmc.net/2020/06/23/116.html) does not mention it; this is from the game's code alone.
 */
public final class Font {

    private Font() {}

    public static int draw(net.minecraft.client.gui.Font self, String text, float x, float y, int color) {
        return self.draw(new PoseStack(), text, x, y, color);
    }

    public static int drawShadow(net.minecraft.client.gui.Font self, String text, float x, float y, int color) {
        return self.drawShadow(new PoseStack(), text, x, y, color);
    }
}

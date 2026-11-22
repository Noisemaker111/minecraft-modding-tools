package mmt.standin.net.minecraft.client.gui.screens.advancements;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;

/**
 * 1.19.3's AdvancementWidget.render9Sprite on 1.19.4.
 *
 * Read from both releases: 1.19.3's render9Sprite(pose, x, y, width, height, corner, textureWidth, textureHeight, u, v)
 * drew a box of the bound texture's region at (u, v), textureWidth x textureHeight, stretched to width x height: its four
 * corner x corner squares drawn once and the edges and middle repeated (renderRepeating: whole tiles from the left and
 * top, the last cut short), each with the widget's blit. 1.19.4 dropped it (the widget draws its title box with
 * GuiComponent.blitNineSliced, which spreads the repeats evenly instead) and made blit static on GuiComponent, the same
 * drawing. This is 1.19.3's body on GuiComponent.blit.
 *
 * Fabric's 1.19.4 post (https://fabricmc.net/2023/03/01/1194.html) does not mention render9Sprite, and NeoForge's primers begin at 1.20.2 -> 1.20.3; this is from the game's code alone.
 */
public final class AdvancementWidget {

    private AdvancementWidget() {}

    public static void render9Sprite(net.minecraft.client.gui.screens.advancements.AdvancementWidget self, PoseStack pose, int x, int y, int width, int height, int corner, int textureWidth, int textureHeight, int u, int v) {
        GuiComponent.blit(pose, x, y, u, v, corner, corner);
        repeat(pose, x + corner, y, width - corner - corner, corner, u + corner, v, textureWidth - corner - corner, textureHeight);
        GuiComponent.blit(pose, x + width - corner, y, u + textureWidth - corner, v, corner, corner);
        GuiComponent.blit(pose, x, y + height - corner, u, v + textureHeight - corner, corner, corner);
        repeat(pose, x + corner, y + height - corner, width - corner - corner, corner, u + corner, v + textureHeight - corner, textureWidth - corner - corner, textureHeight);
        GuiComponent.blit(pose, x + width - corner, y + height - corner, u + textureWidth - corner, v + textureHeight - corner, corner, corner);
        repeat(pose, x, y + corner, corner, height - corner - corner, u, v + corner, textureWidth, textureHeight - corner - corner);
        repeat(pose, x + corner, y + corner, width - corner - corner, height - corner - corner, u + corner, v + corner, textureWidth - corner - corner, textureHeight - corner - corner);
        repeat(pose, x + width - corner, y + corner, corner, height - corner - corner, u + textureWidth - corner, v + corner, textureWidth, textureHeight - corner - corner);
    }

    static void repeat(PoseStack pose, int x, int y, int width, int height, int u, int v, int tileWidth, int tileHeight) {
        for (int dx = 0; dx < width; dx += tileWidth) {
            int w = Math.min(tileWidth, width - dx);
            for (int dy = 0; dy < height; dy += tileHeight) {
                int h = Math.min(tileHeight, height - dy);
                GuiComponent.blit(pose, x + dx, y + dy, u, v, w, h);
            }
        }
    }
}

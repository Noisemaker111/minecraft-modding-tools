package mmt.standin.net.minecraft.client.gui.screens.inventory;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.LivingEntity;

/**
 * 1.19.3's InventoryScreen.renderEntityInInventory(x, y, scale, mouseX, mouseY, entity) on 1.19.4.
 *
 * Read from both releases: 1.19.3's drew the entity at (x, y) and the given scale, turned to look toward the mouse
 * offsets, on RenderSystem's model view stack. 1.19.4 split it: renderEntityInInventoryFollowsMouse(poseStack, x, y,
 * scale, mouseX, mouseY, entity) turns the entity the same way (the same angles from atan(offset / 40)) and
 * renderEntityInInventory(poseStack, x, y, scale, rotation, cameraRotation, entity) draws it within the pose stack. The
 * old call is the first with a fresh PoseStack, where 1.19.3 drew from the model view stack alone.
 *
 * Fabric's 1.19.4 post (https://fabricmc.net/2023/03/01/1194.html) does not mention renderEntityInInventory, and NeoForge's primers begin at 1.20.2 -> 1.20.3; this is from the game's code alone.
 */
public final class InventoryScreen {

    private InventoryScreen() {}

    public static void renderEntityInInventory(int x, int y, int scale, float mouseX, float mouseY, LivingEntity entity) {
        net.minecraft.client.gui.screens.inventory.InventoryScreen.renderEntityInInventoryFollowsMouse(new PoseStack(), x, y, scale, mouseX, mouseY, entity);
    }
}

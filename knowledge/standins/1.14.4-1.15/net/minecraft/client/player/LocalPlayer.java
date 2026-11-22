package mmt.standin.net.minecraft.client.player;

/**
 * 1.14.4's LocalPlayer.isSneaking() on 1.15.
 *
 * Read from both releases: 1.14.4's isSneaking() answered isTryingToSneak(), whether the sneak key was held (the
 * player's input sneakKeyDown). 1.15 renamed the input's flag shiftKeyDown, and LocalPlayer.isShiftKeyDown() answers
 * it (input not null and shiftKeyDown); isCrouching() is the pose, which differs while flying or swimming. This answers
 * isShiftKeyDown().
 *
 * Fabric's 1.15 post (https://fabricmc.net/2019/12/10/115.html) does not mention it; this is from the game's code alone.
 */
public final class LocalPlayer {

    private LocalPlayer() {}

    public static boolean isSneaking(net.minecraft.client.player.LocalPlayer self) {
        return self.isShiftKeyDown();
    }
}

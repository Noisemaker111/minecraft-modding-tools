package mmt.standin.net.minecraft.world.entity;

import net.minecraft.world.InteractionHand;

/**
 * 26.2's LivingEntity on 26.3.
 *
 * Read from both releases: 26.2's swing(hand, sendToSwingingEntity) started the arm's swing unless one was already under
 * way, timed by the hand's item (ItemStack.getSwingAnimation(), its SWING_ANIMATION component), and on a server sent the
 * animation to the players tracking the entity, and to the entity itself when asked. 26.3's swing(hand, animation,
 * sendToSwingingEntity) does the same for the animation it is given, and splits the item's animation in two: the one it
 * attacks with (getAttackAnimation, used by Mob.swingForAttack) and the one it is used with (getInteractAnimation). A
 * call made with no attack is a use, so the old method swings with the hand's item's interact animation.
 *
 * Agrees with NeoForge's 26.2 -> 26.3 primer (https://github.com/neoforged/.github/blob/main/primers/26.3/index.md, CC BY 4.0): "swing(InteractionHand, boolean) now takes in a SwingAnimation, returning whether the entity has made a successful swing" and "getSwingAnimation split into getAttackAnimation, getInteractAnimation".
 */
public final class LivingEntity {

    private LivingEntity() {}

    public static void swing(net.minecraft.world.entity.LivingEntity self, InteractionHand hand, boolean sendToSwingingEntity) {
        self.swing(hand, self.getItemInHand(hand).getInteractAnimation(), sendToSwingingEntity);
    }
}

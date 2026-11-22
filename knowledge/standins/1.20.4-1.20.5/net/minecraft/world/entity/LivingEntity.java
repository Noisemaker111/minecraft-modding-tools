package mmt.standin.net.minecraft.world.entity;

import net.minecraft.world.InteractionHand;

/**
 * 1.20.4's LivingEntity on 1.20.5.
 *
 * Read from both releases: 1.20.4's broadcastBreakEvent(hand) was broadcastBreakEvent of the hand's slot (MAINHAND for
 * the main hand, else OFFHAND). 1.20.5 dropped the hand form and keeps broadcastBreakEvent(slot) and
 * LivingEntity.getSlotForHand(hand), the same choice of slot.
 *
 * NeoForge's 1.20.4 -> 1.20.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.20.5/index.md, CC BY 4.0) does not list this method; it is from the game's code alone.
 */
public final class LivingEntity {

    private LivingEntity() {}

    public static void broadcastBreakEvent(net.minecraft.world.entity.LivingEntity self, InteractionHand hand) {
        self.broadcastBreakEvent(net.minecraft.world.entity.LivingEntity.getSlotForHand(hand));
    }
}

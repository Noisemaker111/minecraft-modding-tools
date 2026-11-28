package mmt.standin.net.minecraft.world.entity;

/**
 * 1.19.4's LivingEntity.walkAnimation on 1.19.3, for a port back: the entity's walk animation over 1.19.3's three
 * fields (see the WalkAnimationState stand-in, which reads both releases).
 *
 * Not the same: each read gives a new view of the same fields, so two reads are equal in what they hold but not the
 * same object.
 */
public final class LivingEntity {

    private LivingEntity() {
    }

    public static WalkAnimationState walkAnimation(net.minecraft.world.entity.LivingEntity self) {
        return new WalkAnimationState(self);
    }
}

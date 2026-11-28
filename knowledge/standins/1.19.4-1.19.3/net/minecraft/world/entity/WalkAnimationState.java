package mmt.standin.net.minecraft.world.entity;

import net.minecraft.world.entity.LivingEntity;

/**
 * 1.19.4's WalkAnimationState on 1.19.3: the class itself, which 1.19.3 does not have.
 *
 * Read from both releases: 1.19.4 moved a living entity's walk animation from three fields of LivingEntity
 * (animationSpeedOld, animationSpeed, animationPosition, 1.19.3's) into this object (speedOld, speed, position), held in
 * the new field walkAnimation. update(target, factor) is 1.19.3's step in calculateEntityAnimation (the old speed kept,
 * the speed moved factor of the way to the target, the position moved by the speed; 1.19.3 always with factor 0.4),
 * and position(partial) and speed(partial) are what 1.19.3's renderers work out from the fields. So one made for an
 * entity (the LivingEntity stand-in's walkAnimation) reads and writes that entity's three fields, and one a mod makes
 * itself keeps its own, as 1.19.4's does.
 */
public class WalkAnimationState {

    private final LivingEntity entity;
    private float speedOld;
    private float speed;
    private float position;

    public WalkAnimationState() {
        this(null);
    }

    public WalkAnimationState(LivingEntity entity) {
        this.entity = entity;
    }

    private float old() {
        return entity != null ? entity.animationSpeedOld : speedOld;
    }

    private float now() {
        return entity != null ? entity.animationSpeed : speed;
    }

    private float at() {
        return entity != null ? entity.animationPosition : position;
    }

    private void set(float old, float now, float at) {
        if (entity != null) {
            entity.animationSpeedOld = old;
            entity.animationSpeed = now;
            entity.animationPosition = at;
        } else {
            speedOld = old;
            speed = now;
            position = at;
        }
    }

    public void setSpeed(float speed) {
        set(old(), speed, at());
    }

    public void update(float target, float factor) {
        float now = now() + (target - now()) * factor;
        set(now(), now, at() + now);
    }

    public float speed() {
        return now();
    }

    public float speed(float partial) {
        return old() + partial * (now() - old());
    }

    public float position() {
        return at();
    }

    public float position(float partial) {
        return at() - now() * (1.0F - partial);
    }

    public boolean isMoving() {
        return now() > 1.0E-5F;
    }
}

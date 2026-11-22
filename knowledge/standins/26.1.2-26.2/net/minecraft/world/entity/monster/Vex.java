package mmt.standin.net.minecraft.world.entity.monster;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * 26.1.2's Vex on 26.2.
 *
 * Read from both releases: 26.1.2's Vex.getOwner() returned the mob that summoned it, the owner reference resolved as a
 * Mob (EntityReference.get(owner, level, Mob.class)), null when there was none or it was no mob. 26.2's Vex is an
 * OwnableEntity whose getOwner() returns the owner as a LivingEntity (setOwner still takes a Mob). So the old method is
 * the new owner when it is a mob.
 *
 * Agrees with NeoForge's 26.1.2 -> 26.2 primer (https://github.com/neoforged/.github/blob/main/primers/26.2/index.md, CC BY 4.0): "Vex now implements OwnableEntity".
 */
public final class Vex {

    private Vex() {}

    public static Mob getOwner(net.minecraft.world.entity.monster.Vex self) {
        LivingEntity owner = self.getOwner();
        return owner instanceof Mob mob ? mob : null;
    }
}

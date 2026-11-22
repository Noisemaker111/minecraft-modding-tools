package mmt.standin.net.minecraft.world.level;

import net.minecraft.world.attribute.EnvironmentAttributes;

/**
 * 1.21.10's Level on 1.21.11.
 *
 * Read from both releases: 1.21.10's getSunAngle(partialTick) was the dimension's time of day (a fraction of a day; the
 * partial tick was not used) times 2 pi, the sun's angle in radians. 1.21.11 moved the sun's angle into the environment
 * attribute SUN_ANGLE, in degrees, driven by the dimension's timeline; where 1.21.10 asked level.getSunAngle(1.0F)
 * (DaylightDetectorBlock.updateSignalStrength) 1.21.11 asks environmentAttributes().getValue(SUN_ANGLE, pos) times
 * pi/180. With no position in hand, the dimension's own value of the attribute, the same conversion.
 *
 * Agrees with NeoForge's 1.21.10 -> 1.21.11 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.11/index.md, CC BY 4.0): "getSunAngle replaced by EnvironmentAttributes#SUN_ANGLE".
 */
public final class Level {

    private Level() {}

    public static float getSunAngle(net.minecraft.world.level.Level self, float partialTick) {
        return ((Float) self.environmentAttributes().getDimensionValue(EnvironmentAttributes.SUN_ANGLE)) * (float) (Math.PI / 180.0);
    }
}

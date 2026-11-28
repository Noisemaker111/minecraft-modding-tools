package mmt.standin.net.minecraft.world.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * 1.21.1's RelativeMovement, which 1.21.2 replaced with Relative, on 1.21.2.
 *
 * Read from both releases: 1.21.1's enum had X, Y, Z, Y_ROT, X_ROT on bits 0 to 4, ALL as those five and ROTATION as
 * X_ROT and Y_ROT, and pack/unpack turned a set into those bits and back. 1.21.2's Relative keeps the same five, by the
 * same names on the same bits, and adds DELTA_X, DELTA_Y, DELTA_Z and ROTATE_DELTA, so its ALL is nine values and a
 * relative delta keeps velocity, which no 1.21.1 teleport did. Relative is no answer for this enum's ALL, so this is the
 * old enum; the stand-ins that hand a set to 1.21.2's teleports turn each value into the Relative of the same name.
 *
 * Agrees with NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0): "RelativeMovement -> Relative, expanded to contain delta movement".
 */
public enum RelativeMovement {
    X(0),
    Y(1),
    Z(2),
    Y_ROT(3),
    X_ROT(4);

    public static final Set ALL = Set.of(values());
    public static final Set ROTATION = Set.of(X_ROT, Y_ROT);

    private final int bit;

    RelativeMovement(int bit) {
        this.bit = bit;
    }

    public static Set unpack(int bits) {
        Set relatives = EnumSet.noneOf(RelativeMovement.class);
        for (RelativeMovement relative : values()) {
            if ((bits & 1 << relative.bit) != 0) {
                relatives.add(relative);
            }
        }
        return relatives;
    }

    public static int pack(Set relatives) {
        int bits = 0;
        for (Object relative : relatives) {
            bits |= 1 << ((RelativeMovement) relative).bit;
        }
        return bits;
    }
}

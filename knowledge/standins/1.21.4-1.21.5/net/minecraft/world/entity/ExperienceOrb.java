package mmt.standin.net.minecraft.world.entity;

/**
 * 1.21.4's ExperienceOrb on 1.21.5.
 *
 * Read from both releases: 1.21.4 kept an orb's experience in the field value (read by getValue(), saved as "Value").
 * 1.21.5 keeps it in synched entity data (DATA_VALUE), read by getValue() and written by the private setValue(int), which
 * the orb's own constructor, merge and load use.
 *
 * Agrees with NeoForge's 1.21.4 -> 1.21.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.5/index.md, CC BY 4.0): "ExperienceOrb#value -> DATA_VALUE".
 */
public final class ExperienceOrb {

    private ExperienceOrb() {}

    public static int value(net.minecraft.world.entity.ExperienceOrb self) {
        return self.getValue();
    }

    public static void value(net.minecraft.world.entity.ExperienceOrb self, int value) {
        self.setValue(value);
    }
}

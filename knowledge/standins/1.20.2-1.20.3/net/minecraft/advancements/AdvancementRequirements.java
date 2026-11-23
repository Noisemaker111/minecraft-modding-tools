package mmt.standin.net.minecraft.advancements;

import java.util.List;

/**
 * 1.20.2's AdvancementRequirements on 1.20.3.
 *
 * Read from both releases: 1.20.2's record kept its requirements as String[][] (an array of alternatives per
 * requirement, each a criterion name), which requirements() gave. 1.20.3's record keeps the same lists as
 * List<List<String>>, in the same order, so the old accessor is the new one copied into arrays.
 */
public final class AdvancementRequirements {

    private AdvancementRequirements() {}

    public static String[][] requirements(net.minecraft.advancements.AdvancementRequirements self) {
        List requirements = self.requirements();
        String[][] out = new String[requirements.size()][];
        for (int i = 0; i < out.length; i++) {
            List alternatives = (List) requirements.get(i);
            out[i] = new String[alternatives.size()];
            for (int j = 0; j < out[i].length; j++) {
                out[i][j] = (String) alternatives.get(j);
            }
        }
        return out;
    }
}

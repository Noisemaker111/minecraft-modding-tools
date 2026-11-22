package mmt.standin.net.minecraft.world.entity.npc;

import net.minecraft.world.entity.npc.VillagerType;

/**
 * 1.21.4's VillagerData on 1.21.5.
 *
 * Read from both releases: 1.21.4's VillagerData held its VillagerType and getType() returned it. 1.21.5's is a record
 * holding the type as a registry Holder<VillagerType> (type()), whose value() is the same VillagerType.
 *
 * Agrees with NeoForge's 1.21.4 -> 1.21.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.5/index.md, CC BY 4.0): "VillagerData is now a record".
 */
public final class VillagerData {

    private VillagerData() {}

    public static VillagerType getType(net.minecraft.world.entity.npc.VillagerData self) {
        return (VillagerType) self.type().value();
    }
}

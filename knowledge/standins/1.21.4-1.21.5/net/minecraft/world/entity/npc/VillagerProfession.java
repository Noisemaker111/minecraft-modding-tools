package mmt.standin.net.minecraft.world.entity.npc;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.21.4's VillagerProfession constants and name() on 1.21.5.
 *
 * Read from both releases: 1.21.4's VillagerProfession was a record whose name() was the String it was registered under
 * ("armorer" for the vanilla ones, registered as minecraft:armorer), and its NONE, ARMORER ... WEAPONSMITH constants were
 * the registered values. 1.21.5's record names a profession with a Component instead, and its constants of the same names
 * are the ResourceKeys the same values are registered under; BuiltInRegistries.VILLAGER_PROFESSION.getValue(key) is the
 * value, and getKey(value) its id, whose path is the old name of a vanilla profession.
 *
 * Agrees with NeoForge's 1.21.4 -> 1.21.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.5/index.md, CC BY 4.0):
 * "VillagerProfession now takes in a Component for the name".
 */
public final class VillagerProfession {

    private VillagerProfession() {}

    public static String name(net.minecraft.world.entity.npc.VillagerProfession self) {
        ResourceLocation id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(self);
        if (id == null) {
            return self.name().getString();
        }
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }

    public static net.minecraft.world.entity.npc.VillagerProfession NONE() {
        return (net.minecraft.world.entity.npc.VillagerProfession) BuiltInRegistries.VILLAGER_PROFESSION.getValue(net.minecraft.world.entity.npc.VillagerProfession.NONE);
    }

    public static net.minecraft.world.entity.npc.VillagerProfession ARMORER() {
        return (net.minecraft.world.entity.npc.VillagerProfession) BuiltInRegistries.VILLAGER_PROFESSION.getValue(net.minecraft.world.entity.npc.VillagerProfession.ARMORER);
    }

    public static net.minecraft.world.entity.npc.VillagerProfession BUTCHER() {
        return (net.minecraft.world.entity.npc.VillagerProfession) BuiltInRegistries.VILLAGER_PROFESSION.getValue(net.minecraft.world.entity.npc.VillagerProfession.BUTCHER);
    }

    public static net.minecraft.world.entity.npc.VillagerProfession CARTOGRAPHER() {
        return (net.minecraft.world.entity.npc.VillagerProfession) BuiltInRegistries.VILLAGER_PROFESSION.getValue(net.minecraft.world.entity.npc.VillagerProfession.CARTOGRAPHER);
    }

    public static net.minecraft.world.entity.npc.VillagerProfession CLERIC() {
        return (net.minecraft.world.entity.npc.VillagerProfession) BuiltInRegistries.VILLAGER_PROFESSION.getValue(net.minecraft.world.entity.npc.VillagerProfession.CLERIC);
    }

    public static net.minecraft.world.entity.npc.VillagerProfession FARMER() {
        return (net.minecraft.world.entity.npc.VillagerProfession) BuiltInRegistries.VILLAGER_PROFESSION.getValue(net.minecraft.world.entity.npc.VillagerProfession.FARMER);
    }

    public static net.minecraft.world.entity.npc.VillagerProfession FISHERMAN() {
        return (net.minecraft.world.entity.npc.VillagerProfession) BuiltInRegistries.VILLAGER_PROFESSION.getValue(net.minecraft.world.entity.npc.VillagerProfession.FISHERMAN);
    }

    public static net.minecraft.world.entity.npc.VillagerProfession FLETCHER() {
        return (net.minecraft.world.entity.npc.VillagerProfession) BuiltInRegistries.VILLAGER_PROFESSION.getValue(net.minecraft.world.entity.npc.VillagerProfession.FLETCHER);
    }

    public static net.minecraft.world.entity.npc.VillagerProfession LEATHERWORKER() {
        return (net.minecraft.world.entity.npc.VillagerProfession) BuiltInRegistries.VILLAGER_PROFESSION.getValue(net.minecraft.world.entity.npc.VillagerProfession.LEATHERWORKER);
    }

    public static net.minecraft.world.entity.npc.VillagerProfession LIBRARIAN() {
        return (net.minecraft.world.entity.npc.VillagerProfession) BuiltInRegistries.VILLAGER_PROFESSION.getValue(net.minecraft.world.entity.npc.VillagerProfession.LIBRARIAN);
    }

    public static net.minecraft.world.entity.npc.VillagerProfession MASON() {
        return (net.minecraft.world.entity.npc.VillagerProfession) BuiltInRegistries.VILLAGER_PROFESSION.getValue(net.minecraft.world.entity.npc.VillagerProfession.MASON);
    }

    public static net.minecraft.world.entity.npc.VillagerProfession NITWIT() {
        return (net.minecraft.world.entity.npc.VillagerProfession) BuiltInRegistries.VILLAGER_PROFESSION.getValue(net.minecraft.world.entity.npc.VillagerProfession.NITWIT);
    }

    public static net.minecraft.world.entity.npc.VillagerProfession SHEPHERD() {
        return (net.minecraft.world.entity.npc.VillagerProfession) BuiltInRegistries.VILLAGER_PROFESSION.getValue(net.minecraft.world.entity.npc.VillagerProfession.SHEPHERD);
    }

    public static net.minecraft.world.entity.npc.VillagerProfession TOOLSMITH() {
        return (net.minecraft.world.entity.npc.VillagerProfession) BuiltInRegistries.VILLAGER_PROFESSION.getValue(net.minecraft.world.entity.npc.VillagerProfession.TOOLSMITH);
    }

    public static net.minecraft.world.entity.npc.VillagerProfession WEAPONSMITH() {
        return (net.minecraft.world.entity.npc.VillagerProfession) BuiltInRegistries.VILLAGER_PROFESSION.getValue(net.minecraft.world.entity.npc.VillagerProfession.WEAPONSMITH);
    }
}

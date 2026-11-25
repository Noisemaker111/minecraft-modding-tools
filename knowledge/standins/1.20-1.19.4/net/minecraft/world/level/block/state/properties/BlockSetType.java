package mmt.standin.net.minecraft.world.level.block.state.properties;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.SoundType;

/**
 * 1.20's BlockSetType.canOpenByHand and the constructor that sets it, on 1.19.4.
 *
 * Read from both releases: 1.20 gave each block set type a canOpenByHand flag (false for IRON and GOLD, true for every
 * other vanilla type) where 1.19.4's types have none: its doors and trapdoors open by hand unless their material is
 * METAL. A type made through 1.20's constructor is made with 1.19.4's (the same name and sounds) and its flag kept
 * beside it; a vanilla type answers as 1.20 sets it.
 */
public final class BlockSetType {

    private static final Map OPEN_BY_HAND = Collections.synchronizedMap(new IdentityHashMap());

    private BlockSetType() {
    }

    public static net.minecraft.world.level.block.state.properties.BlockSetType init(String name, boolean canOpenByHand, SoundType soundType, SoundEvent doorClose,
                                                                                   SoundEvent doorOpen, SoundEvent trapdoorClose, SoundEvent trapdoorOpen,
                                                                                   SoundEvent pressurePlateOff, SoundEvent pressurePlateOn, SoundEvent buttonOff,
                                                                                   SoundEvent buttonOn) {
        net.minecraft.world.level.block.state.properties.BlockSetType type = new net.minecraft.world.level.block.state.properties.BlockSetType(name, soundType, doorClose,
            doorOpen, trapdoorClose, trapdoorOpen, pressurePlateOff, pressurePlateOn, buttonOff, buttonOn);
        OPEN_BY_HAND.put(type, canOpenByHand);
        return type;
    }

    public static boolean canOpenByHand(net.minecraft.world.level.block.state.properties.BlockSetType self) {
        Object kept = OPEN_BY_HAND.get(self);
        if (kept != null) {
            return (Boolean) kept;
        }
        return self != net.minecraft.world.level.block.state.properties.BlockSetType.IRON && self != net.minecraft.world.level.block.state.properties.BlockSetType.GOLD;
    }
}

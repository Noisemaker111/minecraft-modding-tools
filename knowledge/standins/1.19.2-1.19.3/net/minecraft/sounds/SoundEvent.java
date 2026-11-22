package mmt.standin.net.minecraft.sounds;

import net.minecraft.resources.ResourceLocation;

/**
 * 1.19.2's SoundEvent(location) constructor on 1.19.3.
 *
 * Read from both releases: 1.19.2's SoundEvent(location) was a sound heard within a range that grows with its volume
 * (16 blocks at volume 1); SoundEvent(location, range) had a fixed range. 1.19.3 made the constructor private and makes
 * them with createVariableRangeEvent(location) and createFixedRangeEvent(location, range), the same two kinds.
 *
 * Agrees with Fabric's 1.19.3 post (https://fabricmc.net/2022/11/24/1193.html): "`SoundEvent` should now be constructed
 * using `SoundEvent.of`" (createVariableRangeEvent in Mojang's names).
 */
public final class SoundEvent {

    private SoundEvent() {}

    public static net.minecraft.sounds.SoundEvent init(ResourceLocation location) {
        return net.minecraft.sounds.SoundEvent.createVariableRangeEvent(location);
    }

    public static net.minecraft.sounds.SoundEvent init(ResourceLocation location, float range) {
        return net.minecraft.sounds.SoundEvent.createFixedRangeEvent(location, range);
    }
}

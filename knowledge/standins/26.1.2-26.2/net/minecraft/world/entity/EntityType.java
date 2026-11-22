package mmt.standin.net.minecraft.world.entity;

import java.util.Optional;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;

/**
 * 26.1.2's EntityType on 26.2.
 *
 * Read from both releases: 26.1.2's static EntityType.create(input, level, reason) made the entity the saved data names
 * for that spawn reason, with the type's spawn checks, and loaded it. 26.2 takes an EntitySpawnRequest(reason,
 * ignoreChecks) in its place, and its own create(level, reason) is create(level, new EntitySpawnRequest(reason, false)).
 * So the old method is the new one with that request.
 *
 * Agrees with NeoForge's 26.1.2 -> 26.2 primer (https://github.com/neoforged/.github/blob/main/primers/26.2/index.md, CC BY 4.0): "create now has an overload that takes in an EntitySpawnRequest instead of an EntitySpawnReason".
 */
public final class EntityType {

    private EntityType() {}

    public static Optional create(ValueInput input, Level level, EntitySpawnReason reason) {
        return net.minecraft.world.entity.EntityType.create(input, level, new EntitySpawnRequest(reason, false));
    }
}

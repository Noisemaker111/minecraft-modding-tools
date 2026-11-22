package mmt.standin.net.minecraft.world.entity;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueInput;
import org.slf4j.Logger;

/**
 * 1.21.5's EntityType on 1.21.6.
 *
 * Read from both releases: 1.21.5's EntityType.create(tag, level, reason) made the entity the tag's "id" names and
 * loaded it from the tag (warning and giving empty for an unknown id). 1.21.6's create(input, level, reason) does the same
 * from a ValueInput, which its own code makes from a tag with TagValueInput.create(problems, registries, tag).
 * 1.21.5's loadEntitiesRecursive(tags, level, reason) streamed the entities (with their passengers) loaded from the
 * compounds among tags; 1.21.6's takes the ValueInputList TagValueInput.create(problems, registries, compounds) makes.
 * The entities are loaded before the problems are reported, so the stream is of entities already loaded.
 *
 * Agrees with NeoForge's 1.21.5 -> 1.21.6 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.6/index.md, CC BY 4.0): "most methods that take in a CompoundTag now instead take in a ValueInput", TagValueInput "create".
 */
public final class EntityType {

    private static final Logger LOGGER = LogUtils.getLogger();

    private EntityType() {}

    public static Optional create(CompoundTag tag, Level level, EntitySpawnReason reason) {
        try (ProblemReporter.ScopedCollector problems = new ProblemReporter.ScopedCollector(LOGGER)) {
            return net.minecraft.world.entity.EntityType.create(TagValueInput.create(problems, level.registryAccess(), tag), level, reason);
        }
    }

    public static Stream loadEntitiesRecursive(List tags, Level level, EntitySpawnReason reason) {
        List<CompoundTag> compounds = new ArrayList<>();
        for (Object tag : tags) {
            if (tag instanceof CompoundTag compound) {
                compounds.add(compound);
            }
        }
        try (ProblemReporter.ScopedCollector problems = new ProblemReporter.ScopedCollector(LOGGER)) {
            return net.minecraft.world.entity.EntityType.loadEntitiesRecursive(TagValueInput.create(problems, level.registryAccess(), compounds), level, reason).toList().stream();
        }
    }
}

package mmt.standin.net.minecraft.world.entity;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.slf4j.Logger;

/**
 * 1.21.5's Entity on 1.21.6.
 *
 * Read from both releases: 1.21.5's saveWithoutId(tag) wrote the entity into tag and returned it, save(tag) did so with
 * the type id unless the entity was a passenger, load(tag) read it back, and canBeCollidedWith() said whether others
 * collide with it. 1.21.6 reads and writes through ValueInput and ValueOutput: its own code wraps a tag with
 * TagValueInput.create(problems, registries, tag) and collects one with TagValueOutput.createWithContext(problems,
 * registries) and buildResult(), reporting problems through a ProblemReporter.ScopedCollector at the entity's
 * problemPath() (NbtPredicate.getEntityTagToCompare, EntityDataAccessor.setData); canBeCollidedWith takes the entity
 * asking, which may be null.
 *
 * Agrees with NeoForge's 1.21.5 -> 1.21.6 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.6/index.md, CC BY 4.0): "most methods that take in a CompoundTag now instead take in a ValueInput for read/load or ValueOutput for
 * write/save", TagValueOutput "createWithContext", TagValueInput "create", and "canBeCollidedWith now takes the entity
 * its colliding with".
 */
public final class Entity {

    private static final Logger LOGGER = LogUtils.getLogger();

    private Entity() {}

    public static CompoundTag saveWithoutId(net.minecraft.world.entity.Entity self, CompoundTag tag) {
        try (ProblemReporter.ScopedCollector problems = new ProblemReporter.ScopedCollector(self.problemPath(), LOGGER)) {
            TagValueOutput output = TagValueOutput.createWithContext(problems, self.registryAccess());
            self.saveWithoutId(output);
            return tag.merge(output.buildResult());
        }
    }

    public static boolean save(net.minecraft.world.entity.Entity self, CompoundTag tag) {
        try (ProblemReporter.ScopedCollector problems = new ProblemReporter.ScopedCollector(self.problemPath(), LOGGER)) {
            TagValueOutput output = TagValueOutput.createWithContext(problems, self.registryAccess());
            boolean saved = self.save(output);
            tag.merge(output.buildResult());
            return saved;
        }
    }

    public static void load(net.minecraft.world.entity.Entity self, CompoundTag tag) {
        try (ProblemReporter.ScopedCollector problems = new ProblemReporter.ScopedCollector(self.problemPath(), LOGGER)) {
            self.load(TagValueInput.create(problems, self.registryAccess(), tag));
        }
    }

    public static boolean canBeCollidedWith(net.minecraft.world.entity.Entity self) {
        return self.canBeCollidedWith(null);
    }
}

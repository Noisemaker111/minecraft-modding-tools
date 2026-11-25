package mmt.standin.net.minecraft.world.level.storage.loot;

import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParam;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSet;

/**
 * 1.20's LootParams on 1.19.4: the class itself, which 1.19.4 does not have yet.
 *
 * Read from both releases: 1.20 split 1.19.4's LootContext into the parameters a loot roll is given (LootParams, built by
 * LootParams.Builder, 1.19.4's LootContext.Builder, and checked against a LootContextParamSet by create(set) as 1.19.4's
 * LootContext.Builder.create(set) checks them) and the context a table makes of them for each roll (new
 * LootContext.Builder(params).create(randomSequence) in LootTable.getRandomItems). Its getters are 1.19.4's LootContext's
 * own (getParameter is getParam; both throw NoSuchElementException for a missing one). 1.19.4 has no random sequences: a
 * table rolls with the level's random, as 1.20's does for a table without one.
 */
public class LootParams {

    final LootContext.Builder builder;
    final LootContextParamSet set;
    final LootContext context;

    public LootParams(LootContext.Builder builder, LootContextParamSet set) {
        this.builder = builder;
        this.set = set;
        this.context = builder.create(set);
    }

    public ServerLevel getLevel() {
        return context.getLevel();
    }

    public boolean hasParam(LootContextParam param) {
        return context.hasParam(param);
    }

    public Object getParameter(LootContextParam param) {
        return context.getParam(param);
    }

    public Object getOptionalParameter(LootContextParam param) {
        return context.getParamOrNull(param);
    }

    public Object getParamOrNull(LootContextParam param) {
        return context.getParamOrNull(param);
    }

    public void addDynamicDrops(ResourceLocation id, Consumer output) {
        context.addDynamicDrops(id, output);
    }

    public float getLuck() {
        return context.getLuck();
    }

    /** A fresh context for one roll, as 1.20's tables make one from the parameters for each. */
    public LootContext roll() {
        return builder.create(set);
    }

    /**
     * A context for one roll from a seed, as 1.20's LootTable.fill makes one (withOptionalRandomSeed(seed)). 1.19.4's
     * builder keeps the random it is given, so the builder these parameters came from rolls with it from then on.
     */
    public LootContext roll(long seed) {
        return builder.withOptionalRandomSeed(seed).create(set);
    }

    /** 1.20's LootParams.DynamicDrop: 1.19.4's LootContext.DynamicDrop without the context it never needed. */
    public interface DynamicDrop {
        void add(Consumer output);
    }
}

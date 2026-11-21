package mmt.standin.net.minecraft.world.level;

import net.minecraft.world.flag.FeatureFlags;

/**
 * 1.21.1's GameRules on 1.21.2.
 *
 * Read from both releases: 1.21.1's static visitGameRuleTypes(visitor) visited every registered rule type
 * (GAME_RULE_TYPES). 1.21.2 made it an instance method that visits only the types whose required features the instance's
 * feature set holds. Visiting through rules made for every feature flag visits every type, as 1.21.1 did.
 *
 * NeoForge's 1.21.1 -> 1.21.2 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md, CC BY 4.0) does not mention visitGameRuleTypes; this is from the game's code alone.
 */
public final class GameRules {

    private GameRules() {}

    public static void visitGameRuleTypes(net.minecraft.world.level.GameRules.GameRuleTypeVisitor visitor) {
        new net.minecraft.world.level.GameRules(FeatureFlags.REGISTRY.allFlags()).visitGameRuleTypes(visitor);
    }
}

package mmt.standin.com.mojang.blaze3d.platform;

import com.mojang.blaze3d.platform.CompareOp;

/**
 * 1.21.11's DepthTestFunction, which 26.1 replaced with CompareOp.
 *
 * Read from both releases: 1.21.11's DepthTestFunction named the depth test a pipeline ran (NO_DEPTH_TEST,
 * EQUAL_DEPTH_TEST, LEQUAL_DEPTH_TEST, LESS_DEPTH_TEST, GREATER_DEPTH_TEST; GlConst.toGl made each GL_ALWAYS, GL_EQUAL,
 * GL_LEQUAL, GL_LESS, GL_GREATER). 26.1 names it with the general CompareOp inside a pipeline's DepthStencilState
 * (GlConst.toGl(CompareOp)). Each old function is the CompareOp that makes the same GL test (toCompareOp).
 *
 * Agrees with NeoForge's 1.21.11 -> 26.1 primer (https://github.com/neoforged/.github/blob/main/primers/26.1/index.md, CC BY 4.0): "DepthTestFunction has been replaced with a more generic CompareOp ... Each function has an simple equivalent, with NO_DEPTH_TEST replaced by CompareOp#ALWAYS_PASS".
 */
public enum DepthTestFunction {
    NO_DEPTH_TEST,
    EQUAL_DEPTH_TEST,
    LEQUAL_DEPTH_TEST,
    LESS_DEPTH_TEST,
    GREATER_DEPTH_TEST;

    public static CompareOp toCompareOp(DepthTestFunction function) {
        switch (function) {
            case NO_DEPTH_TEST:
                return CompareOp.ALWAYS_PASS;
            case EQUAL_DEPTH_TEST:
                return CompareOp.EQUAL;
            case LESS_DEPTH_TEST:
                return CompareOp.LESS_THAN;
            case GREATER_DEPTH_TEST:
                return CompareOp.GREATER_THAN;
            default:
                return CompareOp.LESS_THAN_OR_EQUAL;
        }
    }
}

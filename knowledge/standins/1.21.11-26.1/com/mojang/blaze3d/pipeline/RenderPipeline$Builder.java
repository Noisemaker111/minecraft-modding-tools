package mmt.standin.com.mojang.blaze3d.pipeline;

import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import java.util.Optional;
import mmt.standin.com.mojang.blaze3d.platform.DepthTestFunction;

/**
 * 1.21.11's RenderPipeline.Builder on 26.1.
 *
 * Read from both releases: 1.21.11's builder kept the depth test, depth write, blend and color writes apart
 * (withDepthTestFunction, withDepthWrite, withoutBlend, withColorWrite(color) setting color and alpha, withColorWrite(color,
 * alpha)), and build() filled what was not set with LEQUAL_DEPTH_TEST, depth write on, no blend, every color written. 26.1
 * keeps them in two records, DepthStencilState(depthTest, writeDepth, bias scale, bias constant) and
 * ColorTargetState(blendFunction, writeMask), each set whole (withDepthStencilState, withColorTargetState); a builder shows
 * what it holds through buildSnippet(). So each old call sets its own part of the record the builder holds, starting from
 * 1.21.11's defaults (DepthStencilState.DEFAULT is LESS_THAN_OR_EQUAL with depth write; ColorTargetState.DEFAULT is no
 * blend, WRITE_ALL) when it holds none.
 *
 * Agrees with NeoForge's 1.21.11 -> 26.1 primer (https://github.com/neoforged/.github/blob/main/primers/26.1/index.md, CC BY 4.0): "withDepthTestFunction, withDepthWrite, withDepthBias -> withDepthStencilState, not one-to-one" and "withBlend, withColorWrite, withColorLogic -> withColorTargetState, not one-to-one".
 */
public final class RenderPipeline$Builder {

    private RenderPipeline$Builder() {}

    private static DepthStencilState depth(RenderPipeline.Builder self) {
        return (DepthStencilState) self.buildSnippet().depthStencilState().orElse(DepthStencilState.DEFAULT);
    }

    private static ColorTargetState color(RenderPipeline.Builder self) {
        return (ColorTargetState) self.buildSnippet().colorTargetState().orElse(ColorTargetState.DEFAULT);
    }

    public static RenderPipeline.Builder withDepthTestFunction(RenderPipeline.Builder self, DepthTestFunction function) {
        DepthStencilState was = depth(self);
        return self.withDepthStencilState(new DepthStencilState(DepthTestFunction.toCompareOp(function), was.writeDepth(), was.depthBiasScaleFactor(), was.depthBiasConstant()));
    }

    public static RenderPipeline.Builder withDepthWrite(RenderPipeline.Builder self, boolean write) {
        DepthStencilState was = depth(self);
        return self.withDepthStencilState(new DepthStencilState(was.depthTest(), write, was.depthBiasScaleFactor(), was.depthBiasConstant()));
    }

    public static RenderPipeline.Builder withoutBlend(RenderPipeline.Builder self) {
        return self.withColorTargetState(new ColorTargetState(Optional.empty(), color(self).writeMask()));
    }

    public static RenderPipeline.Builder withColorWrite(RenderPipeline.Builder self, boolean write) {
        return withColorWrite(self, write, write);
    }

    public static RenderPipeline.Builder withColorWrite(RenderPipeline.Builder self, boolean color, boolean alpha) {
        int mask = (color ? ColorTargetState.WRITE_COLOR : 0) | (alpha ? ColorTargetState.WRITE_ALPHA : 0);
        return self.withColorTargetState(new ColorTargetState(color(self).blendFunction(), mask));
    }
}

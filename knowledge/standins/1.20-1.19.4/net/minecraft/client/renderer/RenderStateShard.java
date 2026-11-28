package mmt.standin.net.minecraft.client.renderer;

/**
 * 1.20's RenderStateShard.GREATER_DEPTH_TEST on 1.19.4.
 *
 * Read from both releases: 1.20 added it beside NO_DEPTH_TEST, EQUAL_DEPTH_TEST and LEQUAL_DEPTH_TEST as
 * new DepthTestStateShard(">", 516) (GL_GREATER); 1.19.4 has the same DepthTestStateShard and constructor.
 */
public final class RenderStateShard {

    private static final net.minecraft.client.renderer.RenderStateShard.DepthTestStateShard GREATER_DEPTH_TEST = new net.minecraft.client.renderer.RenderStateShard.DepthTestStateShard(">", 516);

    private RenderStateShard() {
    }

    public static net.minecraft.client.renderer.RenderStateShard.DepthTestStateShard GREATER_DEPTH_TEST() {
        return GREATER_DEPTH_TEST;
    }
}

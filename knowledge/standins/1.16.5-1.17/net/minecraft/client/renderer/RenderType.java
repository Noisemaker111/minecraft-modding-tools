package mmt.standin.net.minecraft.client.renderer;

/**
 * 1.16.5's RenderType.mode() on 1.17.
 *
 * Read from both releases: 1.16.5's RenderType kept the OpenGL primitive it drew (GL_QUADS, GL_LINES, ...) as an int
 * and mode() returned it. 1.17 keeps a VertexFormat.Mode, whose asGLMode is the OpenGL primitive it draws with (1.17
 * draws QUADS and LINES as GL_TRIANGLES, so those answer 4). This returns that primitive.
 *
 * Fabric's 1.17 post for developers (https://fabricmc.net/2021/05/27/117-for-developers.html) covers Java 16, Fabric API and
 * Yarn, not this; this is from the game's code alone.
 */
public final class RenderType {

    private RenderType() {}

    public static int mode(net.minecraft.client.renderer.RenderType self) {
        return self.mode().asGLMode;
    }
}

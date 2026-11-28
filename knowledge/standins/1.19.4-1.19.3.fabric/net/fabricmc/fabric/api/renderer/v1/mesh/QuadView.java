package mmt.standin.net.fabricmc.fabric.api.renderer.v1.mesh;

/**
 * Fabric API 0.87's QuadView.u(vertex) and v(vertex) on Fabric API 0.76, for a port back from 1.19.4 to 1.19.3.
 *
 * Read from both releases' Fabric API (0.87.2+1.19.4 and 0.76.1+1.19.3, fabric-renderer-api-v1): 1.19.4's Fabric
 * API dropped the sprite index from the quad and material calls (only sprite 0 was ever used) and named the old forms
 * anew; its own default bodies call the old forms with sprite index 0, which 0.76.1 has. u(vertex) is spriteU(vertex, 0) and v(vertex) spriteV(vertex, 0).
 */
public final class QuadView {

    private QuadView() {
    }

    public static float u(net.fabricmc.fabric.api.renderer.v1.mesh.QuadView self, int vertex) {
        return self.spriteU(vertex, 0);
    }

    public static float v(net.fabricmc.fabric.api.renderer.v1.mesh.QuadView self, int vertex) {
        return self.spriteV(vertex, 0);
    }
}

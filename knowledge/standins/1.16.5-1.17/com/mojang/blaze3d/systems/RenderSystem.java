package mmt.standin.com.mojang.blaze3d.systems;

import com.mojang.math.Quaternion;
import com.mojang.math.Vector3f;

/**
 * 1.16.5's fixed-function RenderSystem calls on 1.17.
 *
 * Read from both releases: 1.16.5 drew through OpenGL's fixed-function pipeline, so translatef, translated, scalef,
 * scaled and rotatef changed OpenGL's model-view matrix at once, and alphaFunc, shadeModel, enable/disableLighting and
 * enable/disableColorMaterial set fixed-function state. 1.17 draws only through shaders: the model-view matrix is
 * RenderSystem.getModelViewStack(), sent to the shaders by applyModelViewMatrix(), and the rest is the shaders' own work
 * (the alpha cut-off, smooth shading and the two item lights are written into them). 1.17's own code shows both: its
 * ItemRenderer.renderGuiItem, which 1.16.5 wrote with pushMatrix, translatef and scalef after enableAlphaTest and
 * defaultAlphaFunc, translates and scales getModelViewStack(), calls applyModelViewMatrix(), and sets no alpha or
 * lighting state. So the matrix calls change the model-view stack and apply it, and the state calls do nothing.
 *
 * Fabric's 1.17 post for developers (https://fabricmc.net/2021/05/27/117-for-developers.html) covers Java 16, Fabric API and
 * Yarn, not this; this is from the game's code alone.
 */
public final class RenderSystem {

    private RenderSystem() {}

    static void apply() {
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
    }

    public static void translatef(float x, float y, float z) {
        com.mojang.blaze3d.systems.RenderSystem.getModelViewStack().translate(x, y, z);
        apply();
    }

    public static void translated(double x, double y, double z) {
        com.mojang.blaze3d.systems.RenderSystem.getModelViewStack().translate(x, y, z);
        apply();
    }

    public static void scalef(float x, float y, float z) {
        com.mojang.blaze3d.systems.RenderSystem.getModelViewStack().scale(x, y, z);
        apply();
    }

    public static void scaled(double x, double y, double z) {
        com.mojang.blaze3d.systems.RenderSystem.getModelViewStack().scale((float) x, (float) y, (float) z);
        apply();
    }

    public static void rotatef(float degrees, float x, float y, float z) {
        Vector3f axis = new Vector3f(x, y, z);
        axis.normalize();
        com.mojang.blaze3d.systems.RenderSystem.getModelViewStack().mulPose(new Quaternion(axis, degrees, true));
        apply();
    }

    public static void alphaFunc(int func, float ref) {
    }

    public static void shadeModel(int mode) {
    }

    public static void enableLighting() {
    }

    public static void disableLighting() {
    }

    public static void enableColorMaterial() {
    }

    public static void disableColorMaterial() {
    }
}

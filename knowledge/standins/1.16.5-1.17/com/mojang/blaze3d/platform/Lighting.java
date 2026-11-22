package mmt.standin.com.mojang.blaze3d.platform;

/**
 * 1.16.5's Lighting.turnOff() on 1.17.
 *
 * Read from both releases: 1.16.5's turnOff() was RenderSystem.disableLighting() and disableColorMaterial(), turning the
 * fixed-function item lights off. 1.17 has no fixed-function lights: shaders that light take the two light directions
 * Lighting.setupFor3DItems / setupForFlatItems / setupLevel set, and shaders that do not ignore them, so 1.17's own code
 * calls nothing where 1.16.5 called turnOff(). This does nothing.
 *
 * Fabric's 1.17 post for developers (https://fabricmc.net/2021/05/27/117-for-developers.html) covers Java 16, Fabric API and
 * Yarn, not this; this is from the game's code alone.
 */
public final class Lighting {

    private Lighting() {}

    public static void turnOff() {
    }
}

package mmt.standin.com.mojang.math;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * 1.19.2's com.mojang.math.Vector3f on 1.19.3's JOML Vector3f.
 *
 * Read from both releases: 1.19.3 dropped com.mojang.math.Vector3f for org.joml.Vector3f, which the port carries the
 * type to. These are the old members JOML has no same-named answer for, with 1.19.2's bodies: the axis constants XP ...
 * ZN and ZERO (shared mutable vectors then; a fresh one each read here, the same value); rotation(radians) and
 * rotationDegrees(degrees), the Quaternion(axis, angle, degrees) of sin(angle / 2) times the axis as given (not
 * normalised) and cos(angle / 2); transform(matrix3f), the matrix times the vector, which JOML's mul(Matrix3fc) is;
 * copy(); normalize(), false and unchanged below a squared length of 1.0E-5; and the constructor from a Vec3.
 *
 * Agrees with Fabric's 1.19.3 post (https://fabricmc.net/2022/11/24/1193.html): "Mojang has started using the JOML
 * library for rendering-related math. `Vec3f` is now replaced with JOML `Vector3f`."
 */
public final class Vector3f {

    private Vector3f() {}

    public static org.joml.Vector3f XN() {
        return new org.joml.Vector3f(-1.0F, 0.0F, 0.0F);
    }

    public static org.joml.Vector3f XP() {
        return new org.joml.Vector3f(1.0F, 0.0F, 0.0F);
    }

    public static org.joml.Vector3f YN() {
        return new org.joml.Vector3f(0.0F, -1.0F, 0.0F);
    }

    public static org.joml.Vector3f YP() {
        return new org.joml.Vector3f(0.0F, 1.0F, 0.0F);
    }

    public static org.joml.Vector3f ZN() {
        return new org.joml.Vector3f(0.0F, 0.0F, -1.0F);
    }

    public static org.joml.Vector3f ZP() {
        return new org.joml.Vector3f(0.0F, 0.0F, 1.0F);
    }

    public static org.joml.Vector3f ZERO() {
        return new org.joml.Vector3f(0.0F, 0.0F, 0.0F);
    }

    public static org.joml.Vector3f init(Vec3 vec) {
        return new org.joml.Vector3f((float) vec.x, (float) vec.y, (float) vec.z);
    }

    public static Quaternionf rotation(org.joml.Vector3f self, float radians) {
        return Quaternion.init(self, radians, false);
    }

    public static Quaternionf rotationDegrees(org.joml.Vector3f self, float degrees) {
        return Quaternion.init(self, degrees, true);
    }

    public static void transform(org.joml.Vector3f self, org.joml.Matrix3f matrix) {
        self.mul(matrix);
    }

    public static org.joml.Vector3f copy(org.joml.Vector3f self) {
        return new org.joml.Vector3f(self);
    }

    public static boolean normalize(org.joml.Vector3f self) {
        float lengthSquared = self.x * self.x + self.y * self.y + self.z * self.z;
        if (lengthSquared < 1.0E-5) {
            return false;
        }
        self.normalize();
        return true;
    }
}

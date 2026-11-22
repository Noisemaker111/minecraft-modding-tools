package mmt.standin.com.mojang.math;

import java.nio.FloatBuffer;
import org.joml.Quaternionf;

/**
 * 1.19.2's com.mojang.math.Matrix4f on 1.19.3's JOML Matrix4f.
 *
 * Read from both releases: 1.19.3 dropped com.mojang.math.Matrix4f for org.joml.Matrix4f, which the port carries the
 * type to. The old fields were named row then column (m01 is row 0, column 1); JOML's are column then row, so old mRC is
 * JOML's mCR. These are the old members JOML has no same-named answer for, with 1.19.2's meaning: the sixteen fields;
 * setIdentity(); copy(); load(other); multiply(other) and multiply(quaternion), this times the other (JOML's mul and
 * rotate); multiplyWithTranslation(x, y, z), this times a translation (JOML's translate); invert(), false and unchanged
 * when the determinant is within 1.0E-6 of zero (1.19.2 left the adjugate in place there); store(buffer), the sixteen
 * values column by column at the buffer's indices 0 to 15 (JOML's get(0, buffer)); and createScaleMatrix,
 * createTranslateMatrix and perspective(fovDegrees, aspect, near, far), whose matrix is JOML's setPerspective of the
 * field of view in radians.
 *
 * Agrees with Fabric's 1.19.3 post (https://fabricmc.net/2022/11/24/1193.html): "Mojang has started using the JOML
 * library for rendering-related math. `Vec3f` is now replaced with JOML `Vector3f`."
 */
public final class Matrix4f {

    private Matrix4f() {}

    public static float m00(org.joml.Matrix4f self) {
        return self.m00();
    }

    public static void m00(org.joml.Matrix4f self, float value) {
        self.m00(value);
    }

    public static float m01(org.joml.Matrix4f self) {
        return self.m10();
    }

    public static void m01(org.joml.Matrix4f self, float value) {
        self.m10(value);
    }

    public static float m02(org.joml.Matrix4f self) {
        return self.m20();
    }

    public static void m02(org.joml.Matrix4f self, float value) {
        self.m20(value);
    }

    public static float m03(org.joml.Matrix4f self) {
        return self.m30();
    }

    public static void m03(org.joml.Matrix4f self, float value) {
        self.m30(value);
    }

    public static float m10(org.joml.Matrix4f self) {
        return self.m01();
    }

    public static void m10(org.joml.Matrix4f self, float value) {
        self.m01(value);
    }

    public static float m11(org.joml.Matrix4f self) {
        return self.m11();
    }

    public static void m11(org.joml.Matrix4f self, float value) {
        self.m11(value);
    }

    public static float m12(org.joml.Matrix4f self) {
        return self.m21();
    }

    public static void m12(org.joml.Matrix4f self, float value) {
        self.m21(value);
    }

    public static float m13(org.joml.Matrix4f self) {
        return self.m31();
    }

    public static void m13(org.joml.Matrix4f self, float value) {
        self.m31(value);
    }

    public static float m20(org.joml.Matrix4f self) {
        return self.m02();
    }

    public static void m20(org.joml.Matrix4f self, float value) {
        self.m02(value);
    }

    public static float m21(org.joml.Matrix4f self) {
        return self.m12();
    }

    public static void m21(org.joml.Matrix4f self, float value) {
        self.m12(value);
    }

    public static float m22(org.joml.Matrix4f self) {
        return self.m22();
    }

    public static void m22(org.joml.Matrix4f self, float value) {
        self.m22(value);
    }

    public static float m23(org.joml.Matrix4f self) {
        return self.m32();
    }

    public static void m23(org.joml.Matrix4f self, float value) {
        self.m32(value);
    }

    public static float m30(org.joml.Matrix4f self) {
        return self.m03();
    }

    public static void m30(org.joml.Matrix4f self, float value) {
        self.m03(value);
    }

    public static float m31(org.joml.Matrix4f self) {
        return self.m13();
    }

    public static void m31(org.joml.Matrix4f self, float value) {
        self.m13(value);
    }

    public static float m32(org.joml.Matrix4f self) {
        return self.m23();
    }

    public static void m32(org.joml.Matrix4f self, float value) {
        self.m23(value);
    }

    public static float m33(org.joml.Matrix4f self) {
        return self.m33();
    }

    public static void m33(org.joml.Matrix4f self, float value) {
        self.m33(value);
    }

    public static void setIdentity(org.joml.Matrix4f self) {
        self.identity();
    }

    public static org.joml.Matrix4f copy(org.joml.Matrix4f self) {
        return new org.joml.Matrix4f(self);
    }

    public static void load(org.joml.Matrix4f self, org.joml.Matrix4f other) {
        self.set(other);
    }

    public static void multiply(org.joml.Matrix4f self, org.joml.Matrix4f other) {
        self.mul(other);
    }

    public static void multiply(org.joml.Matrix4f self, Quaternionf rotation) {
        self.rotate(rotation);
    }

    public static void multiplyWithTranslation(org.joml.Matrix4f self, float x, float y, float z) {
        self.translate(x, y, z);
    }

    public static boolean invert(org.joml.Matrix4f self) {
        if (Math.abs(self.determinant()) <= 1.0E-6F) {
            return false;
        }
        self.invert();
        return true;
    }

    public static void store(org.joml.Matrix4f self, FloatBuffer buffer) {
        self.get(0, buffer);
    }

    public static org.joml.Matrix4f createScaleMatrix(float x, float y, float z) {
        return new org.joml.Matrix4f().scaling(x, y, z);
    }

    public static org.joml.Matrix4f createTranslateMatrix(float x, float y, float z) {
        return new org.joml.Matrix4f().translation(x, y, z);
    }

    public static org.joml.Matrix4f perspective(double fovDegrees, float aspect, float near, float far) {
        return new org.joml.Matrix4f().setPerspective((float) (fovDegrees * (Math.PI / 180.0)), aspect, near, far);
    }
}

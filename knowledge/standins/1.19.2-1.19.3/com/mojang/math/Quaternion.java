package mmt.standin.com.mojang.math;

import org.joml.Quaternionf;

/**
 * 1.19.2's com.mojang.math.Quaternion on 1.19.3's JOML Quaternionf.
 *
 * Read from both releases: 1.19.3 dropped com.mojang.math.Quaternion for org.joml.Quaternionf, which the port carries
 * the type to; i, j, k and r are JOML's x, y, z and w, and both multiply as this times the other. These are the old
 * members JOML has no same-named answer for, with 1.19.2's bodies: i() ... r(); Quaternion(axis, angle, degrees), sin(angle
 * / 2) times the axis as given and cos(angle / 2); Quaternion(x, y, z, degrees) from those Euler angles; and fromXYZ(x, y,
 * z), the rotations about X, then Y, then Z multiplied in that order.
 *
 * Agrees with Fabric's 1.19.3 post (https://fabricmc.net/2022/11/24/1193.html): "Mojang has started using the JOML
 * library for rendering-related math. `Vec3f` is now replaced with JOML `Vector3f`."
 */
public final class Quaternion {

    private Quaternion() {}

    public static float i(Quaternionf self) {
        return self.x;
    }

    public static float j(Quaternionf self) {
        return self.y;
    }

    public static float k(Quaternionf self) {
        return self.z;
    }

    public static float r(Quaternionf self) {
        return self.w;
    }

    public static Quaternionf init(org.joml.Vector3f axis, float angle, boolean degrees) {
        if (degrees) {
            angle *= (float) (Math.PI / 180.0);
        }
        float sin = (float) Math.sin(angle / 2.0F);
        return new Quaternionf(axis.x * sin, axis.y * sin, axis.z * sin, (float) Math.cos(angle / 2.0F));
    }

    public static Quaternionf init(float x, float y, float z, boolean degrees) {
        if (degrees) {
            x *= (float) (Math.PI / 180.0);
            y *= (float) (Math.PI / 180.0);
            z *= (float) (Math.PI / 180.0);
        }
        float sx = (float) Math.sin(0.5F * x);
        float cx = (float) Math.cos(0.5F * x);
        float sy = (float) Math.sin(0.5F * y);
        float cy = (float) Math.cos(0.5F * y);
        float sz = (float) Math.sin(0.5F * z);
        float cz = (float) Math.cos(0.5F * z);
        return new Quaternionf(sx * cy * cz + cx * sy * sz, cx * sy * cz - sx * cy * sz, sx * sy * cz + cx * cy * sz, cx * cy * cz - sx * sy * sz);
    }

    public static Quaternionf fromXYZ(float x, float y, float z) {
        Quaternionf q = new Quaternionf();
        q.mul(new Quaternionf((float) Math.sin(x / 2.0F), 0.0F, 0.0F, (float) Math.cos(x / 2.0F)));
        q.mul(new Quaternionf(0.0F, (float) Math.sin(y / 2.0F), 0.0F, (float) Math.cos(y / 2.0F)));
        q.mul(new Quaternionf(0.0F, 0.0F, (float) Math.sin(z / 2.0F), (float) Math.cos(z / 2.0F)));
        return q;
    }
}

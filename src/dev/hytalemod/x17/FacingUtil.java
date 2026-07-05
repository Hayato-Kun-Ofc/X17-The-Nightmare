package dev.hytalemod.x17;

import org.joml.Vector3d;
import com.hypixel.hytale.math.vector.Rotation3f;

/**
 * FacingUtil - v0.3.6
 *
 * Shared yaw-computation helper for the X-17 and X-18 AI systems.
 *
 */
public final class FacingUtil {

    private FacingUtil() {
    }

    /**
     * Compute the yaw that makes an entity at {@code pos} face {@code target}.
     *
     * Convention: yaw = atan2(target.x - pos.x, target.z - pos.z) + orientOffset.
     *
     * @param pos          the entity's current position
     * @param target       the position to face
     * @param orientOffset the model's default forward offset in radians.
     *                     Use 0.0 if the model faces +Z by default.
     *                     Use Math.PI if the model faces -Z by default.
     */
    public static float yawToFace(Vector3d pos, Vector3d target, double orientOffset) {
        return (float) (Math.atan2(target.x() - pos.x(), target.z() - pos.z())
                + orientOffset);
    }

    /**
     * Convenience: build a Rotation3f with yaw facing target, zero pitch/roll.
     */
    public static Rotation3f rotationToFace(Vector3d pos, Vector3d target,
            double orientOffset) {
        return new Rotation3f(0f, yawToFace(pos, target, orientOffset), 0f);
    }
}

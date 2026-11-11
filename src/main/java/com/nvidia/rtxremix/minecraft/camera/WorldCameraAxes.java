/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * The world camera's axes. Minecraft stores roll in the camera quaternion, and
 * Do a Barrel Roll writes it there. Reading only yaw and pitch leaves the view level.
 * Damage tilt and view bobbing are not applied here. They arrive in the pose
 * {@code renderLevel} captures after {@code bobHurt}.
 */
public final class WorldCameraAxes {
    /** Same degree-to-radian factor {@code Camera.setRotation} and {@code Axis.rotateDegrees} use. */
    private static final float DEGREES_TO_RADIANS = 0.017453292f;

    private WorldCameraAxes() {}

    public record Axes(
            float forwardX, float forwardY, float forwardZ,
            float rightX, float rightY, float rightZ,
            float upX, float upY, float upZ) {}

    /** Axes from the vectors {@code Camera.setRotation} already rotated, roll included. */
    public static Axes fromCamera(Vector3fc forward, Vector3fc left, Vector3fc up) {
        return new Axes(
                forward.x(), forward.y(), forward.z(),
                -left.x(), -left.y(), -left.z(),
                up.x(), up.y(), up.z());
    }

    /**
     * The basis {@code Camera.setRotation} builds: yaw in degrees, pitch in degrees,
     * roll in radians as the third argument of {@code rotationYXZ}.
     */
    public static Axes fromYawPitchRoll(float yawDeg, float pitchDeg, float rollRad) {
        Quaternionf rotation = new Quaternionf().rotationYXZ(
                (float) Math.PI - yawDeg * DEGREES_TO_RADIANS,
                -pitchDeg * DEGREES_TO_RADIANS,
                rollRad);
        Vector3f forward = new Vector3f(0.0f, 0.0f, -1.0f).rotate(rotation);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f).rotate(rotation);
        Vector3f left = new Vector3f(-1.0f, 0.0f, 0.0f).rotate(rotation);
        return fromCamera(forward, left, up);
    }

    /** Yaw and pitch only, the view Remix used before it read the camera roll. */
    public static Axes level(float yawDeg, float pitchDeg) {
        float yawRad = (float) Math.toRadians(-yawDeg);
        float pitchRad = (float) Math.toRadians(pitchDeg);
        float forwardX = (float) (Math.sin(yawRad) * Math.cos(pitchRad));
        float forwardY = (float) (-Math.sin(pitchRad));
        float forwardZ = (float) (Math.cos(yawRad) * Math.cos(pitchRad));
        float rightX = (float) (-Math.cos(yawRad));
        float rightZ = (float) (Math.sin(yawRad));
        float upX = -rightZ * forwardY;
        float upY = rightZ * forwardX - rightX * forwardZ;
        float upZ = rightX * forwardY;
        return new Axes(forwardX, forwardY, forwardZ, rightX, 0.0f, rightZ, upX, upY, upZ);
    }
}

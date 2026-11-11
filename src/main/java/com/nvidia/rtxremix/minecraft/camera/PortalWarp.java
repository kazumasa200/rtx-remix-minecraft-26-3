/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The nether-portal and nausea warp {@code GameRenderer.renderLevel} applies
 * after the camera quaternion. Remix decomposes the projection for fov, near,
 * far and handedness, so the stretch stays on the view and the projection
 * stays a plain left-handed frustum. The clip product is still
 * {@code P * F * warp * F * view}.
 *
 * <p>The runtime builds a left-handed perspective (forward is +Z). Vanilla
 * rotates in an OpenGL view (forward is -Z). {@link #apply} conjugates the
 * vanilla rotate / scale / rotate by a Z flip so the same stretch lands in
 * Remix's view.
 */
public final class PortalWarp {
    /** Same degree-to-radian factor {@code GameRenderer.renderLevel} multiplies into the angle. */
    private static final float DEGREES_TO_RADIANS = 0.017453292f;
    /** {@code (0, √2/2, √2/2)}, the axis vanilla tumbles the stretch around. */
    private static final Vector3f VANILLA_AXIS = new Vector3f(
            0.0f, Mth.SQRT_OF_TWO / 2.0f, Mth.SQRT_OF_TWO / 2.0f);

    private PortalWarp() {}

    /**
     * {@code max(portal, nausea) * screenEffectScale²}. Zero when the distortion
     * slider is zero, which is also when vanilla skips the warp.
     */
    public static float intensity(float portalEffectIntensity, float nauseaEffectIntensity, float screenEffectScale) {
        return Math.max(portalEffectIntensity, nauseaEffectIntensity) * screenEffectScale * screenEffectScale;
    }

    /**
     * Left-handed perspective matching {@code SetupByHalfFovy} with
     * {@code PROJ_LEFT_HANDED}: depth −1..1, symmetric frustum, column 2 negated.
     * {@link Matrix4f#get(float[])} writes column 0 first, which is how
     * {@code remixapi_CameraInfo.projection} is read.
     */
    public static Matrix4f projection(float fovYDegrees, float aspect, float nearPlane, float farPlane) {
        float halfFov = (float) Math.toRadians(fovYDegrees) * 0.5f;
        float tanHalf = (float) Math.tan(halfFov);
        Matrix4f projection = new Matrix4f();
        // JOML names an element by column then row. The runtime's a23 (row 2,
        // column 3) is therefore m32 here, and its a32 is m23.
        projection.m00(1.0f / (aspect * tanHalf));
        projection.m11(1.0f / tanHalf);
        projection.m22((farPlane + nearPlane) / (farPlane - nearPlane));
        projection.m32(2.0f * farPlane * nearPlane / (nearPlane - farPlane));
        projection.m23(1.0f);
        projection.m33(0.0f);
        return projection;
    }

    /**
     * World-to-view matching the runtime: view-to-world columns are normalized
     * right, up, forward and the scene-space position, then inverted.
     *
     * <p>The 16-float constructor stores its first four arguments as column 0
     * ({@code m00, m01, m02, m03}). Remix reads that same column. Writing the
     * basis as rows puts the camera position into {@code w} and turns a pitched
     * view into its transpose.
     */
    public static Matrix4f worldToView(WorldCameraAxes.Axes axes, float sceneX, float sceneY, float sceneZ) {
        Vector3f right = unit(axes.rightX(), axes.rightY(), axes.rightZ());
        Vector3f up = unit(axes.upX(), axes.upY(), axes.upZ());
        Vector3f forward = unit(axes.forwardX(), axes.forwardY(), axes.forwardZ());
        return new Matrix4f(
                right.x, right.y, right.z, 0.0f,
                up.x, up.y, up.z, 0.0f,
                forward.x, forward.y, forward.z, 0.0f,
                sceneX, sceneY, sceneZ, 1.0f).invert();
    }

    /**
     * Premultiplies vanilla's {@code rotate}, {@code scale(1/skew, 1, 1)},
     * {@code rotate} back onto {@code worldToView}, conjugated into the
     * left-handed view. Intensity at or below zero leaves the matrix as it is.
     * The projection is not modified, so Remix still decomposes a real frustum.
     */
    public static void apply(Matrix4f worldToView, float spinningAngleDegrees, float intensity) {
        if (!(intensity > 0.0f)) {
            return;
        }
        float skew = skew(intensity);
        float angleRad = spinningAngleDegrees * DEGREES_TO_RADIANS;
        Matrix4f warp = new Matrix4f()
                .rotate(angleRad, VANILLA_AXIS)
                .scale(1.0f / skew, 1.0f, 1.0f)
                .rotate(-angleRad, VANILLA_AXIS);
        // Copy the flip. mul() writes into its receiver, so chaining on the
        // original would multiply the half-built warp by itself.
        Matrix4f flipZ = new Matrix4f().scale(1.0f, 1.0f, -1.0f);
        Matrix4f viewWarp = new Matrix4f(flipZ).mul(warp).mul(flipZ);
        worldToView.mulLocal(viewWarp);
    }

    /** {@code (5 / (intensity² + 5) - intensity * 0.04)²}, vanilla's horizontal squeeze. */
    static float skew(float intensity) {
        float squeezed = 5.0f / (intensity * intensity + 5.0f) - intensity * 0.04f;
        return squeezed * squeezed;
    }

    private static Vector3f unit(float x, float y, float z) {
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        if (length == 0.0f) {
            return new Vector3f();
        }
        return new Vector3f(x / length, y / length, z / length);
    }
}

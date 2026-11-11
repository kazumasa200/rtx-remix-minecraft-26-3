/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The pose {@code GameRenderer.renderLevel} multiplies into the projection after
 * {@code bobHurt} and {@code bobView}. Camera mods that inject there, the damage
 * roll, and view bobbing all land in this one matrix. It is OpenGL view space,
 * where forward is −Z. Remix looks along +Z, so the pose is conjugated by a Z
 * flip before it premultiplies the world-to-view matrix. A pose that is not a
 * finite rigid transform is ignored. A finite one that fails that check is logged
 * once. A non-finite one stays quiet: {@code bobHurt} divides by {@code hurtDuration}
 * on a partial tick of 0 while the player has never been hurt, and that quotient
 * is NaN. Folding it in replaces the camera for that frame.
 */
public final class LevelViewPose {
    private static final Logger LOGGER = LoggerFactory.getLogger(LevelViewPose.class);
    private static final float IDENTITY_EPSILON = 1.0e-6f;
    /** Wide enough for a rotation's float error, tight enough to refuse a mirror. */
    private static final float RIGID_DET_EPSILON = 1.0e-3f;
    private static final Matrix4f CAPTURED = new Matrix4f();
    private static boolean present;
    private static boolean loggedRejectedPose;

    private LevelViewPose() {}

    /** Drops a pose left over from a frame that never submitted a camera. */
    public static void clear() {
        present = false;
    }

    /** Copies the pose. The stack it came from is reused later in the frame. */
    public static void capture(Matrix4fc openGlPose) {
        CAPTURED.set(openGlPose);
        present = true;
    }

    /**
     * The pose captured for this frame, or null when {@code renderLevel} did not
     * build one. Does not clear it. The hand is mounted before the camera submit
     * consumes the pose.
     */
    public static Matrix4f peek() {
        if (!present) {
            return null;
        }
        return new Matrix4f(CAPTURED);
    }

    /**
     * The pose captured for this frame, or null when {@code renderLevel} did not
     * build one. Clears the capture so the next frame cannot reuse it.
     */
    public static Matrix4f consume() {
        if (!present) {
            return null;
        }
        present = false;
        return new Matrix4f(CAPTURED);
    }

    /**
     * Folds the OpenGL pose into the camera. An absent, identity, or non-rigid pose
     * returns the basis and scene position unchanged, so the still view stays on the
     * exact vectors the parameterized camera already used. A NaN hurt pose is the
     * non-rigid case that shows up on the first singleplayer join.
     */
    public static PlacedCamera place(
            WorldCameraAxes.Axes axes, float sceneX, float sceneY, float sceneZ, Matrix4fc openGlPose) {
        if (openGlPose == null || isIdentity(openGlPose) || !isRigid(openGlPose)) {
            return new PlacedCamera(axes, sceneX, sceneY, sceneZ);
        }
        Matrix4f view = PortalWarp.worldToView(axes, sceneX, sceneY, sceneZ);
        premultiply(view, openGlPose);
        Matrix4f viewToWorld = new Matrix4f(view).invert();
        WorldCameraAxes.Axes placed = new WorldCameraAxes.Axes(
                viewToWorld.m20(), viewToWorld.m21(), viewToWorld.m22(),
                viewToWorld.m00(), viewToWorld.m01(), viewToWorld.m02(),
                viewToWorld.m10(), viewToWorld.m11(), viewToWorld.m12());
        return new PlacedCamera(placed, viewToWorld.m30(), viewToWorld.m31(), viewToWorld.m32());
    }

    /**
     * Premultiplies the pose onto a Remix world-to-view. Vanilla post-multiplies
     * the portal warp onto the projection after this pose, so the pose sits
     * outside the warp: {@code P * pose * warp * view}.
     */
    public static void premultiply(Matrix4f worldToView, Matrix4fc openGlPose) {
        if (openGlPose == null || !isApplicable(openGlPose)) {
            return;
        }
        Matrix4f flipZ = new Matrix4f().scale(1.0f, 1.0f, -1.0f);
        Matrix4f remixPose = new Matrix4f(flipZ).mul(openGlPose).mul(flipZ);
        worldToView.mulLocal(remixPose);
    }

    /**
     * Identity is applicable and leaves the camera untouched. Anything else has to be
     * a finite rotation plus translation. A shear, a mirror, or a zoom cannot land
     * on the camera. The steady camera path normalizes its axes, so a zoom in this
     * pose would not scale the view there either.
     */
    static boolean isApplicable(Matrix4fc pose) {
        return isIdentity(pose) || isRigid(pose);
    }

    /**
     * A rigid pose has determinant 1 and three unit axes that are perpendicular.
     * Determinant alone lets a shear through and says nothing useful about a zoom.
     */
    static boolean isRigid(Matrix4fc pose) {
        if (!allFinite(pose)) {
            return false;
        }
        if (orthonormal(pose) && Math.abs(pose.determinant() - 1.0f) <= RIGID_DET_EPSILON) {
            return true;
        }
        if (!loggedRejectedPose) {
            loggedRejectedPose = true;
            LOGGER.warn("Ignoring a finite view pose that is not a rigid transform");
        }
        return false;
    }

    /** The three linear columns, translation in column 3 ignored. */
    private static boolean orthonormal(Matrix4fc pose) {
        Vector3f x = pose.getColumn(0, new Vector3f());
        Vector3f y = pose.getColumn(1, new Vector3f());
        Vector3f z = pose.getColumn(2, new Vector3f());
        return unit(x) && unit(y) && unit(z)
                && perpendicular(x, y) && perpendicular(y, z) && perpendicular(z, x);
    }

    private static boolean unit(Vector3fc axis) {
        return Math.abs(axis.length() - 1.0f) <= RIGID_DET_EPSILON;
    }

    private static boolean perpendicular(Vector3fc a, Vector3fc b) {
        return Math.abs(a.dot(b)) <= RIGID_DET_EPSILON;
    }

    private static boolean allFinite(Matrix4fc pose) {
        return finite(pose.m00()) && finite(pose.m01()) && finite(pose.m02()) && finite(pose.m03())
                && finite(pose.m10()) && finite(pose.m11()) && finite(pose.m12()) && finite(pose.m13())
                && finite(pose.m20()) && finite(pose.m21()) && finite(pose.m22()) && finite(pose.m23())
                && finite(pose.m30()) && finite(pose.m31()) && finite(pose.m32()) && finite(pose.m33());
    }

    private static boolean finite(float value) {
        return Float.isFinite(value);
    }

    static boolean isIdentity(Matrix4fc pose) {
        return Math.abs(pose.m00() - 1.0f) <= IDENTITY_EPSILON
                && Math.abs(pose.m11() - 1.0f) <= IDENTITY_EPSILON
                && Math.abs(pose.m22() - 1.0f) <= IDENTITY_EPSILON
                && Math.abs(pose.m33() - 1.0f) <= IDENTITY_EPSILON
                && Math.abs(pose.m01()) <= IDENTITY_EPSILON
                && Math.abs(pose.m02()) <= IDENTITY_EPSILON
                && Math.abs(pose.m03()) <= IDENTITY_EPSILON
                && Math.abs(pose.m10()) <= IDENTITY_EPSILON
                && Math.abs(pose.m12()) <= IDENTITY_EPSILON
                && Math.abs(pose.m13()) <= IDENTITY_EPSILON
                && Math.abs(pose.m20()) <= IDENTITY_EPSILON
                && Math.abs(pose.m21()) <= IDENTITY_EPSILON
                && Math.abs(pose.m23()) <= IDENTITY_EPSILON
                && Math.abs(pose.m30()) <= IDENTITY_EPSILON
                && Math.abs(pose.m31()) <= IDENTITY_EPSILON
                && Math.abs(pose.m32()) <= IDENTITY_EPSILON;
    }

    public record PlacedCamera(WorldCameraAxes.Axes axes, float sceneX, float sceneY, float sceneZ) {}
}

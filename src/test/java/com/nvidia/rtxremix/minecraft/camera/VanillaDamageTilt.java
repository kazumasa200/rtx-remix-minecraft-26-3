/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * The {@code GameRenderer.bobHurt} formula, kept as a reference for the tests.
 * Production does not call it. The tilt already sits in the pose {@code renderLevel}
 * captures, and applying this again would roll the view a second time.
 */
final class VanillaDamageTilt {
    private VanillaDamageTilt() {}

    /**
     * Rolls the view the way {@code GameRenderer.bobHurt} does.
     * {@code hurtTime} is {@code LivingEntity.hurtTime} minus the camera partial tick,
     * and {@code deathTime} is {@code deathTime} plus that partial tick.
     * No hurt and no death returns {@code axes} unchanged.
     */
    static WorldCameraAxes.Axes applyDamageTilt(
            WorldCameraAxes.Axes axes,
            boolean deadOrDying,
            float hurtTime,
            int hurtDuration,
            float hurtDirDegrees,
            float deathTime,
            double damageTiltStrength) {
        float deathRollDegrees = 0.0f;
        if (deadOrDying) {
            float clampedDeath = Math.min(deathTime, 20.0f);
            deathRollDegrees = 40.0f - 8000.0f / (clampedDeath + 200.0f);
        }
        float hurtRollDegrees = 0.0f;
        if (hurtTime >= 0.0f && hurtDuration > 0) {
            float wave = hurtTime / hurtDuration;
            wave = Mth.sin((double) wave * wave * wave * wave * Math.PI);
            hurtRollDegrees = (float) ((double) -wave * 14.0d * damageTiltStrength);
        }
        return tiltView(axes, deathRollDegrees, hurtDirDegrees, hurtRollDegrees);
    }

    /**
     * Applies the bob-hurt pose in view space. {@code hurtRollDegrees} of zero skips the
     * hurt yaw-roll-yaw, matching the early return when the hurt timer has elapsed.
     * The two yaw turns only aim that small roll. They must cancel, or the view snaps
     * sideways by {@code hurtDirDegrees}.
     */
    static WorldCameraAxes.Axes tiltView(
            WorldCameraAxes.Axes axes, float deathRollDegrees, float hurtDirDegrees, float hurtRollDegrees) {
        if (deathRollDegrees == 0.0f && hurtRollDegrees == 0.0f) {
            return axes;
        }
        PoseStack pose = new PoseStack();
        if (deathRollDegrees != 0.0f) {
            pose.rotateDegrees(Axis.ZP, deathRollDegrees);
        }
        if (hurtRollDegrees != 0.0f) {
            pose.rotateDegrees(Axis.YP, -hurtDirDegrees);
            pose.rotateDegrees(Axis.ZP, hurtRollDegrees);
            pose.rotateDegrees(Axis.YP, hurtDirDegrees);
        }
        // clip = projection * bob * view, so the view rows are right, up, -forward.
        // JOML stores that row across m00/m10/m20, not m00/m01/m02.
        Matrix4f bob = new Matrix4f(pose.last().pose()).mul(viewRotation(axes));
        return new WorldCameraAxes.Axes(
                -bob.m02(), -bob.m12(), -bob.m22(),
                bob.m00(), bob.m10(), bob.m20(),
                bob.m01(), bob.m11(), bob.m21());
    }

    private static Matrix4f viewRotation(WorldCameraAxes.Axes axes) {
        return new Matrix4f(
                axes.rightX(), axes.upX(), -axes.forwardX(), 0.0f,
                axes.rightY(), axes.upY(), -axes.forwardY(), 0.0f,
                axes.rightZ(), axes.upZ(), -axes.forwardZ(), 0.0f,
                0.0f, 0.0f, 0.0f, 1.0f);
    }
}

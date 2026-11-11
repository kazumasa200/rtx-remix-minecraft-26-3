/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.Transform;
import com.nvidia.rtxremix.minecraft.camera.LevelViewPose;
import com.nvidia.rtxremix.minecraft.camera.WorldCameraAxes;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemixFirstPersonPassTest {
    @Test
    void theHandMountUsesTheRolledBobbedCamera() {
        WorldCameraAxes.Axes looking = WorldCameraAxes.fromYawPitchRoll(
                20.0f, -10.0f, (float) Math.toRadians(40.0));
        Matrix4f bob = new Matrix4f().translate(0.2f, -0.3f, 0.0f).rotateZ((float) Math.toRadians(6.0));
        LevelViewPose.PlacedCamera placed = LevelViewPose.place(looking, 4.0f, 80.0f, 6.0f, bob);
        Transform mount = new Transform();
        RemixFirstPersonPass.setCameraToWorld(placed, mount);

        float[] m = mount.matrix;
        assertEquals(placed.axes().rightX(), m[0], 1.0e-4f);
        assertEquals(placed.axes().rightY(), m[4], 1.0e-4f);
        assertEquals(placed.axes().rightZ(), m[8], 1.0e-4f);
        assertEquals(placed.axes().upX(), m[1], 1.0e-4f);
        assertEquals(placed.axes().upY(), m[5], 1.0e-4f);
        assertEquals(placed.axes().upZ(), m[9], 1.0e-4f);
        assertEquals(-placed.axes().forwardX(), m[2], 1.0e-4f);
        assertEquals(-placed.axes().forwardY(), m[6], 1.0e-4f);
        assertEquals(-placed.axes().forwardZ(), m[10], 1.0e-4f);
        assertEquals(placed.sceneX(), m[3], 1.0e-4f);
        assertEquals(placed.sceneY(), m[7], 1.0e-4f);
        assertEquals(placed.sceneZ(), m[11], 1.0e-4f);
        assertTrue(Math.abs(placed.axes().upX()) > 0.2f);
        assertTrue(Math.abs(placed.sceneY() - 80.0f) > 0.05f);
    }
}

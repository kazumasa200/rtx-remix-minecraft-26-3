/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldCameraAxesTest {
    private static final float EPSILON = 1.0e-4f;

    @Test
    void zeroRollMatchesTheLevelBasis() {
        float[] yaws = {0.0f, 37.0f, -120.0f, 180.0f};
        float[] pitches = {0.0f, -25.0f, 64.0f, 89.0f};
        for (float yaw : yaws) {
            for (float pitch : pitches) {
                assertClose(WorldCameraAxes.level(yaw, pitch),
                        WorldCameraAxes.fromYawPitchRoll(yaw, pitch, 0.0f));
            }
        }
    }

    @Test
    void aQuarterRollLiftsTheRightAxis() {
        WorldCameraAxes.Axes rolled = WorldCameraAxes.fromYawPitchRoll(0.0f, 0.0f, (float) (Math.PI / 2.0));
        assertTrue(Math.abs(rolled.rightY()) > 0.5f);
        assertEquals(0.0f, WorldCameraAxes.level(0.0f, 0.0f).rightY(), EPSILON);
    }

    @Test
    void noDamageLeavesTheLevelBasis() {
        WorldCameraAxes.Axes level = WorldCameraAxes.level(0.0f, 0.0f);
        assertSame(level, VanillaDamageTilt.applyDamageTilt(level, false, -0.5f, 10, 0.0f, 0.0f, 1.0d));
        assertSame(level, VanillaDamageTilt.applyDamageTilt(level, false, 8.0f, 10, 0.0f, 0.0f, 0.0d));
    }

    @Test
    void aFrontHitRollsTheRightAxisUp() {
        WorldCameraAxes.Axes level = WorldCameraAxes.level(0.0f, 0.0f);
        WorldCameraAxes.Axes tilted = VanillaDamageTilt.tiltView(level, 0.0f, 0.0f, -14.0f);
        float theta = -14.0f * 0.017453292f;
        float cos = (float) Math.cos(theta);
        float sin = (float) Math.sin(theta);
        assertEquals(-cos, tilted.rightX(), EPSILON);
        assertEquals(-sin, tilted.rightY(), EPSILON);
        assertEquals(0.0f, tilted.rightZ(), EPSILON);
        assertEquals(-sin, tilted.upX(), EPSILON);
        assertEquals(cos, tilted.upY(), EPSILON);
        assertEquals(0.0f, tilted.forwardX(), EPSILON);
        assertEquals(0.0f, tilted.forwardY(), EPSILON);
        assertEquals(1.0f, tilted.forwardZ(), EPSILON);
        assertTrue(tilted.rightY() > 0.2f);
    }

    @Test
    void aSideHitPitchesTheView() {
        WorldCameraAxes.Axes level = WorldCameraAxes.level(0.0f, 0.0f);
        WorldCameraAxes.Axes tilted = VanillaDamageTilt.tiltView(level, 0.0f, 90.0f, -14.0f);
        assertTrue(Math.abs(tilted.forwardY()) > 0.2f);
        assertEquals(0.0f, level.forwardY(), EPSILON);
    }

    @Test
    void damageTiltStaysSmallWhenTheCameraIsTurned() {
        WorldCameraAxes.Axes looking = WorldCameraAxes.level(40.0f, 20.0f);
        WorldCameraAxes.Axes fromTheSide = VanillaDamageTilt.tiltView(looking, 0.0f, 90.0f, -14.0f);
        WorldCameraAxes.Axes fromTheFront = VanillaDamageTilt.tiltView(looking, 0.0f, 0.0f, -14.0f);
        // Vanilla's hardest tilt is 14 degrees. A swapped matrix row looks about 90 degrees off.
        assertTrue(forwardDot(looking, fromTheSide) > 0.96f);
        assertTrue(forwardDot(looking, fromTheFront) > 0.999f);
        assertTrue(forwardDot(looking, VanillaDamageTilt.tiltView(looking, 0.0f, 180.0f, -14.0f)) > 0.96f);
    }

    @Test
    void deathRollsEvenWhenTheHurtTimerHasElapsed() {
        WorldCameraAxes.Axes level = WorldCameraAxes.level(0.0f, 0.0f);
        WorldCameraAxes.Axes tilted = VanillaDamageTilt.applyDamageTilt(level, true, -1.0f, 10, 0.0f, 20.0f, 0.0d);
        assertTrue(Math.abs(tilted.rightY()) > 0.05f);
        assertEquals(1.0f, tilted.forwardZ(), EPSILON);
    }

    private static float forwardDot(WorldCameraAxes.Axes a, WorldCameraAxes.Axes b) {
        return a.forwardX() * b.forwardX() + a.forwardY() * b.forwardY() + a.forwardZ() * b.forwardZ();
    }

    private static void assertClose(WorldCameraAxes.Axes expected, WorldCameraAxes.Axes actual) {
        assertEquals(expected.forwardX(), actual.forwardX(), EPSILON);
        assertEquals(expected.forwardY(), actual.forwardY(), EPSILON);
        assertEquals(expected.forwardZ(), actual.forwardZ(), EPSILON);
        assertEquals(expected.rightX(), actual.rightX(), EPSILON);
        assertEquals(expected.rightY(), actual.rightY(), EPSILON);
        assertEquals(expected.rightZ(), actual.rightZ(), EPSILON);
        assertEquals(expected.upX(), actual.upX(), EPSILON);
        assertEquals(expected.upY(), actual.upY(), EPSILON);
        assertEquals(expected.upZ(), actual.upZ(), EPSILON);
    }
}

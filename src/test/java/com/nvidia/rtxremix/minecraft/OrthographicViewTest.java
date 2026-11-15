/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class OrthographicViewTest {

    @Test
    void aParallelProjectionBecomesAFarNarrowCamera() {
        OrthographicView view = new OrthographicView();
        view.set(new Matrix4f().setOrtho(-12.0f, 12.0f, -6.0f, 6.0f, -1000.0f, 1000.0f));

        assertTrue(view.active());
        assertEquals(OrthographicView.DISTANCE_PER_HALF_HEIGHT * 6.0f, view.distance(), 1.0e-3f);
        assertEquals(2.0f, view.aspect(), 1.0e-5f);
        // The camera's field of view spans the projection's height at the game camera's plane.
        assertEquals(6.0, view.distance() * Math.tan(Math.toRadians(view.fovYDegrees()) * 0.5), 1.0e-3);
        assertEquals(0.0f, view.centerX(), 1.0e-5f);
        assertEquals(0.0f, view.centerY(), 1.0e-5f);
    }

    @Test
    void aProjectionOffTheCameraAxisMovesTheViewCentre() {
        OrthographicView view = new OrthographicView();
        view.set(new Matrix4f().setOrtho(0.0f, 20.0f, 0.0f, 10.0f, 0.0f, 100.0f));

        assertEquals(10.0f, view.centerX(), 1.0e-4f);
        assertEquals(5.0f, view.centerY(), 1.0e-4f);
    }

    @Test
    void aTallViewKeepsTheLargestDistance() {
        OrthographicView view = new OrthographicView();
        view.set(new Matrix4f().setOrtho(-400.0f, 400.0f, -200.0f, 200.0f, -1000.0f, 1000.0f));

        assertEquals(OrthographicView.MAX_DISTANCE, view.distance(), 0.0f);
    }

    @Test
    void aPerspectiveProjectionIsLeftAlone() {
        OrthographicView view = new OrthographicView();
        view.set(new Matrix4f().setPerspective((float) Math.toRadians(70.0), 16.0f / 9.0f, 0.05f, 1000.0f));

        assertFalse(view.active());
        assertEquals(0.0f, view.distance(), 0.0f);
    }
}

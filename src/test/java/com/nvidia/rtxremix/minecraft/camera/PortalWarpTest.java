/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalWarpTest {
    private static final float EPSILON = 1.0e-4f;
    private static final float FOV = 70.0f;
    private static final float ASPECT = 16.0f / 9.0f;
    private static final float NEAR = 0.05f;
    private static final float FAR = 1000.0f;

    @Test
    void zeroIntensityLeavesTheView() {
        Matrix4f view = PortalWarp.worldToView(WorldCameraAxes.level(40.0f, 20.0f), 12.0f, 70.0f, -4.0f);
        Matrix4f copy = new Matrix4f(view);
        PortalWarp.apply(copy, 90.0f, 0.0f);
        PortalWarp.apply(copy, 90.0f, -1.0f);
        assertClose(view, copy);
    }

    @Test
    void distortionScaleZeroDisablesTheWarp() {
        assertEquals(0.0f, PortalWarp.intensity(1.0f, 1.0f, 0.0f), 0.0f);
        assertEquals(0.0f, PortalWarp.intensity(0.0f, 0.0f, 1.0f), 0.0f);
    }

    @Test
    void fullIntensityUsesTheStrongerOfPortalAndNausea() {
        assertEquals(1.0f, PortalWarp.intensity(1.0f, 0.2f, 1.0f), EPSILON);
        assertEquals(0.125f, PortalWarp.intensity(0.5f, 0.2f, 0.5f), EPSILON);
    }

    @Test
    void fullIntensityScalesXAndLeavesTheLookAxis() {
        float scale = 1.0f / PortalWarp.skew(1.0f);
        assertEquals(1.588f, scale, 1.0e-3f);
        Matrix4f projection = PortalWarp.projection(FOV, ASPECT, NEAR, FAR);
        Matrix4f view = new Matrix4f();
        PortalWarp.apply(view, 0.0f, 1.0f);
        Vector4f onAxis = project(projection, view, 0.0f, 0.0f, 5.0f);
        assertEquals(0.0f, onAxis.x, EPSILON);
        Vector4f toTheRight = project(projection, view, 1.0f, 0.0f, 5.0f);
        Vector4f unwarped = projection.transform(new Vector4f(1.0f, 0.0f, 5.0f, 1.0f));
        assertEquals(unwarped.x * scale, toTheRight.x, 1.0e-3f);
    }

    @Test
    void aTurnedStretchPullsAnOverheadPointSideways() {
        Matrix4f projection = PortalWarp.projection(FOV, ASPECT, NEAR, FAR);
        Matrix4f view = new Matrix4f();
        PortalWarp.apply(view, 40.0f, 1.0f);
        assertTrue(Math.abs(project(projection, view, 0.0f, 1.0f, 5.0f).x) > 0.2f);
    }

    @Test
    void theWarpMatchesVanillaOnceZIsFlipped() {
        float angle = 40.0f;
        float intensity = 1.0f;
        float skew = PortalWarp.skew(intensity);
        float angleRad = angle * 0.017453292f;
        Vector3f axis = new Vector3f(0.0f, (float) Math.sqrt(2.0) / 2.0f, (float) Math.sqrt(2.0) / 2.0f);
        Matrix4f vanilla = new Matrix4f().setPerspective((float) Math.toRadians(FOV), ASPECT, NEAR, FAR, false);
        vanilla.rotate(angleRad, axis).scale(1.0f / skew, 1.0f, 1.0f).rotate(-angleRad, axis);
        Matrix4f projection = PortalWarp.projection(FOV, ASPECT, NEAR, FAR);
        Matrix4f view = new Matrix4f();
        PortalWarp.apply(view, angle, intensity);
        float[][] points = {{0.0f, 0.0f, 5.0f}, {1.0f, 0.0f, 5.0f}, {0.0f, 1.0f, 5.0f}, {-0.4f, 0.7f, 2.0f}};
        for (float[] point : points) {
            Vector4f glPoint = vanilla.transform(new Vector4f(point[0], point[1], -point[2], 1.0f));
            Vector4f ourPoint = project(projection, view, point[0], point[1], point[2]);
            assertEquals(glPoint.x, ourPoint.x, 1.0e-3f);
            assertEquals(glPoint.y, ourPoint.y, 1.0e-3f);
            assertEquals(glPoint.z, ourPoint.z, 1.0e-3f);
            assertEquals(glPoint.w, ourPoint.w, 1.0e-3f);
        }
    }

    @Test
    void projectionMatchesTheLeftHandedFrustum() {
        Matrix4f projection = PortalWarp.projection(FOV, ASPECT, NEAR, FAR);
        float tanHalf = (float) Math.tan(Math.toRadians(FOV) * 0.5);
        assertEquals(1.0f / (ASPECT * tanHalf), projection.m00(), EPSILON);
        assertEquals(1.0f / tanHalf, projection.m11(), EPSILON);
        assertEquals((FAR + NEAR) / (FAR - NEAR), projection.get(2, 2), EPSILON);
        assertEquals(2.0f * FAR * NEAR / (NEAR - FAR), projection.get(3, 2), EPSILON);
        assertEquals(1.0f, projection.get(2, 3), EPSILON);
        assertEquals(0.0f, projection.get(3, 3), EPSILON);
        assertEquals(0.0f, projection.m01(), EPSILON);
        assertEquals(0.0f, projection.m02(), EPSILON);
        assertEquals(0.0f, projection.m03(), EPSILON);
    }

    @Test
    void theWarpLeavesTheProjectionAReadableFrustum() {
        Matrix4f projection = PortalWarp.projection(FOV, ASPECT, NEAR, FAR);
        Matrix4f copy = new Matrix4f(projection);
        Matrix4f view = PortalWarp.worldToView(WorldCameraAxes.level(40.0f, 20.0f), 12.0f, 70.0f, -4.0f);
        PortalWarp.apply(view, 40.0f, 1.0f);
        assertClose(projection, copy);
        assertEquals(1.0f, projection.get(2, 3), EPSILON);
        assertEquals(0.0f, projection.get(3, 3), EPSILON);
    }

    @Test
    void remixReadsTranslationAsTheLastColumn() {
        Matrix4f view = PortalWarp.worldToView(WorldCameraAxes.level(0.0f, 0.0f), 12.0f, 70.0f, -4.0f);
        float[] packed = new float[16];
        view.get(packed);
        assertEquals(0.0f, packed[3], EPSILON);
        assertEquals(0.0f, packed[7], EPSILON);
        assertEquals(0.0f, packed[11], EPSILON);
        assertEquals(12.0f, packed[12], EPSILON);
        assertEquals(-70.0f, packed[13], EPSILON);
        assertEquals(4.0f, packed[14], EPSILON);
        assertEquals(1.0f, packed[15], EPSILON);
        Vector4f atCamera = view.transform(new Vector4f(12.0f, 70.0f, -4.0f, 1.0f));
        assertEquals(0.0f, atCamera.x, EPSILON);
        assertEquals(0.0f, atCamera.y, EPSILON);
        assertEquals(0.0f, atCamera.z, EPSILON);
        assertEquals(1.0f, atCamera.w, EPSILON);
    }

    @Test
    void aTurnedCameraKeepsTheBlockAheadInFront() {
        WorldCameraAxes.Axes looking = WorldCameraAxes.level(40.0f, 20.0f);
        Matrix4f view = PortalWarp.worldToView(looking, 12.0f, 70.0f, -4.0f);
        Matrix4f viewToWorld = new Matrix4f(view).invert();
        assertEquals(looking.forwardX(), viewToWorld.m20(), EPSILON);
        assertEquals(looking.forwardY(), viewToWorld.m21(), EPSILON);
        assertEquals(looking.forwardZ(), viewToWorld.m22(), EPSILON);
        assertEquals(12.0f, viewToWorld.m30(), EPSILON);
        assertEquals(70.0f, viewToWorld.m31(), EPSILON);
        assertEquals(-4.0f, viewToWorld.m32(), EPSILON);
        Vector3f forward = new Vector3f(looking.forwardX(), looking.forwardY(), looking.forwardZ()).normalize();
        Vector4f ahead = view.transform(new Vector4f(
                12.0f + forward.x * 5.0f,
                70.0f + forward.y * 5.0f,
                -4.0f + forward.z * 5.0f,
                1.0f));
        assertEquals(0.0f, ahead.x, 1.0e-3f);
        assertEquals(0.0f, ahead.y, 1.0e-3f);
        assertEquals(5.0f, ahead.z, 1.0e-3f);
        assertEquals(1.0f, ahead.w, 1.0e-3f);
    }

    @Test
    void theStretchStaysOnTheScreenWhenTheViewYaws() {
        Matrix4f projection = PortalWarp.projection(FOV, ASPECT, NEAR, FAR);
        float yaw0 = stretchedClipX(projection, 0.0f);
        float yaw40 = stretchedClipX(projection, 40.0f);
        assertEquals(yaw0, yaw40, 1.0e-3f);
        assertTrue(Math.abs(yaw0) > 0.2f);
    }

    private static float stretchedClipX(Matrix4f projection, float yawDegrees) {
        Matrix4f plain = PortalWarp.worldToView(WorldCameraAxes.level(yawDegrees, 0.0f), 12.0f, 70.0f, -4.0f);
        Matrix4f warped = new Matrix4f(plain);
        PortalWarp.apply(warped, 40.0f, 1.0f);
        Matrix4f viewSpaceWarp = new Matrix4f(warped).mul(plain.invert());
        Vector4f warpedPoint = viewSpaceWarp.transform(new Vector4f(0.0f, 1.0f, 5.0f, 1.0f));
        return projection.transform(warpedPoint).x;
    }

    private static Vector4f project(Matrix4f projection, Matrix4f view, float x, float y, float z) {
        return projection.transform(view.transform(new Vector4f(x, y, z, 1.0f)));
    }

    private static void assertClose(Matrix4f expected, Matrix4f actual) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                assertEquals(expected.get(column, row), actual.get(column, row), EPSILON);
            }
        }
    }
}

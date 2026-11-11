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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevelViewPoseTest {
    private static final float EPSILON = 1.0e-4f;

    @Test
    void anIdentityPoseLeavesTheCameraWhereItIs() {
        WorldCameraAxes.Axes looking = WorldCameraAxes.level(40.0f, 20.0f);
        LevelViewPose.PlacedCamera placed = LevelViewPose.place(looking, 12.0f, 70.0f, -4.0f, new Matrix4f());
        assertSame(looking, placed.axes());
        assertEquals(12.0f, placed.sceneX(), EPSILON);
        assertEquals(70.0f, placed.sceneY(), EPSILON);
        assertEquals(-4.0f, placed.sceneZ(), EPSILON);
    }

    @Test
    void aBrokenHurtPoseLeavesTheCameraWhereItIs() {
        WorldCameraAxes.Axes looking = WorldCameraAxes.level(40.0f, 20.0f);
        Matrix4f pose = new Matrix4f().set(0, 0, Float.NaN);
        LevelViewPose.PlacedCamera placed = LevelViewPose.place(looking, 12.0f, 70.0f, -4.0f, pose);
        assertSame(looking, placed.axes());
        assertEquals(12.0f, placed.sceneX(), EPSILON);
        assertEquals(70.0f, placed.sceneY(), EPSILON);
        assertEquals(-4.0f, placed.sceneZ(), EPSILON);

        Matrix4f view = PortalWarp.worldToView(looking, 12.0f, 70.0f, -4.0f);
        Matrix4f before = new Matrix4f(view);
        LevelViewPose.premultiply(view, pose);
        assertTrue(view.equals(before, EPSILON));
    }

    @Test
    void peekLeavesThePoseUntilTheFirstConsume() {
        LevelViewPose.clear();
        try {
            Matrix4f pose = new Matrix4f().translate(0.4f, -0.25f, 0.1f);
            LevelViewPose.capture(pose);
            assertTrue(LevelViewPose.peek().equals(pose, EPSILON));
            assertTrue(LevelViewPose.peek().equals(pose, EPSILON));
            assertTrue(LevelViewPose.consume().equals(pose, EPSILON));
            assertNull(LevelViewPose.consume());
            assertNull(LevelViewPose.peek());
        } finally {
            LevelViewPose.clear();
        }
    }

    @Test
    void aShearWithDeterminantOneLeavesTheCameraWhereItIs() {
        WorldCameraAxes.Axes looking = WorldCameraAxes.level(40.0f, 20.0f);
        Matrix4f pose = new Matrix4f().set(1, 0, 0.35f);
        assertEquals(1.0f, pose.determinant(), 1.0e-5f);
        LevelViewPose.PlacedCamera placed = LevelViewPose.place(looking, 12.0f, 70.0f, -4.0f, pose);
        assertSame(looking, placed.axes());
        assertEquals(12.0f, placed.sceneX(), EPSILON);
        assertEquals(70.0f, placed.sceneY(), EPSILON);
        assertEquals(-4.0f, placed.sceneZ(), EPSILON);

        Matrix4f view = PortalWarp.worldToView(looking, 12.0f, 70.0f, -4.0f);
        Matrix4f before = new Matrix4f(view);
        LevelViewPose.premultiply(view, pose);
        assertTrue(view.equals(before, EPSILON));
    }

    @Test
    void aUniformZoomLeavesTheCameraWhereItIs() {
        WorldCameraAxes.Axes looking = WorldCameraAxes.level(40.0f, 20.0f);
        Matrix4f pose = new Matrix4f().scale(1.25f);
        LevelViewPose.PlacedCamera placed = LevelViewPose.place(looking, 12.0f, 70.0f, -4.0f, pose);
        assertSame(looking, placed.axes());
        assertEquals(12.0f, placed.sceneX(), EPSILON);
        assertEquals(70.0f, placed.sceneY(), EPSILON);
        assertEquals(-4.0f, placed.sceneZ(), EPSILON);
    }

    @Test
    void aMirroredPoseLeavesTheCameraWhereItIs() {
        WorldCameraAxes.Axes looking = WorldCameraAxes.level(40.0f, 20.0f);
        Matrix4f pose = new Matrix4f().scale(-1.0f, 1.0f, 1.0f);
        LevelViewPose.PlacedCamera placed = LevelViewPose.place(looking, 12.0f, 70.0f, -4.0f, pose);
        assertSame(looking, placed.axes());
        assertEquals(12.0f, placed.sceneX(), EPSILON);
        assertEquals(70.0f, placed.sceneY(), EPSILON);
        assertEquals(-4.0f, placed.sceneZ(), EPSILON);

        Matrix4f view = PortalWarp.worldToView(looking, 12.0f, 70.0f, -4.0f);
        Matrix4f before = new Matrix4f(view);
        LevelViewPose.premultiply(view, pose);
        assertTrue(view.equals(before, EPSILON));
    }

    @Test
    void aViewShiftSlidesAlongRightAndUp() {
        WorldCameraAxes.Axes looking = WorldCameraAxes.level(90.0f, 0.0f);
        Matrix4f pose = new Matrix4f().translate(0.4f, -0.25f, 0.0f);
        LevelViewPose.PlacedCamera placed = LevelViewPose.place(looking, 12.0f, 70.0f, -4.0f, pose);
        Vector3f delta = new Vector3f(placed.sceneX() - 12.0f, placed.sceneY() - 70.0f, placed.sceneZ() + 4.0f);
        Vector3f right = new Vector3f(looking.rightX(), looking.rightY(), looking.rightZ());
        Vector3f up = new Vector3f(looking.upX(), looking.upY(), looking.upZ());
        Vector3f forward = new Vector3f(looking.forwardX(), looking.forwardY(), looking.forwardZ());
        Vector3f expected = new Vector3f(right).mul(-0.4f).add(new Vector3f(up).mul(0.25f));
        assertEquals(expected.x, delta.x, 1.0e-3f);
        assertEquals(expected.y, delta.y, 1.0e-3f);
        assertEquals(expected.z, delta.z, 1.0e-3f);
        assertEquals(0.0f, delta.dot(forward), 1.0e-3f);
        assertTrue(Math.abs(looking.rightY()) < EPSILON);
        assertTrue(Math.abs(delta.y) > 0.2f);
    }

    @Test
    void aRollOnATurnedCameraDoesNotSnapTheHorizon() {
        WorldCameraAxes.Axes looking = WorldCameraAxes.level(40.0f, 20.0f);
        Matrix4f pose = new Matrix4f().rotateZ((float) Math.toRadians(14.0));
        LevelViewPose.PlacedCamera placed = LevelViewPose.place(looking, 12.0f, 70.0f, -4.0f, pose);
        float forwardDot = looking.forwardX() * placed.axes().forwardX()
                + looking.forwardY() * placed.axes().forwardY()
                + looking.forwardZ() * placed.axes().forwardZ();
        assertTrue(forwardDot > 0.999f);
        float rightDot = looking.rightX() * placed.axes().rightX()
                + looking.rightY() * placed.axes().rightY()
                + looking.rightZ() * placed.axes().rightZ();
        assertTrue(rightDot < 0.98f);
        assertTrue(rightDot > 0.9f);
        assertEquals(12.0f, placed.sceneX(), 1.0e-3f);
        assertEquals(70.0f, placed.sceneY(), 1.0e-3f);
        assertEquals(-4.0f, placed.sceneZ(), 1.0e-3f);
    }

    @Test
    void thePortalWarpStaysInsideThePose() {
        WorldCameraAxes.Axes looking = WorldCameraAxes.level(15.0f, -10.0f);
        Matrix4f pose = new Matrix4f().translate(0.2f, -0.1f, 0.0f).rotateZ((float) Math.toRadians(8.0));
        Matrix4f view = PortalWarp.worldToView(looking, 3.0f, 64.0f, 9.0f);
        Matrix4f projection = PortalWarp.projection(70.0f, 16.0f / 9.0f, 0.05f, 1000.0f);
        PortalWarp.apply(view, 40.0f, 1.0f);
        LevelViewPose.premultiply(view, pose);

        Matrix4f flipZ = new Matrix4f().scale(1.0f, 1.0f, -1.0f);
        Matrix4f warpOnly = PortalWarp.worldToView(looking, 3.0f, 64.0f, 9.0f);
        PortalWarp.apply(warpOnly, 40.0f, 1.0f);
        Matrix4f base = PortalWarp.worldToView(looking, 3.0f, 64.0f, 9.0f);
        Matrix4f warp = new Matrix4f(warpOnly).mul(new Matrix4f(base).invert());
        Matrix4f remixPose = new Matrix4f(flipZ).mul(pose).mul(flipZ);
        Matrix4f expected = new Matrix4f(projection).mul(remixPose).mul(warp).mul(base);

        Vector4f world = new Vector4f(3.0f + looking.forwardX() * 4.0f, 64.0f + looking.forwardY() * 4.0f,
                9.0f + looking.forwardZ() * 4.0f, 1.0f);
        Vector4f actual = projection.transform(view.transform(new Vector4f(world)));
        Vector4f vanillaOrder = expected.transform(new Vector4f(world));
        assertEquals(vanillaOrder.x, actual.x, 1.0e-3f);
        assertEquals(vanillaOrder.y, actual.y, 1.0e-3f);
        assertEquals(vanillaOrder.z, actual.z, 1.0e-3f);
        assertEquals(vanillaOrder.w, actual.w, 1.0e-3f);
    }

    @Test
    void theHandBobStaysInsideTheViewScale() {
        WorldCameraAxes.Axes looking = WorldCameraAxes.fromYawPitchRoll(
                25.0f, -12.0f, (float) Math.toRadians(40.0));
        Matrix4f bob = new Matrix4f().translate(0.22f, -0.18f, 0.0f).rotateZ((float) Math.toRadians(9.0));
        Matrix4f viewRotation = new Matrix4f(
                looking.rightX(), looking.upX(), -looking.forwardX(), 0.0f,
                looking.rightY(), looking.upY(), -looking.forwardY(), 0.0f,
                looking.rightZ(), looking.upZ(), -looking.forwardZ(), 0.0f,
                0.0f, 0.0f, 0.0f, 1.0f);
        Vector3f item = new Vector3f(0.56f, -0.52f, -0.72f);
        Matrix4f bone = new Matrix4f(viewRotation).invert().mul(bob).translate(item);
        Vector3f onScreen = new Matrix4f().scale(0.1f).mul(viewRotation).mul(bone).transformPosition(new Vector3f());
        Vector3f vanillaSized = new Matrix4f().scale(0.1f).mul(bob).transformPosition(new Vector3f(item));
        assertEquals(vanillaSized.x, onScreen.x, 1.0e-3f);
        assertEquals(vanillaSized.y, onScreen.y, 1.0e-3f);
        assertEquals(vanillaSized.z, onScreen.z, 1.0e-3f);

        Vector3f stuckToTheCrosshair = new Vector3f(item).mul(0.1f);
        float sway = onScreen.distance(stuckToTheCrosshair);
        assertTrue(sway > 0.01f);
        Vector3f flungByTheFullBob = new Matrix4f(bob).mul(new Matrix4f().scale(0.1f)).transformPosition(new Vector3f(item));
        assertTrue(flungByTheFullBob.distance(stuckToTheCrosshair) > sway * 2.0f);

        LevelViewPose.PlacedCamera placed = LevelViewPose.place(looking, 8.0f, 70.0f, -3.0f, bob);
        Vector3f world = new Vector3f(placed.axes().rightX(), placed.axes().rightY(), placed.axes().rightZ())
                .mul(onScreen.x)
                .add(new Vector3f(placed.axes().upX(), placed.axes().upY(), placed.axes().upZ()).mul(onScreen.y))
                .add(new Vector3f(-placed.axes().forwardX(), -placed.axes().forwardY(), -placed.axes().forwardZ())
                        .mul(onScreen.z))
                .add(placed.sceneX(), placed.sceneY(), placed.sceneZ());
        Vector3f delta = new Vector3f(world.x - placed.sceneX(), world.y - placed.sceneY(), world.z - placed.sceneZ());
        assertEquals(onScreen.x, delta.dot(placed.axes().rightX(), placed.axes().rightY(), placed.axes().rightZ()), 1.0e-3f);
        assertEquals(onScreen.y, delta.dot(placed.axes().upX(), placed.axes().upY(), placed.axes().upZ()), 1.0e-3f);
        assertEquals(onScreen.z,
                delta.dot(-placed.axes().forwardX(), -placed.axes().forwardY(), -placed.axes().forwardZ()), 1.0e-3f);
    }
}

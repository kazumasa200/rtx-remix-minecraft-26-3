/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FirstPersonShadowModelPlacementTest {

    private static final Vec3 HEAD = new Vec3(10.0, 70.0, -4.0);
    private static final double EMITTER_RADIUS = 0.05;

    private static FirstPersonShadowModelPlacement.LightConstraint lightAhead(double yawDegrees, double distance) {
        Vec3 forward = HeldLightPlacement.horizontalForward((float) yawDegrees);
        return new FirstPersonShadowModelPlacement.LightConstraint(
                HEAD.add(forward.scale(distance)),
                EMITTER_RADIUS,
                FirstPersonShadowModelPlacement.TARGET_LIGHT_GAP);
    }

    private static Vec3 target(double yawDegrees, double distance) {
        return FirstPersonShadowModelPlacement.targetOffset(
                HEAD,
                List.of(lightAhead(yawDegrees, distance)),
                HeldLightPlacement.horizontalForward((float) yawDegrees).reverse());
    }

    @Test
    void aLightAtItsPreferredDistanceLeavesTheModelInPlace() {
        double preferred = HeldLightPlacement.preferredDistance(EMITTER_RADIUS);
        for (int yaw = 0; yaw < 360; yaw += 15) {
            assertEquals(0.0, target(yaw, preferred).length(), 1.0e-9, "yaw " + yaw);
        }
    }

    @Test
    void aLightPulledInMovesTheModelStraightAwayByWhatTheGapNeeds() {
        double pulledIn = 0.75;
        Vec3 offset = target(30.0, pulledIn);
        Vec3 backward = HeldLightPlacement.horizontalForward(30.0f).reverse();

        double expected = FirstPersonShadowModelPlacement.clearCenterDistance(
                EMITTER_RADIUS, FirstPersonShadowModelPlacement.TARGET_LIGHT_GAP) - pulledIn;
        assertEquals(expected, offset.length(), 1.0e-9);
        assertEquals(1.0, offset.normalize().dot(backward), 1.0e-9);
        assertEquals(0.0, offset.y, 0.0);
    }

    @Test
    void theOffsetNeverExceedsItsLimit() {
        assertEquals(FirstPersonShadowModelPlacement.MAX_MODEL_OFFSET, target(0.0, 0.1).length(), 1.0e-9);
    }

    @Test
    void turningTheViewTurnsTheOffsetWithoutJumps() {
        // Issue #33: the offset was picked among fixed candidates, held still while the
        // view turned, then jumped up to 0.14 blocks in a frame.
        double pulledIn = 0.6;
        Vec3 previous = target(0.0, pulledIn);
        double radius = previous.length();
        double stepRadians = Math.toRadians(0.5);
        for (double yaw = 0.5; yaw <= 720.0; yaw += 0.5) {
            Vec3 next = target(yaw, pulledIn);
            double moved = next.distanceTo(previous);
            assertTrue(moved <= radius * stepRadians * 1.0001, "yaw " + yaw + " moved " + moved);
            previous = next;
        }
    }

    @Test
    void aLightStraightAboveTheHeadFallsBackToTheGivenDirection() {
        FirstPersonShadowModelPlacement.LightConstraint above = new FirstPersonShadowModelPlacement.LightConstraint(
                HEAD.add(0.0, 0.5, 0.0), EMITTER_RADIUS, FirstPersonShadowModelPlacement.TARGET_LIGHT_GAP);
        Vec3 fallback = new Vec3(0.0, 0.0, -1.0);

        Vec3 offset = FirstPersonShadowModelPlacement.targetOffset(HEAD, List.of(above), fallback);

        assertEquals(1.0, offset.normalize().dot(fallback), 1.0e-9);
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import com.nvidia.rtxremix.minecraft.lod.dh.ZoomHold;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ZoomHoldTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void aGlanceThroughASightLeavesTheTerrainAsItWas() {
        ZoomHold hold = new ZoomHold();

        hold.update(0, 2.0, 0.3, 1.0, 0.0);
        hold.update(2 * SECOND, 2.0, 0.3, 1.0, 0.0);
        assertEquals(1.0, hold.magnification());
        assertEquals(0.0, hold.coneTanHalfAngle());

        hold.update(2 * SECOND + 1, 1.0, 0.0, 0.0, 0.0);
        assertEquals(1.0, hold.magnification());
    }

    @Test
    void aHeldZoomReachesTheTerrainAndFollowsTheLook() {
        ZoomHold hold = new ZoomHold();

        hold.update(0, 2.0, 0.3, 1.0, 0.0);
        hold.update(3 * SECOND, 4.0, 0.2, 0.0, 1.0);

        assertEquals(4.0, hold.magnification());
        assertEquals(0.2, hold.coneTanHalfAngle());
        assertEquals(0.0, hold.lookDirectionX());
        assertEquals(1.0, hold.lookDirectionZ());
    }

    @Test
    void theDetailOutlastsTheZoomAndAimingAgainFindsIt() {
        ZoomHold hold = new ZoomHold();
        hold.update(0, 2.0, 0.3, 1.0, 0.0);
        hold.update(3 * SECOND, 2.0, 0.3, 1.0, 0.0);

        hold.update(8 * SECOND, 1.0, 0.0, 0.0, 0.0);
        assertEquals(2.0, hold.magnification(), "kept while released");
        assertEquals(1.0, hold.lookDirectionX());

        hold.update(9 * SECOND, 3.0, 0.25, 0.0, 1.0);
        assertEquals(3.0, hold.magnification(), "no second wait within the release");

        hold.update(15 * SECOND, 1.0, 0.0, 0.0, 0.0);
        hold.update(20 * SECOND, 1.0, 0.0, 0.0, 0.0);
        assertEquals(1.0, hold.magnification(), "released ten seconds after the last zoom");

        hold.update(21 * SECOND, 2.0, 0.3, 1.0, 0.0);
        assertEquals(1.0, hold.magnification(), "and the next zoom waits again");
    }
}

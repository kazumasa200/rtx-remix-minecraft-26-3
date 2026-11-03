/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepCaveCullTest {

    @Test
    void aSectionGoesOnlyWhenItAndEveryNeighbourAreDark() {
        // Rock below section 3, open air from section 4 up.
        DeepCaveCull.Darkness rock = (cx, sy, cz) -> sy <= 3;
        assertTrue(DeepCaveCull.culled(rock, 0, 2, 0));
        // Its top faces meet the lit air above, so the ground under open air stays.
        assertFalse(DeepCaveCull.culled(rock, 0, 3, 0));
        assertFalse(DeepCaveCull.culled(rock, 0, 4, 0));

        DeepCaveCull.Darkness litBeside = (cx, sy, cz) -> !(cx == 1 && sy == 2);
        assertFalse(DeepCaveCull.culled(litBeside, 0, 2, 0));
        assertTrue(DeepCaveCull.culled(litBeside, -1, 2, 0));
    }

    @Test
    void columnsBesideTheCameraKeepEverything() {
        assertTrue(DeepCaveCull.near(4, -4, 0, 0));
        assertFalse(DeepCaveCull.near(5, 0, 0, 0));
        assertFalse(DeepCaveCull.near(0, 0, Integer.MIN_VALUE, Integer.MIN_VALUE));
    }

    @Test
    void goingUndergroundFlipsSoonAndComingUpLate() {
        DeepCaveCull cull = new DeepCaveCull();
        assertTrue(cull.active());
        int frames = 1;
        while (!cull.updateUnderground(true)) frames++;
        assertTrue(cull.underground());
        assertFalse(cull.active());
        assertTrue(frames < 30, "went underground after " + frames + " frames");

        // A frame back in the open does not count toward coming up.
        for (int i = 0; i < 100; i++) assertFalse(cull.updateUnderground(false));
        assertFalse(cull.updateUnderground(true));
        frames = 1;
        while (!cull.updateUnderground(false)) frames++;
        assertTrue(frames > 100, "came up after " + frames + " frames");
        assertTrue(cull.active());
    }
}

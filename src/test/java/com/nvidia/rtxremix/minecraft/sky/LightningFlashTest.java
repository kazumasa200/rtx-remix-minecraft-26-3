/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LightningFlashTest {
    private static void frame(double distance) {
        LightningFlash.begin();
        LightningFlash.offer(0.0, 64.0, 0.0, distance * distance);
        LightningFlash.end();
    }

    @Test
    void theFlashOverTheLandWeakensWithTheBoltsDistance() {
        frame(0.0);
        assertEquals(1.0f, LightningFlash.reach(), 1.0e-6f);
        assertEquals(64.0, LightningFlash.y());
        frame(128.0);
        assertEquals(0.5f, LightningFlash.reach(), 1.0e-6f);
        frame(1280.0);
        assertTrue(LightningFlash.reach() < 0.01f);
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WaterMarkerTest {

    @Test
    void theMarkedColourAbsorbsAsThePlayersDistanceDoes() {
        for (float distance : new float[] {0.5f, 3f, 8f, 40f}) {
            for (float channel : new float[] {0.2f, 0.75f, 0.95f}) {
                float marked = BlockTextureManager.markedTransmittance(channel, distance);
                for (float path : new float[] {0.25f, 1f, 6f, 20f}) {
                    double wanted = Math.pow(channel, path / distance);
                    double drawn = Math.pow(marked, path / BlockTextureManager.WATER_MARKER_DISTANCE);
                    assertEquals(wanted, drawn, 1e-4 * Math.max(wanted, 1e-3),
                            "distance " + distance + ", channel " + channel + ", path " + path);
                }
            }
        }
    }
}

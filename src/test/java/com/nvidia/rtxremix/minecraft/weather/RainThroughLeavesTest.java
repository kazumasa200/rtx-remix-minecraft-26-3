/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.weather;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RainThroughLeavesTest {
    @Test
    void aboutOneColumnInFourPassesWithoutRows() {
        int passing = 0;
        int longestRun = 0;
        for (int x = -64; x < 64; x++) {
            int run = 0;
            for (int z = -64; z < 64; z++) {
                if (RainThroughLeaves.passes(x, z)) {
                    passing++;
                    run++;
                    longestRun = Math.max(longestRun, run);
                } else {
                    run = 0;
                }
            }
        }
        double share = passing / (128.0 * 128.0);
        assertTrue(share > 0.22 && share < 0.28, "share " + share);
        assertTrue(longestRun < 12, "a straight run of " + longestRun + " columns would show as a sheet");
    }
}

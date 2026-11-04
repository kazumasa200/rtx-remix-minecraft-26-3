/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParticleSootColorTest {
    @Test void explicitSootUsesVanillaWorkersGreyAndKeepsEveryAlphaValue() {
        int grey = (int) (ParticleTint.SOOT_GREY * 255f);
        for (int alpha = 0; alpha < 256; alpha++) {
            assertEquals(alpha << 24 | grey << 16 | grey << 8 | grey,
                    ParticleTint.sootColor(alpha << 24 | 0xabcdef));
        }
    }
}

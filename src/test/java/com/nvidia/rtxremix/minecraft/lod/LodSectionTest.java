/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LodSectionTest {

    @Test
    void aCellIsOneBlockAtTheFinestDetailAndDoublesWithEachLevel() {
        assertEquals(1, new LodSection(0, 0, 0, 64).blocksPerCell());
        assertEquals(2, new LodSection(0, 0, 0, 128).blocksPerCell());
        assertEquals(16, new LodSection(0, 0, 0, 1024).blocksPerCell());
    }
}

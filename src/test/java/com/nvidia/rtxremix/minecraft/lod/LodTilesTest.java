/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LodTilesTest {

    @Test
    void aTileIsScaledByItsBrightestFactorSoTheVertexColourCarriesIt() {
        byte[] tile = new byte[16 * 16 * 4];
        Arrays.fill(tile, (byte) 128);
        for (int i = 3; i < tile.length; i += 4) tile[i] = (byte) 255;
        assertEquals(1f, LodTiles.scale(tile), 0.01f, "the untextured tile needs no scale");

        tile[0] = (byte) 191; // one texel's red half again as bright as the face's average
        assertEquals(191 / 127.5f, LodTiles.scale(tile), 1e-4f);

        tile[3] = 0; // a clear texel shows the plain average, whatever its ratio
        assertEquals(1f, LodTiles.factor(tile, 0, 0), 1e-6f);
        assertEquals(1f, LodTiles.scale(tile), 0.01f);
    }

    @Test
    void aScaledColourBrightensEachChannelAndStopsAtWhite() {
        assertEquals(0xFF609000 | 0xFF, LodTiles.scaled(0xFF406000 | 0xAA, 1.5f));
        assertEquals(0xFF123456, LodTiles.scaled(0xFF123456, 1f));
    }

    @Test
    void aTileOutsideWhatWasUploadedReadsTheUntexturedTile() {
        float[] scales = {1f, 1.2f};
        assertEquals(1, LodTiles.usable(1, scales));
        assertEquals(0, LodTiles.usable(2, scales));
        assertEquals(0, LodTiles.usable(-1, scales));
    }
}

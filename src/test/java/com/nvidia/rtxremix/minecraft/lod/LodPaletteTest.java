/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodPaletteTest {

    @Test
    void aColoursCoordinatesLandOnATexelOfNearlyThatColour() {
        ByteBuffer pixels = LodPalette.fill(ByteBuffer.allocate(LodPalette.WIDTH * LodPalette.HEIGHT * 4));
        for (int rgb : new int[] {0x000000, 0xFFFFFF, 0xD06010, 0x20A0F0, 0x7F7F80}) {
            int x = (int) (LodPalette.u(rgb) * LodPalette.WIDTH);
            int y = (int) (LodPalette.v(rgb) * LodPalette.HEIGHT);
            int at = (y * LodPalette.WIDTH + x) * 4;
            for (int c = 0; c < 3; c++) {
                int want = rgb >> (16 - 8 * c) & 0xFF;
                int got = pixels.get(at + c) & 0xFF;
                assertTrue(Math.abs(want - got) <= 4, String.format("%06X channel %d: %d", rgb, c, got));
            }
            assertEquals(-1, pixels.get(at + 3));
        }
    }

    @Test
    void glassKeepsItsHueAndLetsHalfTheRestThrough() {
        assertEquals(0xFFFFFF, LodPalette.glassTint(0xB0B0B0), "grey clear glass is clear");
        int red = LodPalette.glassTint(0xC03030);
        assertEquals(0xFF, red >> 16);
        assertEquals(red >> 8 & 0xFF, red & 0xFF);
        assertTrue((red & 0xFF) > 0x80 && (red & 0xFF) < 0xC0, Integer.toHexString(red));
        assertEquals(0x808080, LodPalette.glassTint(0));
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterNormalsTest {

    @Test
    void sheetFramesHalveInSheetOrder() {
        // A 12 x 10 sheet of 4-texel frames: red is the frame, green its texel's row and column.
        int frameSize = 4;
        int width = 12 * frameSize, height = 10 * frameSize;
        ByteBuffer sheet = ByteBuffer.allocate(width * height * 2);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int frame = (y / frameSize) * 12 + x / frameSize;
                sheet.put((y * width + x) * 2, (byte) frame);
                sheet.put((y * width + x) * 2 + 1, (byte) ((y % frameSize) * 40 + (x % frameSize) * 10));
            }
        }
        byte[][] frames = WaterNormals.halveFrames(sheet, frameSize);
        assertEquals(120, frames.length);
        assertEquals(2 * 2 * 2, frames[0].length);
        assertEquals(37, frames[37][0] & 0xFF);
        assertEquals(119, frames[119][6] & 0xFF);
        // The mean of rows 0 and 1, columns 0 and 1: (0 + 10 + 40 + 50) / 4.
        assertEquals(25, frames[37][1] & 0xFF);
        // Rows 2 and 3, columns 2 and 3: (100 + 110 + 140 + 150) / 4.
        assertEquals(125, frames[37][7] & 0xFF);
    }

    private static final int FRAME_TEXELS = 512 * 512;

    @Test
    void highPassingTakesAFramesEvenTiltAway() {
        byte[][] frames = {constantFrame(150, 128)};
        WaterNormals.highPass(frames);
        for (int at = 0; at < frames[0].length; at += 2 * 991) {
            assertEquals(0, frames[0][at], 1);
            assertEquals(0, frames[0][at + 1], 1);
        }
    }

    @Test
    void calmFramesWithoutRainGiveCalmWater() {
        // High-passed frames hold slopes as signed bytes.
        byte[][] frames = {constantFrame(0, 0), constantFrame(0, 0), constantFrame(0, 0)};
        byte[] rgba = new byte[WaterNormals.SIZE * WaterNormals.SIZE * 4];
        WaterNormals.compose(frames, 0, null, 0f, rgba);
        for (int at = 0; at < rgba.length; at += 4 * 997) {
            assertEquals(128, rgba[at] & 0xFF, 1);
            assertEquals(128, rgba[at + 1] & 0xFF, 1);
            assertEquals(0, rgba[at + 2]);
            assertEquals(255, rgba[at + 3] & 0xFF);
        }
    }

    @Test
    void theWavesDoNotRepeatAFlipbookFrameAcross() {
        SplittableRandom random = new SplittableRandom(3L);
        byte[][] frames = new byte[120][];
        for (int f = 0; f < frames.length; f++) {
            frames[f] = new byte[FRAME_TEXELS * 2];
            for (int i = 0; i < frames[f].length; i++) {
                frames[f][i] = (byte) (random.nextInt(21) - 10);
            }
        }
        byte[] rgba = new byte[WaterNormals.SIZE * WaterNormals.SIZE * 4];
        WaterNormals.compose(frames, 5, null, 0f, rgba);
        // A flipbook frame spans a quarter of the texture; laid alone it repeated there.
        int quarter = WaterNormals.SIZE / 4;
        int same = 0;
        int checked = 0;
        for (int j = 0; j < WaterNormals.SIZE; j += 7) {
            for (int i = 0; i < WaterNormals.SIZE - quarter; i += 7) {
                int a = (j * WaterNormals.SIZE + i) * 4;
                int b = (j * WaterNormals.SIZE + i + quarter) * 4;
                if (rgba[a] == rgba[b] && rgba[a + 1] == rgba[b + 1]) {
                    same++;
                }
                checked++;
            }
        }
        assertTrue(same < checked / 4, "texels equal a frame across: " + same + " of " + checked);
    }

    @Test
    void ringsTiltCalmWaterAndAddToWaves() {
        byte[][] calm = {constantFrame(0, 0)};
        // Rings stay octahedral.
        byte[] ring = constantFrame(160, 128);
        byte[] rgba = new byte[WaterNormals.SIZE * WaterNormals.SIZE * 4];
        WaterNormals.compose(calm, 0, ring, 1f, rgba);
        assertTrue((rgba[0] & 0xFF) > 150, "a ring tilts calm water: " + (rgba[0] & 0xFF));
        WaterNormals.compose(calm, 0, ring, 0.25f, rgba);
        int light = rgba[0] & 0xFF;
        assertTrue(light > 128 && light < 150, "light rain tilts it less: " + light);
        byte[][] wave = {constantFrame(40, 0)};
        WaterNormals.compose(wave, 0, null, 0f, rgba);
        int still = rgba[0] & 0xFF;
        WaterNormals.compose(wave, 0, ring, 1f, rgba);
        assertTrue((rgba[0] & 0xFF) > still, "a ring adds to a wave's own slope: " + (rgba[0] & 0xFF) + " over " + still);
    }

    @Test
    void puddlesHoldStillWithoutRainAndRippleWithIt() {
        byte[] rgba = new byte[FRAME_TEXELS * 4];
        WaterNormals.composeRings(null, 0f, rgba);
        for (int at = 0; at < rgba.length; at += 4 * 997) {
            assertEquals(128, rgba[at] & 0xFF, 1);
            assertEquals(128, rgba[at + 1] & 0xFF, 1);
        }
        WaterNormals.composeRings(constantFrame(160, 128), 1f, rgba);
        assertTrue((rgba[0] & 0xFF) > 150, "a ring tilts a puddle: " + (rgba[0] & 0xFF));
    }

    private static byte[] constantFrame(int red, int green) {
        byte[] frame = new byte[FRAME_TEXELS * 2];
        for (int i = 0; i < FRAME_TEXELS; i++) {
            frame[i * 2] = (byte) red;
            frame[i * 2 + 1] = (byte) green;
        }
        return frame;
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import java.nio.ByteBuffer;

/**
 * A texture holding every colour at five bits a channel, so a quad can carry its
 * colour as a texture coordinate. The runtime's emission and a translucent
 * surface's transmittance come from textures, never from the vertex colour the
 * LOD mod bakes, so glowing and glass LOD terrain read their colour here.
 */
final class LodPalette {

    static final int WIDTH = 256;
    static final int HEIGHT = 128;
    private static final int LEVELS = 32;

    private LodPalette() {}

    /** The RGBA8 texels, one per colour; the caller owns the buffer. */
    static ByteBuffer fill(ByteBuffer pixels) {
        for (int index = 0; index < WIDTH * HEIGHT; index++) {
            int at = index * 4;
            pixels.put(at, level(index >> 10));
            pixels.put(at + 1, level(index >> 5));
            pixels.put(at + 2, level(index));
            pixels.put(at + 3, (byte) -1);
        }
        return pixels.limit(WIDTH * HEIGHT * 4);
    }

    /** The texture coordinates of the texel nearest an RGB colour, at its centre. */
    static float u(int rgb) {
        return ((index(rgb) & (WIDTH - 1)) + 0.5f) / WIDTH;
    }

    static float v(int rgb) {
        return ((index(rgb) / WIDTH) + 0.5f) / HEIGHT;
    }

    /**
     * What a glass face lets through: the block's hue at full brightness, half way
     * to clear. The LOD mod's colour for clear glass averages its frame with the
     * grey behind its clear texels, which read as transmittance would turn the
     * glass dark grey.
     */
    static int glassTint(int rgb) {
        int r = rgb >> 16 & 0xFF;
        int g = rgb >> 8 & 0xFF;
        int b = rgb & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        if (max == 0) return 0x808080;
        return tint(r, max) << 16 | tint(g, max) << 8 | tint(b, max);
    }

    private static int tint(int channel, int max) {
        return 255 - (255 - channel * 255 / max) / 2;
    }

    private static int index(int rgb) {
        return quantize(rgb >> 16) << 10 | quantize(rgb >> 8) << 5 | quantize(rgb);
    }

    private static int quantize(int channel) {
        return ((channel & 0xFF) * (LEVELS - 1) + 127) / 255;
    }

    private static byte level(int bits) {
        return (byte) (((bits & (LEVELS - 1)) * 255 + (LEVELS - 1) / 2) / (LEVELS - 1));
    }
}

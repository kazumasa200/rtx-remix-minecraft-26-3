/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.weather;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Where the rain vanilla draws stops in a column. Vanilla stops every column at the first
 * block that blocks motion, leaves included, so no rain fell under a tree; one leaf-topped
 * column in four lets it fall on through the canopy to the ground, as rain drips through
 * leaves. Which columns pass is a function of the position, so it holds still while the
 * camera moves and players agree.
 */
public final class RainThroughLeaves {

    private RainThroughLeaves() {}

    /** The height the rain in column (x, z) falls to, given vanilla's motion-blocking top. */
    public static int columnTop(ClientLevel level, int x, int z, int motionBlocking) {
        if (!passes(x, z)) {
            return motionBlocking;
        }
        return Math.min(motionBlocking, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
    }

    /** Whether the rain in column (x, z) passes through leaves: one column in four. */
    static boolean passes(int x, int z) {
        int h = x * 0x9E3779B1 ^ z * 0x85EBCA77;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return (h & 3) == 0;
    }
}

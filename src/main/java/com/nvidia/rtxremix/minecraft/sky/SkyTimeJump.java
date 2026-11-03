/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

/**
 * Counts jumps in the time of day, as a command, sleeping or a camera path's time makes them,
 * which {@code LightManager} detects. What follows the sky over frames, the clouds' panorama
 * and the lens's measurements of the image, starts over at a jump: easing from noon into
 * midnight, the clouds showed orange and blue for a couple of seconds and stayed white for
 * ten. Render thread only.
 */
public final class SkyTimeJump {

    private static int generation;

    private SkyTimeJump() {}

    /** Records a jump in the time of day this frame. */
    public static void mark() {
        generation++;
    }

    /** Changes at every jump; a reader compares it with the value it last saw. */
    public static int generation() {
        return generation;
    }
}

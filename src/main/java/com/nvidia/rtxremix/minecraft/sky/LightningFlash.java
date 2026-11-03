/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

/**
 * The nearest lightning bolt's flash, for the clouds it lights: where it struck, and how
 * strongly it flashes now, 1 while the bolt shows and easing out over {@link #FADE_SECONDS} once
 * it goes, so the clouds do not cut off with the bolt. The entity light pass offers the bolts it
 * meets each frame (`LightManager.updateEntityLights`). Render thread only.
 *
 * <p>The flash over the whole land and sky ({@link #reach}) weakens with the bolt's distance.
 */
public final class LightningFlash {

    /** Seconds over which the flash eases out after the bolt goes. */
    private static final float FADE_SECONDS = 0.12f;
    /** Strength below which the flash counts as gone. */
    private static final float GONE = 0.01f;
    /** Blocks from the camera within which a bolt flashes over the land and sky at full strength. */
    private static final double REACH_BLOCKS = 128.0;

    private static double x;
    private static double y;
    private static double z;
    private static double nearest;
    private static boolean seen;
    private static double seenDistanceSquared = Double.MAX_VALUE;
    private static float strength;
    private static long lastNanos;
    /** Whether the strength changed this frame, including the frame it reached zero. */
    private static boolean changing;

    private LightningFlash() {}

    /** Starts a frame's search for the nearest bolt. */
    public static void begin() {
        seen = false;
        nearest = Double.MAX_VALUE;
    }

    /** Offers a bolt where it struck, kept if it is the nearest to the camera so far. */
    public static void offer(double boltX, double boltY, double boltZ, double distanceSquared) {
        if (distanceSquared < nearest) {
            nearest = distanceSquared;
            x = boltX;
            y = boltY;
            z = boltZ;
            seen = true;
        }
    }

    /** Ends the frame: full strength while a bolt shows, easing out after. */
    public static void end() {
        long now = System.nanoTime();
        float seconds = lastNanos == 0L ? 0.0f : Math.min(0.25f, (now - lastNanos) / 1.0e9f);
        lastNanos = now;
        float previous = strength;
        if (seen) {
            strength = 1.0f;
            seenDistanceSquared = nearest;
        } else if (strength > 0.0f) {
            strength *= (float) Math.exp(-seconds / FADE_SECONDS);
            if (strength < GONE) {
                strength = 0.0f;
            }
        }
        changing = strength != previous || strength > 0.0f;
    }

    /** How strongly the flash shows, 0 to 1. */
    public static float strength() {
        return strength;
    }

    /**
     * How strongly the flash lights the whole land and sky, 0 to 1: the strength, less with the
     * bolt's distance beyond {@link #REACH_BLOCKS}.
     */
    public static float reach() {
        return strength <= 0.0f ? 0.0f
                : (float) (strength / (1.0 + seenDistanceSquared / (REACH_BLOCKS * REACH_BLOCKS)));
    }

    /**
     * Whether the flash shows or has just gone: the clouds then take each frame as it is,
     * without their history, which would make the light lag and linger.
     */
    public static boolean changing() {
        return changing;
    }

    public static double x() {
        return x;
    }

    public static double y() {
        return y;
    }

    public static double z() {
        return z;
    }
}

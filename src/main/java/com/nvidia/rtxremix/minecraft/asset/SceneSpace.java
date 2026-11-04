/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

/**
 * The coordinates the mod hands to Remix (#22). The runtime traces rays in float32, so far
 * from world zero its positions lost precision: at x = 40 000 a step is 0.004 blocks, and rays
 * slipped past the ground under leaf litter, overlays fought their faces and the arm shook.
 * Scene space is world space less an origin on a {@link #SNAP} grid that follows the camera
 * horizontally; y is never shifted.
 *
 * <p>A position re-sent every frame goes through {@link #x}, {@link #y}, {@link #z} on its world
 * double. A retained instance takes its world translation through
 * {@link RemixInstance#setWorldTranslation} and follows the origin by itself. Everything else
 * (culling, raycasts, the lens, flare sources, texture coordinates) stays in world space.
 *
 * <p>Render thread only, except {@link Origin}, which is immutable and travels to workers.
 */
public final class SceneSpace {
    /** A power of two, so an origin move shifts floats by a whole number and loses nothing. */
    static final int SNAP = 4096;
    /**
     * How far the camera may get from the origin, on either axis, before it moves. Scene
     * positions near the camera stay within this, where a float step is 0.0005 blocks, as fine
     * as a player six thousand blocks from spawn has always had. Within it of world zero the
     * origin is zero, so near spawn nothing changes and nothing ever moves.
     */
    public static final int REBASE_DISTANCE = SNAP + SNAP / 2;
    /**
     * The Debug page's offset: a position that skipped the conversion lands this far away, out
     * of view, while every converted one stays within what a real move gives.
     */
    static final int DEBUG_OFFSET = SNAP;

    /** An origin in whole blocks, and a count that changes whenever the origin does. */
    public record Origin(int x, int z, long epoch, boolean debug) {
        public float x(double worldX) {
            return (float) (worldX - x);
        }

        public float y(double worldY) {
            return (float) worldY;
        }

        public float z(double worldZ) {
            return (float) (worldZ - z);
        }

        public double worldX(float sceneX) {
            return sceneX + (double) x;
        }

        public double worldZ(float sceneZ) {
            return sceneZ + (double) z;
        }
    }

    private static volatile Origin current = new Origin(0, 0, 0, false);
    private static volatile boolean debugOffset;

    private SceneSpace() {
    }

    public static Origin current() {
        return current;
    }

    public static long epoch() {
        return current.epoch;
    }

    public static float x(double worldX) {
        return current.x(worldX);
    }

    public static float y(double worldY) {
        return (float) worldY;
    }

    public static float z(double worldZ) {
        return current.z(worldZ);
    }

    /**
     * Moves the origin if the camera calls for it. Once a frame, before anything is captured,
     * so every position of a frame is taken against one origin.
     */
    public static void advance(double cameraX, double cameraZ) {
        current = next(current, cameraX, cameraZ, debugOffset);
    }

    public static boolean debugOffset() {
        return debugOffset;
    }

    /** Takes effect at the next {@link #advance}, which then moves the origin wherever the camera is. */
    public static void setDebugOffset(boolean on) {
        debugOffset = on;
    }

    /** The origin the camera calls for: {@code now} itself while it stays. */
    static Origin next(Origin now, double cameraX, double cameraZ, boolean debug) {
        int bias = debug ? DEBUG_OFFSET : 0;
        int baseX = now.x - (now.debug ? DEBUG_OFFSET : 0);
        int baseZ = now.z - (now.debug ? DEBUG_OFFSET : 0);
        if (now.debug == debug
                && Math.max(Math.abs(cameraX - baseX), Math.abs(cameraZ - baseZ)) <= REBASE_DISTANCE) {
            return now;
        }
        boolean nearSpawn = Math.max(Math.abs(cameraX), Math.abs(cameraZ)) <= REBASE_DISTANCE;
        int x = nearSpawn ? 0 : snap(cameraX);
        int z = nearSpawn ? 0 : snap(cameraZ);
        return new Origin(x + bias, z + bias, now.epoch + 1, debug);
    }

    private static int snap(double coordinate) {
        return (int) Math.round(coordinate / SNAP) * SNAP;
    }
}

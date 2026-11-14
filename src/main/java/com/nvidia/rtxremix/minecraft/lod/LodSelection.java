/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import java.util.function.LongConsumer;

/**
 * The LOD sections to draw around the camera, where this mod chooses them rather than the LOD
 * mod: a quadtree whose section of detail {@code d} is {@link #CELLS} cells of {@code 2^d}
 * blocks across, as the LOD mod's own are. A section splits into its four children while the
 * camera is nearer it than {@link #SPLIT} times its width, so at a distance {@code r} a cell is
 * more than {@code r / (2 * SPLIT * CELLS)} and at most {@code r / (SPLIT * CELLS)} blocks
 * across: 4 blocks at a kilometre.
 */
final class LodSelection {

    /** Cells across a section, at every detail. */
    static final int CELLS = 64;
    /** A section splits while the camera is nearer it than this many of its widths. */
    static final int SPLIT = 3;
    /** The coarsest detail: sections 32768 blocks across, whose block offsets still fit 16 bits. */
    static final int MAX_DETAIL = 9;

    private static final int COORD_BITS = 27;
    private static final long COORD_MASK = (1L << COORD_BITS) - 1;

    private LodSelection() {}

    /** Blocks across a section of a detail. */
    static int width(int detail) {
        return CELLS << detail;
    }

    /** Packs a section's detail and its position in widths of that detail. */
    static long key(int detail, int ix, int iz) {
        return (long) detail << (2 * COORD_BITS) | (ix & COORD_MASK) << COORD_BITS | iz & COORD_MASK;
    }

    static int detail(long key) {
        return (int) (key >>> (2 * COORD_BITS));
    }

    static int ix(long key) {
        return (int) ((key >> COORD_BITS & COORD_MASK) << (64 - COORD_BITS) >> (64 - COORD_BITS));
    }

    static int iz(long key) {
        return (int) ((key & COORD_MASK) << (64 - COORD_BITS) >> (64 - COORD_BITS));
    }

    static int minX(long key) {
        return ix(key) * width(detail(key));
    }

    static int minZ(long key) {
        return iz(key) * width(detail(key));
    }

    /** Whether two sections' footprints overlap. */
    static boolean overlaps(long a, long b) {
        int wa = width(detail(a));
        int wb = width(detail(b));
        int ax = minX(a);
        int az = minZ(a);
        int bx = minX(b);
        int bz = minZ(b);
        return ax < bx + wb && bx < ax + wa && az < bz + wb && bz < az + wa;
    }

    /** The coarsest detail a selection of this radius needs: its sections are no wider than the radius. */
    static int rootDetail(int radius) {
        int detail = 0;
        while (detail < MAX_DETAIL && width(detail + 1) <= radius) detail++;
        return detail;
    }

    /**
     * Hands {@code out} every section to draw: no part farther than {@code radius} blocks from
     * the camera, and none wholly within {@code innerHalfWidth} blocks of the camera on both axes,
     * where Minecraft's own terrain is drawn.
     */
    static void select(double cameraX, double cameraZ, int radius, int innerHalfWidth, LongConsumer out) {
        int root = rootDetail(radius);
        int width = width(root);
        int x0 = Math.floorDiv((int) Math.floor(cameraX) - radius, width);
        int x1 = Math.floorDiv((int) Math.floor(cameraX) + radius, width);
        int z0 = Math.floorDiv((int) Math.floor(cameraZ) - radius, width);
        int z1 = Math.floorDiv((int) Math.floor(cameraZ) + radius, width);
        for (int ix = x0; ix <= x1; ix++) {
            for (int iz = z0; iz <= z1; iz++) {
                visit(root, ix, iz, cameraX, cameraZ, radius, innerHalfWidth, out);
            }
        }
    }

    private static void visit(int detail, int ix, int iz, double cameraX, double cameraZ,
                              int radius, int innerHalfWidth, LongConsumer out) {
        int width = width(detail);
        int minX = ix * width;
        int minZ = iz * width;
        double distance = distance(minX, minZ, width, cameraX, cameraZ);
        if (distance > radius) return;
        if (within(minX, minZ, width, cameraX, cameraZ, innerHalfWidth)) return;
        if (detail > 0 && distance < (double) SPLIT * width) {
            for (int child = 0; child < 4; child++) {
                visit(detail - 1, 2 * ix + (child & 1), 2 * iz + (child >> 1),
                        cameraX, cameraZ, radius, innerHalfWidth, out);
            }
            return;
        }
        out.accept(key(detail, ix, iz));
    }

    /** Blocks across the ground from a point to the nearest edge of a square, 0 inside it. */
    static double distance(int minX, int minZ, int width, double x, double z) {
        double dx = Math.max(0.0, Math.max(minX - x, x - (minX + width)));
        double dz = Math.max(0.0, Math.max(minZ - z, z - (minZ + width)));
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static boolean within(int minX, int minZ, int width, double x, double z, int halfWidth) {
        if (halfWidth <= 0) return false;
        return minX >= x - halfWidth && minX + width <= x + halfWidth
                && minZ >= z - halfWidth && minZ + width <= z + halfWidth;
    }
}

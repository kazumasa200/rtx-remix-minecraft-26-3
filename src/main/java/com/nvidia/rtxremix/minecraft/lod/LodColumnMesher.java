/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Turns a section's terrain columns into quads in the LOD mod's own vertex layout, the one
 * {@link LodMeshBuilder} reads, for sections this mod builds itself from the LOD mod's public
 * data rather than copies from its renderer.
 *
 * <p>A section is {@link LodSelection#CELLS} cells across, each one column of vertical runs of
 * one block. A run's top and bottom are drawn where the run against them does not hide them,
 * and each side where the neighbouring cell's runs do not, so the ground of a section is one
 * shell. The cells just outside the section are read too, so a section's edge hides the sides
 * its neighbours cover.
 */
final class LodColumnMesher {

    /** Drawn in the opaque buffer and hides what lies behind it. */
    static final byte SOLID = 0;
    /** Glass and ice: drawn in the translucent buffer, hides only solid and its own kind behind. */
    static final byte TRANSLUCENT = 1;
    /** Water: drawn where it meets neither solid ground nor water. */
    static final byte WATER = 2;
    /**
     * Air the sky's light does not reach, a cave's or a room's: never drawn, and it hides every
     * face that looks into it, so of the ground only the shell seen from outside is drawn.
     * Without it a section held every cave wall under it, gigabytes over a city.
     */
    static final byte DARK = 3;

    private static final int DOWN = 0;
    private static final int UP = 1;
    private static final int NORTH = 2;
    private static final int SOUTH = 3;
    private static final int WEST = 4;
    private static final int EAST = 5;

    private LodColumnMesher() {}

    /**
     * The runs of one section's cells and the ring of cells around it. Fill a cell with
     * {@link #begin} and then its runs with {@link #add}, in any order of cells.
     */
    static final class Columns {
        static final int SPAN = LodSelection.CELLS + 2;
        private final int[] start = new int[SPAN * SPAN];
        private final int[] count = new int[SPAN * SPAN];
        int[] y0 = new int[4096];
        int[] y1 = new int[4096];
        int[] up = new int[4096];
        int[] side = new int[4096];
        int[] down = new int[4096];
        byte[] material = new byte[4096];
        byte[] kind = new byte[4096];
        int runs;
        private int cell = -1;

        /** Starts a cell; {@code cx} and {@code cz} run from -1 to {@link LodSelection#CELLS}. */
        void begin(int cx, int cz) {
            cell = index(cx, cz);
            start[cell] = runs;
            count[cell] = 0;
        }

        /**
         * Adds a run of the cell begun last, from {@code bottom} up to {@code top} in blocks
         * above the level's floor, with the colours of its top, sides and bottom.
         */
        void add(int bottom, int top, int upColor, int sideColor, int downColor, int dhMaterial, byte runKind) {
            if (top <= bottom) return;
            if (runs == y0.length) grow();
            y0[runs] = bottom;
            y1[runs] = top;
            up[runs] = upColor;
            side[runs] = sideColor;
            down[runs] = downColor;
            material[runs] = (byte) dhMaterial;
            kind[runs] = runKind;
            runs++;
            count[cell]++;
        }

        /** Empties every cell. */
        void clear() {
            Arrays.fill(count, 0);
            runs = 0;
            cell = -1;
        }

        static int index(int cx, int cz) {
            return (cz + 1) * SPAN + cx + 1;
        }

        int start(int index) {
            return start[index];
        }

        int count(int index) {
            return count[index];
        }

        /** Sorts each cell's runs from the bottom up. */
        void sort() {
            for (int c = 0; c < SPAN * SPAN; c++) {
                int s = start[c];
                int n = count[c];
                for (int i = s + 1; i < s + n; i++) {
                    for (int j = i; j > s && y0[j - 1] > y0[j]; j--) swap(j - 1, j);
                }
            }
        }

        private void swap(int a, int b) {
            int t = y0[a]; y0[a] = y0[b]; y0[b] = t;
            t = y1[a]; y1[a] = y1[b]; y1[b] = t;
            t = up[a]; up[a] = up[b]; up[b] = t;
            t = side[a]; side[a] = side[b]; side[b] = t;
            t = down[a]; down[a] = down[b]; down[b] = t;
            byte m = material[a]; material[a] = material[b]; material[b] = m;
            byte k = kind[a]; kind[a] = kind[b]; kind[b] = k;
        }

        private void grow() {
            int n = y0.length * 2;
            y0 = Arrays.copyOf(y0, n);
            y1 = Arrays.copyOf(y1, n);
            up = Arrays.copyOf(up, n);
            side = Arrays.copyOf(side, n);
            down = Arrays.copyOf(down, n);
            material = Arrays.copyOf(material, n);
            kind = Arrays.copyOf(kind, n);
        }
    }

    /** Growable quad data in the LOD mod's 16-byte vertex layout, from {@link DirectBufferPool}. */
    static final class Quads {
        ByteBuffer data;
        int quads;

        void quad(int face, int x0, int x1, int y0, int y1, int z0, int z1, int argb, int dhMaterial) {
            int needed = (quads + 1) * LodMeshBuilder.DH_QUAD_BYTES;
            if (data == null || data.capacity() < needed) {
                ByteBuffer grown = DirectBufferPool.acquire(Math.max(needed, data == null ? 16 * 1024 : data.capacity() * 2))
                        .order(ByteOrder.LITTLE_ENDIAN);
                if (data != null) {
                    grown.put(0, data, 0, quads * LodMeshBuilder.DH_QUAD_BYTES);
                    DirectBufferPool.release(data);
                }
                data = grown;
            }
            int at = quads * LodMeshBuilder.DH_QUAD_BYTES;
            // The builder reads a quad's extent from its corners' bounds; their order does not matter.
            vertex(at, x0, y0, z0, argb, dhMaterial, face);
            vertex(at + 16, x1, y1, z1, argb, dhMaterial, face);
            vertex(at + 32, x0, y1, z1, argb, dhMaterial, face);
            vertex(at + 48, x1, y0, z0, argb, dhMaterial, face);
            quads++;
        }

        private void vertex(int at, int x, int y, int z, int argb, int dhMaterial, int face) {
            data.putShort(at, (short) x);
            data.putShort(at + 2, (short) Math.max(0, Math.min(0xFFFF, y)));
            data.putShort(at + 4, (short) z);
            data.putShort(at + 6, (short) 0);
            data.put(at + 8, (byte) (argb >> 16));
            data.put(at + 9, (byte) (argb >> 8));
            data.put(at + 10, (byte) argb);
            data.put(at + 11, (byte) (argb >>> 24));
            data.put(at + 12, (byte) dhMaterial);
            data.put(at + 13, (byte) face);
            data.putShort(at + 14, (short) 0);
        }

        /** The quads written, as a buffer positioned at 0; still owned by this. */
        ByteBuffer view() {
            if (data == null) return ByteBuffer.allocate(0);
            return data.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(0).limit(quads * LodMeshBuilder.DH_QUAD_BYTES);
        }

        void clear() {
            quads = 0;
        }

        void release() {
            DirectBufferPool.release(data);
            data = null;
            quads = 0;
        }
    }

    /**
     * Writes the section's quads, its opaque ones and water to {@code opaque} and its glass to
     * {@code translucent}. A cell is {@code cellSize} blocks across; positions are blocks from
     * the section's minimum corner, heights from the level's floor. The tops of neighbouring
     * cells along x that match are drawn as one quad.
     */
    static void mesh(Columns columns, int cellSize, Quads opaque, Quads translucent) {
        columns.sort();
        int cells = LodSelection.CELLS;
        for (int cz = 0; cz < cells; cz++) {
            int z0 = cz * cellSize;
            int z1 = z0 + cellSize;
            // The strip of matching tops along x not yet drawn: its run and its first cell.
            int strip = -1;
            int stripStart = 0;
            for (int cx = 0; cx < cells; cx++) {
                int c = Columns.index(cx, cz);
                int s = columns.start(c);
                int n = columns.count(c);
                int x0 = cx * cellSize;
                int x1 = x0 + cellSize;
                int highest = -1;
                for (int i = s; i < s + n; i++) {
                    if (columns.kind[i] == DARK) continue;
                    Quads to = columns.kind[i] == TRANSLUCENT ? translucent : opaque;
                    side(columns, i, Columns.index(cx, cz - 1), NORTH, x0, x1, z0, z0, to);
                    side(columns, i, Columns.index(cx, cz + 1), SOUTH, x0, x1, z1, z1, to);
                    side(columns, i, Columns.index(cx - 1, cz), WEST, x0, x0, z0, z1, to);
                    side(columns, i, Columns.index(cx + 1, cz), EAST, x1, x1, z0, z1, to);
                    if (columns.y0[i] > 0 && !hiddenBelow(columns, i, s)) {
                        to.quad(DOWN, x0, x1, columns.y0[i], columns.y0[i], z0, z1, columns.down[i], columns.material[i]);
                    }
                    if (hiddenAbove(columns, i, s, n)) continue;
                    if (i == s + n - 1) {
                        highest = i;
                    } else {
                        top(columns, i, x0, x1, z0, z1, opaque, translucent);
                    }
                }
                if (strip >= 0 && (highest < 0 || !sameTop(columns, strip, highest))) {
                    top(columns, strip, stripStart * cellSize, x0, z0, z1, opaque, translucent);
                    strip = -1;
                }
                if (highest >= 0 && strip < 0) {
                    strip = highest;
                    stripStart = cx;
                }
            }
            if (strip >= 0) top(columns, strip, stripStart * cellSize, cells * cellSize, z0, z1, opaque, translucent);
        }
    }

    private static boolean sameTop(Columns c, int a, int b) {
        return c.y1[a] == c.y1[b] && c.up[a] == c.up[b] && c.material[a] == c.material[b] && c.kind[a] == c.kind[b];
    }

    private static void top(Columns c, int run, int x0, int x1, int z0, int z1, Quads opaque, Quads translucent) {
        Quads to = c.kind[run] == TRANSLUCENT ? translucent : opaque;
        to.quad(UP, x0, x1, c.y1[run], c.y1[run], z0, z1, c.up[run], c.material[run]);
    }

    /** Whether a run's kind hides a face of {@code face} kind behind it. */
    static boolean hides(byte occluder, byte face) {
        if (occluder == DARK) return true;
        return switch (face) {
            case SOLID -> occluder == SOLID;
            case TRANSLUCENT -> occluder == SOLID || occluder == TRANSLUCENT;
            default -> occluder == SOLID || occluder == WATER;
        };
    }

    /** Whether the run just above run {@code i} in its cell covers its top and hides it. */
    private static boolean hiddenAbove(Columns c, int i, int s, int n) {
        int j = i + 1;
        return j < s + n && c.y0[j] <= c.y1[i] && c.y1[j] > c.y1[i] && hides(c.kind[j], c.kind[i]);
    }

    /** Whether the run just below run {@code i} in its cell covers its bottom and hides it. */
    private static boolean hiddenBelow(Columns c, int i, int s) {
        int j = i - 1;
        return j >= s && c.y1[j] >= c.y0[i] && c.y0[j] < c.y0[i] && hides(c.kind[j], c.kind[i]);
    }

    /** Draws the parts of run {@code i}'s side toward {@code neighbour} that its runs leave open. */
    private static void side(Columns c, int i, int neighbour, int face,
                             int x0, int x1, int z0, int z1, Quads to) {
        int bottom = c.y0[i];
        int top = c.y1[i];
        int s = c.start(neighbour);
        int n = c.count(neighbour);
        int y = bottom;
        for (int j = s; j < s + n && y < top; j++) {
            if (!hides(c.kind[j], c.kind[i])) continue;
            int jb = c.y0[j];
            int jt = c.y1[j];
            if (jt <= y) continue;
            if (jb >= top) break;
            if (jb > y) to.quad(face, x0, x1, y, jb, z0, z1, c.side[i], c.material[i]);
            y = Math.max(y, jt);
        }
        if (y < top) to.quad(face, x0, x1, y, top, z0, z1, c.side[i], c.material[i]);
    }
}

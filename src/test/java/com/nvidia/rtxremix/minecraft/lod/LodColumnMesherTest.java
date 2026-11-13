/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodColumnMesherTest {

    private static final int DOWN = 0;
    private static final int UP = 1;
    private static final int NORTH = 2;
    private static final int SOUTH = 3;
    private static final int WEST = 4;
    private static final int EAST = 5;
    private static final int STONE = 2;
    private static final int WATER = 12;
    private static final int GREY = 0xFF808080;

    private final LodColumnMesher.Columns columns = new LodColumnMesher.Columns();
    private final LodColumnMesher.Quads opaque = new LodColumnMesher.Quads();
    private final LodColumnMesher.Quads translucent = new LodColumnMesher.Quads();

    @AfterEach
    void release() {
        opaque.release();
        translucent.release();
    }

    private record Quad(int face, int x0, int x1, int y0, int y1, int z0, int z1, int material) {}

    private static List<Quad> decode(LodColumnMesher.Quads quads) {
        ByteBuffer data = quads.view();
        List<Quad> out = new ArrayList<>();
        for (int q = 0; q < quads.quads; q++) {
            int base = q * LodMeshBuilder.DH_QUAD_BYTES;
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
            int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (int v = 0; v < 4; v++) {
                int at = base + v * LodMeshBuilder.DH_VERTEX_BYTES;
                int x = data.getShort(at) & 0xFFFF;
                int y = data.getShort(at + 2) & 0xFFFF;
                int z = data.getShort(at + 4) & 0xFFFF;
                x0 = Math.min(x0, x); x1 = Math.max(x1, x);
                y0 = Math.min(y0, y); y1 = Math.max(y1, y);
                z0 = Math.min(z0, z); z1 = Math.max(z1, z);
            }
            out.add(new Quad(data.get(base + 13), x0, x1, y0, y1, z0, z1, data.get(base + 12)));
        }
        return out;
    }

    private void column(int cx, int cz, int bottom, int top, byte kind, int material) {
        columns.begin(cx, cz);
        columns.add(bottom, top, GREY, GREY, GREY, material, kind);
    }

    @Test
    void aLonePillarDrawsItsTopFourSidesAndBottom() {
        column(5, 7, 10, 20, LodColumnMesher.SOLID, STONE);
        LodColumnMesher.mesh(columns, 2, opaque, translucent);
        List<Quad> quads = decode(opaque);
        assertEquals(6, quads.size(), quads.toString());
        assertTrue(quads.contains(new Quad(UP, 10, 12, 20, 20, 14, 16, STONE)), quads.toString());
        assertTrue(quads.contains(new Quad(DOWN, 10, 12, 10, 10, 14, 16, STONE)), quads.toString());
        assertTrue(quads.contains(new Quad(NORTH, 10, 12, 10, 20, 14, 14, STONE)), quads.toString());
        assertTrue(quads.contains(new Quad(SOUTH, 10, 12, 10, 20, 16, 16, STONE)), quads.toString());
        assertTrue(quads.contains(new Quad(WEST, 10, 10, 10, 20, 14, 16, STONE)), quads.toString());
        assertTrue(quads.contains(new Quad(EAST, 12, 12, 10, 20, 14, 16, STONE)), quads.toString());
        assertEquals(0, translucent.quads);
    }

    @Test
    void aSideShowsOnlyAboveTheNeighbourAndMatchingTopsMerge() {
        column(0, 0, 0, 10, LodColumnMesher.SOLID, STONE);
        column(1, 0, 0, 10, LodColumnMesher.SOLID, STONE);
        column(2, 0, 0, 14, LodColumnMesher.SOLID, STONE);
        // The ring outside the section: the ground goes on west and north.
        column(-1, 0, 0, 10, LodColumnMesher.SOLID, STONE);
        LodColumnMesher.mesh(columns, 1, opaque, translucent);
        List<Quad> quads = decode(opaque);
        assertTrue(quads.contains(new Quad(UP, 0, 2, 10, 10, 0, 1, STONE)), quads.toString());
        assertTrue(quads.contains(new Quad(WEST, 2, 2, 10, 14, 0, 1, STONE)), quads.toString());
        assertTrue(quads.stream().noneMatch(q -> q.face() == WEST && q.x0() <= 1), quads.toString());
        assertTrue(quads.stream().noneMatch(q -> q.face() == DOWN), "nothing drawn under the floor");
    }

    @Test
    void solidGroundUnderWaterKeepsItsSidesButWaterHidesWater() {
        column(0, 0, 0, 5, LodColumnMesher.SOLID, STONE);
        columns.add(5, 9, GREY, GREY, GREY, WATER, LodColumnMesher.WATER);
        column(1, 0, 0, 3, LodColumnMesher.SOLID, STONE);
        columns.add(3, 9, GREY, GREY, GREY, WATER, LodColumnMesher.WATER);
        LodColumnMesher.mesh(columns, 1, opaque, translucent);
        List<Quad> quads = decode(opaque);
        // The stone step between the two cells shows under the water.
        assertTrue(quads.contains(new Quad(EAST, 1, 1, 3, 5, 0, 1, STONE)), quads.toString());
        // No water face between the two water runs, and the stone top under water is drawn.
        assertTrue(quads.stream().noneMatch(q -> q.material() == WATER && q.face() == EAST && q.x0() == 1), quads.toString());
        assertTrue(quads.contains(new Quad(UP, 0, 1, 5, 5, 0, 1, STONE)), quads.toString());
        assertTrue(quads.contains(new Quad(UP, 0, 2, 9, 9, 0, 1, WATER)), quads.toString());
    }

    @Test
    void darkAirHidesTheCaveWallsAroundItAndIsNeverDrawn() {
        // Ground with a cave in it: dark air from 3 to 6 in cell 0, solid around and above.
        columns.begin(0, 0);
        columns.add(0, 3, GREY, GREY, GREY, STONE, LodColumnMesher.SOLID);
        columns.add(3, 6, 0, 0, 0, 0, LodColumnMesher.DARK);
        columns.add(6, 10, GREY, GREY, GREY, STONE, LodColumnMesher.SOLID);
        column(1, 0, 0, 10, LodColumnMesher.SOLID, STONE);
        LodColumnMesher.mesh(columns, 1, opaque, translucent);
        List<Quad> quads = decode(opaque);
        // The cave's floor, ceiling and the neighbour's wall into it are not drawn.
        assertTrue(quads.stream().noneMatch(q -> q.face() == UP && q.y0() == 3), quads.toString());
        assertTrue(quads.stream().noneMatch(q -> q.face() == DOWN && q.y0() == 6), quads.toString());
        assertTrue(quads.stream().noneMatch(q -> q.face() == WEST && q.x0() == 1 && q.y0() < 6 && q.y1() > 3),
                quads.toString());
        // The ground's top still is.
        assertTrue(quads.contains(new Quad(UP, 0, 2, 10, 10, 0, 1, STONE)), quads.toString());
    }

    @Test
    void glassGoesToTheTranslucentQuadsAndHidesNothingSolid() {
        column(3, 3, 0, 10, LodColumnMesher.SOLID, STONE);
        columns.add(10, 12, GREY, GREY, GREY, STONE, LodColumnMesher.TRANSLUCENT);
        column(4, 3, 0, 12, LodColumnMesher.TRANSLUCENT, STONE);
        LodColumnMesher.mesh(columns, 1, opaque, translucent);
        List<Quad> solid = decode(opaque);
        List<Quad> glass = decode(translucent);
        assertTrue(solid.contains(new Quad(UP, 3, 4, 10, 10, 3, 4, STONE)), solid.toString());
        assertTrue(solid.contains(new Quad(EAST, 4, 4, 0, 10, 3, 4, STONE)), solid.toString());
        assertTrue(glass.stream().noneMatch(q -> q.face() == WEST && q.x0() == 4 && q.y0() < 10), glass.toString());
        assertTrue(glass.contains(new Quad(UP, 3, 5, 12, 12, 3, 4, STONE)), glass.toString());
    }
}

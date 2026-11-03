/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodMeshBuilderTest {

    private static final int UP = 1;
    private static final int NORTH = 2;
    private static final int SOUTH = 3;
    private static final int STONE = 2;

    private final LodMeshBuilder.Bucket ground = new LodMeshBuilder.Bucket(false);
    private final LodMeshBuilder.Bucket water = new LodMeshBuilder.Bucket(true);
    private final LodMeshBuilder.Bucket glass = new LodMeshBuilder.Bucket(LodMeshBuilder.Paint.GLASS);
    private final LodMeshBuilder.Bucket glow = new LodMeshBuilder.Bucket(LodMeshBuilder.Paint.PALETTE);
    private final LodMeshBuilder.Bucket lamp = new LodMeshBuilder.Bucket(LodMeshBuilder.Paint.PALETTE);

    @AfterEach
    void releaseBuckets() {
        ground.release();
        water.release();
        glass.release();
        glow.release();
        lamp.release();
    }

    @Test
    void anUnclippedQuadKeepsItsCornersBelowMinecraftsSurface() {
        ByteBuffer dh = quads(1);
        top(dh, 0, 64, 70, 0, 64, STONE, 0x30, 0x60, 0x90);

        convert(dh, LodMeshBuilder.Clip.NONE, 1024, -512);

        assertEquals(1, ground.quads);
        assertEquals(64f, maxLocal(ground, 0));
        assertEquals(70f - LodMeshBuilder.SINK, ground.vertices.getFloat(4));
        assertEquals(1f, ground.vertices.getFloat(16), "up normal");
        assertEquals(0xFF306090, ground.vertices.getInt(32));
    }

    @Test
    void aMergedQuadLosesOnlyTheChunksMinecraftDraws() {
        ByteBuffer dh = quads(1);
        // World x 0..64 on chunk row z = 0: chunks 0 to 3.
        top(dh, 0, 64, 70, 0, 16, STONE, 1, 1, 1);

        convert(dh, LodMeshBuilder.Clip.disk(0, 0, 1), 0, 0);

        assertEquals(1, ground.quads, "chunks 2 and 3 stay one quad");
        assertEquals(32f, minLocal(ground, 0));
        assertEquals(64f, maxLocal(ground, 0));
    }

    @Test
    void aSideFaceIsClippedWithTheBlockBehindIt() {
        ByteBuffer dh = quads(2);
        // Both faces lie on z = 16: the south face belongs to chunk z = 0, the north face to z = 1.
        side(dh, SOUTH, 0, 16, 60, 70, 16);
        side(dh, NORTH, 0, 16, 60, 70, 16);

        convert(dh, LodMeshBuilder.Clip.disk(0, 0, 0), 0, 0);

        assertEquals(1, ground.quads);
        assertEquals(-1f, ground.vertices.getFloat(20), "the north face survives");
    }

    @Test
    void waterTakesWorldSpaceUvsAndSitsAtAStillSurface() {
        ByteBuffer dh = quads(1);
        top(dh, 0, 16, 63, 0, 16, LodMeshBuilder.WATER_MATERIAL, 0x20, 0x40, 0xC0);

        convert(dh, LodMeshBuilder.Clip.NONE, 160, 0);

        assertEquals(0, ground.quads);
        assertEquals(1, water.quads);
        assertEquals(63f - LodMeshBuilder.SINK - LodMeshBuilder.WATER_SINK, water.vertices.getFloat(4));
        assertEquals(160f / BlockTextureManager.getWaterTileSize(), water.vertices.getFloat(24));
        assertNotEquals(0, water.vertices.getInt(40), "water wraps inside its texture bounds");
    }

    @Test
    void glowingBlocksAndLavaReadTheirColourFromThePalette() {
        ByteBuffer dh = quads(2);
        top(dh, 0, 16, 40, 0, 16, LodMeshBuilder.LAVA_MATERIAL, 0xD0, 0x60, 0x10);
        top(dh, 16, 32, 70, 0, 16, LodMeshBuilder.ILLUMINATED_MATERIAL, 0xF0, 0xE0, 0x90);

        convertSplit(dh, quads(0), LodMeshBuilder.Clip.NONE, 0, 0);

        assertEquals(0, ground.quads);
        assertEquals(1, glow.quads, "lava glows at the terrain's emission");
        assertEquals(1, lamp.quads, "other blocks that give off light glow dimmer");
        assertEquals(0xFFFFFFFF, glow.vertices.getInt(32), "the vertex colour leaves the texel alone");
        assertEquals(LodPalette.u(0xD06010), glow.vertices.getFloat(24));
        assertEquals(LodPalette.v(0xD06010), glow.vertices.getFloat(28));
        assertEquals(LodPalette.u(0xF0E090), lamp.vertices.getFloat(24));
    }

    @Test
    void translucentBuffersBecomeGlassTintedByTheirHue() {
        ByteBuffer opaque = quads(1);
        top(opaque, 0, 16, 64, 0, 16, STONE, 0x50, 0x50, 0x50);
        ByteBuffer translucent = quads(2);
        top(translucent, 0, 16, 70, 0, 16, STONE, 0xC0, 0x30, 0x30);
        top(translucent, 0, 16, 60, 0, 16, LodMeshBuilder.WATER_MATERIAL, 0x20, 0x40, 0xC0);

        convertSplit(opaque, translucent, LodMeshBuilder.Clip.NONE, 0, 0);

        assertEquals(1, ground.quads);
        assertEquals(1, water.quads, "water stays water in the translucent buffer");
        assertEquals(1, glass.quads);
        int tint = LodPalette.glassTint(0xC03030);
        assertEquals(LodPalette.u(tint), glass.vertices.getFloat(24));
        assertEquals(LodPalette.v(tint), glass.vertices.getFloat(28));
    }

    @Test
    void theSignatureChangesOnlyWithTheChunksItRemoves() {
        LodMeshBuilder.Clip near = LodMeshBuilder.Clip.disk(0, 0, 4);
        assertEquals(0, near.signature(1024, 1024, 64), "a far section loses nothing");
        assertEquals(0, LodMeshBuilder.Clip.NONE.signature(0, 0, 64));
        assertEquals(near.signature(0, 0, 64), near.signature(0, 0, 64));
        assertNotEquals(near.signature(0, 0, 64), LodMeshBuilder.Clip.disk(1, 0, 4).signature(0, 0, 64));
        assertEquals(LodMeshBuilder.Clip.disk(10, 10, 1).signature(-64, -64, 64),
                LodMeshBuilder.Clip.disk(20, 20, 1).signature(-64, -64, 64));
    }

    @Test
    void theClipTellsASectionItCoversFromOneItCrosses() {
        LodMeshBuilder.Clip clip = LodMeshBuilder.Clip.disk(0, 0, 4);
        assertTrue(clip.covers(-32, -32, 64), "four chunks around the centre lie inside");
        assertTrue(clip.intersects(-32, -32, 64));
        assertFalse(clip.covers(0, 0, 128), "a section reaching past the radius is only crossed");
        assertTrue(clip.intersects(0, 0, 128));
        assertFalse(clip.intersects(64, 64, 16), "chunk 4, 4 lies outside a radius of 4");
        assertFalse(clip.intersects(1024, 1024, 64));
        assertFalse(LodMeshBuilder.Clip.NONE.covers(0, 0, 64));
        assertFalse(LodMeshBuilder.Clip.NONE.intersects(0, 0, 64));
        LodMeshBuilder.Clip holed = LodMeshBuilder.Clip.of(0, 0, 4, (x, z) -> x != 1 || z != 1);
        assertFalse(holed.covers(0, 0, 32), "a chunk Minecraft has not drawn keeps its LOD");
        assertTrue(holed.covers(-32, -32, 32));
        assertFalse(holed.clips(5, 0), "nothing past the window is clipped");
    }

    private void convert(ByteBuffer dh, LodMeshBuilder.Clip clip, int originX, int originZ) {
        dh.flip();
        LodGeometry geometry = LodGeometry.copyOf(List.of(dh), List.of());
        try {
            LodMeshBuilder.convert(geometry, clip, originX, originZ, ground, water);
        } finally {
            geometry.release();
        }
    }

    private void convertSplit(ByteBuffer opaque, ByteBuffer translucent, LodMeshBuilder.Clip clip,
                              int originX, int originZ) {
        opaque.flip();
        translucent.flip();
        LodGeometry geometry = LodGeometry.copyOf(List.of(opaque), List.of(translucent));
        try {
            LodMeshBuilder.convert(geometry, clip, originX, originZ, ground, water, glass, glow, lamp);
        } finally {
            geometry.release();
        }
    }

    private static ByteBuffer quads(int count) {
        return ByteBuffer.allocate(count * LodMeshBuilder.DH_QUAD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void top(ByteBuffer dh, int x0, int x1, int y, int z0, int z1,
                            int material, int r, int g, int b) {
        vertex(dh, x1, y, z0, r, g, b, material, UP);
        vertex(dh, x1, y, z1, r, g, b, material, UP);
        vertex(dh, x0, y, z1, r, g, b, material, UP);
        vertex(dh, x0, y, z0, r, g, b, material, UP);
    }

    private static void side(ByteBuffer dh, int face, int x0, int x1, int y0, int y1, int z) {
        vertex(dh, x0, y0, z, 9, 9, 9, STONE, face);
        vertex(dh, x0, y1, z, 9, 9, 9, STONE, face);
        vertex(dh, x1, y1, z, 9, 9, 9, STONE, face);
        vertex(dh, x1, y0, z, 9, 9, 9, STONE, face);
    }

    private static void vertex(ByteBuffer dh, int x, int y, int z, int r, int g, int b,
                               int material, int face) {
        dh.putShort((short) x).putShort((short) y).putShort((short) z).putShort((short) 0xF0);
        dh.put((byte) r).put((byte) g).put((byte) b).put((byte) 255);
        dh.put((byte) material).put((byte) face).putShort((short) 0);
    }

    private static float minLocal(LodMeshBuilder.Bucket bucket, int axisOffset) {
        float min = Float.MAX_VALUE;
        for (int v = 0; v < bucket.quads * 4; v++) {
            min = Math.min(min, bucket.vertices.getFloat(v * 64 + axisOffset));
        }
        return min;
    }

    private static float maxLocal(LodMeshBuilder.Bucket bucket, int axisOffset) {
        float max = -Float.MAX_VALUE;
        for (int v = 0; v < bucket.quads * 4; v++) {
            max = Math.max(max, bucket.vertices.getFloat(v * 64 + axisOffset));
        }
        return max;
    }
}

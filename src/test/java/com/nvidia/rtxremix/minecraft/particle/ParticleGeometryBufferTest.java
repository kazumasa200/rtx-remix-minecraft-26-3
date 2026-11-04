/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParticleGeometryBufferTest {
    @Test void failedProducerRollsBackCompleteAndOpenQuadsWithoutLosingHealthyPrefix() {
        try (var buffer = new ParticleGeometryBuffer()) {
            quad(buffer, 3);
            buffer.checkpoint();
            quad(buffer, 6);
            buffer.beginQuad(0, 0);
            buffer.vertex(99, 0, 0, 0, 0, -1);
            buffer.rollback();
            assertEquals(1, buffer.quadCount());
            quad(buffer, 12);
            var vertices = buffer.snapshotVertices();
            try {
                assertEquals(3, vertices.getFloat(0));
                assertEquals(12, vertices.getFloat(4 * HardcodedVertex.STRIDE));
                assertEquals(2 * 4 * HardcodedVertex.STRIDE, vertices.remaining());
            } finally { DirectBufferPool.release(vertices); }
        }
    }

    @Test void discardedPartialFrameCanBeReusedWithoutAnOpenQuad() {
        try (var buffer = new ParticleGeometryBuffer()) {
            buffer.beginQuad(0, 0);
            buffer.vertex(1, 2, 3, 0, 0, -1);
            buffer.discard();
            buffer.reset();
            quad(buffer, 8);
            assertEquals(1, buffer.quadCount());
        }
    }
    @Test void shadingNormalMatchesVanillaBillboardConventionAfterRotation() {
        for (float angle : new float[]{0, .7f, 2.4f}) {
            var rotation = new org.joml.Quaternionf().rotateXYZ(angle * .3f, angle, .2f);
            var normal = new org.joml.Vector3f(0, 0, -1).rotate(rotation);
            try (var buffer = new ParticleGeometryBuffer()) {
                buffer.beginQuad(0, 0);
                for (int i = 0; i < 4; i++) {
                    var point = new org.joml.Vector3f(i == 0 || i == 3 ? -1 : 1, i < 2 ? -1 : 1, 0).rotate(rotation);
                    buffer.vertex(point.x, point.y, point.z, 0, 0, -1);
                }
                buffer.endQuad();
                var vertices = buffer.snapshotVertices();
                try {
                    assertEquals(normal.x, vertices.getFloat(12), 1e-6);
                    assertEquals(normal.y, vertices.getFloat(16), 1e-6);
                    assertEquals(normal.z, vertices.getFloat(20), 1e-6);
                } finally { DirectBufferPool.release(vertices); }
            }
        }
    }

    private static void quad(ParticleGeometryBuffer buffer, float x) {
        buffer.beginQuad(123, 456);
        buffer.vertex(x, -1, 2, .2f, .8f, 0x7f123456);
        buffer.vertex(x + 2, -1, 2, .8f, .8f, 0x7f123456);
        buffer.vertex(x + 2, 1, 2, .8f, .2f, 0x7f123456);
        buffer.vertex(x, 1, 2, .2f, .2f, 0x7f123456);
        buffer.endQuad();
    }

    @Test void preservesSourceCoordinatesUvsAlphaAndAtlasBounds() {
        try (var buffer = new ParticleGeometryBuffer()) {
            quad(buffer, -3.125f);
            ByteBuffer vertices = buffer.snapshotVertices();
            ByteBuffer indices = buffer.snapshotIndices();
            try {
                assertTrue(vertices.isDirect());
                assertEquals(4 * HardcodedVertex.STRIDE, vertices.remaining());
                assertEquals(-3.125f, vertices.getFloat(0));
                assertEquals(-1f, vertices.getFloat(4));
                assertEquals(2f, vertices.getFloat(8));
                assertEquals(.2f, vertices.getFloat(24));
                assertEquals(.8f, vertices.getFloat(28));
                assertEquals(0x7f123456, vertices.getInt(32));
                for (int i = 0; i < 4; i++) {
                    int p = i * HardcodedVertex.STRIDE;
                    assertEquals(0f, vertices.getFloat(p + 12));
                    assertEquals(0f, vertices.getFloat(p + 16));
                    assertEquals(-1f, vertices.getFloat(p + 20));
                    assertEquals(123, vertices.getInt(p + 36));
                    assertEquals(456, vertices.getInt(p + 40));
                    for (int pad = 44; pad < 64; pad += 4) assertEquals(0, vertices.getInt(p + pad));
                }
                assertArrayEquals(new int[]{0, 2, 1, 0, 3, 2}, readIndices(indices));
            } finally { DirectBufferPool.release(vertices); DirectBufferPool.release(indices); }
        }
    }

    @Test void growthKeepsEveryQuadAndResetExcludesPreviousFrameVertices() {
        try (var buffer = new ParticleGeometryBuffer()) {
            for (int i = 0; i < 513; i++) quad(buffer, i);
            assertEquals(513, buffer.quadCount());
            ByteBuffer vertices = buffer.snapshotVertices(), indices = buffer.snapshotIndices();
            try {
                assertEquals(512f, vertices.getFloat(512 * 4 * 64));
                assertEquals(512 * 4 + 2, indices.getInt((513 * 6 - 1) * 4));
            } finally { DirectBufferPool.release(vertices); DirectBufferPool.release(indices); }
            int capacity = buffer.capacity();
            buffer.reset();
            quad(buffer, 19);
            assertEquals(capacity, buffer.capacity());
            vertices = buffer.snapshotVertices();
            try { assertEquals(256, vertices.remaining()); assertEquals(19f, vertices.getFloat(0)); }
            finally { DirectBufferPool.release(vertices); }
        }
    }

    @Test void pageLimitDoesNotSilentlyDiscardAnAdditionalQuad() {
        try (var buffer = new ParticleGeometryBuffer()) {
            for (int i = 0; i < ParticleGeometryBuffer.MAX_QUADS; i++) quad(buffer, i);
            assertTrue(buffer.full());
            assertThrows(IllegalStateException.class, () -> buffer.beginQuad(0, 0));
            assertEquals(ParticleGeometryBuffer.MAX_QUADS, buffer.quadCount());
        }
    }

    @Test void malformedGeometryFailsInsteadOfPublishingPartialPrimitives() {
        try (var buffer = new ParticleGeometryBuffer()) {
            assertThrows(IllegalStateException.class, buffer::endQuad);
            buffer.beginQuad(0, 0);
            assertThrows(IllegalArgumentException.class, () -> buffer.vertex(Float.NaN, 0, 0, 0, 0, -1));
            assertThrows(IllegalStateException.class, buffer::reset);
            assertThrows(IllegalStateException.class, buffer::endQuad);
        }
    }

    private static int[] readIndices(ByteBuffer buffer) {
        int[] values = new int[buffer.remaining() / 4];
        for (int i = 0; i < values.length; i++) values[i] = buffer.getInt(i * 4);
        return values;
    }
}

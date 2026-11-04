/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.minecraft.texture.ParticleSpriteBounds;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParticleGeometryVertexConsumerTest {
    private static final ParticleSpriteBounds BOUNDS = new ParticleSpriteBounds(
            List.of(new ParticleSpriteBounds.Bounds(.25f, .25f, .5f, .5f)));
    private static final class Sink implements ParticleGeometrySink {
        final float[] vertices = new float[40];
        final int[] colors = new int[8];
        int quads, count, emission, min, max;
        @Override public void beginQuad(int emission, int min, int max) { this.emission = emission; this.min = min; this.max = max; }
        @Override public void vertex(float x, float y, float z, float u, float v, int color) {
            int offset = count * 5;
            vertices[offset] = x; vertices[offset + 1] = y; vertices[offset + 2] = z;
            vertices[offset + 3] = u; vertices[offset + 4] = v; colors[count++] = color;
        }
        @Override public void endQuad() { quads++; }
    }
    private static void quad(ParticleGeometryVertexConsumer consumer) {
        for (int i = 0; i < 4; i++) {
            consumer.addVertex(i * .5f, 2 - i, -3).setUv(.3f + .02f * i, .4f)
                    .setColor(0x78123456 + i).setLight(0x00f000f0);
        }
    }
    @Test void preservesEveryVertexColorAlphaUvAndCroppedSpriteBounds() {
        var consumer = new ParticleGeometryVertexConsumer(); var sink = new Sink();
        consumer.begin(sink, BOUNDS); quad(consumer); consumer.finish(); consumer.abort();
        assertEquals(1, sink.quads); assertEquals(4, sink.count);
        for (int i = 0; i < 4; i++) {
            assertEquals(i * .5f, sink.vertices[i * 5]);
            assertEquals(2 - i, sink.vertices[i * 5 + 1]);
            assertEquals(-3, sink.vertices[i * 5 + 2]);
            assertEquals(.3f + .02f * i, sink.vertices[i * 5 + 3]);
            assertEquals(.4f, sink.vertices[i * 5 + 4]);
            assertEquals(0x78123456 + i, sink.colors[i]);
        }
        assertEquals(HardcodedVertex.packHalf2x16(.25f, .25f), sink.min);
        assertEquals(HardcodedVertex.packHalf2x16(.5f, .5f), sink.max);
        assertEquals(0, sink.emission, "Full packed environmental light is not intrinsic emission");
    }
    @Test void continuousStreamsRetainMultipleQuadsAndLastVertexAttributes() {
        var consumer = new ParticleGeometryVertexConsumer(); var sink = new Sink();
        consumer.begin(sink, BOUNDS); quad(consumer); quad(consumer); consumer.finish(); consumer.abort();
        assertEquals(2, sink.quads); assertEquals(8, sink.count);
        assertEquals(0x78123459, sink.colors[3]); assertEquals(0x78123459, sink.colors[7]);
    }
    @Test void incompleteSourcesFailAndAbortedScratchCanBeReused() {
        var consumer = new ParticleGeometryVertexConsumer(); var sink = new Sink();
        consumer.begin(sink, BOUNDS); consumer.addVertex(0, 0, 0);
        assertThrows(IllegalStateException.class, consumer::finish); consumer.abort();
        consumer.begin(sink, BOUNDS); quad(consumer); consumer.finish(); consumer.abort();
        assertEquals(1, sink.quads);
    }
    @Test void reentrantBuildsCannotOverwriteAnActiveLayer() {
        var consumer = new ParticleGeometryVertexConsumer(); var sink = new Sink();
        consumer.begin(sink, BOUNDS);
        assertThrows(IllegalStateException.class, () -> consumer.begin(sink, BOUNDS));
        consumer.abort();
    }
}

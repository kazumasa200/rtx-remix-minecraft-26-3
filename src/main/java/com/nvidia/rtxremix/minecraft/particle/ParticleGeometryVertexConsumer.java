/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.minecraft.texture.ParticleSpriteBounds;

/** One reusable quad of scratch space; it never infers emission from environmental light. */
public final class ParticleGeometryVertexConsumer implements VertexConsumer {
    private final float[] values = new float[20];
    private final int[] colors = new int[4];
    private ParticleGeometrySink sink;
    private ParticleSpriteBounds bounds;
    private int count;

    public void begin(ParticleGeometrySink sink, ParticleSpriteBounds bounds) {
        if (this.sink != null) throw new IllegalStateException("Reentrant particle quad capture");
        this.sink = sink;
        this.bounds = bounds;
        count = 0;
    }

    @Override public VertexConsumer addVertex(float x, float y, float z) {
        if (sink == null) throw new IllegalStateException("Particle layer has not begun");
        if (count == 4) flush();
        int offset = count * 5;
        values[offset] = x; values[offset + 1] = y; values[offset + 2] = z;
        values[offset + 3] = values[offset + 4] = 0;
        colors[count++] = -1;
        return this;
    }
    @Override public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        return setColor((alpha & 255) << 24 | (red & 255) << 16 | (green & 255) << 8 | blue & 255);
    }
    @Override public VertexConsumer setColor(int argb) { requireVertex(); colors[count - 1] = argb; return this; }
    @Override public VertexConsumer setUv(float u, float v) {
        requireVertex(); values[(count - 1) * 5 + 3] = u; values[(count - 1) * 5 + 4] = v; return this;
    }
    @Override public VertexConsumer setUv1(int u, int v) { return this; }
    @Override public VertexConsumer setUv2(int u, int v) { return this; }
    @Override public VertexConsumer setUv3(float u, float v) { return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    @Override public VertexConsumer setLineWidth(float width) { return this; }

    public void finish() {
        if (count != 0 && count != 4) throw new IllegalStateException("Incomplete extracted particle quad");
        if (count == 4) flush();
    }
    public void abort() { sink = null; bounds = null; count = 0; }

    private void requireVertex() { if (count == 0) throw new IllegalStateException("Particle attribute without a vertex"); }
    private void flush() {
        float u0 = values[3], v0 = values[4], u1 = u0, v1 = v0;
        for (int i = 1; i < 4; i++) {
            u0 = Math.min(u0, values[i * 5 + 3]); v0 = Math.min(v0, values[i * 5 + 4]);
            u1 = Math.max(u1, values[i * 5 + 3]); v1 = Math.max(v1, values[i * 5 + 4]);
        }
        long sprite = bounds.find(u0, v0, u1, v1);
        sink.beginQuad(0, (int) (sprite >>> 32), (int) sprite);
        for (int i = 0; i < 4; i++) {
            int offset = i * 5;
            sink.vertex(values[offset], values[offset + 1], values[offset + 2],
                    values[offset + 3], values[offset + 4], colors[i]);
        }
        sink.endQuad();
        count = 0;
    }
}

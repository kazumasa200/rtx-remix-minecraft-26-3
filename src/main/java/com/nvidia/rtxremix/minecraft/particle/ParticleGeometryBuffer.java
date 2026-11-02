/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import java.nio.ByteBuffer;

/** Reusable native-format quad page. Source positions remain camera relative. */
public final class ParticleGeometryBuffer implements AutoCloseable {
    public static final int MAX_QUADS = 8192;
    static final int VERTEX_BYTES = 4 * HardcodedVertex.STRIDE;
    static final int INDEX_BYTES = 6 * Integer.BYTES;
    private ByteBuffer vertices;
    private ByteBuffer indices;
    private int capacity;
    private int quads;
    private int checkpoint;
    private int written;
    private int boundsMin, boundsMax;
    private boolean open;

    public void reset() {
        if (open) throw new IllegalStateException("Unfinished particle quad");
        quads = 0;
    }

    public int quadCount() { return quads; }
    public int capacity() { return capacity; }
    public boolean full() { return quads == MAX_QUADS; }

    void checkpoint() { checkpoint = quads; }
    void rollback() { quads = checkpoint; written = 0; open = false; }
    void discard() { checkpoint = quads = written = 0; open = false; }

    public void beginQuad(int min, int max) {
        if (open || full()) throw new IllegalStateException("Particle page is not ready for another quad");
        ensureCapacity(quads + 1);
        boundsMin = min;
        boundsMax = max;
        written = 0;
        open = true;
    }

    public void vertex(float x, float y, float z, float u, float v, int argb) {
        if (!open || written == 4) throw new IllegalStateException("Particle quad needs exactly four vertices");
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || !Float.isFinite(u) || !Float.isFinite(v)) {
            throw new IllegalArgumentException("Nonfinite particle vertex");
        }
        int p = quads * VERTEX_BYTES + written++ * HardcodedVertex.STRIDE;
        vertices.putFloat(p, x).putFloat(p + 4, y).putFloat(p + 8, z);
        vertices.putFloat(p + 24, u).putFloat(p + 28, v);
        // The native C API uses the same packed D3DCOLOR/ARGB value as Minecraft.
        vertices.putInt(p + 32, argb);
        vertices.putInt(p + 36, boundsMin).putInt(p + 40, boundsMax);
        for (int pad = 44; pad < HardcodedVertex.STRIDE; pad += 4) vertices.putInt(p + pad, 0);
    }

    public void endQuad() {
        if (!open || written != 4) throw new IllegalStateException("Incomplete particle quad");
        int p = quads * VERTEX_BYTES;
        float ax = vertices.getFloat(p + 64) - vertices.getFloat(p);
        float ay = vertices.getFloat(p + 68) - vertices.getFloat(p + 4);
        float az = vertices.getFloat(p + 72) - vertices.getFloat(p + 8);
        float bx = vertices.getFloat(p + 128) - vertices.getFloat(p);
        float by = vertices.getFloat(p + 132) - vertices.getFloat(p + 4);
        float bz = vertices.getFloat(p + 136) - vertices.getFloat(p + 8);
        // Minecraft's particle quad order uses the opposite normal convention to
        // a right-handed cross product (the vanilla billboard basis also uses -Z).
        float nx = az * by - ay * bz, ny = ax * bz - az * bx, nz = ay * bx - ax * by;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length > 0) { nx /= length; ny /= length; nz /= length; }
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = p + vertex * HardcodedVertex.STRIDE;
            vertices.putFloat(offset + 12, nx).putFloat(offset + 16, ny).putFloat(offset + 20, nz);
        }
        quads++;
        open = false;
    }

    public ByteBuffer snapshotVertices() { return snapshot(vertices, quads * VERTEX_BYTES); }
    public ByteBuffer snapshotIndices() { return snapshot(indices, quads * INDEX_BYTES); }

    private static ByteBuffer snapshot(ByteBuffer source, int bytes) {
        ByteBuffer copy = DirectBufferPool.acquire(bytes);
        copy.put(0, source, 0, bytes);
        copy.position(0).limit(bytes);
        return copy;
    }

    private void ensureCapacity(int needed) {
        if (needed <= capacity) return;
        int next = Math.min(MAX_QUADS, Math.max(128, capacity * 2));
        ByteBuffer newVertices = DirectBufferPool.acquire(next * VERTEX_BYTES);
        ByteBuffer newIndices = null;
        try {
            if (vertices != null) newVertices.put(0, vertices, 0, quads * VERTEX_BYTES);
            newIndices = DirectBufferPool.acquire(next * INDEX_BYTES);
            for (int q = 0; q < next; q++) {
                int i = q * INDEX_BYTES, v = q * 4;
                // Preserve Minecraft's front face in the native triangle convention.
                newIndices.putInt(i, v).putInt(i + 4, v + 2).putInt(i + 8, v + 1);
                newIndices.putInt(i + 12, v).putInt(i + 16, v + 3).putInt(i + 20, v + 2);
            }
        } catch (RuntimeException | Error e) {
            DirectBufferPool.release(newVertices);
            DirectBufferPool.release(newIndices);
            throw e;
        }
        DirectBufferPool.release(vertices);
        DirectBufferPool.release(indices);
        vertices = newVertices;
        indices = newIndices;
        capacity = next;
    }

    @Override public void close() {
        DirectBufferPool.release(vertices);
        DirectBufferPool.release(indices);
        vertices = indices = null;
        capacity = checkpoint = quads = written = 0;
        open = false;
    }
}

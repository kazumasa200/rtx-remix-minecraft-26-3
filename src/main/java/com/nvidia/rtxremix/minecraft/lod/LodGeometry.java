/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One copy of an LOD section's vertex buffers in the LOD mod's own quad format.
 * The section keeps a reference so a clip change or a material reload can
 * rebuild its Remix mesh without asking the LOD mod again, and each in-flight
 * build holds another; the last release returns the buffer.
 */
final class LodGeometry {

    final ByteBuffer data;
    final int quadCount;
    /** The opaque buffers' quads, which come first; the translucent ones (glass, ice) follow. */
    final int opaqueQuads;
    private final AtomicInteger refs = new AtomicInteger(1);

    private LodGeometry(ByteBuffer data, int quadCount, int opaqueQuads) {
        this.data = data;
        this.quadCount = quadCount;
        this.opaqueQuads = opaqueQuads;
    }

    /** Copies the buffers' remaining bytes; the caller keeps ownership of them. */
    static LodGeometry copyOf(List<ByteBuffer> opaque, List<ByteBuffer> transparent) {
        int opaqueBytes = remaining(opaque);
        int bytes = opaqueBytes + remaining(transparent);
        ByteBuffer data = DirectBufferPool.acquire(Math.max(bytes, 1));
        put(data, opaque);
        put(data, transparent);
        data.flip();
        return new LodGeometry(data, bytes / LodMeshBuilder.DH_QUAD_BYTES,
                opaqueBytes / LodMeshBuilder.DH_QUAD_BYTES);
    }

    void retain() {
        refs.incrementAndGet();
    }

    void release() {
        int left = refs.decrementAndGet();
        if (left == 0) {
            DirectBufferPool.release(data);
        } else if (left < 0) {
            throw new IllegalStateException("LOD geometry released more often than retained");
        }
    }

    private static int remaining(List<ByteBuffer> buffers) {
        int bytes = 0;
        for (ByteBuffer buffer : buffers) {
            // Whole quads only: a torn tail would shift every later vertex.
            bytes += buffer.remaining() - buffer.remaining() % LodMeshBuilder.DH_QUAD_BYTES;
        }
        return bytes;
    }

    private static void put(ByteBuffer data, List<ByteBuffer> buffers) {
        for (ByteBuffer buffer : buffers) {
            ByteBuffer source = buffer.duplicate();
            source.limit(source.position()
                    + source.remaining() - source.remaining() % LodMeshBuilder.DH_QUAD_BYTES);
            data.put(source);
        }
    }
}

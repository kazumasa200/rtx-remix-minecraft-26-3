/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import com.nvidia.rtxremix.minecraft.asset.RemixInstance;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Remix's view of one section an LOD mod renders. The mod's threads publish
 * new geometry, whether the section is drawn, and its closing through the
 * {@code publish*} methods; {@link LodTerrain} picks the changes up on the
 * render thread. A section sits in the dirty queue at most once, so the queue
 * is bounded by the mod's live sections. Every other field belongs to the
 * render thread.
 */
public final class LodSection {

    /** Set by {@link LodTerrain} on the render thread; while false nothing is copied. */
    static volatile boolean capturing;

    private static final ConcurrentLinkedQueue<LodSection> DIRTY = new ConcurrentLinkedQueue<>();

    final int minX;
    final int minY;
    final int minZ;
    final int width;

    private final AtomicReference<LodGeometry> pending = new AtomicReference<>();
    private final AtomicBoolean queued = new AtomicBoolean();
    volatile boolean enabled;
    volatile boolean closed;

    // Render thread only.
    LodGeometry geometry;
    RemixInstance instance;
    boolean shown;
    long installedBytes;
    long clipSignature;
    int serial;
    int liveIndex = -1;
    boolean inBuildQueue;
    /** Whether a mesh of the section's geometry has been installed, an empty one included. */
    boolean installed;
    /** Whether the section lies past what the mesh budget draws, so it waits unbuilt. */
    boolean starved;
    /** The section's part in a swap with the sections replacing it, one of {@code LodTerrain.SWAP_*}. */
    byte swap;
    /** The section's place in the list of its swap state. */
    int swapIndex = -1;
    /** The frame the section began to linger. */
    int lingerSince;

    /** World block position of the section's minimum corner and its width in blocks. */
    public LodSection(int minX, int minY, int minZ, int width) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.width = width;
    }

    /**
     * How many blocks across one of the LOD mod's cells is: 1 at its finest detail, doubling
     * with each coarser level, since every section is 64 cells across.
     */
    int blocksPerCell() {
        return Math.max(1, width / 64);
    }

    /** Copies the mod's freshly built vertex buffers; any thread. */
    public void publishGeometry(List<ByteBuffer> opaque, List<ByteBuffer> transparent) {
        if (!capturing || closed) return;
        LodGeometry replaced = pending.getAndSet(LodGeometry.copyOf(opaque, transparent));
        if (replaced != null) replaced.release();
        markDirty();
    }

    public void publishEnabled(boolean enabled) {
        this.enabled = enabled;
        if (capturing) markDirty();
    }

    public void publishClosed() {
        closed = true;
        markDirty();
    }

    LodGeometry takePending() {
        return pending.getAndSet(null);
    }

    /** The next changed section, already eligible to be queued again. */
    static LodSection pollDirty() {
        LodSection section = DIRTY.poll();
        if (section != null) section.queued.set(false);
        return section;
    }

    private void markDirty() {
        if (queued.compareAndSet(false, true)) DIRTY.add(this);
    }
}

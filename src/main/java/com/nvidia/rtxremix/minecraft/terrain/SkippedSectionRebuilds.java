/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.SectionPos;

import java.util.concurrent.atomic.AtomicInteger;

/** Counts vanilla section rebuild requests the mod declined because Remix would not use them. */
public final class SkippedSectionRebuilds {
    private static final AtomicInteger LIGHT_ONLY = new AtomicInteger();
    /** Columns, as {@code x << 32 | z}, holding a section whose sky light changed. */
    private static final LongOpenHashSet SKY_LIGHT_COLUMNS = new LongOpenHashSet();

    private SkippedSectionRebuilds() {
    }

    /** Records a rebuild requested only because the client light engine changed a section's light. */
    public static void recordLightOnly() {
        recordLightOnly(1);
    }

    /** Records {@code count} such rebuilds dropped together by one light-engine pass. */
    public static void recordLightOnly(int count) {
        LIGHT_ONLY.addAndGet(count);
    }

    /**
     * Records a section whose sky light changed. Its rebuild is dropped, but the deep-cave
     * cull reads sky light ({@link DeepCaveCull}), so the column is judged again.
     */
    public static void recordSkyLightChanged(long sectionPos) {
        long column = ((long) SectionPos.x(sectionPos) << 32) | (SectionPos.z(sectionPos) & 0xFFFFFFFFL);
        synchronized (SKY_LIGHT_COLUMNS) {
            SKY_LIGHT_COLUMNS.add(column);
        }
    }

    /** Hands the recorded columns to {@code sink} and forgets them. */
    static void drainSkyLightColumns(java.util.function.LongConsumer sink) {
        long[] columns;
        synchronized (SKY_LIGHT_COLUMNS) {
            if (SKY_LIGHT_COLUMNS.isEmpty()) return;
            columns = SKY_LIGHT_COLUMNS.toLongArray();
            SKY_LIGHT_COLUMNS.clear();
        }
        for (long column : columns) sink.accept(column);
    }

    /** Returns and resets the light-only count, for the per-frame terrain counters. */
    public static int drainLightOnly() {
        return LIGHT_ONLY.getAndSet(0);
    }
}

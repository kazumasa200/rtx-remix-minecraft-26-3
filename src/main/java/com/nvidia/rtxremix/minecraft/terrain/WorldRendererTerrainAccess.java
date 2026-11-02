/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.function.Consumer;

/**
 * Terrain rebuild scheduling for the active backend owned by LevelRenderer.
 */
public interface WorldRendererTerrainAccess {

    /**
     * The sections the backend currently holds compiled terrain for, as packed
     * {@code SectionPos} longs. This is the terrain the player has seen:
     * the backend builds a section when its discovery walk reaches it, so the set
     * is the frustum-driven subset of the loaded area. Take it before a
     * {@code LevelExtractor.allChanged()}, whose geometry invalidation forgets it.
     */
    LongSet rtxremix$builtSectionPositions();

    /**
     * Queues resident sections in {@code sections} for Remix capture once the
     * backend has rebuilt its section map after {@code allChanged()}. The
     * backend applies its own validity filters and scheduling budget. A queue still
     * waiting when another one is requested is replaced, and one requested for a
     * level that is gone by the rebuild is dropped.
     *
     * @param onQueued receives the positions actually queued, as packed
     *                 {@code SectionPos} longs; a subset of {@code sections},
     *                 never a view of it. Called on the render thread.
     */
    void rtxremix$queueSectionsForRemix(LongSet sections, Consumer<LongSet> onQueued);

    /**
     * Flags a section for recompile if the backend's section window still holds
     * it. A section that has left the window is skipped: the window marks a
     * section dirty when it re-enters.
     */
    void rtxremix$scheduleResidentSectionRebuild(int cx, int sy, int cz);
}

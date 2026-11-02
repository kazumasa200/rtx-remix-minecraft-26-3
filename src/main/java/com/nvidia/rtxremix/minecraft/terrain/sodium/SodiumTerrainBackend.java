/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.sodium;

import com.nvidia.rtxremix.minecraft.terrain.WorldRendererTerrainAccess;
import com.nvidia.rtxremix.minecraft.terrain.sodium.mixin.SodiumRendererAccessor;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.function.Consumer;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;

/** Routes reload recapture to Sodium's resident section scheduler. Render thread only. */
public final class SodiumTerrainBackend implements WorldRendererTerrainAccess {
    private LongSet pending;
    private Consumer<LongSet> onQueued;
    private ClientLevel requestedLevel;

    private static RenderSectionManager manager() {
        SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
        return renderer == null ? null : ((SodiumRendererAccessor) renderer).rtxremix$manager();
    }

    @Override public LongSet rtxremix$builtSectionPositions() {
        RenderSectionManager manager = manager();
        return manager == null ? LongSets.emptySet() : ((SodiumTerrainManager) manager).rtxremix$builtSections();
    }

    @Override public void rtxremix$queueSectionsForRemix(LongSet sections, Consumer<LongSet> callback) {
        pending = sections;
        onQueued = callback;
        requestedLevel = Minecraft.getInstance().level;
    }

    /** Called once Sodium has recreated and populated the resident section map. */
    public void applyPending(RenderSectionManager manager) {
        if (pending == null || manager == null) return;
        LongSet sections = pending;
        Consumer<LongSet> callback = onQueued;
        pending = null;
        onQueued = null;
        LongSet queued = new LongOpenHashSet();
        if (requestedLevel == Minecraft.getInstance().level) {
            SodiumTerrainManager access = (SodiumTerrainManager) manager;
            for (long section : sections) if (access.rtxremix$requestRebuild(section)) queued.add(section);
        }
        requestedLevel = null;
        callback.accept(queued);
    }

    @Override public void rtxremix$scheduleResidentSectionRebuild(int cx, int sy, int cz) {
        RenderSectionManager manager = manager();
        if (manager != null) ((SodiumTerrainManager) manager).rtxremix$requestRebuild(SectionPos.asLong(cx, sy, cz));
    }
}

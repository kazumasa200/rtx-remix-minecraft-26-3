/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod.dh;

import com.nvidia.rtxremix.minecraft.lod.LodApiSections;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiChunkModifiedEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Distant Horizons' terrain data through its public API alone, for {@link LodApiSections}: the
 * path a release the renderer capture does not support takes, since the API is versioned and
 * keeps its types across releases.
 *
 * <p>The API hands out each block column at full detail, never Distant Horizons' own coarser
 * copies, so a coarse cell reads the one column at its centre.
 */
public final class DhApiTerrain implements LodApiSections.Terrain {

    private static final Logger LOGGER = LoggerFactory.getLogger(DhApiTerrain.class);

    private final ConcurrentLinkedQueue<long[]> modified = new ConcurrentLinkedQueue<>();
    private volatile @Nullable IDhApiLevelWrapper level;
    private @Nullable ClientLevel boundLevel;
    private boolean eventsBound;
    private boolean unmatchedLogged;

    /** Whether the installed Distant Horizons offers the API this path is written against. */
    public static boolean available() {
        return DhPublicApi.compatible();
    }

    @Override
    public String describe() {
        return "Distant Horizons " + DhPublicApi.describe();
    }

    @Override
    public int radiusBlocks() {
        return DhPublicApi.lodRadiusChunks() * 16;
    }

    @Override
    public boolean bind(ClientLevel client) {
        if (!eventsBound) {
            eventsBound = true;
            DhPublicApi.bindLitBlocks();
            DhApi.events.bind(DhApiChunkModifiedEvent.class, new DhApiChunkModifiedEvent() {
                @Override
                public void onChunkModified(DhApiEventParam<EventParam> event) {
                    IDhApiLevelWrapper current = level;
                    EventParam param = event.value;
                    if (current != null && sameLevel(current, param.levelWrapper)) {
                        modified.add(new long[] {param.chunkX, param.chunkZ});
                    }
                }
            });
        }
        if (client != boundLevel || level == null) {
            boundLevel = client;
            level = find(client);
        }
        return level != null;
    }

    @Override
    public void unbind() {
        boundLevel = null;
        level = null;
        modified.clear();
    }

    @Override
    public int minY() {
        IDhApiLevelWrapper current = level;
        return current != null ? current.getMinHeight() : 0;
    }

    @Override
    public boolean ceiling() {
        IDhApiLevelWrapper current = level;
        return current != null && current.hasCeiling();
    }

    @Override
    public boolean skyLit() {
        IDhApiLevelWrapper current = level;
        return current == null || current.hasSkyLight();
    }

    @Override
    public long @Nullable [] pollModifiedChunk() {
        return modified.poll();
    }

    @Override
    public LodApiSections.Reader reader() {
        IDhApiLevelWrapper current = level;
        IDhApiTerrainDataRepo repo = DhApi.Delayed.terrainRepo;
        if (current == null || repo == null) return null;
        IDhApiTerrainDataCache cache = repo.createSoftCache();
        return new LodApiSections.Reader() {
            @Override
            public boolean area64(int sectionX, int sectionZ, LodApiSections.ColumnSink sink) {
                // Detail 6 in the API's units is 64 blocks: the columns of one finest section.
                DhApiResult<DhApiTerrainDataPoint[][][]> result =
                        repo.getAllTerrainDataAtDetailLevelAndPos(current, (byte) 6, sectionX, sectionZ, cache);
                if (!result.success || result.payload == null) return false;
                DhApiTerrainDataPoint[][][] area = result.payload;
                for (int x = 0; x < area.length; x++) {
                    DhApiTerrainDataPoint[][] row = area[x];
                    if (row == null) continue;
                    for (int z = 0; z < row.length; z++) {
                        sink.begin(x, z);
                        points(row[z], sink);
                    }
                }
                return true;
            }

            @Override
            public boolean column(int blockX, int blockZ, LodApiSections.ColumnSink sink, int cx, int cz) {
                DhApiResult<DhApiTerrainDataPoint[]> result = repo.getColumnDataAtBlockPos(current, blockX, blockZ, cache);
                sink.begin(cx, cz);
                if (!result.success) return false;
                points(result.payload, sink);
                return true;
            }
        };
    }

    private static void points(DhApiTerrainDataPoint @Nullable [] column, LodApiSections.ColumnSink sink) {
        if (column == null) return;
        for (DhApiTerrainDataPoint point : column) {
            if (point == null) continue;
            IDhApiBlockStateWrapper block = point.blockStateWrapper;
            if (block == null || block.isAir()) {
                sink.air(point.bottomYBlockPos, point.topYBlockPos, point.skyLightLevel);
                continue;
            }
            if (!(block.getWrappedMcObject() instanceof BlockState state)) continue;
            Object biome = point.biomeWrapper != null ? point.biomeWrapper.getWrappedMcObject() : null;
            sink.run(state, biome, point.bottomYBlockPos, point.topYBlockPos, block.getMaterialId(), block.isLiquid());
        }
    }

    private static boolean sameLevel(IDhApiLevelWrapper a, IDhApiLevelWrapper b) {
        return a == b || a.getDimensionName().equals(b.getDimensionName());
    }

    /** The API's level for the client's dimension: the single-player level, or a loaded one of its name. */
    private @Nullable IDhApiLevelWrapper find(ClientLevel client) {
        IDhApiWorldProxy proxy = DhApi.Delayed.worldProxy;
        if (proxy == null || !proxy.worldLoaded()) return null;
        String dimension = client.dimension().identifier().toString();
        try {
            IDhApiLevelWrapper single = proxy.getSinglePlayerLevel();
            if (single != null && matches(single, dimension)) return single;
        } catch (IllegalStateException multiplayer) {
            // Joined to a server: only the loaded levels are there.
        }
        StringBuilder names = new StringBuilder();
        for (IDhApiLevelWrapper wrapper : proxy.getAllLoadedLevelWrappers()) {
            if (matches(wrapper, dimension)) return wrapper;
            names.append(' ').append(wrapper.getDimensionName());
        }
        if (!unmatchedLogged) {
            unmatchedLogged = true;
            LOGGER.warn("No Distant Horizons level matches {}; loaded:{}", dimension, names);
        }
        return null;
    }

    private static boolean matches(IDhApiLevelWrapper wrapper, String dimension) {
        String name = wrapper.getDimensionName();
        return name != null && (name.equals(dimension) || dimension.endsWith(":" + name) || name.endsWith(dimension));
    }
}

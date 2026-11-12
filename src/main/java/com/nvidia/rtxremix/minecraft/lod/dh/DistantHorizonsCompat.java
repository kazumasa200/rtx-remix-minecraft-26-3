/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiLodShading;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeDeferredRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockStateWrapperCreatedEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.nvidia.rtxremix.minecraft.lod.LodTiles;
import com.nvidia.rtxremix.minecraft.lod.dh.mixin.BlockTextureRegistryAccessor;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockTextureRegistry;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Distant Horizons API calls the LOD capture needs. Only ever loaded with
 * Distant Horizons installed, so nothing else in the mod names its types.
 */
public final class DistantHorizonsCompat {

    /** Shown in Distant Horizons' menu beside the options this mod overrides. */
    private static final String OWNER = "RTX Remix";

    private static volatile boolean capturing;
    private static boolean eventsBound;
    private static IDhApiConfigValue<Integer> renderDistance;
    /** Counts the times Distant Horizons has dropped its tiles and numbers them anew. */
    private static volatile int tileGeneration;
    /** Set by a resource reload: the tiles Distant Horizons holds were cut from the old packs. */
    private static boolean tilesStale;

    private DistantHorizonsCompat() {}

    /**
     * Render thread. While capturing, Distant Horizons stops drawing (Remix
     * draws its LODs) and stops baking face shading into their colours (Remix
     * lights them). Either way it rebuilds every section, which is how a
     * capture that starts late receives the sections already built.
     */
    public static void setCapturing(boolean on) {
        capturing = on;
        bindEvents();
        IDhApiConfig configs = DhApi.Delayed.configs;
        if (configs != null) {
            IDhApiConfigValue<EDhApiLodShading> shading = configs.graphics().lodShading();
            if (on) {
                shading.setValue(EDhApiLodShading.DISABLED, OWNER);
            } else {
                shading.clearValue();
            }
        }
        if (on && tilesStale) {
            tilesStale = false;
            BlockTextureRegistry.INSTANCE.clear();
        }
        if (DhApi.Delayed.renderProxy != null) {
            DhApi.Delayed.renderProxy.clearRenderDataCache();
        }
    }

    /**
     * Render thread, after a resource reload. Distant Horizons cuts a block face's tile when
     * it first meets the block and drops its tiles only when a world loads, so a pack chosen
     * in a world kept the old pack's textures on the LODs until the world was entered again
     * (#25). While capturing, its tiles are dropped and every section rebuilt at once;
     * otherwise that waits for the capture to start, since Distant Horizons draws its own
     * LODs meanwhile.
     */
    public static void reloadTiles() {
        tilesStale = true;
        if (capturing) setCapturing(true);
    }

    /** Called where Distant Horizons drops its tiles, on any thread. */
    public static void tilesCleared() {
        tileGeneration++;
    }

    /**
     * Render thread, before a world loads: Distant Horizons caches a block state's
     * material when it first meets the state, so the material override must be
     * bound before then.
     */
    public static void bindEvents() {
        if (eventsBound) return;
        eventsBound = true;
        DhApi.events.bind(DhApiBeforeRenderEvent.class, new CancelRender());
        DhApi.events.bind(DhApiBeforeDeferredRenderEvent.class, new CancelDeferredRender());
        DhApi.events.bind(DhApiBlockStateWrapperCreatedEvent.class, new LitBlocksIlluminated());
    }

    /** Any thread. Whether Remix draws the LODs in place of Distant Horizons. */
    public static boolean capturing() {
        return capturing;
    }

    /** Distant Horizons' LOD render radius in chunks, or 0 before it has started. */
    public static int lodRadiusChunks() {
        if (renderDistance == null) {
            IDhApiConfig configs = DhApi.Delayed.configs;
            if (configs == null) return 0;
            // Cached: the config getter builds a new wrapper per call.
            renderDistance = configs.graphics().chunkRenderDistance();
        }
        Integer chunks = renderDistance.getValue();
        return chunks != null ? chunks : 0;
    }

    /** The LOD mod's block-face tiles. Its registry hands them out under its own lock. */
    public static final LodTiles.Source TILES = new LodTiles.Source() {
        @Override
        public int tileCount() {
            return BlockTextureRegistry.INSTANCE.getTileCount();
        }

        @Override
        public int generation() {
            return tileGeneration;
        }

        @Override
        public int tiles(int first, byte[][] out) {
            BlockTextureRegistry registry = BlockTextureRegistry.INSTANCE;
            synchronized (registry) {
                java.util.List<byte[]> tiles = ((BlockTextureRegistryAccessor) (Object) registry).rtxremix$tilePixels();
                int n = Math.max(0, Math.min(out.length, tiles.size() - first));
                for (int i = 0; i < n; i++) out[i] = tiles.get(first + i);
                return n;
            }
        }
    };

    private static final class CancelRender extends DhApiBeforeRenderEvent {
        @Override
        public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
            if (capturing) event.cancelEvent();
        }
    }

    /**
     * Distant Horizons names a block's material by its id before it asks whether
     * the block gives off light, so glowstone and lit redstone lamps, whose ids
     * hold "stone", come out as stone and their LODs do not glow. Any block that
     * gives off light, lava and water aside, is marked illuminated instead, the
     * material Distant Horizons gives every other light source; it draws those at
     * full brightness itself too.
     */
    private static final class LitBlocksIlluminated extends DhApiBlockStateWrapperCreatedEvent {
        @Override
        public void blockStateWrapperCreated(DhApiEventParam<EventParam> event) {
            EventParam param = event.value;
            var wrapper = param.getBlockStateWrapper();
            if (wrapper.isLiquid()) return;
            if (wrapper.getWrappedMcObject() instanceof BlockState state && state.getLightEmission() > 0
                    && wrapper.getMaterialId() != EDhApiBlockMaterial.ILLUMINATED.index) {
                param.setBlockMaterial(EDhApiBlockMaterial.ILLUMINATED);
            }
        }
    }

    private static final class CancelDeferredRender extends DhApiBeforeDeferredRenderEvent {
        @Override
        public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
            if (capturing) event.cancelEvent();
        }
    }
}

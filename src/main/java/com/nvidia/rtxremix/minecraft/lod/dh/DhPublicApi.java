/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockStateWrapperCreatedEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What both LOD terrain paths take from Distant Horizons' public API, which names nothing of its
 * internals, so it loads against any release of that API's major version.
 */
public final class DhPublicApi {

    /** The major version of the API this is compiled against; another may have changed its types. */
    static final int API_MAJOR = 7;

    private static IDhApiConfigValue<Integer> renderDistance;
    private static boolean litBlocksBound;

    private DhPublicApi() {}

    /** Whether the installed Distant Horizons offers the API this was compiled against. */
    public static boolean compatible() {
        try {
            return DhApi.getApiMajorVersion() == API_MAJOR;
        } catch (LinkageError e) {
            return false;
        }
    }

    /** The installed release's version, for log lines. */
    static String describe() {
        try {
            return DhApi.getModVersion() + " (API " + DhApi.getApiMajorVersion() + "." + DhApi.getApiMinorVersion() + ")";
        } catch (LinkageError e) {
            return "unknown";
        }
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

    /**
     * Render thread, before a world loads: Distant Horizons caches a block state's material when
     * it first meets the state, so the override must be bound before then.
     */
    public static void bindLitBlocks() {
        if (litBlocksBound) return;
        litBlocksBound = true;
        DhApi.events.bind(DhApiBlockStateWrapperCreatedEvent.class, new LitBlocksIlluminated());
    }

    /**
     * Distant Horizons names a block's material by its id before it asks whether the block gives
     * off light, so glowstone and lit redstone lamps, whose ids hold "stone", come out as stone
     * and their LODs do not glow. Any block that gives off light, lava and water aside, is marked
     * illuminated instead, the material Distant Horizons gives every other light source; it draws
     * those at full brightness itself too.
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
}

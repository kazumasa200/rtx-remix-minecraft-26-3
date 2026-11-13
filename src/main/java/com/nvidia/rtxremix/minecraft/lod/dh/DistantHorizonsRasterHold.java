/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeDeferredRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;

/**
 * Stops a Distant Horizons release the capture does not support from drawing while Remix
 * draws the world.
 *
 * <p>Its LODs are raster draws into the main target, which is laid over the Remix image
 * ({@code ModOverlayLayer}), so left alone they covered the path-traced world with flat
 * terrain. Only Distant Horizons' public API is named here, never its internals, so this
 * class loads against any release. The API has no switch for its fade of vanilla's terrain,
 * which still ran and drew black wherever the main target's depth held land; that one is
 * stopped by {@code UntestedFadeMixin}, which is allowed to miss. Turning its rendering off
 * through {@code renderingEnabled} is no way out: that write lands in its saved config.
 */
public final class DistantHorizonsRasterHold {

    private static volatile boolean held;
    private static boolean bound;

    private DistantHorizonsRasterHold() {}

    /** Any thread. Whether Distant Horizons' own draws are being held back. */
    public static boolean held() {
        return held;
    }

    /** Render thread. Whether Remix draws the world this frame. */
    public static void set(boolean remixDraws) {
        held = remixDraws;
        if (bound) return;
        bound = true;
        DhApi.events.bind(DhApiBeforeRenderEvent.class, new DhApiBeforeRenderEvent() {
            @Override
            public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
                if (held) event.cancelEvent();
            }
        });
        DhApi.events.bind(DhApiBeforeDeferredRenderEvent.class, new DhApiBeforeDeferredRenderEvent() {
            @Override
            public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
                if (held) event.cancelEvent();
            }
        });
    }
}

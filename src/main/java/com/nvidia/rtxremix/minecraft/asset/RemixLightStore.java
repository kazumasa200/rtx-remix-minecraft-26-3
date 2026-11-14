/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

import com.nvidia.rtxremix.LightInfo;
import com.nvidia.rtxremix.RemixAPI;

/**
 * Singleton store for {@link RemixLight} handles. Created and destroyed on the
 * render thread by the light manager, which submits the scene's lights each
 * frame; {@link #update} rewrites a light in place so the runtime's ReSTIR
 * reservoirs keep tracking it as the same source.
 */
public class RemixLightStore extends RemixAssetStore<RemixLight> {

    private static RemixLightStore INSTANCE;

    private RemixLightStore(RemixAPI api) { super(api); }

    public static void init(RemixAPI api) { INSTANCE = new RemixLightStore(api); }

    public static RemixLightStore get() { return INSTANCE; }

    /**
     * Updates an existing light's properties in-place (same hash), so Remix's
     * ReSTIR keeps tracking it as the same light source.
     */
    public void update(RemixLight light, LightInfo newInfo) {
        light.update(api, newInfo);
    }

    /** Gives a light registered without properties, or put to sleep, its native light. */
    public void wake(RemixLight light, LightInfo info) {
        light.wake(api, info);
    }

    /** Gives up a light's native light until it is next woken. */
    public void sleep(RemixLight light) {
        light.sleep(api);
    }

    public static void shutdown() {
        if (INSTANCE == null) return;
        INSTANCE.destroyAll();
        INSTANCE = null;
    }

    @Override
    protected void destroyLeakedHandle(RemixAPI api, long handle) {
        api.destroyLight(handle);
    }
}

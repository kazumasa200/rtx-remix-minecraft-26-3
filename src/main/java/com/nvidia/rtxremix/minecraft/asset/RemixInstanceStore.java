/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

import com.nvidia.rtxremix.RemixAPI;

/**
 * Singleton store for retained {@link RemixInstance} scene handles.
 */
public class RemixInstanceStore extends RemixAssetStore<RemixInstance> {

    private static RemixInstanceStore INSTANCE;

    private RemixInstanceStore(RemixAPI api) { super(api); }

    public static void init(RemixAPI api) { INSTANCE = new RemixInstanceStore(api); }

    public static RemixInstanceStore get() { return INSTANCE; }

    public static void shutdown() {
        if (INSTANCE == null) return;
        INSTANCE.destroyAll();
        INSTANCE = null;
    }

    private long syncedEpoch;

    /**
     * Re-sends, the frame the scene origin moved, every instance in the scene that was placed
     * with {@link RemixInstance#setWorldTranslation}: the camera moves with the origin, so an
     * instance left behind would show thousands of blocks off. That covers staged and retiring
     * terrain pages as well as active ones, since all live here. An instance out of the scene
     * follows when it is next submitted. Render thread. Returns whether the origin had moved.
     */
    public boolean syncToOrigin() {
        long epoch = SceneSpace.epoch();
        if (epoch == syncedEpoch) return false;
        for (RemixInstance instance : assets.values()) {
            if (instance.isDeletedFast() || !instance.inScene() || !instance.behindOrigin(epoch)) continue;
            RemixMesh mesh = instance.getMesh();
            // A retiring page whose mesh went first is about to be removed; it cannot be re-sent.
            if (mesh == null || mesh.isDeletedFast()) continue;
            instance.drawFast(api);
        }
        // Only after the whole walk: a native failure part way leaves the rest for the next frame.
        syncedEpoch = epoch;
        return true;
    }

    @Override
    protected void destroyLeakedHandle(RemixAPI api, long handle) {
        if (handle != RemixInstance.INACTIVE_HANDLE) {
            api.sceneInstanceRemove(handle);
        }
    }
}

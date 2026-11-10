/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

import com.nvidia.rtxremix.LightInfo;
import com.nvidia.rtxremix.RemixAPI;
import org.slf4j.LoggerFactory;

import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Wraps a single Remix light resource (sphere, rect, distant, dome).
 *
 * <p>Create via {@link RemixLightStore#create(RemixAssetBase)}.
 * Destroy via the public {@link #destroy()} method.
 */
public class RemixLight extends RemixAssetBase {

    private LightInfo lightInfo;
    private final List<ByteBuffer> nativeBuffers = new ArrayList<>();

    public RemixLight(LightInfo lightInfo, List<ByteBuffer> nativeBuffers) {
        this.lightInfo = lightInfo;
        if (nativeBuffers != null) this.nativeBuffers.addAll(nativeBuffers);
    }

    /**
     * Submits this light for drawing in the current frame.
     * Safe to call on deleted or not-yet-created lights (no-op).
     */
    public void draw(RemixAPI api) {
        rwLock.readLock().lock();
        try {
            if (handle != 0 && deletionFlag == DeletionReason.NOT_DELETED)
                api.drawLightInstance(handle);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Lock-free draw for render-thread-only hot paths.
     */
    public void drawFast(RemixAPI api) {
        if (handle != 0 && deletionFlag == DeletionReason.NOT_DELETED)
            api.drawLightInstance(handle);
    }

    /**
     * Whether the light is registered but holds no native light for now: one created
     * without properties, or put to {@link #sleep}. Render thread.
     */
    public boolean isAsleepFast() {
        return handle == 0 && deletionFlag == DeletionReason.NOT_DELETED;
    }

    /**
     * Gives a sleeping light its native light, under the hash it has had since it was
     * registered, so ReSTIR takes it for the same source each time it wakes.
     */
    void wake(RemixAPI api, LightInfo info) {
        rwLock.writeLock().lock();
        try {
            if (handle != 0 || deletionFlag != DeletionReason.NOT_DELETED) return;
            info.hash = this.hash;
            replaceNativeHandle(api.createLight(info));
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Gives up the native light and keeps the registration. The runtime walks every
     * light that exists each frame, drawn or not: 290,000 undrawn ones in a city cost
     * it some 18 ms a frame.
     */
    void sleep(RemixAPI api) {
        rwLock.writeLock().lock();
        try {
            if (handle == 0 || deletionFlag != DeletionReason.NOT_DELETED) return;
            // Destroyed first: a failure leaves the handle in place for the next attempt.
            api.destroyLight(handle);
            replaceNativeHandle(0);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Replaces this light's properties while preserving its hash, so Remix's
     * ReSTIR can track it as the same light source across frames. A sleeping
     * light is left alone: it takes its properties when it wakes.
     */
    void update(RemixAPI api, LightInfo newInfo) {
        rwLock.writeLock().lock();
        try {
            if (deletionFlag != DeletionReason.NOT_DELETED)
                throw new IllegalStateException("Cannot update a light that is not alive");
            if (handle == 0) return;
            newInfo.hash = this.hash;
            long oldHandle = handle;
            long newHandle = api.createLight(newInfo);
            if (oldHandle != 0 && oldHandle != newHandle) {
                try {
                    api.destroyLight(oldHandle);
                } catch (RuntimeException e) {
                    // The runtime may already have replaced the same-hash light
                    // inside CreateLight, so a failed destroy is not fatal: keep
                    // the new handle and log, because repeated failures here mean
                    // handles are being retained natively.
                    LoggerFactory.getLogger(RemixLight.class)
                            .warn("Failed to destroy replaced light handle 0x{} for hash 0x{}: {}",
                                    Long.toHexString(oldHandle), Long.toHexString(hash), e.getMessage());
                }
            }
            replaceNativeHandle(newHandle);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    protected long doCreate(RemixAPI api, long hash) {
        // Registered without properties, the light sleeps until wake() gives it both.
        if (lightInfo == null) return 0;
        lightInfo.hash = hash;
        long result = api.createLight(lightInfo);
        Reference.reachabilityFence(nativeBuffers);
        Reference.reachabilityFence(lightInfo);
        return result;
    }

    @Override
    protected void doDestroy(RemixAPI api, long handle) {
        api.destroyLight(handle);
    }

    @Override
    protected void doDisposeData() {
        nativeBuffers.clear();
        lightInfo = null;
    }

    @Override
    protected String typeName() {
        return "RemixLight";
    }
}

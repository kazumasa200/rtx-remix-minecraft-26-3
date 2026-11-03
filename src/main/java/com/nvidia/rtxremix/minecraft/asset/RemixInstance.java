/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixBridge;
import com.nvidia.rtxremix.Transform;

/**
 * Wraps a renderable mesh instance: a reference to a {@link RemixMesh}, a
 * transform, category flags, and an optional blend pNext extension.
 *
 * <p>Visible instances own retained native scene handles. Culling removes the
 * handle while keeping the Java instance and mesh ready for later visibility.
 *
 * <p>Create via {@link RemixInstanceStore#create(RemixAssetBase)}.
 * Destroy via the public {@link #destroy()} method.
 */
public class RemixInstance extends RemixAssetBase {

    /** Non-null sentinel used while a live Java instance is culled from the scene. */
    static final long INACTIVE_HANDLE = -1L;

    private RemixMesh mesh;
    private Transform transform = Transform.identity();
    private int categoryFlags;
    private InstanceInfoBlendEXT blendExt;
    /**
     * C-heap copy of {@link #blendExt}, marshalled on the first submit after the
     * extension is set and passed as the pNext chain of every add and update until
     * the extension changes or the instance is destroyed. The runtime reads the
     * chain during the call, so one copy serves every submission. The copy follows
     * object identity: mutating an extension after setting it is not observed, so
     * callers swap extension objects to change blend state. This instance owns the
     * pointer it was handed; it never reads the extension's own {@code nativePtr}
     * cache, which shared extension objects overwrite for one another.
     */
    private long blendExtNative;
    private boolean doubleSided;

    /** Whether the retained native description needs a complete replacement. */
    private boolean sceneInfoDirty = true;

    /** Set by {@link #setWorldTranslation}: the instance then keeps its own transform in step with the scene origin. */
    private boolean anchored;
    private double anchorX;
    private double anchorY;
    private double anchorZ;
    private long anchorEpoch;

    public RemixInstance(RemixMesh mesh) {
        this.mesh = mesh;
    }

    /** Replaces the mesh referenced by this retained scene instance. */
    public RemixInstance setMesh(RemixMesh mesh) {
        if (this.mesh == mesh) return this;
        this.mesh = mesh;
        this.sceneInfoDirty = true;
        return this;
    }

    public RemixInstance setTransform(Transform transform) {
        if (anchored) {
            throw new IllegalStateException("An instance placed with setWorldTranslation keeps its own transform");
        }
        this.transform = transform;
        this.sceneInfoDirty = true;
        return this;
    }

    /**
     * Places the instance at a world position, unrotated and unscaled. It writes its own transform
     * in scene space ({@link SceneSpace}) and writes it again on the first submit after the origin
     * moves, so a retained instance never needs its owner to know about the origin.
     */
    public RemixInstance setWorldTranslation(double x, double y, double z) {
        if (!anchored) {
            transform = Transform.identity();
            anchored = true;
        }
        anchorX = x;
        anchorY = y;
        anchorZ = z;
        writeAnchor(SceneSpace.current());
        return this;
    }

    /** Whether this instance must follow an origin it has not followed yet. */
    boolean behindOrigin(long epoch) {
        return anchored && anchorEpoch != epoch;
    }

    /** Whether the instance has a native scene handle. */
    boolean inScene() {
        return handle != 0 && handle != INACTIVE_HANDLE;
    }

    private void writeAnchor(SceneSpace.Origin origin) {
        transform.setTranslation(origin.x(anchorX), origin.y(anchorY), origin.z(anchorZ));
        anchorEpoch = origin.epoch();
        sceneInfoDirty = true;
    }

    public RemixInstance setCategoryFlags(int flags) {
        if (this.categoryFlags == flags) return this;
        this.categoryFlags = flags;
        this.sceneInfoDirty = true;
        return this;
    }

    public RemixInstance setBlendExt(InstanceInfoBlendEXT ext) {
        if (this.blendExt == ext) return this;
        this.blendExt = ext;
        this.sceneInfoDirty = true;
        freeBlendExtNative();
        return this;
    }

    public RemixInstance setDoubleSided(boolean ds) {
        if (this.doubleSided == ds) return this;
        this.doubleSided = ds;
        this.sceneInfoDirty = true;
        return this;
    }

    public RemixMesh getMesh() {
        return mesh;
    }

    /**
     * Retained-scene submission for render-thread-only hot paths. Unchanged visible
     * instances require no native call.
     */
    public void drawFast(RemixAPI api) {
        submitRetained(api);
    }

    /**
     * Removes this instance from the scene, keeping the Java object and its mesh so a later
     * submit can restore it. The handle is already gone when it is 0 (never created or
     * destroyed) or INACTIVE_HANDLE (already removed).
     */
    public void hideFromScene(RemixAPI api) {
        if (handle != 0 && handle != INACTIVE_HANDLE) {
            api.sceneInstanceRemove(handle);
            replaceNativeHandle(INACTIVE_HANDLE);
        }
    }

    private void submitRetained(RemixAPI api) {
        if (mesh == null || mesh.isDeletedFast()) {
            throw new IllegalStateException("Trying to submit retained RemixInstance(hash=0x"
                    + Long.toHexString(hash) + ") with no live mesh");
        }
        SceneSpace.Origin origin = SceneSpace.current();
        if (anchored && anchorEpoch != origin.epoch()) writeAnchor(origin);

        if (handle == INACTIVE_HANDLE) {
            replaceNativeHandle(addSceneInstance(api));
            sceneInfoDirty = false;
        } else if (sceneInfoDirty) {
            updateSceneInstance(api, handle);
            sceneInfoDirty = false;
        }
    }

    private long addSceneInstance(RemixAPI api) {
        return api.sceneInstanceAdd(mesh.getHandleFast(), transform.matrix,
                categoryFlags, doubleSided ? 1 : 0, pNextChain());
    }

    private void updateSceneInstance(RemixAPI api, long sceneHandle) {
        api.sceneInstanceUpdate(sceneHandle, mesh.getHandleFast(), transform.matrix,
                categoryFlags, doubleSided ? 1 : 0, pNextChain());
    }

    private long pNextChain() {
        if (blendExt == null) return 0;
        if (blendExtNative == 0) blendExtNative = blendExt.allocNative(0);
        return blendExtNative;
    }

    private void freeBlendExtNative() {
        if (blendExtNative == 0) return;
        RemixBridge.freeExt(blendExtNative);
        blendExtNative = 0;
    }

    @Override
    protected long doCreate(RemixAPI api, long hash) {
        return INACTIVE_HANDLE;
    }

    @Override
    protected void doDestroy(RemixAPI api, long handle) {
        if (handle != INACTIVE_HANDLE) {
            api.sceneInstanceRemove(handle);
        }
        freeBlendExtNative();
    }

    @Override
    protected void doDisposeData() { }

    @Override
    protected String typeName() {
        return "RemixInstance";
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import net.minecraft.client.Camera;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * The view while another mod has made the game's projection orthographic (#34). The runtime
 * casts every primary ray from one point, so it cannot draw a parallel projection; a camera far
 * behind the game's, with a field of view that spans the same extent at the game camera's plane,
 * comes near it: what lies {@code z} blocks beyond that plane draws {@code distance / (distance + z)}
 * of its size.
 */
public final class OrthographicView {
    /**
     * How far back the Remix camera stands for each block of the view's half height: across a
     * depth of that half height, sizes then differ by under a hundredth.
     */
    static final float DISTANCE_PER_HALF_HEIGHT = 128.0f;
    /** Views taller than 128 blocks keep this distance and show more of the perspective. */
    static final float MAX_DISTANCE = 8192.0f;

    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f rotation = new Matrix4f();
    private boolean active;
    private float halfWidth;
    private float halfHeight;
    private float centerX;
    private float centerY;
    private float distance;

    /** Reads the projection the game renders with; the camera's own rotation is taken back out of it. */
    public void update(Camera camera) {
        camera.getViewRotationProjectionMatrix(projection);
        camera.getViewRotationMatrix(rotation);
        set(projection.mul(rotation.transpose()));
    }

    void set(Matrix4fc projection) {
        // A perspective projection's last row is (0, 0, -1, 0), a parallel one's (0, 0, 0, 1).
        active = Math.abs(projection.m33()) > 0.5f && Math.abs(projection.m23()) < 0.5f
                && projection.m00() != 0.0f && projection.m11() != 0.0f;
        if (!active) {
            return;
        }
        halfWidth = 1.0f / Math.abs(projection.m00());
        halfHeight = 1.0f / Math.abs(projection.m11());
        centerX = -projection.m30() / projection.m00();
        centerY = -projection.m31() / projection.m11();
        distance = Math.min(DISTANCE_PER_HALF_HEIGHT * halfHeight, MAX_DISTANCE);
    }

    public boolean active() {
        return active;
    }

    /** How far behind the game's camera the Remix camera stands, in blocks; 0 while not active. */
    public float distance() {
        return active ? distance : 0.0f;
    }

    /** Where the view's centre lies to the right of the game's camera, in blocks; 0 while not active. */
    public float centerX() {
        return active ? centerX : 0.0f;
    }

    /** Where the view's centre lies above the game's camera, in blocks; 0 while not active. */
    public float centerY() {
        return active ? centerY : 0.0f;
    }

    public float fovYDegrees() {
        return (float) Math.toDegrees(2.0 * Math.atan2(halfHeight, distance));
    }

    public float aspect() {
        return halfWidth / halfHeight;
    }

    /**
     * The Remix camera's near plane, half way to the game camera's plane: with the usual
     * twentieth of a block, the depth the runtime writes loses its precision that far out.
     */
    public float nearPlane() {
        return distance * 0.5f;
    }
}

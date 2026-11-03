/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import com.nvidia.rtxremix.minecraft.asset.RemixLight;
import com.nvidia.rtxremix.minecraft.asset.SceneSpace;
import com.nvidia.rtxremix.minecraft.lens.FlareSources;
import com.nvidia.rtxremix.minecraft.light.LightEntry;
import com.nvidia.rtxremix.minecraft.light.LightPower;
import org.jetbrains.annotations.Nullable;

/**
 * One terrain block light as the draw loop sees it: the native handle plus
 * the position and power the reach cull needs, which the {@link RemixLight}
 * itself no longer holds after creation.
 *
 * <p>Render-thread only. The drawn-frame stamp is written only when the light
 * is visited, so a light skipped wholesale by the column or section gates
 * needs no per-light write and simply reads as not drawn.
 */
public final class TerrainLight {

    private static final double FOUR_PI = 4.0 * Math.PI;
    /**
     * A light drawn last frame stays drawn until its weight falls to 1/1.5 of
     * the threshold (about 1.22x the reach in distance), so a light sitting on
     * the boundary does not flicker.
     */
    private static final float HYSTERESIS = 1.5f;

    public final RemixLight light;
    /** The rule output the native light was last built from, so its radiance can be re-derived. */
    private @Nullable LightEntry source;
    private float x, y, z;
    private float power;
    private long lastDrawnFrame = Long.MIN_VALUE;
    /** The scene origin the native light was built against; see {@link #behindOrigin}. */
    private long sceneEpoch;

    public TerrainLight(RemixLight light) {
        this.light = light;
    }

    /** Records where the light is and how much it emits; called at create and update. */
    public void place(float x, float y, float z, float power) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.power = power;
        this.sceneEpoch = SceneSpace.epoch();
    }

    /** Whether the scene origin moved since the native light was built, which leaves it misplaced. */
    public boolean behindOrigin() {
        return sceneEpoch != SceneSpace.epoch();
    }

    /** Records the rule output the native light was built from, where it is and how much it emits. */
    public void place(LightEntry source, float power) {
        this.source = source;
        place(source.x(), source.y(), source.z(), power);
    }

    public @Nullable LightEntry source() {
        return source;
    }

    public float power() {
        return power;
    }

    public boolean drawnInFrame(long frame) {
        return lastDrawnFrame == frame;
    }

    /**
     * Whether the light's weight at the camera, power / (4 pi d^2), clears the
     * threshold this frame, applying the hysteresis band when it was drawn
     * the previous frame. Records the decision.
     */
    public boolean shouldDraw(double camX, double camY, double camZ, float threshold, long frame) {
        double dx = x - camX, dy = y - camY, dz = z - camZ;
        double distanceSq = Math.max(1.0, dx * dx + dy * dy + dz * dz);
        double weight = power / (FOUR_PI * distanceSq);
        float floor = lastDrawnFrame == frame - 1 ? threshold / HYSTERESIS : threshold;
        boolean draw = weight >= floor;
        if (draw) lastDrawnFrame = frame;
        return draw;
    }

    /**
     * Whether a light of this power, at least this far from the camera, could be drawn this
     * frame at all, even having been drawn the frame before.
     */
    public static boolean mayDraw(double minDistanceSq, float power, float threshold) {
        return power / (FOUR_PI * Math.max(1.0, minDistanceSq)) >= threshold / HYSTERESIS;
    }

    /** Offers this light to the lens flare, weighted by how brightly it shines at the camera. */
    public void offerFlare(double camX, double camY, double camZ) {
        double dx = x - camX, dy = y - camY, dz = z - camZ;
        FlareSources.offer(x, y, z, power / Math.max(1.0, dx * dx + dy * dy + dz * dz));
    }

    /**
     * Weight threshold for a light reach in blocks: the weight of the shipped
     * torch at that distance. A light of power P is then drawn out to
     * reach x sqrt(P / torch power).
     */
    public static float thresholdFor(int reachBlocks) {
        double reach = Math.max(1, reachBlocks);
        return (float) (LightPower.torchReference() / (FOUR_PI * reach * reach));
    }
}

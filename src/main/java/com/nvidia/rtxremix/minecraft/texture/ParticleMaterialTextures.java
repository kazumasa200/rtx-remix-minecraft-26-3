/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import java.util.Arrays;

/** Borrowed atlas textures; their manager outlives the geometry materials. */
public record ParticleMaterialTextures(RemixTexture albedo, RemixTexture normal, RemixTexture height,
                                      RemixTexture roughness, RemixTexture metallic, RemixTexture emissive,
                                      float authoredEmission, long[] authoredEmissionBounds) {
    public static final int AUTHORED_EMISSION = 16;
    public static long boundsKey(int min, int max) {
        return ((long) min << 32) | Integer.toUnsignedLong(max);
    }

    /** Separate non-emitting sprites from atlas emission, including at coarse mip levels. */
    public int materialBucket(int minimumEmission, int min, int max) {
        return Arrays.binarySearch(authoredEmissionBounds, boundsKey(min, max)) >= 0
                ? AUTHORED_EMISSION : minimumEmission;
    }

    public MaterialInfo material(int bucket) {
        MaterialInfoOpaqueEXT.Builder opaque = MaterialInfoOpaqueEXT.builder()
                .albedoConstant(1, 1, 1).roughnessConstant(1).metallicConstant(0)
                .useDrawCallAlphaState(true);
        if (roughness != null) opaque.roughnessTexture(roughness.getHandle());
        if (metallic != null) opaque.metallicTexture(metallic.getHandle());
        if (height != null) opaque.heightTexture(height.getHandle()).displaceIn(0.02f);
        MaterialInfo.Builder info = MaterialInfo.builder().albedoTexture(albedo.getHandle()).filterMode(0);
        if (normal != null) info.normalTexture(normal.getHandle());
        if (bucket == AUTHORED_EMISSION && emissive != null) {
            info.emissiveTexture(emissive.getHandle()).emissiveIntensity(authoredEmission);
        } else if (bucket > 0 && bucket < AUTHORED_EMISSION) {
            info.emissiveTexture(albedo.getHandle()).emissiveIntensity(bucket / 15.0f);
        }
        return info.opaqueExt(opaque.build()).build();
    }
}

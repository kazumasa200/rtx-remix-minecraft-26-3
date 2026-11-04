/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

/**
 * Primitive-only transfer of already transformed, camera-relative quad vertices
 * in Minecraft's source order. Positions and UVs must be finite; colors are
 * straight packed ARGB. A quad consists of begin, exactly four vertices, then end.
 */
public interface ParticleGeometrySink {
    /** Intrinsic emission is 0..15; bounds are the full sprite UVs packed as half2. */
    void beginQuad(int emission, int boundsMin, int boundsMax);
    /** Preserve authored ARGB; known soot sources can reuse {@link ParticleTint#sootColor}. */
    void vertex(float x, float y, float z, float u, float v, int argb);
    void endQuad();
}

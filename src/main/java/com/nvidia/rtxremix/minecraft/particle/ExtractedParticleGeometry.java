/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

/** An extracted state whose CPU quads can be consumed without rendering it again. */
public interface ExtractedParticleGeometry {
    boolean rtxremix$hasGeometry();
    void rtxremix$captureGeometry(ParticleGeometryRenderer renderer);
}

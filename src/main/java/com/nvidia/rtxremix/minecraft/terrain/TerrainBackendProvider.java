/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

/** Optional replacement for the vanilla terrain rebuild scheduler. */
public interface TerrainBackendProvider {
    WorldRendererTerrainAccess rtxremix$terrainBackend();
}
